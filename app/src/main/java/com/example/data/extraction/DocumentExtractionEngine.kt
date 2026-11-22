package com.example.data.extraction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.data.model.DocumentSection
import com.example.data.model.ExtractedEmbeddedMedia
import com.example.data.model.ExtractionMethod
import com.example.data.model.ExtractionStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class ExtractedDocumentResult(
    val title: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localFilePath: String,
    val status: ExtractionStatus,
    val method: ExtractionMethod,
    val disclosure: String,
    val pageCount: Int,
    val sections: List<DocumentSection>,
    val combinedText: String,
    val requiresMultimodalOcr: Boolean,
    val embeddedMedia: List<ExtractedEmbeddedMedia> = emptyList(),
)

/**
 * Genuine content-extraction engine for NEXUS AI.
 *
 * - Preserves every original uploaded file untouched in app-private storage (`filesDir/vault_originals/`).
 * - Verifies actual MIME type via magic-byte header & OOXML archive inspection (not blind extension trust).
 * - Extracts real structured text, headings, tables, formulas, speaker notes, and embedded visual
 *   media from TXT/MD, CSV, DOCX, PPTX, and XLSX locally via [OoxmlDocumentParsers].
 * - Inspects PDFs via Android `PdfRenderer` and images via `BitmapFactory` for multimodal OCR & visual
 *   understanding through Firebase AI Logic.
 * - NEVER substitutes filenames or metadata as document content.
 */
object DocumentExtractionEngine {

    private const val ORIGINALS_DIR = "vault_originals"
    private const val MAX_FILE_BYTES = 50L * 1024L * 1024L // 50 MB safety limit
    private const val CSV_ROWS_PER_SECTION = 60
    private const val MAX_IMAGE_DIMENSION = 2048

    fun originalsDirectory(context: Context): File {
        val dir = File(context.filesDir, ORIGINALS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Imports a file from Android SAF `Uri`, preserves the original file verbatim,
     * sniffs its real MIME type, and extracts its complete contents.
     */
    fun importAndExtract(context: Context, uri: Uri): Result<ExtractedDocumentResult> = runCatching {
        val resolver = context.contentResolver
        var fileName = "Untitled_Document"
        var queriedSize = 0L

        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) {
                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { fileName = it }
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    queriedSize = cursor.getLong(sizeIndex)
                }
            }
        }

        val initialMime = resolveMimeType(resolver.getType(uri), fileName)
        var resolvedMime = initialMime
        var effectiveFileName = fileName

        val safeFileName = effectiveFileName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").take(140)
        val destFile = File(originalsDirectory(context), "${UUID.randomUUID()}_$safeFileName")

        val copiedBytes = openSafStreamWithVirtualFallback(resolver, uri)?.let { (stream, exportedMime) ->
            if (!exportedMime.isNullOrBlank() && exportedMime != "*/*") {
                resolvedMime = exportedMime
                effectiveFileName = ensureExtensionForMime(effectiveFileName, exportedMime)
            }
            stream.use { input -> copyWithLimit(input, destFile, MAX_FILE_BYTES) }
        } ?: throw IllegalStateException("Could not open input stream for selected file ($effectiveFileName).")

        if (copiedBytes <= 0L) {
            destFile.delete()
            throw IllegalStateException("Selected file ($effectiveFileName) is empty (0 bytes).")
        }

        val finalSize = if (copiedBytes > 0L) copiedBytes else queriedSize
        val cleanTitle = effectiveFileName.substringBeforeLast('.').ifBlank { effectiveFileName }.take(180)

        extractFromPreservedFile(
            file = destFile,
            title = cleanTitle,
            fileName = effectiveFileName.take(255),
            mimeType = resolvedMime.take(120),
            sizeBytes = finalSize,
        )
    }

    /**
     * Runs local extraction directly from a preserved original file on disk using real magic-byte MIME detection.
     */
    fun extractFromPreservedFile(
        file: File,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
    ): ExtractedDocumentResult {
        if (!file.exists() || !file.canRead()) {
            return failedResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = file.absolutePath,
                method = ExtractionMethod.UNKNOWN,
                disclosure = "Original preserved file is missing or unreadable on this device.",
            )
        }

        val rawBytes = runCatching { file.readBytes() }.getOrElse { err ->
            return failedResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = file.absolutePath,
                method = ExtractionMethod.UNKNOWN,
                disclosure = "Failed to read file bytes: ${err.localizedMessage ?: "I/O error"}.",
            )
        }
        if (rawBytes.isEmpty()) {
            return failedResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = 0L,
                localFilePath = file.absolutePath,
                method = ExtractionMethod.UNKNOWN,
                disclosure = "File is empty (0 bytes) and contains no extractable content.",
            )
        }

        val detectedMime = OoxmlDocumentParsers.detectActualMimeType(rawBytes, fileName, mimeType)
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val lowerMime = detectedMime.lowercase(Locale.US)
        val mediaOutDir = File(file.parentFile ?: file.absoluteFile.parentFile, "${file.nameWithoutExtension}_media")

        return when {
            lowerMime == "application/pdf" || ext == "pdf" ->
                inspectPdfForMultimodalOcr(file, rawBytes, title, fileName, detectedMime, sizeBytes)

            isImageFormat(ext, lowerMime) ->
                inspectImageForVisualOcr(file, title, fileName, detectedMime, sizeBytes)

            lowerMime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || ext == "docx" ->
                extractDocxFromBytes(rawBytes, title, fileName, detectedMime, sizeBytes, file.absolutePath, mediaOutDir)

            lowerMime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" || ext == "pptx" ->
                extractPptxFromBytes(rawBytes, title, fileName, detectedMime, sizeBytes, file.absolutePath, mediaOutDir)

            lowerMime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" || ext == "xlsx" ->
                extractXlsxFromBytes(rawBytes, title, fileName, detectedMime, sizeBytes, file.absolutePath)

            lowerMime.startsWith("application/vnd.oasis.opendocument.") || ext in setOf("odt", "ods", "odp") ->
                extractOpenDocumentFromBytes(rawBytes, title, fileName, detectedMime, sizeBytes, file.absolutePath)

            lowerMime == "application/epub+zip" || ext == "epub" ->
                extractEpubFromBytes(rawBytes, title, fileName, detectedMime, sizeBytes, file.absolutePath)

            lowerMime == "text/csv" || lowerMime == "application/csv" || ext == "csv" ->
                extractCsvFromBytes(rawBytes, title, fileName, detectedMime, sizeBytes, file.absolutePath, delimiter = ',')

            lowerMime == "text/tab-separated-values" || ext == "tsv" ->
                extractCsvFromBytes(rawBytes, title, fileName, detectedMime, sizeBytes, file.absolutePath, delimiter = '\t')

            lowerMime == "text/html" || ext in setOf("html", "htm") ->
                extractHtmlFromBytes(rawBytes, title, fileName, detectedMime, sizeBytes, file.absolutePath)

            lowerMime == "application/rtf" || lowerMime == "text/rtf" || ext == "rtf" ->
                extractRtfFromBytes(rawBytes, title, fileName, detectedMime, sizeBytes, file.absolutePath)

            lowerMime in setOf("application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint") ||
                ext in setOf("doc", "xls", "ppt") ->
                extractLegacyOfficeFromBytes(rawBytes, title, fileName, detectedMime, sizeBytes, file.absolutePath)

            isAudioOrVideoFormat(ext, lowerMime) ->
                inspectMediaForMultimodal(file, title, fileName, detectedMime, sizeBytes)

            lowerMime == "application/zip" || ext == "zip" ->
                extractGenericZipFromBytes(rawBytes, title, fileName, detectedMime, sizeBytes, file.absolutePath)

            isPlainTextFormat(ext, lowerMime) ->
                extractPlainTextFromBytes(rawBytes, title, fileName, detectedMime, sizeBytes, file.absolutePath)

            else -> failedResult(
                title = title,
                fileName = fileName,
                mimeType = detectedMime,
                sizeBytes = sizeBytes,
                localFilePath = file.absolutePath,
                method = ExtractionMethod.UNKNOWN,
                disclosure = "Binary format ($ext / $detectedMime) does not contain readable text. Original file is preserved in your vault so you can view externally or download it anytime.",
            )
        }
    }

    fun persistExtractedSections(context: Context, itemId: String, sections: List<DocumentSection>) {
        if (itemId.isBlank()) return
        runCatching {
            val arr = JSONArray()
            sections.forEach { sec ->
                arr.put(
                    JSONObject().apply {
                        put("index", sec.index)
                        put("referenceLabel", sec.referenceLabel)
                        put("content", sec.content)
                    }
                )
            }
            File(originalsDirectory(context), "${itemId}_sections.json")
                .writeText(arr.toString(), StandardCharsets.UTF_8)
        }
    }

    fun loadExtractedSections(context: Context, itemId: String): List<DocumentSection> {
        if (itemId.isBlank()) return emptyList()
        return runCatching {
            val file = File(originalsDirectory(context), "${itemId}_sections.json")
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText(StandardCharsets.UTF_8))
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        DocumentSection(
                            index = obj.optInt("index", i + 1),
                            referenceLabel = obj.optString("referenceLabel", "Section ${i + 1}"),
                            content = obj.optString("content", ""),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun deleteLocalArtifacts(context: Context, itemId: String, localFilePath: String?) {
        runCatching {
            val dir = originalsDirectory(context)
            if (itemId.isNotBlank()) {
                File(dir, "${itemId}_sections.json").delete()
                DocumentCheckpointStore.clearCheckpoint(dir, itemId)
                DocumentCheckpointStore.embeddedMediaFile(dir, itemId).delete()
            }
            if (!localFilePath.isNullOrBlank()) {
                val f = File(localFilePath)
                if (f.exists() && f.parentFile?.name == ORIGINALS_DIR) {
                    val mediaDir = File(f.parentFile, "${f.nameWithoutExtension}_media")
                    if (mediaDir.exists()) mediaDir.deleteRecursively()
                    f.delete()
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // 1. Plain Text / Markdown / Code Extractor
    // ------------------------------------------------------------------------
    fun extractPlainTextFromBytes(
        bytes: ByteArray,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long = bytes.size.toLong(),
        localFilePath: String = "",
    ): ExtractedDocumentResult {
        val decoded = decodeTextAuto(bytes).replace("\r\n", "\n").replace("\r", "\n").trim()
        if (decoded.isEmpty()) {
            return failedResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = localFilePath,
                method = ExtractionMethod.DIRECT_TEXT,
                disclosure = "File contains no readable text content.",
            )
        }

        val sections = splitTextIntoSections(decoded)
        return ExtractedDocumentResult(
            title = title,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            localFilePath = localFilePath,
            status = ExtractionStatus.COMPLETE,
            method = ExtractionMethod.DIRECT_TEXT,
            disclosure = "Extracted complete text (${decoded.length} characters across ${sections.size} section(s)).",
            pageCount = sections.size,
            sections = sections,
            combinedText = formatSectionsForPreview(sections),
            requiresMultimodalOcr = false,
        )
    }

    // ------------------------------------------------------------------------
    // 2. CSV Table & Data Inspector (RFC-4180 quote-aware)
    // ------------------------------------------------------------------------
    fun extractCsvFromBytes(
        bytes: ByteArray,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long = bytes.size.toLong(),
        localFilePath: String = "",
        delimiter: Char = ',',
    ): ExtractedDocumentResult {
        val raw = decodeTextAuto(bytes).replace("\r\n", "\n").replace("\r", "\n").trim()
        val rows = parseDelimitedRows(raw, delimiter)
        if (rows.isEmpty()) {
            return failedResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = localFilePath,
                method = ExtractionMethod.CSV_PARSER,
                disclosure = "CSV file has no readable rows or columns.",
            )
        }

        val headers = rows.first().mapIndexed { idx, cell -> cell.trim().ifBlank { "Column_${idx + 1}" } }
        val dataRows = rows.drop(1)
        val sections = mutableListOf<DocumentSection>()
        sections.add(DocumentSection(1, "Dataset Schema & Summary", buildTabularProfile("CSV Dataset", headers, dataRows)))

        var rowOffset = 0
        var secIdx = 2
        while (rowOffset < dataRows.size) {
            val slice = dataRows.subList(rowOffset, min(rowOffset + CSV_ROWS_PER_SECTION, dataRows.size))
            val startRow = rowOffset + 1
            val endRow = rowOffset + slice.size
            sections.add(DocumentSection(secIdx++, "Rows $startRow–$endRow", formatMarkdownTable(headers, slice)))
            rowOffset += CSV_ROWS_PER_SECTION
        }

        return ExtractedDocumentResult(
            title = title,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            localFilePath = localFilePath,
            status = ExtractionStatus.COMPLETE,
            method = ExtractionMethod.CSV_PARSER,
            disclosure = "Parsed CSV completely: ${headers.size} columns, ${dataRows.size} data rows.",
            pageCount = sections.size,
            sections = sections,
            combinedText = formatSectionsForPreview(sections),
            requiresMultimodalOcr = false,
        )
    }

    // ------------------------------------------------------------------------
    // 3. DOCX Complete Extractor (paragraphs, headings, tables, headers/footers, embedded media)
    // ------------------------------------------------------------------------
    fun extractDocxFromBytes(
        bytes: ByteArray,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long = bytes.size.toLong(),
        localFilePath: String = "",
        mediaOutputDir: File? = null,
    ): ExtractedDocumentResult {
        return try {
            val parsed = OoxmlDocumentParsers.parseDocx(bytes, mediaOutputDir)
            if (parsed.sections.isEmpty() && parsed.embeddedMedia.isEmpty()) {
                return failedResult(
                    title = title,
                    fileName = fileName,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    localFilePath = localFilePath,
                    method = ExtractionMethod.DOCX_XML,
                    disclosure = "DOCX opened successfully, but no text paragraphs, tables, or readable embedded images were found.",
                )
            }

            val hasMedia = parsed.embeddedMedia.isNotEmpty()
            val method = if (hasMedia) ExtractionMethod.DOCX_MULTIMODAL else ExtractionMethod.DOCX_XML
            val status = if (parsed.unsupportedVisualCount > 0) ExtractionStatus.PARTIAL else ExtractionStatus.COMPLETE
            val disclosure = buildString {
                append("Extracted complete DOCX content (${parsed.blockCount} blocks across ${parsed.sections.size} section(s)")
                if (parsed.headerFooterCount > 0) append(", plus ${parsed.headerFooterCount} header/footer/footnote part(s)")
                if (hasMedia) append(", plus ${parsed.embeddedMedia.size} embedded image(s) queued for Gemini visual analysis")
                append(").")
                if (parsed.unsupportedVisualCount > 0) {
                    append(" Limitation: ${parsed.unsupportedVisualCount} legacy vector graphic(s) (EMF/WMF/TIFF) could not be rasterized locally.")
                }
            }

            ExtractedDocumentResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = localFilePath,
                status = status,
                method = method,
                disclosure = disclosure,
                pageCount = parsed.sections.size.coerceAtLeast(1),
                sections = parsed.sections,
                combinedText = formatSectionsForPreview(parsed.sections),
                requiresMultimodalOcr = false,
                embeddedMedia = parsed.embeddedMedia,
            )
        } catch (e: Exception) {
            failedResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = localFilePath,
                method = ExtractionMethod.DOCX_XML,
                disclosure = "Failed to extract DOCX content: ${e.localizedMessage ?: "corrupted or encrypted ZIP/XML archive"}.",
            )
        }
    }

    // ------------------------------------------------------------------------
    // 4. PPTX Complete Extractor (slides, tables, speaker notes, embedded media)
    // ------------------------------------------------------------------------
    fun extractPptxFromBytes(
        bytes: ByteArray,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long = bytes.size.toLong(),
        localFilePath: String = "",
        mediaOutputDir: File? = null,
    ): ExtractedDocumentResult {
        return try {
            val parsed = OoxmlDocumentParsers.parsePptx(bytes, mediaOutputDir)
            val status = if (parsed.unsupportedVisualCount > 0) ExtractionStatus.PARTIAL else ExtractionStatus.COMPLETE
            val disclosure = buildString {
                append("Extracted ${parsed.slideCount} PPTX slide(s)")
                if (parsed.notesCount > 0) append(" including ${parsed.notesCount} speaker notes section(s)")
                if (parsed.embeddedMedia.isNotEmpty()) append(" and ${parsed.embeddedMedia.size} embedded visual(s) for Gemini multimodal analysis")
                append(".")
                if (parsed.unsupportedVisualCount > 0) {
                    append(" Limitation: ${parsed.unsupportedVisualCount} legacy vector graphic(s) could not be rasterized locally.")
                }
            }
            ExtractedDocumentResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = localFilePath,
                status = status,
                method = ExtractionMethod.PPTX_XML,
                disclosure = disclosure,
                pageCount = parsed.slideCount,
                sections = parsed.sections,
                combinedText = formatSectionsForPreview(parsed.sections),
                requiresMultimodalOcr = false,
                embeddedMedia = parsed.embeddedMedia,
            )
        } catch (e: Exception) {
            failedResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = localFilePath,
                method = ExtractionMethod.PPTX_XML,
                disclosure = "Failed to extract PPTX presentation: ${e.localizedMessage ?: "corrupted archive"}.",
            )
        }
    }

    // ------------------------------------------------------------------------
    // 5. XLSX Complete Extractor (worksheets, shared strings, values, formulas)
    // ------------------------------------------------------------------------
    fun extractXlsxFromBytes(
        bytes: ByteArray,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long = bytes.size.toLong(),
        localFilePath: String = "",
    ): ExtractedDocumentResult {
        return try {
            val parsed = OoxmlDocumentParsers.parseXlsx(bytes)
            if (parsed.sections.isEmpty()) {
                return failedResult(
                    title = title,
                    fileName = fileName,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    localFilePath = localFilePath,
                    method = ExtractionMethod.XLSX_XML,
                    disclosure = "XLSX worksheets contained no populated cells.",
                )
            }
            val disclosure = buildString {
                append("Extracted ${parsed.sheetCount} worksheet(s) and ${parsed.totalDataRows} data row(s)")
                if (parsed.formulaCount > 0) append(" including ${parsed.formulaCount} Excel formula cell(s)")
                append(" from XLSX workbook.")
            }
            ExtractedDocumentResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = localFilePath,
                status = ExtractionStatus.COMPLETE,
                method = ExtractionMethod.XLSX_XML,
                disclosure = disclosure,
                pageCount = parsed.sheetCount,
                sections = parsed.sections,
                combinedText = formatSectionsForPreview(parsed.sections),
                requiresMultimodalOcr = false,
            )
        } catch (e: Exception) {
            failedResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = localFilePath,
                method = ExtractionMethod.XLSX_XML,
                disclosure = "Failed to extract XLSX spreadsheet: ${e.localizedMessage ?: "corrupted archive"}.",
            )
        }
    }

    // ------------------------------------------------------------------------
    // 5b. Universal Format Extractors (ODT/ODS/ODP, EPUB, HTML, RTF, Legacy OLE2, ZIP, Audio/Video)
    // ------------------------------------------------------------------------
    fun extractOpenDocumentFromBytes(
        bytes: ByteArray,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long = bytes.size.toLong(),
        localFilePath: String = "",
    ): ExtractedDocumentResult = runCatching {
        val sections = OoxmlDocumentParsers.parseOpenDocumentZip(bytes)
        if (sections.isEmpty()) {
            failedResult(
                title, fileName, mimeType, sizeBytes, localFilePath, ExtractionMethod.DIRECT_TEXT,
                "OpenDocument archive opened, but contained no readable text paragraphs.",
            )
        } else {
            ExtractedDocumentResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = localFilePath,
                status = ExtractionStatus.COMPLETE,
                method = ExtractionMethod.DIRECT_TEXT,
                disclosure = "Extracted OpenDocument content across ${sections.size} section(s).",
                pageCount = sections.size,
                sections = sections,
                combinedText = formatSectionsForPreview(sections),
                requiresMultimodalOcr = false,
            )
        }
    }.getOrElse { err ->
        failedResult(
            title, fileName, mimeType, sizeBytes, localFilePath, ExtractionMethod.DIRECT_TEXT,
            "Failed to parse OpenDocument archive: ${err.localizedMessage ?: "corrupted file"}.",
        )
    }

    fun extractEpubFromBytes(
        bytes: ByteArray,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long = bytes.size.toLong(),
        localFilePath: String = "",
    ): ExtractedDocumentResult = runCatching {
        val sections = OoxmlDocumentParsers.parseEpubZip(bytes)
        if (sections.isEmpty()) {
            failedResult(
                title, fileName, mimeType, sizeBytes, localFilePath, ExtractionMethod.DIRECT_TEXT,
                "EPUB archive opened, but no readable XHTML/HTML chapter text was found.",
            )
        } else {
            ExtractedDocumentResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = localFilePath,
                status = ExtractionStatus.COMPLETE,
                method = ExtractionMethod.DIRECT_TEXT,
                disclosure = "Extracted ${sections.size} EPUB chapter(s).",
                pageCount = sections.size,
                sections = sections,
                combinedText = formatSectionsForPreview(sections),
                requiresMultimodalOcr = false,
            )
        }
    }.getOrElse { err ->
        failedResult(
            title, fileName, mimeType, sizeBytes, localFilePath, ExtractionMethod.DIRECT_TEXT,
            "Failed to parse EPUB archive: ${err.localizedMessage ?: "corrupted archive"}.",
        )
    }

    fun extractHtmlFromBytes(
        bytes: ByteArray,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long = bytes.size.toLong(),
        localFilePath: String = "",
    ): ExtractedDocumentResult {
        val raw = decodeTextAuto(bytes)
        val plain = OoxmlDocumentParsers.stripHtmlToPlainText(raw).trim()
        if (plain.isEmpty()) {
            return failedResult(
                title, fileName, mimeType, sizeBytes, localFilePath, ExtractionMethod.DIRECT_TEXT,
                "HTML document contained no visible text after stripping markup.",
            )
        }
        val sections = splitTextIntoSections(plain)
        return ExtractedDocumentResult(
            title = title,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            localFilePath = localFilePath,
            status = ExtractionStatus.COMPLETE,
            method = ExtractionMethod.DIRECT_TEXT,
            disclosure = "Extracted visible HTML text (${plain.length} characters across ${sections.size} section(s)).",
            pageCount = sections.size,
            sections = sections,
            combinedText = formatSectionsForPreview(sections),
            requiresMultimodalOcr = false,
        )
    }

    fun extractRtfFromBytes(
        bytes: ByteArray,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long = bytes.size.toLong(),
        localFilePath: String = "",
    ): ExtractedDocumentResult {
        val raw = decodeTextAuto(bytes)
        val plain = OoxmlDocumentParsers.stripRtfToPlainText(raw).trim()
        if (plain.isEmpty()) {
            return failedResult(
                title, fileName, mimeType, sizeBytes, localFilePath, ExtractionMethod.DIRECT_TEXT,
                "RTF document contained no readable text.",
            )
        }
        val sections = splitTextIntoSections(plain)
        return ExtractedDocumentResult(
            title = title,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            localFilePath = localFilePath,
            status = ExtractionStatus.COMPLETE,
            method = ExtractionMethod.DIRECT_TEXT,
            disclosure = "Extracted RTF text (${plain.length} characters across ${sections.size} section(s)).",
            pageCount = sections.size,
            sections = sections,
            combinedText = formatSectionsForPreview(sections),
            requiresMultimodalOcr = false,
        )
    }

    fun extractLegacyOfficeFromBytes(
        bytes: ByteArray,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long = bytes.size.toLong(),
        localFilePath: String = "",
    ): ExtractedDocumentResult {
        val sections = OoxmlDocumentParsers.extractLegacyOle2ReadableText(bytes)
        if (sections.isEmpty()) {
            return failedResult(
                title, fileName, mimeType, sizeBytes, localFilePath, ExtractionMethod.DIRECT_TEXT,
                "Legacy binary Office file (.doc/.xls/.ppt) could not be read cleanly. Please save or export as .docx/.xlsx/.pptx or PDF for full structured extraction.",
            )
        }
        return ExtractedDocumentResult(
            title = title,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            localFilePath = localFilePath,
            status = ExtractionStatus.PARTIAL,
            method = ExtractionMethod.DIRECT_TEXT,
            disclosure = "Partial legacy Office binary extraction (${sections.size} section(s)). Formatting, tables, or embedded objects from legacy 97–2003 formats may be incomplete; converting to .docx/.xlsx/.pptx gives highest fidelity.",
            pageCount = sections.size,
            sections = sections,
            combinedText = formatSectionsForPreview(sections),
            requiresMultimodalOcr = false,
        )
    }

    fun extractGenericZipFromBytes(
        bytes: ByteArray,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long = bytes.size.toLong(),
        localFilePath: String = "",
    ): ExtractedDocumentResult = runCatching {
        val sections = OoxmlDocumentParsers.parseGenericZipTextEntries(bytes)
        if (sections.isEmpty()) {
            failedResult(
                title, fileName, mimeType, sizeBytes, localFilePath, ExtractionMethod.DIRECT_TEXT,
                "ZIP archive opened, but contained no readable text files. Original archive is preserved for download.",
            )
        } else {
            ExtractedDocumentResult(
                title = title,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localFilePath = localFilePath,
                status = ExtractionStatus.PARTIAL,
                method = ExtractionMethod.DIRECT_TEXT,
                disclosure = "Extracted ${sections.size} readable text entry(s) from ZIP archive (non-text binary entries skipped).",
                pageCount = sections.size,
                sections = sections,
                combinedText = formatSectionsForPreview(sections),
                requiresMultimodalOcr = false,
            )
        }
    }.getOrElse { err ->
        failedResult(
            title, fileName, mimeType, sizeBytes, localFilePath, ExtractionMethod.DIRECT_TEXT,
            "Failed to read ZIP archive: ${err.localizedMessage ?: "corrupted or password-protected archive"}.",
        )
    }

    private fun inspectMediaForMultimodal(
        file: File,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
    ): ExtractedDocumentResult {
        val maxInlineBytes = 18L * 1024L * 1024L
        if (sizeBytes > maxInlineBytes) {
            return failedResult(
                title, fileName, mimeType, sizeBytes, file.absolutePath, ExtractionMethod.MEDIA_MULTIMODAL,
                "Audio/video file is ${sizeBytes / (1024 * 1024)} MB, exceeding the 18 MB inline multimodal limit. Original file is preserved for viewing and download.",
            )
        }
        return ExtractedDocumentResult(
            title = title,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            localFilePath = file.absolutePath,
            status = ExtractionStatus.PENDING_OCR,
            method = ExtractionMethod.MEDIA_MULTIMODAL,
            disclosure = "Verified $mimeType media (${sizeBytes / 1024} KB). Queued for Firebase AI Logic multimodal transcription & analysis.",
            pageCount = 1,
            sections = emptyList(),
            combinedText = "",
            requiresMultimodalOcr = true,
        )
    }

    // ------------------------------------------------------------------------
    // 6. PDF Inspection & Page Rendering (Multimodal PDF + Scanned Page OCR)
    // ------------------------------------------------------------------------
    private fun inspectPdfForMultimodalOcr(
        file: File,
        rawBytes: ByteArray,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
    ): ExtractedDocumentResult {
        val hasPdfMagic = rawBytes.size >= 5 &&
            rawBytes[0] == '%'.code.toByte() &&
            rawBytes[1] == 'P'.code.toByte() &&
            rawBytes[2] == 'D'.code.toByte() &&
            rawBytes[3] == 'F'.code.toByte() &&
            rawBytes[4] == '-'.code.toByte()

        val pageCountResult = countPdfPages(file)
        val pageCount = pageCountResult.getOrNull() ?: 0

        if (pageCount <= 0) {
            if (!hasPdfMagic) {
                return failedResult(
                    title = title,
                    fileName = fileName,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    localFilePath = file.absolutePath,
                    method = ExtractionMethod.PDF_MULTIMODAL_OCR,
                    disclosure = "File has a .pdf extension but does not start with a valid %PDF- header.",
                )
            }
            // Android's PdfRenderer can fail on certain PDF 2.0 / linearized / unusual encodings
            // even when Gemini's native inline PDF engine can read them completely.
            return ExtractedDocumentResult(
                title = title,
                fileName = fileName,
                mimeType = "application/pdf",
                sizeBytes = sizeBytes,
                localFilePath = file.absolutePath,
                status = ExtractionStatus.PENDING_OCR,
                method = ExtractionMethod.PDF_MULTIMODAL_OCR,
                disclosure = "Valid PDF header detected (local page renderer unavailable: ${pageCountResult.exceptionOrNull()?.localizedMessage ?: "fallback mode"}). Queued for native Gemini multimodal PDF extraction.",
                pageCount = 1,
                sections = emptyList(),
                combinedText = "",
                requiresMultimodalOcr = true,
            )
        }
        return ExtractedDocumentResult(
            title = title,
            fileName = fileName,
            mimeType = "application/pdf",
            sizeBytes = sizeBytes,
            localFilePath = file.absolutePath,
            status = ExtractionStatus.PENDING_OCR,
            method = ExtractionMethod.PDF_MULTIMODAL_OCR,
            disclosure = "Verified $pageCount-page PDF. Queued for Firebase AI Logic multimodal page-by-page extraction & OCR.",
            pageCount = pageCount,
            sections = emptyList(),
            combinedText = "",
            requiresMultimodalOcr = true,
        )
    }

    fun countPdfPages(file: File): Result<Int> = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    }

    fun renderPdfPageBatch(
        file: File,
        startPageIndex: Int,
        endPageIndexExclusive: Int,
        maxEdgePx: Int = 1600,
    ): Result<List<Pair<Int, Bitmap>>> = runCatching {
        val rendered = mutableListOf<Pair<Int, Bitmap>>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val end = min(endPageIndexExclusive, renderer.pageCount)
                for (pageIdx in startPageIndex until end) {
                    renderer.openPage(pageIdx).use { page ->
                        val scale = min(
                            maxEdgePx.toFloat() / max(page.width, 1),
                            maxEdgePx.toFloat() / max(page.height, 1)
                        ).coerceIn(1.0f, 2.2f)
                        val width = max(1, (page.width * scale).toInt())
                        val height = max(1, (page.height * scale).toInt())
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        rendered.add((pageIdx + 1) to bitmap)
                    }
                }
            }
        }
        rendered
    }

    // ------------------------------------------------------------------------
    // 7. Image Inspection & Safe Downsampling
    // ------------------------------------------------------------------------
    private fun inspectImageForVisualOcr(
        file: File,
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
    ): ExtractedDocumentResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        val dimNote = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Verified image (${bounds.outWidth}×${bounds.outHeight}px)"
        } else {
            "Detected image ($mimeType)"
        }
        return ExtractedDocumentResult(
            title = title,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            localFilePath = file.absolutePath,
            status = ExtractionStatus.PENDING_OCR,
            method = ExtractionMethod.IMAGE_VISUAL_OCR,
            disclosure = "$dimNote. Queued for Firebase AI Logic multimodal OCR & visual understanding.",
            pageCount = 1,
            sections = emptyList(),
            combinedText = "",
            requiresMultimodalOcr = true,
        )
    }

    fun decodeImageSafe(file: File, maxDimension: Int = MAX_IMAGE_DIMENSION): Result<Bitmap> = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image file could not be decoded." }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            ?: throw IllegalStateException("BitmapFactory returned null while decoding ${file.name}.")
    }

    // ------------------------------------------------------------------------
    // Shared Formatting & CSV Helpers
    // ------------------------------------------------------------------------
    internal fun buildTabularProfile(
        label: String,
        headers: List<String>,
        dataRows: List<List<String>>,
    ): String = buildString {
        appendLine("### $label")
        appendLine("- Total Columns: ${headers.size}")
        appendLine("- Total Data Rows: ${dataRows.size}")
        appendLine("- Columns: ${headers.joinToString(", ")}")
        if (dataRows.isNotEmpty()) {
            appendLine("- Column Statistics:")
            headers.forEachIndexed { colIdx, colName ->
                val values = dataRows.mapNotNull { row -> row.getOrNull(colIdx)?.trim()?.takeIf { it.isNotEmpty() } }
                val numeric = values.mapNotNull { parseNumericValue(it) }
                if (numeric.isNotEmpty() && numeric.size >= values.size / 2) {
                    val minVal = numeric.minOrNull() ?: 0.0
                    val maxVal = numeric.maxOrNull() ?: 0.0
                    val sumVal = numeric.sum()
                    val avgVal = sumVal / numeric.size
                    appendLine(
                        "  • $colName (numeric, ${numeric.size}/${dataRows.size} filled): " +
                            "min=${formatStat(minVal)}, max=${formatStat(maxVal)}, avg=${formatStat(avgVal)}, sum=${formatStat(sumVal)}"
                    )
                } else {
                    val distinctCount = values.distinct().size
                    val sample = values.distinct().take(4).joinToString(", ")
                    appendLine("  • $colName (text, ${values.size}/${dataRows.size} filled, $distinctCount unique): $sample")
                }
            }
        }
    }.trim()

    internal fun formatMarkdownTable(headers: List<String>, rows: List<List<String>>): String {
        val colCount = max(headers.size, rows.maxOfOrNull { it.size } ?: 0)
        if (colCount == 0) return ""
        val paddedHeaders = (0 until colCount).map { idx ->
            headers.getOrElse(idx) { "Col_${idx + 1}" }.replace("|", "/").replace("\n", " ")
        }
        return buildString {
            appendLine("| ${paddedHeaders.joinToString(" | ")} |")
            appendLine("| ${paddedHeaders.joinToString(" | ") { "---" }} |")
            rows.forEach { row ->
                val padded = (0 until colCount).map { idx ->
                    row.getOrElse(idx) { "" }.replace("|", "/").replace("\n", " ")
                }
                appendLine("| ${padded.joinToString(" | ")} |")
            }
        }.trim()
    }

    fun formatSectionsForPreview(sections: List<DocumentSection>): String =
        sections.joinToString("\n\n") { sec -> "[${sec.referenceLabel}]\n${sec.content}" }

    fun resolveMimeType(queriedMime: String?, fileName: String): String {
        if (!queriedMime.isNullOrBlank() && queriedMime != "application/octet-stream" && queriedMime != "*/*") {
            return queriedMime
        }
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "odt" -> "application/vnd.oasis.opendocument.text"
            "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
            "odp" -> "application/vnd.oasis.opendocument.presentation"
            "epub" -> "application/epub+zip"
            "doc" -> "application/msword"
            "xls" -> "application/vnd.ms-excel"
            "ppt" -> "application/vnd.ms-powerpoint"
            "rtf" -> "application/rtf"
            "csv" -> "text/csv"
            "tsv" -> "text/tab-separated-values"
            "html", "htm" -> "text/html"
            "md", "markdown" -> "text/markdown"
            "txt", "log", "ini", "cfg", "conf", "env", "toml", "properties", "gradle", "tex", "ics", "vcf", "eml" -> "text/plain"
            "json" -> "application/json"
            "xml", "svg" -> "application/xml"
            "zip" -> "application/zip"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            else -> queriedMime?.takeIf { it.isNotBlank() && it != "*/*" } ?: "application/octet-stream"
        }
    }

    private fun parseDelimitedRows(raw: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < raw.length && raw[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(ch)
                    }
                }
                ch == '"' -> inQuotes = true
                ch == delimiter -> {
                    currentRow.add(field.toString().trim())
                    field.setLength(0)
                }
                ch == '\n' -> {
                    currentRow.add(field.toString().trim())
                    field.setLength(0)
                    if (currentRow.any { it.isNotEmpty() }) rows.add(currentRow.toList())
                    currentRow.clear()
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString().trim())
            if (currentRow.any { it.isNotEmpty() }) rows.add(currentRow.toList())
        }
        return rows
    }

    private fun splitTextIntoSections(text: String): List<DocumentSection> {
        val lines = text.lines()
        val sections = mutableListOf<DocumentSection>()
        var currentLabel = "Section 1"
        val currentBuf = StringBuilder()
        var sectionIndex = 1
        val headingRegex = Regex("^#{1,6}\\s+(.+)$")

        for (line in lines) {
            val match = headingRegex.matchEntire(line.trim())
            if (match != null) {
                if (currentBuf.isNotBlank()) {
                    sections.add(DocumentSection(sectionIndex++, currentLabel, currentBuf.toString().trim()))
                    currentBuf.setLength(0)
                }
                currentLabel = "Section $sectionIndex: ${match.groupValues[1].trim().take(70)}"
                currentBuf.appendLine(line)
            } else {
                currentBuf.appendLine(line)
                if (currentBuf.length >= 4500) {
                    sections.add(DocumentSection(sectionIndex++, currentLabel, currentBuf.toString().trim()))
                    currentBuf.setLength(0)
                    currentLabel = "Section $sectionIndex"
                }
            }
        }
        if (currentBuf.isNotBlank()) {
            sections.add(DocumentSection(sectionIndex, currentLabel, currentBuf.toString().trim()))
        }
        return sections
    }

    private fun parseNumericValue(raw: String): Double? {
        val cleaned = raw.substringBefore("[=").replace("$", "").replace("€", "").replace("£", "")
            .replace(",", "").replace("%", "").trim()
        return cleaned.toDoubleOrNull()
    }

    private fun formatStat(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

    private fun openSafStreamWithVirtualFallback(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): Pair<InputStream, String?>? {
        runCatching { resolver.openInputStream(uri) }.getOrNull()?.let { return it to null }

        // Handle virtual SAF documents (e.g., Google Docs/Sheets/Slides from Google Drive)
        val openableTypes = runCatching { resolver.getStreamTypes(uri, "*/*") }.getOrNull().orEmpty()
        val preferredOrder = listOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/pdf",
            "text/plain",
            "text/csv",
        )
        val chosenMime = preferredOrder.firstOrNull { it in openableTypes } ?: openableTypes.firstOrNull()
        if (chosenMime != null) {
            val afd = runCatching { resolver.openTypedAssetFileDescriptor(uri, chosenMime, null) }.getOrNull()
            if (afd != null) {
                return afd.createInputStream() to chosenMime
            }
        }
        return null
    }

    private fun ensureExtensionForMime(fileName: String, mime: String): String {
        val ext = when (mime.lowercase(Locale.US)) {
            "application/pdf" -> "pdf"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "text/csv" -> "csv"
            "text/plain" -> "txt"
            else -> return fileName
        }
        return if (fileName.lowercase(Locale.US).endsWith(".$ext")) fileName else "$fileName.$ext"
    }

    private fun copyWithLimit(input: InputStream, destination: File, maxBytes: Long): Long {
        var total = 0L
        val buffer = ByteArray(16_384)
        FileOutputStream(destination).use { out ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break // CRITICAL: only -1 is EOF; 0 can occur on SAF/piped streams!
                if (read > 0) {
                    total += read
                    if (total > maxBytes) {
                        destination.delete()
                        throw IllegalStateException("File exceeds maximum supported size of 50 MB.")
                    }
                    out.write(buffer, 0, read)
                }
            }
        }
        return total
    }

    internal fun decodeTextAuto(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
            }
        }
        return String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun isImageFormat(ext: String, mime: String): Boolean =
        mime.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif", "avif")

    private fun isAudioOrVideoFormat(ext: String, mime: String): Boolean =
        mime.startsWith("audio/") || mime.startsWith("video/") ||
            ext in setOf("mp3", "wav", "m4a", "ogg", "flac", "mp4", "mov", "webm")

    private fun isPlainTextFormat(ext: String, mime: String): Boolean =
        mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/xml" ||
            mime == "application/javascript" ||
            mime == "application/x-yaml" ||
            ext in setOf(
                "txt", "md", "markdown", "json", "xml", "yaml", "yml", "log",
                "kt", "java", "ts", "tsx", "js", "jsx", "py", "sql", "css",
                "ini", "cfg", "conf", "env", "sh", "bat", "c", "cpp", "h", "hpp",
                "cs", "go", "rs", "swift", "dart", "rb", "php", "tex", "svg",
                "ics", "vcf", "eml", "toml", "properties", "gradle",
            )

    private fun failedResult(
        title: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        localFilePath: String,
        method: ExtractionMethod,
        disclosure: String,
    ) = ExtractedDocumentResult(
        title = title,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        localFilePath = localFilePath,
        status = ExtractionStatus.FAILED,
        method = method,
        disclosure = disclosure,
        pageCount = 0,
        sections = emptyList(),
        combinedText = "",
        requiresMultimodalOcr = false,
    )
}
