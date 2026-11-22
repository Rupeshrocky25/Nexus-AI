package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.HybridAiService
import com.example.data.extraction.DocumentExtractionEngine
import com.example.data.model.DocumentAnalysis
import com.example.data.model.DocumentSection
import com.example.data.model.ExtractionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name resource matches Nexus AI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Nexus AI", appName)
    }

    @Test
    fun `device capability probe truthfully reports unsupported Robolectric environment`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = HybridAiService()
        val status = service.probeDeviceCapability(context)
        assertFalse("Robolectric JVM environment must not falsely claim AICore installed", status.aiCorePackageInstalled)
        assertTrue("Probe headline should report truthful fallback status", status.statusHeadline.contains("Cloud Fallback"))
    }

    @Test
    fun `persisted document sections round trip cleanly on app-private storage`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sections = listOf(
            DocumentSection(1, "Page 1", "First page verified content."),
            DocumentSection(2, "Page 2", "Second page table metrics: revenue=42000."),
        )
        DocumentExtractionEngine.persistExtractedSections(context, "test_doc_42", sections)
        val loaded = DocumentExtractionEngine.loadExtractedSections(context, "test_doc_42")
        assertEquals(2, loaded.size)
        assertEquals("Page 1", loaded[0].referenceLabel)
        assertTrue(loaded[1].content.contains("revenue=42000"))

        DocumentExtractionEngine.deleteLocalArtifacts(context, "test_doc_42", null)
        assertTrue(DocumentExtractionEngine.loadExtractedSections(context, "test_doc_42").isEmpty())
    }

    @Test
    fun `unsupported binary file returns honest FAILED status while preserving original`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(DocumentExtractionEngine.originalsDirectory(context), "sample.bin")
        file.writeBytes(byteArrayOf(0x00, 0x01, 0x7F, 0x44))

        val res = DocumentExtractionEngine.extractFromPreservedFile(
            file = file,
            title = "Unknown Binary",
            fileName = "sample.bin",
            mimeType = "application/octet-stream",
            sizeBytes = file.length(),
        )
        assertEquals(ExtractionStatus.FAILED, res.status)
        assertTrue(res.combinedText.isEmpty())
        assertTrue(file.exists())
        file.delete()
    }

    @Test
    fun `DocumentAnalysis fromFirestoreMap safely parses native Firestore maps and lists`() {
        val rawMap: Map<String, Any?> = mapOf(
            "userId" to "uid-1",
            "itemId" to "item-1",
            "summary" to "Grounded summary.",
            "detailedExplanation" to "Thorough section-by-section breakdown of actual extracted text.",
            "conclusions" to listOf("Zero-trust rules are active.", "All sections verified."),
            "extractionStatus" to "COMPLETE",
            "extractionMethod" to "PDF_MULTIMODAL_OCR",
            "extractionDisclosure" to "Extracted 5 pages.",
            "chunksProcessed" to 2L,
            "totalChunks" to 2L,
            "mainTopics" to listOf("Hybrid AI", "RAG"),
            "sourceFacts" to listOf(mapOf("fact" to "Uses App Check", "sourceRef" to "Page 2")),
            "aiInterpretations" to listOf("Reduces unauthorized traffic."),
            "definitions" to listOf(mapOf("term" to "RAG", "explanation" to "Retrieval-Augmented Generation", "sourceRef" to "Page 1")),
            "entities" to listOf(mapOf("name" to "Firebase", "type" to "Organization", "context" to "Backend", "sourceRef" to "Page 1")),
            "tableInsights" to listOf(mapOf("title" to "Latency", "finding" to "Median 210ms", "sourceRef" to "Page 4")),
            "actionItems" to listOf(mapOf("action" to "Deploy rules", "priority" to "High", "sourceRef" to "Page 5")),
            "groundedQa" to listOf(mapOf("question" to "What backend?", "answer" to "Firebase Firestore", "sourceRef" to "Page 1")),
            "extractedPreview" to "[Page 1]\nSample text",
            "requestedMode" to "PREFER_IN_CLOUD",
            "actualSource" to "IN_CLOUD",
            "modelName" to "gemini-3.8-flash",
            "latencyMs" to 1200L,
        )

        val parsed = DocumentAnalysis.fromFirestoreMap("item-1", rawMap)
        assertEquals("Grounded summary.", parsed.summary)
        assertEquals("Thorough section-by-section breakdown of actual extracted text.", parsed.detailedExplanation)
        assertEquals(2, parsed.conclusions.size)
        assertEquals(2L, parsed.chunksProcessed)
        assertEquals(1, parsed.sourceFacts.size)
        assertEquals("Page 2", parsed.sourceFacts.first().sourceRef)
        assertEquals("High", parsed.actionItems.first().priority)
        assertEquals("gemini-3.8-flash", parsed.modelName)
    }
}
