package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.NetworkMonitor
import com.example.data.ai.DocumentIntelligenceService
import com.example.data.ai.HybridAiService
import com.example.data.extraction.DocumentAnalysisCheckpointRecord
import com.example.data.extraction.DocumentCheckpointStore
import com.example.data.extraction.DocumentChunker
import com.example.data.extraction.DocumentExtractionEngine
import com.example.data.model.ActualInferenceSource
import com.example.data.model.AiAuditLog
import com.example.data.model.AiTaskType
import com.example.data.model.AssistantMessage
import com.example.data.model.ChunkProcessingStatus
import com.example.data.model.DocumentAnalysis
import com.example.data.model.DocumentAnalysisJobState
import com.example.data.model.DocumentProcessingPhase
import com.example.data.model.DocumentSection
import com.example.data.model.ExtractedEmbeddedMedia
import com.example.data.model.ExtractionMethod
import com.example.data.model.ExtractionStatus
import com.example.data.model.HybridInferenceMode
import com.example.data.model.HybridInferenceResult
import com.example.data.model.OnDeviceCapabilityStatus
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.model.VaultItem
import com.example.data.model.VaultItemType
import com.example.data.repository.DocumentImportHelper
import com.example.data.repository.VaultRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "NexusViewModel"
private const val STOP_TIMEOUT_MS = 5000L

data class NoteAiToolState(
    val runningTask: AiTaskType? = null,
    val lastTask: AiTaskType? = null,
    val lastResult: HybridInferenceResult? = null,
    val errorMessage: String? = null,
)

class NexusViewModel(
    private val repository: VaultRepository,
    private val aiService: HybridAiService,
    private val networkMonitor: NetworkMonitor,
    val currentUserId: String,
    val userDisplayName: String,
    val userEmail: String?,
    val userPhotoUrl: String?,
    val deviceCapability: OnDeviceCapabilityStatus,
    private val docIntelligenceService: DocumentIntelligenceService = DocumentIntelligenceService(),
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = networkMonitor.observeOnlineState()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = networkMonitor.isCurrentlyOnline()
        )

    val userProfileState: StateFlow<UiState<UserProfile?>> = repository.observeUserProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing user profile", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load user profile"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    val vaultState: StateFlow<UiState<List<VaultItem>>> = repository.observeVaultItems(currentUserId)
        .map<List<VaultItem>, UiState<List<VaultItem>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing Knowledge Vault", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load Knowledge Vault"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    val documentAnalyses: StateFlow<Map<String, DocumentAnalysis>> =
        repository.observeDocumentAnalyses(currentUserId)
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                Log.w(TAG, "Error observing document analyses", firestoreError ?: error)
                emit(emptyMap())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyMap()
            )

    val aiLogsState: StateFlow<UiState<List<AiAuditLog>>> = repository.observeAiLogs(currentUserId)
        .map<List<AiAuditLog>, UiState<List<AiAuditLog>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing AI audit logs", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load AI processing history"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTypeFilter = MutableStateFlow<VaultItemType?>(null)
    val selectedTypeFilter: StateFlow<VaultItemType?> = _selectedTypeFilter.asStateFlow()

    private val _selectedCategoryFilter = MutableStateFlow<String?>(null)
    val selectedCategoryFilter: StateFlow<String?> = _selectedCategoryFilter.asStateFlow()

    private val _activeInferenceMode = MutableStateFlow(HybridInferenceMode.PREFER_ON_DEVICE)
    val activeInferenceMode: StateFlow<HybridInferenceMode> = _activeInferenceMode.asStateFlow()

    private val _selectedContextItemIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedContextItemIds: StateFlow<Set<String>> = _selectedContextItemIds.asStateFlow()

    private val _messages = MutableStateFlow<List<AssistantMessage>>(emptyList())
    val messages: StateFlow<List<AssistantMessage>> = _messages.asStateFlow()

    private val _isAssistantBusy = MutableStateFlow(false)
    val isAssistantBusy: StateFlow<Boolean> = _isAssistantBusy.asStateFlow()

    private val _noteAiToolState = MutableStateFlow(NoteAiToolState())
    val noteAiToolState: StateFlow<NoteAiToolState> = _noteAiToolState.asStateFlow()

    private val _analysisJobs = MutableStateFlow<Map<String, DocumentAnalysisJobState>>(emptyMap())
    val analysisJobs: StateFlow<Map<String, DocumentAnalysisJobState>> = _analysisJobs.asStateFlow()

    // Active coroutine Job map prevents duplicate concurrent analysis requests and supports instant cancellation
    private val activeAnalysisJobs = mutableMapOf<String, Job>()

    private val _docQaConversations = MutableStateFlow<Map<String, List<AssistantMessage>>>(emptyMap())
    val docQaConversations: StateFlow<Map<String, List<AssistantMessage>>> = _docQaConversations.asStateFlow()

    private val _docQaBusyItemIds = MutableStateFlow<Set<String>>(emptySet())
    val docQaBusyItemIds: StateFlow<Set<String>> = _docQaBusyItemIds.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureUserProfile(currentUserId, userDisplayName, userEmail)
        }
        viewModelScope.launch {
            userProfileState.collect { state ->
                if (state is UiState.Success && state.data != null) {
                    _activeInferenceMode.value = state.data.resolvedMode()
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setTypeFilter(type: VaultItemType?) {
        _selectedTypeFilter.value = type
    }

    fun setCategoryFilter(category: String?) {
        _selectedCategoryFilter.value = category
    }

    fun setInferenceMode(mode: HybridInferenceMode, persistToProfile: Boolean = false) {
        _activeInferenceMode.value = mode
        if (persistToProfile) {
            viewModelScope.launch {
                val res = repository.updateDefaultInferenceMode(currentUserId, mode)
                if (res.isFailure) {
                    _statusBanner.value = res.exceptionOrNull()?.localizedMessage
                        ?: "Could not save default inference preference."
                } else {
                    _statusBanner.value = "Default routing updated to ${mode.label}."
                }
            }
        }
    }

    fun toggleContextItem(itemId: String) {
        _selectedContextItemIds.update { current ->
            if (itemId in current) current - itemId else current + itemId
        }
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun clearNoteAiToolState() {
        _noteAiToolState.value = NoteAiToolState()
    }

    fun createNote(
        title: String,
        content: String,
        category: String,
        tagsCsv: String,
    ) {
        val tags = parseTags(tagsCsv)
        viewModelScope.launch {
            val result = repository.createVaultItem(
                userId = currentUserId,
                itemType = VaultItemType.NOTE,
                title = title,
                content = content,
                category = category,
                tags = tags,
                extractionStatus = ExtractionStatus.NOT_APPLICABLE,
                extractionMethod = ExtractionMethod.DIRECT_TEXT,
            )
            _statusBanner.value = if (result.isSuccess) {
                if (isOnline.value) "Note saved to Knowledge Vault." else "Note saved offline — will sync when online."
            } else {
                result.exceptionOrNull()?.localizedMessage ?: "Failed to save note."
            }
        }
    }

    fun saveLink(
        title: String,
        url: String,
        notes: String,
        category: String,
        tagsCsv: String,
    ) {
        val normalizedUrl = url.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
        val cleanTitle = title.trim().ifBlank { normalizedUrl.removePrefix("https://").removePrefix("http://").take(80) }
        val tags = parseTags(tagsCsv)
        viewModelScope.launch {
            val result = repository.createVaultItem(
                userId = currentUserId,
                itemType = VaultItemType.LINK,
                title = cleanTitle,
                content = notes.trim(),
                category = category.ifBlank { "Links" },
                tags = tags,
                sourceUrl = normalizedUrl,
                extractionStatus = ExtractionStatus.NOT_APPLICABLE,
                extractionMethod = ExtractionMethod.DIRECT_TEXT,
            )
            _statusBanner.value = if (result.isSuccess) {
                "Web link saved to Knowledge Vault."
            } else {
                result.exceptionOrNull()?.localizedMessage ?: "Failed to save link."
            }
        }
    }

    /**
     * Imports a file via SAF, preserves the original untouched in app-private storage,
     * extracts real structured content (or queues PDF/Image multimodal OCR), saves to Firestore,
     * opens the dedicated Document Analysis page, and runs real content-grounded analysis.
     */
    fun importDocument(
        context: Context,
        uri: Uri,
        onOpenAnalysisPage: ((String) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            _statusBanner.value = "Reading & preserving uploaded file..."
            val imported = withContext(Dispatchers.IO) {
                DocumentImportHelper.importFromUri(appContext, uri)
            }
            imported.onSuccess { doc ->
                val extTag = doc.fileName.substringAfterLast('.', "").lowercase()
                    .takeIf { it.isNotBlank() && it.length <= 12 }
                val tags = buildList {
                    add("document")
                    if (extTag != null) add(extTag)
                }.distinct()

                val createRes = repository.createVaultItem(
                    userId = currentUserId,
                    itemType = VaultItemType.DOCUMENT,
                    title = doc.title,
                    content = doc.combinedText,
                    category = "Docs",
                    tags = tags,
                    fileName = doc.fileName,
                    mimeType = doc.mimeType,
                    localFilePath = doc.localFilePath,
                    extractionStatus = doc.status,
                    extractionMethod = doc.method,
                    pageCount = doc.pageCount.toLong(),
                    sizeBytes = doc.sizeBytes,
                )

                createRes.onSuccess { itemId ->
                    withContext(Dispatchers.IO) {
                        if (doc.sections.isNotEmpty()) {
                            DocumentExtractionEngine.persistExtractedSections(appContext, itemId, doc.sections)
                        }
                        if (doc.embeddedMedia.isNotEmpty()) {
                            DocumentCheckpointStore.saveEmbeddedMediaManifest(appContext, itemId, doc.embeddedMedia)
                        }
                    }

                    onOpenAnalysisPage?.invoke(itemId)

                    when {
                        doc.status == ExtractionStatus.FAILED -> {
                            updateAnalysisJob(
                                itemId,
                                DocumentAnalysisJobState(
                                    running = false,
                                    phase = DocumentProcessingPhase.FAILED,
                                    uploadProgress = 1f,
                                    extractionProgress = 0f,
                                    errorMessage = doc.disclosure,
                                )
                            )
                            _statusBanner.value = "Imported ${doc.fileName} — extraction failed: ${doc.disclosure}"
                        }

                        doc.requiresMultimodalOcr || doc.sections.isNotEmpty() || doc.embeddedMedia.isNotEmpty() -> {
                            _statusBanner.value = "Imported ${doc.fileName} (${DocumentImportHelper.formatBytes(doc.sizeBytes)}). Streaming content analysis..."
                            val syntheticItem = VaultItem(
                                id = itemId,
                                userId = currentUserId,
                                itemType = VaultItemType.DOCUMENT.name,
                                title = doc.title,
                                content = doc.combinedText,
                                category = "Docs",
                                tags = tags,
                                fileName = doc.fileName,
                                mimeType = doc.mimeType,
                                localFilePath = doc.localFilePath,
                                extractionStatus = doc.status.name,
                                extractionMethod = doc.method.name,
                                pageCount = doc.pageCount.toLong(),
                                sizeBytes = doc.sizeBytes,
                            )
                            launchTrackedAnalysis(
                                context = appContext,
                                item = syntheticItem,
                                preExtractedSections = doc.sections.takeIf { it.isNotEmpty() },
                                preExtractedMedia = doc.embeddedMedia,
                                initialMethod = doc.method,
                                initialStatus = doc.status,
                                initialDisclosure = doc.disclosure,
                                forceOcrFromOriginal = doc.requiresMultimodalOcr,
                                resumeFromCheckpoint = false,
                                onlyRetryFailed = false,
                            )
                        }
                    }
                }.onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Failed to store imported document."
                }
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Failed to read selected file."
            }
        }
    }

    /**
     * Hydrates any persisted chunk checkpoint for a document when opening the analysis workspace
     * so Resume / Retry Failed Sections remain available even after closing the app.
     */
    fun hydrateCheckpointStatus(context: Context, itemId: String) {
        if (activeAnalysisJobs[itemId]?.isActive == true) return
        val appContext = context.applicationContext
        viewModelScope.launch {
            val cp = withContext(Dispatchers.IO) { DocumentCheckpointStore.loadCheckpoint(appContext, itemId) }
                ?: return@launch
            val completed = cp.chunks.count { it.status == ChunkProcessingStatus.COMPLETED }
            val failed = cp.chunks.count { it.status == ChunkProcessingStatus.FAILED }
            val pending = cp.chunks.count { it.status == ChunkProcessingStatus.PENDING }
            if (failed > 0 || pending > 0) {
                val existing = _analysisJobs.value[itemId]
                if (existing?.running == true) return@launch
                updateAnalysisJob(
                    itemId,
                    DocumentAnalysisJobState(
                        running = false,
                        phase = if (failed > 0) DocumentProcessingPhase.PARTIAL else DocumentProcessingPhase.CANCELLED,
                        stageLabel = "Saved Checkpoint Available",
                        detail = "$completed/${cp.chunks.size} chunk(s) already completed and saved locally ($failed failed, $pending pending).",
                        uploadProgress = 1f,
                        extractionProgress = 1f,
                        chunksCompleted = completed,
                        chunksFailed = failed,
                        totalChunks = cp.chunks.size,
                        chunkStatuses = cp.chunks,
                        canResume = pending > 0 || failed > 0,
                        canRetryFailed = failed > 0,
                        errorMessage = existing?.errorMessage,
                    )
                )
            }
        }
    }

    /**
     * Starts a fresh full extraction + streaming analysis (clears stale chunk checkpoints).
     * Duplicate concurrent requests for the same document are safely ignored.
     */
    fun analyzeOrReanalyzeItem(context: Context, item: VaultItem) {
        if (activeAnalysisJobs[item.id]?.isActive == true) {
            _statusBanner.value = "Analysis is already running for \"${item.title}\"."
            return
        }
        val appContext = context.applicationContext
        launchTrackedAnalysis(
            context = appContext,
            item = item,
            preExtractedSections = null,
            preExtractedMedia = emptyList(),
            initialMethod = item.resolvedExtractionMethod(),
            initialStatus = item.resolvedExtractionStatus(),
            initialDisclosure = "",
            forceOcrFromOriginal = false,
            resumeFromCheckpoint = false,
            onlyRetryFailed = false,
        )
    }

    /**
     * Resumes an interrupted or cancelled document analysis from the last saved chunk checkpoint
     * without re-running already-completed chunks.
     */
    fun resumeDocumentAnalysis(context: Context, item: VaultItem) {
        if (activeAnalysisJobs[item.id]?.isActive == true) return
        launchTrackedAnalysis(
            context = context.applicationContext,
            item = item,
            preExtractedSections = null,
            preExtractedMedia = emptyList(),
            initialMethod = item.resolvedExtractionMethod(),
            initialStatus = item.resolvedExtractionStatus(),
            initialDisclosure = "",
            forceOcrFromOriginal = false,
            resumeFromCheckpoint = true,
            onlyRetryFailed = false,
        )
    }

    /**
     * Retries only the chunks that previously failed and re-synthesizes the complete report.
     */
    fun retryFailedSections(context: Context, item: VaultItem) {
        if (activeAnalysisJobs[item.id]?.isActive == true) return
        launchTrackedAnalysis(
            context = context.applicationContext,
            item = item,
            preExtractedSections = null,
            preExtractedMedia = emptyList(),
            initialMethod = item.resolvedExtractionMethod(),
            initialStatus = item.resolvedExtractionStatus(),
            initialDisclosure = "",
            forceOcrFromOriginal = false,
            resumeFromCheckpoint = true,
            onlyRetryFailed = true,
        )
    }

    /**
     * Cancels a running document analysis cleanly while keeping every already-completed chunk
     * saved on disk so the user can resume at any time.
     */
    fun cancelDocumentAnalysis(context: Context, itemId: String) {
        val job = activeAnalysisJobs.remove(itemId)
        job?.cancel(CancellationException("Cancelled by user"))
        val appContext = context.applicationContext
        viewModelScope.launch {
            val cp = withContext(Dispatchers.IO) { DocumentCheckpointStore.loadCheckpoint(appContext, itemId) }
            val completed = cp?.chunks?.count { it.status == ChunkProcessingStatus.COMPLETED } ?: 0
            val failed = cp?.chunks?.count { it.status == ChunkProcessingStatus.FAILED } ?: 0
            val total = cp?.chunks?.size ?: 0
            updateAnalysisJob(
                itemId,
                DocumentAnalysisJobState(
                    running = false,
                    phase = DocumentProcessingPhase.CANCELLED,
                    stageLabel = "Analysis Cancelled",
                    detail = if (total > 0) {
                        "Cancelled by user. $completed/$total completed chunk(s) remain saved — tap Resume anytime."
                    } else {
                        "Analysis cancelled by user."
                    },
                    uploadProgress = 1f,
                    extractionProgress = 1f,
                    chunksCompleted = completed,
                    chunksFailed = failed,
                    totalChunks = total,
                    chunkStatuses = cp?.chunks.orEmpty(),
                    canResume = true,
                    canRetryFailed = failed > 0,
                    errorMessage = null,
                )
            )
            _statusBanner.value = "Analysis cancelled. Completed progress preserved."
        }
    }

    private fun launchTrackedAnalysis(
        context: Context,
        item: VaultItem,
        preExtractedSections: List<DocumentSection>?,
        preExtractedMedia: List<ExtractedEmbeddedMedia>,
        initialMethod: ExtractionMethod,
        initialStatus: ExtractionStatus,
        initialDisclosure: String,
        forceOcrFromOriginal: Boolean,
        resumeFromCheckpoint: Boolean,
        onlyRetryFailed: Boolean,
    ) {
        val itemId = item.id
        if (activeAnalysisJobs[itemId]?.isActive == true) return
        val job = viewModelScope.launch {
            try {
                runFullDocumentAnalysis(
                    context = context,
                    item = item,
                    preExtractedSections = preExtractedSections,
                    preExtractedMedia = preExtractedMedia,
                    initialMethod = initialMethod,
                    initialStatus = initialStatus,
                    initialDisclosure = initialDisclosure,
                    forceOcrFromOriginal = forceOcrFromOriginal,
                    resumeFromCheckpoint = resumeFromCheckpoint,
                    onlyRetryFailed = onlyRetryFailed,
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                activeAnalysisJobs.remove(itemId)
            }
        }
        activeAnalysisJobs[itemId] = job
    }

    private suspend fun runFullDocumentAnalysis(
        context: Context,
        item: VaultItem,
        preExtractedSections: List<DocumentSection>?,
        preExtractedMedia: List<ExtractedEmbeddedMedia>,
        initialMethod: ExtractionMethod,
        initialStatus: ExtractionStatus,
        initialDisclosure: String,
        forceOcrFromOriginal: Boolean,
        resumeFromCheckpoint: Boolean,
        onlyRetryFailed: Boolean,
    ) {
        val itemId = item.id
        val checkpointDir = DocumentExtractionEngine.originalsDirectory(context)
        if (!resumeFromCheckpoint) {
            withContext(Dispatchers.IO) { DocumentCheckpointStore.clearCheckpoint(checkpointDir, itemId) }
        }
        val existingCheckpoint: DocumentAnalysisCheckpointRecord? = if (resumeFromCheckpoint) {
            withContext(Dispatchers.IO) { DocumentCheckpointStore.loadCheckpoint(checkpointDir, itemId) }
        } else null

        var sections = preExtractedSections.orEmpty()
        var embeddedMedia = preExtractedMedia.ifEmpty {
            withContext(Dispatchers.IO) { DocumentCheckpointStore.loadEmbeddedMediaManifest(checkpointDir, itemId) }
        }
        var method = initialMethod
        var status = initialStatus
        var disclosure = initialDisclosure
        var pageCount = (item.pageCount ?: 0L).toInt()
        val localFile = item.localFilePath?.takeIf { it.isNotBlank() }?.let { File(it) }

        updateAnalysisJob(
            itemId,
            DocumentAnalysisJobState(
                running = true,
                phase = DocumentProcessingPhase.EXTRACTING,
                stageLabel = if (resumeFromCheckpoint) "Resuming Document Analysis" else "Extracting Document Structure",
                detail = "Inspecting ${item.fileName ?: item.title}...",
                progressFraction = 0.06f,
                uploadProgress = 1f,
                extractionProgress = 0.25f,
            )
        )

        // Step 1: Ensure we have real extracted sections from the source
        if (sections.isEmpty()) {
            val savedSections = withContext(Dispatchers.IO) {
                DocumentExtractionEngine.loadExtractedSections(context, itemId)
            }
            if (savedSections.isNotEmpty() && !forceOcrFromOriginal && item.resolvedExtractionStatus() != ExtractionStatus.FAILED) {
                sections = savedSections
                if (disclosure.isBlank()) {
                    disclosure = "Loaded ${sections.size} extracted section(s) from preserved local index."
                }
            } else if (localFile != null && localFile.exists()) {
                val reExtracted = withContext(Dispatchers.IO) {
                    DocumentExtractionEngine.extractFromPreservedFile(
                        file = localFile,
                        title = item.title,
                        fileName = item.fileName ?: item.title,
                        mimeType = item.mimeType ?: "application/octet-stream",
                        sizeBytes = item.sizeBytes,
                    )
                }
                method = reExtracted.method
                status = reExtracted.status
                disclosure = reExtracted.disclosure
                pageCount = reExtracted.pageCount
                sections = reExtracted.sections
                if (reExtracted.embeddedMedia.isNotEmpty()) {
                    embeddedMedia = reExtracted.embeddedMedia
                    withContext(Dispatchers.IO) {
                        DocumentCheckpointStore.saveEmbeddedMediaManifest(checkpointDir, itemId, embeddedMedia)
                    }
                }

                if (reExtracted.status == ExtractionStatus.FAILED) {
                    repository.updateVaultItemExtraction(
                        userId = currentUserId,
                        itemId = itemId,
                        content = "",
                        extractionStatus = ExtractionStatus.FAILED,
                        extractionMethod = method,
                        pageCount = 0L,
                    )
                    updateAnalysisJob(
                        itemId,
                        DocumentAnalysisJobState(
                            running = false,
                            phase = DocumentProcessingPhase.FAILED,
                            uploadProgress = 1f,
                            extractionProgress = 0f,
                            errorMessage = reExtracted.disclosure,
                        )
                    )
                    return
                }

                // Multimodal PDF / Image OCR step via Firebase AI Logic Cloud
                if (reExtracted.requiresMultimodalOcr) {
                    if (!isOnline.value) {
                        val offlineMsg = "Offline: Multimodal ${method.label} requires internet connectivity to Firebase AI Logic Cloud. Original file is safely preserved — reconnect and tap Reanalyse."
                        updateAnalysisJob(
                            itemId,
                            DocumentAnalysisJobState(
                                running = false,
                                phase = DocumentProcessingPhase.FAILED,
                                uploadProgress = 1f,
                                canResume = true,
                                errorMessage = offlineMsg,
                            )
                        )
                        return
                    }

                    val ocrRes = when (method) {
                        ExtractionMethod.PDF_MULTIMODAL_OCR -> {
                            docIntelligenceService.extractPdfWithMultimodalOcr(
                                localFile = localFile,
                                fileName = item.fileName ?: item.title,
                                totalPages = pageCount.coerceAtLeast(1),
                                onProgress = { stage, detail, frac, stream ->
                                    updateAnalysisJob(
                                        itemId,
                                        DocumentAnalysisJobState(
                                            running = true,
                                            phase = DocumentProcessingPhase.OCR_VISUALS,
                                            stageLabel = stage,
                                            detail = detail,
                                            progressFraction = frac * 0.45f,
                                            uploadProgress = 1f,
                                            extractionProgress = frac,
                                            totalSections = pageCount,
                                            streamingOutput = stream,
                                        )
                                    )
                                }
                            )
                        }

                        ExtractionMethod.MEDIA_MULTIMODAL -> {
                            updateAnalysisJob(
                                itemId,
                                DocumentAnalysisJobState(
                                    running = true,
                                    phase = DocumentProcessingPhase.OCR_VISUALS,
                                    stageLabel = "Streaming Audio / Video Transcription",
                                    detail = "Transcribing & analyzing media via Firebase AI Logic Cloud...",
                                    progressFraction = 0.25f,
                                    uploadProgress = 1f,
                                    extractionProgress = 0.4f,
                                    totalSections = 1,
                                )
                            )
                            docIntelligenceService.extractMediaWithMultimodal(
                                localFile = localFile,
                                fileName = item.fileName ?: item.title,
                                mimeType = reExtracted.mimeType,
                                onStream = { stream ->
                                    updateAnalysisJob(
                                        itemId,
                                        DocumentAnalysisJobState(
                                            running = true,
                                            phase = DocumentProcessingPhase.OCR_VISUALS,
                                            stageLabel = "Streaming Media Multimodal Analysis",
                                            detail = "Transcribing spoken audio & observable visual details...",
                                            progressFraction = 0.38f,
                                            uploadProgress = 1f,
                                            extractionProgress = 0.7f,
                                            totalSections = 1,
                                            streamingOutput = stream,
                                        )
                                    )
                                }
                            )
                        }

                        else -> {
                            updateAnalysisJob(
                                itemId,
                                DocumentAnalysisJobState(
                                    running = true,
                                    phase = DocumentProcessingPhase.OCR_VISUALS,
                                    stageLabel = "Running Visual & OCR Understanding",
                                    detail = "Reading visible text, charts, and diagrams via Firebase AI Logic Cloud...",
                                    progressFraction = 0.25f,
                                    uploadProgress = 1f,
                                    extractionProgress = 0.4f,
                                    totalSections = 1,
                                )
                            )
                            docIntelligenceService.extractImageWithVisualOcr(
                                localFile = localFile,
                                fileName = item.fileName ?: item.title,
                                mimeType = reExtracted.mimeType,
                                onStream = { stream ->
                                    updateAnalysisJob(
                                        itemId,
                                        DocumentAnalysisJobState(
                                            running = true,
                                            phase = DocumentProcessingPhase.OCR_VISUALS,
                                            stageLabel = "Streaming Image OCR & Visual Analysis",
                                            detail = "Transcribing text and observable details...",
                                            progressFraction = 0.38f,
                                            uploadProgress = 1f,
                                            extractionProgress = 0.7f,
                                            totalSections = 1,
                                            streamingOutput = stream,
                                        )
                                    )
                                }
                            )
                        }
                    }

                    ocrRes.onSuccess { ocr ->
                        sections = ocr.sections
                        status = ocr.status
                        disclosure = ocr.disclosure
                        withContext(Dispatchers.IO) {
                            DocumentExtractionEngine.persistExtractedSections(context, itemId, ocr.sections)
                        }
                        repository.updateVaultItemExtraction(
                            userId = currentUserId,
                            itemId = itemId,
                            content = ocr.combinedText,
                            extractionStatus = ocr.status,
                            extractionMethod = method,
                            pageCount = pageCount.toLong().coerceAtLeast(ocr.sections.size.toLong()),
                        )
                        repository.recordAiLog(
                            userId = currentUserId,
                            taskType = AiTaskType.DOC_OCR,
                            promptPreview = "OCR & Extract: ${item.fileName ?: item.title}",
                            requestedMode = ocr.requestedMode,
                            actualSource = ocr.actualSource,
                            modelName = ocr.modelName,
                            transmittedToCloud = ocr.actualSource == ActualInferenceSource.IN_CLOUD,
                            latencyMs = ocr.latencyMs,
                            succeeded = true,
                        )
                    }.onFailure { err ->
                        val msg = err.localizedMessage ?: "Multimodal OCR extraction failed."
                        repository.updateVaultItemExtraction(
                            userId = currentUserId,
                            itemId = itemId,
                            content = "",
                            extractionStatus = ExtractionStatus.FAILED,
                            extractionMethod = method,
                            pageCount = pageCount.toLong(),
                        )
                        repository.recordAiLog(
                            userId = currentUserId,
                            taskType = AiTaskType.DOC_OCR,
                            promptPreview = "OCR Failed: ${item.fileName ?: item.title}",
                            requestedMode = HybridInferenceMode.ONLY_IN_CLOUD,
                            actualSource = ActualInferenceSource.UNKNOWN,
                            modelName = DocumentIntelligenceService.CLOUD_MULTIMODAL_MODEL,
                            transmittedToCloud = true,
                            latencyMs = 0L,
                            succeeded = false,
                        )
                        updateAnalysisJob(
                            itemId,
                            DocumentAnalysisJobState(
                                running = false,
                                phase = DocumentProcessingPhase.FAILED,
                                uploadProgress = 1f,
                                canResume = true,
                                errorMessage = msg,
                            )
                        )
                        return
                    }
                } else if (sections.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        DocumentExtractionEngine.persistExtractedSections(context, itemId, sections)
                    }
                    repository.updateVaultItemExtraction(
                        userId = currentUserId,
                        itemId = itemId,
                        content = reExtracted.combinedText,
                        extractionStatus = status,
                        extractionMethod = method,
                        pageCount = pageCount.toLong(),
                    )
                }
            } else if (item.content.isNotBlank()) {
                sections = DocumentChunker.parseSectionsFromFormattedText(item.content)
                if (method == ExtractionMethod.UNKNOWN) method = ExtractionMethod.DIRECT_TEXT
                if (status == ExtractionStatus.NOT_APPLICABLE) status = ExtractionStatus.COMPLETE
                if (disclosure.isBlank()) {
                    disclosure = "Analyzed ${item.content.length} characters across ${sections.size} section(s)."
                }
            }
        }

        // Step 1b: Analyze embedded images inside DOCX (`word/media/*`) or PPTX (`ppt/media/*`) via Gemini Vision
        val failedEmbeddedVisuals = mutableListOf<String>()
        if (embeddedMedia.isNotEmpty() && isOnline.value) {
            val existingVisualNotes = existingCheckpoint?.embeddedVisualNotes?.toMutableMap() ?: mutableMapOf()
            val visualsOutcome = docIntelligenceService.analyzeEmbeddedVisuals(
                mediaList = embeddedMedia,
                existingNotes = existingVisualNotes,
                startingSectionIndex = sections.size + 1,
                onVisualProgress = { idx, total, label, stream ->
                    updateAnalysisJob(
                        itemId,
                        DocumentAnalysisJobState(
                            running = true,
                            phase = DocumentProcessingPhase.OCR_VISUALS,
                            stageLabel = "Analyzing Embedded Visual $idx/$total",
                            detail = "Gemini multimodal vision reading $label...",
                            progressFraction = 0.25f + (0.20f * (idx.toFloat() / total.toFloat())),
                            uploadProgress = 1f,
                            extractionProgress = 0.85f,
                            sectionsExtracted = sections.size + idx,
                            totalSections = sections.size + total,
                            streamingOutput = stream,
                        )
                    )
                },
                onNotePersisted = { mediaId, note ->
                    existingVisualNotes[mediaId] = note
                }
            )
            if (visualsOutcome.visualSections.isNotEmpty()) {
                val existingLabels = sections.map { it.referenceLabel }.toSet()
                val newVisualSections = visualsOutcome.visualSections.filter { it.referenceLabel !in existingLabels }
                if (newVisualSections.isNotEmpty()) {
                    sections = sections + newVisualSections
                    withContext(Dispatchers.IO) {
                        DocumentExtractionEngine.persistExtractedSections(context, itemId, sections)
                    }
                }
            }
            failedEmbeddedVisuals.addAll(visualsOutcome.failedLabels)
        } else if (embeddedMedia.isNotEmpty() && !isOnline.value) {
            failedEmbeddedVisuals.add("${embeddedMedia.size} embedded image(s) skipped while offline")
        }

        // Guard: NEVER analyze empty content or invent facts from filename!
        if (sections.isEmpty()) {
            updateAnalysisJob(
                itemId,
                DocumentAnalysisJobState(
                    running = false,
                    phase = DocumentProcessingPhase.FAILED,
                    uploadProgress = 1f,
                    extractionProgress = 0f,
                    errorMessage = "No extractable text or visual content is available for this item. NEXUS AI will not fabricate an analysis from a filename.",
                )
            )
            return
        }

        if (!isOnline.value) {
            updateAnalysisJob(
                itemId,
                DocumentAnalysisJobState(
                    running = false,
                    phase = DocumentProcessingPhase.FAILED,
                    uploadProgress = 1f,
                    extractionProgress = 1f,
                    sectionsExtracted = sections.size,
                    totalSections = sections.size,
                    canResume = true,
                    errorMessage = "Offline: Extracted content is safely preserved (${sections.size} section(s)), but structured Gemini analysis requires internet connectivity. Reconnect and tap Resume.",
                )
            )
            return
        }

        // Step 2: Chunk into bounded 6KB segments and run resilient, checkpointed streaming analysis
        val chunks = DocumentChunker.chunkSections(sections)
        val fullPreview = DocumentExtractionEngine.formatSectionsForPreview(sections)

        val analysisRes = docIntelligenceService.analyzeDocumentResilient(
            userId = currentUserId,
            itemId = itemId,
            title = item.title,
            fileName = item.fileName,
            extractionMethod = method,
            extractionStatus = status,
            extractionDisclosure = disclosure,
            chunks = chunks,
            fullExtractedPreview = fullPreview,
            checkpointDir = checkpointDir,
            existingCheckpoint = existingCheckpoint,
            onlyRetryFailed = onlyRetryFailed,
            failedEmbeddedVisuals = failedEmbeddedVisuals,
            onProgress = { progress ->
                val doneCount = progress.chunkStatuses.count { it.status == ChunkProcessingStatus.COMPLETED }
                val failCount = progress.chunkStatuses.count { it.status == ChunkProcessingStatus.FAILED }
                updateAnalysisJob(
                    itemId,
                    DocumentAnalysisJobState(
                        running = true,
                        phase = progress.phase,
                        stageLabel = progress.stageLabel,
                        detail = progress.detail,
                        progressFraction = 0.45f + (progress.progressFraction * 0.53f),
                        uploadProgress = 1f,
                        extractionProgress = 1f,
                        sectionsExtracted = sections.size,
                        totalSections = sections.size,
                        chunksCompleted = doneCount,
                        chunksFailed = failCount,
                        totalChunks = chunks.size,
                        streamingOutput = progress.streamingPreview,
                        chunkStatuses = progress.chunkStatuses,
                    )
                )
            }
        )

        analysisRes.onSuccess { outcome ->
            val analysis = outcome.analysis
            val saveRes = repository.saveDocumentAnalysis(currentUserId, analysis)
            repository.attachAiMetadata(
                userId = currentUserId,
                itemId = itemId,
                aiSummary = analysis.summary.take(3900),
            )
            repository.recordAiLog(
                userId = currentUserId,
                taskType = AiTaskType.DOC_ANALYSIS,
                promptPreview = "Analyze (${analysis.chunksProcessed}/${analysis.totalChunks} chunks): ${item.title}",
                requestedMode = analysis.resolvedRequestedMode(),
                actualSource = analysis.resolvedActualSource(),
                modelName = analysis.modelName,
                transmittedToCloud = analysis.resolvedActualSource() == ActualInferenceSource.IN_CLOUD,
                latencyMs = analysis.latencyMs,
                succeeded = true,
            )
            val completedCount = outcome.chunkCheckpoints.count { it.status == ChunkProcessingStatus.COMPLETED }
            val failedCount = outcome.chunkCheckpoints.count { it.status == ChunkProcessingStatus.FAILED }

            if (saveRes.isFailure) {
                updateAnalysisJob(
                    itemId,
                    DocumentAnalysisJobState(
                        running = false,
                        phase = DocumentProcessingPhase.FAILED,
                        uploadProgress = 1f,
                        extractionProgress = 1f,
                        sectionsExtracted = sections.size,
                        totalSections = sections.size,
                        chunksCompleted = completedCount,
                        chunksFailed = failedCount,
                        totalChunks = chunks.size,
                        chunkStatuses = outcome.chunkCheckpoints,
                        canResume = true,
                        canRetryFailed = failedCount > 0,
                        errorMessage = "Analysis generated, but saving to Firestore failed: ${saveRes.exceptionOrNull()?.localizedMessage}",
                    )
                )
            } else {
                updateAnalysisJob(
                    itemId,
                    DocumentAnalysisJobState(
                        running = false,
                        phase = if (outcome.hasFailedChunks) DocumentProcessingPhase.PARTIAL else DocumentProcessingPhase.COMPLETED,
                        stageLabel = if (outcome.hasFailedChunks) "Completed with $failedCount Failed Section(s)" else "Analysis Complete",
                        detail = if (outcome.hasFailedChunks) {
                            "Analyzed $completedCount/${chunks.size} chunks. Failed sections remain saved — tap Retry Failed Sections."
                        } else {
                            "All ${chunks.size} chunk(s) analyzed cleanly."
                        },
                        progressFraction = 1f,
                        uploadProgress = 1f,
                        extractionProgress = 1f,
                        sectionsExtracted = sections.size,
                        totalSections = sections.size,
                        chunksCompleted = completedCount,
                        chunksFailed = failedCount,
                        totalChunks = chunks.size,
                        chunkStatuses = outcome.chunkCheckpoints,
                        canResume = outcome.hasFailedChunks,
                        canRetryFailed = outcome.hasFailedChunks,
                    )
                )
                _statusBanner.value = if (outcome.hasFailedChunks) {
                    "Partial analysis ready ($completedCount/${chunks.size} chunks). Retry failed sections anytime."
                } else {
                    "Document analysis ready (${analysis.resolvedActualSource().badgeLabel})."
                }
            }
        }.onFailure { err ->
            val msg = err.localizedMessage ?: "Structured document analysis failed."
            val latestCp = withContext(Dispatchers.IO) { DocumentCheckpointStore.loadCheckpoint(checkpointDir, itemId) }
            val completedCount = latestCp?.chunks?.count { it.status == ChunkProcessingStatus.COMPLETED } ?: 0
            val failedCount = latestCp?.chunks?.count { it.status == ChunkProcessingStatus.FAILED } ?: 0

            repository.recordAiLog(
                userId = currentUserId,
                taskType = AiTaskType.DOC_ANALYSIS,
                promptPreview = "Analysis Failed: ${item.title}",
                requestedMode = HybridInferenceMode.PREFER_IN_CLOUD,
                actualSource = ActualInferenceSource.UNKNOWN,
                modelName = DocumentIntelligenceService.CLOUD_MULTIMODAL_MODEL,
                transmittedToCloud = true,
                latencyMs = 0L,
                succeeded = false,
            )
            updateAnalysisJob(
                itemId,
                DocumentAnalysisJobState(
                    running = false,
                    phase = DocumentProcessingPhase.FAILED,
                    uploadProgress = 1f,
                    extractionProgress = 1f,
                    sectionsExtracted = sections.size,
                    totalSections = sections.size,
                    chunksCompleted = completedCount,
                    chunksFailed = failedCount,
                    totalChunks = chunks.size,
                    chunkStatuses = latestCp?.chunks.orEmpty(),
                    canResume = true,
                    canRetryFailed = failedCount > 0,
                    errorMessage = msg,
                )
            )
        }
    }

    /**
     * Answers a follow-up question grounded across ALL chunks of a specific document ("Ask This Document").
     */
    fun askDocumentQuestion(
        context: Context,
        item: VaultItem,
        question: String,
        mode: HybridInferenceMode = _activeInferenceMode.value,
    ) {
        val trimmed = question.trim()
        val itemId = item.id
        if (trimmed.isEmpty() || itemId in _docQaBusyItemIds.value) return

        val requiresCloud = mode == HybridInferenceMode.ONLY_IN_CLOUD ||
            (!deviceCapability.sdkOnDeviceProviderAvailable && mode != HybridInferenceMode.ONLY_ON_DEVICE)

        if (!isOnline.value && requiresCloud) {
            appendDocQaMessages(
                itemId,
                AssistantMessage(UUID.randomUUID().toString(), true, trimmed),
                AssistantMessage(
                    id = UUID.randomUUID().toString(),
                    fromUser = false,
                    text = "Offline: Cloud fallback is unavailable without internet connectivity.",
                    actualSource = ActualInferenceSource.UNKNOWN,
                    requestedMode = mode,
                    error = true,
                )
            )
            return
        }

        val appContext = context.applicationContext
        val existingHistory = _docQaConversations.value[itemId].orEmpty()
            .filter { !it.error && it.text.isNotBlank() }
            .map { it.fromUser to it.text }

        val userMsg = AssistantMessage(UUID.randomUUID().toString(), true, trimmed)
        val aiMsgId = UUID.randomUUID().toString()
        val placeholder = AssistantMessage(
            id = aiMsgId,
            fromUser = false,
            text = "",
            requestedMode = mode,
            streaming = true,
        )
        appendDocQaMessages(itemId, userMsg, placeholder)
        _docQaBusyItemIds.update { it + itemId }

        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var finalSource = ActualInferenceSource.UNKNOWN
            var finalModel = if (mode == HybridInferenceMode.PREFER_ON_DEVICE) {
                HybridAiService.LOCAL_FALLBACK_MODEL
            } else {
                HybridAiService.CLOUD_REASONING_MODEL
            }
            var finalLatency = 0L

            try {
                val analysis = documentAnalyses.value[itemId]
                val sections = withContext(Dispatchers.IO) {
                    val local = DocumentExtractionEngine.loadExtractedSections(appContext, itemId)
                    when {
                        local.isNotEmpty() -> local
                        !analysis?.extractedPreview.isNullOrBlank() ->
                            DocumentChunker.parseSectionsFromFormattedText(analysis!!.extractedPreview)
                        item.content.isNotBlank() ->
                            DocumentChunker.parseSectionsFromFormattedText(item.content)
                        else -> emptyList()
                    }
                }
                val chunks = DocumentChunker.chunkSections(sections)

                docIntelligenceService.streamAskDocument(
                    question = trimmed,
                    item = item,
                    analysis = analysis,
                    allChunks = chunks,
                    conversationHistory = existingHistory,
                    mode = mode,
                ).collect { update ->
                    finalSource = update.actualSource
                    finalModel = update.modelName
                    finalLatency = update.latencyMs
                    updateDocQaMessage(itemId, aiMsgId) { msg ->
                        msg.copy(
                            text = update.accumulatedText,
                            actualSource = update.actualSource,
                            modelName = update.modelName,
                            latencyMs = update.latencyMs,
                            streaming = !update.completed,
                        )
                    }
                }

                repository.recordAiLog(
                    userId = currentUserId,
                    taskType = AiTaskType.DOC_QA,
                    promptPreview = "[${item.title.take(40)}] $trimmed",
                    requestedMode = mode,
                    actualSource = finalSource,
                    modelName = finalModel,
                    transmittedToCloud = finalSource == ActualInferenceSource.IN_CLOUD,
                    latencyMs = finalLatency,
                    succeeded = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startedAt
                updateDocQaMessage(itemId, aiMsgId) { msg ->
                    msg.copy(
                        text = e.localizedMessage ?: "Failed to answer question from document.",
                        actualSource = ActualInferenceSource.UNKNOWN,
                        latencyMs = elapsed,
                        streaming = false,
                        error = true,
                    )
                }
                repository.recordAiLog(
                    userId = currentUserId,
                    taskType = AiTaskType.DOC_QA,
                    promptPreview = "[${item.title.take(40)}] $trimmed",
                    requestedMode = mode,
                    actualSource = ActualInferenceSource.UNKNOWN,
                    modelName = finalModel,
                    transmittedToCloud = false,
                    latencyMs = elapsed,
                    succeeded = false,
                )
            } finally {
                _docQaBusyItemIds.update { it - itemId }
            }
        }
    }

    fun clearDocQaConversation(itemId: String) {
        _docQaConversations.update { it - itemId }
    }

    /**
     * Exports a formatted Markdown Document Intelligence Report via Android Share Sheet.
     */
    fun exportDocumentAnalysis(context: Context, item: VaultItem, analysis: DocumentAnalysis) {
        val markdown = DocumentChunker.exportAnalysisAsMarkdown(item, analysis)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, "NEXUS AI Analysis — ${item.title}")
            putExtra(Intent.EXTRA_TEXT, markdown)
        }
        val chooser = Intent.createChooser(sendIntent, "Export NEXUS AI Analysis").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(chooser)
            _statusBanner.value = "Prepared Markdown analysis report for export."
        }.onFailure { err ->
            _statusBanner.value = err.localizedMessage ?: "Could not open share sheet for export."
        }
    }

    fun updateVaultItem(
        itemId: String,
        title: String,
        content: String,
        category: String,
        tagsCsv: String,
        sourceUrl: String?,
    ) {
        val tags = parseTags(tagsCsv)
        viewModelScope.launch {
            val result = repository.updateVaultItem(
                userId = currentUserId,
                itemId = itemId,
                title = title,
                content = content,
                category = category,
                tags = tags,
                sourceUrl = sourceUrl,
            )
            _statusBanner.value = if (result.isSuccess) {
                "Knowledge item updated."
            } else {
                result.exceptionOrNull()?.localizedMessage ?: "Failed to update item."
            }
        }
    }

    fun togglePin(item: VaultItem) {
        viewModelScope.launch {
            val res = repository.togglePin(currentUserId, item.id, !item.pinned)
            if (res.isFailure) {
                _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Could not update pin state."
            }
        }
    }

    fun deleteVaultItem(itemId: String, context: Context? = null, localFilePath: String? = null) {
        activeAnalysisJobs.remove(itemId)?.cancel()
        viewModelScope.launch {
            val res = repository.deleteVaultItem(currentUserId, itemId)
            if (context != null) {
                withContext(Dispatchers.IO) {
                    DocumentExtractionEngine.deleteLocalArtifacts(context.applicationContext, itemId, localFilePath)
                }
            }
            _selectedContextItemIds.update { it - itemId }
            _analysisJobs.update { it - itemId }
            _docQaConversations.update { it - itemId }
            _statusBanner.value = if (res.isSuccess) {
                "Item deleted from Knowledge Vault."
            } else {
                res.exceptionOrNull()?.localizedMessage ?: "Failed to delete item."
            }
        }
    }

    /**
     * Executes a Local-Preferred (`PREFER_ON_DEVICE`) AI tool on a specific Vault item and logs
     * the authentic Firebase AI Logic `inferenceSource` into Firestore Privacy Center history.
     */
    fun runNoteAiTool(item: VaultItem, taskType: AiTaskType) {
        if (item.content.isBlank()) {
            _noteAiToolState.value = NoteAiToolState(
                runningTask = null,
                lastTask = taskType,
                errorMessage = "No extracted text content yet. Open Full Document Analysis to run OCR/extraction first."
            )
            return
        }
        val mode = HybridInferenceMode.PREFER_ON_DEVICE
        if (!isOnline.value && !deviceCapability.sdkOnDeviceProviderAvailable) {
            _noteAiToolState.value = NoteAiToolState(
                runningTask = null,
                lastTask = taskType,
                errorMessage = "You are offline and this device does not have Gemini Nano on-device inference provisioned. Reconnect to use Firebase AI Logic Cloud fallback."
            )
            return
        }

        viewModelScope.launch {
            _noteAiToolState.value = NoteAiToolState(runningTask = taskType)
            val startedAt = System.currentTimeMillis()
            when (taskType) {
                AiTaskType.SUMMARIZE -> {
                    val res = aiService.summarizeNote(item.title, item.content, mode)
                    handleNoteToolOutcome(item, taskType, mode, res, startedAt) { hybridRes ->
                        repository.attachAiMetadata(
                            userId = currentUserId,
                            itemId = item.id,
                            aiSummary = hybridRes.text
                        )
                    }
                }
                AiTaskType.REWRITE -> {
                    val res = aiService.rewriteText(item.title, item.content, mode = mode)
                    handleNoteToolOutcome(item, taskType, mode, res, startedAt)
                }
                AiTaskType.CLASSIFY -> {
                    val res = aiService.classifyAndTag(item.title, item.content)
                    res.onSuccess { (classification, hybridRes) ->
                        _noteAiToolState.value = NoteAiToolState(
                            runningTask = null,
                            lastTask = taskType,
                            lastResult = hybridRes,
                        )
                        repository.attachAiMetadata(
                            userId = currentUserId,
                            itemId = item.id,
                            category = classification.category,
                            tags = classification.tags,
                        )
                        logInference(taskType, item.title, hybridRes, succeeded = true)
                    }.onFailure { err ->
                        handleToolFailure(item.title, taskType, mode, err, startedAt)
                    }
                }
                AiTaskType.KEYWORDS -> {
                    val res = aiService.extractKeywords(item.title, item.content)
                    res.onSuccess { (_, hybridRes) ->
                        _noteAiToolState.value = NoteAiToolState(
                            runningTask = null,
                            lastTask = taskType,
                            lastResult = hybridRes,
                        )
                        logInference(taskType, item.title, hybridRes, succeeded = true)
                    }.onFailure { err ->
                        handleToolFailure(item.title, taskType, mode, err, startedAt)
                    }
                }
                AiTaskType.TASKS -> {
                    val res = aiService.extractTasks(item.title, item.content)
                    res.onSuccess { (_, hybridRes) ->
                        _noteAiToolState.value = NoteAiToolState(
                            runningTask = null,
                            lastTask = taskType,
                            lastResult = hybridRes,
                        )
                        logInference(taskType, item.title, hybridRes, succeeded = true)
                    }.onFailure { err ->
                        handleToolFailure(item.title, taskType, mode, err, startedAt)
                    }
                }
                else -> {
                    val res = aiService.summarizeNote(item.title, item.content, mode)
                    handleNoteToolOutcome(item, taskType, mode, res, startedAt)
                }
            }
        }
    }

    private suspend fun handleNoteToolOutcome(
        item: VaultItem,
        taskType: AiTaskType,
        mode: HybridInferenceMode,
        res: Result<HybridInferenceResult>,
        startedAt: Long,
        onPersist: (suspend (HybridInferenceResult) -> Unit)? = null,
    ) {
        res.onSuccess { hybridRes ->
            _noteAiToolState.value = NoteAiToolState(
                runningTask = null,
                lastTask = taskType,
                lastResult = hybridRes,
            )
            onPersist?.invoke(hybridRes)
            logInference(taskType, item.title, hybridRes, succeeded = true)
        }.onFailure { err ->
            handleToolFailure(item.title, taskType, mode, err, startedAt)
        }
    }

    private suspend fun handleToolFailure(
        preview: String,
        taskType: AiTaskType,
        mode: HybridInferenceMode,
        err: Throwable,
        startedAt: Long,
    ) {
        val elapsed = System.currentTimeMillis() - startedAt
        _noteAiToolState.value = NoteAiToolState(
            runningTask = null,
            lastTask = taskType,
            errorMessage = err.localizedMessage ?: "AI execution failed.",
        )
        repository.recordAiLog(
            userId = currentUserId,
            taskType = taskType,
            promptPreview = preview,
            requestedMode = mode,
            actualSource = ActualInferenceSource.UNKNOWN,
            modelName = HybridAiService.LOCAL_FALLBACK_MODEL,
            transmittedToCloud = false,
            latencyMs = elapsed,
            succeeded = false,
        )
    }

    /**
     * Sends a prompt to the Hybrid AI Assistant with streaming responses and selected Knowledge Vault context.
     */
    fun sendAssistantPrompt(
        prompt: String,
        taskType: AiTaskType = AiTaskType.CHAT,
        modeOverride: HybridInferenceMode? = null,
    ) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || _isAssistantBusy.value) return

        val mode = modeOverride ?: _activeInferenceMode.value
        val requiresCloud = mode == HybridInferenceMode.ONLY_IN_CLOUD ||
            (!deviceCapability.sdkOnDeviceProviderAvailable && mode != HybridInferenceMode.ONLY_ON_DEVICE)

        if (!isOnline.value && requiresCloud) {
            val errMsg = AssistantMessage(
                id = UUID.randomUUID().toString(),
                fromUser = false,
                text = "Offline: Cloud intelligence is unavailable without internet connectivity. You can still browse and edit cached notes in your Knowledge Vault.",
                actualSource = ActualInferenceSource.UNKNOWN,
                requestedMode = mode,
                error = true,
            )
            _messages.update {
                it + AssistantMessage(UUID.randomUUID().toString(), true, trimmed) + errMsg
            }
            return
        }

        val allItems = (vaultState.value as? UiState.Success)?.data.orEmpty()
        val chosenIds = _selectedContextItemIds.value
        val contextItems = if (chosenIds.isNotEmpty()) {
            allItems.filter { it.id in chosenIds }
        } else if (taskType == AiTaskType.VAULT_QA || taskType == AiTaskType.RESEARCH) {
            allItems.take(6)
        } else {
            allItems.take(4)
        }

        val history = _messages.value
            .filter { !it.error && it.text.isNotBlank() }
            .map { it.fromUser to it.text }

        val userMsg = AssistantMessage(
            id = UUID.randomUUID().toString(),
            fromUser = true,
            text = trimmed,
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val placeholderMsg = AssistantMessage(
            id = assistantMsgId,
            fromUser = false,
            text = "",
            requestedMode = mode,
            streaming = true,
        )
        _messages.update { it + userMsg + placeholderMsg }
        _isAssistantBusy.value = true

        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var finalSource = ActualInferenceSource.UNKNOWN
            var finalModel = if (mode == HybridInferenceMode.PREFER_ON_DEVICE) {
                HybridAiService.LOCAL_FALLBACK_MODEL
            } else {
                HybridAiService.CLOUD_REASONING_MODEL
            }
            var finalLatency = 0L

            try {
                aiService.streamVaultAssistant(
                    userPrompt = trimmed,
                    contextItems = contextItems,
                    conversationHistory = history,
                    mode = mode,
                ).collect { update ->
                    finalSource = update.actualSource
                    finalModel = update.modelName
                    finalLatency = update.latencyMs
                    _messages.update { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId) {
                                msg.copy(
                                    text = update.accumulatedText,
                                    actualSource = update.actualSource,
                                    modelName = update.modelName,
                                    latencyMs = update.latencyMs,
                                    streaming = !update.completed,
                                )
                            } else {
                                msg
                            }
                        }
                    }
                }

                repository.recordAiLog(
                    userId = currentUserId,
                    taskType = taskType,
                    promptPreview = trimmed,
                    requestedMode = mode,
                    actualSource = finalSource,
                    modelName = finalModel,
                    transmittedToCloud = finalSource == ActualInferenceSource.IN_CLOUD,
                    latencyMs = finalLatency,
                    succeeded = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startedAt
                _messages.update { list ->
                    list.map { msg ->
                        if (msg.id == assistantMsgId) {
                            msg.copy(
                                text = e.localizedMessage ?: "Hybrid AI execution failed.",
                                actualSource = ActualInferenceSource.UNKNOWN,
                                latencyMs = elapsed,
                                streaming = false,
                                error = true,
                            )
                        } else {
                            msg
                        }
                    }
                }
                repository.recordAiLog(
                    userId = currentUserId,
                    taskType = taskType,
                    promptPreview = trimmed,
                    requestedMode = mode,
                    actualSource = ActualInferenceSource.UNKNOWN,
                    modelName = finalModel,
                    transmittedToCloud = false,
                    latencyMs = elapsed,
                    succeeded = false,
                )
            } finally {
                _isAssistantBusy.value = false
            }
        }
    }

    fun clearConversation() {
        _messages.value = emptyList()
    }

    fun clearAiHistory() {
        viewModelScope.launch {
            val res = repository.clearAiLogs(currentUserId)
            _statusBanner.value = res.fold(
                onSuccess = { count -> "Cleared $count AI processing log entries." },
                onFailure = { it.localizedMessage ?: "Failed to clear AI history." }
            )
        }
    }

    fun deleteAllVaultData() {
        viewModelScope.launch {
            val res = repository.deleteAllVaultItems(currentUserId)
            _selectedContextItemIds.value = emptySet()
            _analysisJobs.value = emptyMap()
            _docQaConversations.value = emptyMap()
            _statusBanner.value = res.fold(
                onSuccess = { count -> "Deleted $count items and analyses from your Knowledge Vault." },
                onFailure = { it.localizedMessage ?: "Failed to delete vault items." }
            )
        }
    }

    private fun updateAnalysisJob(itemId: String, state: DocumentAnalysisJobState) {
        _analysisJobs.update { it + (itemId to state) }
    }

    private fun appendDocQaMessages(itemId: String, vararg newMessages: AssistantMessage) {
        _docQaConversations.update { current ->
            val updated = current[itemId].orEmpty() + newMessages.toList()
            current + (itemId to updated)
        }
    }

    private fun updateDocQaMessage(
        itemId: String,
        messageId: String,
        transform: (AssistantMessage) -> AssistantMessage,
    ) {
        _docQaConversations.update { current ->
            val list = current[itemId].orEmpty().map { msg ->
                if (msg.id == messageId) transform(msg) else msg
            }
            current + (itemId to list)
        }
    }

    private suspend fun logInference(
        taskType: AiTaskType,
        preview: String,
        result: HybridInferenceResult,
        succeeded: Boolean,
    ) {
        repository.recordAiLog(
            userId = currentUserId,
            taskType = taskType,
            promptPreview = preview,
            requestedMode = result.requestedMode,
            actualSource = result.actualSource,
            modelName = result.modelName,
            transmittedToCloud = result.transmittedToCloud,
            latencyMs = result.latencyMs,
            succeeded = succeeded,
        )
    }

    private fun parseTags(csv: String): List<String> =
        csv.split(",", "#", " ")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(10)
}
