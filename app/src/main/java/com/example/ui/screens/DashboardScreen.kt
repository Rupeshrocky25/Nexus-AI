package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.ActualInferenceSource
import com.example.data.model.AiAuditLog
import com.example.data.model.OnDeviceCapabilityStatus
import com.example.data.model.UiState
import com.example.data.model.VaultItem
import com.example.data.model.VaultItemType
import com.example.data.repository.DocumentImportHelper
import com.example.ui.components.AiActivityOrb
import com.example.ui.components.CategoryPill
import com.example.ui.components.NexusCard
import com.example.ui.components.NexusStarLogo
import com.example.ui.components.QuickActionCard
import com.example.ui.components.SparklineCanvas
import com.example.ui.components.StatusPill
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.theme.NexusBlue
import com.example.ui.theme.NexusBorder
import com.example.ui.theme.NexusCyan
import com.example.ui.theme.NexusEmerald
import com.example.ui.theme.NexusEmeraldLight
import com.example.ui.theme.NexusError
import com.example.ui.theme.NexusOnlineGreen
import com.example.ui.theme.NexusSurface
import com.example.ui.theme.NexusSurfaceElevated
import com.example.ui.theme.NexusTextMuted
import com.example.ui.theme.NexusTextPrimary
import com.example.ui.theme.NexusTextSecondary
import com.example.ui.theme.NexusViolet
import com.example.ui.theme.NexusVioletLight
import com.example.ui.theme.NexusWarning

@Composable
fun DashboardScreen(
    userDisplayName: String,
    userPhotoUrl: String?,
    isOnline: Boolean,
    isAssistantBusy: Boolean,
    deviceCapability: OnDeviceCapabilityStatus,
    searchQuery: String,
    vaultState: UiState<List<VaultItem>>,
    aiLogsState: UiState<List<AiAuditLog>>,
    isCompact: Boolean,
    onSearchChange: (String) -> Unit,
    onNewNote: () -> Unit,
    onUploadFile: () -> Unit,
    onSaveLink: () -> Unit,
    onAskAi: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenSettings: () -> Unit,
    onItemClick: (VaultItem) -> Unit,
    onOpenAnalysis: (VaultItem) -> Unit = onItemClick,
    onViewFile: (VaultItem) -> Unit = {},
    onDownloadFile: (VaultItem) -> Unit = {},
    onQuickSummarize: (VaultItem) -> Unit,
    onDeleteItem: (VaultItem) -> Unit,
) {
    val allItems = (vaultState as? UiState.Success)?.data.orEmpty()
    val filteredItems = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems
        else allItems.filter { item ->
            item.title.contains(searchQuery, ignoreCase = true) ||
                item.content.contains(searchQuery, ignoreCase = true) ||
                item.category.contains(searchQuery, ignoreCase = true) ||
                item.tags.any { it.contains(searchQuery, ignoreCase = true) }
        }
    }
    val logs = (aiLogsState as? UiState.Success)?.data.orEmpty()
    val lastSuccessfulLog = remember(logs) { logs.firstOrNull { it.succeeded } }

    val totalNotes = remember(allItems) { allItems.count { it.resolvedType() == VaultItemType.NOTE } }
    val totalDocsAndLinks = remember(allItems) {
        allItems.count { it.resolvedType() == VaultItemType.DOCUMENT || it.resolvedType() == VaultItemType.LINK }
    }
    val totalStorageBytes = remember(allItems) { allItems.sumOf { it.sizeBytes } }

    val noteSparkline = remember(allItems) {
        val counts = allItems.filter { it.resolvedType() == VaultItemType.NOTE }
            .take(8)
            .reversed()
            .mapIndexed { idx, _ -> (idx + 1).toFloat() }
        if (counts.size >= 2) counts else listOf(0.2f, 0.35f, 0.3f, 0.55f, 0.6f, 0.85f)
    }
    val docSparkline = remember(allItems) {
        val counts = allItems.filter { it.resolvedType() != VaultItemType.NOTE }
            .take(8)
            .reversed()
            .mapIndexed { idx, _ -> (idx + 1).toFloat() }
        if (counts.size >= 2) counts else listOf(0.15f, 0.3f, 0.28f, 0.5f, 0.62f, 0.78f)
    }
    val storageSparkline = remember(allItems) {
        val sizes = allItems.take(8).reversed().map { it.sizeBytes.coerceAtLeast(100L).toFloat() }
        if (sizes.size >= 2) sizes else listOf(0.2f, 0.32f, 0.45f, 0.48f, 0.7f, 0.9f)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        contentPadding = PaddingValues(
            horizontal = if (isCompact) 16.dp else 28.dp,
            vertical = if (isCompact) 14.dp else 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 1. Header with Welcome back!, Search bar, Online badge & Avatar
        item {
            DashboardHeader(
                userDisplayName = userDisplayName,
                userPhotoUrl = userPhotoUrl,
                isOnline = isOnline,
                searchQuery = searchQuery,
                isCompact = isCompact,
                onSearchChange = onSearchChange,
                onOpenSettings = onOpenSettings,
            )
        }

        // 2. 4 Quick Action Gradient Cards (2x2 on phone, 4-in-a-row on tablet)
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 700.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        QuickActionCard(
                            title = "New Note",
                            subtitle = "Capture your thoughts",
                            icon = Icons.Default.NoteAdd,
                            accentColor = NexusViolet,
                            testTagName = "quick_action_new_note",
                            onClick = onNewNote,
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionCard(
                            title = "Upload File",
                            subtitle = "Add documents",
                            icon = Icons.Default.CloudUpload,
                            accentColor = NexusCyan,
                            testTagName = "quick_action_upload_file",
                            onClick = onUploadFile,
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionCard(
                            title = "Save Link",
                            subtitle = "Store web content",
                            icon = Icons.Default.Link,
                            accentColor = NexusEmerald,
                            testTagName = "quick_action_save_link",
                            onClick = onSaveLink,
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionCard(
                            title = "Ask AI",
                            subtitle = "Get intelligent answers",
                            icon = Icons.Default.AutoAwesome,
                            accentColor = Color(0xFF6366F1),
                            testTagName = "quick_action_ask_ai",
                            onClick = onAskAi,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            QuickActionCard(
                                title = "New Note",
                                subtitle = "Capture thoughts",
                                icon = Icons.Default.NoteAdd,
                                accentColor = NexusViolet,
                                testTagName = "quick_action_new_note",
                                onClick = onNewNote,
                                modifier = Modifier.weight(1f)
                            )
                            QuickActionCard(
                                title = "Upload File",
                                subtitle = "Add documents",
                                icon = Icons.Default.CloudUpload,
                                accentColor = NexusCyan,
                                testTagName = "quick_action_upload_file",
                                onClick = onUploadFile,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            QuickActionCard(
                                title = "Save Link",
                                subtitle = "Store web content",
                                icon = Icons.Default.Link,
                                accentColor = NexusEmerald,
                                testTagName = "quick_action_save_link",
                                onClick = onSaveLink,
                                modifier = Modifier.weight(1f)
                            )
                            QuickActionCard(
                                title = "Ask AI",
                                subtitle = "Intelligent answers",
                                icon = Icons.Default.AutoAwesome,
                                accentColor = Color(0xFF6366F1),
                                testTagName = "quick_action_ask_ai",
                                onClick = onAskAi,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 3. Middle Row: Your Knowledge Vault + AI Activity Orb Card
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 720.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RecentVaultCard(
                            vaultState = vaultState,
                            items = filteredItems.take(4),
                            onViewAll = onOpenVault,
                            onNewNote = onNewNote,
                            onItemClick = onItemClick,
                            onOpenAnalysis = onOpenAnalysis,
                            onViewFile = onViewFile,
                            onDownloadFile = onDownloadFile,
                            onQuickSummarize = onQuickSummarize,
                            onDeleteItem = onDeleteItem,
                            modifier = Modifier.weight(1.45f)
                        )
                        AiActivityCard(
                            isOnline = isOnline,
                            isBusy = isAssistantBusy,
                            deviceCapability = deviceCapability,
                            lastLog = lastSuccessfulLog,
                            totalRuns = logs.size,
                            onViewDetails = onOpenPrivacy,
                            onAskAi = onAskAi,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RecentVaultCard(
                            vaultState = vaultState,
                            items = filteredItems.take(4),
                            onViewAll = onOpenVault,
                            onNewNote = onNewNote,
                            onItemClick = onItemClick,
                            onOpenAnalysis = onOpenAnalysis,
                            onViewFile = onViewFile,
                            onDownloadFile = onDownloadFile,
                            onQuickSummarize = onQuickSummarize,
                            onDeleteItem = onDeleteItem,
                            modifier = Modifier.fillMaxWidth()
                        )
                        AiActivityCard(
                            isOnline = isOnline,
                            isBusy = isAssistantBusy,
                            deviceCapability = deviceCapability,
                            lastLog = lastSuccessfulLog,
                            totalRuns = logs.size,
                            onViewDetails = onOpenPrivacy,
                            onAskAi = onAskAi,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 4. Bottom Stats & Privacy Priority Row
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 760.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        StatSparklineCard(
                            title = "Total Notes",
                            value = totalNotes.toString(),
                            subtitle = "${allItems.size} total items",
                            icon = Icons.Default.Description,
                            accent = NexusVioletLight,
                            sparkValues = noteSparkline,
                            modifier = Modifier.weight(1f)
                        )
                        StatSparklineCard(
                            title = "Documents",
                            value = totalDocsAndLinks.toString(),
                            subtitle = "Files & web links",
                            icon = Icons.Default.Folder,
                            accent = NexusCyan,
                            sparkValues = docSparkline,
                            modifier = Modifier.weight(1f)
                        )
                        StatSparklineCard(
                            title = "Storage Used",
                            value = DocumentImportHelper.formatBytes(totalStorageBytes),
                            subtitle = "Offline cache on",
                            icon = Icons.Default.Cloud,
                            accent = NexusEmeraldLight,
                            sparkValues = storageSparkline,
                            modifier = Modifier.weight(1f)
                        )
                        PrivacyPriorityCard(
                            onClick = onOpenPrivacy,
                            modifier = Modifier.weight(1.25f)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatSparklineCard(
                                title = "Total Notes",
                                value = totalNotes.toString(),
                                subtitle = "${allItems.size} vault items",
                                icon = Icons.Default.Description,
                                accent = NexusVioletLight,
                                sparkValues = noteSparkline,
                                modifier = Modifier.weight(1f)
                            )
                            StatSparklineCard(
                                title = "Documents",
                                value = totalDocsAndLinks.toString(),
                                subtitle = "Files & links",
                                icon = Icons.Default.Folder,
                                accent = NexusCyan,
                                sparkValues = docSparkline,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatSparklineCard(
                                title = "Storage Used",
                                value = DocumentImportHelper.formatBytes(totalStorageBytes),
                                subtitle = "Cached locally",
                                icon = Icons.Default.Cloud,
                                accent = NexusEmeraldLight,
                                sparkValues = storageSparkline,
                                modifier = Modifier.weight(1f)
                            )
                            PrivacyPriorityCard(
                                onClick = onOpenPrivacy,
                                modifier = Modifier.weight(1.2f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    userDisplayName: String,
    userPhotoUrl: String?,
    isOnline: Boolean,
    searchQuery: String,
    isCompact: Boolean,
    onSearchChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (isCompact) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NexusStarLogo(size = 28.dp)
                    Text(
                        text = "NEXUS AI",
                        style = MaterialTheme.typography.titleLarge,
                        color = NexusTextPrimary
                    )
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    WelcomeHeadline()
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Your personal AI-powered knowledge hub",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NexusTextSecondary
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isCompact) {
                    SearchPill(
                        query = searchQuery,
                        onQueryChange = onSearchChange,
                        modifier = Modifier.width(290.dp)
                    )
                }
                StatusPill(
                    text = if (isOnline) "Synced" else "Offline",
                    dotColor = if (isOnline) NexusOnlineGreen else NexusWarning
                )
                UserAvatarButton(
                    displayName = userDisplayName,
                    photoUrl = userPhotoUrl,
                    onClick = onOpenSettings
                )
            }
        }

        if (isCompact) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                WelcomeHeadline()
                Text(
                    text = "Your personal AI-powered knowledge hub",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexusTextSecondary
                )
            }
            SearchPill(
                query = searchQuery,
                onQueryChange = onSearchChange,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WelcomeHeadline() {
    Text(
        text = buildAnnotatedString {
            append("Welcome ")
            withStyle(SpanStyle(color = NexusVioletLight)) {
                append("back!")
            }
        },
        style = MaterialTheme.typography.displaySmall,
        color = NexusTextPrimary
    )
}

@Composable
private fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(999.dp),
        color = NexusSurfaceElevated,
        border = BorderStroke(1.dp, NexusBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search Knowledge Vault",
                tint = NexusTextMuted,
                modifier = Modifier.size(18.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search your notes, documents...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexusTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NexusTextPrimary),
                    cursorBrush = SolidColor(NexusCyan),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dashboard_search_input")
                )
            }
        }
    }
}

@Composable
private fun UserAvatarButton(
    displayName: String,
    photoUrl: String?,
    onClick: () -> Unit,
) {
    val initials = displayName.trim().split(" ")
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifBlank { "N" }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(NexusViolet.copy(alpha = 0.25f))
            .border(1.5.dp, NexusVioletLight.copy(alpha = 0.6f), CircleShape)
            .clickable(onClick = onClick)
            .testTag("profile_avatar_button"),
        contentAlignment = Alignment.Center
    ) {
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = "Account profile",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initials,
                style = MaterialTheme.typography.labelLarge,
                color = NexusTextPrimary
            )
        }
    }
}

@Composable
private fun RecentVaultCard(
    vaultState: UiState<List<VaultItem>>,
    items: List<VaultItem>,
    onViewAll: () -> Unit,
    onNewNote: () -> Unit,
    onItemClick: (VaultItem) -> Unit,
    onOpenAnalysis: (VaultItem) -> Unit,
    onViewFile: (VaultItem) -> Unit,
    onDownloadFile: (VaultItem) -> Unit,
    onQuickSummarize: (VaultItem) -> Unit,
    onDeleteItem: (VaultItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    NexusCard(modifier = modifier) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = NexusVioletLight,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Your Knowledge Vault",
                        style = MaterialTheme.typography.titleLarge,
                        color = NexusTextPrimary
                    )
                }
                TextButton(
                    onClick = onViewAll,
                    modifier = Modifier.testTag("vault_view_all_button")
                ) {
                    Text("View All", color = NexusVioletLight, style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "View all vault items",
                        tint = NexusVioletLight,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (vaultState) {
                is UiState.Loading -> {
                    Text(
                        text = "Loading your Knowledge Vault...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexusTextSecondary,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
                is UiState.Error -> {
                    Text(
                        text = vaultState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexusError,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                is UiState.Success -> {
                    if (items.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = NexusTextMuted,
                                modifier = Modifier.size(34.dp)
                            )
                            Text(
                                text = "Your vault is ready for your first note, link, or document.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NexusTextSecondary
                            )
                            TextButton(onClick = onNewNote) {
                                Text("Create your first note", color = NexusCyan)
                            }
                        }
                    } else {
                        items.forEachIndexed { idx, item ->
                            VaultRecentRow(
                                item = item,
                                onClick = { onItemClick(item) },
                                onOpenAnalysis = { onOpenAnalysis(item) },
                                onViewFile = { onViewFile(item) },
                                onDownloadFile = { onDownloadFile(item) },
                                onQuickSummarize = { onQuickSummarize(item) },
                                onDelete = { onDeleteItem(item) }
                            )
                            if (idx < items.lastIndex) {
                                HorizontalDivider(color = NexusBorder.copy(alpha = 0.55f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultRecentRow(
    item: VaultItem,
    onClick: () -> Unit,
    onOpenAnalysis: () -> Unit,
    onViewFile: () -> Unit,
    onDownloadFile: () -> Unit,
    onQuickSummarize: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val leadingIcon = when (item.resolvedType()) {
        VaultItemType.NOTE -> Icons.Default.Description
        VaultItemType.DOCUMENT -> Icons.Default.Folder
        VaultItemType.LINK -> Icons.Default.Link
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = item.resolvedType().label,
            tint = NexusTextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = NexusTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.content.isNotBlank()) {
                Text(
                    text = item.content.replace("\n", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = NexusTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = formatRelativeTimestamp(item.updatedAt ?: item.createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = NexusTextMuted
        )
        CategoryPill(category = item.category)
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Item options",
                    tint = NexusTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = NexusSurfaceElevated
            ) {
                DropdownMenuItem(
                    text = { Text("View File / Document", color = NexusTextPrimary) },
                    onClick = { menuExpanded = false; onViewFile() }
                )
                DropdownMenuItem(
                    text = { Text("Download File", color = NexusOnlineGreen) },
                    onClick = { menuExpanded = false; onDownloadFile() }
                )
                DropdownMenuItem(
                    text = { Text("Open Document Analysis", color = NexusCyan) },
                    onClick = { menuExpanded = false; onOpenAnalysis() }
                )
                DropdownMenuItem(
                    text = { Text("Summarize with Hybrid AI", color = NexusTextPrimary) },
                    onClick = { menuExpanded = false; onQuickSummarize() }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = NexusError) },
                    onClick = { menuExpanded = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun AiActivityCard(
    isOnline: Boolean,
    isBusy: Boolean,
    deviceCapability: OnDeviceCapabilityStatus,
    lastLog: AiAuditLog?,
    totalRuns: Int,
    onViewDetails: () -> Unit,
    onAskAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastSource = lastLog?.resolvedActualSource()
    val pillColor = when {
        !isOnline && !deviceCapability.sdkOnDeviceProviderAvailable -> NexusWarning
        lastSource == ActualInferenceSource.ON_DEVICE -> NexusOnlineGreen
        lastSource == ActualInferenceSource.IN_CLOUD -> NexusCyan
        deviceCapability.aiCorePackageInstalled && deviceCapability.sdkOnDeviceProviderAvailable -> NexusOnlineGreen
        else -> NexusCyan
    }

    val truthfulPillText = when {
        !isOnline && !deviceCapability.sdkOnDeviceProviderAvailable -> "Offline • Cloud Unavailable"
        lastSource == ActualInferenceSource.ON_DEVICE -> "Last: On-Device • Private"
        lastSource == ActualInferenceSource.IN_CLOUD -> "Last: Cloud • ${lastLog.modelName}"
        deviceCapability.aiCorePackageInstalled && deviceCapability.sdkOnDeviceProviderAvailable ->
            "On-Device Ready • Private"
        else -> "Hybrid • Cloud Fallback Ready"
    }

    NexusCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAskAi)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
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
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = NexusVioletLight,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "AI Activity",
                        style = MaterialTheme.typography.titleLarge,
                        color = NexusTextPrimary
                    )
                }
                TextButton(
                    onClick = onViewDetails,
                    modifier = Modifier.testTag("ai_activity_details_button")
                ) {
                    Text("View Details", color = NexusCyan, style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "View AI Activity details",
                        tint = NexusCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            AiActivityOrb(
                isBusy = isBusy,
                lastSource = lastSource,
                size = 92.dp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isBusy) "Synthesizing..." else "Ready to help!",
                style = MaterialTheme.typography.titleMedium,
                color = NexusTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (totalRuns > 0) {
                    "Powered by Firebase AI Logic • $totalRuns logged runs"
                } else {
                    "Powered by Firebase AI Logic"
                },
                style = MaterialTheme.typography.bodySmall,
                color = NexusTextSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = pillColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, pillColor.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(pillColor)
                    )
                    Text(
                        text = truthfulPillText,
                        style = MaterialTheme.typography.labelMedium,
                        color = pillColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun StatSparklineCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    sparkValues: List<Float>,
    modifier: Modifier = Modifier,
) {
    NexusCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodySmall,
                        color = NexusTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = NexusTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            SparklineCanvas(
                values = sparkValues,
                lineColor = accent,
                modifier = Modifier
                    .width(56.dp)
                    .height(34.dp)
            )
        }
    }
}

@Composable
private fun PrivacyPriorityCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NexusCard(
        modifier = modifier
            .clickable(onClick = onClick)
            .testTag("dashboard_privacy_card"),
        borderColor = NexusCyan.copy(alpha = 0.32f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(NexusCyan.copy(alpha = 0.14f))
                    .border(1.dp, NexusCyan.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Privacy Center",
                    tint = NexusCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Your Privacy. Our Priority.",
                    style = MaterialTheme.typography.titleSmall,
                    color = NexusTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Your data stays yours — with on-device processing whenever possible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexusTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(NexusBlue.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open Privacy Center",
                    tint = NexusCyan,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
