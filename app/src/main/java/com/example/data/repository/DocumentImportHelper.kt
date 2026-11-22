package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.extraction.DocumentExtractionEngine
import com.example.data.extraction.ExtractedDocumentResult
import java.util.Locale

/**
 * Zero-permission SAF document import facade delegating to [DocumentExtractionEngine].
 * Preserves original files in app-private storage and performs real content extraction
 * (never generating summaries or content from filenames).
 */
object DocumentImportHelper {

    fun importFromUri(context: Context, uri: Uri): Result<ExtractedDocumentResult> =
        DocumentExtractionEngine.importAndExtract(context, uri)

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
