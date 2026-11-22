package com.example.data.ai

import android.util.Log
import com.example.data.extraction.DocumentAnalysisCheckpointRecord
import com.example.data.extraction.DocumentCheckpointStore
import com.example.data.extraction.DocumentChunker
import com.example.data.extraction.DocumentExtractionEngine
import com.example.data.model.ActualInferenceSource
import com.example.data.model.ChunkAnalysisCheckpoint
import com.example.data.model.ChunkProcessingStatus
import com.example.data.model.DataInsight
import com.example.data.model.DocumentActionItem
import com.example.data.model.DocumentAnalysis
import com.example.data.model.DocumentChunk
import com.example.data.model.DocumentProcessingPhase
import com.example.data.model.DocumentSection
import com.example.data.model.ExtractedEmbeddedMedia
import com.example.data.model.ExtractedEntity
import com.example.data.model.ExtractionMethod
import com.example.data.model.ExtractionStatus
import com.example.data.model.GroundedQaPair
import com.example.data.model.HybridInferenceMode
import com.example.data.model.SourceFact
import com.example.data.model.TermDefinition
import com.example.data.model.VaultItem
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import kotlin.math.min

private const val DOC_AI_TAG = "DocumentIntelligence"

data class MultimodalOcrOutcome(
    val sections: List<DocumentSection>,
    val combinedText: String,
    val status: ExtractionStatus,
    val disclosure: String,
    val actualSource: ActualInferenceSource,
    val requestedMode: HybridInferenceMode,
    val modelName: String,
    val latencyMs: Long,
)

data class EmbeddedVisualsOutcome(
    val visualSections: List<DocumentSection>,
    val succeededCount: Int,
    val failedLabels: List<String>,
    val actualSource: ActualInferenceSource,
    val latencyMs: Long,
)

data class AnalysisProgressUpdate(
    val phase: DocumentProcessingPhase,
    val stageLabel: String,
    val detail: String,
    val progressFraction: Float,
    val streamingPreview: String = "",
    val chunkStatuses: List<ChunkAnalysisCheckpoint> = emptyList(),
)

data class ResilientAnalysisOutcome(
    val analysis: DocumentAnalysis,
    val chunkCheckpoints: List<ChunkAnalysisCheckpoint>,
    val hasFailedChunks: Boolean,
)

/**
 * Resilient, Streaming, Gemini-First Document Intelligence Engine powered by Firebase AI Logic.
 *
 * Key architectural guarantees (fixing DOCX timeouts and incomplete analysis):
 * 1. **Streaming everywhere**: All chunk map passes, embedded image OCR, PDF OCR, hierarchical
 *    reduction, and final structured consolidation use `generateContentStream()` for immediate
 *    token streaming and reliable responsiveness.
 * 2. **Bounded 6KB chunks + automatic HTTP 413 splitting**: Prevents oversized prompts and
 *    single-shot latency timeouts on long DOCX/PDF/PPTX/XLSX files.
 * 3. **Per-chunk persistent checkpoints**: Every completed chunk digest is saved immediately to
 *    disk (`<itemId>_checkpoint.json`). Timeouts, cancellations, or network drops NEVER discard
 *    completed chunks — users can Resume or Retry Failed Sections cleanly.
 * 4. **Exponential backoff retry**: Automatically retries transient 429, 500/503, network drops,
 *    and request timeouts with bounded backoff while preserving cooperative `CancellationException`.
 * 5. **Strict Coverage Honesty**: NEVER reports `ExtractionStatus.COMPLETE` if any chunk, page,
 *    or embedded visual failed or was skipped.
 */
@OptIn(PublicPreviewAPI::class)
class DocumentIntelligenceService(
    private val executor: DocumentAiExecutor = FirebaseDocumentAiExecutor(),
    private val retryBaseDelayMs: Long = 1_200L,
    private val maxRetryAttempts: Int = 3,
    private val chunkTimeoutMs: Long = 60_000L,
    private val synthesisTimeoutMs: Long = 75_000L,
    private val ocrTimeoutMs: Long = 75_000L,
) {

    companion object {
        const val CLOUD_MULTIMODAL_MODEL = HybridAiService.CLOUD_REASONING_MODEL // gemini-3.8-flash
        const val LOCAL_FALLBACK_MODEL = HybridAiService.LOCAL_FALLBACK_MODEL   // gemini-3.5-flash-lite
        private const val INLINE_PDF_MAX_BYTES = 18L * 1024L * 1024L
        private const val INLINE_PDF_MAX_PAGES = 20
        private const val PDF_OCR_BATCH_PAGES = 3
        private const val MAX_DIRECT_SYNTHESIS_CHARS = 24_000
        private const val HIERARCHICAL_REDUCE_BATCH_SIZE = 5
    }

    private fun createDocQaModel(mode: HybridInferenceMode): Pair<GenerativeModel, String> {
        val sdkMode = when (mode) {
            HybridInferenceMode.PREFER_ON_DEVICE -> InferenceMode.PREFER_ON_DEVICE
            HybridInferenceMode.ONLY_ON_DEVICE -> InferenceMode.ONLY_ON_DEVICE
            HybridInferenceMode.PREFER_IN_CLOUD -> InferenceMode.PREFER_IN_CLOUD
            HybridInferenceMode.ONLY_IN_CLOUD -> InferenceMode.ONLY_IN_CLOUD
        }
        val modelName = when (mode) {
            HybridInferenceMode.PREFER_ON_DEVICE -> LOCAL_FALLBACK_MODEL
            HybridInferenceMode.ONLY_ON_DEVICE -> "gemini-nano"
            HybridInferenceMode.PREFER_IN_CLOUD, HybridInferenceMode.ONLY_IN_CLOUD -> CLOUD_MULTIMODAL_MODEL
        }
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = if (mode == HybridInferenceMode.ONLY_ON_DEVICE) LOCAL_FALLBACK_MODEL else modelName,
            systemInstruction = content {
                text(
                    "You answer questions strictly grounded in the provided document excerpts. " +
                        "Always cite exact [Page N], [Section], [Slide N], or [Sheet] references inline. " +
                        "Clearly distinguish direct facts stated in the source from any AI interpretation. " +
                        "If the answer is not present in the extracted excerpts, explicitly state that it is not found in the document."
                )
            },
            onDeviceConfig = OnDeviceConfig(mode = sdkMode)
        )
        return model to modelName
    }

    // ------------------------------------------------------------------------
    // 1. Embedded Media Visual & OCR Pass (for DOCX word/media/* & PPTX ppt/media/*)
    // ------------------------------------------------------------------------
    suspend fun analyzeEmbeddedVisuals(
        mediaList: List<ExtractedEmbeddedMedia>,
        existingNotes: Map<String, String>,
        startingSectionIndex: Int,
        onVisualProgress: suspend (index: Int, total: Int, label: String, streamPreview: String) -> Unit = { _, _, _, _ -> },
        onNotePersisted: (mediaId: String, note: String) -> Unit = { _, _ -> },
    ): EmbeddedVisualsOutcome {
        if (mediaList.isEmpty()) {
            return EmbeddedVisualsOutcome(emptyList(), 0, emptyList(), ActualInferenceSource.UNKNOWN, 0L)
        }
        val startMs = System.currentTimeMillis()
        val sections = mutableListOf<DocumentSection>()
        val failedLabels = mutableListOf<String>()
        var observedSource = ActualInferenceSource.UNKNOWN
        var secIdx = startingSectionIndex

        mediaList.forEachIndexed { idx, media ->
            val cached = existingNotes[media.id]
            if (!cached.isNullOrBlank()) {
                sections.add(DocumentSection(secIdx++, media.referenceLabel, cached))
                return@forEachIndexed
            }

            val file = File(media.localPath)
            if (!file.exists() || !file.canRead()) {
                failedLabels.add("${media.referenceLabel} (missing file)")
                return@forEachIndexed
            }

            onVisualProgress(idx + 1, mediaList.size, media.referenceLabel, "")
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val prompt = "Inspect this embedded visual (${media.referenceLabel}) from a document.\n" +
                    "1. Transcribe all visible text, headings, axis labels, legends, and numbers verbatim.\n" +
                    "2. Describe any chart trends, diagrams, screenshots, or tables (as Markdown tables) strictly factually.\n" +
                    "Do not invent content not visibly shown."

                val output = ResilientRetryPolicy.executeWithBackoff(
                    maxAttempts = maxRetryAttempts,
                    baseDelayMs = retryBaseDelayMs,
                ) {
                    executor.streamMultimodalInline(
                        bytes = bytes,
                        mimeType = media.mimeType,
                        prompt = prompt,
                        timeoutMs = ocrTimeoutMs,
                    ) { _, acc ->
                        onVisualProgress(idx + 1, mediaList.size, media.referenceLabel, acc.takeLast(600))
                    }
                }

                if (output.actualSource != ActualInferenceSource.UNKNOWN) {
                    observedSource = output.actualSource
                }
                sections.add(DocumentSection(secIdx++, media.referenceLabel, output.text))
                onNotePersisted(media.id, output.text)
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                Log.w(DOC_AI_TAG, "Embedded visual analysis failed for ${media.referenceLabel}", e)
                failedLabels.add("${media.referenceLabel} (${ResilientRetryPolicy.classifyError(e).name})")
            }
        }

        return EmbeddedVisualsOutcome(
            visualSections = sections,
            succeededCount = sections.size,
            failedLabels = failedLabels,
            actualSource = observedSource,
            latencyMs = System.currentTimeMillis() - startMs,
        )
    }

    // ------------------------------------------------------------------------
    // 2. PDF Multimodal Streaming Extraction & Scanned-PDF Batched Page OCR
    // ------------------------------------------------------------------------
    suspend fun extractPdfWithMultimodalOcr(
        localFile: File,
        fileName: String,
        totalPages: Int,
        onProgress: (stage: String, detail: String, fraction: Float, streamPreview: String) -> Unit,
    ): Result<MultimodalOcrOutcome> {
        val startMs = System.currentTimeMillis()
        if (!localFile.exists() || !localFile.canRead()) {
            return Result.failure(IllegalStateException("Original PDF file is unavailable on device storage."))
        }

        val fileSize = localFile.length()
        if (fileSize in 1L..INLINE_PDF_MAX_BYTES && totalPages <= INLINE_PDF_MAX_PAGES) {
            val pageHint = if (totalPages > 0) "$totalPages-page " else ""
            onProgress(
                "Streaming PDF Multimodal Extraction",
                "Reading ${if (totalPages > 0) "all $totalPages page(s)" else "PDF"} via Firebase AI Logic multimodal cloud model...",
                0.22f,
                ""
            )
            val directAttempt = runCatching {
                val pdfBytes = withContext(Dispatchers.IO) { localFile.readBytes() }
                val prompt = "Extract ALL content from this ${pageHint}PDF verbatim, page by page.\n" +
                    "- Prefix each page on its own line with [Page 1], [Page 2], ... for every page present.\n" +
                    "- Preserve headings (# / ##), bullet lists, and tables as Markdown tables.\n" +
                    "- For scanned pages, charts, or diagrams, perform verbatim OCR and report observable visual facts.\n" +
                    "- Never summarize, omit pages, or invent content."

                ResilientRetryPolicy.executeWithBackoff(
                    maxAttempts = maxRetryAttempts,
                    baseDelayMs = retryBaseDelayMs,
                    onRetry = { attempt, kind, delayMs ->
                        onProgress(
                            "Retrying PDF Extraction (Attempt ${attempt + 1}/$maxRetryAttempts)",
                            "Transient ${kind.name} — retrying in ${delayMs / 1000.0}s...",
                            0.25f,
                            ""
                        )
                    }
                ) {
                    executor.streamMultimodalInline(
                        bytes = pdfBytes,
                        mimeType = "application/pdf",
                        prompt = prompt,
                        timeoutMs = ocrTimeoutMs,
                    ) { _, acc ->
                        onProgress(
                            "Streaming PDF Multimodal Extraction",
                            "Extracted ${acc.length} chars from $totalPages page(s)...",
                            0.40f,
                            acc.takeLast(800)
                        )
                    }
                }
            }

            if (directAttempt.isSuccess) {
                val output = directAttempt.getOrThrow()
                if (output.text.isNotBlank()) {
                    val sections = DocumentChunker.parseSectionsFromFormattedText(output.text)
                    return Result.success(
                        MultimodalOcrOutcome(
                            sections = sections,
                            combinedText = DocumentExtractionEngine.formatSectionsForPreview(sections),
                            status = ExtractionStatus.COMPLETE,
                            disclosure = "Extracted $totalPages-page PDF page by page (${sections.size} section(s)) via streaming multimodal PDF & OCR engine.",
                            actualSource = output.actualSource,
                            requestedMode = HybridInferenceMode.ONLY_IN_CLOUD,
                            modelName = output.modelName,
                            latencyMs = System.currentTimeMillis() - startMs,
                        )
                    )
                }
            } else {
                val err = directAttempt.exceptionOrNull()
                if (err is CancellationException && err !is TimeoutCancellationException) throw err
                Log.w(DOC_AI_TAG, "Inline PDF extraction failed; falling back to batched page Bitmap OCR", err)
            }
        }

        // Batched PdfRenderer page-by-page rasterization + streaming Vision OCR
        val sections = mutableListOf<DocumentSection>()
        val failedRanges = mutableListOf<String>()
        var observedSource = ActualInferenceSource.UNKNOWN
        var pageCursor = 0
        val totalBatches = ((totalPages + PDF_OCR_BATCH_PAGES - 1) / PDF_OCR_BATCH_PAGES).coerceAtLeast(1)
        var batchIndex = 0

        while (pageCursor < totalPages) {
            val batchEnd = min(pageCursor + PDF_OCR_BATCH_PAGES, totalPages)
            val startLabel = pageCursor + 1
            val endLabel = batchEnd
            batchIndex++
            val baseFraction = (batchIndex.toFloat() / (totalBatches + 1).toFloat()).coerceIn(0.08f, 0.85f)
            onProgress(
                "PDF Page-by-Page OCR ($batchIndex/$totalBatches)",
                "Rendering & streaming Pages $startLabel–$endLabel of $totalPages...",
                baseFraction,
                ""
            )

            val bitmapsResult = withContext(Dispatchers.IO) {
                DocumentExtractionEngine.renderPdfPageBatch(localFile, pageCursor, batchEnd)
            }
            if (bitmapsResult.isFailure) {
                failedRanges.add("Pages $startLabel–$endLabel (render error)")
                pageCursor = batchEnd
                continue
            }

            val pageBitmaps = bitmapsResult.getOrThrow()
            try {
                val prompt = "These images are consecutive Pages $startLabel to $endLabel of a $totalPages-page PDF.\n" +
                    "Perform complete verbatim OCR and layout extraction for EACH page.\n" +
                    "- Start each page on its own line with [Page N] (using exact page numbers $startLabel..$endLabel).\n" +
                    "- Preserve headings, paragraphs, numbers, and tables as Markdown tables.\n" +
                    "- For charts, figures, or diagrams, report exact visible labels and numerical values.\n" +
                    "- Never invent missing or illegible text; mark unreadable regions as [illegible]."

                val output = ResilientRetryPolicy.executeWithBackoff(
                    maxAttempts = maxRetryAttempts,
                    baseDelayMs = retryBaseDelayMs,
                ) {
                    executor.streamMultimodalBitmaps(
                        bitmaps = pageBitmaps.map { it.second },
                        prompt = prompt,
                        timeoutMs = ocrTimeoutMs,
                    ) { _, acc ->
                        onProgress(
                            "PDF Page-by-Page OCR ($batchIndex/$totalBatches)",
                            "Streaming OCR for Pages $startLabel–$endLabel...",
                            baseFraction,
                            acc.takeLast(700)
                        )
                    }
                }
                if (output.actualSource != ActualInferenceSource.UNKNOWN) observedSource = output.actualSource
                val parsed = DocumentChunker.parseSectionsFromFormattedText(output.text)
                parsed.forEach { sec ->
                    sections.add(DocumentSection(sections.size + 1, sec.referenceLabel, sec.content))
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                Log.e(DOC_AI_TAG, "PDF OCR failed for pages $startLabel–$endLabel", e)
                failedRanges.add("Pages $startLabel–$endLabel (${ResilientRetryPolicy.classifyError(e).name})")
            } finally {
                pageBitmaps.forEach { (_, bmp) -> if (!bmp.isRecycled) bmp.recycle() }
            }
            pageCursor = batchEnd
        }

        val elapsed = System.currentTimeMillis() - startMs
        if (sections.isEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "PDF content extraction failed for $fileName across all $totalPages page(s). " +
                        "Failures: ${failedRanges.joinToString("; ")}"
                )
            )
        }

        val status = if (failedRanges.isEmpty()) ExtractionStatus.COMPLETE else ExtractionStatus.PARTIAL
        val disclosure = if (failedRanges.isEmpty()) {
            "Extracted all $totalPages page(s) via streaming page-by-page multimodal Vision OCR."
        } else {
            "Partial PDF extraction: extracted ${sections.size} section(s) of $totalPages pages. " +
                "Unextracted ranges: ${failedRanges.joinToString(", ")}."
        }

        return Result.success(
            MultimodalOcrOutcome(
                sections = sections,
                combinedText = DocumentExtractionEngine.formatSectionsForPreview(sections),
                status = status,
                disclosure = disclosure,
                actualSource = observedSource,
                requestedMode = HybridInferenceMode.ONLY_IN_CLOUD,
                modelName = CLOUD_MULTIMODAL_MODEL,
                latencyMs = elapsed,
            )
        )
    }

    // ------------------------------------------------------------------------
    // 3. Image Multimodal Visual Understanding & OCR (Streaming + Retry)
    // ------------------------------------------------------------------------
    suspend fun extractImageWithVisualOcr(
        localFile: File,
        fileName: String,
        mimeType: String = "image/jpeg",
        onStream: suspend (String) -> Unit = {},
    ): Result<MultimodalOcrOutcome> {
        val startMs = System.currentTimeMillis()
        val prompt = "Analyze this image thoroughly for a knowledge management system.\n" +
            "Output two clearly labeled sections:\n" +
            "[Image OCR Text]\n" +
            "Transcribe ALL visible text, labels, headings, numbers, UI text, and tables (as Markdown tables) verbatim. " +
            "If no text is visible, write 'No readable text visible in image.'\n\n" +
            "[Visual Content & Chart/Diagram Details]\n" +
            "Describe the exact visual subject, layout, charts/axes/data points, diagrams/arrows, screenshots, or objects observed. " +
            "State only observable visual facts; do not speculate beyond what is visibly shown."

        val bitmapResult = withContext(Dispatchers.IO) {
            DocumentExtractionEngine.decodeImageSafe(localFile)
        }
        val bitmap = bitmapResult.getOrNull()

        return try {
            val output = if (bitmap != null) {
                ResilientRetryPolicy.executeWithBackoff(
                    maxAttempts = maxRetryAttempts,
                    baseDelayMs = retryBaseDelayMs,
                ) {
                    executor.streamMultimodalBitmaps(
                        bitmaps = listOf(bitmap),
                        prompt = prompt,
                        timeoutMs = ocrTimeoutMs,
                    ) { _, acc -> onStream(acc.takeLast(700)) }
                }
            } else {
                // Fallback for HEIC / AVIF / encodings that BitmapFactory cannot decode directly
                val bytes = withContext(Dispatchers.IO) { localFile.readBytes() }
                val resolvedMime = mimeType.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                ResilientRetryPolicy.executeWithBackoff(
                    maxAttempts = maxRetryAttempts,
                    baseDelayMs = retryBaseDelayMs,
                ) {
                    executor.streamMultimodalInline(
                        bytes = bytes,
                        mimeType = resolvedMime,
                        prompt = prompt,
                        timeoutMs = ocrTimeoutMs,
                    ) { _, acc -> onStream(acc.takeLast(700)) }
                }
            }

            val sections = DocumentChunker.parseSectionsFromFormattedText(output.text)
            Result.success(
                MultimodalOcrOutcome(
                    sections = sections,
                    combinedText = DocumentExtractionEngine.formatSectionsForPreview(sections),
                    status = ExtractionStatus.COMPLETE,
                    disclosure = "Completed streaming multimodal visual understanding & verbatim OCR on original image.",
                    actualSource = output.actualSource,
                    requestedMode = HybridInferenceMode.ONLY_IN_CLOUD,
                    modelName = output.modelName,
                    latencyMs = System.currentTimeMillis() - startMs,
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            Log.e(DOC_AI_TAG, "Image visual OCR failed for $fileName", e)
            Result.failure(Exception(ResilientRetryPolicy.friendlyMessage(e), e))
        } finally {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------------
    // 3b. Audio / Video Multimodal Transcription & Content Extraction
    // ------------------------------------------------------------------------
    suspend fun extractMediaWithMultimodal(
        localFile: File,
        fileName: String,
        mimeType: String,
        onStream: suspend (String) -> Unit = {},
    ): Result<MultimodalOcrOutcome> {
        val startMs = System.currentTimeMillis()
        if (!localFile.exists() || !localFile.canRead()) {
            return Result.failure(IllegalStateException("Original media file ($fileName) is unavailable on device storage."))
        }
        return try {
            val bytes = withContext(Dispatchers.IO) { localFile.readBytes() }
            val isVideo = mimeType.startsWith("video/")
            val prompt = if (isVideo) {
                "Analyze this video file thoroughly for a personal knowledge base.\n" +
                    "Output clearly labeled sections:\n" +
                    "[Transcript & Spoken Content]\nTranscribe spoken dialogue or narration with approximate [MM:SS] timestamps.\n\n" +
                    "[Visual Scenes & On-Screen Text]\nTranscribe any visible slides, titles, captions, or charts and describe key visual events strictly factually."
            } else {
                "Transcribe and extract all spoken content from this audio file.\n" +
                    "Output clearly labeled sections:\n" +
                    "[Audio Transcript]\nProvide a faithful verbatim transcript with speaker turns and approximate [MM:SS] timestamps where helpful.\n\n" +
                    "[Key Points Mentioned]\nList the core factual topics, figures, and decisions stated in the audio."
            }

            val output = ResilientRetryPolicy.executeWithBackoff(
                maxAttempts = maxRetryAttempts,
                baseDelayMs = retryBaseDelayMs,
            ) {
                executor.streamMultimodalInline(
                    bytes = bytes,
                    mimeType = mimeType,
                    prompt = prompt,
                    timeoutMs = ocrTimeoutMs,
                ) { _, acc -> onStream(acc.takeLast(700)) }
            }

            val sections = DocumentChunker.parseSectionsFromFormattedText(output.text)
            Result.success(
                MultimodalOcrOutcome(
                    sections = sections,
                    combinedText = DocumentExtractionEngine.formatSectionsForPreview(sections),
                    status = ExtractionStatus.COMPLETE,
                    disclosure = "Completed Firebase AI Logic multimodal ${if (isVideo) "video" else "audio"} transcription & extraction.",
                    actualSource = output.actualSource,
                    requestedMode = HybridInferenceMode.ONLY_IN_CLOUD,
                    modelName = output.modelName,
                    latencyMs = System.currentTimeMillis() - startMs,
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            Log.e(DOC_AI_TAG, "Media multimodal extraction failed for $fileName", e)
            Result.failure(Exception(ResilientRetryPolicy.friendlyMessage(e), e))
        }
    }

    // ------------------------------------------------------------------------
    // 4. Resilient, Checkpointed, Streaming Chunked Map-Reduce Document Analysis
    // ------------------------------------------------------------------------
    suspend fun analyzeDocumentResilient(
        userId: String,
        itemId: String,
        title: String,
        fileName: String?,
        extractionMethod: ExtractionMethod,
        extractionStatus: ExtractionStatus,
        extractionDisclosure: String,
        chunks: List<DocumentChunk>,
        fullExtractedPreview: String,
        checkpointDir: File? = null,
        existingCheckpoint: DocumentAnalysisCheckpointRecord? = null,
        onlyRetryFailed: Boolean = false,
        failedEmbeddedVisuals: List<String> = emptyList(),
        onProgress: (AnalysisProgressUpdate) -> Unit = {},
    ): Result<ResilientAnalysisOutcome> {
        if (chunks.isEmpty()) {
            return Result.failure(
                IllegalStateException("No extracted source content is available to analyze. Extraction must succeed before AI analysis.")
            )
        }

        val startMs = System.currentTimeMillis()
        val contentHash = DocumentCheckpointStore.computeContentHash(fullExtractedPreview)
        val reusableByIndex: Map<Int, ChunkAnalysisCheckpoint> =
            if (existingCheckpoint != null && existingCheckpoint.contentHash == contentHash) {
                existingCheckpoint.chunks.associateBy { it.chunkIndex }
            } else {
                emptyMap()
            }

        val workingCheckpoints = chunks.map { chunk ->
            val prior = reusableByIndex[chunk.chunkIndex]
            if (prior != null && prior.status == ChunkProcessingStatus.COMPLETED && prior.digestText.isNotBlank()) {
                prior.copy(totalChunks = chunks.size, referenceRange = chunk.referenceRange)
            } else if (prior != null && onlyRetryFailed && prior.status != ChunkProcessingStatus.FAILED) {
                prior.copy(totalChunks = chunks.size, referenceRange = chunk.referenceRange)
            } else {
                ChunkAnalysisCheckpoint(
                    chunkIndex = chunk.chunkIndex,
                    totalChunks = chunks.size,
                    referenceRange = chunk.referenceRange,
                    status = ChunkProcessingStatus.PENDING,
                )
            }
        }.toMutableList()

        val embeddedVisualNotes = existingCheckpoint?.embeddedVisualNotes.orEmpty()

        fun persistCurrentCheckpoints() {
            if (checkpointDir != null && itemId.isNotBlank()) {
                DocumentCheckpointStore.saveCheckpoint(
                    baseDir = checkpointDir,
                    record = DocumentAnalysisCheckpointRecord(
                        itemId = itemId,
                        contentHash = contentHash,
                        chunks = workingCheckpoints.toList(),
                        embeddedVisualNotes = embeddedVisualNotes,
                    )
                )
            }
        }

        var observedSource = workingCheckpoints
            .firstOrNull { it.actualSource != ActualInferenceSource.UNKNOWN }
            ?.actualSource ?: ActualInferenceSource.UNKNOWN

        // Step A: Stream-analyze each pending/failed chunk with retry + 413 auto-split + instant checkpoint save
        val isSingleCompactChunk = chunks.size == 1 && chunks.first().text.length <= DocumentChunker.DEFAULT_MAX_CHUNK_CHARS
        if (isSingleCompactChunk && workingCheckpoints[0].status != ChunkProcessingStatus.COMPLETED) {
            // Even for a 1-chunk document, mark checkpoint cleanly as completed once direct synthesis runs
            workingCheckpoints[0] = workingCheckpoints[0].copy(
                status = ChunkProcessingStatus.RUNNING,
                attempts = workingCheckpoints[0].attempts + 1,
            )
            onProgress(
                AnalysisProgressUpdate(
                    phase = DocumentProcessingPhase.ANALYZING_CHUNKS,
                    stageLabel = "Streaming Document Analysis (1/1)",
                    detail = "Analyzing ${chunks.first().referenceRange}...",
                    progressFraction = 0.45f,
                    chunkStatuses = workingCheckpoints.toList(),
                )
            )
        } else {
            for (idx in chunks.indices) {
                val chunk = chunks[idx]
                val cp = workingCheckpoints[idx]
                if (cp.status == ChunkProcessingStatus.COMPLETED && cp.digestText.isNotBlank()) {
                    continue // Already checkpointed from prior run — skip cleanly!
                }
                if (onlyRetryFailed && cp.status != ChunkProcessingStatus.FAILED && cp.status != ChunkProcessingStatus.PENDING) {
                    continue
                }

                workingCheckpoints[idx] = cp.copy(status = ChunkProcessingStatus.RUNNING, errorMessage = null)
                val baseFrac = 0.15f + (0.60f * (idx.toFloat() / chunks.size.toFloat()))
                onProgress(
                    AnalysisProgressUpdate(
                        phase = DocumentProcessingPhase.ANALYZING_CHUNKS,
                        stageLabel = "Analyzing Chunk ${chunk.chunkIndex}/${chunks.size}",
                        detail = "Streaming factual extraction for ${chunk.referenceRange}...",
                        progressFraction = baseFrac,
                        chunkStatuses = workingCheckpoints.toList(),
                    )
                )

                var attemptsUsed = cp.attempts
                try {
                    val chunkResult = analyzeSingleChunkWithResilience(
                        title = title,
                        chunk = chunk,
                        onAttempt = { attemptsUsed = cp.attempts + it },
                        onRetry = { attempt, kind, delayMs ->
                            onProgress(
                                AnalysisProgressUpdate(
                                    phase = DocumentProcessingPhase.ANALYZING_CHUNKS,
                                    stageLabel = "Retrying Chunk ${chunk.chunkIndex}/${chunks.size} (${attempt + 1}/$maxRetryAttempts)",
                                    detail = "${kind.name} on ${chunk.referenceRange} — retrying in ${delayMs / 1000.0}s...",
                                    progressFraction = baseFrac,
                                    chunkStatuses = workingCheckpoints.toList(),
                                )
                            )
                        },
                        onStream = { accText ->
                            onProgress(
                                AnalysisProgressUpdate(
                                    phase = DocumentProcessingPhase.ANALYZING_CHUNKS,
                                    stageLabel = "Streaming Chunk ${chunk.chunkIndex}/${chunks.size}",
                                    detail = chunk.referenceRange,
                                    progressFraction = baseFrac,
                                    streamingPreview = accText.takeLast(900),
                                    chunkStatuses = workingCheckpoints.toList(),
                                )
                            )
                        }
                    )
                    if (chunkResult.actualSource != ActualInferenceSource.UNKNOWN) {
                        observedSource = chunkResult.actualSource
                    }
                    workingCheckpoints[idx] = cp.copy(
                        status = ChunkProcessingStatus.COMPLETED,
                        digestText = chunkResult.text,
                        errorMessage = null,
                        attempts = attemptsUsed.coerceAtLeast(1),
                        actualSource = chunkResult.actualSource,
                        modelName = chunkResult.modelName,
                        latencyMs = chunkResult.latencyMs,
                    )
                    persistCurrentCheckpoints()
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) {
                        // Revert RUNNING -> PENDING and persist already-completed chunks so user can resume!
                        workingCheckpoints[idx] = cp.copy(status = ChunkProcessingStatus.PENDING)
                        persistCurrentCheckpoints()
                        throw e
                    }
                    val friendly = ResilientRetryPolicy.friendlyMessage(e)
                    Log.w(DOC_AI_TAG, "Chunk ${chunk.chunkIndex}/${chunks.size} failed after retries", e)
                    workingCheckpoints[idx] = cp.copy(
                        status = ChunkProcessingStatus.FAILED,
                        errorMessage = friendly,
                        attempts = attemptsUsed.coerceAtLeast(1),
                    )
                    persistCurrentCheckpoints()
                    onProgress(
                        AnalysisProgressUpdate(
                            phase = DocumentProcessingPhase.ANALYZING_CHUNKS,
                            stageLabel = "Chunk ${chunk.chunkIndex}/${chunks.size} Failed (Saved Prior Progress)",
                            detail = "${chunk.referenceRange}: $friendly",
                            progressFraction = baseFrac,
                            chunkStatuses = workingCheckpoints.toList(),
                        )
                    )
                }
            }
        }

        // Check completed chunks
        val completedDigests = if (isSingleCompactChunk) {
            listOf(chunks.first().text)
        } else {
            workingCheckpoints.filter { it.status == ChunkProcessingStatus.COMPLETED && it.digestText.isNotBlank() }
                .map { "=== Digest of Chunk ${it.chunkIndex}/${it.totalChunks} (${it.referenceRange}) ===\n${it.digestText}" }
        }
        val failedCheckpoints = workingCheckpoints.filter { it.status == ChunkProcessingStatus.FAILED }

        if (completedDigests.isEmpty()) {
            val firstError = failedCheckpoints.firstOrNull()?.errorMessage
                ?: "All ${chunks.size} document chunks failed during analysis."
            return Result.failure(IllegalStateException(firstError))
        }

        // Step B: Hierarchical Reduce if digests are huge (prevents giant reduce timeouts on 100+ page docs)
        var synthesisInput = completedDigests.joinToString("\n\n")
        if (!isSingleCompactChunk && synthesisInput.length > MAX_DIRECT_SYNTHESIS_CHARS) {
            synthesisInput = hierarchicallyReduceDigests(
                title = title,
                digests = completedDigests,
                onProgress = { batchIdx, totalBatches, preview ->
                    onProgress(
                        AnalysisProgressUpdate(
                            phase = DocumentProcessingPhase.CONSOLIDATING,
                            stageLabel = "Compressing Large Document Digests ($batchIdx/$totalBatches)",
                            detail = "Preserving all facts, figures, and citations before final synthesis...",
                            progressFraction = 0.78f + (0.08f * (batchIdx.toFloat() / totalBatches.toFloat())),
                            streamingPreview = preview,
                            chunkStatuses = workingCheckpoints.toList(),
                        )
                    )
                }
            )
        }

        // Step C: Streaming Final Structured JSON Consolidation (with exponential backoff retry)
        val chunksSucceeded = if (isSingleCompactChunk) 1 else workingCheckpoints.count { it.status == ChunkProcessingStatus.COMPLETED }
        onProgress(
            AnalysisProgressUpdate(
                phase = DocumentProcessingPhase.CONSOLIDATING,
                stageLabel = "Synthesizing Final Structured Report",
                detail = "Consolidating $chunksSucceeded of ${chunks.size} chunk(s) into grounded intelligence report...",
                progressFraction = 0.88f,
                chunkStatuses = workingCheckpoints.toList(),
            )
        )

        val finalPrompt = buildFinalConsolidationPrompt(
            title = title,
            fileName = fileName,
            extractionMethod = extractionMethod,
            chunksSucceeded = chunksSucceeded,
            totalChunks = chunks.size,
            synthesisInput = synthesisInput,
        )

        return try {
            val finalOutput = ResilientRetryPolicy.executeWithBackoff(
                maxAttempts = maxRetryAttempts,
                baseDelayMs = retryBaseDelayMs,
                onRetry = { attempt, kind, delayMs ->
                    onProgress(
                        AnalysisProgressUpdate(
                            phase = DocumentProcessingPhase.CONSOLIDATING,
                            stageLabel = "Retrying Final Report (${attempt + 1}/$maxRetryAttempts)",
                            detail = "${kind.name} — retrying synthesis in ${delayMs / 1000.0}s (all completed chunks preserved)...",
                            progressFraction = 0.90f,
                            chunkStatuses = workingCheckpoints.toList(),
                        )
                    )
                }
            ) {
                executor.streamStructuredJson(
                    prompt = finalPrompt,
                    timeoutMs = synthesisTimeoutMs,
                ) { _, acc ->
                    onProgress(
                        AnalysisProgressUpdate(
                            phase = DocumentProcessingPhase.CONSOLIDATING,
                            stageLabel = "Streaming Final Report",
                            detail = "Received ${acc.length} chars of structured analysis...",
                            progressFraction = 0.93f,
                            streamingPreview = buildHumanReadableStreamPreview(acc),
                            chunkStatuses = workingCheckpoints.toList(),
                        )
                    )
                }
            }

            if (finalOutput.actualSource != ActualInferenceSource.UNKNOWN) {
                observedSource = finalOutput.actualSource
            }
            if (isSingleCompactChunk) {
                workingCheckpoints[0] = workingCheckpoints[0].copy(
                    status = ChunkProcessingStatus.COMPLETED,
                    digestText = "Single-chunk direct synthesis completed.",
                    actualSource = finalOutput.actualSource,
                    modelName = finalOutput.modelName,
                    latencyMs = finalOutput.latencyMs,
                )
                persistCurrentCheckpoints()
            }

            // STRICT COVERAGE HONESTY: NEVER mark COMPLETE if any chunk or visual failed/skipped!
            val hasCoverageGaps = failedCheckpoints.isNotEmpty() ||
                failedEmbeddedVisuals.isNotEmpty() ||
                extractionStatus == ExtractionStatus.PARTIAL ||
                chunksSucceeded < chunks.size

            val finalStatus = if (hasCoverageGaps) ExtractionStatus.PARTIAL else ExtractionStatus.COMPLETE
            val finalDisclosure = buildString {
                append(extractionDisclosure)
                if (failedCheckpoints.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    val failedNames = failedCheckpoints.joinToString(", ") { it.referenceRange }
                    append("Partial AI analysis: analyzed $chunksSucceeded/${chunks.size} chunk(s); failed/skipped: $failedNames. Use 'Retry Failed Sections' to complete.")
                }
                if (failedEmbeddedVisuals.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("Unanalyzed embedded visuals: ${failedEmbeddedVisuals.joinToString(", ")}.")
                }
            }.take(1000)

            val parsed = parseStructuredAnalysisJson(
                rawJson = finalOutput.text,
                userId = userId,
                itemId = itemId,
                extractionStatus = finalStatus,
                extractionMethod = extractionMethod,
                extractionDisclosure = finalDisclosure,
                chunksProcessed = chunksSucceeded.toLong(),
                totalChunks = chunks.size.toLong(),
                extractedPreview = fullExtractedPreview.take(98_000),
                actualSource = observedSource,
                modelName = if (observedSource == ActualInferenceSource.ON_DEVICE) "gemini-nano" else finalOutput.modelName.ifBlank { CLOUD_MULTIMODAL_MODEL },
                latencyMs = System.currentTimeMillis() - startMs,
            )

            // Clear checkpoint only when 100% complete; preserve it if partial so user can retry failed chunks!
            if (!hasCoverageGaps && checkpointDir != null && itemId.isNotBlank()) {
                DocumentCheckpointStore.clearCheckpoint(checkpointDir, itemId)
            }

            Result.success(
                ResilientAnalysisOutcome(
                    analysis = parsed,
                    chunkCheckpoints = workingCheckpoints.toList(),
                    hasFailedChunks = failedCheckpoints.isNotEmpty(),
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) {
                persistCurrentCheckpoints()
                throw e
            }
            if (isSingleCompactChunk) {
                workingCheckpoints[0] = workingCheckpoints[0].copy(
                    status = ChunkProcessingStatus.FAILED,
                    errorMessage = ResilientRetryPolicy.friendlyMessage(e),
                )
            }
            persistCurrentCheckpoints()
            Log.e(DOC_AI_TAG, "Final structured consolidation failed (prior chunk checkpoints preserved)", e)
            Result.failure(Exception(ResilientRetryPolicy.friendlyMessage(e), e))
        }
    }

    private suspend fun analyzeSingleChunkWithResilience(
        title: String,
        chunk: DocumentChunk,
        onAttempt: (Int) -> Unit,
        onRetry: suspend (attempt: Int, kind: AiErrorKind, delayMs: Long) -> Unit,
        onStream: suspend (String) -> Unit,
    ): AiGenerationOutput {
        val mapPrompt = buildChunkMapPrompt(title, chunk)
        return try {
            ResilientRetryPolicy.executeWithBackoff(
                maxAttempts = maxRetryAttempts,
                baseDelayMs = retryBaseDelayMs,
                onRetry = onRetry,
            ) { attempt ->
                onAttempt(attempt)
                executor.streamText(
                    prompt = mapPrompt,
                    kind = ExecutorKind.CHUNK_MAP,
                    timeoutMs = chunkTimeoutMs,
                ) { _, acc -> onStream(acc) }
            }
        } catch (e: Throwable) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            // Graceful HTTP 413 recovery: automatically split oversized chunk into smaller sub-parts and re-run!
            if (ResilientRetryPolicy.classifyError(e) == AiErrorKind.PAYLOAD_TOO_LARGE_413 && chunk.text.length > 2_000) {
                val subChunks = DocumentChunker.splitChunkForPayloadLimit(chunk, targetMaxChars = chunk.text.length / 2)
                if (subChunks.size > 1) {
                    val subDigests = mutableListOf<String>()
                    var src = ActualInferenceSource.UNKNOWN
                    var model = CLOUD_MULTIMODAL_MODEL
                    var totalLatency = 0L
                    for (sub in subChunks) {
                        val subOut = ResilientRetryPolicy.executeWithBackoff(
                            maxAttempts = maxRetryAttempts,
                            baseDelayMs = retryBaseDelayMs,
                            onRetry = onRetry,
                        ) {
                            executor.streamText(
                                prompt = buildChunkMapPrompt(title, sub),
                                kind = ExecutorKind.CHUNK_MAP,
                                timeoutMs = chunkTimeoutMs,
                            ) { _, acc -> onStream(acc) }
                        }
                        if (subOut.actualSource != ActualInferenceSource.UNKNOWN) src = subOut.actualSource
                        model = subOut.modelName
                        totalLatency += subOut.latencyMs
                        subDigests.add(subOut.text)
                    }
                    return AiGenerationOutput(
                        text = subDigests.joinToString("\n\n"),
                        actualSource = src,
                        modelName = model,
                        latencyMs = totalLatency,
                    )
                }
            }
            throw e
        }
    }

    private suspend fun hierarchicallyReduceDigests(
        title: String,
        digests: List<String>,
        onProgress: suspend (batchIdx: Int, totalBatches: Int, preview: String) -> Unit,
    ): String {
        val batches = digests.chunked(HIERARCHICAL_REDUCE_BATCH_SIZE)
        val reduced = mutableListOf<String>()
        batches.forEachIndexed { idx, group ->
            val prompt = buildString {
                appendLine("Combine the following factual chunk digests from \"$title\" into one dense, lossless intermediate digest.")
                appendLine("Preserve all exact [Page/Section/Slide/Sheet] citations, metrics, table findings, formulas, conclusions, and action items.")
                appendLine()
                appendLine(group.joinToString("\n\n"))
            }
            val out = ResilientRetryPolicy.executeWithBackoff(
                maxAttempts = maxRetryAttempts,
                baseDelayMs = retryBaseDelayMs,
            ) {
                executor.streamText(
                    prompt = prompt,
                    kind = ExecutorKind.HIERARCHICAL_REDUCE,
                    timeoutMs = chunkTimeoutMs,
                ) { _, acc -> onProgress(idx + 1, batches.size, acc.takeLast(700)) }
            }
            reduced.add(out.text)
        }
        return reduced.joinToString("\n\n")
    }

    private fun buildChunkMapPrompt(title: String, chunk: DocumentChunk): String = buildString {
        appendLine("Analyze Chunk ${chunk.chunkIndex}/${chunk.totalChunks} (${chunk.referenceRange}) of \"$title\".")
        appendLine("Produce a dense, strictly factual digest covering:")
        appendLine("1. Detailed explanation of what this section contains")
        appendLine("2. Verified source facts with exact [Page/Section/Slide/Sheet] citations")
        appendLine("3. Topics, definitions, entities, dates, and exact numerical figures")
        appendLine("4. Tables, spreadsheet formulas/calculations, charts, or embedded visual details")
        appendLine("5. Conclusions and explicit action items (if any)")
        appendLine("RULE: Never invent facts or extrapolate beyond the source text below.")
        appendLine()
        appendLine(chunk.text)
    }

    private fun buildFinalConsolidationPrompt(
        title: String,
        fileName: String?,
        extractionMethod: ExtractionMethod,
        chunksSucceeded: Int,
        totalChunks: Int,
        synthesisInput: String,
    ): String = buildString {
        appendLine("Produce a complete, strictly source-grounded structured JSON analysis for the document below.")
        appendLine("Document Title: $title")
        if (!fileName.isNullOrBlank()) appendLine("Original Filename (metadata only — NEVER infer content from filename): $fileName")
        appendLine("Extraction Method: ${extractionMethod.label}")
        appendLine("Chunks Covered: $chunksSucceeded of $totalChunks")
        appendLine()
        appendLine("CRITICAL ACCURACY RULES:")
        appendLine("- Provide a crisp 'summary' AND a thorough 'detailedExplanation' walking through the document's real contents.")
        appendLine("- Provide 'conclusions' directly supported by the document.")
        appendLine("- Every item in 'sourceFacts', 'definitions', 'entities', 'tableInsights', 'actionItems', and 'groundedQa' MUST come directly from the extracted text below and cite its exact 'sourceRef'.")
        appendLine("- Put high-level synthesis, implications, or interpretive takeaways ONLY in 'aiInterpretations', never mixed into 'sourceFacts'.")
        appendLine("- Never fabricate figures, dates, quotes, or action items. Return [] if not genuinely present.")
        appendLine()
        appendLine("=== EXTRACTED SOURCE CONTENT ===")
        appendLine(synthesisInput)
    }

    // ------------------------------------------------------------------------
    // 5. Ask This Document (Full-Document RAG Q&A with Hybrid Inference Routing)
    // ------------------------------------------------------------------------
    fun streamAskDocument(
        question: String,
        item: VaultItem,
        analysis: DocumentAnalysis?,
        allChunks: List<DocumentChunk>,
        conversationHistory: List<Pair<Boolean, String>>,
        mode: HybridInferenceMode,
    ): Flow<HybridAiService.StreamUpdate> = flow {
        val startMs = System.currentTimeMillis()
        if (allChunks.isEmpty()) {
            throw IllegalStateException("No extracted document content is available to answer questions.")
        }

        val relevantChunks = DocumentChunker.retrieveRelevantChunks(
            query = question,
            chunks = allChunks,
            topK = if (mode == HybridInferenceMode.ONLY_ON_DEVICE) 2 else 4,
        )
        val (model, fallbackModelName) = createDocQaModel(mode)

        val prompt = buildString {
            appendLine("### Document Metadata")
            appendLine("Title: ${item.title}")
            if (!item.fileName.isNullOrBlank()) appendLine("File: ${item.fileName}")
            appendLine("Total Chunks Indexed: ${allChunks.size} (retrieved ${relevantChunks.size} most relevant chunks)")
            if (analysis != null && analysis.summary.isNotBlank()) {
                appendLine()
                appendLine("### Consolidated Document Summary")
                appendLine(analysis.summary.take(1500))
            }
            appendLine()
            appendLine("### Retrieved Source Excerpts (cite exact [Page / Section / Slide / Sheet] tags)")
            relevantChunks.forEach { chunk ->
                appendLine("--- Chunk ${chunk.chunkIndex}/${chunk.totalChunks}: ${chunk.referenceRange} ---")
                appendLine(chunk.text.take(if (mode == HybridInferenceMode.ONLY_ON_DEVICE) 3500 else 6500))
                appendLine()
            }
            if (conversationHistory.isNotEmpty()) {
                appendLine("### Recent Document Q&A")
                conversationHistory.takeLast(6).forEach { (fromUser, msg) ->
                    appendLine(if (fromUser) "User: ${msg.take(500)}" else "NEXUS AI: ${msg.take(700)}")
                }
                appendLine()
            }
            appendLine("### User Question")
            appendLine(question)
            appendLine()
            appendLine("Answer strictly from the source excerpts above. Cite exact [Page/Section/Slide/Sheet] references. Clearly label any AI interpretation separately from direct source facts.")
        }

        val accumulated = StringBuilder()
        var observedSource = ActualInferenceSource.UNKNOWN

        try {
            withTimeout(synthesisTimeoutMs) {
                model.generateContentStream(prompt).collect { chunk ->
                    val src = extractActualSource(chunk)
                    if (src != ActualInferenceSource.UNKNOWN) observedSource = src
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) accumulated.append(piece)
                    val effectiveModel = if (observedSource == ActualInferenceSource.ON_DEVICE) "gemini-nano" else fallbackModelName
                    emit(
                        HybridAiService.StreamUpdate(
                            accumulatedText = accumulated.toString(),
                            actualSource = observedSource,
                            modelName = effectiveModel,
                            completed = false,
                            latencyMs = System.currentTimeMillis() - startMs,
                        )
                    )
                }
            }

            if (accumulated.isBlank()) throw IllegalStateException("Model returned an empty answer.")
            val effectiveModel = if (observedSource == ActualInferenceSource.ON_DEVICE) "gemini-nano" else fallbackModelName
            emit(
                HybridAiService.StreamUpdate(
                    accumulatedText = accumulated.toString(),
                    actualSource = observedSource,
                    modelName = effectiveModel,
                    completed = true,
                    latencyMs = System.currentTimeMillis() - startMs,
                )
            )
        } catch (e: TimeoutCancellationException) {
            throw Exception("Document Q&A timed out. Please try again.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val friendly = if (mode == HybridInferenceMode.ONLY_ON_DEVICE) {
                "Strict On-Device mode unavailable on this device: ${e.localizedMessage ?: "Gemini Nano is not provisioned."}"
            } else {
                ResilientRetryPolicy.friendlyMessage(e)
            }
            throw Exception(friendly, e)
        }
    }

    // ------------------------------------------------------------------------
    // JSON Parser & Streaming Preview Helper
    // ------------------------------------------------------------------------
    private fun buildHumanReadableStreamPreview(rawJsonSoFar: String): String {
        val summaryMatch = Regex("\"summary\"\\s*:\\s*\"([^\"]{10,700})").find(rawJsonSoFar)
        if (summaryMatch != null) {
            return summaryMatch.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"")
        }
        return rawJsonSoFar.takeLast(500)
    }

    private fun parseStructuredAnalysisJson(
        rawJson: String,
        userId: String,
        itemId: String,
        extractionStatus: ExtractionStatus,
        extractionMethod: ExtractionMethod,
        extractionDisclosure: String,
        chunksProcessed: Long,
        totalChunks: Long,
        extractedPreview: String,
        actualSource: ActualInferenceSource,
        modelName: String,
        latencyMs: Long,
    ): DocumentAnalysis {
        val cleaned = rawJson.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val json = JSONObject(cleaned)

        fun parseStringArray(key: String, maxItems: Int): List<String> {
            val arr = json.optJSONArray(key) ?: return emptyList()
            return buildList {
                for (i in 0 until min(arr.length(), maxItems)) {
                    val v = arr.optString(i).trim()
                    if (v.isNotEmpty()) add(v)
                }
            }
        }

        val facts = buildList {
            val arr = json.optJSONArray("sourceFacts") ?: return@buildList
            for (i in 0 until min(arr.length(), 45)) {
                val o = arr.optJSONObject(i) ?: continue
                val fact = o.optString("fact").trim()
                if (fact.isNotEmpty()) add(SourceFact(fact = fact, sourceRef = o.optString("sourceRef").trim()))
            }
        }

        val definitions = buildList {
            val arr = json.optJSONArray("definitions") ?: return@buildList
            for (i in 0 until min(arr.length(), 25)) {
                val o = arr.optJSONObject(i) ?: continue
                val term = o.optString("term").trim()
                val exp = o.optString("explanation").trim()
                if (term.isNotEmpty() && exp.isNotEmpty()) {
                    add(TermDefinition(term = term, explanation = exp, sourceRef = o.optString("sourceRef").trim()))
                }
            }
        }

        val entities = buildList {
            val arr = json.optJSONArray("entities") ?: return@buildList
            for (i in 0 until min(arr.length(), 45)) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isNotEmpty()) {
                    add(
                        ExtractedEntity(
                            name = name,
                            type = o.optString("type", "Entity").trim().ifBlank { "Entity" },
                            context = o.optString("context").trim(),
                            sourceRef = o.optString("sourceRef").trim(),
                        )
                    )
                }
            }
        }

        val tableInsights = buildList {
            val arr = json.optJSONArray("tableInsights") ?: return@buildList
            for (i in 0 until min(arr.length(), 25)) {
                val o = arr.optJSONObject(i) ?: continue
                val finding = o.optString("finding").trim()
                if (finding.isNotEmpty()) {
                    add(
                        DataInsight(
                            title = o.optString("title", "Data Insight").trim().ifBlank { "Data Insight" },
                            finding = finding,
                            sourceRef = o.optString("sourceRef").trim(),
                        )
                    )
                }
            }
        }

        val actionItems = buildList {
            val arr = json.optJSONArray("actionItems") ?: return@buildList
            for (i in 0 until min(arr.length(), 25)) {
                val o = arr.optJSONObject(i) ?: continue
                val action = o.optString("action").trim()
                if (action.isNotEmpty()) {
                    add(
                        DocumentActionItem(
                            action = action,
                            priority = o.optString("priority", "Medium").trim().ifBlank { "Medium" },
                            sourceRef = o.optString("sourceRef").trim(),
                        )
                    )
                }
            }
        }

        val groundedQa = buildList {
            val arr = json.optJSONArray("groundedQa") ?: return@buildList
            for (i in 0 until min(arr.length(), 20)) {
                val o = arr.optJSONObject(i) ?: continue
                val q = o.optString("question").trim()
                val a = o.optString("answer").trim()
                if (q.isNotEmpty() && a.isNotEmpty()) {
                    add(GroundedQaPair(question = q, answer = a, sourceRef = o.optString("sourceRef").trim()))
                }
            }
        }

        val summary = json.optString("summary").trim().ifBlank {
            "Analysis completed across $chunksProcessed of $totalChunks chunk(s)."
        }.take(7800)

        val detailedExplanation = json.optString("detailedExplanation").trim().take(15_500)

        return DocumentAnalysis(
            id = itemId,
            userId = userId,
            itemId = itemId,
            summary = summary,
            detailedExplanation = detailedExplanation,
            conclusions = parseStringArray("conclusions", 20),
            extractionStatus = extractionStatus.name,
            extractionMethod = extractionMethod.name,
            extractionDisclosure = extractionDisclosure,
            chunksProcessed = chunksProcessed,
            totalChunks = totalChunks,
            mainTopics = parseStringArray("mainTopics", 25),
            sourceFacts = facts,
            aiInterpretations = parseStringArray("aiInterpretations", 25),
            definitions = definitions,
            entities = entities,
            tableInsights = tableInsights,
            actionItems = actionItems,
            groundedQa = groundedQa,
            extractedPreview = extractedPreview,
            requestedMode = HybridInferenceMode.PREFER_IN_CLOUD.name,
            actualSource = actualSource.name,
            modelName = modelName.take(80),
            latencyMs = latencyMs.coerceIn(0L, 600_000L),
        )
    }

    private fun extractActualSource(response: GenerateContentResponse): ActualInferenceSource =
        when (response.inferenceSource) {
            InferenceSource.ON_DEVICE -> ActualInferenceSource.ON_DEVICE
            InferenceSource.IN_CLOUD -> ActualInferenceSource.IN_CLOUD
            else -> ActualInferenceSource.UNKNOWN
        }
}
