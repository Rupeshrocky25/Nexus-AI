package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ActualInferenceSource
import com.example.data.model.AiAuditLog
import com.example.data.model.OnDeviceCapabilityStatus
import com.example.data.model.UiState
import com.example.ui.components.InferenceSourceBadge
import com.example.ui.components.NexusCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.theme.NexusBorder
import com.example.ui.theme.NexusCyan
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
fun PrivacyCenterScreen(
    deviceCapability: OnDeviceCapabilityStatus,
    isOnline: Boolean,
    aiLogsState: UiState<List<AiAuditLog>>,
    totalVaultItems: Int,
    isCompact: Boolean,
    onClearAiHistory: () -> Unit,
    onDeleteAllVaultData: () -> Unit,
) {
    val logs = (aiLogsState as? UiState.Success)?.data.orEmpty()
    val localCount = remember(logs) { logs.count { it.resolvedActualSource() == ActualInferenceSource.ON_DEVICE } }
    val cloudCount = remember(logs) { logs.count { it.resolvedActualSource() == ActualInferenceSource.IN_CLOUD } }
    val totalMeasured = (localCount + cloudCount).coerceAtLeast(1)
    val localFraction = localCount.toFloat() / totalMeasured.toFloat()

    var confirmClearLogs by remember { mutableStateOf(false) }
    var confirmDeleteVault by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("privacy_center_screen"),
        contentPadding = PaddingValues(
            horizontal = if (isCompact) 16.dp else 28.dp,
            vertical = if (isCompact) 14.dp else 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Privacy Center",
                        style = MaterialTheme.typography.headlineLarge,
                        color = NexusTextPrimary
                    )
                    Text(
                        text = "Truthful Firebase AI Logic execution telemetry & data control",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexusTextSecondary
                    )
                }
                StatusPill(
                    text = "App Check Active",
                    dotColor = NexusOnlineGreen
                )
            }
        }

        // Device Hybrid Runtime Diagnostics Card
        item {
            NexusCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = NexusCyan.copy(alpha = 0.35f)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = NexusCyan)
                        Text(
                            text = deviceCapability.statusHeadline,
                            style = MaterialTheme.typography.titleMedium,
                            color = NexusTextPrimary
                        )
                    }
                    Text(
                        text = deviceCapability.statusDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexusTextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DiagnosticPill(
                            label = "Device",
                            value = "${deviceCapability.deviceModel} (API ${deviceCapability.androidApiLevel})",
                            modifier = Modifier.weight(1f)
                        )
                        DiagnosticPill(
                            label = "AICore Package",
                            value = if (deviceCapability.aiCorePackageInstalled) "Installed" else "Not Installed",
                            accent = if (deviceCapability.aiCorePackageInstalled) NexusOnlineGreen else NexusWarning,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Execution Distribution (Local vs Cloud)
        item {
            NexusCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Actual Inference Execution Split",
                        style = MaterialTheme.typography.titleMedium,
                        color = NexusTextPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("On-Device (Gemini Nano)", style = MaterialTheme.typography.labelLarge, color = NexusEmeraldLight)
                            Text("$localCount executions • 0 bytes transmitted", style = MaterialTheme.typography.bodySmall, color = NexusTextSecondary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Cloud (Firebase AI Logic)", style = MaterialTheme.typography.labelLarge, color = NexusCyan)
                            Text("$cloudCount executions • TLS + App Check", style = MaterialTheme.typography.bodySmall, color = NexusTextSecondary)
                        }
                    }

                    // Visual split bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(NexusSurfaceElevated)
                    ) {
                        if (localCount > 0) {
                            Box(
                                modifier = Modifier
                                    .weight(localFraction.coerceAtLeast(0.05f))
                                    .fillMaxSize()
                                    .background(NexusEmeraldLight)
                            )
                        }
                        if (cloudCount > 0) {
                            Box(
                                modifier = Modifier
                                    .weight((1f - localFraction).coerceAtLeast(0.05f))
                                    .fillMaxSize()
                                    .background(NexusCyan)
                            )
                        }
                    }
                }
            }
        }

        // Transparent Data Transmission Disclosure
        item {
            NexusCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "When Is Your Information Transmitted?",
                        style = MaterialTheme.typography.titleMedium,
                        color = NexusTextPrimary
                    )
                    DisclosureRow(
                        icon = Icons.Default.Memory,
                        tint = NexusEmeraldLight,
                        title = "On-Device Execution (InferenceSource.ON_DEVICE)",
                        body = "When Gemini Nano via Android AICore executes a PREFER_ON_DEVICE or ONLY_ON_DEVICE prompt, note content never leaves your device hardware."
                    )
                    DisclosureRow(
                        icon = Icons.Default.Cloud,
                        tint = NexusCyan,
                        title = "Cloud Execution (InferenceSource.IN_CLOUD)",
                        body = "For complex multi-document synthesis, research reports, or when this device lacks AICore / Gemini Nano, prompts are sent over TLS with Firebase App Check attestation to Firebase AI Logic."
                    )
                    DisclosureRow(
                        icon = Icons.Default.VerifiedUser,
                        tint = NexusVioletLight,
                        title = "Zero-Trust User Isolation & Quota Scope",
                        body = "All notes, analyses, and audit logs are isolated under /users/{yourUid}/ in Cloud Firestore. Google Sign-In secures your private data (it does not supply personal Gemini API quota); cloud Gemini calls use Firebase AI Logic with App Check attestation and never request personal API keys."
                    )
                }
            }
        }

        // Data Deletion Controls
        item {
            NexusCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = NexusError.copy(alpha = 0.35f)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Data Deletion Controls",
                        style = MaterialTheme.typography.titleMedium,
                        color = NexusTextPrimary
                    )
                    Text(
                        text = "You own your knowledge and audit history. Permanently erase logs or wipe your Knowledge Vault at any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NexusTextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { confirmClearLogs = true },
                            enabled = logs.isNotEmpty(),
                            border = BorderStroke(1.dp, NexusWarning.copy(alpha = 0.55f)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("privacy_clear_logs_button")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = NexusWarning, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear AI Logs (${logs.size})", color = NexusWarning, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = { confirmDeleteVault = true },
                            enabled = totalVaultItems > 0,
                            border = BorderStroke(1.dp, NexusError.copy(alpha = 0.55f)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("privacy_delete_vault_button")
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, tint = NexusError, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Wipe Vault ($totalVaultItems)", color = NexusError, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // Processing History Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI Processing History (${logs.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = NexusTextPrimary
                )
                Text(
                    text = if (isOnline) "Live Audit Trail" else "Cached Offline",
                    style = MaterialTheme.typography.labelSmall,
                    color = NexusTextMuted
                )
            }
        }

        if (logs.isEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "No AI inference runs recorded yet. Summarize a note or chat with the Hybrid AI Assistant to inspect real execution-source telemetry here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexusTextSecondary,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }
        } else {
            items(logs, key = { it.id }) { log ->
                AuditLogCard(log)
            }
        }
    }

    if (confirmClearLogs) {
        AlertDialog(
            onDismissRequest = { confirmClearLogs = false },
            containerColor = NexusSurface,
            titleContentColor = NexusTextPrimary,
            textContentColor = NexusTextSecondary,
            title = { Text("Clear AI Processing History?") },
            text = { Text("This permanently deletes all ${logs.size} AI execution audit records from Firestore.") },
            confirmButton = {
                Button(
                    onClick = { confirmClearLogs = false; onClearAiHistory() },
                    colors = ButtonDefaults.buttonColors(containerColor = NexusWarning)
                ) {
                    Text("Clear Logs", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearLogs = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmDeleteVault) {
        AlertDialog(
            onDismissRequest = { confirmDeleteVault = false },
            containerColor = NexusSurface,
            titleContentColor = NexusTextPrimary,
            textContentColor = NexusTextSecondary,
            title = { Text("Delete All Knowledge Vault Data?") },
            text = { Text("This permanently deletes all $totalVaultItems notes, imported documents, and saved links from your account. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { confirmDeleteVault = false; onDeleteAllVaultData() },
                    colors = ButtonDefaults.buttonColors(containerColor = NexusError)
                ) {
                    Text("Delete Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteVault = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DiagnosticPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = NexusTextPrimary,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = NexusSurfaceElevated,
        border = BorderStroke(1.dp, NexusBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = NexusTextMuted)
            Spacer(modifier = Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.titleSmall, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DisclosureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    body: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = NexusTextPrimary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = NexusTextSecondary)
        }
    }
}

@Composable
private fun AuditLogCard(log: AiAuditLog) {
    NexusCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = log.resolvedTask().displayLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = NexusTextPrimary
                )
                Text(
                    text = formatRelativeTimestamp(log.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = NexusTextMuted
                )
            }
            Text(
                text = log.promptPreview,
                style = MaterialTheme.typography.bodySmall,
                color = NexusTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                InferenceSourceBadge(
                    source = log.resolvedActualSource(),
                    modelName = log.modelName,
                    latencyMs = log.latencyMs
                )
                Text(
                    text = if (log.transmittedToCloud) "Transmitted to Cloud" else "Local Only",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (log.transmittedToCloud) NexusCyan else NexusEmeraldLight
                )
            }
        }
    }
}
