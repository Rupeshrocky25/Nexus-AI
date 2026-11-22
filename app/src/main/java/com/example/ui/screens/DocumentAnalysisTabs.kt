package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.model.AssistantMessage
import com.example.data.model.DocumentAnalysis
import com.example.data.model.DocumentSection
import com.example.data.model.ExtractionStatus
import com.example.data.model.HybridInferenceMode
import com.example.data.model.VaultItem
import com.example.ui.components.InferenceSourceBadge
import com.example.ui.components.NexusCard
import com.example.ui.theme.NexusBorder
import com.example.ui.theme.NexusBorderBright
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
fun AnalysisOverviewTab(
    analysis: DocumentAnalysis,
    horizontalPadding: Dp,
    onJumpToAsk: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("analysis_overview_list"),
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Coverage & Honest Disclosure Banner
        if (analysis.extractionDisclosure.isNotBlank()) {
            val isPartial = analysis.resolvedExtractionStatus() == ExtractionStatus.PARTIAL
            item {
                NexusCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = if (isPartial) NexusWarning.copy(alpha = 0.55f) else NexusCyan.copy(alpha = 0.35f)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = if (isPartial) Icons.Default.WarningAmber else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (isPartial) NexusWarning else NexusCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (isPartial) "Partial Extraction Disclosure" else "Extraction Provenance",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isPartial) NexusWarning else NexusCyan
                            )
                            Text(
                                text = analysis.extractionDisclosure,
                                style = MaterialTheme.typography.bodySmall,
                                color = NexusTextSecondary
                            )
                        }
                    }
                }
            }
        }

        // Executive Summary
        item {
            NexusCard(modifier = Modifier.fillMaxWidth(), borderColor = NexusViolet.copy(alpha = 0.45f)) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NexusVioletLight, modifier = Modifier.size(20.dp))
                        Text("Executive Summary", style = MaterialTheme.typography.titleMedium, color = NexusTextPrimary)
                    }
                    Text(
                        text = analysis.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NexusTextPrimary
                    )
                }
            }
        }

        // Detailed Explanation of Actual Content (Phase 4)
        if (analysis.detailedExplanation.isNotBlank()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth(), borderColor = NexusCyan.copy(alpha = 0.42f)) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, tint = NexusCyan, modifier = Modifier.size(20.dp))
                            Text("Detailed Content Explanation", style = MaterialTheme.typography.titleMedium, color = NexusTextPrimary)
                        }
                        Text(
                            text = analysis.detailedExplanation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexusTextSecondary
                        )
                    }
                }
            }
        }

        // Important Conclusions (Phase 4)
        if (analysis.conclusions.isNotEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth(), borderColor = NexusEmerald.copy(alpha = 0.40f)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NexusEmeraldLight, modifier = Modifier.size(20.dp))
                            Text("Important Conclusions", style = MaterialTheme.typography.titleMedium, color = NexusTextPrimary)
                        }
                        analysis.conclusions.forEach { conclusion ->
                            Text(
                                text = "• $conclusion",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NexusTextPrimary
                            )
                        }
                    }
                }
            }
        }

        // Main Topics & Concepts
        if (analysis.mainTopics.isNotEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Main Topics & Concepts", style = MaterialTheme.typography.titleSmall, color = NexusTextPrimary)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            analysis.mainTopics.forEach { topic ->
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = NexusViolet.copy(alpha = 0.16f),
                                    border = BorderStroke(1.dp, NexusVioletLight.copy(alpha = 0.35f))
                                ) {
                                    Text(
                                        text = topic,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NexusVioletLight,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Verified Source Facts (strictly separated from AI interpretations)
        if (analysis.sourceFacts.isNotEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth(), borderColor = NexusEmerald.copy(alpha = 0.42f)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Verified, contentDescription = null, tint = NexusEmeraldLight, modifier = Modifier.size(20.dp))
                            Column {
                                Text("Verified Source Facts & Findings", style = MaterialTheme.typography.titleMedium, color = NexusTextPrimary)
                                Text(
                                    text = "Direct facts grounded in extracted content",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NexusEmeraldLight
                                )
                            }
                        }
                        analysis.sourceFacts.forEachIndexed { idx, fact ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "• ${fact.fact}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NexusTextPrimary
                                )
                                if (fact.sourceRef.isNotBlank()) {
                                    SourceRefChip(fact.sourceRef)
                                }
                            }
                            if (idx < analysis.sourceFacts.lastIndex) {
                                HorizontalDivider(color = NexusBorder.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }

        // AI Interpretations (Explicitly labeled as separate from source facts)
        if (analysis.aiInterpretations.isNotEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth(), borderColor = NexusVioletLight.copy(alpha = 0.38f)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = NexusVioletLight, modifier = Modifier.size(20.dp))
                            Column {
                                Text("AI Interpretations & Synthesis", style = MaterialTheme.typography.titleMedium, color = NexusTextPrimary)
                                Text(
                                    text = "AI synthesis — distinct from direct source facts above",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NexusVioletLight
                                )
                            }
                        }
                        analysis.aiInterpretations.forEach { interp ->
                            Text(
                                text = "• $interp",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NexusTextSecondary
                            )
                        }
                    }
                }
            }
        }

        // Action Items
        if (analysis.actionItems.isNotEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.TaskAlt, contentDescription = null, tint = NexusCyan, modifier = Modifier.size(20.dp))
                            Text("Action Items", style = MaterialTheme.typography.titleMedium, color = NexusTextPrimary)
                        }
                        analysis.actionItems.forEach { item ->
                            val priorityColor = when (item.priority.lowercase()) {
                                "high" -> NexusError
                                "low" -> NexusOnlineGreen
                                else -> NexusWarning
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = priorityColor.copy(alpha = 0.16f),
                                    border = BorderStroke(1.dp, priorityColor.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = item.priority,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = priorityColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(item.action, style = MaterialTheme.typography.bodyMedium, color = NexusTextPrimary)
                                    if (item.sourceRef.isNotBlank()) SourceRefChip(item.sourceRef)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onJumpToAsk,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, NexusCyan.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.QuestionAnswer, contentDescription = null, tint = NexusCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ask Follow-Up Questions About This Document", color = NexusCyan)
            }
        }
    }
}

@Composable
fun AnalysisDetailedTab(
    analysis: DocumentAnalysis,
    horizontalPadding: Dp,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("analysis_detailed_list"),
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Definitions & Explanations
        if (analysis.definitions.isNotEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, tint = NexusVioletLight, modifier = Modifier.size(20.dp))
                            Text("Definitions & Explanations", style = MaterialTheme.typography.titleMedium, color = NexusTextPrimary)
                        }
                        analysis.definitions.forEachIndexed { idx, def ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(def.term, style = MaterialTheme.typography.titleSmall, color = NexusCyan)
                                Text(def.explanation, style = MaterialTheme.typography.bodyMedium, color = NexusTextPrimary)
                                if (def.sourceRef.isNotBlank()) SourceRefChip(def.sourceRef)
                            }
                            if (idx < analysis.definitions.lastIndex) HorizontalDivider(color = NexusBorder.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // Tables & Data Insights
        if (analysis.tableInsights.isNotEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth(), borderColor = NexusCyan.copy(alpha = 0.4f)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.TableChart, contentDescription = null, tint = NexusCyan, modifier = Modifier.size(20.dp))
                            Text("Tables & Data Insights", style = MaterialTheme.typography.titleMedium, color = NexusTextPrimary)
                        }
                        analysis.tableInsights.forEachIndexed { idx, ins ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(ins.title, style = MaterialTheme.typography.titleSmall, color = NexusTextPrimary)
                                Text(ins.finding, style = MaterialTheme.typography.bodyMedium, color = NexusTextSecondary)
                                if (ins.sourceRef.isNotBlank()) SourceRefChip(ins.sourceRef)
                            }
                            if (idx < analysis.tableInsights.lastIndex) HorizontalDivider(color = NexusBorder.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // Entities, Dates & Figures
        if (analysis.entities.isNotEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Entities, Dates & Figures", style = MaterialTheme.typography.titleMedium, color = NexusTextPrimary)
                        analysis.entities.forEachIndexed { idx, ent ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(ent.name, style = MaterialTheme.typography.titleSmall, color = NexusTextPrimary)
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = NexusSurfaceElevated,
                                        border = BorderStroke(1.dp, NexusBorder)
                                    ) {
                                        Text(
                                            text = ent.type,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NexusVioletLight,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                if (ent.context.isNotBlank()) {
                                    Text(ent.context, style = MaterialTheme.typography.bodySmall, color = NexusTextSecondary)
                                }
                                if (ent.sourceRef.isNotBlank()) SourceRefChip(ent.sourceRef)
                            }
                            if (idx < analysis.entities.lastIndex) HorizontalDivider(color = NexusBorder.copy(alpha = 0.45f))
                        }
                    }
                }
            }
        }

        // Grounded Q&A
        if (analysis.groundedQa.isNotEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.QuestionAnswer, contentDescription = null, tint = NexusEmeraldLight, modifier = Modifier.size(20.dp))
                            Text("Grounded Q&A", style = MaterialTheme.typography.titleMedium, color = NexusTextPrimary)
                        }
                        analysis.groundedQa.forEachIndexed { idx, qa ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Q: ${qa.question}", style = MaterialTheme.typography.titleSmall, color = NexusTextPrimary)
                                Text("A: ${qa.answer}", style = MaterialTheme.typography.bodyMedium, color = NexusTextSecondary)
                                if (qa.sourceRef.isNotBlank()) SourceRefChip(qa.sourceRef)
                            }
                            if (idx < analysis.groundedQa.lastIndex) HorizontalDivider(color = NexusBorder.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        if (analysis.definitions.isEmpty() && analysis.tableInsights.isEmpty() && analysis.entities.isEmpty() && analysis.groundedQa.isEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "No additional definitions, tables, entities, or Q&A items were detected in this document.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexusTextSecondary,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AnalysisReferencesTab(
    analysis: DocumentAnalysis,
    horizontalPadding: Dp,
) {
    val grouped = remember(analysis) {
        val entries = mutableListOf<Pair<String, String>>()
        analysis.sourceFacts.forEach {
            entries.add((it.sourceRef.ifBlank { "General Source" }) to "Fact: ${it.fact}")
        }
        analysis.definitions.forEach {
            entries.add((it.sourceRef.ifBlank { "General Source" }) to "Definition (${it.term}): ${it.explanation}")
        }
        analysis.entities.forEach {
            entries.add((it.sourceRef.ifBlank { "General Source" }) to "${it.type} — ${it.name}: ${it.context}")
        }
        analysis.tableInsights.forEach {
            entries.add((it.sourceRef.ifBlank { "General Source" }) to "Data (${it.title}): ${it.finding}")
        }
        analysis.actionItems.forEach {
            entries.add((it.sourceRef.ifBlank { "General Source" }) to "Action [${it.priority}]: ${it.action}")
        }
        analysis.groundedQa.forEach {
            entries.add((it.sourceRef.ifBlank { "General Source" }) to "Q&A: ${it.question} → ${it.answer}")
        }
        entries.groupBy({ it.first }, { it.second }).toList()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("analysis_references_list"),
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (grouped.isEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "No page or section references were recorded in this analysis.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexusTextSecondary,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        } else {
            items(grouped, key = { it.first }) { (refLabel, claims) ->
                NexusCard(modifier = Modifier.fillMaxWidth(), borderColor = NexusBorderBright.copy(alpha = 0.6f)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Bookmark, contentDescription = null, tint = NexusCyan, modifier = Modifier.size(18.dp))
                            Text(refLabel, style = MaterialTheme.typography.titleSmall, color = NexusCyan)
                        }
                        claims.forEach { claim ->
                            Text("• $claim", style = MaterialTheme.typography.bodySmall, color = NexusTextPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExtractedContentTab(
    item: VaultItem,
    sections: List<DocumentSection>,
    disclosure: String,
    horizontalPadding: Dp,
    onReanalyze: () -> Unit,
) {
    var filterQuery by remember(item.id) { mutableStateOf("") }
    val visibleSections = remember(sections, filterQuery) {
        if (filterQuery.isBlank()) sections
        else sections.filter {
            it.referenceLabel.contains(filterQuery, ignoreCase = true) ||
                it.content.contains(filterQuery, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("analysis_extracted_content_list"),
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                placeholder = { Text("Search extracted pages, tables, or OCR text...", color = NexusTextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = NexusTextMuted) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NexusCyan,
                    unfocusedBorderColor = NexusBorder,
                    focusedContainerColor = NexusSurfaceElevated,
                    unfocusedContainerColor = NexusSurfaceElevated,
                    focusedTextColor = NexusTextPrimary,
                    unfocusedTextColor = NexusTextPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("extracted_search_input")
            )
        }

        if (disclosure.isNotBlank()) {
            item {
                Text(
                    text = disclosure,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexusTextSecondary
                )
            }
        }

        if (visibleSections.isEmpty()) {
            item {
                NexusCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (sections.isEmpty()) {
                                "No extracted text is available yet for this document."
                            } else {
                                "No extracted sections match \"$filterQuery\"."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexusTextSecondary
                        )
                        if (sections.isEmpty()) {
                            OutlinedButton(
                                onClick = onReanalyze,
                                border = BorderStroke(1.dp, NexusCyan.copy(alpha = 0.55f))
                            ) {
                                Text("Run Extraction / OCR Now", color = NexusCyan)
                            }
                        }
                    }
                }
            }
        } else {
            items(visibleSections, key = { "${it.index}_${it.referenceLabel}" }) { sec ->
                NexusCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SourceRefChip(sec.referenceLabel)
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

@Composable
fun AskDocumentTab(
    item: VaultItem,
    analysis: DocumentAnalysis?,
    sectionsCount: Int,
    messages: List<AssistantMessage>,
    isBusy: Boolean,
    isOnline: Boolean,
    selectedMode: HybridInferenceMode,
    horizontalPadding: Dp,
    onModeChange: (HybridInferenceMode) -> Unit,
    onSendQuestion: (String) -> Unit,
    onClearChat: () -> Unit,
) {
    var input by remember(item.id) { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val starterQuestions = remember(analysis) {
        val fromQa = analysis?.groundedQa?.take(2)?.map { it.question }.orEmpty()
        (fromQa + listOf(
            "What are the most important figures, dates, or tables in this document?",
            "List every concrete action item or open risk with exact page/section references."
        )).distinct().take(3)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Mode Selector & Scope Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RAG Scope: $sectionsCount extracted section(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = NexusCyan
                )
                if (messages.isNotEmpty()) {
                    IconButton(onClick = onClearChat) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear document Q&A", tint = NexusTextSecondary)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HybridInferenceMode.entries.forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { onModeChange(mode) },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NexusCyan.copy(alpha = 0.22f),
                            selectedLabelColor = NexusCyan,
                        )
                    )
                }
            }
        }

        // Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("ask_document_messages"),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    NexusCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Ask anything about \"${item.title}\"",
                                style = MaterialTheme.typography.titleSmall,
                                color = NexusTextPrimary
                            )
                            Text(
                                text = "Questions search across ALL extracted chunks of this document and cite exact page, section, or sheet references.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NexusTextSecondary
                            )
                            starterQuestions.forEach { prompt ->
                                AssistChip(
                                    onClick = { if (!isBusy && sectionsCount > 0) onSendQuestion(prompt) },
                                    label = { Text(prompt, style = MaterialTheme.typography.bodySmall, color = NexusTextPrimary) },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = NexusSurfaceElevated),
                                    border = BorderStroke(1.dp, NexusBorderBright.copy(alpha = 0.6f))
                                )
                            }
                        }
                    }
                }
            } else {
                items(messages, key = { it.id }) { msg ->
                    DocQaBubble(message = msg)
                }
            }
        }

        // Input Composer
        Surface(
            color = NexusSurfaceElevated,
            border = BorderStroke(1.dp, NexusBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            text = if (sectionsCount == 0) "Extract content first to ask questions..."
                            else if (!isOnline && selectedMode != HybridInferenceMode.ONLY_ON_DEVICE) "Offline — reconnect for cloud Q&A"
                            else "Ask a grounded question about this document...",
                            color = NexusTextMuted
                        )
                    },
                    enabled = !isBusy && sectionsCount > 0,
                    maxLines = 3,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NexusCyan,
                        unfocusedBorderColor = NexusBorder,
                        focusedTextColor = NexusTextPrimary,
                        unfocusedTextColor = NexusTextPrimary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ask_document_input")
                )
                IconButton(
                    onClick = {
                        val q = input.trim()
                        if (q.isNotEmpty()) {
                            input = ""
                            onSendQuestion(q)
                        }
                    },
                    enabled = !isBusy && sectionsCount > 0 && input.isNotBlank(),
                    modifier = Modifier.testTag("ask_document_send_button")
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = NexusCyan)
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send document question",
                            tint = if (input.isNotBlank() && sectionsCount > 0) NexusCyan else NexusTextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocQaBubble(message: AssistantMessage) {
    val bg = when {
        message.fromUser -> NexusViolet.copy(alpha = 0.22f)
        message.error -> NexusError.copy(alpha = 0.14f)
        else -> NexusSurfaceElevated
    }
    val border = when {
        message.fromUser -> NexusVioletLight.copy(alpha = 0.45f)
        message.error -> NexusError.copy(alpha = 0.45f)
        else -> NexusBorder
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = bg,
            border = BorderStroke(1.dp, border),
            modifier = Modifier.fillMaxWidth(if (message.fromUser) 0.86f else 0.96f)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!message.fromUser && message.actualSource != null) {
                    InferenceSourceBadge(
                        source = message.actualSource,
                        modelName = message.modelName.orEmpty(),
                        latencyMs = message.latencyMs
                    )
                }
                Text(
                    text = message.text.ifBlank { if (message.streaming) "Searching extracted chunks..." else "" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.error) NexusError else NexusTextPrimary
                )
            }
        }
    }
}

@Composable
private fun SourceRefChip(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = NexusCyan.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, NexusCyan.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = NexusCyan,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = NexusCyan
            )
        }
    }
}
