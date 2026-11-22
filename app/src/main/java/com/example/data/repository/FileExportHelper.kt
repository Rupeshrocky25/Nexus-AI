package com.example.data.repository

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.extraction.DocumentExtractionEngine
import com.example.data.model.DocumentSection
import com.example.data.model.VaultItem
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Zero-permission file viewing and export/download helper for NEXUS AI.
 *
 * - **Download / Save to Device**: Uses Android Storage Access Framework (`ACTION_CREATE_DOCUMENT`)
 *   so users can save the exact original file (PDF, DOCX, PPTX, XLSX, image, audio, video, TXT, etc.)
 *   or a formatted Markdown note to Downloads / Drive with ZERO storage permissions.
 * - **Open in External App**: Uses `androidx.core.content.FileProvider` with
 *   `FLAG_GRANT_READ_URI_PERMISSION` to open preserved files in installed PDF/Office/Gallery/Media apps.
 */
object FileExportHelper {

    private const val EXPORTS_CACHE_DIR = "exports"

    fun originalLocalFile(item: VaultItem): File? {
        val path = item.localFilePath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        return file.takeIf { it.exists() && it.canRead() && it.length() > 0L }
    }

    fun hasOriginalLocalFile(item: VaultItem): Boolean = originalLocalFile(item) != null

    fun suggestedDownloadFileName(item: VaultItem): String {
        val originalFile = originalLocalFile(item)
        if (originalFile != null && !item.fileName.isNullOrBlank()) {
            return sanitizeFileName(item.fileName)
        }
        val baseTitle = sanitizeFileName(item.title.ifBlank { "NEXUS_Document" })
        return if (baseTitle.contains('.')) baseTitle else "$baseTitle.md"
    }

    fun suggestedDownloadMimeType(item: VaultItem): String {
        val originalFile = originalLocalFile(item)
        if (originalFile != null) {
            val mime = item.mimeType?.takeIf { it.isNotBlank() && it != "*/*" }
                ?: DocumentExtractionEngine.resolveMimeType(null, item.fileName ?: originalFile.name)
            return mime
        }
        return "text/markdown"
    }

    fun buildCreateDocumentIntent(item: VaultItem): Intent {
        val mime = suggestedDownloadMimeType(item)
        val fileName = suggestedDownloadFileName(item)
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
    }

    /**
     * Copies the original preserved file verbatim (or writes full formatted Markdown content for
     * notes/links/cloud-synced items without a local binary) to the SAF destination Uri.
     */
    fun writeItemToDestinationUri(
        context: Context,
        item: VaultItem,
        destinationUri: Uri,
    ): Result<Long> = runCatching {
        context.contentResolver.openOutputStream(destinationUri, "wt")?.use { out ->
            writeItemToStream(context, item, out)
        } ?: context.contentResolver.openOutputStream(destinationUri)?.use { out ->
            writeItemToStream(context, item, out)
        } ?: throw IllegalStateException("Could not open destination file for writing.")
    }

    internal fun writeItemToStream(
        context: Context,
        item: VaultItem,
        out: OutputStream,
    ): Long {
        val original = originalLocalFile(item)
        if (original != null) {
            var total = 0L
            val buf = ByteArray(16_384)
            FileInputStream(original).use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    if (read > 0) {
                        out.write(buf, 0, read)
                        total += read
                    }
                }
            }
            out.flush()
            return total
        }

        val fallbackText = buildFallbackExportText(context, item)
        val bytes = fallbackText.toByteArray(StandardCharsets.UTF_8)
        out.write(bytes)
        out.flush()
        return bytes.size.toLong()
    }

    /**
     * Opens the original preserved file (or an exported temporary Markdown/text copy for notes)
     * in an external viewer app installed on Android via FileProvider.
     */
    fun openInExternalApp(context: Context, item: VaultItem): Result<Unit> = runCatching {
        val authority = "${context.packageName}.fileprovider"
        val original = originalLocalFile(item)
        val targetFile: File
        val targetMime: String

        if (original != null) {
            targetFile = original
            targetMime = suggestedDownloadMimeType(item)
        } else {
            val exportDir = File(context.cacheDir, EXPORTS_CACHE_DIR).apply { if (!exists()) mkdirs() }
            val safeName = sanitizeFileName(item.title.ifBlank { "NEXUS_Note" }).let {
                if (it.endsWith(".txt") || it.endsWith(".md")) it else "$it.txt"
            }
            targetFile = File(exportDir, safeName)
            targetFile.writeText(buildFallbackExportText(context, item), StandardCharsets.UTF_8)
            targetMime = "text/plain"
        }

        val contentUri = FileProvider.getUriForFile(context, authority, targetFile)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, targetMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            val chooser = Intent.createChooser(viewIntent, "Open ${item.fileName ?: item.title}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException(
                "No external app is installed on this device to open $targetMime files. Use Download to save it or view inside NEXUS AI."
            )
        }
    }

    /**
     * Loads the best available readable content for the in-app document viewer:
     * 1. Saved extracted sections (`<itemId>_sections.json`) if present.
     * 2. Raw decoded text directly from the local original file if it's a text/code/CSV/HTML/RTF file.
     * 3. `item.content` from Firestore as fallback.
     */
    fun loadReadableSectionsForViewer(
        context: Context,
        item: VaultItem,
    ): List<DocumentSection> {
        val saved = DocumentExtractionEngine.loadExtractedSections(context, item.id)
        if (saved.isNotEmpty()) return saved

        val original = originalLocalFile(item)
        if (original != null) {
            val reExtracted = runCatching {
                DocumentExtractionEngine.extractFromPreservedFile(
                    file = original,
                    title = item.title,
                    fileName = item.fileName ?: original.name,
                    mimeType = item.mimeType ?: "application/octet-stream",
                    sizeBytes = original.length(),
                )
            }.getOrNull()
            if (reExtracted != null && reExtracted.sections.isNotEmpty()) {
                return reExtracted.sections
            }
        }

        if (item.content.isNotBlank()) {
            return listOf(
                DocumentSection(
                    index = 1,
                    referenceLabel = item.fileName ?: item.title.ifBlank { "Document Content" },
                    content = item.content,
                )
            )
        }
        return emptyList()
    }

    private fun buildFallbackExportText(context: Context, item: VaultItem): String {
        val sections = DocumentExtractionEngine.loadExtractedSections(context, item.id)
        return buildString {
            appendLine("# ${item.title}")
            item.fileName?.takeIf { it.isNotBlank() }?.let { appendLine("File: $it") }
            item.sourceUrl?.takeIf { it.isNotBlank() }?.let { appendLine("Source URL: $it") }
            appendLine("Category: ${item.category}")
            if (item.tags.isNotEmpty()) appendLine("Tags: ${item.tags.joinToString(", ")}")
            appendLine()
            if (sections.isNotEmpty()) {
                sections.forEach { sec ->
                    appendLine("## ${sec.referenceLabel}")
                    appendLine(sec.content)
                    appendLine()
                }
            } else if (item.content.isNotBlank()) {
                appendLine(item.content)
            } else {
                appendLine("(No text content available)")
            }
        }.trim()
    }

    private fun sanitizeFileName(raw: String): String =
        raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "NEXUS_Document" }.take(140)
}
