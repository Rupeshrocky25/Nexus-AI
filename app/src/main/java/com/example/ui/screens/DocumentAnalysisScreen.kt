package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.extraction.DocumentChunker
import com.example.data.extraction.DocumentExtractionEngine
import com.example.data.model.AssistantMessage
import com.example.data.model.ChunkAnalysisCheckpoint
import com.example.data.model.ChunkProcessingStatus
import com.example.data.model.DocumentAnalysis
import com.example.data.model.DocumentAnalysisJobState
import com.example.data.model.DocumentSection
import com.example.data.model.ExtractionStatus
import com.example.data.model.HybridInferenceMode
import com.example.data.model.VaultItem
import com.example.data.repository.DocumentImportHelper
import com.example.ui.components.InferenceSourceBadge
import com.example.ui.components.NexusCard
import com.example.ui.theme.NexusBorder
import com.example.ui.theme.NexusBorderBright
import com.example.ui.theme.NexusCyan
import com.example.ui.theme.NexusError
import com.example.ui.theme.NexusOnlineGreen
import com.example.ui.theme.NexusSurfaceElevated
import com.example.ui.theme.NexusTextMuted
import com.example.ui.theme.NexusTextPrimary
import com.example.ui.theme.NexusTextSecondary
import com.example.ui.theme.NexusViolet
import com.example.ui.theme.NexusVioletLight
import com.example.ui.theme.NexusWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AnalysisTab(val label: String, val tag: String) {
    OVERVIEW("Overview", "analysis_tab_overview"),
    DETAILED("Detailed", "analysis_tab_detailed"),
    REFERENCES("References", "analysis_tab_references"),
    EXTRACTED("Extracted Content", "analysis_tab_extracted"),
    ASK("Ask Document", "analysis_tab_ask"),
}

@Composable
fun DocumentAnalysisScreen(
    item: VaultItem,
    analysis: DocumentAnalysis?,
    jobState: DocumentAnalysisJobState,
    qaMessages: List<AssistantMessage>,
    isQaBusy: Boolean,
    activeMode: HybridInferenceMode,
    isOnline: Boolean,
    isCompact: Boolean,
    onBack: () -> Unit,
    onReanalyze: () -> Unit,
    onResume: () -> Unit,
    onRetryFailed: () -> Unit,
    onCancel: () -> Unit,
    onViewFile: () -> Unit = {},
    onDownloadFile: () -> Unit = {},
    onExport: (DocumentAnalysis) -> Unit,
    onAskDocument: (question: String, mode: HybridInferenceMode) -> Unit,
    onClearQa: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    var selectedTab by remember(item.id) { mutableStateOf(AnalysisTab.OVERVIEW) }
    var docQaMode by remember(item.id, activeMode) { mutableStateOf(activeMode) }
    var fullSections by remember(item.id) { mutableStateOf<List<DocumentSection>>(emptyList()) }

    LaunchedEffect(item.id, item.content, analysis?.updatedAt, jobState.running) {
        val loaded = withContext(Dispatchers.IO) {
            val local = DocumentExtractionEngine.loadExtractedSections(context, item.id)
            when {
                local.isNotEmpty() -> local
                !analysis?.extractedPreview.isNullOrBlank() ->
                    DocumentChunker.parseSectionsFromFormattedText(analysis!!.extractedPreview)
                item.content.isNotBlank() ->
                    DocumentChunker.parseSectionsFromFormattedText(item.content)
                else -> emptyList()
            }
        }
        fullSections = loaded
    }

    val horizontalPadding = if (isCompact) 16.dp else 28.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("document_analysis_screen")
    ) {
        // 1. Top Header with Back, Title, Reanalyse & Export actions
        Surface(
            color = NexusSurfaceElevated.copy(alpha = 0.72f),
            border = BorderStroke(1.dp, NexusBorder.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 14.dp),
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
                            onClick = onBack,
                            modifier = Modifier.testTag("analysis_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to vault",
                                tint = NexusTextPrimary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = NexusTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val metaLine = buildString {
                                append(item.fileName ?: item.resolvedType().label)
                                append(" • ")
                                append(DocumentImportHelper.formatBytes(item.sizeBytes))
                                val sectionsCount = fullSections.size.takeIf { it > 0 } ?: (item.pageCount ?: 0L).toInt()
                                if (sectionsCount > 0) append(" • $sectionsCount page/sec")
                            }
                            Text(
                                text = metaLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = NexusTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Action Bar: View File, Download File, Reanalyse, Export Report (horizontally scrollable on compact screens)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onViewFile,
                        border = BorderStroke(1.dp, NexusCyan.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("view_document_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "View file",
                            tint = NexusCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "View File",
                            color = NexusCyan,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    OutlinedButton(
                        onClick = onDownloadFile,
                        border = BorderStroke(1.dp, NexusOnlineGreen.copy(alpha = 0.55f)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("download_document_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download file",
                            tint = NexusOnlineGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Download",
                            color = NexusOnlineGreen,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    OutlinedButton(
                        onClick = onReanalyze,
                        enabled = !jobState.running,
                        border = BorderStroke(1.dp, NexusVioletLight.copy(alpha = 0.55f)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("reanalyze_document_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reanalyse document",
                            tint = NexusVioletLight,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (analysis == null) "Analyse" else "Reanalyse",
                            color = NexusVioletLight,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    if (analysis != null) {
                        Button(
                            onClick = { onExport(analysis) },
                            colors = ButtonDefaults.buttonColors(containerColor = NexusCyan),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("export_analysis_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Export analysis",
                                tint = NexusSurfaceElevated,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Export Report",
                                color = NexusSurfaceElevated,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                // Provenance Row: Extraction Method + Coverage + Truthful SDK Inference Source Badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val status = analysis?.resolvedExtractionStatus() ?: item.resolvedExtractionStatus()
                    val statusColor = when (status) {
                        ExtractionStatus.COMPLETE -> NexusOnlineGreen
                        ExtractionStatus.PARTIAL -> NexusWarning
                        ExtractionStatus.PENDING_OCR -> NexusCyan
                        ExtractionStatus.FAILED -> NexusError
                        ExtractionStatus.NOT_APPLICABLE -> NexusVioletLight
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = statusColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.42f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = when (status) {
                                    ExtractionStatus.FAILED -> Icons.Default.ErrorOutline
                                    ExtractionStatus.PARTIAL -> Icons.Default.WarningAmber
                                    else -> Icons.Default.Verified
                                },
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${status.label} • ${(analysis?.resolvedExtractionMethod() ?: item.resolvedExtractionMethod()).label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor
                            )
                        }
                    }

                    if (analysis != null) {
                        InferenceSourceBadge(
                            source = analysis.resolvedActualSource(),
                            modelName = analysis.modelName,
                            latencyMs = analysis.latencyMs
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = NexusSurfaceElevated,
                            border = BorderStroke(1.dp, NexusBorder)
                        ) {
                            Text(
                                text = "Chunks ${analysis.chunksProcessed}/${analysis.totalChunks}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NexusTextSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // 5-Tab Navigation Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnalysisTab.entries.forEach { tab ->
                        val active = selectedTab == tab
                        FilterChip(
                            selected = active,
                            onClick = { selectedTab = tab },
                            modifier = Modifier.testTag(tab.tag),
                            label = { Text(text = tab.label, style = MaterialTheme.typography.labelLarge) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NexusViolet.copy(alpha = 0.28f),
                                selectedLabelColor = NexusTextPrimary,
                                labelColor = NexusTextSecondary,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = active,
                                borderColor = NexusBorder,
                                selectedBorderColor = NexusVioletLight
                            )
                        )
                    }
                }
            }
        }

        // 2. Live Streaming & Resilient Pipeline Progress Panel
        if (jobState.running) {
            LiveStreamingProgressCard(
                jobState = jobState,
                horizontalPadding = horizontalPadding,
                onCancel = onCancel,
            )
        }

        // 3. Recovery / Checkpoint / Partial / Error Controls (Resume + Retry Failed Sections)
        if (!jobState.running && (jobState.errorMessage != null || jobState.canResume || jobState.canRetryFailed)) {
            ResilientRecoveryCard(
                jobState = jobState,
                horizontalPadding = horizontalPadding,
                onResume = onResume,
                onRetryFailed = onRetryFailed,
                onReanalyze = onReanalyze,
            )
        }

        // 4. Tab Content Area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                AnalysisTab.OVERVIEW -> {
                    if (analysis == null) {
                        EmptyAnalysisPlaceholder(
                            item = item,
                            isRunning = jobState.running,
                            isOnline = isOnline,
                            horizontalPadding = horizontalPadding,
                            onRunAnalysis = onReanalyze
                        )
                    } else {
                        AnalysisOverviewTab(
                            analysis = analysis,
                            horizontalPadding = horizontalPadding,
                            onJumpToAsk = { selectedTab = AnalysisTab.ASK }
                        )
                    }
                }

                AnalysisTab.DETAILED -> {
                    if (analysis == null) {
                        EmptyAnalysisPlaceholder(
                            item = item,
                            isRunning = jobState.running,
                            isOnline = isOnline,
                            horizontalPadding = horizontalPadding,
                            onRunAnalysis = onReanalyze
                        )
                    } else {
                        AnalysisDetailedTab(
                            analysis = analysis,
                            horizontalPadding = horizontalPadding
                        )
                    }
                }

                AnalysisTab.REFERENCES -> {
                    if (analysis == null) {
                        EmptyAnalysisPlaceholder(
                            item = item,
                            isRunning = jobState.running,
                            isOnline = isOnline,
                            horizontalPadding = horizontalPadding,
                            onRunAnalysis = onReanalyze
                        )
                    } else {
                        AnalysisReferencesTab(
                            analysis = analysis,
                            horizontalPadding = horizontalPadding
                        )
                    }
                }

                AnalysisTab.EXTRACTED -> {
                    ExtractedContentTab(
                        item = item,
                        sections = fullSections,
                        disclosure = analysis?.extractionDisclosure.orEmpty(),
                        horizontalPadding = horizontalPadding,
                        onReanalyze = onReanalyze
                    )
                }

                AnalysisTab.ASK -> {
                    AskDocumentTab(
                        item = item,
                        analysis = analysis,
                        sectionsCount = fullSections.size,
                        messages = qaMessages,
                        isBusy = isQaBusy,
                        isOnline = isOnline,
                        selectedMode = docQaMode,
                        horizontalPadding = horizontalPadding,
                        onModeChange = { docQaMode = it },
                        onSendQuestion = { q -> onAskDocument(q, docQaMode) },
                        onClearChat = onClearQa
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveStreamingProgressCard(
    jobState: DocumentAnalysisJobState,
    horizontalPadding: Dp,
    onCancel: () -> Unit,
) {
    NexusCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 10.dp)
            .testTag("live_analysis_progress_card"),
        borderColor = NexusVioletLight.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = NexusVioletLight,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = jobState.stageLabel.ifBlank { jobState.phase.label },
                            style = MaterialTheme.typography.titleSmall,
                            color = NexusTextPrimary
                        )
                        if (jobState.detail.isNotBlank()) {
                            Text(
                                text = jobState.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = NexusTextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onCancel,
                    border = BorderStroke(1.dp, NexusError.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("cancel_analysis_button")
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = "Cancel analysis", tint = NexusError, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cancel", color = NexusError, style = MaterialTheme.typography.labelMedium)
                }
            }

            // Stage metrics strip
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricChip("Upload", if (jobState.uploadProgress >= 1f) "Done" else "${(jobState.uploadProgress * 100).toInt()}%", NexusOnlineGreen)
                if (jobState.sectionsExtracted > 0 || jobState.totalSections > 0) {
                    MetricChip("Sections", "${jobState.sectionsExtracted}/${jobState.totalSections.coerceAtLeast(jobState.sectionsExtracted)}", NexusCyan)
                }
                if (jobState.totalChunks > 0) {
                    MetricChip(
                        "AI Chunks",
                        "${jobState.chunksCompleted}/${jobState.totalChunks}" + if (jobState.chunksFailed > 0) " (${jobState.chunksFailed} failed)" else "",
                        if (jobState.chunksFailed > 0) NexusWarning else NexusVioletLight
                    )
                }
            }

            val progress = jobState.progressFraction
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = NexusCyan,
                    trackColor = NexusBorder
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = NexusCyan,
                    trackColor = NexusBorder
                )
            }

            // Live streaming preview output
            if (jobState.streamingOutput.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = NexusSurfaceElevated,
                    border = BorderStroke(1.dp, NexusCyan.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_streaming_output_box")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "LIVE STREAMING OUTPUT",
                            style = MaterialTheme.typography.labelSmall,
                            color = NexusCyan
                        )
                        Text(
                            text = jobState.streamingOutput,
                            style = MaterialTheme.typography.bodySmall,
                            color = NexusTextSecondary,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (jobState.chunkStatuses.size > 1) {
                ChunkStatusStrip(jobState.chunkStatuses)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResilientRecoveryCard(
    jobState: DocumentAnalysisJobState,
    horizontalPadding: Dp,
    onResume: () -> Unit,
    onRetryFailed: () -> Unit,
    onReanalyze: () -> Unit,
) {
    val isError = jobState.errorMessage != null
    val accent = if (isError) NexusError else NexusWarning

    NexusCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 10.dp)
            .testTag("analysis_recovery_card"),
        borderColor = accent.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isError) Icons.Default.ErrorOutline else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = accent
                )
                Text(
                    text = jobState.stageLabel.ifBlank { if (isError) "Analysis Interrupted (Progress Saved)" else "Saved Checkpoint Ready" },
                    style = MaterialTheme.typography.titleSmall,
                    color = accent
                )
            }
            Text(
                text = jobState.errorMessage ?: jobState.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = NexusTextPrimary
            )

            if (jobState.chunkStatuses.isNotEmpty()) {
                ChunkStatusStrip(jobState.chunkStatuses)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (jobState.canRetryFailed && jobState.chunksFailed > 0) {
                    Button(
                        onClick = onRetryFailed,
                        colors = ButtonDefaults.buttonColors(containerColor = NexusWarning),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("retry_failed_sections_button")
                    ) {
                        Icon(Icons.Default.Replay, contentDescription = null, tint = NexusSurfaceElevated, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry Failed (${jobState.chunksFailed})", color = NexusSurfaceElevated)
                    }
                }
                if (jobState.canResume) {
                    Button(
                        onClick = onResume,
                        colors = ButtonDefaults.buttonColors(containerColor = NexusCyan),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("resume_analysis_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = NexusSurfaceElevated, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Resume Analysis", color = NexusSurfaceElevated)
                    }
                }
                OutlinedButton(
                    onClick = onReanalyze,
                    border = BorderStroke(1.dp, NexusBorderBright),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = NexusTextPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Restart Full Analysis", color = NexusTextPrimary)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChunkStatusStrip(chunks: List<ChunkAnalysisCheckpoint>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chunks.take(12).forEach { cp ->
            val color = when (cp.status) {
                ChunkProcessingStatus.COMPLETED -> NexusOnlineGreen
                ChunkProcessingStatus.RUNNING -> NexusCyan
                ChunkProcessingStatus.FAILED -> NexusError
                ChunkProcessingStatus.PENDING -> NexusTextMuted
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = color.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, color.copy(alpha = 0.42f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = when (cp.status) {
                            ChunkProcessingStatus.COMPLETED -> Icons.Default.CheckCircle
                            ChunkProcessingStatus.FAILED -> Icons.Default.ErrorOutline
                            else -> Icons.Default.AutoAwesome
                        },
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "C${cp.chunkIndex}: ${cp.status.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = color
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun EmptyAnalysisPlaceholder(
    item: VaultItem,
    isRunning: Boolean,
    isOnline: Boolean,
    horizontalPadding: Dp,
    onRunAnalysis: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding, vertical = 24.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        NexusCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = NexusBorderBright.copy(alpha = 0.65f)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = NexusCyan,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = if (isRunning) "Streaming Content-Grounded Analysis..." else "Ready for Content-Grounded Analysis",
                    style = MaterialTheme.typography.titleMedium,
                    color = NexusTextPrimary
                )
                Text(
                    text = "NEXUS AI extracts actual text, tables, formulas, slides, embedded visuals, or OCR pages from \"${item.title}\" and saves every completed chunk checkpoint so timeouts never lose work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexusTextSecondary
                )
                if (!isRunning) {
                    Button(
                        onClick = onRunAnalysis,
                        enabled = isOnline || item.content.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = NexusViolet),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("start_analysis_button")
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Run Full Content Analysis")
                    }
                }
                if (!isOnline) {
                    Text(
                        text = "You are currently offline. Reconnect to run Firebase AI Logic multimodal OCR & structured analysis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NexusWarning
                    )
                }
            }
        }
    }
}
