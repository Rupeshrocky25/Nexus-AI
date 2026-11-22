package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import com.example.core.signOutUser
import com.example.data.model.ActualInferenceSource
import com.example.data.model.AiTaskType
import com.example.data.model.OnDeviceCapabilityStatus
import com.example.data.model.UiState
import com.example.data.model.VaultItem
import com.example.data.repository.DocumentImportHelper
import com.example.data.repository.FileExportHelper
import com.example.ui.components.DocumentViewerDialog
import com.example.ui.components.NexusAuroraBackground
import com.example.ui.components.NexusStarLogo
import com.example.ui.components.SparklineCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.ui.theme.NexusBorder
import com.example.ui.theme.NexusCyan
import com.example.ui.theme.NexusOnlineGreen
import com.example.ui.theme.NexusSidebarBg
import com.example.ui.theme.NexusSurface
import com.example.ui.theme.NexusSurfaceElevated
import com.example.ui.theme.NexusTextMuted
import com.example.ui.theme.NexusTextPrimary
import com.example.ui.theme.NexusTextSecondary
import com.example.ui.theme.NexusViolet
import com.example.ui.theme.NexusVioletLight
import com.example.ui.theme.NexusWarning
import com.example.ui.viewmodel.NexusViewModel

enum class NexusDestination(
    val route: String,
    val label: String,
    val shortLabel: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME("home", "Home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    VAULT("vault", "Knowledge Vault", "Vault", Icons.Filled.Folder, Icons.Outlined.Folder),
    ASSISTANT("assistant", "AI Assistant", "AI", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    PRIVACY("privacy", "Privacy Center", "Privacy", Icons.Filled.Shield, Icons.Outlined.Shield),
    SETTINGS("settings", "Settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

@Composable
fun MainShell(
    viewModel: NexusViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val vaultState by viewModel.vaultState.collectAsStateWithLifecycle()
    val documentAnalyses by viewModel.documentAnalyses.collectAsStateWithLifecycle()
    val analysisJobs by viewModel.analysisJobs.collectAsStateWithLifecycle()
    val docQaConversations by viewModel.docQaConversations.collectAsStateWithLifecycle()
    val docQaBusyItemIds by viewModel.docQaBusyItemIds.collectAsStateWithLifecycle()
    val aiLogsState by viewModel.aiLogsState.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedType by viewModel.selectedTypeFilter.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategoryFilter.collectAsStateWithLifecycle()
    val activeMode by viewModel.activeInferenceMode.collectAsStateWithLifecycle()
    val selectedContextIds by viewModel.selectedContextItemIds.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isAssistantBusy by viewModel.isAssistantBusy.collectAsStateWithLifecycle()
    val noteAiToolState by viewModel.noteAiToolState.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    var currentDestination by remember { mutableStateOf(NexusDestination.HOME) }
    var showNewNoteDialog by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<VaultItem?>(null) }
    var showSaveLinkDialog by remember { mutableStateOf(false) }
    var inspectedItemId by remember { mutableStateOf<String?>(null) }
    var analyzedItemId by remember { mutableStateOf<String?>(null) }
    var viewingItem by remember { mutableStateOf<VaultItem?>(null) }
    var pendingDownloadItem by remember { mutableStateOf<VaultItem?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusBanner) {
        statusBanner?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    // Back navigation from secondary destinations returns cleanly to Home
    BackHandler(enabled = analyzedItemId == null && currentDestination != NexusDestination.HOME) {
        currentDestination = NexusDestination.HOME
    }

    // Zero-permission SAF document importer (Google Play compliant; supports all file formats)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importDocument(context, uri) { newItemId ->
                inspectedItemId = null
                analyzedItemId = newItemId
            }
        }
    }

    // Zero-permission SAF file saver / downloader (ACTION_CREATE_DOCUMENT)
    val fileDownloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val itemToSave = pendingDownloadItem
        pendingDownloadItem = null
        val destUri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && destUri != null && itemToSave != null) {
            scope.launch {
                val writeRes = withContext(Dispatchers.IO) {
                    FileExportHelper.writeItemToDestinationUri(context, itemToSave, destUri)
                }
                val fileName = FileExportHelper.suggestedDownloadFileName(itemToSave)
                val msg = writeRes.fold(
                    onSuccess = { bytes -> "Saved \"$fileName\" (${DocumentImportHelper.formatBytes(bytes)})." },
                    onFailure = { err -> err.localizedMessage ?: "Failed to save file." }
                )
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    val triggerDownloadFile: (VaultItem) -> Unit = { item ->
        pendingDownloadItem = item
        runCatching {
            fileDownloadLauncher.launch(FileExportHelper.buildCreateDocumentIntent(item))
        }.onFailure { err ->
            pendingDownloadItem = null
            scope.launch {
                snackbarHostState.showSnackbar(err.localizedMessage ?: "Could not open file save dialog.")
            }
        }
    }

    val triggerOpenExternal: (VaultItem) -> Unit = { item ->
        val res = FileExportHelper.openInExternalApp(context, item)
        res.onFailure { err ->
            scope.launch {
                snackbarHostState.showSnackbar(err.localizedMessage ?: "No external app found to open this file.")
            }
        }
    }

    val supportedDocumentMimes = remember { arrayOf("*/*") }

    val allItems = (vaultState as? UiState.Success)?.data.orEmpty()
    val inspectedItem = remember(allItems, inspectedItemId) {
        allItems.firstOrNull { it.id == inspectedItemId }
    }
    val analyzedItem = remember(allItems, analyzedItemId) {
        allItems.firstOrNull { it.id == analyzedItemId }
    }
    val logs = (aiLogsState as? UiState.Success)?.data.orEmpty()
    val lastSource = logs.firstOrNull { it.succeeded }?.resolvedActualSource()

    NexusAuroraBackground {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isExpanded = maxWidth >= 840.dp

            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (!isExpanded) {
                        Column {
                            HorizontalDivider(color = NexusBorder.copy(alpha = 0.7f))
                            NavigationBar(
                                containerColor = NexusSidebarBg.copy(alpha = 0.96f),
                                tonalElevation = 0.dp,
                                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                            ) {
                                NexusDestination.entries.forEach { dest ->
                                    val selected = currentDestination == dest
                                    NavigationBarItem(
                                        selected = selected && analyzedItem == null,
                                        onClick = {
                                            analyzedItemId = null
                                            currentDestination = dest
                                        },
                                        modifier = Modifier.testTag("nav_${dest.route}"),
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                contentDescription = dest.label
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = dest.shortLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = NexusTextPrimary,
                                            selectedTextColor = NexusVioletLight,
                                            indicatorColor = NexusViolet.copy(alpha = 0.30f),
                                            unselectedIconColor = NexusTextMuted,
                                            unselectedTextColor = NexusTextMuted
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .statusBarsPadding()
                ) {
                    // Persistent Desktop/Tablet Sidebar matching the reference UI 1:1
                    if (isExpanded) {
                        NexusSidebar(
                            currentDestination = currentDestination,
                            isOnline = isOnline,
                            deviceCapability = viewModel.deviceCapability,
                            lastSource = lastSource,
                            onSelect = {
                                analyzedItemId = null
                                currentDestination = it
                            },
                            onOpenPrivacy = {
                                analyzedItemId = null
                                currentDestination = NexusDestination.PRIVACY
                            }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        if (analyzedItem != null) {
                            LaunchedEffect(analyzedItem.id) {
                                viewModel.hydrateCheckpointStatus(context, analyzedItem.id)
                            }
                            DocumentAnalysisScreen(
                                item = analyzedItem,
                                analysis = documentAnalyses[analyzedItem.id],
                                jobState = analysisJobs[analyzedItem.id] ?: com.example.data.model.DocumentAnalysisJobState(),
                                qaMessages = docQaConversations[analyzedItem.id].orEmpty(),
                                isQaBusy = analyzedItem.id in docQaBusyItemIds,
                                activeMode = activeMode,
                                isOnline = isOnline,
                                isCompact = !isExpanded,
                                onBack = { analyzedItemId = null },
                                onReanalyze = { viewModel.analyzeOrReanalyzeItem(context, analyzedItem) },
                                onResume = { viewModel.resumeDocumentAnalysis(context, analyzedItem) },
                                onRetryFailed = { viewModel.retryFailedSections(context, analyzedItem) },
                                onCancel = { viewModel.cancelDocumentAnalysis(context, analyzedItem.id) },
                                onViewFile = { viewingItem = analyzedItem },
                                onDownloadFile = { triggerDownloadFile(analyzedItem) },
                                onExport = { analysis -> viewModel.exportDocumentAnalysis(context, analyzedItem, analysis) },
                                onAskDocument = { q, mode -> viewModel.askDocumentQuestion(context, analyzedItem, q, mode) },
                                onClearQa = { viewModel.clearDocQaConversation(analyzedItem.id) }
                            )
                        } else when (currentDestination) {
                            NexusDestination.HOME -> DashboardScreen(
                                userDisplayName = viewModel.userDisplayName,
                                userPhotoUrl = viewModel.userPhotoUrl,
                                isOnline = isOnline,
                                isAssistantBusy = isAssistantBusy,
                                deviceCapability = viewModel.deviceCapability,
                                searchQuery = searchQuery,
                                vaultState = vaultState,
                                aiLogsState = aiLogsState,
                                isCompact = !isExpanded,
                                onSearchChange = { viewModel.updateSearchQuery(it) },
                                onNewNote = { showNewNoteDialog = true },
                                onUploadFile = {
                                    documentPickerLauncher.launch(supportedDocumentMimes)
                                },
                                onSaveLink = { showSaveLinkDialog = true },
                                onAskAi = { currentDestination = NexusDestination.ASSISTANT },
                                onOpenVault = { currentDestination = NexusDestination.VAULT },
                                onOpenPrivacy = { currentDestination = NexusDestination.PRIVACY },
                                onOpenSettings = { currentDestination = NexusDestination.SETTINGS },
                                onItemClick = { item ->
                                    if (item.resolvedType() == com.example.data.model.VaultItemType.DOCUMENT) {
                                        analyzedItemId = item.id
                                    } else {
                                        viewModel.clearNoteAiToolState()
                                        inspectedItemId = item.id
                                    }
                                },
                                onOpenAnalysis = { item ->
                                    analyzedItemId = item.id
                                    if (documentAnalyses[item.id] == null && analysisJobs[item.id]?.running != true) {
                                        viewModel.analyzeOrReanalyzeItem(context, item)
                                    }
                                },
                                onViewFile = { item -> viewingItem = item },
                                onDownloadFile = { item -> triggerDownloadFile(item) },
                                onQuickSummarize = { item ->
                                    viewModel.clearNoteAiToolState()
                                    inspectedItemId = item.id
                                    viewModel.runNoteAiTool(item, AiTaskType.SUMMARIZE)
                                },
                                onDeleteItem = { item ->
                                    viewModel.deleteVaultItem(item.id, context, item.localFilePath)
                                }
                            )

                            NexusDestination.VAULT -> VaultScreen(
                                vaultState = vaultState,
                                documentAnalyses = documentAnalyses,
                                searchQuery = searchQuery,
                                selectedType = selectedType,
                                selectedCategory = selectedCategory,
                                isOnline = isOnline,
                                isCompact = !isExpanded,
                                onSearchChange = { viewModel.updateSearchQuery(it) },
                                onTypeChange = { viewModel.setTypeFilter(it) },
                                onCategoryChange = { viewModel.setCategoryFilter(it) },
                                onNewNote = { showNewNoteDialog = true },
                                onUploadFile = {
                                    documentPickerLauncher.launch(supportedDocumentMimes)
                                },
                                onSaveLink = { showSaveLinkDialog = true },
                                onItemClick = { item ->
                                    if (item.resolvedType() == com.example.data.model.VaultItemType.DOCUMENT) {
                                        analyzedItemId = item.id
                                    } else {
                                        viewModel.clearNoteAiToolState()
                                        inspectedItemId = item.id
                                    }
                                },
                                onViewFile = { item -> viewingItem = item },
                                onDownloadFile = { item -> triggerDownloadFile(item) }
                            )

                            NexusDestination.ASSISTANT -> AiAssistantScreen(
                                messages = messages,
                                isBusy = isAssistantBusy,
                                isOnline = isOnline,
                                activeMode = activeMode,
                                vaultState = vaultState,
                                selectedContextIds = selectedContextIds,
                                isCompact = !isExpanded,
                                onSelectMode = { viewModel.setInferenceMode(it, persistToProfile = false) },
                                onToggleContextItem = { viewModel.toggleContextItem(it) },
                                onSendPrompt = { prompt, task, modeOverride ->
                                    viewModel.sendAssistantPrompt(prompt, task, modeOverride)
                                },
                                onClearChat = { viewModel.clearConversation() }
                            )

                            NexusDestination.PRIVACY -> PrivacyCenterScreen(
                                deviceCapability = viewModel.deviceCapability,
                                isOnline = isOnline,
                                aiLogsState = aiLogsState,
                                totalVaultItems = allItems.size,
                                isCompact = !isExpanded,
                                onClearAiHistory = { viewModel.clearAiHistory() },
                                onDeleteAllVaultData = { viewModel.deleteAllVaultData() }
                            )

                            NexusDestination.SETTINGS -> SettingsScreen(
                                userDisplayName = viewModel.userDisplayName,
                                userEmail = viewModel.userEmail,
                                userPhotoUrl = viewModel.userPhotoUrl,
                                userId = viewModel.currentUserId,
                                activeMode = activeMode,
                                isOnline = isOnline,
                                isCompact = !isExpanded,
                                onUpdateDefaultMode = { viewModel.setInferenceMode(it, persistToProfile = true) },
                                onSignOut = {
                                    signOutUser(credentialManager = credentialManager, scope = scope)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Create Note Dialog
    if (showNewNoteDialog) {
        NoteEditorDialog(
            existingItem = null,
            onDismiss = { showNewNoteDialog = false },
            onSave = { title, content, category, tagsCsv, _ ->
                showNewNoteDialog = false
                viewModel.createNote(title, content, category, tagsCsv)
            }
        )
    }

    // Edit Existing Vault Item Dialog
    editingItem?.let { item ->
        NoteEditorDialog(
            existingItem = item,
            onDismiss = { editingItem = null },
            onSave = { title, content, category, tagsCsv, sourceUrl ->
                editingItem = null
                viewModel.updateVaultItem(item.id, title, content, category, tagsCsv, sourceUrl)
            }
        )
    }

    // Save Web Link Dialog
    if (showSaveLinkDialog) {
        SaveLinkDialog(
            onDismiss = { showSaveLinkDialog = false },
            onSave = { title, url, notes, category, tagsCsv ->
                showSaveLinkDialog = false
                viewModel.saveLink(title, url, notes, category, tagsCsv)
            }
        )
    }

    // Item Detail + Local-Preferred AI Tools Bottom Sheet
    inspectedItem?.let { item ->
        VaultItemDetailSheet(
            item = item,
            aiToolState = noteAiToolState,
            hasFullAnalysis = documentAnalyses[item.id] != null,
            onDismiss = { inspectedItemId = null },
            onEdit = {
                inspectedItemId = null
                editingItem = item
            },
            onDelete = {
                inspectedItemId = null
                viewModel.deleteVaultItem(item.id, context, item.localFilePath)
            },
            onTogglePin = { viewModel.togglePin(item) },
            onRunAiTool = { taskType -> viewModel.runNoteAiTool(item, taskType) },
            onOpenFullAnalysis = {
                inspectedItemId = null
                analyzedItemId = item.id
                if (documentAnalyses[item.id] == null && analysisJobs[item.id]?.running != true) {
                    viewModel.analyzeOrReanalyzeItem(context, item)
                }
            },
            onViewFile = {
                inspectedItemId = null
                viewingItem = item
            },
            onDownloadFile = {
                inspectedItemId = null
                triggerDownloadFile(item)
            },
            onAskCloudAssistant = {
                inspectedItemId = null
                if (item.id !in selectedContextIds) {
                    viewModel.toggleContextItem(item.id)
                }
                currentDestination = NexusDestination.ASSISTANT
            }
        )
    }

    // Full-Screen In-App File & Document Viewer Dialog
    viewingItem?.let { item ->
        DocumentViewerDialog(
            item = item,
            onDismiss = { viewingItem = null },
            onDownload = { triggerDownloadFile(item) },
            onOpenExternal = { triggerOpenExternal(item) },
        )
    }
}

@Composable
private fun NexusSidebar(
    currentDestination: NexusDestination,
    isOnline: Boolean,
    deviceCapability: OnDeviceCapabilityStatus,
    lastSource: ActualInferenceSource?,
    onSelect: (NexusDestination) -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    val statusDot = when {
        !isOnline && !deviceCapability.sdkOnDeviceProviderAvailable -> NexusWarning
        lastSource == ActualInferenceSource.ON_DEVICE -> NexusOnlineGreen
        deviceCapability.aiCorePackageInstalled && deviceCapability.sdkOnDeviceProviderAvailable -> NexusOnlineGreen
        else -> NexusCyan
    }
    val statusLabel = when {
        !isOnline && !deviceCapability.sdkOnDeviceProviderAvailable -> "Offline • Cached"
        lastSource == ActualInferenceSource.ON_DEVICE -> "On-Device Active"
        lastSource == ActualInferenceSource.IN_CLOUD -> "Cloud Fallback Active"
        deviceCapability.aiCorePackageInstalled && deviceCapability.sdkOnDeviceProviderAvailable -> "On-Device Ready"
        else -> "Hybrid Cloud Ready"
    }

    Surface(
        modifier = Modifier
            .width(248.dp)
            .fillMaxHeight(),
        color = NexusSidebarBg.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, NexusBorder.copy(alpha = 0.65f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    NexusStarLogo(size = 32.dp)
                    Text(
                        text = "NEXUS AI",
                        style = MaterialTheme.typography.titleLarge,
                        color = NexusTextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                NexusDestination.entries.forEach { dest ->
                    val selected = currentDestination == dest
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .then(
                                if (selected) {
                                    Modifier.background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                NexusViolet,
                                                Color(0xFF4F34B8),
                                                NexusSurfaceElevated
                                            )
                                        )
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { onSelect(dest) }
                            .minimumInteractiveComponentSize()
                            .testTag("sidebar_${dest.route}")
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                            contentDescription = dest.label,
                            tint = if (selected) NexusTextPrimary else NexusTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = dest.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) NexusTextPrimary else NexusTextSecondary
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenPrivacy),
                    shape = RoundedCornerShape(18.dp),
                    color = NexusSurface,
                    border = BorderStroke(1.dp, NexusBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Hybrid Inference",
                            style = MaterialTheme.typography.titleSmall,
                            color = NexusTextPrimary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusDot)
                            )
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusDot
                            )
                        }
                        SparklineCanvas(
                            values = listOf(0.22f, 0.30f, 0.25f, 0.45f, 0.40f, 0.65f, 0.88f),
                            lineColor = NexusCyan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                        )
                    }
                }

                Text(
                    text = "Your knowledge.\nYour intelligence. Your control.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexusTextMuted,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
    }
}
