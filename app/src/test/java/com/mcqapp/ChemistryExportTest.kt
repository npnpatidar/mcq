package com.mcqapp

import com.mcqapp.data.anki.AnkiMediaPool
import com.mcqapp.data.anki.payloadFromField
import com.mcqapp.data.anki.payloadOf
import com.mcqapp.data.anki.toField
import com.mcqapp.data.docx.parseDocx
import com.mcqapp.data.export.chemElementText
import com.mcqapp.data.export.isPngDataUri
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Chemistry in the *export* layer: a ChemElement must print its readable
 * handles everywhere, carry a PNG diagram only when that diagram is a valid
 * PNG data URI, and never leak raw SVG markup. The hidden payloads (DOCX's
 * custom property and Anki's third field) must carry the element unchanged so
 * an untouched re-export is not read as an edit.
 */
class ChemistryExportTest {

    private val pngBytes = ByteArray(24).also { b ->
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        sig.copyInto(b, 0)
        'I'.code.toByte().let { b[8] = it }
        'H'.code.toByte().let { b[9] = it }
        'D'.code.toByte().let { b[10] = it }
        'R'.code.toByte().let { b[11] = it }
        b[16] = 0; b[17] = 0; b[18] = 0; b[19] = 2 // width = 2
        b[20] = 0; b[21] = 0; b[22] = 0; b[23] = 2 // height = 2
    }

    private val png = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)

    private fun chem(diagram: String = png) = ContentElement.ChemElement(
        smiles = "c1ccccc1",
        formula = "C6H6",
        caption = "Benzene ring",
        spoken = "aromatic ring",
        diagram = diagram
    )

    private fun paper(element: ContentElement): PaperDto {
        val q = QuestionDto(
            id = "q-chem",
            elements = listOf(ContentElement.TextElement("Identify:"), element),
            options = listOf(OptionDto(id = "a", text = "Benzene")),
            correctOptionIds = listOf("a")
        )
        return PaperDto(
            id = "p1",
            title = "Chemistry",
            categories = listOf(CategoryDto(id = "c1", title = "Structures", questions = listOf(q)))
        )
    }

    @Test
    fun readableTextIsCaptionFormulaThenSmiles() {
        assertEquals("Benzene ring C6H6 c1ccccc1", chemElementText(chem()))
        // Accessibility label and base64 are not printed text.
        assertEquals("c1ccccc1", chemElementText(chem().copy(caption = "", formula = "")))
        assertEquals("", chemElementText(chem().copy(smiles = "", caption = "", formula = "")))
    }

    @Test
    fun onlyAWellFormedPngDataUriIsAnEmbeddableDiagram() {
        assertTrue(isPngDataUri(png))
        assertFalse(isPngDataUri(""))
        assertFalse(isPngDataUri("https://example.com/ring.png"))
        assertFalse(isPngDataUri("data:image/png,raw-not-base64"))
        assertFalse(isPngDataUri("data:image/jpeg;base64,${Base64.getEncoder().encodeToString(pngBytes)}"))
        // SVG is text rendered by the on-screen agent, never a raster embedding.
        val svg = Base64.getEncoder().encodeToString("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray())
        assertFalse(isPngDataUri("data:image/svg+xml;base64,$svg"))
        // The renderer's strict prefix and bare-base64 rules are shared: mixed
        // case, an empty payload and an attribute-breaking character all fail.
        assertFalse(isPngDataUri("DATA:IMAGE/PNG;BASE64,iVBORw0KGgo"))
        assertFalse(isPngDataUri("data:image/png;base64,"))
        assertFalse(isPngDataUri("data:image/png;base64,AA\" onerror=\"alert(1)"))
    }

    @Test
    fun htmlPrintsHandlesAndTheDiagramWithAccessibleAlt() {
        val html = htmlOf(paper(chem()))
        assertTrue(html.contains("Benzene ring"))
        assertTrue(html.contains("C6H6"))
        assertTrue(html.contains("c1ccccc1"))
        // Spoken copy is the accessible label; caption alone is not enough.
        assertTrue(html.contains("<img src=\"$png\" alt=\"aromatic ring C6H6\">"))
        assertFalse(html.contains("<svg"))
    }

    @Test
    fun diagramAltIsNotEmptyWhenCaptionIsBlank() {
        val noCaption = chem().copy(caption = "", spoken = "")
        val html = htmlOf(paper(noCaption))
        assertTrue(html.contains("<img src=\"$png\" alt=\"c1ccccc1 C6H6\">"))
        val spokenOnly = chem().copy(caption = "", formula = "", diagram = png)
        assertTrue(
            htmlOf(paper(spokenOnly)).contains(
                "<img src=\"$png\" alt=\"aromatic ring\">"
            )
        )
    }

    @Test
    fun svgAndJpegDiagramsNeverProduceADataImageTag() {
        val jpeg = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(pngBytes)
        val bad = listOf(
            "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString("<svg onload='x()'/>".toByteArray()),
            jpeg,
            "data:image/png,raw-not-base64",
            "data:image/png;base64,AA\" onerror=\"alert(1)"
        )
        bad.forEach { diagram ->
            val html = htmlOf(paper(chem(diagram)))
            assertTrue("text fallback for $diagram", html.contains("Benzene ring"))
            assertFalse("an invalid diagram must not become an image: $diagram",
                html.contains("<img src=\"data:"))
            assertFalse("no raw SVG: $diagram", html.contains("<svg"))
        }
    }

    @Test
    fun htmlWithAnSvgDiagramKeepsTextButNeverEmitsRawSvg() {
        val svg = "data:image/svg+xml;base64," +
            Base64.getEncoder().encodeToString("<svg onload='bad()'/>".toByteArray())
        val html = htmlOf(paper(chem(svg)))
        assertTrue(html.contains("Benzene ring"))
        assertTrue(html.contains("c1ccccc1"))
        assertFalse("raw SVG must not be inlined in the page", html.contains("<svg"))
        assertFalse(html.contains("onload"))
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    @Test
    fun docxEmbedsTheDiagramAndPrintsTheHandles() {
        val files = entries(docxOf(paper(chem())))
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue(xml.contains("Benzene ring"))
        assertTrue(xml.contains("C6H6"))
        assertTrue(xml.contains("c1ccccc1"))
        val media = files.filterKeys { it.startsWith("word/media/") }
        assertEquals(1, media.size)
        assertTrue(media.values.single().contentEquals(pngBytes))
        assertTrue(files.getValue("word/_rels/document.xml.rels").toString(Charsets.UTF_8)
            .contains("image"))
    }

    @Test
    fun docxWithAnInvalidDiagramKeepsTheTextAndEmbedsNoPicture() {
        val files = entries(docxOf(paper(chem("data:image/png,not-base64"))))
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue(xml.contains("Benzene ring"))
        assertTrue(xml.contains("c1ccccc1"))
        assertTrue("a broken diagram must not create a media entry",
            files.keys.none { it.startsWith("word/media/") })
    }

    @Test
    fun untouchedChemDocxRereadsWithoutAStalePayloadWarning() {
        val original = paper(chem())
        val parsed = parseDocx(docxOf(original))
        assertTrue("an untouched export must not look edited: ${parsed.warnings}",
            parsed.warnings.isEmpty())
        val restored = LegacyParser.parse(parsed.json).papers.single()
            .categories.single().questions.single()
        assertEquals(
            "the hidden payload must carry the whole ChemElement",
            listOf(chem()),
            restored.elements.filterIsInstance<ContentElement.ChemElement>()
        )
    }

    @Test
    fun ankiFieldSpoolsThePngUnderItsFilenameAndPrintsTheHandles() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(listOf(chem()))
            assertTrue(html.contains("Benzene ring"))
            assertTrue(html.contains("C6H6"))
            assertTrue(html.contains("c1ccccc1"))
            assertEquals(1, pool.size)
            assertEquals("mcqapp-0.png", pool.manifest.values.single())
            val bytes = java.io.ByteArrayOutputStream()
            pool.copyTo(pool.names.single(), bytes)
            assertTrue(bytes.toByteArray().contentEquals(pngBytes))
        } finally {
            pool.close()
        }
    }

    @Test
    fun ankiFieldWithAnInvalidDiagramPrintsTextAndSpoolsNothing() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(listOf(chem("https://example.com/ring.png")))
            assertTrue(html.contains("Benzene ring"))
            assertEquals(0, pool.size)
        } finally {
            pool.close()
        }
    }

    @Test
    fun ankiMcqPayloadCarriesTheChemElementUnchanged() {
        val front = chem()
        val back = chem().copy(smiles = "CC(=O)O", caption = "Acetic acid")
        val question = Question(
            id = "q-chem",
            categoryId = "c1",
            elements = listOf(ContentElement.TextElement("Draw"), front),
            options = listOf(QuestionOption("a", listOf(ContentElement.TextElement("A")))),
            correctOptionIds = setOf("a"),
            explanationElements = listOf(back)
        )
        val roundTripped = payloadFromField(payloadOf(question).toField())!!
        assertEquals(listOf(front), roundTripped.elements.filterIsInstance<ContentElement.ChemElement>())
        assertEquals(listOf(back), roundTripped.explanationElements
            .filterIsInstance<ContentElement.ChemElement>())
    }
}
