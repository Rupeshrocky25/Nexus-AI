package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.HybridInferenceMode
import com.example.ui.components.NexusCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.NexusBorder
import com.example.ui.theme.NexusCyan
import com.example.ui.theme.NexusEmeraldLight
import com.example.ui.theme.NexusError
import com.example.ui.theme.NexusOnlineGreen
import com.example.ui.theme.NexusTextMuted
import com.example.ui.theme.NexusTextPrimary
import com.example.ui.theme.NexusTextSecondary
import com.example.ui.theme.NexusViolet
import com.example.ui.theme.NexusVioletLight
import com.example.ui.theme.NexusWarning

@Composable
fun SettingsScreen(
    userDisplayName: String,
    userEmail: String?,
    userPhotoUrl: String?,
    userId: String,
    activeMode: HybridInferenceMode,
    isOnline: Boolean,
    isCompact: Boolean,
    onUpdateDefaultMode: (HybridInferenceMode) -> Unit,
    onSignOut: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        contentPadding = PaddingValues(
            horizontal = if (isCompact) 16.dp else 28.dp,
            vertical = if (isCompact) 14.dp else 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineLarge,
                color = NexusTextPrimary
            )
            Text(
                text = "Account, default hybrid routing, and offline synchronization",
                style = MaterialTheme.typography.bodyMedium,
                color = NexusTextSecondary
            )
        }

        // User Profile Card
        item {
            NexusCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(NexusViolet.copy(alpha = 0.25f))
                            .border(1.5.dp, NexusVioletLight, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!userPhotoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = userPhotoUrl,
                                contentDescription = "User profile photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = userDisplayName.take(1).uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                color = NexusTextPrimary
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(userDisplayName, style = MaterialTheme.typography.titleMedium, color = NexusTextPrimary)
                        if (!userEmail.isNullOrBlank()) {
                            Text(userEmail, style = MaterialTheme.typography.bodySmall, color = NexusTextSecondary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "UID: ${userId.take(12)}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = NexusTextMuted
                        )
                    }

                    StatusPill(
                        text = if (isOnline) "Online" else "Offline",
                        dotColor = if (isOnline) NexusOnlineGreen else NexusWarning
                    )
                }
            }
        }

        // Default Hybrid Inference Mode
        item {
            NexusCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Default Hybrid Inference Routing",
                        style = MaterialTheme.typography.titleMedium,
                        color = NexusTextPrimary
                    )
                    Text(
                        text = "Saved to your Firestore profile and applied across NEXUS AI Assistant sessions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NexusTextSecondary
                    )

                    HybridInferenceMode.entries.forEach { mode ->
                        val selected = activeMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) NexusViolet.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) NexusVioletLight.copy(alpha = 0.55f) else NexusBorder,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onUpdateDefaultMode(mode) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = mode.label,
                                tint = if (selected) NexusVioletLight else NexusTextMuted
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mode.label, style = MaterialTheme.typography.titleSmall, color = NexusTextPrimary)
                                Text(mode.description, style = MaterialTheme.typography.bodySmall, color = NexusTextSecondary)
                            }
                        }
                    }
                }
            }
        }

        // Security & Architecture Info
        item {
            NexusCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    InfoRow(
                        icon = Icons.Default.CloudDone,
                        tint = NexusEmeraldLight,
                        title = "Offline Disk Cache Enabled",
                        subtitle = "Cloud Firestore persistent cache keeps your notes readable and editable offline, syncing automatically once connectivity returns."
                    )
                    InfoRow(
                        icon = Icons.Default.Security,
                        tint = NexusCyan,
                        title = "Firebase App Check & Zero-Trust Rules",
                        subtitle = "AI Logic requests require valid App Check attestation; Firestore isolates all records strictly under your Google UID."
                    )
                    InfoRow(
                        icon = Icons.Default.CloudDone,
                        tint = NexusVioletLight,
                        title = "Gemini AI Quota & Account Attribution",
                        subtitle = "Google Sign-In isolates your private Knowledge Vault data; it does not grant personal per-user Gemini API quota. Cloud Gemini analysis runs through the app's configured Firebase AI Logic project provider — no personal API keys are ever requested."
                    )
                    InfoRow(
                        icon = Icons.Default.Gavel,
                        tint = NexusVioletLight,
                        title = "Open Source — Apache 2.0 License",
                        subtitle = "Built for Firebase #BuildHybridAI Hackathon • NEXUS AI Android Client."
                    )
                }
            }
        }

        // Sign Out Button
        item {
            Button(
                onClick = onSignOut,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NexusError.copy(alpha = 0.16f),
                    contentColor = NexusError
                ),
                border = BorderStroke(1.dp, NexusError.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("sign_out_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = NexusTextPrimary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NexusTextSecondary)
        }
    }
}
