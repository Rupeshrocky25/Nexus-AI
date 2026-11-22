package com.example.data.extraction

import com.example.data.model.DocumentAnalysis
import com.example.data.model.DocumentChunk
import com.example.data.model.DocumentSection
import com.example.data.model.VaultItem

/**
 * Large-document chunker, RAG retrieval scorer, and Markdown report exporter.
 *
 * Preserves exact page/section/sheet references across arbitrarily large documents
 * so neither analysis nor follow-up Q&A silently truncates content.
 */
object DocumentChunker {

    // 6,000 chars per chunk keeps streaming Gemini calls fast, bounded, and resilient against timeouts / 413s
    const val DEFAULT_MAX_CHUNK_CHARS = 6_000

    private val STOP_WORDS = setOf(
        "the", "and", "for", "that", "with", "this", "from", "are", "was", "were",
        "have", "has", "had", "not", "but", "what", "which", "who", "when", "where",
        "why", "how", "about", "into", "their", "there", "these", "those", "your",
        "does", "did", "can", "could", "would", "should", "will", "just", "than",
        "then", "them", "they", "been", "being", "also", "more", "most", "some",
    )

    /**
     * Groups extracted sections into bounded chunks while keeping `[ReferenceLabel]` headers
     * intact. Very large individual sections are split cleanly on line boundaries with
     * `(Part k)` markers so nothing is silently dropped.
     */
    fun chunkSections(
        sections: List<DocumentSection>,
        maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS,
    ): List<DocumentChunk> {
        val normalized = mutableListOf<DocumentSection>()
        for (sec in sections) {
            if (sec.content.isBlank()) continue
            if (sec.content.length <= maxChunkChars) {
                normalized.add(sec)
            } else {
                val parts = splitLargeText(sec.content, maxChunkChars - 120)
                parts.forEachIndexed { idx, partText ->
                    normalized.add(
                        DocumentSection(
                            index = sec.index,
                            referenceLabel = "${sec.referenceLabel} (Part ${idx + 1}/${parts.size})",
                            content = partText,
                        )
                    )
                }
            }
        }

        if (normalized.isEmpty()) return emptyList()

        val rawChunks = mutableListOf<Pair<List<String>, String>>()
        val currentRefs = mutableListOf<String>()
        val buffer = StringBuilder()

        for (sec in normalized) {
            val formatted = "[${sec.referenceLabel}]\n${sec.content}"
            if (buffer.isNotEmpty() && buffer.length + formatted.length + 2 > maxChunkChars) {
                rawChunks.add(currentRefs.toList() to buffer.toString().trim())
                currentRefs.clear()
                buffer.setLength(0)
            }
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(formatted)
            currentRefs.add(sec.referenceLabel)
        }
        if (buffer.isNotEmpty()) {
            rawChunks.add(currentRefs.toList() to buffer.toString().trim())
        }

        val total = rawChunks.size
        return rawChunks.mapIndexed { idx, (refs, text) ->
            val range = when {
                refs.isEmpty() -> "Chunk ${idx + 1}"
                refs.size == 1 -> refs.first()
                else -> "${refs.first()} – ${refs.last()}"
            }
            DocumentChunk(
                chunkIndex = idx + 1,
                totalChunks = total,
                referenceRange = range,
                text = text,
            )
        }
    }

    /**
     * Automatically splits an oversized chunk into smaller sub-chunks when the model reports
     * HTTP 413 / payload-too-large or token-limit errors, preserving provenance labels.
     */
    fun splitChunkForPayloadLimit(chunk: DocumentChunk, targetMaxChars: Int = 2_800): List<DocumentChunk> {
        val parts = splitLargeText(chunk.text, targetMaxChars.coerceAtLeast(1_000))
        if (parts.size <= 1) return listOf(chunk)
        return parts.mapIndexed { idx, partText ->
            DocumentChunk(
                chunkIndex = idx + 1,
                totalChunks = parts.size,
                referenceRange = "${chunk.referenceRange} [Sub-part ${idx + 1}/${parts.size}]",
                text = partText,
            )
        }
    }

    /**
     * Reconstructs sections from already-formatted `[Page N]` / `[Section X]` text
     * (useful when analyzing existing notes or Firestore-synced extracted text).
     */
    fun parseSectionsFromFormattedText(text: String): List<DocumentSection> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val markerRegex = Regex("^\\[([^\\]]{1,90})\\]\\s*$")
        val lines = trimmed.lines()
        val sections = mutableListOf<DocumentSection>()
        var currentLabel: String? = null
        val buf = StringBuilder()
        var index = 1

        for (line in lines) {
            val match = markerRegex.matchEntire(line.trim())
            if (match != null) {
                if (buf.isNotBlank()) {
                    sections.add(DocumentSection(index++, currentLabel ?: "Section $index", buf.toString().trim()))
                    buf.setLength(0)
                }
                currentLabel = match.groupValues[1].trim()
            } else {
                buf.appendLine(line)
            }
        }
        if (buf.isNotBlank()) {
            sections.add(DocumentSection(index, currentLabel ?: "Section 1", buf.toString().trim()))
        }
        return sections.ifEmpty {
            listOf(DocumentSection(1, "Document Content", trimmed))
        }
    }

    /**
     * Ranks all chunks of a document against a user question using token overlap, phrase bonus,
     * and reference matching so follow-up Q&A retrieves the exact right pages/sections even in
     * 100-page documents.
     */
    fun retrieveRelevantChunks(
        query: String,
        chunks: List<DocumentChunk>,
        topK: Int = 4,
    ): List<DocumentChunk> {
        if (chunks.size <= topK) return chunks

        val queryLower = query.lowercase()
        val queryTokens = tokenize(queryLower)
        if (queryTokens.isEmpty()) return chunks.take(topK)

        val scored = chunks.map { chunk ->
            val textLower = chunk.text.lowercase()
            val refLower = chunk.referenceRange.lowercase()
            var score = 0.0

            for (token in queryTokens) {
                if (refLower.contains(token)) score += 4.0
                val occurrences = countOccurrences(textLower, token)
                if (occurrences > 0) {
                    // Sub-linear TF weighting so one repeated word doesn't drown out multi-term matches
                    score += 2.0 + kotlin.math.ln(occurrences.toDouble() + 1.0)
                }
            }
            // Bigram phrase bonus
            if (queryTokens.size >= 2) {
                for (i in 0 until queryTokens.lastIndex) {
                    val bigram = "${queryTokens[i]} ${queryTokens[i + 1]}"
                    if (textLower.contains(bigram)) score += 3.5
                }
            }
            chunk to score
        }

        val ranked = scored
            .sortedWith(compareByDescending<Pair<DocumentChunk, Double>> { it.second }.thenBy { it.first.chunkIndex })
            .take(topK)
            .map { it.first }
            .sortedBy { it.chunkIndex }

        return ranked
    }

    /**
     * Exports a complete, structured Markdown analysis report with full provenance and
     * clear separation between Source Facts and AI Interpretations.
     */
    fun exportAnalysisAsMarkdown(item: VaultItem, analysis: DocumentAnalysis): String = buildString {
        appendLine("# NEXUS AI — Document Intelligence Report")
        appendLine()
        appendLine("- **Title:** ${item.title}")
        if (!item.fileName.isNullOrBlank()) appendLine("- **Original File:** ${item.fileName}")
        if (!item.mimeType.isNullOrBlank()) appendLine("- **MIME Type:** ${item.mimeType}")
        appendLine("- **Extraction Method:** ${analysis.resolvedExtractionMethod().label}")
        appendLine("- **Extraction Status:** ${analysis.resolvedExtractionStatus().label}")
        if (analysis.extractionDisclosure.isNotBlank()) {
            appendLine("- **Coverage & Disclosure:** ${analysis.extractionDisclosure}")
        }
        appendLine("- **Chunks Processed:** ${analysis.chunksProcessed} / ${analysis.totalChunks}")
        appendLine("- **Inference Execution Source:** ${analysis.resolvedActualSource().badgeLabel} (${analysis.modelName}, ${analysis.latencyMs}ms)")
        appendLine()
        appendLine("## Executive Summary")
        appendLine(analysis.summary)
        appendLine()
        if (analysis.detailedExplanation.isNotBlank()) {
            appendLine("## Detailed Explanation of Content")
            appendLine(analysis.detailedExplanation)
            appendLine()
        }
        if (analysis.conclusions.isNotEmpty()) {
            appendLine("## Important Conclusions")
            analysis.conclusions.forEach { appendLine("- $it") }
            appendLine()
        }
        if (analysis.mainTopics.isNotEmpty()) {
            appendLine("## Main Topics & Concepts")
            analysis.mainTopics.forEach { appendLine("- $it") }
            appendLine()
        }
        if (analysis.sourceFacts.isNotEmpty()) {
            appendLine("## Verified Source Facts & Findings")
            analysis.sourceFacts.forEach { fact ->
                val ref = fact.sourceRef.takeIf { it.isNotBlank() }?.let { " *[$it]*" }.orEmpty()
                appendLine("- ${fact.fact}$ref")
            }
            appendLine()
        }
        if (analysis.aiInterpretations.isNotEmpty()) {
            appendLine("## AI Interpretations & Synthesis (Distinct from Direct Source Facts)")
            analysis.aiInterpretations.forEach { appendLine("- $it") }
            appendLine()
        }
        if (analysis.definitions.isNotEmpty()) {
            appendLine("## Definitions & Explanations")
            analysis.definitions.forEach { def ->
                val ref = def.sourceRef.takeIf { it.isNotBlank() }?.let { " *[$it]*" }.orEmpty()
                appendLine("- **${def.term}:** ${def.explanation}$ref")
            }
            appendLine()
        }
        if (analysis.entities.isNotEmpty()) {
            appendLine("## Entities, Dates & Figures")
            analysis.entities.forEach { ent ->
                val ref = ent.sourceRef.takeIf { it.isNotBlank() }?.let { " *[$it]*" }.orEmpty()
                appendLine("- **${ent.name}** (${ent.type}): ${ent.context}$ref")
            }
            appendLine()
        }
        if (analysis.tableInsights.isNotEmpty()) {
            appendLine("## Tables & Data Insights")
            analysis.tableInsights.forEach { ins ->
                val ref = ins.sourceRef.takeIf { it.isNotBlank() }?.let { " *[$it]*" }.orEmpty()
                appendLine("- **${ins.title}:** ${ins.finding}$ref")
            }
            appendLine()
        }
        if (analysis.actionItems.isNotEmpty()) {
            appendLine("## Action Items")
            analysis.actionItems.forEach { act ->
                val ref = act.sourceRef.takeIf { it.isNotBlank() }?.let { " *[$it]*" }.orEmpty()
                appendLine("- **[${act.priority}]** ${act.action}$ref")
            }
            appendLine()
        }
        if (analysis.groundedQa.isNotEmpty()) {
            appendLine("## Grounded Q&A")
            analysis.groundedQa.forEachIndexed { idx, qa ->
                val ref = qa.sourceRef.takeIf { it.isNotBlank() }?.let { " *[$it]*" }.orEmpty()
                appendLine("### ${idx + 1}. ${qa.question}")
                appendLine("${qa.answer}$ref")
                appendLine()
            }
        }
    }.trim()

    private fun splitLargeText(text: String, maxChars: Int): List<String> {
        val parts = mutableListOf<String>()
        val lines = text.lines()
        val buf = StringBuilder()
        for (line in lines) {
            if (line.length > maxChars) {
                if (buf.isNotEmpty()) {
                    parts.add(buf.toString().trim())
                    buf.setLength(0)
                }
                var start = 0
                while (start < line.length) {
                    val end = minOf(start + maxChars, line.length)
                    parts.add(line.substring(start, end))
                    start = end
                }
            } else {
                if (buf.length + line.length + 1 > maxChars && buf.isNotEmpty()) {
                    parts.add(buf.toString().trim())
                    buf.setLength(0)
                }
                buf.appendLine(line)
            }
        }
        if (buf.isNotBlank()) parts.add(buf.toString().trim())
        return parts
    }

    private fun tokenize(text: String): List<String> =
        text.split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .distinct()

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
