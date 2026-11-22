package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.AiTaskType
import com.example.data.model.VaultItem
import com.example.data.model.VaultItemType
import com.example.data.repository.DocumentImportHelper
import com.example.ui.components.CategoryPill
import com.example.ui.components.InferenceSourceBadge
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.theme.NexusBorder
import com.example.ui.theme.NexusBorderBright
import com.example.ui.theme.NexusCyan
import com.example.ui.theme.NexusEmerald
import com.example.ui.theme.NexusEmeraldLight
import com.example.ui.theme.NexusError
import com.example.ui.theme.NexusSurface
import com.example.ui.theme.NexusSurfaceElevated
import com.example.ui.theme.NexusTextPrimary
import com.example.ui.theme.NexusTextSecondary
import com.example.ui.theme.NexusViolet
import com.example.ui.theme.NexusVioletLight
import com.example.ui.viewmodel.NoteAiToolState

val DEFAULT_CATEGORIES = listOf("Ideas", "Notes", "Plans", "Research", "Personal", "Docs", "Links")

@Composable
fun NoteEditorDialog(
    existingItem: VaultItem? = null,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, category: String, tagsCsv: String, sourceUrl: String?) -> Unit,
) {
    var title by remember(existingItem?.id) { mutableStateOf(existingItem?.title.orEmpty()) }
    var content by remember(existingItem?.id) { mutableStateOf(existingItem?.content.orEmpty()) }
    var category by remember(existingItem?.id) { mutableStateOf(existingItem?.category ?: "Notes") }
    var tagsCsv by remember(existingItem?.id) { mutableStateOf(existingItem?.tags?.joinToString(", ").orEmpty()) }
    var sourceUrl by remember(existingItem?.id) { mutableStateOf(existingItem?.sourceUrl.orEmpty()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = NexusViolet,
        unfocusedBorderColor = NexusBorder,
        focusedTextColor = NexusTextPrimary,
        unfocusedTextColor = NexusTextPrimary,
        focusedLabelColor = NexusVioletLight,
        unfocusedLabelColor = NexusTextSecondary,
        cursorColor = NexusCyan,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NexusSurface,
        titleContentColor = NexusTextPrimary,
        textContentColor = NexusTextSecondary,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = if (existingItem == null) "New Note" else "Edit ${existingItem.resolvedType().label}",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(200); validationError = null },
                    label = { Text("Title") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_title_input")
                )

                Text("Category", style = MaterialTheme.typography.labelLarge, color = NexusTextSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DEFAULT_CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NexusViolet.copy(alpha = 0.25f),
                                selectedLabelColor = NexusVioletLight,
                                labelColor = NexusTextSecondary,
                            )
                        )
                    }
                }

                if (existingItem?.resolvedType() == VaultItemType.LINK) {
                    OutlinedTextField(
                        value = sourceUrl,
                        onValueChange = { sourceUrl = it.take(2048) },
                        label = { Text("URL") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content / Knowledge") },
                    minLines = 5,
                    maxLines = 10,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_content_input")
                )

                OutlinedTextField(
                    value = tagsCsv,
                    onValueChange = { tagsCsv = it },
                    label = { Text("Tags (comma separated)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_tags_input")
                )

                if (validationError != null) {
                    Text(
                        text = validationError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = NexusError
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.trim().isEmpty()) {
                        validationError = "Please enter a title."
                    } else {
                        onSave(title, content, category, tagsCsv, sourceUrl.takeIf { it.isNotBlank() })
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NexusViolet),
                modifier = Modifier.testTag("save_note_button")
            ) {
                Text(if (existingItem == null) "Save Note" else "Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NexusTextSecondary)
            }
        }
    )
}

@Composable
fun SaveLinkDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, notes: String, category: String, tagsCsv: String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var tagsCsv by remember { mutableStateOf("web, reference") }
    var errorText by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = NexusEmerald,
        unfocusedBorderColor = NexusBorder,
        focusedTextColor = NexusTextPrimary,
        unfocusedTextColor = NexusTextPrimary,
        focusedLabelColor = NexusEmeraldLight,
        unfocusedLabelColor = NexusTextSecondary,
        cursorColor = NexusEmeraldLight,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NexusSurface,
        titleContentColor = NexusTextPrimary,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Link, contentDescription = null, tint = NexusEmeraldLight)
                Text("Save Web Link", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.take(2048); errorText = null },
                    label = { Text("Website URL (https://...)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("link_url_input")
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(200) },
                    label = { Text("Title (optional)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("link_title_input")
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Key takeaways / annotations") },
                    minLines = 3,
                    maxLines = 6,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tagsCsv,
                    onValueChange = { tagsCsv = it },
                    label = { Text("Tags (comma separated)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorText != null) {
                    Text(errorText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = NexusError)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = url.trim()
                    if (trimmed.isEmpty() || !trimmed.contains('.')) {
                        errorText = "Enter a valid website URL (for example firebase.google.com)."
                    } else {
                        onSave(title, trimmed, notes, "Links", tagsCsv)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NexusEmerald),
                modifier = Modifier.testTag("confirm_save_link_button")
            ) {
                Text("Save Link")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NexusTextSecondary)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VaultItemDetailSheet(
    item: VaultItem,
    aiToolState: NoteAiToolState,
    hasFullAnalysis: Boolean = false,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onRunAiTool: (AiTaskType) -> Unit,
    onOpenFullAnalysis: () -> Unit = {},
    onViewFile: () -> Unit = {},
    onDownloadFile: () -> Unit = {},
    onAskCloudAssistant: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NexusSurface,
        contentColor = NexusTextPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CategoryPill(category = item.category)
                    Text(
                        text = "${item.resolvedType().label} • ${formatRelativeTimestamp(item.updatedAt ?: item.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NexusTextSecondary
                    )
                }
                Row {
                    IconButton(onClick = onTogglePin, modifier = Modifier.testTag("detail_pin_button")) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = if (item.pinned) "Unpin item" else "Pin item",
                            tint = if (item.pinned) NexusCyan else NexusTextSecondary
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.testTag("detail_edit_button")) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit item", tint = NexusVioletLight)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.testTag("detail_delete_button")) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete item", tint = NexusError)
                    }
                }
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineMedium,
                color = NexusTextPrimary
            )

            if (!item.sourceUrl.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = NexusEmerald.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, NexusEmerald.copy(alpha = 0.30f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = NexusEmeraldLight, modifier = Modifier.size(18.dp))
                        Text(
                            text = item.sourceUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = NexusEmeraldLight
                        )
                    }
                }
            }

            if (!item.fileName.isNullOrBlank()) {
                Text(
                    text = "File: ${item.fileName} (${DocumentImportHelper.formatBytes(item.sizeBytes)})",
                    style = MaterialTheme.typography.labelMedium,
                    color = NexusCyan
                )
            }

            if (item.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item.tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = NexusSurfaceElevated,
                            border = BorderStroke(1.dp, NexusBorder)
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelSmall,
                                color = NexusTextSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = NexusSurfaceElevated,
                border = BorderStroke(1.dp, NexusBorder)
            ) {
                Text(
                    text = item.content.ifBlank { "No content added yet." },
                    style = MaterialTheme.typography.bodyLarge,
                    color = NexusTextPrimary,
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (!item.aiSummary.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = NexusViolet.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, NexusViolet.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NexusVioletLight, modifier = Modifier.size(16.dp))
                            Text("Saved AI Summary", style = MaterialTheme.typography.labelLarge, color = NexusVioletLight)
                        }
                        Text(item.aiSummary, style = MaterialTheme.typography.bodyMedium, color = NexusTextPrimary)
                    }
                }
            }

            HorizontalDivider(color = NexusBorder)

            // Local-Preferred Intelligence Toolbar (PREFER_ON_DEVICE)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Local-Preferred Intelligence",
                        style = MaterialTheme.typography.titleSmall,
                        color = NexusTextPrimary
                    )
                    Text(
                        text = "PREFER_ON_DEVICE",
                        style = MaterialTheme.typography.labelSmall,
                        color = NexusCyan
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AiActionChip("Summarize", Icons.Default.Summarize, aiToolState.runningTask == null, "ai_summarize_chip") {
                        onRunAiTool(AiTaskType.SUMMARIZE)
                    }
                    AiActionChip("Classify & Tag", Icons.Default.Label, aiToolState.runningTask == null, "ai_classify_chip") {
                        onRunAiTool(AiTaskType.CLASSIFY)
                    }
                    AiActionChip("Keywords", Icons.Default.AutoAwesome, aiToolState.runningTask == null, "ai_keywords_chip") {
                        onRunAiTool(AiTaskType.KEYWORDS)
                    }
                    AiActionChip("Extract Tasks", Icons.Default.TaskAlt, aiToolState.runningTask == null, "ai_tasks_chip") {
                        onRunAiTool(AiTaskType.TASKS)
                    }
                    AiActionChip("Rewrite", Icons.Default.Tune, aiToolState.runningTask == null, "ai_rewrite_chip") {
                        onRunAiTool(AiTaskType.REWRITE)
                    }
                }

                if (aiToolState.runningTask != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = NexusVioletLight,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Running ${aiToolState.runningTask.displayLabel} via Firebase AI Logic...",
                            style = MaterialTheme.typography.bodySmall,
                            color = NexusTextSecondary
                        )
                    }
                }

                if (aiToolState.errorMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = NexusError.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, NexusError.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = aiToolState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = NexusError,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (aiToolState.lastResult != null) {
                    val res = aiToolState.lastResult
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = NexusSurfaceElevated,
                        border = BorderStroke(1.dp, NexusBorderBright)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NexusEmeraldLight, modifier = Modifier.size(16.dp))
                                    Text(
                                        text = aiToolState.lastTask?.displayLabel ?: "AI Output",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = NexusTextPrimary
                                    )
                                }
                            }
                            InferenceSourceBadge(
                                source = res.actualSource,
                                modelName = res.modelName,
                                latencyMs = res.latencyMs,
                            )
                            Text(
                                text = res.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NexusTextPrimary
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onViewFile,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("detail_view_file_button"),
                        border = BorderStroke(1.dp, NexusCyan.copy(alpha = 0.55f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, tint = NexusCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("View File", color = NexusCyan)
                    }
                    OutlinedButton(
                        onClick = onDownloadFile,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("detail_download_file_button"),
                        border = BorderStroke(1.dp, NexusEmeraldLight.copy(alpha = 0.55f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = NexusEmeraldLight, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download", color = NexusEmeraldLight)
                    }
                }

                Button(
                    onClick = onOpenFullAnalysis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("detail_open_analysis_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = NexusViolet),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (hasFullAnalysis) "Open Full Document Analysis" else "Run Deep Document Analysis")
                }

                OutlinedButton(
                    onClick = onAskCloudAssistant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("detail_ask_assistant_button"),
                    border = BorderStroke(1.dp, NexusCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NexusCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analyze in Hybrid AI Assistant", color = NexusCyan)
                }
            }
        }
    }
}

@Composable
private fun AiActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(tag),
        label = { Text(label, color = NexusTextPrimary) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = NexusVioletLight,
                modifier = Modifier.size(16.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = NexusSurfaceElevated,
        ),
        border = BorderStroke(1.dp, NexusBorderBright.copy(alpha = 0.7f))
    )
}
