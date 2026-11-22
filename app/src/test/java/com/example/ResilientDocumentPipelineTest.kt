package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.AiGenerationOutput
import com.example.data.ai.DocumentAiExecutor
import com.example.data.ai.DocumentIntelligenceService
import com.example.data.ai.ExecutorKind
import com.example.data.extraction.DocumentCheckpointStore
import com.example.data.extraction.DocumentChunker
import com.example.data.extraction.DocumentExtractionEngine
import com.example.data.extraction.OoxmlDocumentParsers
import com.example.data.model.ActualInferenceSource
import com.example.data.model.ChunkProcessingStatus
import com.example.data.model.DocumentSection
import com.example.data.model.ExtractionMethod
import com.example.data.model.ExtractionStatus
import com.example.data.model.VaultItem
import com.example.data.model.VaultItemType
import com.example.data.repository.FileExportHelper
import org.xml.sax.helpers.AttributesImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 8 Automated Verification Suite covering all 10 resilient document-intelligence scenarios:
 *  1. Short DOCX
 *  2. Large DOCX (multi-chunk streaming + checkpoints)
 *  3. DOCX with tables, headers, footers, and footnotes
 *  4. DOCX with embedded raster images + unsupported vector disclosure
 *  5. PPTX presentation (numeric slide order, tables, speaker notes, embedded images)
 *  6. Scanned PDF (inline multimodal streaming OCR) & Long PDF (12+ page multi-chunk analysis)
 *  7. Large XLSX spreadsheet with formulas (<f>) & cached values (<v>)
 *  8. Unsupported & corrupted files (never fabricates from filename)
 *  9. Simulated network interruption + persistent disk checkpoint resume (zero duplicate work)
 * 10. Simulated model timeout, HTTP 429 backoff retry, HTTP 413 auto-split, cancellation checkpointing,
 *     and strict coverage honesty (NEVER reports COMPLETE when any chunk or visual failed)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResilientDocumentPipelineTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        tempDir = File(context.cacheDir, "pipeline_test_${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::tempDir.isInitialized && tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------------
    // Scenario 1: Short DOCX (magic-byte MIME detection + streaming synthesis)
    // ------------------------------------------------------------------------
    @Test
    fun `scenario 1 - short DOCX extracts via magic bytes and streams COMPLETE grounded report`() = runBlocking {
        val docxXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                  <w:r><w:t>Project Aurora Release Charter</w:t></w:r>
                </w:p>
                <w:p>
                  <w:r><w:t>Launch window is fixed for October 12, 2026 with a latency SLA under 250ms.</w:t></w:r>
                </w:p>
                <w:p>
                  <w:r><w:t>Security sign-off by Priya Nair is mandatory prior to production traffic cutover.</w:t></w:r>
                </w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        // Intentionally give a generic extensionless filename to prove magic-byte MIME sniffing works!
        val docxFile = File(tempDir, "uploaded_blob_001")
        docxFile.writeBytes(buildZipBytes(mapOf("word/document.xml" to docxXml.toByteArray(StandardCharsets.UTF_8))))

        val extracted = DocumentExtractionEngine.extractFromPreservedFile(
            file = docxFile,
            title = "Aurora Charter",
            fileName = "uploaded_blob_001",
            mimeType = "application/octet-stream",
            sizeBytes = docxFile.length(),
        )

        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", extracted.mimeType)
        assertEquals(ExtractionStatus.COMPLETE, extracted.status)
        assertEquals(ExtractionMethod.DOCX_XML, extracted.method)
        assertTrue(extracted.combinedText.contains("October 12, 2026"))
        assertTrue(extracted.combinedText.contains("250ms"))

        val chunks = DocumentChunker.chunkSections(extracted.sections)
        assertEquals(1, chunks.size)

        var sawStreamingDelta = false
        val executor = FakeDocumentAiExecutor(
            onStructured = { prompt ->
                assertTrue("Final prompt must include extracted content", prompt.contains("October 12, 2026"))
                sampleStructuredJson(
                    summary = "Project Aurora targets an October 12, 2026 release with a 250ms latency SLA.",
                    detailed = "The charter sets the release date to October 12, 2026 and requires security sign-off from Priya Nair before cutover.",
                    conclusions = listOf("Production cutover is gated on Priya Nair's security approval."),
                    fact = "Launch window is October 12, 2026 with SLA under 250ms.",
                    factRef = "Section 1: Project Aurora Release Charter",
                )
            }
        )

        val service = createFastService(executor)
        val result = service.analyzeDocumentResilient(
            userId = "user-1",
            itemId = "short_docx_1",
            title = extracted.title,
            fileName = extracted.fileName,
            extractionMethod = extracted.method,
            extractionStatus = extracted.status,
            extractionDisclosure = extracted.disclosure,
            chunks = chunks,
            fullExtractedPreview = extracted.combinedText,
            checkpointDir = tempDir,
            onProgress = { update ->
                if (update.streamingPreview.isNotBlank()) sawStreamingDelta = true
            }
        )

        assertTrue("Short DOCX analysis should succeed", result.isSuccess)
        val outcome = result.getOrThrow()
        assertFalse(outcome.hasFailedChunks)
        assertEquals(ExtractionStatus.COMPLETE, outcome.analysis.resolvedExtractionStatus())
        assertTrue(sawStreamingDelta)
        assertEquals(1L, outcome.analysis.chunksProcessed)
        assertEquals(1L, outcome.analysis.totalChunks)
        assertTrue(outcome.analysis.detailedExplanation.contains("Priya Nair"))
        assertEquals(1, outcome.analysis.conclusions.size)
        assertNull("Completed analysis should clear temporary checkpoint", DocumentCheckpointStore.loadCheckpoint(tempDir, "short_docx_1"))
    }

    // ------------------------------------------------------------------------
    // Scenario 2: Large DOCX (multi-chunk streaming + bounded 6KB chunks)
    // ------------------------------------------------------------------------
    @Test
    fun `scenario 2 - large DOCX splits into bounded 6KB chunks and completes without timeout`() = runBlocking {
        val bodyXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
            for (sec in 1..24) {
                append("""<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Chapter $sec Operations</w:t></w:r></w:p>""")
                val paragraph = "Chapter $sec records verified throughput of ${sec * 115} transactions per second across cluster node-$sec with zero packet loss during load testing. ".repeat(10)
                append("""<w:p><w:r><w:t>$paragraph</w:t></w:r></w:p>""")
            }
            append("</w:body></w:document>")
        }

        val docxBytes = buildZipBytes(mapOf("word/document.xml" to bodyXml.toByteArray(StandardCharsets.UTF_8)))
        val extracted = DocumentExtractionEngine.extractDocxFromBytes(
            bytes = docxBytes,
            title = "Large Operations Manual",
            fileName = "large_operations_manual.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )

        assertEquals(ExtractionStatus.COMPLETE, extracted.status)
        assertTrue("Large DOCX should have 24 sections", extracted.sections.size >= 24)

        val chunks = DocumentChunker.chunkSections(extracted.sections)
        assertTrue("Large DOCX (>30KB) must split into >= 5 bounded chunks", chunks.size >= 5)
        assertTrue("Every chunk must remain bounded", chunks.all { it.text.length <= DocumentChunker.DEFAULT_MAX_CHUNK_CHARS + 500 })

        val mappedChunks = mutableListOf<String>()
        val executor = FakeDocumentAiExecutor(
            onText = { prompt, kind ->
                if (kind == ExecutorKind.CHUNK_MAP) {
                    mappedChunks.add(prompt)
                    "Verified digest from chunk (${prompt.length} chars): throughput metrics captured."
                } else {
                    "Compressed intermediate digest preserving all chapter metrics."
                }
            },
            onStructured = {
                sampleStructuredJson(
                    summary = "24-chapter operations manual covering throughput across cluster nodes 1 to 24.",
                    detailed = "Each chapter documents verified cluster throughput scaling linearly from node-1 to node-24.",
                    conclusions = listOf("Zero packet loss observed across all 24 chapters."),
                    fact = "Chapter 24 recorded 2760 transactions per second.",
                    factRef = "Section 24: Chapter 24 Operations",
                )
            }
        )

        val service = createFastService(executor)
        val res = service.analyzeDocumentResilient(
            userId = "user-1",
            itemId = "large_docx_1",
            title = extracted.title,
            fileName = extracted.fileName,
            extractionMethod = extracted.method,
            extractionStatus = extracted.status,
            extractionDisclosure = extracted.disclosure,
            chunks = chunks,
            fullExtractedPreview = extracted.combinedText,
            checkpointDir = tempDir,
        )

        assertTrue(res.isSuccess)
        val outcome = res.getOrThrow()
        assertEquals(chunks.size, mappedChunks.size)
        assertEquals(chunks.size.toLong(), outcome.analysis.chunksProcessed)
        assertEquals(chunks.size.toLong(), outcome.analysis.totalChunks)
        assertEquals(ExtractionStatus.COMPLETE, outcome.analysis.resolvedExtractionStatus())
        assertEquals(ActualInferenceSource.IN_CLOUD, outcome.analysis.resolvedActualSource())
    }

    // ------------------------------------------------------------------------
    // Scenario 3: DOCX containing tables, headers, footers, and footnotes
    // ------------------------------------------------------------------------
    @Test
    fun `scenario 3 - DOCX extracts tables headers footers and footnotes cleanly`() {
        val docXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Vendor SLA Matrix</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>Tier</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>Uptime</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>Credit</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>Enterprise</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>99.99%</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>25%</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()
        val headerXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:p><w:r><w:t>CONFIDENTIAL — MASTER SERVICES AGREEMENT REV 4.2</w:t></w:r></w:p>
            </w:hdr>
        """.trimIndent()
        val footnotesXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:footnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:footnote w:id="1"><w:p><w:r><w:t>Footnote 1: Scheduled maintenance windows excluded from downtime calculation.</w:t></w:r></w:p></w:footnote>
            </w:footnotes>
        """.trimIndent()

        val bytes = buildZipBytes(
            mapOf(
                "word/document.xml" to docXml.toByteArray(StandardCharsets.UTF_8),
                "word/header1.xml" to headerXml.toByteArray(StandardCharsets.UTF_8),
                "word/footnotes.xml" to footnotesXml.toByteArray(StandardCharsets.UTF_8),
            )
        )
        val extracted = DocumentExtractionEngine.extractDocxFromBytes(
            bytes = bytes,
            title = "Vendor SLA",
            fileName = "vendor_sla.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )

        assertEquals(ExtractionStatus.COMPLETE, extracted.status)
        assertTrue(extracted.combinedText.contains("| Tier | Uptime | Credit |"))
        assertTrue(extracted.combinedText.contains("| Enterprise | 99.99% | 25% |"))
        assertTrue(extracted.sections.any { it.referenceLabel.contains("Header1") && it.content.contains("REV 4.2") })
        assertTrue(extracted.sections.any { it.referenceLabel.contains("Footnotes") && it.content.contains("Scheduled maintenance") })
    }

    // ------------------------------------------------------------------------
    // Scenario 4: DOCX with embedded images + unsupported vector disclosure
    // ------------------------------------------------------------------------
    @Test
    fun `scenario 4 - DOCX extracts embedded raster images for visual OCR and discloses unsupported EMF`() = runBlocking {
        val docXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>Figure 1 below summarizes Q3 regional revenue growth.</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val fakePngBytes = ByteArray(1024) { idx ->
            when (idx) {
                0 -> 0x89.toByte()
                1 -> 0x50.toByte()
                2 -> 0x4E.toByte()
                3 -> 0x47.toByte()
                else -> (idx % 251).toByte()
            }
        }
        val fakeEmfBytes = ByteArray(512) { 0x01 }

        val docxFile = File(tempDir, "report_with_charts.docx")
        docxFile.writeBytes(
            buildZipBytes(
                mapOf(
                    "word/document.xml" to docXml.toByteArray(StandardCharsets.UTF_8),
                    "word/media/chart1.png" to fakePngBytes,
                    "word/media/legacy_diagram.emf" to fakeEmfBytes,
                )
            )
        )

        val extracted = DocumentExtractionEngine.extractFromPreservedFile(
            file = docxFile,
            title = "Q3 Report",
            fileName = "report_with_charts.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            sizeBytes = docxFile.length(),
        )

        assertEquals(ExtractionMethod.DOCX_MULTIMODAL, extracted.method)
        assertEquals(1, extracted.embeddedMedia.size)
        assertEquals("image/png", extracted.embeddedMedia.first().mimeType)
        assertTrue(File(extracted.embeddedMedia.first().localPath).exists())
        assertTrue("Disclosure must mention skipped legacy vector graphic", extracted.disclosure.contains("1 legacy vector graphic"))

        val persistedNotes = mutableMapOf<String, String>()
        val executor = FakeDocumentAiExecutor(
            onInline = { _, mime, _ ->
                assertEquals("image/png", mime)
                "Bar Chart: EMEA +34%, North America +22%, APAC +41%."
            }
        )
        val service = createFastService(executor)
        val visualOutcome = service.analyzeEmbeddedVisuals(
            mediaList = extracted.embeddedMedia,
            existingNotes = emptyMap(),
            startingSectionIndex = extracted.sections.size + 1,
            onNotePersisted = { id, note -> persistedNotes[id] = note }
        )

        assertEquals(1, visualOutcome.succeededCount)
        assertTrue(visualOutcome.failedLabels.isEmpty())
        assertTrue(visualOutcome.visualSections.first().content.contains("APAC +41%"))
        assertEquals(1, persistedNotes.size)
    }

    // ------------------------------------------------------------------------
    // Scenario 5: PPTX presentation (numeric slide order, tables, notes, media)
    // ------------------------------------------------------------------------
    @Test
    fun `scenario 5 - PPTX extracts slides in numeric order with tables speaker notes and embedded images`() {
        val slide1Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                   xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld><p:spTree>
                <p:sp><p:txBody><a:p><a:r><a:t>NEXUS AI Architecture Deck</a:t></a:r></a:p></p:txBody></p:sp>
              </p:spTree></p:cSld>
            </p:sld>
        """.trimIndent()
        val slide2Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                   xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld><p:spTree>
                <a:tbl>
                  <a:tr><a:tc><a:txBody><a:p><a:r><a:t>Metric</a:t></a:r></a:p></a:txBody></a:tc><a:tc><a:txBody><a:p><a:r><a:t>Target</a:t></a:r></a:p></a:txBody></a:tc></a:tr>
                  <a:tr><a:tc><a:txBody><a:p><a:r><a:t>TTFT</a:t></a:r></a:p></a:txBody></a:tc><a:tc><a:txBody><a:p><a:r><a:t>&lt; 900ms</a:t></a:r></a:p></a:txBody></a:tc></a:tr>
                </a:tbl>
              </p:spTree></p:cSld>
            </p:sld>
        """.trimIndent()
        val slide10Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                   xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld><p:spTree>
                <p:sp><p:txBody><a:p><a:r><a:t>Slide Ten Roadmap Closing</a:t></a:r></a:p></p:txBody></p:sp>
              </p:spTree></p:cSld>
            </p:sld>
        """.trimIndent()
        val notes1Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <p:notes xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                     xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld><p:spTree>
                <p:sp><p:txBody><a:p><a:r><a:t>Emphasize zero-trust Firestore rules during demo.</a:t></a:r></a:p></p:txBody></p:sp>
              </p:spTree></p:cSld>
            </p:notes>
        """.trimIndent()
        val slideImage = ByteArray(1024) { if (it < 3) byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())[it] else 0x22 }

        val pptxFile = File(tempDir, "architecture_deck.pptx")
        pptxFile.writeBytes(
            buildZipBytes(
                mapOf(
                    "ppt/presentation.xml" to "<p:presentation/>".toByteArray(),
                    "ppt/slides/slide10.xml" to slide10Xml.toByteArray(StandardCharsets.UTF_8),
                    "ppt/slides/slide2.xml" to slide2Xml.toByteArray(StandardCharsets.UTF_8),
                    "ppt/slides/slide1.xml" to slide1Xml.toByteArray(StandardCharsets.UTF_8),
                    "ppt/notesSlides/notesSlide1.xml" to notes1Xml.toByteArray(StandardCharsets.UTF_8),
                    "ppt/media/diagram1.jpg" to slideImage,
                )
            )
        )

        val extracted = DocumentExtractionEngine.extractFromPreservedFile(
            file = pptxFile,
            title = "Architecture Deck",
            fileName = "architecture_deck.pptx",
            mimeType = "application/octet-stream",
            sizeBytes = pptxFile.length(),
        )

        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", extracted.mimeType)
        assertEquals(ExtractionMethod.PPTX_XML, extracted.method)
        assertEquals(ExtractionStatus.COMPLETE, extracted.status)
        assertEquals(3, extracted.sections.size)
        // Verify numeric ordering (Slide 1 -> Slide 2 -> Slide 10, NOT lexicographical Slide 1 -> Slide 10 -> Slide 2!)
        assertEquals("Slide 1", extracted.sections[0].referenceLabel)
        assertEquals("Slide 2", extracted.sections[1].referenceLabel)
        assertEquals("Slide 10", extracted.sections[2].referenceLabel)
        assertTrue(extracted.sections[0].content.contains("Speaker Notes"))
        assertTrue(extracted.sections[0].content.contains("zero-trust Firestore rules"))
        assertTrue(extracted.sections[1].content.contains("| TTFT | < 900ms |"))
        assertEquals(1, extracted.embeddedMedia.size)
        assertEquals("image/jpeg", extracted.embeddedMedia.first().mimeType)
    }

    // ------------------------------------------------------------------------
    // Scenario 6: Scanned PDF (inline multimodal streaming OCR) & Long PDF
    // ------------------------------------------------------------------------
    @Test
    fun `scenario 6 - scanned PDF streams multimodal OCR and long 12-page PDF discloses partial gaps honestly`() = runBlocking {
        // Part A: Compact scanned PDF via inline streaming multimodal OCR
        val pdfFile = File(tempDir, "scanned_invoice.pdf")
        pdfFile.writeBytes("%PDF-1.4\n%Scanned dummy bytes for inline multimodal test\n%%EOF".toByteArray(StandardCharsets.US_ASCII))
        assertEquals("application/pdf", OoxmlDocumentParsers.detectActualMimeType(pdfFile.readBytes(), "unknown.dat", null))

        val executor = FakeDocumentAiExecutor(
            onInline = { _, mime, _ ->
                assertEquals("application/pdf", mime)
                """
                [Page 1]
                # Invoice #INV-2026-884
                Total Due: $14,750.00 due November 30, 2026.

                [Page 2]
                Wire transfer terms and authorized signature [illegible].
                """.trimIndent()
            },
            onText = { _, _ -> "Factual page digest preserving all citations." },
            onStructured = {
                sampleStructuredJson(
                    summary = "Long 12-page technical specification with partial OCR coverage disclosed.",
                    detailed = "Pages 1 through 11 were fully extracted and analyzed; Page 12 could not be rendered.",
                    conclusions = listOf("Re-scan Page 12 for complete compliance verification."),
                    fact = "Section 9 requires TLS 1.3 mutual authentication.",
                    factRef = "Page 9",
                )
            }
        )
        val service = createFastService(executor)

        val ocrOutcome = service.extractPdfWithMultimodalOcr(
            localFile = pdfFile,
            fileName = "scanned_invoice.pdf",
            totalPages = 2,
            onProgress = { _, _, _, _ -> }
        )
        assertTrue(ocrOutcome.isSuccess)
        val pdfOcr = ocrOutcome.getOrThrow()
        assertEquals(ExtractionStatus.COMPLETE, pdfOcr.status)
        assertEquals(2, pdfOcr.sections.size)
        assertTrue(pdfOcr.combinedText.contains("INV-2026-884"))

        // Part B: Long 12-page PDF where Page 12 failed OCR (extractionStatus = PARTIAL)
        val longPdfSections = (1..11).map { page ->
            DocumentSection(
                index = page,
                referenceLabel = "Page $page",
                content = "Technical compliance requirement on Page $page: TLS 1.3 mutual authentication and audit logging enabled across tier-$page services. ".repeat(18)
            )
        }
        val longChunks = DocumentChunker.chunkSections(longPdfSections)
        assertTrue("11-page technical PDF should split into multiple chunks", longChunks.size >= 3)

        val longAnalysis = service.analyzeDocumentResilient(
            userId = "user-1",
            itemId = "long_pdf_12",
            title = "Long Spec PDF",
            fileName = "long_spec.pdf",
            extractionMethod = ExtractionMethod.PDF_MULTIMODAL_OCR,
            extractionStatus = ExtractionStatus.PARTIAL,
            extractionDisclosure = "Partial PDF extraction: extracted 11 of 12 pages. Unextracted ranges: Page 12 (render error).",
            chunks = longChunks,
            fullExtractedPreview = DocumentExtractionEngine.formatSectionsForPreview(longPdfSections),
            checkpointDir = tempDir,
        )
        assertTrue(longAnalysis.isSuccess)
        val analysis = longAnalysis.getOrThrow().analysis
        assertEquals(
            "Partial PDF extraction MUST NEVER be escalated to COMPLETE",
            ExtractionStatus.PARTIAL,
            analysis.resolvedExtractionStatus()
        )
        assertTrue(analysis.extractionDisclosure.contains("Page 12"))
    }

    // ------------------------------------------------------------------------
    // Scenario 7: Large XLSX spreadsheet with formulas (<f>) & cached values (<v>)
    // ------------------------------------------------------------------------
    @Test
    fun `scenario 7 - large XLSX extracts formulas cached values numeric stats and multi-sheet sections`() {
        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets>
                <sheet name="Q4_Ledger" sheetId="1" r:id="rId1"/>
                <sheet name="Summary" sheetId="2" r:id="rId2"/>
              </sheets>
            </workbook>
        """.trimIndent()
        val relsXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
              <Relationship Id="rId2" Target="worksheets/sheet2.xml"/>
            </Relationships>
        """.trimIndent()
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <si><t>LineItem</t></si>
              <si><t>CostUSD</t></si>
              <si><t>TaxUSD</t></si>
              <si><t>CloudCompute</t></si>
              <si><t>TotalLedger</t></si>
            </sst>
        """.trimIndent()

        val sheet1Xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            append("""<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row>""")
            for (r in 2..115) {
                val cost = r * 100
                val tax = r * 10
                append("""<row r="$r"><c r="A$r" t="s"><v>3</v></c><c r="B$r"><v>$cost</v></c><c r="C$r"><f>B$r*0.1</f><v>$tax</v></c></row>""")
            }
            append("</sheetData></worksheet>")
        }
        val sheet2Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                <row r="2"><c r="A2" t="s"><v>4</v></c><c r="B2"><f>SUM(Q4_Ledger!B2:B115)</f><v>666900</v></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val xlsxBytes = buildZipBytes(
            mapOf(
                "xl/workbook.xml" to workbookXml.toByteArray(StandardCharsets.UTF_8),
                "xl/_rels/workbook.xml.rels" to relsXml.toByteArray(StandardCharsets.UTF_8),
                "xl/sharedStrings.xml" to sharedStringsXml.toByteArray(StandardCharsets.UTF_8),
                "xl/worksheets/sheet1.xml" to sheet1Xml.toByteArray(StandardCharsets.UTF_8),
                "xl/worksheets/sheet2.xml" to sheet2Xml.toByteArray(StandardCharsets.UTF_8),
            )
        )

        val extracted = DocumentExtractionEngine.extractXlsxFromBytes(
            bytes = xlsxBytes,
            title = "Q4 Financial Workbook",
            fileName = "q4_financials.xlsx",
            mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )

        assertEquals(ExtractionStatus.COMPLETE, extracted.status)
        assertEquals(ExtractionMethod.XLSX_XML, extracted.method)
        assertEquals(2, extracted.pageCount)
        assertTrue("Must disclose formulas captured", extracted.disclosure.contains("115 Excel formula cell(s)"))
        assertTrue("Must include formula annotation in cell output", extracted.combinedText.contains("[=SUM(Q4_Ledger!B2:B115)]"))
        assertTrue("Must include row-level formula sample", extracted.combinedText.contains("=B2*0.1"))
        assertTrue("Large workbook should span multiple sections", extracted.sections.size >= 5)
    }

    // ------------------------------------------------------------------------
    // Scenario 8: Unsupported & corrupted files (zero filename fabrication)
    // ------------------------------------------------------------------------
    @Test
    fun `scenario 8 - corrupted docx empty file and unknown binary fail honestly without fabrication`() = runBlocking {
        // 1. Corrupted DOCX
        val badDocx = File(tempDir, "board_acquisition_secret.docx")
        badDocx.writeBytes("CORRUPTED_NOT_ZIP".toByteArray())
        val badDocxRes = DocumentExtractionEngine.extractFromPreservedFile(
            file = badDocx,
            title = "Board Acquisition Secret",
            fileName = badDocx.name,
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            sizeBytes = badDocx.length(),
        )
        assertEquals(ExtractionStatus.FAILED, badDocxRes.status)
        assertTrue(badDocxRes.sections.isEmpty())
        assertTrue(badDocxRes.combinedText.isEmpty())

        // 2. Empty 0-byte file
        val emptyFile = File(tempDir, "empty_notes.txt")
        emptyFile.writeBytes(ByteArray(0))
        val emptyRes = DocumentExtractionEngine.extractFromPreservedFile(
            file = emptyFile,
            title = "Empty Notes",
            fileName = emptyFile.name,
            mimeType = "text/plain",
            sizeBytes = 0L,
        )
        assertEquals(ExtractionStatus.FAILED, emptyRes.status)
        assertTrue(emptyRes.disclosure.contains("empty", ignoreCase = true))

        // 3. Unsupported binary
        val binFile = File(tempDir, "firmware.bin")
        binFile.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01))
        val binRes = DocumentExtractionEngine.extractFromPreservedFile(
            file = binFile,
            title = "Firmware Dump",
            fileName = binFile.name,
            mimeType = "application/octet-stream",
            sizeBytes = binFile.length(),
        )
        assertEquals(ExtractionStatus.FAILED, binRes.status)

        // 4. DocumentIntelligenceService MUST refuse to call AI when chunks are empty
        var aiWasCalled = false
        val executor = FakeDocumentAiExecutor(
            onText = { _, _ -> aiWasCalled = true; "should never run" },
            onStructured = { aiWasCalled = true; "{}" }
        )
        val service = createFastService(executor)
        val aiRes = service.analyzeDocumentResilient(
            userId = "user-1",
            itemId = "corrupt_doc",
            title = "Board Acquisition Secret",
            fileName = "board_acquisition_secret.docx",
            extractionMethod = ExtractionMethod.DOCX_XML,
            extractionStatus = ExtractionStatus.FAILED,
            extractionDisclosure = badDocxRes.disclosure,
            chunks = emptyList(),
            fullExtractedPreview = "",
            checkpointDir = tempDir,
        )
        assertTrue(aiRes.isFailure)
        assertFalse("AI executor MUST NOT be invoked when extraction produced zero chunks", aiWasCalled)
    }

    // ------------------------------------------------------------------------
    // Scenario 9: Simulated network interruption + Checkpoint Resume
    // ------------------------------------------------------------------------
    @Test
    fun `scenario 9 - network interruption saves completed chunks and resume only processes failed chunk`() = runBlocking {
        val sections = (1..3).map { i ->
            DocumentSection(
                index = i,
                referenceLabel = "Section $i",
                content = "Section $i authoritative financial policy record #$i with verified figures $${i * 25_000}. ".repeat(55)
            )
        }
        val chunks = DocumentChunker.chunkSections(sections, maxChunkChars = 4_800)
        assertEquals(3, chunks.size)
        val preview = DocumentExtractionEngine.formatSectionsForPreview(sections)

        val run1ChunkCalls = mutableMapOf<Int, Int>()
        val failingExecutor = FakeDocumentAiExecutor(
            onText = { prompt, _ ->
                val chunkNum = extractChunkNumberFromPrompt(prompt)
                run1ChunkCalls[chunkNum] = (run1ChunkCalls[chunkNum] ?: 0) + 1
                if (chunkNum == 3) {
                    throw IOException("Connection reset by peer (simulated network drop)")
                }
                "Digest for chunk $chunkNum: verified amount $${chunkNum * 25_000}."
            },
            onStructured = {
                sampleStructuredJson(
                    summary = "Partial report covering Sections 1–2 while Section 3 failed due to network drop.",
                    detailed = "Sections 1 and 2 were analyzed cleanly.",
                    conclusions = listOf("Retry Section 3 to complete coverage."),
                    fact = "Section 1 verified $25,000.",
                    factRef = "Section 1",
                )
            }
        )

        val serviceRun1 = createFastService(failingExecutor, maxRetryAttempts = 2)
        val partialResult = serviceRun1.analyzeDocumentResilient(
            userId = "user-1",
            itemId = "resume_doc_9",
            title = "Policy Handbook",
            fileName = "policy_handbook.docx",
            extractionMethod = ExtractionMethod.DOCX_XML,
            extractionStatus = ExtractionStatus.COMPLETE,
            extractionDisclosure = "Extracted 3 sections.",
            chunks = chunks,
            fullExtractedPreview = preview,
            checkpointDir = tempDir,
        )

        assertTrue("Partial run should still return partial report for chunks 1 & 2", partialResult.isSuccess)
        val partialOutcome = partialResult.getOrThrow()
        assertTrue("Must flag failed chunks", partialOutcome.hasFailedChunks)
        assertEquals(
            "CRITICAL: Must NEVER report COMPLETE when chunk 3 failed!",
            ExtractionStatus.PARTIAL,
            partialOutcome.analysis.resolvedExtractionStatus()
        )
        assertEquals(2L, partialOutcome.analysis.chunksProcessed)
        assertEquals(3L, partialOutcome.analysis.totalChunks)
        assertTrue(partialOutcome.analysis.extractionDisclosure.contains("Section 3"))
        assertEquals("Chunk 3 should have been retried maxRetryAttempts times", 2, run1ChunkCalls[3])

        // Verify checkpoint persisted cleanly on disk
        val savedCheckpoint = DocumentCheckpointStore.loadCheckpoint(tempDir, "resume_doc_9")
        assertNotNull("Checkpoint must remain on disk after partial run", savedCheckpoint)
        assertEquals(ChunkProcessingStatus.COMPLETED, savedCheckpoint!!.chunks[0].status)
        assertEquals(ChunkProcessingStatus.COMPLETED, savedCheckpoint.chunks[1].status)
        assertEquals(ChunkProcessingStatus.FAILED, savedCheckpoint.chunks[2].status)

        // Now network recovers -> Resume with onlyRetryFailed = true!
        val run2ChunkCalls = mutableMapOf<Int, Int>()
        val recoveredExecutor = FakeDocumentAiExecutor(
            onText = { prompt, _ ->
                val chunkNum = extractChunkNumberFromPrompt(prompt)
                run2ChunkCalls[chunkNum] = (run2ChunkCalls[chunkNum] ?: 0) + 1
                "Digest for chunk $chunkNum: verified amount $${chunkNum * 25_000}."
            },
            onStructured = { prompt ->
                assertTrue("Final consolidation must contain reused Chunk 1 digest", prompt.contains("25000") || prompt.contains("25,000"))
                assertTrue("Final consolidation must contain newly analyzed Chunk 3 digest", prompt.contains("75000") || prompt.contains("75,000"))
                sampleStructuredJson(
                    summary = "Complete report covering all 3 sections after seamless checkpoint resume.",
                    detailed = "Sections 1, 2, and 3 are all analyzed.",
                    conclusions = listOf("Full 100% document coverage achieved."),
                    fact = "Section 3 verified $75,000.",
                    factRef = "Section 3",
                )
            }
        )

        val serviceRun2 = createFastService(recoveredExecutor, maxRetryAttempts = 2)
        val resumedResult = serviceRun2.analyzeDocumentResilient(
            userId = "user-1",
            itemId = "resume_doc_9",
            title = "Policy Handbook",
            fileName = "policy_handbook.docx",
            extractionMethod = ExtractionMethod.DOCX_XML,
            extractionStatus = ExtractionStatus.COMPLETE,
            extractionDisclosure = "Extracted 3 sections.",
            chunks = chunks,
            fullExtractedPreview = preview,
            checkpointDir = tempDir,
            existingCheckpoint = savedCheckpoint,
            onlyRetryFailed = true,
        )

        assertTrue(resumedResult.isSuccess)
        val resumedOutcome = resumedResult.getOrThrow()
        assertEquals("Chunk 1 MUST NOT be re-analyzed on resume", 0, run2ChunkCalls[1] ?: 0)
        assertEquals("Chunk 2 MUST NOT be re-analyzed on resume", 0, run2ChunkCalls[2] ?: 0)
        assertEquals("Only failed Chunk 3 should be executed on resume", 1, run2ChunkCalls[3] ?: 0)
        assertFalse(resumedOutcome.hasFailedChunks)
        assertEquals(ExtractionStatus.COMPLETE, resumedOutcome.analysis.resolvedExtractionStatus())
        assertEquals(3L, resumedOutcome.analysis.chunksProcessed)
        assertNull("Checkpoint should be removed after reaching 100% COMPLETE", DocumentCheckpointStore.loadCheckpoint(tempDir, "resume_doc_9"))
    }

    // ------------------------------------------------------------------------
    // Scenario 10: Model timeout + HTTP 429 backoff + HTTP 413 split + Cancellation
    // ------------------------------------------------------------------------
    @Test
    fun `scenario 10 - handles transient 429 and timeout retries, HTTP 413 auto-split, persistent timeout honesty, and cancellation`() = runBlocking {
        val sections = (1..3).map { i ->
            DocumentSection(
                index = i,
                referenceLabel = "Part $i",
                content = "Part $i technical contract clause details for section $i. ".repeat(90)
            )
        }
        val chunks = DocumentChunker.chunkSections(sections, maxChunkChars = 5_400)
        assertEquals(3, chunks.size)
        val preview = DocumentExtractionEngine.formatSectionsForPreview(sections)

        // Sub-test A: Chunk 1 hits transient 429 on attempt 1 -> succeeds on attempt 2;
        //             Chunk 2 hits HTTP 413 Payload Too Large -> auto-splits into sub-parts and succeeds;
        //             Chunk 3 persistently times out -> MUST report PARTIAL (never COMPLETE)!
        var chunk1Attempts = 0
        var chunk2SplitSubCalls = 0
        var chunk3Timeouts = 0

        val chaosExecutor = FakeDocumentAiExecutor(
            onText = { prompt, _ ->
                val chunkNum = extractChunkNumberFromPrompt(prompt)
                when {
                    prompt.contains("[Sub-part ") -> {
                        chunk2SplitSubCalls++
                        "Split sub-chunk digest succeeded."
                    }
                    chunkNum == 1 -> {
                        chunk1Attempts++
                        if (chunk1Attempts == 1) {
                            throw IllegalStateException("HTTP 429 RESOURCE_EXHAUSTED rate limit exceeded")
                        }
                        "Chunk 1 digest succeeded on retry."
                    }
                    chunkNum == 2 -> {
                        throw IllegalStateException("HTTP 413 Payload Too Large: request exceeds token limit")
                    }
                    else -> {
                        chunk3Timeouts++
                        // Trigger a genuine coroutine TimeoutCancellationException
                        withTimeout(1L) {
                            kotlinx.coroutines.delay(50L)
                        }
                        "unreachable"
                    }
                }
            },
            onStructured = {
                sampleStructuredJson(
                    summary = "Partial report for Parts 1–2; Part 3 timed out.",
                    detailed = "Parts 1 and 2 succeeded via retry and payload auto-splitting.",
                    conclusions = listOf("Part 3 requires retry."),
                    fact = "Part 1 succeeded after 429 backoff.",
                    factRef = "Part 1",
                )
            }
        )

        val service = createFastService(chaosExecutor, maxRetryAttempts = 2)
        val outcome = service.analyzeDocumentResilient(
            userId = "user-1",
            itemId = "chaos_doc_10",
            title = "Enterprise Contract",
            fileName = "enterprise_contract.docx",
            extractionMethod = ExtractionMethod.DOCX_XML,
            extractionStatus = ExtractionStatus.COMPLETE,
            extractionDisclosure = "Extracted 3 parts.",
            chunks = chunks,
            fullExtractedPreview = preview,
            checkpointDir = tempDir,
        ).getOrThrow()

        assertEquals("Chunk 1 should succeed on 2nd attempt after 429 backoff", 2, chunk1Attempts)
        assertTrue("Chunk 2 HTTP 413 must auto-split into >= 2 sub-calls", chunk2SplitSubCalls >= 2)
        assertEquals("Chunk 3 should retry timeout up to maxRetryAttempts", 2, chunk3Timeouts)
        assertTrue(outcome.hasFailedChunks)
        assertTrue(
            "CRITICAL: Persistent timeout on chunk 3 MUST NEVER produce ExtractionStatus.COMPLETE",
            outcome.analysis.resolvedExtractionStatus() != ExtractionStatus.COMPLETE
        )
        assertEquals(ExtractionStatus.PARTIAL, outcome.analysis.resolvedExtractionStatus())
        assertTrue(outcome.analysis.extractionDisclosure.contains("Part 3"))

        // Sub-test B: Cooperative CancellationException preserves Chunk 1 checkpoint cleanly!
        val cancelExecutor = FakeDocumentAiExecutor(
            onText = { prompt, _ ->
                val chunkNum = extractChunkNumberFromPrompt(prompt)
                if (chunkNum == 1) {
                    "Chunk 1 completed before user pressed Cancel."
                } else {
                    throw CancellationException("User cancelled document analysis")
                }
            }
        )
        val cancelService = createFastService(cancelExecutor, maxRetryAttempts = 2)
        var cancellationPropagated = false
        try {
            cancelService.analyzeDocumentResilient(
                userId = "user-1",
                itemId = "cancel_doc_10",
                title = "Cancelable Spec",
                fileName = "cancelable.docx",
                extractionMethod = ExtractionMethod.DOCX_XML,
                extractionStatus = ExtractionStatus.COMPLETE,
                extractionDisclosure = "Extracted 3 parts.",
                chunks = chunks,
                fullExtractedPreview = preview,
                checkpointDir = tempDir,
            )
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        assertTrue("CancellationException must propagate cleanly without being swallowed", cancellationPropagated)
        val cpAfterCancel = DocumentCheckpointStore.loadCheckpoint(tempDir, "cancel_doc_10")
        assertNotNull("Checkpoint must persist completed Chunk 1 when user cancels at Chunk 2", cpAfterCancel)
        assertEquals(ChunkProcessingStatus.COMPLETED, cpAfterCancel!!.chunks[0].status)
        assertEquals(ChunkProcessingStatus.PENDING, cpAfterCancel.chunks[1].status)
    }

    // ------------------------------------------------------------------------
    // Scenario 11: Android ExpatParser SAX compatibility + Universal Format Extraction
    // ------------------------------------------------------------------------
    @Test
    fun `scenario 11 - android ExpatParser empty qName and universal formats extract cleanly without false failures`() {
        // 1. Android ExpatParser passes localName="p" and qName="" (and strips attribute qNames)
        assertEquals("p", OoxmlDocumentParsers.resolvedTagName("p", ""))
        assertEquals("p", OoxmlDocumentParsers.resolvedTagName("", "w:p"))
        assertEquals("pStyle", OoxmlDocumentParsers.resolvedTagName("pStyle", null))

        val androidStyleAttrs = AttributesImpl().apply {
            addAttribute("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val", "", "CDATA", "Heading1")
            addAttribute("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id", "", "CDATA", "rId4")
        }
        assertEquals("Heading1", OoxmlDocumentParsers.findAttr(androidStyleAttrs, "val", "w:val"))
        assertEquals("rId4", OoxmlDocumentParsers.findAttr(androidStyleAttrs, "r:id", "id"))

        // 2. UTF-16LE BOM plain text file with generic octet-stream MIME
        val utf16File = File(tempDir, "notes_utf16")
        val utf16Bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Quarterly Architecture Review: Latency dropped by 42%.".toByteArray(StandardCharsets.UTF_16LE)
        utf16File.writeBytes(utf16Bytes)
        val utf16Res = DocumentExtractionEngine.extractFromPreservedFile(
            file = utf16File,
            title = "UTF16 Notes",
            fileName = utf16File.name,
            mimeType = "application/octet-stream",
            sizeBytes = utf16File.length(),
        )
        assertEquals(ExtractionStatus.COMPLETE, utf16Res.status)
        assertTrue(utf16Res.combinedText.contains("Latency dropped by 42%"))

        // 3. Extensionless plain UTF-8 file with application/octet-stream
        val extlessFile = File(tempDir, "README")
        extlessFile.writeText("Deploy instructions: enable Firebase App Check before release.", StandardCharsets.UTF_8)
        val extlessRes = DocumentExtractionEngine.extractFromPreservedFile(
            file = extlessFile,
            title = "README",
            fileName = extlessFile.name,
            mimeType = "application/octet-stream",
            sizeBytes = extlessFile.length(),
        )
        assertEquals(ExtractionStatus.COMPLETE, extlessRes.status)
        assertTrue(extlessRes.combinedText.contains("enable Firebase App Check"))

        // 4. HTML document
        val htmlFile = File(tempDir, "report.html")
        htmlFile.writeText("<html><body><h1>Sprint Report</h1><script>ignore()</script><p>All 24 unit tests passed.</p></body></html>")
        val htmlRes = DocumentExtractionEngine.extractFromPreservedFile(
            file = htmlFile,
            title = "Sprint Report",
            fileName = htmlFile.name,
            mimeType = "text/html",
            sizeBytes = htmlFile.length(),
        )
        assertEquals(ExtractionStatus.COMPLETE, htmlRes.status)
        assertTrue(htmlRes.combinedText.contains("All 24 unit tests passed"))
        assertFalse(htmlRes.combinedText.contains("ignore()"))

        // 5. RTF document
        val rtfFile = File(tempDir, "memo.rtf")
        rtfFile.writeText("{\\rtf1\\ansi\\deff0 {\\b Release Memo}\\par Budget approved at 250000 USD.}")
        val rtfRes = DocumentExtractionEngine.extractFromPreservedFile(
            file = rtfFile,
            title = "Memo",
            fileName = rtfFile.name,
            mimeType = "application/octet-stream",
            sizeBytes = rtfFile.length(),
        )
        assertEquals(ExtractionStatus.COMPLETE, rtfRes.status)
        assertTrue(rtfRes.combinedText.contains("Budget approved at 250000 USD"))

        // 6. OpenDocument ODT archive
        val odtXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                     xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
              <office:body><office:text>
                <text:h>OpenDocument Title</text:h>
                <text:p>Verified ODT paragraph extraction works cleanly.</text:p>
              </office:text></office:body>
            </office:document-content>
        """.trimIndent()
        val odtFile = File(tempDir, "proposal.odt")
        odtFile.writeBytes(buildZipBytes(mapOf("content.xml" to odtXml.toByteArray(StandardCharsets.UTF_8))))
        val odtRes = DocumentExtractionEngine.extractFromPreservedFile(
            file = odtFile,
            title = "Proposal",
            fileName = odtFile.name,
            mimeType = "application/octet-stream",
            sizeBytes = odtFile.length(),
        )
        assertEquals(ExtractionStatus.COMPLETE, odtRes.status)
        assertTrue(odtRes.combinedText.contains("Verified ODT paragraph extraction"))

        // 7. Valid %PDF- file where local PdfRenderer cannot render -> must queue PENDING_OCR, NOT FAILED
        val pdfFallback = File(tempDir, "complex_pdf2.pdf")
        pdfFallback.writeBytes("%PDF-2.0\n%minimal header for inline multimodal test\n".toByteArray(StandardCharsets.US_ASCII))
        val pdfRes = DocumentExtractionEngine.extractFromPreservedFile(
            file = pdfFallback,
            title = "Complex PDF",
            fileName = pdfFallback.name,
            mimeType = "application/pdf",
            sizeBytes = pdfFallback.length(),
        )
        assertEquals(ExtractionStatus.PENDING_OCR, pdfRes.status)
        assertTrue("Valid PDF must fall back to native Gemini multimodal OCR", pdfRes.requiresMultimodalOcr)
    }

    // ------------------------------------------------------------------------
    // Scenario 12: FileExportHelper download preservation & viewer section loading
    // ------------------------------------------------------------------------
    @Test
    fun `scenario 12 - FileExportHelper preserves original binary bytes and falls back cleanly for notes`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val originalBytes = "EXACT_ORIGINAL_FILE_PAYLOAD_1234567890".toByteArray(StandardCharsets.UTF_8)
        val originalFile = File(tempDir, "contract_signed.pdf")
        originalFile.writeBytes(originalBytes)

        val docItem = VaultItem(
            id = "doc_export_1",
            userId = "user-1",
            itemType = VaultItemType.DOCUMENT.name,
            title = "Signed Contract",
            fileName = "contract_signed.pdf",
            mimeType = "application/pdf",
            localFilePath = originalFile.absolutePath,
            sizeBytes = originalBytes.size.toLong(),
        )
        assertTrue(FileExportHelper.hasOriginalLocalFile(docItem))
        assertEquals("contract_signed.pdf", FileExportHelper.suggestedDownloadFileName(docItem))
        assertEquals("application/pdf", FileExportHelper.suggestedDownloadMimeType(docItem))

        val outDoc = ByteArrayOutputStream()
        val writtenBytes = FileExportHelper.writeItemToStream(appContext, docItem, outDoc)
        assertEquals(originalBytes.size.toLong(), writtenBytes)
        assertEquals(String(originalBytes, StandardCharsets.UTF_8), String(outDoc.toByteArray(), StandardCharsets.UTF_8))

        // Note without local binary exports cleanly as Markdown
        val noteItem = VaultItem(
            id = "note_export_1",
            userId = "user-1",
            itemType = VaultItemType.NOTE.name,
            title = "Hybrid Architecture Notes",
            content = "On-device Gemini Nano with Firebase AI Logic cloud fallback.",
            category = "Notes",
            tags = listOf("hybrid", "firebase"),
        )
        assertFalse(FileExportHelper.hasOriginalLocalFile(noteItem))
        assertEquals("Hybrid Architecture Notes.md", FileExportHelper.suggestedDownloadFileName(noteItem))
        assertEquals("text/markdown", FileExportHelper.suggestedDownloadMimeType(noteItem))

        val outNote = ByteArrayOutputStream()
        FileExportHelper.writeItemToStream(appContext, noteItem, outNote)
        val exportedMd = String(outNote.toByteArray(), StandardCharsets.UTF_8)
        assertTrue(exportedMd.contains("# Hybrid Architecture Notes"))
        assertTrue(exportedMd.contains("On-device Gemini Nano"))
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------
    private fun createFastService(
        executor: DocumentAiExecutor,
        maxRetryAttempts: Int = 3,
    ) = DocumentIntelligenceService(
        executor = executor,
        retryBaseDelayMs = 5L,
        maxRetryAttempts = maxRetryAttempts,
        chunkTimeoutMs = 5_000L,
        synthesisTimeoutMs = 5_000L,
        ocrTimeoutMs = 5_000L,
    )

    private fun extractChunkNumberFromPrompt(prompt: String): Int {
        val match = Regex("Chunk (\\d+)/\\d+").find(prompt)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
    }

    private fun buildZipBytes(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun sampleStructuredJson(
        summary: String,
        detailed: String,
        conclusions: List<String>,
        fact: String,
        factRef: String,
    ): String {
        val conclusionsJson = conclusions.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        return """
            {
              "summary": "$summary",
              "detailedExplanation": "$detailed",
              "conclusions": [$conclusionsJson],
              "mainTopics": ["Document Intelligence", "Verification"],
              "sourceFacts": [{"fact": "$fact", "sourceRef": "$factRef"}],
              "aiInterpretations": ["Structured analysis derived strictly from extracted text."],
              "definitions": [{"term": "SLA", "explanation": "Service Level Agreement", "sourceRef": "$factRef"}],
              "entities": [{"name": "NEXUS AI", "type": "Product", "context": "Knowledge OS", "sourceRef": "$factRef"}],
              "tableInsights": [],
              "actionItems": [{"action": "Review cited obligations", "priority": "High", "sourceRef": "$factRef"}],
              "groundedQa": [{"question": "What is verified?", "answer": "$fact", "sourceRef": "$factRef"}]
            }
        """.trimIndent()
    }

    private class FakeDocumentAiExecutor(
        private val onText: suspend (prompt: String, kind: ExecutorKind) -> String = { _, _ -> "Default chunk digest." },
        private val onStructured: suspend (prompt: String) -> String = { "{}" },
        private val onInline: suspend (bytes: ByteArray, mimeType: String, prompt: String) -> String = { _, _, _ -> "[Page 1]\nExtracted text." },
        private val onBitmaps: suspend (bitmaps: List<Bitmap>, prompt: String) -> String = { _, _ -> "[Page 1]\nBitmap OCR text." },
    ) : DocumentAiExecutor {
        override suspend fun streamText(
            prompt: String,
            kind: ExecutorKind,
            timeoutMs: Long,
            onDelta: suspend (delta: String, accumulated: String) -> Unit,
        ): AiGenerationOutput {
            val out = onText(prompt, kind)
            val half = (out.length / 2).coerceAtLeast(1)
            onDelta(out.take(half), out.take(half))
            onDelta(out.drop(half), out)
            return AiGenerationOutput(out, ActualInferenceSource.IN_CLOUD, "gemini-3.8-flash", 12L)
        }

        override suspend fun streamStructuredJson(
            prompt: String,
            timeoutMs: Long,
            onDelta: suspend (delta: String, accumulated: String) -> Unit,
        ): AiGenerationOutput {
            val out = onStructured(prompt)
            val half = (out.length / 2).coerceAtLeast(1)
            onDelta(out.take(half), out.take(half))
            onDelta(out.drop(half), out)
            return AiGenerationOutput(out, ActualInferenceSource.IN_CLOUD, "gemini-3.8-flash", 18L)
        }

        override suspend fun streamMultimodalInline(
            bytes: ByteArray,
            mimeType: String,
            prompt: String,
            timeoutMs: Long,
            onDelta: suspend (delta: String, accumulated: String) -> Unit,
        ): AiGenerationOutput {
            val out = onInline(bytes, mimeType, prompt)
            onDelta(out, out)
            return AiGenerationOutput(out, ActualInferenceSource.IN_CLOUD, "gemini-3.8-flash", 15L)
        }

        override suspend fun streamMultimodalBitmaps(
            bitmaps: List<Bitmap>,
            prompt: String,
            timeoutMs: Long,
            onDelta: suspend (delta: String, accumulated: String) -> Unit,
        ): AiGenerationOutput {
            val out = onBitmaps(bitmaps, prompt)
            onDelta(out, out)
            return AiGenerationOutput(out, ActualInferenceSource.IN_CLOUD, "gemini-3.8-flash", 15L)
        }
    }
}
