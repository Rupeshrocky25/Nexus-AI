package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.ActualInferenceSource
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException

private const val EXEC_TAG = "ResilientAiExecutor"

enum class ExecutorKind {
    MULTIMODAL_OCR,
    CHUNK_MAP,
    HIERARCHICAL_REDUCE,
}

data class AiGenerationOutput(
    val text: String,
    val actualSource: ActualInferenceSource,
    val modelName: String,
    val latencyMs: Long,
)

/**
 * Abstraction over streaming Firebase AI Logic inference calls so production always uses
 * `Firebase.ai` (`generateContentStream`) while unit tests can deterministically inject
 * simulated network drops, HTTP 429/413/500 errors, and model timeouts.
 */
interface DocumentAiExecutor {
    suspend fun streamText(
        prompt: String,
        kind: ExecutorKind,
        timeoutMs: Long,
        onDelta: suspend (delta: String, accumulated: String) -> Unit = { _, _ -> },
    ): AiGenerationOutput

    suspend fun streamStructuredJson(
        prompt: String,
        timeoutMs: Long,
        onDelta: suspend (delta: String, accumulated: String) -> Unit = { _, _ -> },
    ): AiGenerationOutput

    suspend fun streamMultimodalInline(
        bytes: ByteArray,
        mimeType: String,
        prompt: String,
        timeoutMs: Long,
        onDelta: suspend (delta: String, accumulated: String) -> Unit = { _, _ -> },
    ): AiGenerationOutput

    suspend fun streamMultimodalBitmaps(
        bitmaps: List<Bitmap>,
        prompt: String,
        timeoutMs: Long,
        onDelta: suspend (delta: String, accumulated: String) -> Unit = { _, _ -> },
    ): AiGenerationOutput
}

enum class AiErrorKind {
    RATE_LIMIT_429,
    PAYLOAD_TOO_LARGE_413,
    SERVER_TRANSIENT_5XX,
    TIMEOUT,
    NETWORK_INTERRUPTED,
    PERMANENT,
}

object ResilientRetryPolicy {

    fun classifyError(error: Throwable): AiErrorKind {
        if (error is TimeoutCancellationException || error is SocketTimeoutException) {
            return AiErrorKind.TIMEOUT
        }
        val msg = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
            .lowercase()

        return when {
            msg.contains("413") ||
                msg.contains("payload too large") ||
                msg.contains("request entity too large") ||
                msg.contains("token limit") ||
                msg.contains("exceeds the maximum number of tokens") -> AiErrorKind.PAYLOAD_TOO_LARGE_413

            msg.contains("429") ||
                msg.contains("resource_exhausted") ||
                msg.contains("quota") ||
                msg.contains("rate limit") ||
                msg.contains("too many requests") -> AiErrorKind.RATE_LIMIT_429

            msg.contains("500") ||
                msg.contains("502") ||
                msg.contains("503") ||
                msg.contains("504") ||
                msg.contains("unavailable") ||
                msg.contains("overloaded") ||
                msg.contains("internal error") ||
                msg.contains("empty output") ||
                msg.contains("empty response") ||
                error is org.json.JSONException -> AiErrorKind.SERVER_TRANSIENT_5XX

            msg.contains("timeout") || msg.contains("timed out") -> AiErrorKind.TIMEOUT

            error is IOException ||
                msg.contains("network") ||
                msg.contains("connection reset") ||
                msg.contains("connection closed") ||
                msg.contains("unable to resolve host") ||
                msg.contains("eof") ||
                msg.contains("broken pipe") ||
                msg.contains("stream") ||
                msg.contains("socket") ||
                msg.contains("aborted") -> AiErrorKind.NETWORK_INTERRUPTED

            else -> AiErrorKind.PERMANENT
        }
    }

    fun isRetryable(kind: AiErrorKind): Boolean = kind in setOf(
        AiErrorKind.RATE_LIMIT_429,
        AiErrorKind.SERVER_TRANSIENT_5XX,
        AiErrorKind.TIMEOUT,
        AiErrorKind.NETWORK_INTERRUPTED,
    )

    fun friendlyMessage(error: Throwable): String = when (classifyError(error)) {
        AiErrorKind.RATE_LIMIT_429 ->
            "Gemini rate limit (429) reached temporarily. Completed sections are saved — wait a moment and tap Resume or Retry Failed Sections."
        AiErrorKind.PAYLOAD_TOO_LARGE_413 ->
            "Section exceeded single-request payload limits (413) even after automatic splitting."
        AiErrorKind.SERVER_TRANSIENT_5XX ->
            "Gemini service returned a transient error (${error.localizedMessage?.take(120) ?: "5xx"}). Completed sections are saved — tap Resume."
        AiErrorKind.TIMEOUT ->
            "Request timed out while waiting for Gemini response. Completed sections are saved — tap Resume."
        AiErrorKind.NETWORK_INTERRUPTED ->
            "Network connection was interrupted mid-analysis. Completed sections are safely checkpointed — tap Resume."
        AiErrorKind.PERMANENT ->
            error.localizedMessage?.takeIf { it.isNotBlank() } ?: "Document analysis failed."
    }

    suspend fun <T> executeWithBackoff(
        maxAttempts: Int = 3,
        baseDelayMs: Long = 600L,
        onRetry: suspend (attempt: Int, kind: AiErrorKind, delayMs: Long) -> Unit = { _, _, _ -> },
        block: suspend (attempt: Int) -> T,
    ): T {
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
            try {
                return block(attempt)
            } catch (e: TimeoutCancellationException) {
                lastError = e
                if (attempt < maxAttempts) {
                    val waitMs = (baseDelayMs * (1L shl (attempt - 1))).coerceAtMost(5_000L)
                    onRetry(attempt, AiErrorKind.TIMEOUT, waitMs)
                    delay(waitMs)
                }
            } catch (e: CancellationException) {
                // ALWAYS rethrow cooperative coroutine cancellation (user Cancel or ViewModel cleared)
                throw e
            } catch (e: Throwable) {
                lastError = e
                val kind = classifyError(e)
                if (!isRetryable(kind) || attempt >= maxAttempts) {
                    throw e
                }
                val multiplier = if (kind == AiErrorKind.RATE_LIMIT_429) 2L else 1L
                val waitMs = (baseDelayMs * (1L shl (attempt - 1)) * multiplier).coerceAtMost(5_000L)
                onRetry(attempt, kind, waitMs)
                delay(waitMs)
            }
        }
        throw lastError ?: IllegalStateException("Operation failed after $maxAttempts attempts.")
    }
}

/**
 * Production executor backed exclusively by Firebase AI Logic (`Firebase.ai`) and App Check.
 *
 * Speed & Reliability Architecture:
 * - Uses ultra-fast `gemini-3.5-flash-lite` for parallel chunk digests, hierarchical reduction,
 *   and fast OCR/visual extraction (3x-4x lower latency than heavy reasoning models).
 * - Uses `gemini-3.8-flash` (with automatic `gemini-3.5-flash-lite` fallback) for final structured JSON.
 * - Automatically falls back from SSE streaming to unary `generateContent()` whenever a mobile
 *   network or emulator hiccup drops a streaming chunk, eliminating spurious "Analysis Interrupted" errors.
 */
@OptIn(PublicPreviewAPI::class)
class FirebaseDocumentAiExecutor(
    private val cloudModelName: String = HybridAiService.CLOUD_REASONING_MODEL,
    private val fastCloudModelName: String = HybridAiService.LOCAL_FALLBACK_MODEL,
) : DocumentAiExecutor {

    private val fastOcrModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = fastCloudModelName,
            generationConfig = generationConfig {
                temperature = 0.1f
                maxOutputTokens = 4096
            },
            systemInstruction = content {
                text(
                    "You are a fast, precision document OCR and visual-content extraction engine. " +
                        "Extract verbatim text, headings, tables (as Markdown tables), charts/axes/values, and observed visual data " +
                        "strictly from the supplied pages or image. Never invent content, never guess from filenames, " +
                        "and mark illegible regions as [illegible]."
                )
            },
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD)
        )
    }

    private val multimodalOcrFallbackModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = cloudModelName,
            generationConfig = generationConfig {
                temperature = 0.1f
                maxOutputTokens = 4096
            },
            systemInstruction = content {
                text(
                    "You are a precision document OCR and visual-content extraction engine. " +
                        "Extract verbatim text, headings, tables (as Markdown tables), charts/axes/values, and observed visual data " +
                        "strictly from the supplied pages or image. Never invent content, never guess from filenames, " +
                        "and mark illegible regions as [illegible]."
                )
            },
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD)
        )
    }

    private val chunkMapModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = fastCloudModelName,
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = 2048
            },
            systemInstruction = content {
                text(
                    "You analyze one bounded chunk of a larger document with high speed and factual accuracy. " +
                        "Produce a crisp, dense digest of verified facts with exact [Page/Section/Slide/Sheet] citations, " +
                        "topics, definitions, named entities/dates/figures, table/visual findings, conclusions, and explicit action items. " +
                        "Never fabricate content."
                )
            },
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD)
        )
    }

    private val hierarchicalReduceModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = fastCloudModelName,
            generationConfig = generationConfig {
                temperature = 0.15f
                maxOutputTokens = 2560
            },
            systemInstruction = content {
                text(
                    "You consolidate multiple factual chunk digests into a compact, lossless intermediate digest. " +
                        "Preserve every exact [Page/Section/Slide/Sheet] citation, numerical figure, formula, table finding, " +
                        "conclusion, and action item without inventing any new claims."
                )
            },
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD)
        )
    }

    private val structuredSystemInstruction by lazy {
        content {
            text(
                "You are NEXUS AI Document Intelligence. Produce a concise, fast, strictly source-grounded JSON analysis. " +
                    "RULES: (1) Base every claim exclusively on the extracted document content — NEVER invent information " +
                    "or infer content from a filename. (2) Keep 'summary' crisp (2-4 sentences) and 'detailedExplanation' clear and focused. " +
                    "(3) Keep verifiable 'sourceFacts' strictly separate from 'aiInterpretations'. " +
                    "(4) Include exact page, section, slide, row-range, or sheet citations in 'sourceRef'. " +
                    "(5) Return [] for sections not genuinely present in the source."
            )
        }
    }

    private val structuredAnalysisModel by lazy {
        val schema = buildAnalysisResponseSchema()
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = cloudModelName,
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = 8192
                responseMimeType = "application/json"
                responseSchema = schema
            },
            systemInstruction = structuredSystemInstruction,
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD)
        )
    }

    private val fastStructuredAnalysisModel by lazy {
        val schema = buildAnalysisResponseSchema()
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = fastCloudModelName,
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = 8192
                responseMimeType = "application/json"
                responseSchema = schema
            },
            systemInstruction = structuredSystemInstruction,
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD)
        )
    }

    override suspend fun streamText(
        prompt: String,
        kind: ExecutorKind,
        timeoutMs: Long,
        onDelta: suspend (delta: String, accumulated: String) -> Unit,
    ): AiGenerationOutput {
        val (primary, primaryName, fallback, fallbackName) = when (kind) {
            ExecutorKind.MULTIMODAL_OCR -> Quad(fastOcrModel, fastCloudModelName, multimodalOcrFallbackModel, cloudModelName)
            ExecutorKind.CHUNK_MAP -> Quad(chunkMapModel, fastCloudModelName, multimodalOcrFallbackModel, cloudModelName)
            ExecutorKind.HIERARCHICAL_REDUCE -> Quad(hierarchicalReduceModel, fastCloudModelName, multimodalOcrFallbackModel, cloudModelName)
        }
        return collectStreamWithUnaryFallback(
            primaryModel = primary,
            primaryModelName = primaryName,
            fallbackModel = fallback,
            fallbackModelName = fallbackName,
            payload = content { text(prompt) },
            timeoutMs = timeoutMs,
            onDelta = onDelta,
        )
    }

    override suspend fun streamStructuredJson(
        prompt: String,
        timeoutMs: Long,
        onDelta: suspend (delta: String, accumulated: String) -> Unit,
    ): AiGenerationOutput {
        val payload = content { text(prompt) }
        // Unary generateContent() is dramatically more reliable than SSE streaming for strict responseSchema JSON
        // (eliminates mid-stream chunked-transfer drops and truncated JSON objects).
        return runUnaryWithFallback(
            primaryModel = structuredAnalysisModel,
            primaryModelName = cloudModelName,
            fallbackModel = fastStructuredAnalysisModel,
            fallbackModelName = fastCloudModelName,
            payload = payload,
            timeoutMs = timeoutMs,
            onDelta = onDelta,
        )
    }

    override suspend fun streamMultimodalInline(
        bytes: ByteArray,
        mimeType: String,
        prompt: String,
        timeoutMs: Long,
        onDelta: suspend (delta: String, accumulated: String) -> Unit,
    ): AiGenerationOutput {
        val payload = content {
            inlineData(bytes, mimeType)
            text(prompt)
        }
        return collectStreamWithUnaryFallback(
            primaryModel = fastOcrModel,
            primaryModelName = fastCloudModelName,
            fallbackModel = multimodalOcrFallbackModel,
            fallbackModelName = cloudModelName,
            payload = payload,
            timeoutMs = timeoutMs,
            onDelta = onDelta,
        )
    }

    override suspend fun streamMultimodalBitmaps(
        bitmaps: List<Bitmap>,
        prompt: String,
        timeoutMs: Long,
        onDelta: suspend (delta: String, accumulated: String) -> Unit,
    ): AiGenerationOutput {
        val payload = content {
            bitmaps.forEach { image(it) }
            text(prompt)
        }
        return collectStreamWithUnaryFallback(
            primaryModel = fastOcrModel,
            primaryModelName = fastCloudModelName,
            fallbackModel = multimodalOcrFallbackModel,
            fallbackModelName = cloudModelName,
            payload = payload,
            timeoutMs = timeoutMs,
            onDelta = onDelta,
        )
    }

    private data class Quad(
        val primary: GenerativeModel,
        val primaryName: String,
        val fallback: GenerativeModel?,
        val fallbackName: String,
    )

    private suspend fun runUnaryWithFallback(
        primaryModel: GenerativeModel,
        primaryModelName: String,
        fallbackModel: GenerativeModel?,
        fallbackModelName: String,
        payload: com.google.firebase.ai.type.Content,
        timeoutMs: Long,
        onDelta: suspend (delta: String, accumulated: String) -> Unit,
    ): AiGenerationOutput {
        val startMs = System.currentTimeMillis()
        try {
            val response = withTimeout(timeoutMs) {
                primaryModel.generateContent(payload)
            }
            val text = response.text?.trim().orEmpty()
            if (text.isNotEmpty()) {
                onDelta(text, text)
                val observedSource = extractActualSource(response)
                val effectiveModel = if (observedSource == ActualInferenceSource.ON_DEVICE) "gemini-nano" else primaryModelName
                return AiGenerationOutput(
                    text = text,
                    actualSource = observedSource,
                    modelName = effectiveModel,
                    latencyMs = System.currentTimeMillis() - startMs,
                )
            }
            throw IllegalStateException("Primary Gemini model returned empty output.")
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            if (fallbackModel == null) throw e
            Log.w(EXEC_TAG, "Primary unary model ($primaryModelName) timed out; falling back to $fallbackModelName", e)
        } catch (e: Throwable) {
            if (fallbackModel == null || ResilientRetryPolicy.classifyError(e) == AiErrorKind.PAYLOAD_TOO_LARGE_413) {
                throw e
            }
            Log.w(EXEC_TAG, "Primary unary model ($primaryModelName) failed; falling back to $fallbackModelName", e)
        }

        // Fast fallback model (e.g., gemini-3.5-flash-lite)
        val fallbackResponse = withTimeout(timeoutMs) {
            fallbackModel!!.generateContent(payload)
        }
        val fallbackText = fallbackResponse.text?.trim().orEmpty()
        if (fallbackText.isEmpty()) {
            throw IllegalStateException("Gemini fallback model returned empty output.")
        }
        onDelta(fallbackText, fallbackText)
        val observedSource = extractActualSource(fallbackResponse)
        val effectiveModel = if (observedSource == ActualInferenceSource.ON_DEVICE) "gemini-nano" else fallbackModelName
        return AiGenerationOutput(
            text = fallbackText,
            actualSource = observedSource,
            modelName = effectiveModel,
            latencyMs = System.currentTimeMillis() - startMs,
        )
    }

    private suspend fun collectStreamWithUnaryFallback(
        primaryModel: GenerativeModel,
        primaryModelName: String,
        fallbackModel: GenerativeModel?,
        fallbackModelName: String,
        payload: com.google.firebase.ai.type.Content,
        timeoutMs: Long,
        onDelta: suspend (delta: String, accumulated: String) -> Unit,
    ): AiGenerationOutput {
        val startMs = System.currentTimeMillis()
        val accumulated = StringBuilder()
        var observedSource = ActualInferenceSource.UNKNOWN

        try {
            withTimeout(timeoutMs) {
                primaryModel.generateContentStream(payload).collect { response ->
                    val src = extractActualSource(response)
                    if (src != ActualInferenceSource.UNKNOWN) observedSource = src
                    val piece = response.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        onDelta(piece, accumulated.toString())
                    }
                }
            }
            val text = accumulated.toString().trim()
            if (text.isNotEmpty()) {
                val effectiveModel = if (observedSource == ActualInferenceSource.ON_DEVICE) "gemini-nano" else primaryModelName
                return AiGenerationOutput(
                    text = text,
                    actualSource = observedSource,
                    modelName = effectiveModel,
                    latencyMs = System.currentTimeMillis() - startMs,
                )
            }
            Log.w(EXEC_TAG, "Streaming ($primaryModelName) returned empty output; falling back to unary call.")
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Log.w(EXEC_TAG, "Streaming ($primaryModelName) timed out; falling back to unary call.", e)
        } catch (e: Throwable) {
            if (ResilientRetryPolicy.classifyError(e) == AiErrorKind.PAYLOAD_TOO_LARGE_413) throw e
            Log.w(EXEC_TAG, "Streaming ($primaryModelName) interrupted; falling back to unary call.", e)
        }

        // Seamless unary fallback recovers cleanly from SSE chunked-stream interruptions
        return runUnaryWithFallback(
            primaryModel = primaryModel,
            primaryModelName = primaryModelName,
            fallbackModel = fallbackModel,
            fallbackModelName = fallbackModelName,
            payload = payload,
            timeoutMs = timeoutMs,
            onDelta = onDelta,
        )
    }

    private fun extractActualSource(response: GenerateContentResponse): ActualInferenceSource =
        when (response.inferenceSource) {
            InferenceSource.ON_DEVICE -> ActualInferenceSource.ON_DEVICE
            InferenceSource.IN_CLOUD -> ActualInferenceSource.IN_CLOUD
            else -> ActualInferenceSource.UNKNOWN
        }

    companion object {
        fun buildAnalysisResponseSchema(): Schema = Schema.obj(
            properties = mapOf(
                "summary" to Schema.string(description = "Accurate executive summary grounded solely in extracted content."),
                "detailedExplanation" to Schema.string(description = "Thorough, structured walkthrough explaining the actual contents, arguments, structure, and data of the document."),
                "conclusions" to Schema.array(
                    items = Schema.string(description = "Important conclusion directly supported by the document"),
                    description = "Key conclusions and final takeaways (2 to 10 items)."
                ),
                "mainTopics" to Schema.array(
                    items = Schema.string(description = "Core topic or subtopic explicitly covered in the document"),
                    description = "Main topics and subtopics (3 to 15 items)."
                ),
                "sourceFacts" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "fact" to Schema.string(description = "Verifiable fact or finding directly stated in the document"),
                            "sourceRef" to Schema.string(description = "Exact page, section, slide, row range, or sheet citation")
                        )
                    ),
                    description = "Direct, verifiable facts and findings strictly from the source."
                ),
                "aiInterpretations" to Schema.array(
                    items = Schema.string(description = "AI synthesis, implication, or cross-section observation clearly distinct from literal source quotes"),
                    description = "High-level AI interpretations and synthesis."
                ),
                "definitions" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "term" to Schema.string(description = "Key term, acronym, or concept"),
                            "explanation" to Schema.string(description = "Definition or explanation grounded in the document"),
                            "sourceRef" to Schema.string(description = "Page, slide, or section citation")
                        )
                    ),
                    description = "Definitions and terminology explained in the document."
                ),
                "entities" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "name" to Schema.string(description = "Entity name, date, metric, or figure"),
                            "type" to Schema.string(description = "Category: Person, Organization, Date, Metric, Currency, Product, Location, or Standard"),
                            "context" to Schema.string(description = "Role or context in the document"),
                            "sourceRef" to Schema.string(description = "Page, slide, or section citation")
                        )
                    ),
                    description = "Key names, dates, numbers, and entities extracted from the document."
                ),
                "tableInsights" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "title" to Schema.string(description = "Table, spreadsheet, formula, chart, or visual name"),
                            "finding" to Schema.string(description = "Specific numerical, formula, or visual insight from the data"),
                            "sourceRef" to Schema.string(description = "Page, slide, sheet, or row range citation")
                        )
                    ),
                    description = "Insights derived from tables, spreadsheets, formulas, CSVs, charts, or embedded visuals. Return [] if none exist."
                ),
                "actionItems" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "action" to Schema.string(description = "Concrete next step or obligation mentioned in the document"),
                            "priority" to Schema.enumeration(values = listOf("High", "Medium", "Low"), description = "Priority level"),
                            "sourceRef" to Schema.string(description = "Page, slide, or section citation")
                        )
                    ),
                    description = "Action items explicitly present or required by the document. Return [] if none apply."
                ),
                "groundedQa" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "question" to Schema.string(description = "High-value question answered by this document"),
                            "answer" to Schema.string(description = "Accurate answer grounded exclusively in the document"),
                            "sourceRef" to Schema.string(description = "Page, slide, or section citation")
                        )
                    ),
                    description = "3 to 6 helpful Q&A pairs grounded in the extracted document."
                ),
            )
        )
    }
}
