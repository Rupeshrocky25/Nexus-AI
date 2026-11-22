package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.example.data.model.ActualInferenceSource
import com.example.data.model.AiAuditLog
import com.example.data.model.AiTaskType
import com.example.data.model.DocumentAnalysis
import com.example.data.model.ExtractionMethod
import com.example.data.model.ExtractionStatus
import com.example.data.model.HybridInferenceMode
import com.example.data.model.UserProfile
import com.example.data.model.VaultItem
import com.example.data.model.VaultItemType
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * User-scoped Firestore repository for NEXUS AI Knowledge Vault, structured document analyses,
 * user preferences, and AI privacy audit logs.
 * Always connects to the provisioned named database via R.string.firestore_database_id with persistent offline cache.
 */
class VaultRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        createConfiguredFirestore(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    companion object {
        fun createConfiguredFirestore(databaseId: String): FirebaseFirestore {
            val firestore = FirebaseFirestore.getInstance(databaseId)
            try {
                val settings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        PersistentCacheSettings.newBuilder()
                            .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                            .build()
                    )
                    .build()
                firestore.firestoreSettings = settings
            } catch (_: IllegalStateException) {
                // Settings already initialized for this instance; safe to continue.
            }
            return firestore
        }
    }

    private fun requireUserId(expectedUserId: String? = null): String {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        if (expectedUserId != null && uid != expectedUserId) {
            throw IllegalStateException("Signed-in user changed; refusing cross-user Firestore operation.")
        }
        return uid
    }

    /**
     * Firestore security rules evaluate `string.size()` in UTF-8 bytes rather than Kotlin UTF-16 Chars.
     * Clamping by UTF-8 byte length prevents PERMISSION_DENIED on documents containing Unicode, CJK, or emoji.
     */
    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        if (maxBytes <= 0 || isEmpty()) return ""
        val bytes = toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return this
        var end = maxBytes
        // Avoid splitting a multi-byte UTF-8 sequence (continuation bytes are 10xxxxxx)
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid"
        emitAll(
            db.collection("users").document(uid)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            UserProfile::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else {
                        null
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    fun observeVaultItems(userId: String): Flow<List<VaultItem>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/vault_items"
        emitAll(
            db.collection("users").document(uid).collection("vault_items")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        VaultItem::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedWith(
                        compareByDescending<VaultItem> { it.pinned }
                            .thenByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeDocumentAnalyses(userId: String): Flow<Map<String, DocumentAnalysis>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/document_analyses"
        emitAll(
            db.collection("users").document(uid).collection("document_analyses")
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        val data = doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: return@mapNotNull null
                        doc.id to DocumentAnalysis.fromFirestoreMap(doc.id, data)
                    }.toMap()
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeAiLogs(userId: String): Flow<List<AiAuditLog>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/ai_logs"
        emitAll(
            db.collection("users").document(uid).collection("ai_logs")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        AiAuditLog::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun ensureUserProfile(
        userId: String,
        displayName: String,
        email: String?,
    ): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid)
            val existing = docRef.get().await()
            val safeName = displayName.trim().ifBlank { "Nexus User" }.take(100)
            val safeEmail = email?.trim()?.takeIf { it.isNotBlank() }?.take(254)
            if (!existing.exists()) {
                val payload = buildMap<String, Any> {
                    put("userId", uid)
                    put("displayName", safeName)
                    if (safeEmail != null) put("email", safeEmail)
                    put("defaultInferenceMode", HybridInferenceMode.PREFER_ON_DEVICE.name)
                    put("createdAt", FieldValue.serverTimestamp())
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateDefaultInferenceMode(
        userId: String,
        mode: HybridInferenceMode,
    ): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId(userId)
            db.collection("users").document(uid).update(
                mapOf(
                    "defaultInferenceMode" to mode.name,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun createVaultItem(
        userId: String,
        itemType: VaultItemType,
        title: String,
        content: String,
        category: String,
        tags: List<String>,
        sourceUrl: String? = null,
        fileName: String? = null,
        mimeType: String? = null,
        localFilePath: String? = null,
        extractionStatus: ExtractionStatus? = null,
        extractionMethod: ExtractionMethod? = null,
        pageCount: Long? = null,
        sizeBytes: Long? = null,
    ): Result<String> {
        var docPath = "users/$userId/vault_items"
        return try {
            val uid = requireUserId(userId)
            val cleanTitle = title.trim().takeUtf8Bytes(200)
            require(cleanTitle.isNotEmpty()) { "Title cannot be empty." }
            val cleanContent = content.takeUtf8Bytes(95_000)
            val cleanCategory = category.trim().ifBlank { "Notes" }.takeUtf8Bytes(40)
            val cleanTags = tags.map { it.trim().takeUtf8Bytes(40) }.filter { it.isNotEmpty() }.distinct().take(10)
            val computedSize = (sizeBytes ?: (cleanTitle.toByteArray().size + cleanContent.toByteArray().size).toLong())
                .coerceIn(0L, 104_857_600L)

            val docRef = db.collection("users").document(uid).collection("vault_items").document()
            docPath = docRef.path

            val payload = buildMap<String, Any> {
                put("userId", uid)
                put("itemType", itemType.name)
                put("title", cleanTitle)
                put("content", cleanContent)
                put("category", cleanCategory)
                put("tags", cleanTags)
                sourceUrl?.trim()?.takeIf { it.isNotEmpty() }?.takeUtf8Bytes(2048)?.let { put("sourceUrl", it) }
                fileName?.trim()?.takeIf { it.isNotEmpty() }?.takeUtf8Bytes(255)?.let { put("fileName", it) }
                mimeType?.trim()?.takeIf { it.isNotEmpty() }?.takeUtf8Bytes(120)?.let { put("mimeType", it) }
                localFilePath?.trim()?.takeIf { it.isNotEmpty() }?.takeUtf8Bytes(512)?.let { put("localFilePath", it) }
                extractionStatus?.let { put("extractionStatus", it.name) }
                extractionMethod?.let { put("extractionMethod", it.name.takeUtf8Bytes(80)) }
                pageCount?.let { put("pageCount", it.coerceIn(0L, 50_000L)) }
                put("sizeBytes", computedSize)
                put("pinned", false)
                put("createdAt", FieldValue.serverTimestamp())
                put("updatedAt", FieldValue.serverTimestamp())
            }
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateVaultItemExtraction(
        userId: String,
        itemId: String,
        content: String,
        extractionStatus: ExtractionStatus,
        extractionMethod: ExtractionMethod,
        pageCount: Long? = null,
        aiSummary: String? = null,
    ): Result<Unit> {
        val docPath = "users/$userId/vault_items/$itemId"
        return try {
            val uid = requireUserId(userId)
            require(itemId.isNotBlank()) { "Item ID must not be blank." }
            val updates = buildMap<String, Any> {
                put("content", content.takeUtf8Bytes(95_000))
                put("extractionStatus", extractionStatus.name)
                put("extractionMethod", extractionMethod.name.takeUtf8Bytes(80))
                pageCount?.let { put("pageCount", it.coerceIn(0L, 50_000L)) }
                aiSummary?.trim()?.takeIf { it.isNotEmpty() }?.takeUtf8Bytes(3800)?.let { put("aiSummary", it) }
                put("updatedAt", FieldValue.serverTimestamp())
            }
            db.collection("users").document(uid).collection("vault_items").document(itemId)
                .update(updates)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Persists a structured [DocumentAnalysis] using native Firestore Maps & Lists.
     * Creates if absent, or updates mutable analysis fields while preserving immutable `createdAt` on reanalysis.
     */
    suspend fun saveDocumentAnalysis(
        userId: String,
        analysis: DocumentAnalysis,
    ): Result<Unit> {
        val docPath = "users/$userId/document_analyses/${analysis.itemId}"
        return try {
            val uid = requireUserId(userId)
            require(analysis.itemId.isNotBlank()) { "DocumentAnalysis.itemId must not be blank." }

            val docRef = db.collection("users").document(uid)
                .collection("document_analyses").document(analysis.itemId)

            val mutableFields = buildMap<String, Any> {
                put("summary", analysis.summary.trim().ifBlank { "Analysis completed." }.takeUtf8Bytes(7800))
                if (analysis.detailedExplanation.isNotBlank()) {
                    put("detailedExplanation", analysis.detailedExplanation.trim().takeUtf8Bytes(15_500))
                }
                if (analysis.conclusions.isNotEmpty()) {
                    put(
                        "conclusions",
                        analysis.conclusions.map { it.trim().takeUtf8Bytes(480) }.filter { it.isNotEmpty() }.take(25)
                    )
                }
                put("extractionStatus", analysis.resolvedExtractionStatus().name)
                put("extractionMethod", analysis.extractionMethod.ifBlank { ExtractionMethod.DIRECT_TEXT.name }.takeUtf8Bytes(80))
                put("extractionDisclosure", analysis.extractionDisclosure.takeUtf8Bytes(950))
                put("chunksProcessed", analysis.chunksProcessed.coerceIn(0L, 1000L))
                put("totalChunks", analysis.totalChunks.coerceIn(0L, 1000L))
                put("mainTopics", analysis.mainTopics.map { it.trim().takeUtf8Bytes(120) }.filter { it.isNotEmpty() }.take(30))
                put(
                    "sourceFacts",
                    analysis.sourceFacts.take(50).map {
                        mapOf("fact" to it.fact.takeUtf8Bytes(580), "sourceRef" to it.sourceRef.takeUtf8Bytes(120))
                    }
                )
                put("aiInterpretations", analysis.aiInterpretations.map { it.trim().takeUtf8Bytes(580) }.filter { it.isNotEmpty() }.take(30))
                put(
                    "definitions",
                    analysis.definitions.take(30).map {
                        mapOf(
                            "term" to it.term.takeUtf8Bytes(120),
                            "explanation" to it.explanation.takeUtf8Bytes(580),
                            "sourceRef" to it.sourceRef.takeUtf8Bytes(120),
                        )
                    }
                )
                put(
                    "entities",
                    analysis.entities.take(50).map {
                        mapOf(
                            "name" to it.name.takeUtf8Bytes(160),
                            "type" to it.type.takeUtf8Bytes(60),
                            "context" to it.context.takeUtf8Bytes(380),
                            "sourceRef" to it.sourceRef.takeUtf8Bytes(120),
                        )
                    }
                )
                put(
                    "tableInsights",
                    analysis.tableInsights.take(30).map {
                        mapOf(
                            "title" to it.title.takeUtf8Bytes(160),
                            "finding" to it.finding.takeUtf8Bytes(580),
                            "sourceRef" to it.sourceRef.takeUtf8Bytes(120),
                        )
                    }
                )
                put(
                    "actionItems",
                    analysis.actionItems.take(30).map {
                        mapOf(
                            "action" to it.action.takeUtf8Bytes(480),
                            "priority" to it.priority.takeUtf8Bytes(20),
                            "sourceRef" to it.sourceRef.takeUtf8Bytes(120),
                        )
                    }
                )
                put(
                    "groundedQa",
                    analysis.groundedQa.take(25).map {
                        mapOf(
                            "question" to it.question.takeUtf8Bytes(380),
                            "answer" to it.answer.takeUtf8Bytes(1100),
                            "sourceRef" to it.sourceRef.takeUtf8Bytes(120),
                        )
                    }
                )
                put("extractedPreview", analysis.extractedPreview.takeUtf8Bytes(60_000))
                put("requestedMode", analysis.resolvedRequestedMode().name)
                put("actualSource", analysis.resolvedActualSource().name)
                put("modelName", analysis.modelName.ifBlank { "gemini-3.8-flash" }.takeUtf8Bytes(80))
                put("latencyMs", analysis.latencyMs.coerceIn(0L, 600_000L))
                put("updatedAt", FieldValue.serverTimestamp())
            }

            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(mutableFields).await()
            } else {
                val createPayload = mutableFields.toMutableMap().apply {
                    put("userId", uid)
                    put("itemId", analysis.itemId)
                    put("createdAt", FieldValue.serverTimestamp())
                }
                docRef.set(createPayload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateVaultItem(
        userId: String,
        itemId: String,
        title: String,
        content: String,
        category: String,
        tags: List<String>,
        sourceUrl: String? = null,
    ): Result<Unit> {
        val docPath = "users/$userId/vault_items/$itemId"
        return try {
            val uid = requireUserId(userId)
            require(itemId.isNotBlank()) { "Item ID must not be blank." }
            val cleanTitle = title.trim().take(200)
            require(cleanTitle.isNotEmpty()) { "Title cannot be empty." }
            val cleanContent = content.take(100_000)
            val cleanCategory = category.trim().ifBlank { "Notes" }.take(40)
            val cleanTags = tags.map { it.trim().take(40) }.filter { it.isNotEmpty() }.distinct().take(10)
            val computedSize = (cleanTitle.toByteArray().size + cleanContent.toByteArray().size).toLong()
                .coerceIn(0L, 104_857_600L)

            val updates = buildMap<String, Any> {
                put("title", cleanTitle)
                put("content", cleanContent)
                put("category", cleanCategory)
                put("tags", cleanTags)
                sourceUrl?.trim()?.takeIf { it.isNotEmpty() }?.take(2048)?.let { put("sourceUrl", it) }
                put("sizeBytes", computedSize)
                put("updatedAt", FieldValue.serverTimestamp())
            }
            db.collection("users").document(uid).collection("vault_items").document(itemId)
                .update(updates)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun attachAiMetadata(
        userId: String,
        itemId: String,
        aiSummary: String? = null,
        category: String? = null,
        tags: List<String>? = null,
    ): Result<Unit> {
        val docPath = "users/$userId/vault_items/$itemId"
        return try {
            val uid = requireUserId(userId)
            require(itemId.isNotBlank()) { "Item ID must not be blank." }
            val updates = buildMap<String, Any> {
                aiSummary?.trim()?.takeIf { it.isNotEmpty() }?.takeUtf8Bytes(3800)?.let { put("aiSummary", it) }
                category?.trim()?.takeIf { it.isNotEmpty() }?.takeUtf8Bytes(40)?.let { put("category", it) }
                tags?.let { list ->
                    put("tags", list.map { it.trim().takeUtf8Bytes(40) }.filter { it.isNotEmpty() }.distinct().take(10))
                }
                put("updatedAt", FieldValue.serverTimestamp())
            }
            db.collection("users").document(uid).collection("vault_items").document(itemId)
                .update(updates)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun togglePin(userId: String, itemId: String, pinned: Boolean): Result<Unit> {
        val docPath = "users/$userId/vault_items/$itemId"
        return try {
            val uid = requireUserId(userId)
            require(itemId.isNotBlank()) { "Item ID must not be blank." }
            db.collection("users").document(uid).collection("vault_items").document(itemId)
                .update(
                    mapOf(
                        "pinned" to pinned,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteVaultItem(userId: String, itemId: String): Result<Unit> {
        val docPath = "users/$userId/vault_items/$itemId"
        return try {
            val uid = requireUserId(userId)
            require(itemId.isNotBlank()) { "Item ID must not be blank." }
            db.collection("users").document(uid).collection("vault_items").document(itemId)
                .delete()
                .await()
            // Clean up any associated document analysis (ignore if it doesn't exist)
            runCatching {
                db.collection("users").document(uid).collection("document_analyses").document(itemId)
                    .delete()
                    .await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun recordAiLog(
        userId: String,
        taskType: AiTaskType,
        promptPreview: String,
        requestedMode: HybridInferenceMode,
        actualSource: ActualInferenceSource,
        modelName: String,
        transmittedToCloud: Boolean,
        latencyMs: Long,
        succeeded: Boolean,
    ): Result<Unit> {
        var docPath = "users/$userId/ai_logs"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid).collection("ai_logs").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "taskType" to taskType.name,
                "promptPreview" to promptPreview.trim().ifBlank { taskType.displayLabel }.takeUtf8Bytes(280),
                "requestedMode" to requestedMode.name,
                "actualSource" to actualSource.name,
                "modelName" to modelName.trim().ifBlank { "gemini-3.8-flash" }.takeUtf8Bytes(80),
                "transmittedToCloud" to transmittedToCloud,
                "latencyMs" to latencyMs.coerceIn(0L, 600_000L),
                "succeeded" to succeeded,
                "createdAt" to FieldValue.serverTimestamp(),
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun clearAiLogs(userId: String): Result<Int> {
        val collectionPath = "users/$userId/ai_logs"
        return try {
            val uid = requireUserId(userId)
            val snapshot = db.collection("users").document(uid).collection("ai_logs").get().await()
            val docs = snapshot.documents
            docs.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            Result.success(docs.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, collectionPath)
            Result.failure(e)
        }
    }

    suspend fun deleteAllVaultItems(userId: String): Result<Int> {
        val collectionPath = "users/$userId/vault_items"
        return try {
            val uid = requireUserId(userId)
            val snapshot = db.collection("users").document(uid).collection("vault_items").get().await()
            val docs = snapshot.documents
            docs.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            // Also purge associated document analyses
            runCatching {
                val analyses = db.collection("users").document(uid).collection("document_analyses").get().await()
                analyses.documents.chunked(400).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
            }
            Result.success(docs.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, collectionPath)
            Result.failure(e)
        }
    }
}
