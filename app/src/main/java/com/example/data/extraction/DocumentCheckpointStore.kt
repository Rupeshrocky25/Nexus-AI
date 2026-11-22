package com.example.data.extraction

import android.content.Context
import com.example.data.model.ActualInferenceSource
import com.example.data.model.ChunkAnalysisCheckpoint
import com.example.data.model.ChunkProcessingStatus
import com.example.data.model.ExtractedEmbeddedMedia
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class DocumentAnalysisCheckpointRecord(
    val itemId: String,
    val contentHash: String,
    val chunks: List<ChunkAnalysisCheckpoint>,
    val embeddedVisualNotes: Map<String, String> = emptyMap(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Persistent per-document checkpoint store saved in app-private storage (`<itemId>_checkpoint.json`).
 *
 * Ensures that if a timeout, rate limit, or network interruption happens mid-analysis, every
 * already-completed chunk digest and embedded visual OCR result is preserved on disk and reused
 * cleanly when the user taps Resume or Retry Failed Sections.
 */
object DocumentCheckpointStore {

    fun computeContentHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun checkpointFile(baseDir: File, itemId: String): File =
        File(baseDir, "${itemId}_checkpoint.json")

    fun embeddedMediaFile(baseDir: File, itemId: String): File =
        File(baseDir, "${itemId}_embedded_media.json")

    fun saveCheckpoint(context: Context, record: DocumentAnalysisCheckpointRecord) {
        saveCheckpoint(DocumentExtractionEngine.originalsDirectory(context), record)
    }

    fun saveCheckpoint(baseDir: File, record: DocumentAnalysisCheckpointRecord) {
        if (record.itemId.isBlank()) return
        runCatching {
            if (!baseDir.exists()) baseDir.mkdirs()
            val root = JSONObject().apply {
                put("itemId", record.itemId)
                put("contentHash", record.contentHash)
                put("updatedAtEpochMs", record.updatedAtEpochMs)

                val chunkArr = JSONArray()
                record.chunks.forEach { cp ->
                    chunkArr.put(
                        JSONObject().apply {
                            put("chunkIndex", cp.chunkIndex)
                            put("totalChunks", cp.totalChunks)
                            put("referenceRange", cp.referenceRange)
                            put("status", cp.status.name)
                            put("digestText", cp.digestText)
                            if (cp.errorMessage != null) put("errorMessage", cp.errorMessage)
                            put("attempts", cp.attempts)
                            put("actualSource", cp.actualSource.name)
                            put("modelName", cp.modelName)
                            put("latencyMs", cp.latencyMs)
                        }
                    )
                }
                put("chunks", chunkArr)

                val visualsObj = JSONObject()
                record.embeddedVisualNotes.forEach { (k, v) -> visualsObj.put(k, v) }
                put("embeddedVisualNotes", visualsObj)
            }
            val target = checkpointFile(baseDir, record.itemId)
            val tmp = File(baseDir, "${record.itemId}_checkpoint.json.tmp")
            tmp.writeText(root.toString(), StandardCharsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString(), StandardCharsets.UTF_8)
                tmp.delete()
            }
        }
    }

    fun loadCheckpoint(context: Context, itemId: String): DocumentAnalysisCheckpointRecord? =
        loadCheckpoint(DocumentExtractionEngine.originalsDirectory(context), itemId)

    fun loadCheckpoint(baseDir: File, itemId: String): DocumentAnalysisCheckpointRecord? {
        if (itemId.isBlank()) return null
        return runCatching {
            val file = checkpointFile(baseDir, itemId)
            if (!file.exists()) return null
            val root = JSONObject(file.readText(StandardCharsets.UTF_8))
            val chunkArr = root.optJSONArray("chunks") ?: JSONArray()
            val chunks = buildList {
                for (i in 0 until chunkArr.length()) {
                    val obj = chunkArr.optJSONObject(i) ?: continue
                    add(
                        ChunkAnalysisCheckpoint(
                            chunkIndex = obj.optInt("chunkIndex", i + 1),
                            totalChunks = obj.optInt("totalChunks", chunkArr.length()),
                            referenceRange = obj.optString("referenceRange", "Chunk ${i + 1}"),
                            status = ChunkProcessingStatus.fromRaw(obj.optString("status")),
                            digestText = obj.optString("digestText", ""),
                            errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() },
                            attempts = obj.optInt("attempts", 0),
                            actualSource = ActualInferenceSource.fromRaw(obj.optString("actualSource")),
                            modelName = obj.optString("modelName", ""),
                            latencyMs = obj.optLong("latencyMs", 0L),
                        )
                    )
                }
            }
            val visualsMap = mutableMapOf<String, String>()
            val visualsObj = root.optJSONObject("embeddedVisualNotes")
            if (visualsObj != null) {
                val keys = visualsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = visualsObj.optString(k, "")
                    if (v.isNotBlank()) visualsMap[k] = v
                }
            }
            DocumentAnalysisCheckpointRecord(
                itemId = root.optString("itemId", itemId),
                contentHash = root.optString("contentHash", ""),
                chunks = chunks,
                embeddedVisualNotes = visualsMap,
                updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L),
            )
        }.getOrNull()
    }

    fun clearCheckpoint(context: Context, itemId: String) {
        clearCheckpoint(DocumentExtractionEngine.originalsDirectory(context), itemId)
    }

    fun clearCheckpoint(baseDir: File, itemId: String) {
        if (itemId.isBlank()) return
        runCatching { checkpointFile(baseDir, itemId).delete() }
    }

    fun saveEmbeddedMediaManifest(
        context: Context,
        itemId: String,
        media: List<ExtractedEmbeddedMedia>,
    ) {
        saveEmbeddedMediaManifest(DocumentExtractionEngine.originalsDirectory(context), itemId, media)
    }

    fun saveEmbeddedMediaManifest(
        baseDir: File,
        itemId: String,
        media: List<ExtractedEmbeddedMedia>,
    ) {
        if (itemId.isBlank()) return
        runCatching {
            if (!baseDir.exists()) baseDir.mkdirs()
            val arr = JSONArray()
            media.forEach { m ->
                arr.put(
                    JSONObject().apply {
                        put("id", m.id)
                        put("fileName", m.fileName)
                        put("mimeType", m.mimeType)
                        put("localPath", m.localPath)
                        put("referenceLabel", m.referenceLabel)
                    }
                )
            }
            embeddedMediaFile(baseDir, itemId).writeText(arr.toString(), StandardCharsets.UTF_8)
        }
    }

    fun loadEmbeddedMediaManifest(context: Context, itemId: String): List<ExtractedEmbeddedMedia> =
        loadEmbeddedMediaManifest(DocumentExtractionEngine.originalsDirectory(context), itemId)

    fun loadEmbeddedMediaManifest(baseDir: File, itemId: String): List<ExtractedEmbeddedMedia> {
        if (itemId.isBlank()) return emptyList()
        return runCatching {
            val file = embeddedMediaFile(baseDir, itemId)
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText(StandardCharsets.UTF_8))
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val path = obj.optString("localPath", "")
                    if (path.isNotBlank() && File(path).exists()) {
                        add(
                            ExtractedEmbeddedMedia(
                                id = obj.optString("id", "media_${i + 1}"),
                                fileName = obj.optString("fileName", "image_${i + 1}"),
                                mimeType = obj.optString("mimeType", "image/png"),
                                localPath = path,
                                referenceLabel = obj.optString("referenceLabel", "Embedded Visual ${i + 1}"),
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
}
