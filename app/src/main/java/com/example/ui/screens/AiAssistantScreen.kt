package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ActualInferenceSource
import com.example.data.model.AiTaskType
import com.example.data.model.AssistantMessage
import com.example.data.model.HybridInferenceMode
import com.example.data.model.UiState
import com.example.data.model.VaultItem
import com.example.ui.components.AiActivityOrb
import com.example.ui.components.InferenceSourceBadge
import com.example.ui.components.NexusCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.NexusBorder
import com.example.ui.theme.NexusBorderBright
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
fun AiAssistantScreen(
    messages: List<AssistantMessage>,
    isBusy: Boolean,
    isOnline: Boolean,
    activeMode: HybridInferenceMode,
    vaultState: UiState<List<VaultItem>>,
    selectedContextIds: Set<String>,
    isCompact: Boolean,
    onSelectMode: (HybridInferenceMode) -> Unit,
    onToggleContextItem: (String) -> Unit,
    onSendPrompt: (String, AiTaskType, HybridInferenceMode?) -> Unit,
    onClearChat: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val vaultItems = (vaultState as? UiState.Success)?.data.orEmpty()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("ai_assistant_screen")
            .padding(
                horizontal = if (isCompact) 14.dp else 28.dp,
                vertical = if (isCompact) 12.dp else 20.dp
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + Mode Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hybrid AI Assistant",
                    style = MaterialTheme.typography.headlineMedium,
                    color = NexusTextPrimary
                )
                Text(
                    text = "Firebase AI Logic • Truthful execution source per response",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexusTextSecondary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(
                    text = if (isOnline) "Online" else "Offline",
                    dotColor = if (isOnline) NexusOnlineGreen else NexusWarning
                )
                if (messages.isNotEmpty()) {
                    IconButton(
                        onClick = onClearChat,
                        modifier = Modifier.testTag("clear_chat_button")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear conversation", tint = NexusTextSecondary)
                    }
                }
            }
        }

        // Hybrid Inference Mode Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HybridInferenceMode.entries.forEach { mode ->
                val isLocalPref = mode == HybridInferenceMode.PREFER_ON_DEVICE || mode == HybridInferenceMode.ONLY_ON_DEVICE
                FilterChip(
                    selected = activeMode == mode,
                    onClick = { onSelectMode(mode) },
                    modifier = Modifier.testTag("mode_chip_${mode.name.lowercase()}"),
                    leadingIcon = {
                        Icon(
                            imageVector = if (isLocalPref) Icons.Default.Memory else Icons.Default.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(mode.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (isLocalPref) NexusEmeraldLight.copy(alpha = 0.20f) else NexusCyan.copy(alpha = 0.20f),
                        selectedLabelColor = if (isLocalPref) NexusEmeraldLight else NexusCyan,
                        selectedLeadingIconColor = if (isLocalPref) NexusEmeraldLight else NexusCyan,
                    )
                )
            }
        }

        // Vault Context Attachment Strip
        if (vaultItems.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (selectedContextIds.isEmpty()) {
                        "Vault Context (tap items to focus Q&A, or auto-ground top notes):"
                    } else {
                        "Attached Vault Context (${selectedContextIds.size} selected):"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = NexusTextMuted
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    vaultItems.take(10).forEach { item ->
                        val selected = item.id in selectedContextIds
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleContextItem(item.id) },
                            label = {
                                Text(
                                    text = item.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NexusViolet.copy(alpha = 0.28f),
                                selectedLabelColor = NexusVioletLight,
                            )
                        )
                    }
                }
            }
        }

        // Messages Area
        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                NexusCard(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        AiActivityOrb(isBusy = false, lastSource = null, size = 86.dp)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Ask NEXUS AI anything across your Vault",
                            style = MaterialTheme.typography.titleLarge,
                            color = NexusTextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = activeMode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = NexusTextSecondary
                        )
                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PromptStarterChip("Synthesize my Vault notes") {
                                onSendPrompt(
                                    "Synthesize the key themes, insights, and connections across my Knowledge Vault items.",
                                    AiTaskType.VAULT_QA,
                                    HybridInferenceMode.PREFER_IN_CLOUD
                                )
                            }
                            PromptStarterChip("Generate Research Report") {
                                onSendPrompt(
                                    "Write a structured research report with executive summary, key findings, and next steps based on my vault.",
                                    AiTaskType.RESEARCH,
                                    HybridInferenceMode.PREFER_IN_CLOUD
                                )
                            }
                            PromptStarterChip("Private Quick Summary") {
                                onSendPrompt(
                                    "Give me a concise 3-bullet private summary of my most recent note.",
                                    AiTaskType.SUMMARIZE,
                                    HybridInferenceMode.PREFER_ON_DEVICE
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        AssistantMessageBubble(msg)
                    }
                }
            }
        }

        // Composer Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        text = if (isOnline) "Ask about your notes, research, or tasks..." else "Offline: on-device only if supported",
                        color = NexusTextMuted
                    )
                },
                maxLines = 4,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NexusViolet,
                    unfocusedBorderColor = NexusBorder,
                    focusedContainerColor = NexusSurfaceElevated,
                    unfocusedContainerColor = NexusSurfaceElevated,
                    focusedTextColor = NexusTextPrimary,
                    unfocusedTextColor = NexusTextPrimary,
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("assistant_prompt_input")
            )

            IconButton(
                onClick = {
                    val prompt = inputText.trim()
                    if (prompt.isNotEmpty()) {
                        inputText = ""
                        val task = if (selectedContextIds.size > 1) AiTaskType.VAULT_QA else AiTaskType.CHAT
                        onSendPrompt(prompt, task, activeMode)
                    }
                },
                enabled = !isBusy && inputText.isNotBlank(),
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (!isBusy && inputText.isNotBlank()) NexusViolet else NexusSurfaceElevated)
                    .testTag("assistant_send_button")
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = NexusTextPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send prompt",
                        tint = NexusTextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptStarterChip(
    label: String,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, color = NexusTextPrimary) },
        leadingIcon = {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NexusCyan, modifier = Modifier.size(16.dp))
        },
        colors = AssistChipDefaults.assistChipColors(containerColor = NexusSurfaceElevated),
        border = BorderStroke(1.dp, NexusBorderBright)
    )
}

@Composable
private fun AssistantMessageBubble(message: AssistantMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (message.fromUser) 0.84f else 0.96f),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (message.fromUser) 18.dp else 6.dp,
                bottomEnd = if (message.fromUser) 6.dp else 18.dp
            ),
            color = when {
                message.error -> NexusError.copy(alpha = 0.14f)
                message.fromUser -> NexusViolet.copy(alpha = 0.24f)
                else -> NexusSurface
            },
            border = BorderStroke(
                width = 1.dp,
                color = when {
                    message.error -> NexusError.copy(alpha = 0.45f)
                    message.fromUser -> NexusVioletLight.copy(alpha = 0.45f)
                    else -> NexusBorder
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!message.fromUser) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "NEXUS AI",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (message.error) NexusError else NexusVioletLight
                        )
                        if (message.streaming) {
                            Text(
                                text = "Streaming...",
                                style = MaterialTheme.typography.labelSmall,
                                color = NexusCyan
                            )
                        }
                    }

                    if (!message.error && message.actualSource != null && message.actualSource != ActualInferenceSource.UNKNOWN) {
                        InferenceSourceBadge(
                            source = message.actualSource,
                            modelName = message.modelName,
                            latencyMs = message.latencyMs
                        )
                    } else if (!message.error && message.requestedMode != null && message.streaming) {
                        Text(
                            text = "Requested: ${message.requestedMode.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NexusTextMuted
                        )
                    }
                }

                Text(
                    text = message.text.ifBlank { if (message.streaming) "Thinking..." else "" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (message.error) NexusError else NexusTextPrimary
                )
            }
        }
    }
}
