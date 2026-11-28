package com.mcqapp

import com.mcqapp.data.export.ChemDiagramCache
import com.mcqapp.data.export.ChemDiagramPreparation
import com.mcqapp.data.export.ChemDiagramRenderer
import com.mcqapp.data.export.ExportFormat
import com.mcqapp.data.export.HtmlPaperWriter
import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.export.chemAccessibleText
import com.mcqapp.data.export.chemElementText
import com.mcqapp.data.export.isPngDataUri
import com.mcqapp.data.export.preparePaperForExport
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CellSegment
import com.mcqapp.util.cellSegments
import com.mcqapp.util.chemCellHtml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The export preparation pass: every structure without a usable diagram gets
 * one drawn on a copy, repeated structures render once, and nothing else —
 * stored data, prose around a table marker, or a marker that already carries a
 * valid PNG — is touched. Pure JVM with a fake renderer.
 */
class ChemDiagramPreparationTest {

    private val pngBytes = ByteArray(24).also { b ->
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        sig.copyInto(b, 0)
        'I'.code.toByte().let { b[8] = it }
        'H'.code.toByte().let { b[9] = it }
        'D'.code.toByte().let { b[10] = it }
        'R'.code.toByte().let { b[11] = it }
        b[16] = 0; b[17] = 0; b[18] = 0; b[19] = 2
        b[20] = 0; b[21] = 0; b[22] = 0; b[23] = 2
    }
    private val png = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)

    private class FakeRenderer(
        private val result: String? = null,
        private val failOn: Set<String> = emptySet(),
        private val throwOn: Set<String> = emptySet(),
        private val cancelOn: Set<String> = emptySet()
    ) : ChemDiagramRenderer {
        val calls = mutableListOf<String>()
        val captions = mutableListOf<String>()
        override suspend fun render(smiles: String, textAbove: String): String? {
            calls += smiles
            captions += textAbove
            if (smiles in cancelOn) throw CancellationException("renderer cancelled")
            if (smiles in throwOn) throw IllegalStateException("renderer exploded")
            return if (smiles in failOn) null else result
        }
    }

    private fun chem(smiles: String, diagram: String = "") = ContentElement.ChemElement(
        smiles = smiles,
        formula = "F",
        caption = "caption",
        diagram = diagram
    )

    private fun paper(
        elements: List<ContentElement> = emptyList(),
        options: List<OptionDto> = emptyList(),
        explanation: List<ContentElement> = emptyList(),
        passages: List<PassageDto> = emptyList(),
        loose: List<QuestionDto> = emptyList()
    ) = PaperDto(
        id = "p1",
        title = "Chemistry",
        categories = listOf(
            CategoryDto(
                id = "c1",
                title = "Structures",
                questions = listOf(
                    QuestionDto(
                        id = "q1",
                        elements = elements,
                        options = options,
                        explanationElements = explanation
                    )
                )
            )
        ),
        questions = loose,
        passages = passages
    )

    private fun questionsOf(stream: PaperStream): List<QuestionDto> = runBlocking {
        val out = mutableListOf<QuestionDto>()
        stream.walk(onCategory = {}, onQuestion = { out += it })
        out
    }

    private fun structureOf(elements: List<ContentElement>) =
        elements.filterIsInstance<ContentElement.ChemElement>().single()

    @Test
    fun missingDiagramsAreFilledOnBodyOptionsExplanationPassagesAndCells() = runBlocking {
        val cell = "<b>before</b> " +
            chemCellHtml(chem("O"), includePng = false) +
            " <i>after</i>"
        val table = ContentElement.TableElement(listOf(listOf(cell)))
        val originalPaper = paper(
            elements = listOf(ContentElement.TextElement("Stem"), chem("c1ccccc1"), table),
            options = listOf(
                OptionDto(id = "a", elements = listOf(chem("CCO"))),
                OptionDto(id = "b", text = "plain")
            ),
            explanation = listOf(chem("CCN")),
            passages = listOf(PassageDto(id = "ps", title = "Passage", elements = listOf(chem("C1CCCCC1")))),
            loose = listOf(QuestionDto(id = "loose", elements = listOf(chem("CCCl"))))
        )
        val renderer = FakeRenderer(result = png)

        val stream = ChemDiagramPreparation.enrichPaper(PaperStream.of(originalPaper), renderer)
        val question = questionsOf(stream).single()

        assertEquals(png, structureOf(question.elements).diagram)
        assertEquals(png, structureOf(question.options.first().elements).diagram)
        assertEquals(png, structureOf(question.explanationElements).diagram)
        assertEquals(png, structureOf(stream.passages.single().elements).diagram)
        assertEquals(png, structureOf(stream.looseQuestions.single().elements).diagram)

        val enrichedCell = (question.elements.filterIsInstance<ContentElement.TableElement>().single())
            .rows.single().single()
        val marker = cellSegments(enrichedCell).filterIsInstance<CellSegment.Chem>().single().element
        assertEquals(png, marker.diagram)
        assertTrue("prose before the marker is byte-identical",
            enrichedCell.startsWith("<b>before</b> "))
        assertTrue("prose after the marker is byte-identical",
            enrichedCell.endsWith(" <i>after</i>"))
        assertTrue(enrichedCell.contains("data-png=\""))
        assertEquals(
            "only the marker is rebuilt; the prose around it is untouched",
            "<b>before</b> " +
                chemCellHtml(chem("O").copy(diagram = png), includePng = true) +
                " <i>after</i>",
            enrichedCell
        )

        assertEquals(
            "one render per distinct structure, despite repeats across surfaces",
            setOf("c1ccccc1", "CCO", "CCN", "C1CCCCC1", "CCCl", "O"),
            renderer.calls.toSet()
        )
        assertEquals("no distinct structure is rendered twice", renderer.calls.size, renderer.calls.toSet().size)
    }

    @Test
    fun aValidDiagramOnAnElementOrMarkerIsLeftExactlyAsStored() = runBlocking {
        val validChem = chem("c1ccccc1", diagram = png)
        val validMarker = chemCellHtml(validChem, includePng = true)
        val cell = "prefix $validMarker suffix"
        val originalPaper = paper(
            elements = listOf(validChem, ContentElement.TableElement(listOf(listOf(cell))))
        )
        val renderer = FakeRenderer(result = png)

        val stream = ChemDiagramPreparation.enrichPaper(PaperStream.of(originalPaper), renderer)
        val question = questionsOf(stream).single()

        assertEquals(validChem, structureOf(question.elements))
        val enrichedCell = question.elements.filterIsInstance<ContentElement.TableElement>()
            .single().rows.single().single()
        assertEquals("an already-rasterised marker is not rewritten at all", cell, enrichedCell)
        assertTrue("nothing needed rendering", renderer.calls.isEmpty())
    }

    @Test
    fun repeatedStructuresRenderOnceEvenWhenTheWalkRunsTwice() = runBlocking {
        val originalPaper = paper(
            elements = listOf(chem("CCO"), chem("CCO").copy(caption = "another caption")),
            options = listOf(OptionDto(id = "a", elements = listOf(chem("CCO"), chem("O")))),
            explanation = listOf(chem("O")),
            passages = listOf(PassageDto(id = "ps", title = "P", elements = listOf(chem("CCO")))),
            loose = listOf(QuestionDto(id = "loose", elements = listOf(chem("O"))))
        )
        val renderer = FakeRenderer(result = png)
        val stream = ChemDiagramPreparation.enrichPaper(PaperStream.of(originalPaper), renderer)

        questionsOf(stream)
        questionsOf(stream)

        assertEquals(listOf("CCO", "O").sorted(), renderer.calls.sorted())
    }

    @Test
    fun aFailedOrThrowingRendererLeavesTheHandlesPathUnchanged() = runBlocking {
        val cell = "before " + chemCellHtml(chem("CCO"), includePng = false) + " after"
        val originalPaper = paper(
            elements = listOf(chem("CCO"), ContentElement.TableElement(listOf(listOf(cell)))),
            options = listOf(OptionDto(id = "a", elements = listOf(chem("O"))))
        )

        for (renderer in listOf(
            FakeRenderer(result = null, failOn = setOf("CCO", "O")),
            FakeRenderer(result = png, throwOn = setOf("CCO", "O"))
        )) {
            val stream = ChemDiagramPreparation.enrichPaper(PaperStream.of(originalPaper), renderer)
            val question = questionsOf(stream).single()
            assertEquals(
                "a failed render must not change the stored element",
                originalPaper.categories.single().questions.single().elements,
                question.elements
            )
            assertEquals(
                "a failed cell render must not change the marker",
                cell,
                question.elements.filterIsInstance<ContentElement.TableElement>()
                    .single().rows.single().single()
            )
            assertEquals(
                originalPaper.categories.single().questions.single().options,
                question.options
            )
        }
    }

    @Test
    fun anUnavailableRendererLeavesTheStreamUntouched() = runBlocking {
        val stream = PaperStream.of(paper(elements = listOf(chem("CCO"))))
        assertSame(stream, preparePaperForExport(stream, ExportFormat.HTML, renderer = null))
    }

    @Test
    fun jsonIsNeverPreparedButVisualFormatsAre() = runBlocking {
        val renderer = FakeRenderer(result = png)
        val jsonStream = PaperStream.of(paper(elements = listOf(chem("CCO"))))
        assertSame(
            "JSON must stay a byte-faithful copy of storage",
            jsonStream,
            preparePaperForExport(jsonStream, ExportFormat.JSON_INLINE, renderer)
        )
        assertTrue("JSON must not trigger a render", renderer.calls.isEmpty())

        for (format in ExportFormat.entries.filter { it != ExportFormat.JSON_INLINE }) {
            val before = renderer.calls.size
            val stream = PaperStream.of(paper(elements = listOf(chem("CCO"))))
            val prepared = preparePaperForExport(stream, format, renderer)
            assertNotSame("$format must receive the prepared stream", stream, prepared)
            assertEquals(png, structureOf(questionsOf(prepared).single().elements).diagram)
            assertTrue("$format must render the structure", renderer.calls.size > before)
        }
    }

    @Test
    fun theRendererCacheIsBounded() {
        val cache = ChemDiagramCache(maxEntries = 2)
        cache.put("a", png)
        cache.put("b", png)
        assertEquals(2, cache.size())
        cache.put("c", png)
        assertEquals("the cache must not grow without bound", 2, cache.size())
        assertNull("the least recently used entry is evicted", cache.get("a"))
        assertEquals(png, cache.get("b"))
        assertEquals(png, cache.get("c"))
    }

    /**
     * Acceptance: the real bundled sample's structures are drawn by the
     * preparation pass, including the marker inside q-chem-6's table and the
     * standalone chemistry questions.
     */
    @Test
    fun theSamplePaperGetsPicturesForItsChemistryQuestions() = runBlocking {
        val sampleJson = listOf(
            "src/main/assets/sample_paper.json",
            "app/src/main/assets/sample_paper.json"
        ).firstNotNullOfOrNull { File(it).takeIf { file -> file.isFile } }
            ?.readText()
            ?: error("sample_paper.json not found from ${File(".").absolutePath}")
        val sample = LegacyParser.parse(sampleJson).papers.single()
        val renderer = FakeRenderer(result = png)

        val stream = ChemDiagramPreparation.enrichPaper(PaperStream.of(sample), renderer)
        val byId = questionsOf(stream).associateBy { it.id }

        fun chemElementsOf(question: QuestionDto): List<ContentElement.ChemElement> =
            (question.elements +
                question.options.flatMap { it.elements } +
                question.explanationElements)
                .filterIsInstance<ContentElement.ChemElement>()

        for (id in listOf("q-chem-5", "q-org-1", "q-org-2")) {
            val question = byId.getValue(id)
            val structures = chemElementsOf(question)
            assertTrue("$id must carry a structure", structures.isNotEmpty())
            assertTrue(
                "$id structures must all have a picture: " +
                    structures.map { it.smiles to it.diagram },
                structures.all { isPngDataUri(it.diagram) }
            )
        }

        val cell = byId.getValue("q-chem-6").elements
            .filterIsInstance<ContentElement.TableElement>()
            .flatMap { it.rows.flatten() }
            .flatMap { cellSegments(it).filterIsInstance<CellSegment.Chem>() }
            .single()
        assertTrue("q-chem-6's table marker must carry the rendered PNG",
            isPngDataUri(cell.element.diagram))
        val authored = sample.categories.flatMap { it.questions }
            .single { it.id == "q-chem-5" }
            .elements.filterIsInstance<ContentElement.ChemElement>()
            .single()
        assertEquals("the stored sample is not mutated by the preparation pass",
            "", authored.diagram)

        // The real sample reaction exports as the in-app page shows it: the
        // drawing (handles in alt), followed by its "(A)" product label.
        val organic = byId.getValue("q-org-1").elements
            .filterIsInstance<ContentElement.ChemElement>().single()
        assertEquals("O=C1OCCCCC(=O)C1>>", organic.smiles)
        assertEquals("A", organic.formula)
        val page = buildString { HtmlPaperWriter.writeHtml(stream, this) }
        val alt = com.mcqapp.data.escapeHtmlText(chemAccessibleText(organic))
        assertTrue(
            "the reaction picture carries its label right after it: $alt",
            page.contains("<img src=\"$png\" alt=\"$alt\">\nA")
        )
        assertFalse(
            "the organic handles are not printed beside the drawing",
            page.contains(com.mcqapp.data.escapeHtmlText(chemElementText(organic)))
        )
    }

    @Test
    fun invalidStoredDiagramsAreRenderedAgain() = runBlocking {
        val svg = "data:image/svg+xml;base64," +
            Base64.getEncoder().encodeToString("<svg onload='x()'/>".toByteArray())
        val brokenMarker = "<span class=\"mcq-chem-cell\" data-smiles=\"CCO\" " +
            "data-png=\"not-base64\">ethanol C2H5OH CCO</span>"
        val originalPaper = paper(
            elements = listOf(
                chem("c1ccccc1", diagram = svg),
                ContentElement.TableElement(listOf(listOf(brokenMarker)))
            )
        )
        val renderer = FakeRenderer(result = png)
        val question = questionsOf(
            ChemDiagramPreparation.enrichPaper(PaperStream.of(originalPaper), renderer)
        ).single()

        assertEquals("an SVG diagram is not a usable export raster",
            png, structureOf(question.elements).diagram)
        val cell = question.elements.filterIsInstance<ContentElement.TableElement>()
            .single().rows.single().single()
        assertTrue("the garbage data-png must be replaced", cell.contains("data-png=\""))
        assertFalse(cell.contains("not-base64"))
        assertEquals(png, cellSegments(cell).filterIsInstance<CellSegment.Chem>().single().element.diagram)
        assertEquals(setOf("c1ccccc1", "CCO"), renderer.calls.toSet())
    }

    @Test
    fun reactionCaptionsTravelAsTextAboveAndKeyTheCache() = runBlocking {
        val reaction = ContentElement.ChemElement(
            smiles = "CCO>>CC=O",
            caption = "LiAlH₄ / ether"
        )
        val otherCaption = reaction.copy(caption = "cold, dilute")
        val renderer = FakeRenderer(result = png)
        val question = questionsOf(
            ChemDiagramPreparation.enrichPaper(
                PaperStream.of(paper(elements = listOf(reaction, otherCaption, reaction))),
                renderer
            )
        ).single()

        assertEquals(
            "the caption is the reagent text above the arrow",
            listOf("LiAlH₄ / ether", "cold, dilute"),
            renderer.captions
        )
        assertEquals("a different caption is a different drawing", 2, renderer.calls.size)
        assertTrue(
            "every reaction copy carries its picture",
            question.elements.filterIsInstance<ContentElement.ChemElement>()
                .all { it.diagram == png }
        )
    }

    @Test
    fun cancellationPropagatesButAPlainRendererFailureDoesNot() = runBlocking {
        val cancelled = FakeRenderer(result = png, cancelOn = setOf("CCO"))
        try {
            questionsOf(
                ChemDiagramPreparation.enrichPaper(
                    PaperStream.of(paper(elements = listOf(chem("CCO")))),
                    cancelled
                )
            )
            fail("CancellationException must escape an export preparation")
        } catch (expected: CancellationException) {
            assertEquals("renderer cancelled", expected.message)
        }

        val throwing = FakeRenderer(result = png, throwOn = setOf("CCO"))
        val question = questionsOf(
            ChemDiagramPreparation.enrichPaper(
                PaperStream.of(paper(elements = listOf(chem("CCO")))),
                throwing
            )
        ).single()
        assertEquals("a plain exception is a failed render, not a failed export",
            "", structureOf(question.elements).diagram)
    }

    @Test
    fun malformedSmilesNeverReachTheRenderer() = runBlocking {
        val malformed = listOf(chem(""), chem(">"), chem("CCO>>CC=O>"))
        val renderer = FakeRenderer(result = png)
        val question = questionsOf(
            ChemDiagramPreparation.enrichPaper(
                PaperStream.of(paper(elements = malformed)),
                renderer
            )
        ).single()

        assertTrue("blank, lone '>' and three '>' are not drawable", renderer.calls.isEmpty())
        assertEquals("their handles path is unchanged", malformed, question.elements)
    }

    @Test
    fun theSessionCacheIsBoundedAndTheBudgetStopsRendering() = runBlocking {
        val budgeted = FakeRenderer(result = png)
        val budgetQuestion = questionsOf(
            ChemDiagramPreparation.enrichPaper(
                PaperStream.of(paper(elements = listOf(chem("CCO")))),
                budgeted,
                budgetMillis = 0
            )
        ).single()
        assertEquals("an exhausted budget keeps the handles path",
            "", structureOf(budgetQuestion.elements).diagram)
        assertTrue("no render may run after the budget", budgeted.calls.isEmpty())

        val renderer = FakeRenderer(result = png)
        val distinct = (1..ChemDiagramPreparation.MAX_CACHED_STRUCTURES + 1).map { chem("C$it") }
        val stream = ChemDiagramPreparation.enrichPaper(
            PaperStream.of(paper(elements = distinct + chem("C1"))),
            renderer
        )
        val question = questionsOf(stream).single()

        assertEquals("the last structure still gets its picture",
            png, question.elements.filterIsInstance<ContentElement.ChemElement>().last().diagram)
        assertEquals(
            "the first structure was evicted and rendered a second time",
            ChemDiagramPreparation.MAX_CACHED_STRUCTURES + 2,
            renderer.calls.size
        )
        assertEquals(renderer.calls.first(), renderer.calls.last())
    }
}
