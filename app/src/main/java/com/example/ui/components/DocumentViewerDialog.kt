package com.example.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.extraction.DocumentExtractionEngine
import com.example.data.model.DocumentSection
import com.example.data.model.VaultItem
import com.example.data.repository.DocumentImportHelper
import com.example.data.repository.FileExportHelper
import com.example.ui.theme.NexusObsidian
import com.example.ui.theme.NexusBorder
import com.example.ui.theme.NexusCyan
import com.example.ui.theme.NexusSurface
import com.example.ui.theme.NexusSurfaceElevated
import com.example.ui.theme.NexusTextMuted
import com.example.ui.theme.NexusTextPrimary
import com.example.ui.theme.NexusTextSecondary
import com.example.ui.theme.NexusViolet
import com.example.ui.theme.NexusVioletLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class ViewerMode { VISUAL, TEXT }

/**
 * Full-screen in-app document & file viewer supporting:
 * - Multi-page PDF rendering via Android `PdfRenderer` with Previous / Next page navigation
 * - High-resolution Image viewing via safe downsampled `BitmapFactory`
 * - Formatted readable text & structured sections for DOCX, PPTX, XLSX, CSV, HTML, RTF, ODT, EPUB, TXT/MD, and Notes
 * - Zero-permission **Download to Device** (SAF `ACTION_CREATE_DOCUMENT`) and **Open in External App** (`FileProvider`)
 */
@Composable
fun DocumentViewerDialog(
    item: VaultItem,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val localFile = remember(item.id, item.localFilePath) { FileExportHelper.originalLocalFile(item) }
    val mime = remember(item.mimeType, item.fileName) {
        (item.mimeType ?: DocumentExtractionEngine.resolveMimeType(null, item.fileName ?: item.title))
            .lowercase(Locale.US)
    }
    val ext = remember(item.fileName) {
        item.fileName?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
    }
    val isPdf = localFile != null && (mime == "application/pdf" || ext == "pdf")
    val isImage = localFile != null && (mime.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "avif"))

    var isLoading by remember(item.id) { mutableStateOf(true) }
    var sections by remember(item.id) { mutableStateOf<List<DocumentSection>>(emptyList()) }
    var pdfPageCount by remember(item.id) { mutableIntStateOf(0) }
    var currentPdfPage by remember(item.id) { mutableIntStateOf(0) }
    var currentBitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var viewerMode by remember(item.id) { mutableStateOf(if (isPdf || isImage) ViewerMode.VISUAL else ViewerMode.TEXT) }

    // Load sections + inspect PDF page count or decode image
    LaunchedEffect(item.id, localFile?.absolutePath) {
        isLoading = true
        val loadedSections = withContext(Dispatchers.IO) {
            FileExportHelper.loadReadableSectionsForViewer(context, item)
        }
        sections = loadedSections

        if (isPdf && localFile != null) {
            val pages = withContext(Dispatchers.IO) {
                DocumentExtractionEngine.countPdfPages(localFile).getOrDefault(0)
            }
            pdfPageCount = pages
            if (pages <= 0) {
                viewerMode = ViewerMode.TEXT
            }
        } else if (isImage && localFile != null) {
            val bmp = withContext(Dispatchers.IO) {
                DocumentExtractionEngine.decodeImageSafe(localFile, maxDimension = 2048).getOrNull()
            }
            currentBitmap = bmp
            if (bmp == null) {
                viewerMode = ViewerMode.TEXT
            }
        }
        isLoading = false
    }

    // Render requested PDF page on demand
    LaunchedEffect(item.id, isPdf, pdfPageCount, currentPdfPage, viewerMode) {
        if (isPdf && localFile != null && pdfPageCount > 0 && viewerMode == ViewerMode.VISUAL) {
            val rendered = withContext(Dispatchers.IO) {
                DocumentExtractionEngine.renderPdfPageBatch(
                    file = localFile,
                    startPageIndex = currentPdfPage,
                    endPageIndexExclusive = currentPdfPage + 1,
                    maxEdgePx = 1800,
                ).getOrNull()?.firstOrNull()?.second
            }
            val old = currentBitmap
            currentBitmap = rendered
            if (old != null && old != rendered && !old.isRecycled) {
                old.recycle()
            }
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            currentBitmap?.let { bmp -> if (!bmp.isRecycled) bmp.recycle() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("document_viewer_dialog"),
            color = NexusObsidian,
            contentColor = NexusTextPrimary,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // Header Bar
                Surface(
                    color = NexusSurface,
                    border = BorderStroke(1.dp, NexusBorder.copy(alpha = 0.8f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.testTag("viewer_close_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close viewer",
                                        tint = NexusTextPrimary
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.fileName ?: item.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = NexusTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val subtitle = buildString {
                                        append(mime)
                                        if (item.sizeBytes > 0L) {
                                            append(" • ")
                                            append(DocumentImportHelper.formatBytes(item.sizeBytes))
                                        }
                                        if (pdfPageCount > 0) {
                                            append(" • $pdfPageCount pages")
                                        }
                                    }
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NexusTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Action Strip: Download, Open External, Copy Text, and Visual/Text toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onDownload,
                                colors = ButtonDefaults.buttonColors(containerColor = NexusCyan),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("viewer_download_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download file",
                                    tint = NexusObsidian,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download", color = NexusObsidian, style = MaterialTheme.typography.labelLarge)
                            }

                            OutlinedButton(
                                onClick = onOpenExternal,
                                border = BorderStroke(1.dp, NexusVioletLight.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("viewer_open_external_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open in external app",
                                    tint = NexusVioletLight,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open External", color = NexusVioletLight, style = MaterialTheme.typography.labelLarge)
                            }

                            if (sections.isNotEmpty() && viewerMode == ViewerMode.TEXT) {
                                OutlinedButton(
                                    onClick = {
                                        val allText = DocumentExtractionEngine.formatSectionsForPreview(sections)
                                        clipboard.setText(AnnotatedString(allText))
                                        Toast.makeText(context, "Copied document text to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    border = BorderStroke(1.dp, NexusBorder),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier.testTag("viewer_copy_text_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy text",
                                        tint = NexusTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // Switch between visual PDF/Image render and extracted text when both exist
                        if ((pdfPageCount > 0 || (isImage && currentBitmap != null)) && sections.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = viewerMode == ViewerMode.VISUAL,
                                    onClick = { viewerMode = ViewerMode.VISUAL },
                                    label = { Text(if (isPdf) "PDF Preview" else "Image Preview") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.Image,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = NexusViolet.copy(alpha = 0.28f),
                                        selectedLabelColor = NexusVioletLight,
                                    )
                                )
                                FilterChip(
                                    selected = viewerMode == ViewerMode.TEXT,
                                    onClick = { viewerMode = ViewerMode.TEXT },
                                    label = { Text("Extracted Text (${sections.size})") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = NexusCyan.copy(alpha = 0.22f),
                                        selectedLabelColor = NexusCyan,
                                    )
                                )
                            }
                        }
                    }
                }

                // Body Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when {
                        isLoading -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(color = NexusCyan)
                                Text(
                                    text = "Opening file...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NexusTextSecondary
                                )
                            }
                        }

                        viewerMode == ViewerMode.VISUAL && currentBitmap != null -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (isPdf) Color.White else NexusSurfaceElevated,
                                        border = BorderStroke(1.dp, NexusBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Image(
                                            bitmap = currentBitmap!!.asImageBitmap(),
                                            contentDescription = "Preview of ${item.fileName ?: item.title}",
                                            contentScale = ContentScale.FillWidth,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(16.dp))
                                        )
                                    }
                                }

                                // PDF Page Navigator
                                if (isPdf && pdfPageCount > 1) {
                                    HorizontalDivider(color = NexusBorder)
                                    Surface(
                                        color = NexusSurface,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            OutlinedButton(
                                                onClick = { if (currentPdfPage > 0) currentPdfPage-- },
                                                enabled = currentPdfPage > 0,
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.testTag("viewer_pdf_prev")
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous page")
                                                Text("Prev")
                                            }
                                            Text(
                                                text = "Page ${currentPdfPage + 1} of $pdfPageCount",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = NexusTextPrimary
                                            )
                                            OutlinedButton(
                                                onClick = { if (currentPdfPage + 1 < pdfPageCount) currentPdfPage++ },
                                                enabled = currentPdfPage + 1 < pdfPageCount,
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.testTag("viewer_pdf_next")
                                            ) {
                                                Text("Next")
                                                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next page")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        sections.isNotEmpty() -> {
                            SelectionContainer {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(sections, key = { "${it.index}_${it.referenceLabel}" }) { sec ->
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = NexusSurfaceElevated,
                                            border = BorderStroke(1.dp, NexusBorder),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = sec.referenceLabel,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = NexusCyan
                                                )
                                                HorizontalDivider(color = NexusBorder.copy(alpha = 0.6f))
                                                Text(
                                                    text = sec.content,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = NexusTextPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            // Binary / Audio / Video / Cloud-only item with no local preview yet
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(NexusViolet.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = null,
                                        tint = NexusVioletLight,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                                Text(
                                    text = item.fileName ?: item.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = NexusTextPrimary
                                )
                                Text(
                                    text = if (localFile != null) {
                                        "Original file (${DocumentImportHelper.formatBytes(localFile.length())}) is safely preserved on this device. Open it in an installed viewer app or download a copy to your device storage."
                                    } else {
                                        "No local preview is available for this item on this device. You can still download the synced record as a Markdown file."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NexusTextSecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = onOpenExternal,
                                        colors = ButtonDefaults.buttonColors(containerColor = NexusViolet),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Open External")
                                    }
                                    OutlinedButton(
                                        onClick = onDownload,
                                        border = BorderStroke(1.dp, NexusCyan),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, tint = NexusCyan, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Download File", color = NexusCyan)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
