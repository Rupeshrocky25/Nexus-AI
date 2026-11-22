package com.example.data.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.data.model.ActualInferenceSource
import com.example.data.model.ExtractedTask
import com.example.data.model.HybridInferenceMode
import com.example.data.model.HybridInferenceResult
import com.example.data.model.OnDeviceCapabilityStatus
import com.example.data.model.StructuredClassification
import com.example.data.model.StructuredKeywordsResult
import com.example.data.model.StructuredTasksResult
import com.example.data.model.VaultItem
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "HybridAiService"

/**
 * Reusable Firebase AI Logic Hybrid Inference Service for NEXUS AI.
 *
 * Uses official Firebase AI Logic SDK (`Firebase.ai`) with `OnDeviceConfig` and `InferenceMode`
 * (`PREFER_ON_DEVICE`, `ONLY_ON_DEVICE`, `PREFER_IN_CLOUD`, `ONLY_IN_CLOUD`) and captures
 * the truthful `GenerateContentResponse.inferenceSource` reported by the SDK at runtime.
 *
 * Never fabricates local inference and never infers execution source from network state.
 */
@OptIn(PublicPreviewAPI::class)
class HybridAiService {

    companion object {
        // Supported, billing-free Gemini 3.x models via Firebase AI Logic
        const val CLOUD_REASONING_MODEL = "gemini-3.8-flash"
        const val LOCAL_FALLBACK_MODEL = "gemini-3.5-flash-lite"
        private const val INFERENCE_TIMEOUT_MS = 60_000L
        private const val AICORE_PACKAGE = "com.google.android.aicore"
    }

    // Lazy models initialized on first use (never eagerly at class construction)
    private val localPreferredTextModel by lazy {
        createHybridModel(
            modelName = LOCAL_FALLBACK_MODEL,
            mode = HybridInferenceMode.PREFER_ON_DEVICE,
            systemPrompt = "You are NEXUS AI, a concise, privacy-first personal knowledge assistant. Return crisp, accurate answers."
        )
    }

    private val strictLocalTextModel by lazy {
        createHybridModel(
            modelName = LOCAL_FALLBACK_MODEL,
            mode = HybridInferenceMode.ONLY_ON_DEVICE,
            systemPrompt = "You are NEXUS AI running strictly on-device. Return concise, helpful answers."
        )
    }

    private val cloudReasoningModel by lazy {
        createHybridModel(
            modelName = CLOUD_REASONING_MODEL,
            mode = HybridInferenceMode.PREFER_IN_CLOUD,
            systemPrompt = "You are NEXUS AI Cloud Intelligence. Synthesize across the user's Knowledge Vault with clear structure, citations to note titles, and actionable insights."
        )
    }

    private val strictCloudModel by lazy {
        createHybridModel(
            modelName = CLOUD_REASONING_MODEL,
            mode = HybridInferenceMode.ONLY_IN_CLOUD,
            systemPrompt = "You are NEXUS AI Cloud Intelligence. Synthesize across the user's Knowledge Vault with deep analytical reasoning and clear headings."
        )
    }

    private val classificationModel by lazy {
        val schema = Schema.obj(
            properties = mapOf(
                "category" to Schema.enumeration(
                    values = listOf("Ideas", "Notes", "Plans", "Research", "Personal", "Docs", "Links"),
                    description = "Best matching single category for this knowledge item"
                ),
                "tags" to Schema.array(
                    items = Schema.string(description = "Short lowercase keyword tag"),
                    description = "3 to 5 concise topic tags"
                ),
                "confidenceNote" to Schema.string(description = "One-sentence explanation of why this category fits")
            )
        )
        createHybridModel(
            modelName = LOCAL_FALLBACK_MODEL,
            mode = HybridInferenceMode.PREFER_ON_DEVICE,
            config = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema
            }
        )
    }

    private val keywordsModel by lazy {
        val schema = Schema.obj(
            properties = mapOf(
                "keyTheme" to Schema.string(description = "Primary central theme in 3-8 words"),
                "keywords" to Schema.array(
                    items = Schema.string(description = "High-signal keyword or entity"),
                    description = "5 to 8 extracted keywords"
                )
            )
        )
        createHybridModel(
            modelName = LOCAL_FALLBACK_MODEL,
            mode = HybridInferenceMode.PREFER_ON_DEVICE,
            config = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema
            }
        )
    }

    private val tasksModel by lazy {
        val taskSchema = Schema.obj(
            properties = mapOf(
                "title" to Schema.string(description = "Actionable task description"),
                "priority" to Schema.enumeration(
                    values = listOf("High", "Medium", "Low"),
                    description = "Priority level"
                )
            )
        )
        val schema = Schema.obj(
            properties = mapOf(
                "tasks" to Schema.array(
                    items = taskSchema,
                    description = "Actionable next steps extracted from the text"
                )
            )
        )
        createHybridModel(
            modelName = LOCAL_FALLBACK_MODEL,
            mode = HybridInferenceMode.PREFER_ON_DEVICE,
            config = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema
            }
        )
    }

    private fun createHybridModel(
        modelName: String,
        mode: HybridInferenceMode,
        config: com.google.firebase.ai.type.GenerationConfig? = null,
        systemPrompt: String? = null,
    ): GenerativeModel {
        val sdkMode = when (mode) {
            HybridInferenceMode.PREFER_ON_DEVICE -> InferenceMode.PREFER_ON_DEVICE
            HybridInferenceMode.ONLY_ON_DEVICE -> InferenceMode.ONLY_ON_DEVICE
            HybridInferenceMode.PREFER_IN_CLOUD -> InferenceMode.PREFER_IN_CLOUD
            HybridInferenceMode.ONLY_IN_CLOUD -> InferenceMode.ONLY_IN_CLOUD
        }
        return Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            generationConfig = config,
            systemInstruction = systemPrompt?.let { content { text(it) } },
            onDeviceConfig = OnDeviceConfig(mode = sdkMode)
        )
    }

    /**
     * Truthfully probes the Android device environment for AICore / Gemini Nano on-device support.
     * Never claims on-device readiness on emulators or unsupported devices.
     */
    fun probeDeviceCapability(context: Context): OnDeviceCapabilityStatus {
        val modelLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val apiLevel = Build.VERSION.SDK_INT
        val aiCoreInstalled = try {
            context.packageManager.getPackageInfo(AICORE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }

        val onDeviceSdkPresent = try {
            Class.forName("com.google.firebase.ai.ondevice.FirebaseAIOnDevice")
            true
        } catch (_: ClassNotFoundException) {
            false
        }

        val (headline, detail) = when {
            aiCoreInstalled && onDeviceSdkPresent && apiLevel >= 34 -> {
                "Hybrid Ready • On-Device Capable" to
                    "Android AICore detected on $modelLabel (API $apiLevel). Requests configured with PREFER_ON_DEVICE attempt local Gemini Nano first and report exact SDK inferenceSource."
            }
            aiCoreInstalled && !onDeviceSdkPresent -> {
                "Cloud Fallback Active • AICore Detected" to
                    "AICore is present on $modelLabel (API $apiLevel), while runtime hybrid execution falls back to Firebase AI Logic Cloud unless the firebase-ai-ondevice runtime provider is bundled for supported Pixel/Galaxy devices."
            }
            else -> {
                "On-Device Unavailable • Cloud Fallback Ready" to
                    "$modelLabel (API $apiLevel) does not have Android AICore / Gemini Nano provisioned. PREFER_ON_DEVICE requests transparently route to Firebase AI Logic Cloud ($LOCAL_FALLBACK_MODEL / $CLOUD_REASONING_MODEL) and accurately report IN_CLOUD."
            }
        }

        return OnDeviceCapabilityStatus(
            deviceModel = modelLabel,
            androidApiLevel = apiLevel,
            aiCorePackageInstalled = aiCoreInstalled,
            sdkOnDeviceProviderAvailable = onDeviceSdkPresent,
            statusHeadline = headline,
            statusDetail = detail,
        )
    }

    /**
     * Extracts the authentic execution source from the Firebase AI Logic SDK response metadata.
     */
    private fun extractActualSource(response: GenerateContentResponse): ActualInferenceSource {
        return when (response.inferenceSource) {
            InferenceSource.ON_DEVICE -> ActualInferenceSource.ON_DEVICE
            InferenceSource.IN_CLOUD -> ActualInferenceSource.IN_CLOUD
            else -> ActualInferenceSource.UNKNOWN
        }
    }

    private fun modelForMode(mode: HybridInferenceMode): Pair<GenerativeModel, String> = when (mode) {
        HybridInferenceMode.PREFER_ON_DEVICE -> localPreferredTextModel to LOCAL_FALLBACK_MODEL
        HybridInferenceMode.ONLY_ON_DEVICE -> strictLocalTextModel to "gemini-nano"
        HybridInferenceMode.PREFER_IN_CLOUD -> cloudReasoningModel to CLOUD_REASONING_MODEL
        HybridInferenceMode.ONLY_IN_CLOUD -> strictCloudModel to CLOUD_REASONING_MODEL
    }

    suspend fun summarizeNote(
        title: String,
        body: String,
        mode: HybridInferenceMode = HybridInferenceMode.PREFER_ON_DEVICE,
    ): Result<HybridInferenceResult> {
        val prompt = buildString {
            appendLine("Summarize the following note into 2-4 crisp bullet points and a 1-sentence takeaway.")
            appendLine("Title: $title")
            appendLine("Content:")
            appendLine(body.take(12_000))
        }
        return runUnaryInference(prompt, mode)
    }

    suspend fun rewriteText(
        title: String,
        body: String,
        style: String = "clear, concise, executive-ready prose",
        mode: HybridInferenceMode = HybridInferenceMode.PREFER_ON_DEVICE,
    ): Result<HybridInferenceResult> {
        val prompt = buildString {
            appendLine("Rewrite the following content in $style while preserving all key facts and technical accuracy.")
            appendLine("Title: $title")
            appendLine("Content:")
            appendLine(body.take(12_000))
        }
        return runUnaryInference(prompt, mode)
    }

    suspend fun classifyAndTag(
        title: String,
        body: String,
    ): Result<Pair<StructuredClassification, HybridInferenceResult>> {
        val startMs = System.currentTimeMillis()
        return try {
            val prompt = "Classify this knowledge item and generate 3 to 5 concise tags.\nTitle: $title\nContent: ${body.take(8_000)}"
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                classificationModel.generateContent(prompt)
            }
            val elapsed = System.currentTimeMillis() - startMs
            val rawJson = response.text?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalStateException("Model returned an empty classification response."))

            val json = JSONObject(rawJson)
            val category = json.optString("category", "Notes").ifBlank { "Notes" }
            val tagsArr = json.optJSONArray("tags")
            val tags = buildList {
                if (tagsArr != null) {
                    for (i in 0 until tagsArr.length()) {
                        val t = tagsArr.optString(i).trim()
                        if (t.isNotEmpty()) add(t.take(32))
                    }
                }
            }.distinct().take(10)
            val note = json.optString("confidenceNote", "")
            val actualSource = extractActualSource(response)
            val result = HybridInferenceResult(
                text = "Category: $category\nTags: ${tags.joinToString(", ") { "#$it" }}\n$note",
                actualSource = actualSource,
                requestedMode = HybridInferenceMode.PREFER_ON_DEVICE,
                modelName = if (actualSource == ActualInferenceSource.ON_DEVICE) "gemini-nano" else LOCAL_FALLBACK_MODEL,
                latencyMs = elapsed,
                transmittedToCloud = actualSource == ActualInferenceSource.IN_CLOUD,
            )
            Result.success(StructuredClassification(category, tags, note) to result)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Classification timed out", e)
            Result.failure(Exception("Classification timed out after 60 seconds. Please try again.", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed", e)
            Result.failure(Exception(e.localizedMessage ?: "Classification failed.", e))
        }
    }

    suspend fun extractKeywords(
        title: String,
        body: String,
    ): Result<Pair<StructuredKeywordsResult, HybridInferenceResult>> {
        val startMs = System.currentTimeMillis()
        return try {
            val prompt = "Extract the central theme and 5-8 high-signal keywords from this item.\nTitle: $title\nContent: ${body.take(8_000)}"
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                keywordsModel.generateContent(prompt)
            }
            val elapsed = System.currentTimeMillis() - startMs
            val rawJson = response.text?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalStateException("Model returned an empty keywords response."))

            val json = JSONObject(rawJson)
            val keyTheme = json.optString("keyTheme", "General Knowledge")
            val arr = json.optJSONArray("keywords")
            val keywords = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val kw = arr.optString(i).trim()
                        if (kw.isNotEmpty()) add(kw)
                    }
                }
            }
            val actualSource = extractActualSource(response)
            val formatted = "Theme: $keyTheme\nKeywords: ${keywords.joinToString(" • ")}"
            val result = HybridInferenceResult(
                text = formatted,
                actualSource = actualSource,
                requestedMode = HybridInferenceMode.PREFER_ON_DEVICE,
                modelName = if (actualSource == ActualInferenceSource.ON_DEVICE) "gemini-nano" else LOCAL_FALLBACK_MODEL,
                latencyMs = elapsed,
                transmittedToCloud = actualSource == ActualInferenceSource.IN_CLOUD,
            )
            Result.success(StructuredKeywordsResult(keywords, keyTheme) to result)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Keyword extraction timed out", e)
            Result.failure(Exception("Keyword extraction timed out after 60 seconds.", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Keyword extraction failed", e)
            Result.failure(Exception(e.localizedMessage ?: "Keyword extraction failed.", e))
        }
    }

    suspend fun extractTasks(
        title: String,
        body: String,
    ): Result<Pair<StructuredTasksResult, HybridInferenceResult>> {
        val startMs = System.currentTimeMillis()
        return try {
            val prompt = "Extract concrete, actionable tasks with priority levels from this note.\nTitle: $title\nContent: ${body.take(8_000)}"
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                tasksModel.generateContent(prompt)
            }
            val elapsed = System.currentTimeMillis() - startMs
            val rawJson = response.text?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalStateException("Model returned an empty task extraction response."))

            val json = JSONObject(rawJson)
            val arr = json.optJSONArray("tasks")
            val tasks = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val taskTitle = obj.optString("title").trim()
                        val priority = obj.optString("priority", "Medium").ifBlank { "Medium" }
                        if (taskTitle.isNotEmpty()) add(ExtractedTask(taskTitle, priority))
                    }
                }
            }
            val actualSource = extractActualSource(response)
            val formatted = if (tasks.isEmpty()) {
                "No explicit action items detected in this note."
            } else {
                tasks.joinToString("\n") { "[${it.priority}] ${it.title}" }
            }
            val result = HybridInferenceResult(
                text = formatted,
                actualSource = actualSource,
                requestedMode = HybridInferenceMode.PREFER_ON_DEVICE,
                modelName = if (actualSource == ActualInferenceSource.ON_DEVICE) "gemini-nano" else LOCAL_FALLBACK_MODEL,
                latencyMs = elapsed,
                transmittedToCloud = actualSource == ActualInferenceSource.IN_CLOUD,
            )
            Result.success(StructuredTasksResult(tasks) to result)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Task extraction timed out", e)
            Result.failure(Exception("Task extraction timed out after 60 seconds.", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Task extraction failed", e)
            Result.failure(Exception(e.localizedMessage ?: "Task extraction failed.", e))
        }
    }

    private suspend fun runUnaryInference(
        prompt: String,
        mode: HybridInferenceMode,
    ): Result<HybridInferenceResult> {
        val startMs = System.currentTimeMillis()
        val (model, fallbackModelName) = modelForMode(mode)
        return try {
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                model.generateContent(prompt)
            }
            val elapsed = System.currentTimeMillis() - startMs
            val text = response.text?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalStateException("Model returned an empty response."))
            val actualSource = extractActualSource(response)
            val effectiveModel = if (actualSource == ActualInferenceSource.ON_DEVICE) {
                "gemini-nano"
            } else {
                fallbackModelName
            }
            Result.success(
                HybridInferenceResult(
                    text = text,
                    actualSource = actualSource,
                    requestedMode = mode,
                    modelName = effectiveModel,
                    latencyMs = elapsed,
                    transmittedToCloud = actualSource == ActualInferenceSource.IN_CLOUD,
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Hybrid inference timed out", e)
            Result.failure(Exception("AI request timed out after 60 seconds. Please try again.", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Hybrid inference failed in mode=$mode", e)
            val friendly = when {
                mode == HybridInferenceMode.ONLY_ON_DEVICE ->
                    "Strict On-Device mode unavailable on this device: ${e.localizedMessage ?: "Gemini Nano / AICore is not provisioned."}"
                else -> e.localizedMessage ?: "Hybrid AI inference failed."
            }
            Result.failure(Exception(friendly, e))
        }
    }

    data class StreamUpdate(
        val accumulatedText: String,
        val actualSource: ActualInferenceSource,
        val modelName: String,
        val completed: Boolean,
        val latencyMs: Long,
    )

    /**
     * Streams hybrid multi-turn / multi-document responses via Firebase AI Logic `generateContentStream()`
     * and captures the real `chunk.inferenceSource` reported by the SDK.
     */
    fun streamVaultAssistant(
        userPrompt: String,
        contextItems: List<VaultItem>,
        conversationHistory: List<Pair<Boolean, String>>,
        mode: HybridInferenceMode,
    ): Flow<StreamUpdate> = flow {
        val startMs = System.currentTimeMillis()
        val (model, fallbackModelName) = modelForMode(mode)

        val fullPrompt = buildString {
            if (contextItems.isNotEmpty()) {
                appendLine("### Knowledge Vault Context (${contextItems.size} items):")
                contextItems.take(8).forEachIndexed { idx, item ->
                    appendLine("${idx + 1}. [${item.itemType} | ${item.category}] ${item.title}")
                    if (!item.sourceUrl.isNullOrBlank()) appendLine("   URL: ${item.sourceUrl}")
                    if (item.tags.isNotEmpty()) appendLine("   Tags: ${item.tags.joinToString(", ")}")
                    appendLine("   Content: ${item.content.take(2500)}")
                    appendLine()
                }
            }
            if (conversationHistory.isNotEmpty()) {
                appendLine("### Recent Conversation:")
                conversationHistory.takeLast(6).forEach { (fromUser, msg) ->
                    appendLine(if (fromUser) "User: ${msg.take(600)}" else "NEXUS AI: ${msg.take(900)}")
                }
                appendLine()
            }
            appendLine("### Current Request:")
            appendLine(userPrompt)
        }

        val accumulated = StringBuilder()
        var observedSource = ActualInferenceSource.UNKNOWN

        try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                model.generateContentStream(fullPrompt).collect { chunk ->
                    val chunkSource = extractActualSource(chunk)
                    if (chunkSource != ActualInferenceSource.UNKNOWN) {
                        observedSource = chunkSource
                    }
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                    }
                    val effectiveModel = if (observedSource == ActualInferenceSource.ON_DEVICE) {
                        "gemini-nano"
                    } else {
                        fallbackModelName
                    }
                    emit(
                        StreamUpdate(
                            accumulatedText = accumulated.toString(),
                            actualSource = observedSource,
                            modelName = effectiveModel,
                            completed = false,
                            latencyMs = System.currentTimeMillis() - startMs,
                        )
                    )
                }
            }

            if (accumulated.isBlank()) {
                throw IllegalStateException("Model returned an empty response.")
            }

            val effectiveModel = if (observedSource == ActualInferenceSource.ON_DEVICE) {
                "gemini-nano"
            } else {
                fallbackModelName
            }
            emit(
                StreamUpdate(
                    accumulatedText = accumulated.toString(),
                    actualSource = observedSource,
                    modelName = effectiveModel,
                    completed = true,
                    latencyMs = System.currentTimeMillis() - startMs,
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Streaming inference timed out", e)
            throw Exception("Streaming timed out after 60 seconds. Please try again.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Streaming inference failed in mode=$mode", e)
            val msg = if (mode == HybridInferenceMode.ONLY_ON_DEVICE) {
                "Strict On-Device mode is unavailable on this device: ${e.localizedMessage ?: "Gemini Nano is not provisioned."}"
            } else {
                e.localizedMessage ?: "Hybrid AI streaming failed."
            }
            throw Exception(msg, e)
        }
    }
}
