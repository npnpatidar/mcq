package com.mcqapp

import com.mcqapp.data.anki.AnkiMediaPool
import com.mcqapp.data.anki.payloadFromField
import com.mcqapp.data.anki.payloadOf
import com.mcqapp.data.anki.toField
import com.mcqapp.data.docx.escapeXmlText
import com.mcqapp.data.docx.parseDocx
import com.mcqapp.data.escapeHtmlText
import com.mcqapp.data.export.ChemDiagramPreparation
import com.mcqapp.data.export.ChemDiagramRenderer
import com.mcqapp.data.export.DocxPaperWriter
import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.export.chemElementText
import com.mcqapp.data.export.chemProductLabel
import com.mcqapp.data.export.isPngDataUri
import com.mcqapp.data.io.CardContent
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Chemistry in the *export* layer: a ChemElement with a valid PNG prints its
 * picture everywhere (the readable handles move into alt/descr), a structure
 * without one keeps its readable text, and no raw SVG markup leaks. A reaction
 * appends its product label after the drawing. The hidden payloads (DOCX's
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

    /** The sample organic scheme: empty product, `formula` is the "A" label. */
    private fun reaction(diagram: String = png) = ContentElement.ChemElement(
        smiles = "O=C1OCCCCC(=O)C1>>",
        formula = "A",
        caption = "LiAlH₄ / ether",
        spoken = "lactone reduced by LiAlH4 and ether",
        diagram = diagram
    )

    private fun blankLabelReaction(diagram: String = png) = reaction(diagram).copy(formula = "")

    private val renderer = object : ChemDiagramRenderer {
        override suspend fun render(smiles: String, textAbove: String): String = png
    }

    /** A well-formed marker whose data-png is not a PNG: the handles must win. */
    private val invalidPngMarker: String = run {
        val payload = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        "<span class=\"mcq-chem-cell\" data-smiles=\"CCO\" data-formula=\"C2H5OH\" " +
            "data-caption=\"ethanol\" data-png=\"$payload\">ethanol C2H5OH CCO</span>"
    }

    private fun questionOf(parsedJson: String): QuestionDto =
        LegacyParser.parse(parsedJson).papers.single().categories.single().questions.single()

    private fun paper(
        element: ContentElement,
        marks: Double = 1.0,
        tags: List<String> = emptyList()
    ): PaperDto {
        val q = QuestionDto(
            id = "q-chem",
            elements = listOf(ContentElement.TextElement("Identify:"), element),
            options = listOf(OptionDto(id = "a", text = "Benzene")),
            correctOptionIds = listOf("a"),
            marks = marks,
            tags = tags
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
        // A reaction SMILES keeps its arrow in every readable export field:
        // caption plus SMILES when the formula is blank.
        assertEquals(
            "reagent CCO>>",
            chemElementText(ContentElement.ChemElement(smiles = "CCO>>", caption = "reagent"))
        )
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
    fun onlyATwoArrowSchemeCarriesTheProductLabel() {
        assertEquals("A", chemProductLabel(reaction()))
        assertEquals("a molecule has no product label", "", chemProductLabel(chem()))
        assertEquals("a blank formula has no label", "", chemProductLabel(reaction().copy(formula = "")))
        assertEquals(
            "a lone arrow is not a reaction",
            "",
            chemProductLabel(reaction().copy(smiles = "CCO>CCO", formula = "A"))
        )
    }

    @Test
    fun htmlDrawsTheDiagramAndKeepsTheHandlesInAlt() {
        val html = htmlOf(paper(chem()))
        // The picture replaces the printed handles, matching the in-app page.
        assertTrue(html.contains("<img src=\"$png\" alt=\"aromatic ring C6H6\">"))
        assertFalse(
            "the readable handles are not printed beside the picture",
            html.contains("Benzene ring C6H6 c1ccccc1")
        )
        // Spoken copy is the accessible label; caption alone is not enough.
        assertTrue(html.contains("alt=\"aromatic ring C6H6\""))
        assertFalse(html.contains("<svg"))
    }

    @Test
    fun htmlAppendsTheReactionLabelAfterThePicture() {
        val html = htmlOf(paper(reaction()))
        val imageAt = html.indexOf("<img src=\"$png\"")
        assertTrue("the reaction picture: $html", imageAt >= 0)
        val afterPicture = html.substring(html.indexOf('>', imageAt) + 1).trimStart()
        assertTrue("the product label follows the picture: $html", afterPicture.startsWith("A"))
        assertFalse(
            "the readable handles are not printed beside the picture",
            html.contains(escapeHtmlText(chemElementText(reaction())))
        )
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
    fun docxEmbedsTheDiagramAndKeepsTheHandlesInItsDescription() {
        val files = entries(docxOf(paper(chem())))
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        // The picture is the visible content; the handles live in wp:docPr.
        assertTrue(xml.contains("descr=\"aromatic ring C6H6\""))
        assertFalse(
            "the readable handles are not printed beside the picture",
            xml.contains("Benzene ring C6H6 c1ccccc1")
        )
        val media = files.filterKeys { it.startsWith("word/media/") }
        assertEquals(1, media.size)
        assertTrue(media.values.single().contentEquals(pngBytes))
        assertTrue(files.getValue("word/_rels/document.xml.rels").toString(Charsets.UTF_8)
            .contains("image"))
    }

    @Test
    fun docxAppendsTheReactionLabelAfterThePicture() {
        val bytes = docxOf(paper(reaction()))
        val xml = entries(bytes).getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue("the reaction label prints as text: $xml",
            xml.contains("<w:t xml:space=\"preserve\">A</w:t>"))
        assertTrue("the handles stay in the picture description: $xml",
            xml.contains("descr=\"lactone reduced by LiAlH4 and ether A\""))
        assertFalse(
            "the readable handles are not printed beside the picture: $xml",
            xml.contains(escapeXmlText(chemElementText(reaction())))
        )
        // The freshness canon must agree with the picture-then-label body.
        val parsed = parseDocx(bytes)
        assertTrue("an untouched reaction export must stay fresh: ${parsed.warnings}",
            parsed.warnings.isEmpty())
    }

    @Test
    fun docxAppendsTheReactionLabelInsideAChemCell() {
        val cell = com.mcqapp.util.chemCellHtml(reaction())
        val bytes = docxOf(paper(ContentElement.TableElement(listOf(listOf(cell)))))
        val xml = entries(bytes).getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue("the cell reaction label prints as text: $xml",
            xml.contains("<w:t xml:space=\"preserve\">A</w:t>"))
        assertTrue("the cell handles stay in the picture description: $xml",
            xml.contains("descr=\"lactone reduced by LiAlH4 and ether A\""))
        assertFalse("the cell handles are not printed beside the picture: $xml",
            xml.contains(escapeXmlText(chemElementText(reaction()))))
        val parsed = parseDocx(bytes)
        assertTrue("an untouched reaction cell export must stay fresh: ${parsed.warnings}",
            parsed.warnings.isEmpty())
    }

    @Test
    fun aPicturedReactionKeepsMarksAndTagsThroughDocx() = runBlocking {
        val original = paper(reaction(), marks = 2.5, tags = listOf("organic", "reaction"))
        val stream = ChemDiagramPreparation.enrichPaper(PaperStream.of(original), renderer)
        var prepared: QuestionDto? = null
        stream.walk(onCategory = {}, onQuestion = { prepared = it })
        val preparedQuestion = requireNotNull(prepared)
        val structure = preparedQuestion.elements
            .filterIsInstance<ContentElement.ChemElement>().single()
        assertTrue("the preparation must render the reaction", isPngDataUri(structure.diagram))

        val parsed = parseDocx(exportBytes { DocxPaperWriter.writeDocx(stream, it) })
        assertTrue("a pictured reaction must stay fresh: ${parsed.warnings}",
            parsed.warnings.isEmpty())
        val restored = questionOf(parsed.json)
        assertEquals("the rendered element must survive", preparedQuestion.elements, restored.elements)
        assertEquals("marks must survive", 2.5, restored.marks, 0.0)
        assertEquals(listOf("organic", "reaction"), restored.tags)
        assertEquals(CardContent.of(preparedQuestion), CardContent.of(restored))
    }

    @Test
    fun aPicturedReactionCellKeepsMarksAndTagsThroughDocx() = runBlocking {
        val original = paper(
            ContentElement.TableElement(
                listOf(listOf(com.mcqapp.util.chemCellHtml(reaction(), includePng = false)))
            ),
            marks = 2.5,
            tags = listOf("organic", "reaction")
        )
        val stream = ChemDiagramPreparation.enrichPaper(PaperStream.of(original), renderer)
        var prepared: QuestionDto? = null
        stream.walk(onCategory = {}, onQuestion = { prepared = it })
        val preparedQuestion = requireNotNull(prepared)
        val preparedRows = preparedQuestion.elements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertTrue("the preparation must render the cell structure",
            preparedRows.flatten().any { it.contains("data-png=") })

        val parsed = parseDocx(exportBytes { DocxPaperWriter.writeDocx(stream, it) })
        assertTrue("a pictured reaction cell must stay fresh: ${parsed.warnings}",
            parsed.warnings.isEmpty())
        val restored = questionOf(parsed.json)
        val restoredRows = restored.elements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals("the rendered cell must round-trip exactly", preparedRows, restoredRows)
        assertEquals("marks must survive", 2.5, restored.marks, 0.0)
        assertEquals(listOf("organic", "reaction"), restored.tags)
        assertEquals(CardContent.of(preparedQuestion), CardContent.of(restored))
    }

    @Test
    fun twoStructuresInOneCellKeepBothPicturesAndStayFresh() {
        val secondPng = "data:image/png;base64," +
            Base64.getEncoder().encodeToString(pngBytes + byteArrayOf(0))
        val cell = com.mcqapp.util.chemCellHtml(chem()) + " " +
            com.mcqapp.util.chemCellHtml(reaction(secondPng))
        val paper = paper(ContentElement.TableElement(listOf(listOf(cell))))

        val files = entries(docxOf(paper))
        assertEquals(
            "both structures must draw",
            2,
            files.keys.count { it.startsWith("word/media/") }
        )
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue("the reaction label prints after its picture: $xml",
            xml.contains("<w:t xml:space=\"preserve\">A</w:t>"))

        val parsed = parseDocx(docxOf(paper))
        assertTrue("two pictured structures must stay fresh: ${parsed.warnings}",
            parsed.warnings.isEmpty())
        val originalRows = paper.categories.single().questions.single().elements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        val restoredRows = questionOf(parsed.json).elements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals(originalRows, restoredRows)
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
    fun ankiFieldSpoolsThePngUnderItsFilenameAndKeepsHandlesInAlt() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(listOf(chem()))
            // The visible card shows the drawing; the handles are its alt.
            assertTrue(html.contains("<img src=\"mcqapp-0.png\" alt=\"aromatic ring C6H6\">"))
            assertFalse(
                "the readable handles are not printed beside the picture",
                html.contains("Benzene ring C6H6 c1ccccc1")
            )
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
    fun ankiFieldAppendsTheReactionLabelAfterThePicture() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(listOf(reaction()))
            assertTrue(
                "the reaction picture carries the handles in alt and the label follows: $html",
                html.contains(
                    "<img src=\"mcqapp-0.png\" " +
                        "alt=\"lactone reduced by LiAlH4 and ether A\">A"
                )
            )
            assertFalse(
                "the readable handles are not printed beside the picture",
                html.contains(AnkiMediaPool.escapeHtml(chemElementText(reaction())))
            )
        } finally {
            pool.close()
        }
    }

    @Test
    fun ankiFieldAppendsTheReactionLabelInsideAChemCell() {
        val pool = AnkiMediaPool()
        try {
            val cell = com.mcqapp.util.chemCellHtml(reaction())
            val html = pool.elementsToHtml(
                listOf(ContentElement.TableElement(listOf(listOf(cell))))
            )
            assertTrue(
                "the cell picture carries the handles in alt and the label follows: $html",
                html.contains(
                    "<img src=\"mcqapp-0.png\" " +
                        "alt=\"lactone reduced by LiAlH4 and ether A\">A"
                )
            )
            assertFalse(
                "the cell handles are not printed beside the picture: $html",
                html.contains(AnkiMediaPool.escapeHtml(chemElementText(reaction())))
            )
        } finally {
            pool.close()
        }
    }

    @Test
    fun aBlankFormulaOrAMoleculePrintsNoProductLabel() {
        // A reaction whose product label is blank must not emit a stray "A".
        val blank = blankLabelReaction()
        val html = htmlOf(paper(blank))
        assertTrue(
            "the reaction picture still draws: $html",
            html.contains("<img src=\"$png\" alt=\"lactone reduced by LiAlH4 and ether\">\n")
        )
        assertFalse(
            "a blank formula prints no HTML label",
            html.contains("alt=\"lactone reduced by LiAlH4 and ether\">\nA")
        )
        val xml = entries(docxOf(paper(blank))).getValue("word/document.xml")
            .toString(Charsets.UTF_8)
        assertFalse(
            "a blank formula prints no DOCX label",
            xml.contains("<w:t xml:space=\"preserve\">A</w:t>")
        )

        // A molecule whose formula happens to be "A" is not a reaction.
        val molecule = chem().copy(formula = "A")
        val moleculeHtml = htmlOf(paper(molecule))
        assertTrue(
            "the molecule picture still draws: $moleculeHtml",
            moleculeHtml.contains("<img src=\"$png\" alt=\"aromatic ring A\">\n")
        )
        assertFalse(
            "a molecule never gets a product label",
            moleculeHtml.contains("alt=\"aromatic ring A\">\nA")
        )
        val moleculeXml = entries(docxOf(paper(molecule))).getValue("word/document.xml")
            .toString(Charsets.UTF_8)
        assertFalse(
            "a molecule never gets a DOCX label",
            moleculeXml.contains("<w:t xml:space=\"preserve\">A</w:t>")
        )

        val pool = AnkiMediaPool()
        try {
            val blankField = pool.elementsToHtml(listOf(blank))
            assertEquals(1, Regex("mcqapp-0\\.png").findAll(blankField).count())
            assertFalse("a blank formula prints no Anki label", blankField.contains(">A"))
            val moleculeField = pool.elementsToHtml(listOf(molecule))
            assertFalse("a molecule never gets an Anki label", moleculeField.contains(">A"))
        } finally {
            pool.close()
        }
    }

    @Test
    fun aCellWithAnInvalidStoredPngFallsBackToHandlesInDocxAndAnki() {
        val table = ContentElement.TableElement(listOf(listOf(invalidPngMarker)))
        val files = entries(docxOf(paper(table)))
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue("the cell handles print: $xml", xml.contains("ethanol C2H5OH CCO"))
        assertTrue(
            "an invalid payload must not create a media entry",
            files.keys.none { it.startsWith("word/media/") }
        )

        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(listOf(table))
            assertTrue("the cell handles print: $html", html.contains("<td>ethanol C2H5OH CCO</td>"))
            assertEquals("an invalid payload must spool nothing", 0, pool.size)
            assertFalse("no data URI may reach the card", html.contains("data:"))
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
