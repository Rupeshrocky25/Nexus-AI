package com.example

import com.example.data.extraction.DocumentChunker
import com.example.data.extraction.DocumentExtractionEngine
import com.example.data.model.ActualInferenceSource
import com.example.data.model.AiTaskType
import com.example.data.model.DataInsight
import com.example.data.model.DocumentActionItem
import com.example.data.model.DocumentAnalysis
import com.example.data.model.DocumentSection
import com.example.data.model.ExtractedEntity
import com.example.data.model.ExtractionMethod
import com.example.data.model.ExtractionStatus
import com.example.data.model.GroundedQaPair
import com.example.data.model.HybridInferenceMode
import com.example.data.model.SourceFact
import com.example.data.model.TermDefinition
import com.example.data.model.VaultItem
import com.example.data.model.VaultItemType
import com.example.data.repository.DocumentImportHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NexusAiUnitTest {

    @Test
    fun `enum deserialization safely falls back on unknown schema values`() {
        assertEquals(VaultItemType.NOTE, VaultItemType.fromRaw("UNKNOWN_FUTURE_TYPE"))
        assertEquals(VaultItemType.DOCUMENT, VaultItemType.fromRaw("DOCUMENT"))
        assertEquals(HybridInferenceMode.PREFER_ON_DEVICE, HybridInferenceMode.fromRaw(null))
        assertEquals(HybridInferenceMode.PREFER_IN_CLOUD, HybridInferenceMode.fromRaw("PREFER_IN_CLOUD"))
        assertEquals(ActualInferenceSource.UNKNOWN, ActualInferenceSource.fromRaw("UNEXPECTED"))
        assertEquals(ActualInferenceSource.ON_DEVICE, ActualInferenceSource.fromRaw("ON_DEVICE"))
        assertEquals(AiTaskType.CHAT, AiTaskType.fromRaw("INVALID_TASK"))
        assertEquals(AiTaskType.DOC_ANALYSIS, AiTaskType.fromRaw("DOC_ANALYSIS"))
        assertEquals(ExtractionStatus.FAILED, ExtractionStatus.fromRaw("FAILED"))
        assertEquals(ExtractionMethod.DOCX_XML, ExtractionMethod.fromRaw("DOCX_XML"))
    }

    @Test
    fun `formatBytes renders human readable storage units`() {
        assertEquals("512 B", DocumentImportHelper.formatBytes(512L))
        assertEquals("2.0 KB", DocumentImportHelper.formatBytes(2048L))
        assertEquals("1.50 MB", DocumentImportHelper.formatBytes(1_572_864L))
    }

    @Test
    fun `extractPlainTextFromBytes reads complete markdown with headings and zero silent truncation`() {
        val md = buildString {
            appendLine("# Executive Overview")
            appendLine("NEXUS AI processes real content across all sections.")
            appendLine()
            appendLine("## Financial Metrics")
            appendLine("Annual recurring savings reached $48,200 in Q4.")
            appendLine()
            appendLine("## Action Plan")
            appendLine("Complete security audit by March 15.")
        }
        val res = DocumentExtractionEngine.extractPlainTextFromBytes(
            bytes = md.toByteArray(StandardCharsets.UTF_8),
            title = "Q4 Strategy",
            fileName = "q4_strategy.md",
            mimeType = "text/markdown",
        )
        assertEquals(ExtractionStatus.COMPLETE, res.status)
        assertEquals(ExtractionMethod.DIRECT_TEXT, res.method)
        assertEquals(3, res.sections.size)
        assertTrue(res.sections[0].referenceLabel.contains("Executive Overview"))
        assertTrue(res.sections[1].content.contains("$48,200"))
        assertTrue(res.sections[2].content.contains("March 15"))
    }

    @Test
    fun `extractCsvFromBytes inspects real headers quoted commas rows and numeric stats`() {
        val csv = """
            Region,Quarter,"Revenue, USD",GrowthPct
            North America,Q1,125000,12.5
            Europe,Q1,98000,9.0
            APAC,Q1,77000,18.5
        """.trimIndent()

        val res = DocumentExtractionEngine.extractCsvFromBytes(
            bytes = csv.toByteArray(StandardCharsets.UTF_8),
            title = "Q1 Sales",
            fileName = "q1_sales.csv",
            mimeType = "text/csv",
        )

        assertEquals(ExtractionStatus.COMPLETE, res.status)
        assertEquals(ExtractionMethod.CSV_PARSER, res.method)
        assertEquals(2, res.sections.size)
        assertTrue(res.sections[0].content.contains("Total Columns: 4"))
        assertTrue(res.sections[0].content.contains("Total Data Rows: 3"))
        assertTrue(res.sections[0].content.contains("Revenue, USD"))
        assertTrue(res.sections[0].content.contains("sum=300000"))
        assertTrue(res.sections[1].referenceLabel.contains("Rows 1–3"))
        assertTrue(res.sections[1].content.contains("| North America | Q1 | 125000 | 12.5 |"))
    }

    @Test
    fun `extractDocxFromBytes parses real word document xml headings paragraphs and tables`() {
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                  <w:r><w:t>Hybrid Inference Architecture</w:t></w:r>
                </w:p>
                <w:p>
                  <w:r><w:t>Local Gemini Nano runs private short-form tasks with zero cloud egress.</w:t></w:r>
                </w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>Model</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>Latency</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>Gemini Nano</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>180ms</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()

        val docxBytes = buildZipBytes(mapOf("word/document.xml" to documentXml))
        val res = DocumentExtractionEngine.extractDocxFromBytes(
            bytes = docxBytes,
            title = "Architecture Spec",
            fileName = "architecture_spec.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )

        assertEquals(ExtractionStatus.COMPLETE, res.status)
        assertEquals(ExtractionMethod.DOCX_XML, res.method)
        assertTrue(res.combinedText.contains("# Hybrid Inference Architecture"))
        assertTrue(res.combinedText.contains("zero cloud egress"))
        assertTrue(res.combinedText.contains("| Model | Latency |"))
        assertTrue(res.combinedText.contains("| Gemini Nano | 180ms |"))
    }

    @Test
    fun `extractXlsxFromBytes parses workbook sheets sharedStrings and numeric statistics`() {
        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets>
                <sheet name="Budget2026" sheetId="1" r:id="rId1"/>
              </sheets>
            </workbook>
        """.trimIndent()
        val relsXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
            </Relationships>
        """.trimIndent()
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <si><t>Department</t></si>
              <si><t>Spend</t></si>
              <si><t>Engineering</t></si>
              <si><t>Security</t></si>
            </sst>
        """.trimIndent()
        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="s"><v>0</v></c>
                  <c r="B1" t="s"><v>1</v></c>
                </row>
                <row r="2">
                  <c r="A2" t="s"><v>2</v></c>
                  <c r="B2"><v>45000</v></c>
                </row>
                <row r="3">
                  <c r="A3" t="s"><v>3</v></c>
                  <c r="B3"><v>15000</v></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val xlsxBytes = buildZipBytes(
            mapOf(
                "xl/workbook.xml" to workbookXml,
                "xl/_rels/workbook.xml.rels" to relsXml,
                "xl/sharedStrings.xml" to sharedStringsXml,
                "xl/worksheets/sheet1.xml" to sheet1Xml,
            )
        )

        val res = DocumentExtractionEngine.extractXlsxFromBytes(
            bytes = xlsxBytes,
            title = "Annual Budget",
            fileName = "annual_budget.xlsx",
            mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )

        assertEquals(ExtractionStatus.COMPLETE, res.status)
        assertEquals(ExtractionMethod.XLSX_XML, res.method)
        assertEquals(1, res.pageCount)
        assertTrue(res.combinedText.contains("Sheet: Budget2026"))
        assertTrue(res.combinedText.contains("sum=60000"))
        assertTrue(res.combinedText.contains("| Engineering | 45000 |"))
        assertTrue(res.combinedText.contains("| Security | 15000 |"))
    }

    @Test
    fun `corrupted docx reports FAILED extraction honestly and never fabricates filename text`() {
        val corruptedBytes = "NOT_A_VALID_ZIP_FILE".toByteArray(StandardCharsets.UTF_8)
        val res = DocumentExtractionEngine.extractDocxFromBytes(
            bytes = corruptedBytes,
            title = "Secret Contract 2026",
            fileName = "secret_contract_2026.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
        assertEquals(ExtractionStatus.FAILED, res.status)
        assertTrue("Failed extraction must leave combinedText empty", res.combinedText.isEmpty())
        assertTrue("Failed extraction must report zero sections", res.sections.isEmpty())
        assertFalse(res.disclosure.contains("Secret Contract 2026"))
    }

    @Test
    fun `chunkSections preserves page references and RAG retrieval ranks exact matching chunk first`() {
        val sections = (1..10).map { page ->
            val body = if (page == 7) {
                "Page 7 specifies the cryptographic rotation schedule: KMS keys rotate every 90 days under FIPS 140-3."
            } else {
                "General operational background content for page $page covering routine team updates. ".repeat(12)
            }
            DocumentSection(index = page, referenceLabel = "Page $page", content = body)
        }

        val chunks = DocumentChunker.chunkSections(sections, maxChunkChars = 1400)
        assertTrue("Large document should produce multiple chunks", chunks.size >= 4)

        val retrieved = DocumentChunker.retrieveRelevantChunks(
            query = "What is the KMS key rotation schedule under FIPS 140-3?",
            chunks = chunks,
            topK = 2,
        )
        assertEquals(2, retrieved.size)
        assertTrue(
            "Top retrieved chunks must include Page 7 containing the exact answer",
            retrieved.any { it.text.contains("rotate every 90 days") && it.text.contains("[Page 7]") }
        )
    }

    @Test
    fun `exportAnalysisAsMarkdown includes source facts separately from AI interpretations and citations`() {
        val item = VaultItem(
            id = "doc-1",
            title = "Compliance Audit",
            fileName = "compliance_audit.pdf",
            mimeType = "application/pdf",
        )
        val analysis = DocumentAnalysis(
            id = "doc-1",
            userId = "user-1",
            itemId = "doc-1",
            summary = "Audit passed with two minor remediation items.",
            extractionStatus = ExtractionStatus.COMPLETE.name,
            extractionMethod = ExtractionMethod.PDF_MULTIMODAL_OCR.name,
            extractionDisclosure = "Extracted all 4 pages.",
            chunksProcessed = 1,
            totalChunks = 1,
            mainTopics = listOf("Access Control", "Key Rotation"),
            sourceFacts = listOf(SourceFact("KMS keys rotate every 90 days.", "Page 3")),
            aiInterpretations = listOf("Current cadence aligns with enterprise security benchmarks."),
            definitions = listOf(TermDefinition("Zero Trust", "Never trust, always verify.", "Page 1")),
            entities = listOf(ExtractedEntity("FIPS 140-3", "Standard", "Cryptographic standard", "Page 3")),
            tableInsights = listOf(DataInsight("Controls Table", "18/20 controls passed", "Page 2")),
            actionItems = listOf(DocumentActionItem("Enable hardware MFA", "High", "Page 4")),
            groundedQa = listOf(GroundedQaPair("How often do keys rotate?", "Every 90 days.", "Page 3")),
            actualSource = ActualInferenceSource.IN_CLOUD.name,
            modelName = "gemini-3.8-flash",
            latencyMs = 1420L,
        )

        val md = DocumentChunker.exportAnalysisAsMarkdown(item, analysis)
        assertTrue(md.contains("## Verified Source Facts & Findings"))
        assertTrue(md.contains("## AI Interpretations & Synthesis"))
        assertTrue(md.contains("*[Page 3]*"))
        assertTrue(md.contains("Enable hardware MFA"))
        assertTrue(md.contains("Cloud • Firebase AI Logic"))
    }

    private fun buildZipBytes(entries: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
