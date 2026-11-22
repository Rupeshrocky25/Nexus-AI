package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.DocumentAnalysis
import com.example.data.model.ExtractionStatus
import com.example.data.model.UiState
import com.example.data.model.VaultItem
import com.example.data.model.VaultItemType
import com.example.data.repository.DocumentImportHelper
import com.example.ui.components.CategoryPill
import com.example.ui.components.NexusCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.theme.NexusBorder
import com.example.ui.theme.NexusCyan
import com.example.ui.theme.NexusEmerald
import com.example.ui.theme.NexusEmeraldLight
import com.example.ui.theme.NexusError
import com.example.ui.theme.NexusOnlineGreen
import com.example.ui.theme.NexusSurfaceElevated
import com.example.ui.theme.NexusTextMuted
import com.example.ui.theme.NexusTextPrimary
import com.example.ui.theme.NexusTextSecondary
import com.example.ui.theme.NexusViolet
import com.example.ui.theme.NexusVioletLight
import com.example.ui.theme.NexusWarning

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultScreen(
    vaultState: UiState<List<VaultItem>>,
    documentAnalyses: Map<String, DocumentAnalysis> = emptyMap(),
    searchQuery: String,
    selectedType: VaultItemType?,
    selectedCategory: String?,
    isOnline: Boolean,
    isCompact: Boolean,
    onSearchChange: (String) -> Unit,
    onTypeChange: (VaultItemType?) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onNewNote: () -> Unit,
    onUploadFile: () -> Unit,
    onSaveLink: () -> Unit,
    onItemClick: (VaultItem) -> Unit,
    onViewFile: (VaultItem) -> Unit = {},
    onDownloadFile: (VaultItem) -> Unit = {},
) {
    val allItems = (vaultState as? UiState.Success)?.data.orEmpty()
    val filteredItems = remember(allItems, searchQuery, selectedType, selectedCategory) {
        allItems.filter { item ->
            val matchesType = selectedType == null || item.resolvedType() == selectedType
            val matchesCat = selectedCategory == null || item.category.equals(selectedCategory, ignoreCase = true)
            val matchesQuery = searchQuery.isBlank() ||
                item.title.contains(searchQuery, ignoreCase = true) ||
                item.content.contains(searchQuery, ignoreCase = true) ||
                item.category.contains(searchQuery, ignoreCase = true) ||
                item.tags.any { it.contains(searchQuery, ignoreCase = true) } ||
                (item.sourceUrl?.contains(searchQuery, ignoreCase = true) == true)
            matchesType && matchesCat && matchesQuery
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("vault_screen"),
        contentPadding = PaddingValues(
            horizontal = if (isCompact) 16.dp else 28.dp,
            vertical = if (isCompact) 14.dp else 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Knowledge Vault",
                        style = MaterialTheme.typography.headlineLarge,
                        color = NexusTextPrimary
                    )
                    Text(
                        text = "${allItems.size} synced items • tap any item for Local-Preferred AI tools",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexusTextSecondary
                    )
                }
                StatusPill(
                    text = if (isOnline) "Cloud Synced" else "Offline Cache",
                    dotColor = if (isOnline) NexusOnlineGreen else NexusWarning
                )
            }
        }

        // Action Buttons Bar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onNewNote,
                    colors = ButtonDefaults.buttonColors(containerColor = NexusViolet),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("vault_new_note_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New Note")
                }
                OutlinedButton(
                    onClick = onUploadFile,
                    border = BorderStroke(1.dp, NexusCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("vault_upload_file_button")
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = NexusCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Upload File", color = NexusCyan)
                }
                OutlinedButton(
                    onClick = onSaveLink,
                    border = BorderStroke(1.dp, NexusEmerald.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("vault_save_link_button")
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = NexusEmeraldLight, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Link", color = NexusEmeraldLight)
                }
            }
        }

        // Search Input
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search titles, content, URLs, or #tags...", color = NexusTextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = NexusTextMuted) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", tint = NexusTextSecondary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NexusViolet,
                    unfocusedBorderColor = NexusBorder,
                    focusedContainerColor = NexusSurfaceElevated,
                    unfocusedContainerColor = NexusSurfaceElevated,
                    focusedTextColor = NexusTextPrimary,
                    unfocusedTextColor = NexusTextPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_search_input")
            )
        }

        // Type & Category Filter Chips
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedType == null,
                        onClick = { onTypeChange(null) },
                        label = { Text("All Types") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NexusViolet.copy(alpha = 0.25f),
                            selectedLabelColor = NexusVioletLight,
                        )
                    )
                    VaultItemType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { onTypeChange(if (selectedType == type) null else type) },
                            label = { Text("${type.label}s") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NexusCyan.copy(alpha = 0.22f),
                                selectedLabelColor = NexusCyan,
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { onCategoryChange(null) },
                        label = { Text("All Categories") }
                    )
                    DEFAULT_CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { onCategoryChange(if (selectedCategory == cat) null else cat) },
                            label = { Text(cat) }
                        )
                    }
                }
            }
        }

        when (vaultState) {
            is UiState.Loading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = NexusVioletLight)
                    }
                }
            }
            is UiState.Error -> {
                item {
                    NexusCard(modifier = Modifier.fillMaxWidth(), borderColor = NexusError.copy(alpha = 0.45f)) {
                        Text(
                            text = vaultState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexusError,
                            modifier = Modifier.padding(18.dp)
                        )
                    }
                }
            }
            is UiState.Success -> {
                if (filteredItems.isEmpty()) {
                    item {
                        NexusCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = NexusTextMuted,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = if (allItems.isEmpty()) "Your Knowledge Vault is empty" else "No matching items found",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NexusTextPrimary
                                )
                                Text(
                                    text = if (allItems.isEmpty()) {
                                        "Create a note, save a web link, or import a document to unlock local-preferred and cloud AI synthesis."
                                    } else {
                                        "Try clearing your search or category filter."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NexusTextSecondary
                                )
                            }
                        }
                    }
                } else {
                    items(filteredItems, key = { it.id }) { item ->
                        VaultItemCard(
                            item = item,
                            analysis = documentAnalyses[item.id],
                            onClick = { onItemClick(item) },
                            onViewFile = { onViewFile(item) },
                            onDownloadFile = { onDownloadFile(item) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VaultItemCard(
    item: VaultItem,
    analysis: DocumentAnalysis?,
    onClick: () -> Unit,
    onViewFile: () -> Unit,
    onDownloadFile: () -> Unit,
) {
    val icon = when (item.resolvedType()) {
        VaultItemType.NOTE -> Icons.Default.Description
        VaultItemType.DOCUMENT -> Icons.Default.Folder
        VaultItemType.LINK -> Icons.Default.Link
    }
    val tint = when (item.resolvedType()) {
        VaultItemType.NOTE -> NexusVioletLight
        VaultItemType.DOCUMENT -> NexusCyan
        VaultItemType.LINK -> NexusEmeraldLight
    }

    NexusCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("vault_item_${item.id}")
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(icon, contentDescription = item.resolvedType().label, tint = tint, modifier = Modifier.size(20.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = NexusTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.pinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = NexusCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                CategoryPill(category = item.category)
            }

            if (item.resolvedType() == VaultItemType.DOCUMENT || analysis != null) {
                val status = analysis?.resolvedExtractionStatus() ?: item.resolvedExtractionStatus()
                val badgeColor = when (status) {
                    ExtractionStatus.COMPLETE -> NexusOnlineGreen
                    ExtractionStatus.PARTIAL -> NexusWarning
                    ExtractionStatus.PENDING_OCR -> NexusCyan
                    ExtractionStatus.FAILED -> NexusError
                    ExtractionStatus.NOT_APPLICABLE -> NexusVioletLight
                }
                val badgeText = when {
                    analysis != null -> "${status.label} • Deep Analysis Ready"
                    else -> "${status.label} • ${item.resolvedExtractionMethod().label}"
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = badgeColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.38f))
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (item.content.isNotBlank()) {
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexusTextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!item.aiSummary.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = NexusViolet.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, NexusViolet.copy(alpha = 0.28f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NexusVioletLight, modifier = Modifier.size(15.dp))
                        Text(
                            text = item.aiSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = NexusTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    item.tags.take(4).forEach { tag ->
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = NexusTextMuted
                        )
                    }
                }
                Text(
                    text = "${DocumentImportHelper.formatBytes(item.sizeBytes)} • ${formatRelativeTimestamp(item.updatedAt ?: item.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NexusTextMuted
                )
            }

            // Quick View & Download Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onViewFile,
                    border = BorderStroke(1.dp, NexusCyan.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("vault_view_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "View ${item.title}",
                        tint = NexusCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("View", color = NexusCyan, style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onDownloadFile,
                    border = BorderStroke(1.dp, NexusOnlineGreen.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("vault_download_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download ${item.title}",
                        tint = NexusOnlineGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download", color = NexusOnlineGreen, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
