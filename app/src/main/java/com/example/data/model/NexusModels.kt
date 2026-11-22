package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class VaultItemType(val label: String) {
    NOTE("Note"),
    DOCUMENT("Document"),
    LINK("Link");

    companion object {
        fun fromRaw(raw: String?): VaultItemType =
            entries.firstOrNull { it.name == raw } ?: NOTE
    }
}

enum class ExtractionStatus(val label: String) {
    COMPLETE("Extracted"),
    PARTIAL("Partially Extracted"),
    PENDING_OCR("Pending Multimodal OCR"),
    FAILED("Extraction Failed"),
    NOT_APPLICABLE("Direct Content");

    companion object {
        fun fromRaw(raw: String?): ExtractionStatus =
            entries.firstOrNull { it.name == raw } ?: NOT_APPLICABLE
    }
}

enum class ExtractionMethod(val label: String) {
    DIRECT_TEXT("Direct Text Reader"),
    DOCX_XML("DOCX XML Parser"),
    DOCX_MULTIMODAL("DOCX XML + Embedded Visuals"),
    PPTX_XML("PPTX Slide & Visual Parser"),
    XLSX_XML("XLSX Spreadsheet & Formula Parser"),
    CSV_PARSER("CSV Table Parser"),
    PDF_MULTIMODAL_OCR("PDF Multimodal / Page OCR"),
    IMAGE_VISUAL_OCR("Image Visual & OCR"),
    MEDIA_MULTIMODAL("Audio / Video Multimodal"),
    UNKNOWN("Unrecognized Format");

    companion object {
        fun fromRaw(raw: String?): ExtractionMethod =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

enum class HybridInferenceMode(val label: String, val description: String) {
    PREFER_ON_DEVICE(
        label = "Prefer On-Device",
        description = "Runs locally on Gemini Nano when supported; automatically falls back to Firebase AI Logic Cloud."
    ),
    ONLY_ON_DEVICE(
        label = "Strict On-Device",
        description = "Never transmits data off-device; fails transparently if Gemini Nano is unavailable."
    ),
    PREFER_IN_CLOUD(
        label = "Prefer Cloud",
        description = "Uses Gemini 3.8 Flash in the cloud for deep reasoning; falls back locally if supported."
    ),
    ONLY_IN_CLOUD(
        label = "Cloud Only",
        description = "Always executes on Firebase AI Logic Cloud models via App Check."
    );

    companion object {
        fun fromRaw(raw: String?): HybridInferenceMode =
            entries.firstOrNull { it.name == raw } ?: PREFER_ON_DEVICE
    }
}

enum class ActualInferenceSource(val badgeLabel: String) {
    ON_DEVICE("On-Device • Gemini Nano"),
    IN_CLOUD("Cloud • Firebase AI Logic"),
    UNKNOWN("Execution Source Unreported");

    companion object {
        fun fromRaw(raw: String?): ActualInferenceSource =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

enum class AiTaskType(val displayLabel: String) {
    SUMMARIZE("Summarize"),
    CLASSIFY("Classify & Tag"),
    KEYWORDS("Extract Keywords"),
    TASKS("Extract Tasks"),
    REWRITE("Rewrite & Polish"),
    VAULT_QA("Multi-Doc Q&A"),
    RESEARCH("Research Report"),
    CHAT("Hybrid Assistant"),
    DOC_ANALYSIS("Document Analysis"),
    DOC_OCR("Multimodal OCR"),
    DOC_QA("Ask Document");

    companion object {
        fun fromRaw(raw: String?): AiTaskType =
            entries.firstOrNull { it.name == raw } ?: CHAT
    }
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String? = null,
    val defaultInferenceMode: String = HybridInferenceMode.PREFER_ON_DEVICE.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun resolvedMode(): HybridInferenceMode = HybridInferenceMode.fromRaw(defaultInferenceMode)
}

@Keep
@IgnoreExtraProperties
data class VaultItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val itemType: String = VaultItemType.NOTE.name,
    val title: String = "",
    val content: String = "",
    val category: String = "Notes",
    val tags: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val localFilePath: String? = null,
    val extractionStatus: String? = null,
    val extractionMethod: String? = null,
    val pageCount: Long? = null,
    val sizeBytes: Long = 0L,
    val aiSummary: String? = null,
    val pinned: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun resolvedType(): VaultItemType = VaultItemType.fromRaw(itemType)
    fun resolvedExtractionStatus(): ExtractionStatus = ExtractionStatus.fromRaw(extractionStatus)
    fun resolvedExtractionMethod(): ExtractionMethod = ExtractionMethod.fromRaw(extractionMethod)
}

/**
 * A contiguous section/page/sheet/slide extracted verbatim from a document with its source reference label.
 */
data class DocumentSection(
    val index: Int,
    val referenceLabel: String,
    val content: String,
)

/**
 * An embedded raster image extracted from a DOCX (`word/media/` folder) or PPTX (`ppt/media/` folder) archive
 * and persisted in app-private storage for Gemini multimodal visual & OCR analysis.
 */
data class ExtractedEmbeddedMedia(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val localPath: String,
    val referenceLabel: String,
)

/**
 * A bounded text chunk ready for chunked AI analysis and RAG retrieval while preserving provenance.
 */
data class DocumentChunk(
    val chunkIndex: Int,
    val totalChunks: Int,
    val referenceRange: String,
    val text: String,
)

enum class ChunkProcessingStatus(val label: String) {
    PENDING("Pending"),
    RUNNING("Analyzing"),
    COMPLETED("Completed"),
    FAILED("Failed");

    companion object {
        fun fromRaw(raw: String?): ChunkProcessingStatus =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

/**
 * Persistent per-chunk checkpoint saved after each chunk completes or fails so
 * timeouts or network drops never discard prior completed analysis work.
 */
data class ChunkAnalysisCheckpoint(
    val chunkIndex: Int,
    val totalChunks: Int,
    val referenceRange: String,
    val status: ChunkProcessingStatus = ChunkProcessingStatus.PENDING,
    val digestText: String = "",
    val errorMessage: String? = null,
    val attempts: Int = 0,
    val actualSource: ActualInferenceSource = ActualInferenceSource.UNKNOWN,
    val modelName: String = "",
    val latencyMs: Long = 0L,
)

enum class DocumentProcessingPhase(val label: String) {
    IDLE("Ready"),
    UPLOADING("Importing File"),
    EXTRACTING("Extracting Structure"),
    OCR_VISUALS("Multimodal Vision & OCR"),
    ANALYZING_CHUNKS("Streaming Chunk Analysis"),
    CONSOLIDATING("Synthesizing Final Report"),
    COMPLETED("Completed"),
    PARTIAL("Completed with Skipped Sections"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
}

data class SourceFact(
    val fact: String = "",
    val sourceRef: String = "",
)

data class TermDefinition(
    val term: String = "",
    val explanation: String = "",
    val sourceRef: String = "",
)

data class ExtractedEntity(
    val name: String = "",
    val type: String = "",
    val context: String = "",
    val sourceRef: String = "",
)

data class DataInsight(
    val title: String = "",
    val finding: String = "",
    val sourceRef: String = "",
)

data class DocumentActionItem(
    val action: String = "",
    val priority: String = "Medium",
    val sourceRef: String = "",
)

data class GroundedQaPair(
    val question: String = "",
    val answer: String = "",
    val sourceRef: String = "",
)

/**
 * Structured, source-grounded document analysis stored natively in Firestore
 * at `/users/{userId}/document_analyses/{itemId}` using Maps & Lists (no JSON strings).
 */
@Keep
@IgnoreExtraProperties
data class DocumentAnalysis(
    @DocumentId val id: String = "",
    val userId: String = "",
    val itemId: String = "",
    val summary: String = "",
    val detailedExplanation: String = "",
    val conclusions: List<String> = emptyList(),
    val extractionStatus: String = ExtractionStatus.COMPLETE.name,
    val extractionMethod: String = ExtractionMethod.DIRECT_TEXT.name,
    val extractionDisclosure: String = "",
    val chunksProcessed: Long = 1L,
    val totalChunks: Long = 1L,
    val mainTopics: List<String> = emptyList(),
    val sourceFacts: List<SourceFact> = emptyList(),
    val aiInterpretations: List<String> = emptyList(),
    val definitions: List<TermDefinition> = emptyList(),
    val entities: List<ExtractedEntity> = emptyList(),
    val tableInsights: List<DataInsight> = emptyList(),
    val actionItems: List<DocumentActionItem> = emptyList(),
    val groundedQa: List<GroundedQaPair> = emptyList(),
    val extractedPreview: String = "",
    val requestedMode: String = HybridInferenceMode.PREFER_IN_CLOUD.name,
    val actualSource: String = ActualInferenceSource.UNKNOWN.name,
    val modelName: String = "",
    val latencyMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun resolvedExtractionStatus(): ExtractionStatus = ExtractionStatus.fromRaw(extractionStatus)
    fun resolvedExtractionMethod(): ExtractionMethod = ExtractionMethod.fromRaw(extractionMethod)
    fun resolvedRequestedMode(): HybridInferenceMode = HybridInferenceMode.fromRaw(requestedMode)
    fun resolvedActualSource(): ActualInferenceSource = ActualInferenceSource.fromRaw(actualSource)

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromFirestoreMap(docId: String, data: Map<String, Any?>): DocumentAnalysis {
            fun listOfMaps(key: String): List<Map<String, Any?>> =
                (data[key] as? List<*>)?.mapNotNull { it as? Map<String, Any?> }.orEmpty()

            fun listOfStrings(key: String): List<String> =
                (data[key] as? List<*>)?.mapNotNull { it as? String }.orEmpty()

            return DocumentAnalysis(
                id = docId,
                userId = data["userId"] as? String ?: "",
                itemId = data["itemId"] as? String ?: docId,
                summary = data["summary"] as? String ?: "",
                detailedExplanation = data["detailedExplanation"] as? String ?: "",
                conclusions = listOfStrings("conclusions"),
                extractionStatus = data["extractionStatus"] as? String ?: ExtractionStatus.COMPLETE.name,
                extractionMethod = data["extractionMethod"] as? String ?: ExtractionMethod.DIRECT_TEXT.name,
                extractionDisclosure = data["extractionDisclosure"] as? String ?: "",
                chunksProcessed = (data["chunksProcessed"] as? Number)?.toLong() ?: 0L,
                totalChunks = (data["totalChunks"] as? Number)?.toLong() ?: 0L,
                mainTopics = listOfStrings("mainTopics"),
                sourceFacts = listOfMaps("sourceFacts").map {
                    SourceFact(
                        fact = it["fact"] as? String ?: "",
                        sourceRef = it["sourceRef"] as? String ?: "",
                    )
                }.filter { it.fact.isNotBlank() },
                aiInterpretations = listOfStrings("aiInterpretations"),
                definitions = listOfMaps("definitions").map {
                    TermDefinition(
                        term = it["term"] as? String ?: "",
                        explanation = it["explanation"] as? String ?: "",
                        sourceRef = it["sourceRef"] as? String ?: "",
                    )
                }.filter { it.term.isNotBlank() },
                entities = listOfMaps("entities").map {
                    ExtractedEntity(
                        name = it["name"] as? String ?: "",
                        type = it["type"] as? String ?: "",
                        context = it["context"] as? String ?: "",
                        sourceRef = it["sourceRef"] as? String ?: "",
                    )
                }.filter { it.name.isNotBlank() },
                tableInsights = listOfMaps("tableInsights").map {
                    DataInsight(
                        title = it["title"] as? String ?: "",
                        finding = it["finding"] as? String ?: "",
                        sourceRef = it["sourceRef"] as? String ?: "",
                    )
                }.filter { it.finding.isNotBlank() },
                actionItems = listOfMaps("actionItems").map {
                    DocumentActionItem(
                        action = it["action"] as? String ?: "",
                        priority = it["priority"] as? String ?: "Medium",
                        sourceRef = it["sourceRef"] as? String ?: "",
                    )
                }.filter { it.action.isNotBlank() },
                groundedQa = listOfMaps("groundedQa").map {
                    GroundedQaPair(
                        question = it["question"] as? String ?: "",
                        answer = it["answer"] as? String ?: "",
                        sourceRef = it["sourceRef"] as? String ?: "",
                    )
                }.filter { it.question.isNotBlank() },
                extractedPreview = data["extractedPreview"] as? String ?: "",
                requestedMode = data["requestedMode"] as? String ?: HybridInferenceMode.PREFER_IN_CLOUD.name,
                actualSource = data["actualSource"] as? String ?: ActualInferenceSource.UNKNOWN.name,
                modelName = data["modelName"] as? String ?: "",
                latencyMs = (data["latencyMs"] as? Number)?.toLong() ?: 0L,
                createdAt = data["createdAt"] as? Timestamp,
                updatedAt = data["updatedAt"] as? Timestamp,
            )
        }
    }
}

data class DocumentAnalysisJobState(
    val running: Boolean = false,
    val phase: DocumentProcessingPhase = DocumentProcessingPhase.IDLE,
    val stageLabel: String = "",
    val detail: String = "",
    val progressFraction: Float? = null,
    val uploadProgress: Float = 0f,
    val extractionProgress: Float = 0f,
    val sectionsExtracted: Int = 0,
    val totalSections: Int = 0,
    val chunksCompleted: Int = 0,
    val chunksFailed: Int = 0,
    val totalChunks: Int = 0,
    val streamingOutput: String = "",
    val chunkStatuses: List<ChunkAnalysisCheckpoint> = emptyList(),
    val canResume: Boolean = false,
    val canRetryFailed: Boolean = false,
    val errorMessage: String? = null,
)

@Keep
@IgnoreExtraProperties
data class AiAuditLog(
    @DocumentId val id: String = "",
    val userId: String = "",
    val taskType: String = AiTaskType.CHAT.name,
    val promptPreview: String = "",
    val requestedMode: String = HybridInferenceMode.PREFER_ON_DEVICE.name,
    val actualSource: String = ActualInferenceSource.UNKNOWN.name,
    val modelName: String = "",
    val transmittedToCloud: Boolean = false,
    val latencyMs: Long = 0L,
    val succeeded: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
) {
    fun resolvedTask(): AiTaskType = AiTaskType.fromRaw(taskType)
    fun resolvedRequestedMode(): HybridInferenceMode = HybridInferenceMode.fromRaw(requestedMode)
    fun resolvedActualSource(): ActualInferenceSource = ActualInferenceSource.fromRaw(actualSource)
}

data class OnDeviceCapabilityStatus(
    val deviceModel: String,
    val androidApiLevel: Int,
    val aiCorePackageInstalled: Boolean,
    val sdkOnDeviceProviderAvailable: Boolean,
    val statusHeadline: String,
    val statusDetail: String,
)

data class HybridInferenceResult(
    val text: String,
    val actualSource: ActualInferenceSource,
    val requestedMode: HybridInferenceMode,
    val modelName: String,
    val latencyMs: Long,
    val transmittedToCloud: Boolean,
    val note: String? = null,
)

data class StructuredClassification(
    val category: String,
    val tags: List<String>,
    val confidenceNote: String,
)

data class StructuredTasksResult(
    val tasks: List<ExtractedTask>,
)

data class ExtractedTask(
    val title: String,
    val priority: String,
)

data class StructuredKeywordsResult(
    val keywords: List<String>,
    val keyTheme: String,
)

data class AssistantMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val actualSource: ActualInferenceSource? = null,
    val requestedMode: HybridInferenceMode? = null,
    val modelName: String? = null,
    val latencyMs: Long? = null,
    val streaming: Boolean = false,
    val error: Boolean = false,
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
