package com.example.data.extraction

import com.example.data.model.DocumentSection
import com.example.data.model.ExtractedEmbeddedMedia
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.min

/**
 * Magic-byte MIME detection and Android-safe hardened SAX parsers for DOCX, PPTX, XLSX,
 * OpenDocument (ODT/ODS/ODP), EPUB, HTML, RTF, and legacy OLE2 documents.
 *
 * CRITICAL ANDROID COMPATIBILITY NOTE:
 * Android's runtime SAX implementation (`org.apache.harmony.xml.ExpatParser`) populates
 * `localName` and frequently passes an empty `qName` (`""`) when namespace-aware, whereas
 * Desktop OpenJDK Xerces populates `qName`. Every handler below uses [resolvedTagName] and
 * [findAttr] so parsing is 100% identical across both real Android devices/emulators and JVM tests.
 */
object OoxmlDocumentParsers {

    private const val MAX_ARCHIVE_EXPANDED_BYTES = 45L * 1024L * 1024L
    private const val MAX_EMBEDDED_IMAGES = 12
    private const val MIN_EMBEDDED_IMAGE_BYTES = 256
    private const val MAX_EMBEDDED_IMAGE_BYTES = 8 * 1024 * 1024
    private const val XLSX_ROWS_PER_SECTION = 50

    data class DocxParseOutput(
        val sections: List<DocumentSection>,
        val blockCount: Int,
        val embeddedMedia: List<ExtractedEmbeddedMedia>,
        val unsupportedVisualCount: Int,
        val headerFooterCount: Int,
    )

    data class PptxParseOutput(
        val sections: List<DocumentSection>,
        val slideCount: Int,
        val notesCount: Int,
        val embeddedMedia: List<ExtractedEmbeddedMedia>,
        val unsupportedVisualCount: Int,
    )

    data class XlsxParseOutput(
        val sections: List<DocumentSection>,
        val sheetCount: Int,
        val totalDataRows: Int,
        val formulaCount: Int,
    )

    private data class DocxBlock(val isHeading: Boolean, val headingTitle: String?, val text: String)

    private val RASTER_IMAGE_EXT_TO_MIME = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
    )

    private val UNSUPPORTED_VECTOR_EXTS = setOf("emf", "wmf", "svg", "eps", "tif", "tiff")

    /**
     * Sniffs actual file magic bytes, ZIP/OOXML/ODF/EPUB structure, and printable text heuristics
     * so misnamed, extensionless, or generic `application/octet-stream` files are classified accurately.
     */
    fun detectActualMimeType(
        bytes: ByteArray,
        fileName: String,
        declaredMime: String?,
    ): String {
        if (bytes.size >= 5 &&
            bytes[0] == '%'.code.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[4] == '-'.code.toByte()
        ) {
            return "application/pdf"
        }

        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }

        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }

        if (bytes.size >= 12 &&
            String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP"
        ) {
            return "image/webp"
        }

        if (bytes.size >= 12 &&
            String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WAVE"
        ) {
            return "audio/wav"
        }

        if (bytes.size >= 6 &&
            (String(bytes, 0, 6, StandardCharsets.US_ASCII) == "GIF89a" ||
                String(bytes, 0, 6, StandardCharsets.US_ASCII) == "GIF87a")
        ) {
            return "image/gif"
        }

        if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
            return "image/bmp"
        }

        // ISO Base Media File Format (HEIC/AVIF/MP4/M4A/MOV)
        if (bytes.size >= 12 && String(bytes, 4, 4, StandardCharsets.US_ASCII) == "ftyp") {
            val brand = String(bytes, 8, 4, StandardCharsets.US_ASCII).lowercase(Locale.US)
            return when {
                brand.startsWith("hei") || brand.startsWith("mif") -> "image/heic"
                brand.startsWith("avi") -> "image/avif"
                brand.startsWith("m4a") -> "audio/mp4"
                else -> "video/mp4"
            }
        }

        // MP3 (ID3 tag or MPEG sync)
        if (bytes.size >= 3 && String(bytes, 0, 3, StandardCharsets.US_ASCII) == "ID3") {
            return "audio/mpeg"
        }
        if (bytes.size >= 4 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "OggS") {
            return "audio/ogg"
        }
        if (bytes.size >= 4 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "fLaC") {
            return "audio/flac"
        }

        // Legacy OLE2 Compound Document (.doc / .xls / .ppt)
        if (bytes.size >= 8 &&
            bytes[0] == 0xD0.toByte() &&
            bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() &&
            bytes[3] == 0xE0.toByte()
        ) {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
            return when (ext) {
                "xls" -> "application/vnd.ms-excel"
                "ppt" -> "application/vnd.ms-powerpoint"
                else -> "application/msword"
            }
        }

        // ZIP / OOXML / OpenDocument / EPUB signature: PK\x03\x04
        if (bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()
        ) {
            val zipEntries = runCatching { listZipEntryNames(bytes) }.getOrDefault(emptySet())
            val lowerNames = zipEntries.map { it.lowercase(Locale.US) }.toSet()
            return when {
                "word/document.xml" in lowerNames ->
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "ppt/presentation.xml" in lowerNames || lowerNames.any { it.startsWith("ppt/slides/slide") } ->
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "xl/workbook.xml" in lowerNames || lowerNames.any { it.startsWith("xl/worksheets/sheet") } ->
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "meta-inf/container.xml" in lowerNames && lowerNames.any { it.endsWith(".xhtml") || it.endsWith(".html") } ->
                    "application/epub+zip"
                "content.xml" in lowerNames ->
                    "application/vnd.oasis.opendocument.text"
                else -> "application/zip"
            }
        }

        // Check declared/extension MIME first for structured text formats
        val resolved = DocumentExtractionEngine.resolveMimeType(declaredMime, fileName)
        if (resolved != "application/octet-stream") {
            return resolved
        }

        // Prefix / printable-text sniffing for extensionless or generic octet-stream text files
        val sampleLen = min(bytes.size, 4096)
        val hasUtf16Bom = bytes.size >= 2 &&
            ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        if (hasUtf16Bom) {
            return "text/plain"
        }

        val head = String(bytes, 0, sampleLen, StandardCharsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val lowerHead = head.take(64).lowercase(Locale.US)
        when {
            lowerHead.startsWith("{\\rtf") -> return "application/rtf"
            lowerHead.startsWith("<!doctype html") || lowerHead.startsWith("<html") -> return "text/html"
            lowerHead.startsWith("<?xml") -> return "application/xml"
            (lowerHead.startsWith("{") || lowerHead.startsWith("[")) && isMostlyPrintableText(bytes, sampleLen) ->
                return "application/json"
            isMostlyPrintableText(bytes, sampleLen) -> return "text/plain"
        }

        return resolved
    }

    private fun isMostlyPrintableText(bytes: ByteArray, sampleLen: Int): Boolean {
        if (sampleLen <= 0) return false
        var controlBytes = 0
        for (i in 0 until sampleLen) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return false // NUL byte -> binary (UTF-16 handled above via BOM)
            if (b < 0x09 || (b in 0x0E..0x1F)) {
                controlBytes++
            }
        }
        return controlBytes.toDouble() / sampleLen.toDouble() < 0.05
    }

    // ------------------------------------------------------------------------
    // 1. DOCX Parser (body, headings, tables, headers/footers/footnotes, embedded media)
    // ------------------------------------------------------------------------
    fun parseDocx(
        bytes: ByteArray,
        mediaOutputDir: File? = null,
    ): DocxParseOutput {
        val entries = readAllZipEntries(bytes)
        val docXml = entries.entries.firstOrNull { it.key.equals("word/document.xml", ignoreCase = true) }?.value
            ?: throw IllegalArgumentException("DOCX archive is missing word/document.xml (corrupted or encrypted).")

        val bodyBlocks = parseWordprocessingXml(docXml)
        val sections = groupDocxBlocksIntoSections(bodyBlocks).toMutableList()
        var sectionIndex = sections.size + 1

        // Also capture headers, footers, footnotes, endnotes, and comments if they contain meaningful text
        val supplementalKeys = entries.keys
            .filter { k ->
                val lower = k.lowercase(Locale.US)
                (lower.startsWith("word/header") ||
                    lower.startsWith("word/footer") ||
                    lower == "word/footnotes.xml" ||
                    lower == "word/endnotes.xml" ||
                    lower == "word/comments.xml") &&
                    lower.endsWith(".xml")
            }
            .sorted()

        var headerFooterCount = 0
        for (key in supplementalKeys) {
            val xml = entries[key] ?: continue
            val extraBlocks = runCatching { parseWordprocessingXml(xml) }.getOrDefault(emptyList())
            val combinedExtra = extraBlocks.joinToString("\n\n") { it.text }.trim()
            if (combinedExtra.length >= 4) {
                headerFooterCount++
                val label = key.substringAfter("word/").substringBeforeLast('.')
                    .replaceFirstChar { it.uppercaseChar() }
                sections.add(
                    DocumentSection(
                        index = sectionIndex++,
                        referenceLabel = "DOCX $label",
                        content = combinedExtra,
                    )
                )
            }
        }

        val (embeddedMedia, unsupportedCount) = extractEmbeddedImages(
            entries = entries,
            mediaPrefix = "word/media/",
            labelPrefix = "DOCX Embedded Visual",
            mediaOutputDir = mediaOutputDir,
        )

        return DocxParseOutput(
            sections = sections,
            blockCount = bodyBlocks.size,
            embeddedMedia = embeddedMedia,
            unsupportedVisualCount = unsupportedCount,
            headerFooterCount = headerFooterCount,
        )
    }

    // ------------------------------------------------------------------------
    // 2. PPTX Parser (ordered slides, tables, speaker notes, embedded media)
    // ------------------------------------------------------------------------
    fun parsePptx(
        bytes: ByteArray,
        mediaOutputDir: File? = null,
    ): PptxParseOutput {
        val entries = readAllZipEntries(bytes)
        val slideRegex = Regex("^ppt/slides/slide(\\d+)\\.xml$", RegexOption.IGNORE_CASE)
        val slideEntries = entries.entries
            .mapNotNull { (path, data) ->
                val match = slideRegex.matchEntire(path) ?: return@mapNotNull null
                val num = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                Triple(num, path, data)
            }
            .sortedBy { it.first }

        if (slideEntries.isEmpty()) {
            throw IllegalArgumentException("PPTX archive contains no readable slides under ppt/slides/slide*.xml.")
        }

        val notesMap = mutableMapOf<Int, ByteArray>()
        val notesRegex = Regex("^ppt/notesSlides/notesSlide(\\d+)\\.xml$", RegexOption.IGNORE_CASE)
        entries.forEach { (path, data) ->
            val m = notesRegex.matchEntire(path)
            val num = m?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (num != null) notesMap[num] = data
        }

        val sections = mutableListOf<DocumentSection>()
        var notesCount = 0

        slideEntries.forEachIndexed { idx, (slideNum, _, xmlBytes) ->
            val slideText = parseDrawingMlSlideXml(xmlBytes)
            val notesText = notesMap[slideNum]?.let { runCatching { parseDrawingMlSlideXml(it) }.getOrDefault("") }.orEmpty()
            if (notesText.isNotBlank()) notesCount++

            val body = buildString {
                if (slideText.isNotBlank()) {
                    appendLine(slideText)
                } else {
                    appendLine("(No text elements detected on this slide — inspect embedded visuals if present.)")
                }
                if (notesText.isNotBlank()) {
                    appendLine()
                    appendLine("### Speaker Notes")
                    appendLine(notesText)
                }
            }.trim()

            sections.add(
                DocumentSection(
                    index = idx + 1,
                    referenceLabel = "Slide $slideNum",
                    content = body,
                )
            )
        }

        val (embeddedMedia, unsupportedCount) = extractEmbeddedImages(
            entries = entries,
            mediaPrefix = "ppt/media/",
            labelPrefix = "PPTX Embedded Visual",
            mediaOutputDir = mediaOutputDir,
        )

        return PptxParseOutput(
            sections = sections,
            slideCount = slideEntries.size,
            notesCount = notesCount,
            embeddedMedia = embeddedMedia,
            unsupportedVisualCount = unsupportedCount,
        )
    }

    // ------------------------------------------------------------------------
    // 3. XLSX Parser (worksheets, shared strings, values + Excel formulas <f>)
    // ------------------------------------------------------------------------
    fun parseXlsx(bytes: ByteArray): XlsxParseOutput {
        val entries = readAllZipEntries(bytes)
        val sharedStrings = entries.entries
            .firstOrNull { it.key.equals("xl/sharedStrings.xml", ignoreCase = true) }
            ?.value
            ?.let { parseXlsxSharedStrings(it) }
            .orEmpty()

        val workbookEntry = entries.entries.firstOrNull { it.key.equals("xl/workbook.xml", ignoreCase = true) }?.value
        val relsEntry = entries.entries.firstOrNull { it.key.equals("xl/_rels/workbook.xml.rels", ignoreCase = true) }?.value

        val sheetDefs = parseXlsxWorkbookSheets(
            workbookXml = workbookEntry,
            relsXml = relsEntry,
            availableEntries = entries.keys,
        )
        if (sheetDefs.isEmpty()) {
            throw IllegalArgumentException("XLSX workbook contains no readable worksheets (may be corrupted or encrypted).")
        }

        val sections = mutableListOf<DocumentSection>()
        var totalDataRows = 0
        var totalFormulas = 0
        var secIdx = 1

        sheetDefs.forEach { (sheetName, entryPath) ->
            val sheetXml = entries[entryPath]
                ?: entries.entries.firstOrNull { it.key.equals(entryPath, ignoreCase = true) }?.value
                ?: return@forEach
            val parsedSheet = parseXlsxSheetGridWithFormulas(sheetXml, sharedStrings)
            if (parsedSheet.rows.isEmpty()) return@forEach

            val headers = parsedSheet.rows.first().mapIndexed { idx, v -> v.trim().ifBlank { "Col_${idx + 1}" } }
            val dataRows = parsedSheet.rows.drop(1)
            totalDataRows += dataRows.size
            totalFormulas += parsedSheet.formulaSamples.size

            val profile = buildString {
                append(DocumentExtractionEngine.buildTabularProfile("Sheet: $sheetName", headers, dataRows))
                if (parsedSheet.formulaSamples.isNotEmpty()) {
                    appendLine()
                    appendLine("- Formulas Detected (${parsedSheet.formulaSamples.size}):")
                    parsedSheet.formulaSamples.take(20).forEach { f ->
                        appendLine("  • $f")
                    }
                }
            }.trim()

            sections.add(
                DocumentSection(
                    index = secIdx++,
                    referenceLabel = "Sheet: $sheetName (Schema, Stats & Formulas)",
                    content = profile,
                )
            )

            var offset = 0
            while (offset < dataRows.size) {
                val slice = dataRows.subList(offset, min(offset + XLSX_ROWS_PER_SECTION, dataRows.size))
                val startRow = offset + 1
                val endRow = offset + slice.size
                sections.add(
                    DocumentSection(
                        index = secIdx++,
                        referenceLabel = "Sheet: $sheetName • Rows $startRow–$endRow",
                        content = DocumentExtractionEngine.formatMarkdownTable(headers, slice),
                    )
                )
                offset += XLSX_ROWS_PER_SECTION
            }
        }

        return XlsxParseOutput(
            sections = sections,
            sheetCount = sheetDefs.size,
            totalDataRows = totalDataRows,
            formulaCount = totalFormulas,
        )
    }

    // ------------------------------------------------------------------------
    // 4. OpenDocument (ODT/ODS/ODP), EPUB, HTML, RTF & Legacy OLE2 Extractors
    // ------------------------------------------------------------------------
    fun parseOpenDocumentZip(bytes: ByteArray): List<DocumentSection> {
        val entries = readAllZipEntries(bytes)
        val contentXml = entries.entries.firstOrNull { it.key.equals("content.xml", ignoreCase = true) }?.value
            ?: throw IllegalArgumentException("OpenDocument archive is missing content.xml.")
        val paragraphs = mutableListOf<String>()
        val buf = StringBuilder()
        var capture = false

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (resolvedTagName(localName, qName)) {
                    "p", "h" -> {
                        capture = true
                        buf.setLength(0)
                    }
                    "s" -> if (capture) buf.append(' ')
                    "tab" -> if (capture) buf.append('\t')
                    "line-break" -> if (capture) buf.append('\n')
                }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (capture && ch != null) buf.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (resolvedTagName(localName, qName)) {
                    "p", "h" -> {
                        val clean = buf.toString().trim()
                        if (clean.isNotEmpty()) paragraphs.add(clean)
                        capture = false
                    }
                }
            }
        }
        createHardenedSaxParser().parse(ByteArrayInputStream(contentXml), handler)
        return groupTextBlocksIntoSections(paragraphs, "Section")
    }

    fun parseEpubZip(bytes: ByteArray): List<DocumentSection> {
        val entries = readAllZipEntries(bytes)
        val chapterEntries = entries.entries
            .filter { (k, _) ->
                val l = k.lowercase(Locale.US)
                l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm")
            }
            .sortedBy { it.key }
        val sections = mutableListOf<DocumentSection>()
        var idx = 1
        for ((name, data) in chapterEntries) {
            val clean = stripHtmlToPlainText(String(data, StandardCharsets.UTF_8))
            if (clean.length >= 20) {
                val chapterLabel = name.substringAfterLast('/').substringBeforeLast('.')
                sections.add(DocumentSection(idx++, "Chapter $idx: $chapterLabel", clean))
            }
        }
        return sections
    }

    fun parseGenericZipTextEntries(bytes: ByteArray): List<DocumentSection> {
        val entries = readAllZipEntries(bytes)
        val sections = mutableListOf<DocumentSection>()
        var idx = 1
        entries.entries.sortedBy { it.key }.forEach { (name, data) ->
            if (data.isNotEmpty() && isMostlyPrintableText(data, min(data.size, 2048))) {
                val text = String(data, StandardCharsets.UTF_8).trim()
                if (text.isNotEmpty()) {
                    sections.add(DocumentSection(idx++, "Archive Entry: $name", text.take(12_000)))
                }
            }
        }
        return sections
    }

    fun stripHtmlToPlainText(rawHtml: String): String {
        val noScripts = rawHtml
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?i)<(br|/p|/div|/li|/tr|/h[1-6])[^>]*>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), " ")
        return unescapeHtmlEntities(noScripts)
            .lines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun stripRtfToPlainText(rawRtf: String): String {
        val stripped = rawRtf
            .replace(Regex("\\\\'[0-9a-fA-F]{2}"), "")
            .replace(Regex("\\\\(par|line)\\b"), "\n")
            .replace(Regex("\\\\tab\\b"), "\t")
            .replace(Regex("\\\\[a-zA-Z]+-?\\d*\\s?"), "")
            .replace("{", "")
            .replace("}", "")
        return stripped.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun extractLegacyOle2ReadableText(bytes: ByteArray): List<DocumentSection> {
        // Best-effort extraction of printable ASCII & UTF-16LE text runs from legacy .doc/.xls/.ppt
        val runs = mutableListOf<String>()
        val asciiBuf = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126 || c == 10 || c == 13 || c == 9) {
                asciiBuf.append(c.toChar())
            } else {
                val s = asciiBuf.toString().trim()
                if (s.length >= 24 && s.count { it.isLetterOrDigit() } >= 14) {
                    runs.add(s)
                }
                asciiBuf.setLength(0)
            }
        }
        val tail = asciiBuf.toString().trim()
        if (tail.length >= 24) runs.add(tail)

        // Also scan UTF-16LE runs (used by Word 97–2003 Unicode text)
        val u16Buf = StringBuilder()
        var i = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            val ch = (hi shl 8) or lo
            if (ch in 32..126 || ch == 10 || ch == 13 || ch == 9) {
                u16Buf.append(ch.toChar())
            } else {
                val s = u16Buf.toString().trim()
                if (s.length >= 24 && s.count { it.isLetterOrDigit() } >= 14) {
                    runs.add(s)
                }
                u16Buf.setLength(0)
            }
            i += 2
        }
        val cleaned = runs
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filterNot { it.startsWith("Root Entry") || it.startsWith("Microsoft") && it.length < 40 }
            .distinct()
        return groupTextBlocksIntoSections(cleaned, "Legacy Office Extract")
    }

    private fun groupTextBlocksIntoSections(blocks: List<String>, prefix: String): List<DocumentSection> {
        if (blocks.isEmpty()) return emptyList()
        val sections = mutableListOf<DocumentSection>()
        val buf = StringBuilder()
        var idx = 1
        for (b in blocks) {
            if (buf.isNotEmpty()) buf.append("\n\n")
            buf.append(b)
            if (buf.length >= 4200) {
                sections.add(DocumentSection(idx, "$prefix $idx", buf.toString().trim()))
                idx++
                buf.setLength(0)
            }
        }
        if (buf.isNotBlank()) {
            sections.add(DocumentSection(idx, "$prefix $idx", buf.toString().trim()))
        }
        return sections
    }

    private fun unescapeHtmlEntities(input: String): String = input
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")

    // ------------------------------------------------------------------------
    // Internal Android-Safe Hardened SAX Parsers & ZIP Utilities
    // ------------------------------------------------------------------------
    private fun createHardenedSaxParser() = SAXParserFactory.newInstance().apply {
        // Namespace-aware = true is consistent on Android ExpatParser AND OpenJDK Xerces,
        // and we ALWAYS use resolvedTagName(localName, qName) + findAttr(attributes, ...).
        isNamespaceAware = true
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newSAXParser()

    /**
     * Resolves the local XML tag name safely on BOTH Android ExpatParser (which populates `localName`
     * and may leave `qName` empty) AND OpenJDK Xerces (which populates `qName` and/or `localName`).
     */
    internal fun resolvedTagName(localName: String?, qName: String?): String =
        localName?.takeIf { it.isNotBlank() }
            ?: qName?.substringAfter(':')?.takeIf { it.isNotBlank() }
            ?: ""

    /**
     * Finds an XML attribute value by local name or qualified name across Android ExpatParser & Xerces.
     */
    internal fun findAttr(attributes: Attributes?, vararg candidateLocalNames: String): String? {
        if (attributes == null) return null
        for (candidate in candidateLocalNames) {
            attributes.getValue(candidate)?.let { return it }
        }
        for (i in 0 until attributes.length) {
            val ln = attributes.getLocalName(i)
            val qn = attributes.getQName(i)?.substringAfter(':')
            for (candidate in candidateLocalNames) {
                val cleanCandidate = candidate.substringAfter(':')
                if (ln.equals(cleanCandidate, ignoreCase = false) ||
                    qn.equals(cleanCandidate, ignoreCase = false)
                ) {
                    return attributes.getValue(i)
                }
            }
        }
        return null
    }

    private fun parseWordprocessingXml(xmlBytes: ByteArray): List<DocxBlock> {
        val blocks = mutableListOf<DocxBlock>()
        var inTable = false
        val currentTableRows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        var inCell = false
        val cellBuffer = StringBuilder()

        var inParagraph = false
        val paraBuffer = StringBuilder()
        var paraStyle = ""
        var inTextNode = false

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (resolvedTagName(localName, qName)) {
                    "tbl" -> {
                        inTable = true
                        currentTableRows.clear()
                    }
                    "tr" -> if (inTable) currentRow = mutableListOf()
                    "tc" -> if (inTable) {
                        inCell = true
                        cellBuffer.setLength(0)
                    }
                    "p" -> {
                        inParagraph = true
                        paraBuffer.setLength(0)
                        paraStyle = ""
                    }
                    "pStyle" -> if (inParagraph) {
                        paraStyle = findAttr(attributes, "val", "w:val").orEmpty()
                    }
                    "t" -> if (inParagraph) inTextNode = true
                    "tab" -> if (inParagraph) paraBuffer.append('\t')
                    "br" -> if (inParagraph) paraBuffer.append('\n')
                }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (inParagraph && inTextNode && ch != null) {
                    paraBuffer.append(ch, start, length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (resolvedTagName(localName, qName)) {
                    "t" -> inTextNode = false
                    "p" -> {
                        val clean = paraBuffer.toString().trim()
                        if (inTable && inCell) {
                            if (clean.isNotEmpty()) {
                                if (cellBuffer.isNotEmpty()) cellBuffer.append(" ")
                                cellBuffer.append(clean)
                            }
                        } else if (clean.isNotEmpty()) {
                            val isHeading = paraStyle.startsWith("Heading", ignoreCase = true) ||
                                paraStyle.equals("Title", ignoreCase = true) ||
                                paraStyle.equals("Subtitle", ignoreCase = true)
                            val prefix = when {
                                paraStyle.equals("Title", ignoreCase = true) -> "# "
                                paraStyle.equals("Heading1", ignoreCase = true) -> "# "
                                paraStyle.equals("Heading2", ignoreCase = true) -> "## "
                                paraStyle.startsWith("Heading", ignoreCase = true) -> "### "
                                else -> ""
                            }
                            blocks.add(
                                DocxBlock(
                                    isHeading = isHeading,
                                    headingTitle = if (isHeading) clean.take(70) else null,
                                    text = "$prefix$clean",
                                )
                            )
                        }
                        inParagraph = false
                        inTextNode = false
                    }
                    "tc" -> if (inTable && inCell) {
                        currentRow.add(cellBuffer.toString().trim())
                        inCell = false
                    }
                    "tr" -> if (inTable && currentRow.any { it.isNotBlank() }) {
                        currentTableRows.add(currentRow.toList())
                    }
                    "tbl" -> if (inTable) {
                        if (currentTableRows.isNotEmpty()) {
                            val headers = currentTableRows.first().mapIndexed { i, h -> h.ifBlank { "Column_${i + 1}" } }
                            val mdTable = DocumentExtractionEngine.formatMarkdownTable(headers, currentTableRows.drop(1))
                            blocks.add(DocxBlock(isHeading = false, headingTitle = null, text = mdTable))
                        }
                        inTable = false
                    }
                }
            }
        }
        createHardenedSaxParser().parse(ByteArrayInputStream(xmlBytes), handler)
        return blocks
    }

    private fun groupDocxBlocksIntoSections(blocks: List<DocxBlock>): List<DocumentSection> {
        val sections = mutableListOf<DocumentSection>()
        var currentLabel = "Section 1"
        val currentBuf = StringBuilder()
        var sectionIndex = 1

        for (block in blocks) {
            if (block.isHeading && currentBuf.isNotBlank()) {
                sections.add(DocumentSection(sectionIndex++, currentLabel, currentBuf.toString().trim()))
                currentBuf.setLength(0)
                currentLabel = "Section $sectionIndex: ${block.headingTitle ?: "Content"}"
            } else if (block.isHeading && currentBuf.isBlank()) {
                currentLabel = "Section $sectionIndex: ${block.headingTitle ?: "Content"}"
            }

            if (currentBuf.isNotEmpty()) currentBuf.append("\n\n")
            currentBuf.append(block.text)

            if (currentBuf.length >= 4500 && !block.isHeading) {
                sections.add(DocumentSection(sectionIndex++, currentLabel, currentBuf.toString().trim()))
                currentBuf.setLength(0)
                currentLabel = "Section $sectionIndex"
            }
        }
        if (currentBuf.isNotBlank()) {
            sections.add(DocumentSection(sectionIndex, currentLabel, currentBuf.toString().trim()))
        }
        return sections
    }

    private fun parseDrawingMlSlideXml(xmlBytes: ByteArray): String {
        val blocks = mutableListOf<String>()
        var inTable = false
        val tableRows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        var inCell = false
        val cellBuf = StringBuilder()

        var inParagraph = false
        val paraBuf = StringBuilder()
        var inText = false

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (resolvedTagName(localName, qName)) {
                    "tbl" -> {
                        inTable = true
                        tableRows.clear()
                    }
                    "tr" -> if (inTable) currentRow = mutableListOf()
                    "tc" -> if (inTable) {
                        inCell = true
                        cellBuf.setLength(0)
                    }
                    "p" -> {
                        inParagraph = true
                        paraBuf.setLength(0)
                    }
                    "t" -> if (inParagraph) inText = true
                    "br" -> if (inParagraph) paraBuf.append('\n')
                }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (inParagraph && inText && ch != null) {
                    paraBuf.append(ch, start, length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (resolvedTagName(localName, qName)) {
                    "t" -> inText = false
                    "p" -> {
                        val clean = paraBuf.toString().trim()
                        if (inTable && inCell) {
                            if (clean.isNotEmpty()) {
                                if (cellBuf.isNotEmpty()) cellBuf.append(" ")
                                cellBuf.append(clean)
                            }
                        } else if (clean.isNotEmpty()) {
                            blocks.add(clean)
                        }
                        inParagraph = false
                        inText = false
                    }
                    "tc" -> if (inTable && inCell) {
                        currentRow.add(cellBuf.toString().trim())
                        inCell = false
                    }
                    "tr" -> if (inTable && currentRow.any { it.isNotBlank() }) {
                        tableRows.add(currentRow.toList())
                    }
                    "tbl" -> if (inTable) {
                        if (tableRows.isNotEmpty()) {
                            val headers = tableRows.first().mapIndexed { i, h -> h.ifBlank { "Col_${i + 1}" } }
                            blocks.add(DocumentExtractionEngine.formatMarkdownTable(headers, tableRows.drop(1)))
                        }
                        inTable = false
                    }
                }
            }
        }
        createHardenedSaxParser().parse(ByteArrayInputStream(xmlBytes), handler)
        return blocks.joinToString("\n\n").trim()
    }

    private fun extractEmbeddedImages(
        entries: Map<String, ByteArray>,
        mediaPrefix: String,
        labelPrefix: String,
        mediaOutputDir: File?,
    ): Pair<List<ExtractedEmbeddedMedia>, Int> {
        val rasterCandidates = mutableListOf<Triple<String, String, ByteArray>>()
        var unsupportedVectorCount = 0

        entries.forEach { (path, data) ->
            val lower = path.lowercase(Locale.US)
            if (!lower.startsWith(mediaPrefix)) return@forEach
            val ext = lower.substringAfterLast('.', "")
            val mime = RASTER_IMAGE_EXT_TO_MIME[ext]
            when {
                mime != null && data.size in MIN_EMBEDDED_IMAGE_BYTES..MAX_EMBEDDED_IMAGE_BYTES -> {
                    rasterCandidates.add(Triple(path, mime, data))
                }
                ext in UNSUPPORTED_VECTOR_EXTS -> unsupportedVectorCount++
            }
        }

        if (rasterCandidates.isEmpty() || mediaOutputDir == null) {
            return emptyList<ExtractedEmbeddedMedia>() to unsupportedVectorCount
        }

        if (!mediaOutputDir.exists()) mediaOutputDir.mkdirs()

        val selected = rasterCandidates
            .sortedByDescending { it.third.size }
            .take(MAX_EMBEDDED_IMAGES)
            .sortedBy { it.first }

        val persisted = selected.mapIndexed { idx, (entryPath, mime, data) ->
            val baseName = entryPath.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val outFile = File(mediaOutputDir, "embedded_${idx + 1}_$baseName")
            outFile.writeBytes(data)
            ExtractedEmbeddedMedia(
                id = "embedded_${idx + 1}",
                fileName = baseName,
                mimeType = mime,
                localPath = outFile.absolutePath,
                referenceLabel = "$labelPrefix ${idx + 1} ($baseName)",
            )
        }
        return persisted to unsupportedVectorCount
    }

    private fun parseXlsxSharedStrings(xmlBytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        var inSi = false
        var inText = false
        val siBuf = StringBuilder()

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (resolvedTagName(localName, qName)) {
                    "si" -> {
                        inSi = true
                        siBuf.setLength(0)
                    }
                    "t" -> if (inSi) inText = true
                }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (inSi && inText && ch != null) siBuf.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (resolvedTagName(localName, qName)) {
                    "t" -> inText = false
                    "si" -> {
                        strings.add(siBuf.toString())
                        inSi = false
                    }
                }
            }
        }
        createHardenedSaxParser().parse(ByteArrayInputStream(xmlBytes), handler)
        return strings
    }

    private fun parseXlsxWorkbookSheets(
        workbookXml: ByteArray?,
        relsXml: ByteArray?,
        availableEntries: Set<String>,
    ): List<Pair<String, String>> {
        val relMap = mutableMapOf<String, String>()
        if (relsXml != null) {
            runCatching {
                val handler = object : DefaultHandler() {
                    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                        if (resolvedTagName(localName, qName) == "Relationship") {
                            val id = findAttr(attributes, "Id")
                            val target = findAttr(attributes, "Target")
                            if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                                val normalized = when {
                                    target.startsWith("/xl/") -> target.removePrefix("/")
                                    target.startsWith("xl/") -> target
                                    else -> "xl/${target.removePrefix("/")}"
                                }
                                relMap[id] = normalized
                            }
                        }
                    }
                }
                createHardenedSaxParser().parse(ByteArrayInputStream(relsXml), handler)
            }
        }

        val ordered = mutableListOf<Pair<String, String>>()
        val lowerAvailable = availableEntries.associateBy { it.lowercase(Locale.US) }
        if (workbookXml != null) {
            runCatching {
                var sheetCounter = 1
                val handler = object : DefaultHandler() {
                    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                        if (resolvedTagName(localName, qName) == "sheet") {
                            val name = findAttr(attributes, "name") ?: "Sheet$sheetCounter"
                            val rId = findAttr(attributes, "r:id", "id")
                            val target = relMap[rId] ?: "xl/worksheets/sheet$sheetCounter.xml"
                            val matchedKey = lowerAvailable[target.lowercase(Locale.US)]
                            if (matchedKey != null) {
                                ordered.add(name to matchedKey)
                            }
                            sheetCounter++
                        }
                    }
                }
                createHardenedSaxParser().parse(ByteArrayInputStream(workbookXml), handler)
            }
        }

        if (ordered.isEmpty()) {
            availableEntries
                .filter { it.lowercase(Locale.US).startsWith("xl/worksheets/sheet") && it.lowercase(Locale.US).endsWith(".xml") }
                .sorted()
                .forEachIndexed { idx, path -> ordered.add("Sheet${idx + 1}" to path) }
        }
        return ordered
    }

    private data class ParsedSheetGrid(
        val rows: List<List<String>>,
        val formulaSamples: List<String>,
    )

    private fun parseXlsxSheetGridWithFormulas(
        xmlBytes: ByteArray,
        sharedStrings: List<String>,
    ): ParsedSheetGrid {
        val rows = mutableListOf<List<String>>()
        val formulaSamples = mutableListOf<String>()
        var currentRow = mutableMapOf<Int, String>()
        var inRow = false
        var currentColIdx = 0
        var currentCellRef = ""
        var cellType = ""
        var inCell = false
        var inVal = false
        var inFormula = false
        val valueBuf = StringBuilder()
        val formulaBuf = StringBuilder()

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (resolvedTagName(localName, qName)) {
                    "row" -> {
                        inRow = true
                        currentRow = mutableMapOf()
                        currentColIdx = 0
                    }
                    "c" -> if (inRow) {
                        inCell = true
                        cellType = findAttr(attributes, "t").orEmpty()
                        currentCellRef = findAttr(attributes, "r").orEmpty()
                        currentColIdx = parseColumnIndex(currentCellRef) ?: currentColIdx
                        valueBuf.setLength(0)
                        formulaBuf.setLength(0)
                    }
                    "v", "t" -> if (inCell) inVal = true
                    "f" -> if (inCell) inFormula = true
                }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (inCell && ch != null) {
                    if (inVal) valueBuf.append(ch, start, length)
                    if (inFormula) formulaBuf.append(ch, start, length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (resolvedTagName(localName, qName)) {
                    "v", "t" -> inVal = false
                    "f" -> inFormula = false
                    "c" -> if (inCell) {
                        val rawVal = valueBuf.toString().trim()
                        val rawFormula = formulaBuf.toString().trim()
                        val resolved = when (cellType) {
                            "s" -> rawVal.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: rawVal
                            "b" -> if (rawVal == "1") "TRUE" else "FALSE"
                            else -> rawVal
                        }
                        val displayCell = when {
                            rawFormula.isNotEmpty() && resolved.isNotEmpty() -> {
                                formulaSamples.add("${currentCellRef.ifBlank { "Cell" }}: =$rawFormula → $resolved")
                                "$resolved [=${rawFormula}]"
                            }
                            rawFormula.isNotEmpty() -> {
                                formulaSamples.add("${currentCellRef.ifBlank { "Cell" }}: =$rawFormula")
                                "=$rawFormula"
                            }
                            else -> resolved
                        }
                        if (displayCell.isNotEmpty()) {
                            currentRow[currentColIdx] = displayCell
                        }
                        currentColIdx++
                        inCell = false
                        inVal = false
                        inFormula = false
                    }
                    "row" -> if (inRow) {
                        if (currentRow.isNotEmpty()) {
                            val maxCol = currentRow.keys.maxOrNull() ?: 0
                            rows.add((0..maxCol).map { idx -> currentRow[idx] ?: "" })
                        }
                        inRow = false
                    }
                }
            }
        }
        createHardenedSaxParser().parse(ByteArrayInputStream(xmlBytes), handler)
        return ParsedSheetGrid(rows = rows, formulaSamples = formulaSamples)
    }

    private fun parseColumnIndex(cellRef: String?): Int? {
        if (cellRef.isNullOrBlank()) return null
        val letters = cellRef.takeWhile { it.isLetter() }.uppercase(Locale.US)
        if (letters.isEmpty()) return null
        var idx = 0
        for (ch in letters) {
            idx = idx * 26 + (ch - 'A' + 1)
        }
        return idx - 1
    }

    private fun listZipEntryNames(zipBytes: ByteArray): Set<String> {
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) names.add(entry.name)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return names
    }

    private fun readAllZipEntries(zipBytes: ByteArray): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            var totalBytes = 0L
            while (entry != null) {
                if (!entry.isDirectory) {
                    val data = zis.readBytes()
                    totalBytes += data.size
                    if (totalBytes > MAX_ARCHIVE_EXPANDED_BYTES) {
                        throw IllegalStateException("Archive expanded beyond 45 MB safety limit.")
                    }
                    map[entry.name] = data
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return map
    }
}
