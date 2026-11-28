package com.mcqapp

import com.mcqapp.data.export.HtmlPaperWriter
import com.mcqapp.data.export.ZipPaperWriter
import com.mcqapp.data.export.parseDataUri
import com.mcqapp.data.export.unreadableImageLabel
import com.mcqapp.data.export.DocxPaperWriter
import com.mcqapp.data.export.PaperJsonWriter
import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CELL_FORMULA_MARKER
import com.mcqapp.util.CELL_IMAGE_ALT
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.CELL_IMAGE_STYLE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class ExportWritersTest {

    /** Exactly the wrapper the writer emits around every MathElement. */
    private val inlineMathWrapper =
        "<span class=\"math-block\" style=\"display:inline-block;overflow-x:auto;max-width:100%\">"

    private fun samplePaper(imageSrc: String? = null): PaperDto {
        val q = QuestionDto(
            id = "q1",
            text = "Pick <one> & \"all\"?",
            image = imageSrc,
            options = listOf(
                OptionDto(id = "a", text = "Alpha"),
                OptionDto(id = "b", text = "Beta", image = imageSrc)
            ),
            correctOptionIds = listOf("b"),
            explanation = "Because <reasons>"
        )
        return PaperDto(
            id = "p1",
            title = "Paper & Title",
            description = "Desc",
            durationMinutes = 30,
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = listOf(q)))
        )
    }

    @Test
    fun htmlEscapesAndMarksCorrect() {
        val html = htmlOf(samplePaper())
        assertTrue(html.contains("Pick &lt;one&gt; &amp; &quot;all&quot;?"))
        assertTrue(html.contains("Paper &amp; Title"))
        assertTrue(html.contains("Because &lt;reasons&gt;"))
        assertTrue(html.contains("class=\"correct\""))
        assertFalse(html.contains("<one>"))
    }

    @Test
    fun htmlEmbedsDataUriImage() {
        val src = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val html = htmlOf(samplePaper(src))
        assertTrue(html.contains("<img src=\"data:image/jpeg;base64,"))
    }

    @Test
    fun htmlEmbedsEachImageExactlyOnce() {
        // The question and its option use different images here so each
        // <img> can be counted separately: a duplicated appendImage call
        // would show up as two identical question-image tags.
        val questionSrc = "data:image/png;base64," +
            java.util.Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val optionSrc = "data:image/gif;base64," +
            java.util.Base64.getEncoder().encodeToString(ByteArray(16) { (it + 1).toByte() })
        val q = QuestionDto(
            id = "q1",
            text = "Pick one?",
            image = questionSrc,
            options = listOf(
                OptionDto(id = "a", text = "Alpha"),
                OptionDto(id = "b", text = "Beta", image = optionSrc)
            ),
            correctOptionIds = listOf("b"),
            explanation = "Because reasons"
        )
        val paper = PaperDto(
            id = "p1",
            title = "Paper",
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = listOf(q)))
        )
        val html = htmlOf(paper)
        assertEquals(1, html.split("<img src=\"$questionSrc\"").size - 1)
        assertEquals(1, html.split("<img src=\"$optionSrc\"").size - 1)
    }

    @Test
    fun htmlRendersTopLevelQuestionsAndCountsThem() {
        // A foreign file can carry questions outside every category. walk does
        // not visit them, so the page printed a header count that included
        // them while rendering none of them.
        val loose = QuestionDto(
            id = "q2",
            text = "Loose?",
            options = listOf(OptionDto(id = "a", text = "Alpha"), OptionDto(id = "b", text = "Beta")),
            correctOptionIds = listOf("a")
        )
        val paper = samplePaper().copy(questions = listOf(loose))

        val html = htmlOf(paper)

        assertTrue(html.contains("2 questions"))
        assertTrue(html.contains("Loose?"))
        // Rendered questions now match the count: one in the category, one
        // loose.
        assertEquals(2, html.split("<div class=\"q\">").size - 1)
        // The loose one gets its own section rather than joining the category.
        val looseAt = html.indexOf("Loose?")
        assertTrue(html.indexOf("<h2>Uncategorized</h2>") in 1 until looseAt)

        // Quiz mode numbers the loose question after the categorised one.
        val quiz = htmlOf(paper, quiz = true)
        assertTrue(quiz.contains("Loose?"))
        assertTrue("numbering must stay continuous", quiz.contains("Q2."))
    }

    @Test
    fun quizHtmlHidesAnswersBehindToggle() {
        val html = htmlOf(samplePaper(), quiz = true)
        // no correct marks in the question body ...
        assertFalse(html.contains("class=\"correct\""))
        // ... and the answer line exists only inside the hidden block
        val hiddenAt = html.indexOf("style=\"display:none\"")
        val answerAt = html.indexOf("<p class=\"answer\">Answer:")
        assertTrue(hiddenAt >= 0 && answerAt > hiddenAt)
        // reveal controls exist and are self-contained (inline script, no src=)
        assertTrue(html.contains("Show answer"))
        assertTrue(html.contains("function toggle(id)"))
        assertFalse(html.contains("<script src="))
        // the answer itself is present but hidden
        assertTrue(html.contains("id=\"ans1\""))
        assertTrue(html.contains("Beta"))
        assertTrue(html.contains("style=\"display:none\""))
    }

    @Test
    fun htmlPrintsAnImageOnlyExplanation() {
        // explanationElements empty and explanation blank, only the image:
        // the guard used to omit explanationImage, so the block vanished from
        // both pages while PDF (answer key) and DOCX rendered it.
        val src = "data:image/jpeg;base64,QUJDREVG"
        val q = QuestionDto(
            id = "q1",
            text = "Stem",
            options = listOf(OptionDto(id = "a", text = "A"), OptionDto(id = "b", text = "B")),
            correctOptionIds = listOf("a"),
            explanation = "",
            explanationImage = src
        )
        val paper = PaperDto(
            id = "p1",
            title = "T",
            categories = listOf(CategoryDto(id = "c1", title = "C", questions = listOf(q)))
        )
        for (quiz in listOf(false, true)) {
            val html = htmlOf(paper, quiz = quiz)
            assertTrue("answer-shown=$quiz body", html.contains("<p class=\"expl\">Explanation: "))
            assertTrue("answer-shown=$quiz image", html.contains("<img src=\"$src\""))
        }
    }

    /**
     * A zero-option question is a flashcard: its back is the answer, so both
     * pages label it "Answer" rather than "Explanation". Options would be a
     * misnomer twice over — the card has none, and the back is not a note
     * about one.
     */
    @Test
    fun aCardsBackIsLabelledAnswerOnBothPages() {
        val card = QuestionDto(
            id = "q1",
            text = "Capital of France?",
            options = emptyList(),
            explanation = "Paris."
        )
        val paper = PaperDto(
            id = "p1",
            title = "Cards",
            categories = listOf(CategoryDto(id = "c1", title = "Deck", questions = listOf(card)))
        )
        for (quiz in listOf(false, true)) {
            val html = htmlOf(paper, quiz = quiz)
            assertTrue(
                "quiz=$quiz must label a card's back Answer: $html",
                html.contains("<p class=\"expl\">Answer: Paris.</p>")
            )
            assertFalse(
                "quiz=$quiz must not call a card's back an explanation: $html",
                html.contains("Explanation:")
            )
        }
    }

    @Test
    fun exportFileNamesAreDistinctPerFormat() {
        assertEquals(
            "My-Paper-quiz.html",
            com.mcqapp.data.export.PaperExporter.fileNameFor(
                "My Paper",
                com.mcqapp.data.export.ExportFormat.HTML_QUIZ
            )
        )
        assertEquals(
            "My-Paper-answer-key.pdf",
            com.mcqapp.data.export.PaperExporter.fileNameFor(
                "My Paper",
                com.mcqapp.data.export.ExportFormat.PDF_ANSWER_KEY
            )
        )
        assertEquals(
            "My-Paper.pdf",
            com.mcqapp.data.export.PaperExporter.fileNameFor(
                "My Paper",
                com.mcqapp.data.export.ExportFormat.PDF
            )
        )
    }

    @Test
    fun parseDataUriHandlesShapes() {
        assertNull(parseDataUri(null))
        assertNull(parseDataUri("https://example.com/x.png"))
        assertNull(parseDataUri("data:image/jpeg;base64,!!!not-base64!!!"))
        val img = parseDataUri("data:image/png;base64,iVBORw0KGgo=")
        assertNotNull(img)
        assertEquals("image/png", img!!.mimeType)
        assertTrue(img.bytes.isNotEmpty())
    }

    @Test
    fun aRejectedDataUriSrcLabelsItselfUnreadableNotMegabytes() {
        // A `data:` src parseDataUri rejects (raw payload, no ;base64) printed
        // as its entire literal self — a 2 MB failed image was hundreds of
        // pages of base64 in the paper, with no hint it was an image. The
        // regression is a LENGTH assertion, not an eyeball.
        val blob = "data:image/png," + "A".repeat(16_384)
        assertEquals("[unreadable image]", unreadableImageLabel(blob))
        assertTrue("label must stay bounded, got ${unreadableImageLabel(blob).length}", unreadableImageLabel(blob).length < 40)
        // Non-data srcs keep the provenance label they always had.
        assertEquals("[image: photo.jpg]", unreadableImageLabel("photo.jpg"))
        assertEquals("[image: http://host/img.png]", unreadableImageLabel("http://host/img.png"))
    }

    @Test
    fun aMonsterPixelHeaderYieldsAPositiveClampedExtent() {
        // 225,443 px × 9525 EMU/px = 2,148,463,375 — past Int.MAX when computed
        // in Int, the wrapped negative slipped past the >MAX_CX rescale and
        // went straight into <wp:extent cx="-…">: Word refused the whole file.
        val (cx, cy) = DocxPaperWriter.emuExtent(225_443, 100)
        assertTrue("cx must be positive, got $cx", cx > 0)
        assertEquals(5486400, cx)
        assertTrue("cy must be positive, got $cy", cy > 0)
        // Aspect preserved through the clamp: 100 px wide × MAX_CX/2148463375.
        assertEquals((100L * 9525 * 5486400 / (225_443L * 9525)).toInt(), cy)
        // The import bound's extrema (1..1_000_000 accepted) clamp, not wrap.
        val (w1, h1) = DocxPaperWriter.emuExtent(1_000_000, 1_000_000)
        assertEquals(5486400 to 5486400, w1 to h1)
        // A lone huge height on a narrow header overflows cy by itself.
        val (w2, h2) = DocxPaperWriter.emuExtent(100, 900_000)
        assertEquals(952500, w2)
        assertEquals(5486400, h2)
        // Ordinary sizes are byte-for-byte what the writer always produced.
        assertEquals(3048000 to 2286000, DocxPaperWriter.emuExtent(320, 240))
    }

    @Test
    fun zipContainsJsonAndImages() {
        val raw = ByteArray(64) { it.toByte() }
        val src = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(raw)
        val bytes = zipOf(samplePaper(src))

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertTrue(entries.containsKey("paper.json"))
        val json = entries["paper.json"]!!.toString(Charsets.UTF_8)
        // data URIs must be replaced by relative file references
        assertFalse(json.contains("data:image"))
        assertTrue(json.contains("images/img001.jpg"))
        val imgEntry = entries.keys.firstOrNull { it.startsWith("images/") }
        assertNotNull(imgEntry)
        // question image and identical option image dedupe to one file
        assertEquals(1, entries.keys.count { it.startsWith("images/") })
        assertTrue(entries[imgEntry]!!.contentEquals(raw))
    }

    private fun dataUri(mime: String, raw: ByteArray) =
        "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(raw)

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    /**
     * Nearly all of this app's images are blocks inside content elements, not
     * the legacy per-row `image` field. The writer used to walk only the legacy
     * fields, so those images stayed inline as data URIs in paper.json and the
     * images/ folder came out empty.
     */
    @Test
    fun zipMovesImagesInsideElementsIntoTheFolder() {
        val stem = ByteArray(16) { it.toByte() }
        val option = ByteArray(16) { (it + 1).toByte() }
        val explanation = ByteArray(16) { (it + 2).toByte() }
        val passage = ByteArray(16) { (it + 3).toByte() }
        val q = QuestionDto(
            id = "q1",
            elements = listOf(
                ContentElement.TextElement("Read this"),
                ContentElement.ImageElement(dataUri("image/png", stem))
            ),
            options = listOf(
                OptionDto(
                    id = "a",
                    elements = listOf(ContentElement.ImageElement(dataUri("image/gif", option)))
                ),
                OptionDto(id = "b", text = "Beta")
            ),
            correctOptionIds = listOf("b"),
            explanationElements = listOf(
                ContentElement.ImageElement(dataUri("image/jpeg", explanation))
            )
        )
        val paper = PaperDto(
            id = "p1",
            title = "Paper",
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = listOf(q))),
            passages = listOf(
                PassageDto(
                    id = "passage-1",
                    title = "Reading",
                    elements = listOf(ContentElement.ImageElement(dataUri("image/png", passage)))
                )
            )
        )

        val entries = unzip(zipOf(paper))
        val json = entries.getValue("paper.json").toString(Charsets.UTF_8)
        val images = entries.filterKeys { it.startsWith("images/") }

        assertFalse("an element image is still inline: $json", json.contains("data:image"))
        // One file per image, its bytes intact, and the JSON refers to each
        // by a relative path (a passage is referenced twice — by the paper and
        // by the top-level list — so count distinct references, not mentions).
        assertEquals(4, images.size)
        assertEquals(
            4,
            Regex("images/img\\d{3}\\.[a-z]+").findAll(json).map { it.value }.toSet().size
        )
        listOf(stem, option, explanation, passage).forEach { raw ->
            assertTrue(
                "an element image never reached images/",
                images.values.any { it.contentEquals(raw) }
            )
        }
    }

    @Test
    fun zipKeepsRemoteUrlsUntouched() {
        val bytes = zipOf(
            samplePaper("https://example.com/x.png")
        )
        val names = mutableListOf<String>()
        var json = ""
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names.add(entry.name)
                if (entry.name == "paper.json") json = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertEquals(listOf("paper.json"), names)
        assertTrue(json.contains("https://example.com/x.png"))
    }

    private fun richPaper(): PaperDto {
        val q = QuestionDto(
            id = "q1",
            text = "Water?",
            elements = listOf(
                com.mcqapp.domain.ContentElement.TextElement("H<sub>2</sub>O <strong>x</strong> <foo>y</foo>"),
                com.mcqapp.domain.ContentElement.TableElement(listOf(listOf("a<b", "c"))),
                com.mcqapp.domain.ContentElement.MathElement("<math><mi>x</mi></math>")
            ),
            options = listOf(OptionDto(id = "a", text = "Yes")),
            correctOptionIds = listOf("a"),
            explanation = "Because"
        )
        return PaperDto(
            id = "p1",
            title = "Paper",
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = listOf(q)))
        )
    }

    @Test
    fun htmlRendersInlineFormattingTags() {
        val html = htmlOf(richPaper())
        assertTrue(html.contains("H<sub>2</sub>O <strong>x</strong>"))
        assertFalse(html.contains("&lt;sub&gt;"))
    }

    @Test
    fun htmlEscapesUnknownInlineTags() {
        val html = htmlOf(richPaper())
        assertFalse(html.contains("<foo>"))
        assertTrue(html.contains("&lt;foo&gt;y&lt;/foo&gt;"))
    }

    @Test
    fun htmlRendersTableAndMathElements() {
        val html = htmlOf(richPaper())
        assertTrue(html.contains("<td>a&lt;b</td>"))
        assertTrue(html.contains("<math><mi>x</mi></math>"))
    }

    @Test
    fun htmlEmbedsATableCellImageAndMarkersOnlyTheRest() {
        val src = "data:image/png;base64,AAAA"
        val q = QuestionDto(
            id = "q1",
            text = "Look at the table?",
            elements = listOf(
                ContentElement.TableElement(
                    listOf(
                        listOf("before <img src=\"$src\"/> after"),
                        listOf("<img src=\"http://example.com/x.png\"/>")
                    )
                )
            ),
            options = listOf(OptionDto(id = "a", text = "Yes")),
            correctOptionIds = listOf("a")
        )
        val paper = PaperDto(
            id = "p1",
            title = "Paper",
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = listOf(q)))
        )
        // The same writer serves the plain page and quiz mode.
        for (quiz in listOf(false, true)) {
            val html = htmlOf(paper, quiz = quiz)
            assertTrue(
                "quiz=$quiz: the real image is embedded: $html",
                html.contains(
                    "<img src=\"$src\" style=\"$CELL_IMAGE_STYLE\" alt=\"$CELL_IMAGE_ALT\""
                )
            )
            assertTrue("quiz=$quiz: order kept", html.contains("before <img"))
            assertTrue("quiz=$quiz: order kept", html.contains("/> after"))
            assertTrue(
                "quiz=$quiz: a non-portable source is the marker",
                html.contains("<td>$CELL_IMAGE_MARKER</td>")
            )
            assertFalse("quiz=$quiz: no remote tag leaks", html.contains("http://example.com/x.png"))
            assertEquals(
                "quiz=$quiz: exactly one cell image",
                1,
                Regex("<img src=\"data:image/png;base64,AAAA\"").findAll(html).count()
            )
        }
    }

    @Test
    fun htmlEscapesChemHandlesButKeepsTheDiagramTag() {
        val chem = ContentElement.ChemElement(
            smiles = "c1ccccc1",
            formula = "C6H6",
            caption = "Benzene <aromatic> & friends",
            diagram = "data:image/png;base64,iVBORw0KGgo="
        )
        val q = QuestionDto(
            id = "q1",
            elements = listOf(chem),
            options = listOf(OptionDto(id = "a", text = "Yes")),
            correctOptionIds = listOf("a")
        )
        val paper = PaperDto(
            id = "p1",
            title = "Paper",
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = listOf(q)))
        )
        for (quiz in listOf(false, true)) {
            val html = htmlOf(paper, quiz = quiz)
            assertTrue("quiz=$quiz", html.contains("c1ccccc1"))
            assertTrue("quiz=$quiz", html.contains("C6H6"))
            assertFalse("inline markup in a caption is text", html.contains("<aromatic>"))
            assertTrue(
                "alt uses the spoken-first fallback, not just the caption: $html",
                html.contains("alt=\"Benzene &lt;aromatic&gt; &amp; friends C6H6\"")
            )
            assertTrue(html.contains("<img src=\"data:image/png;base64,iVBORw0KGgo=\""))
        }
    }

    // ---- chemistry inside a table cell ---------------------------------

    /** A 2x2 PNG header: the strict validator accepts its signature. */
    private val cellPngBytes = ByteArray(24).also { b ->
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b, 0)
        b[19] = 2 // width = 2
        b[23] = 2 // height = 2
    }
    private val cellPng: String =
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(cellPngBytes)

    private fun cellChemPaper(cell: String): PaperDto {
        val q = QuestionDto(
            id = "q1",
            text = "Look at the table?",
            elements = listOf(ContentElement.TableElement(listOf(listOf(cell)))),
            options = listOf(OptionDto(id = "a", text = "Yes")),
            correctOptionIds = listOf("a")
        )
        return PaperDto(
            id = "p1",
            title = "Paper",
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = listOf(q)))
        )
    }

    @Test
    fun htmlRendersAChemTableCellInBothPageVariants() {
        // A cell structure must render like a standalone ChemElement: its
        // readable handles plus the stored PNG. It used to degrade to the
        // handles text alone, so the exported table lost every drawing.
        val element = ContentElement.ChemElement(
            smiles = "c1ccccc1",
            formula = "C6H6",
            caption = "Benzene <ring> & co",
            spoken = "aromatic ring",
            diagram = cellPng
        )
        val tableImage = "data:image/png;base64,AAAA"
        val cell = "Prose before " + com.mcqapp.util.chemCellHtml(element) +
            " <math><mi>x</mi></math> <img src=\"$tableImage\"/> prose after"
        val handles = com.mcqapp.data.escapeHtmlText(com.mcqapp.data.export.chemElementText(element))
        val paper = cellChemPaper(cell)

        for (quiz in listOf(false, true)) {
            val html = htmlOf(paper, quiz = quiz)
            val label = "quiz=$quiz"
            val handlesAt = html.indexOf(handles)
            val structureAt = html.indexOf("<img src=\"$cellPng\" alt=\"aromatic ring C6H6\">")
            val mathAt = html.indexOf("<math><mi>x</mi></math>")
            val imageAt = html.indexOf(
                "<img src=\"$tableImage\" style=\"$CELL_IMAGE_STYLE\" alt=\"$CELL_IMAGE_ALT\""
            )
            val afterAt = html.indexOf("prose after")
            assertTrue("$label: prose first: $html", html.contains("Prose before"))
            assertTrue("$label: handles text: $html", handlesAt >= 0)
            assertTrue("$label: the structure picture: $html", structureAt > handlesAt)
            assertTrue("$label: the formula is typeset: $html", mathAt > structureAt)
            assertTrue("$label: the cell picture keeps its style/alt: $html", imageAt > mathAt)
            assertTrue("$label: order kept: $html", afterAt > imageAt)
            assertFalse("$label: no raw marker: $html", html.contains("mcq-chem-cell"))
            assertFalse("$label: no attribute leaks: $html", html.contains("data-smiles"))
            assertFalse("$label: no data-png: $html", html.contains("data-png"))
        }
    }

    @Test
    fun htmlKeepsAChemCellWithoutAValidPngAsHandlesOnly() {
        // The payload is well-formed base64 but not a PNG: the marker stays a
        // structure (so no [image] marker) and prints only its handles. No raw
        // data-png attribute and no base64 text may reach the page.
        val payload = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val marker = "<span class=\"mcq-chem-cell\" data-smiles=\"CCO\" " +
            "data-formula=\"C2H5OH\" data-caption=\"ethanol\" data-png=\"$payload\">" +
            "ethanol C2H5OH CCO</span>"
        val html = htmlOf(cellChemPaper("Look " + marker + " done"))

        assertTrue("handles kept: $html", html.contains("ethanol C2H5OH CCO"))
        assertTrue("surrounding prose kept: $html", html.contains("Look "))
        assertFalse("no structure picture: $html", html.contains("<img src=\"data:image/png"))
        assertFalse("no base64 text: $html", html.contains(payload))
        assertFalse("no opaque marker: $html", html.contains(CELL_IMAGE_MARKER))
        assertFalse("no data-png text: $html", html.contains("data-png"))
        assertFalse("no raw marker: $html", html.contains("mcq-chem-cell"))
    }

    @Test
    fun htmlNeverRebuildsAHostileChemCellMarker() {
        // Neither a stray attribute nor an unquoted value may be promoted to
        // markup: both spans stay inert prose, so no tag, handler or picture
        // can come out of them.
        val extraAttribute = "<span class=\"mcq-chem-cell\" data-smiles=\"CCO\" " +
            "onclick=\"alert(1)\">ethanol CCO</span>"
        val unquoted = "<span class=mcq-chem-cell data-smiles=CCO>ethanol CCO</span>"
        val html = htmlOf(cellChemPaper("$extraAttribute | $unquoted"))

        assertTrue("the hostile span stays readable prose: $html", html.contains("ethanol CCO"))
        assertTrue("it is escaped, not markup: $html",
            html.contains("onclick=&quot;alert(1)&quot;"))
        assertFalse("no attribute was emitted: $html", html.contains("onclick=\"alert(1)\""))
        assertFalse("no span element: $html", html.contains("<span"))
        assertFalse("no picture from a hostile marker: $html", html.contains("<img"))
        assertFalse("no base64: $html", html.contains("base64,"))
    }

    @Test
    fun htmlGivesAChemTableFixedColumnsAndItsCss() {
        val element = ContentElement.ChemElement(
            smiles = "CCO",
            formula = "C2H5OH",
            caption = "ethanol",
            diagram = cellPng
        )
        val html = htmlOf(cellChemPaper(com.mcqapp.util.chemCellHtml(element)))

        assertTrue(
            "the chem table is marked: $html",
            html.contains("<table class=\"mcq-rich-table\" border=\"1\" cellspacing=\"0\" cellpadding=\"4\">")
        )
        // The rules the class relies on travel in the page's own stylesheet.
        assertTrue(html.contains("table.mcq-rich-table{table-layout:fixed;width:100%}"))
        assertTrue(html.contains("table.mcq-rich-table td{overflow-wrap:anywhere;word-break:break-word}"))
        assertTrue(
            html.contains("table.mcq-rich-table td img{max-width:100%;height:auto;max-height:240px}")
        )
    }

    @Test
    fun htmlTypesetsABareMathLiveFragmentAndKeepsItsProse() {
        val bare = "<mrow><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></mrow>"
        val html = htmlOf(cellChemPaper("Find x = $bare now"))
        assertTrue("the repaired table is rich: $html",
            html.contains("<table class=\"mcq-rich-table\" border=\"1\" cellspacing=\"0\" cellpadding=\"4\">"))
        assertTrue("one complete formula: $html", html.contains("<math><mrow><msup>"))
        assertTrue("prose before survives: $html", html.contains("Find x = "))
        assertTrue("prose after survives: $html", html.contains(" now"))
        assertFalse("never escaped tag text: $html", html.contains("&lt;mrow"))
        assertFalse("never escaped atom: $html", html.contains("&lt;msup"))
        assertFalse("never the fallback marker: $html", html.contains(CELL_FORMULA_MARKER))
    }

    @Test
    fun htmlGivesAFormulaTableTheFixedRichLayout() {
        val html = htmlOf(cellChemPaper("<math><mi>v</mi></math> m/s"))
        assertTrue(
            "the formula table is rich: $html",
            html.contains("<table class=\"mcq-rich-table\" border=\"1\" cellspacing=\"0\" cellpadding=\"4\">")
        )
        assertTrue(html.contains("table.mcq-rich-table{table-layout:fixed;width:100%}"))
        assertTrue(html.contains("table.mcq-rich-table td{overflow-wrap:anywhere;word-break:break-word}"))
        assertTrue(html.contains("table.mcq-rich-table td svg{max-width:100%;height:auto}"))
        assertStylesheetLeavesMathLayoutAlone(html)
        // An inline, in-cell formula keeps its native MathML markup and is
        // never put in the block wrapper: the wrapper is for a formula that
        // sits on its own line.
        assertTrue("the cell formula is native: $html", html.contains("<math><mi>v</mi></math> m/s"))
        assertFalse("an inline formula is not wrapped: $html", html.contains("<div class=\"math-block\""))
    }

    /**
     * A `display` declaration on a `math` selector replaces the browser's
     * native MathML container; Chrome then lays out every atom on its own line
     * (the owner's broken export screenshot). A string test cannot observe the
     * resulting layout, so this pins the stylesheet state that caused it: no
     * `display` on `<math>` at all, and the width cap only on the wrapper.
     */
    private fun assertStylesheetLeavesMathLayoutAlone(html: String) {
        val css = html.substringAfter("<style>").substringBefore("</style>")
        val mathRuleDisplay = Regex("""(^|})\s*math\b[^{}]*\{([^}]*)}""")
            .findAll(css)
            .map { it.groupValues[2] }
            .firstOrNull { it.contains("display") }
        assertNull("no display override on <math>: $mathRuleDisplay", mathRuleDisplay)
        assertFalse("no math[display] selector: $css", css.contains("math[display"))
        assertFalse(
            "the old broken rule is gone: $css",
            css.contains("math { display: inline-block")
        )
        assertTrue(
            "the inline wrapper carries the width cap: $css",
            css.contains(".math-block { display: inline-block; overflow-x: auto; max-width: 100%; }")
        )
    }

    @Test
    fun htmlWrapsABlockFractionAndPowerWithoutTouchingItsMathml() {
        // The previous export CSS made <math> an inline-block with a
        // vertical-align, which destroyed native MathML layout: a fraction
        // and a power rendered as stacks of single characters. The stylesheet
        // assertion above pins the fix; here the markup itself must stay one
        // contiguous formula with no per-token div/br inserted.
        val mathml = "<math><mrow><mfrac><mi>a</mi><mi>b</mi></mfrac>" +
            "<msup><mi>x</mi><mn>2</mn></msup></mrow></math>"
        val paper = PaperDto(
            id = "p1",
            title = "Fractions",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Cat",
                    questions = listOf(
                        QuestionDto(
                            id = "q1",
                            text = "Simplify?",
                            elements = listOf(
                                ContentElement.MathElement(mathml),
                                ContentElement.TextElement(" then check the table")
                            ),
                            options = listOf(OptionDto(id = "a", text = "Yes")),
                            correctOptionIds = listOf("a")
                        )
                    )
                )
            )
        )
        for (quiz in listOf(false, true)) {
            val html = htmlOf(paper, quiz = quiz)
            val label = "quiz=$quiz"
            assertStylesheetLeavesMathLayoutAlone(html)

            val wrapper = inlineMathWrapper
            val wrapperAt = html.indexOf(wrapper)
            val mathAt = html.indexOf("<math>", wrapperAt)
            val mathEnd = html.indexOf("</math>", mathAt)
            assertTrue("$label: the formula is wrapped: $html", wrapperAt >= 0)
            assertTrue("$label: the formula follows the wrapper: $html", mathAt > wrapperAt)
            assertEquals(
                "$label: nothing between wrapper and formula",
                wrapper.length,
                mathAt - wrapperAt
            )
            val formula = html.substring(mathAt, mathEnd + "</math>".length)
            assertTrue("$label: fraction kept: $formula", formula.contains("<mfrac>"))
            assertTrue("$label: power kept: $formula", formula.contains("<msup>"))
            assertFalse("$label: no per-token div: $formula", formula.contains("<div"))
            assertFalse("$label: no injected line break: $formula", formula.contains("<br"))
            assertEquals(
                "$label: the wrapper closes immediately after the formula",
                "</span>",
                html.substring(mathEnd + "</math>".length).take("</span>".length)
            )
            assertTrue("$label: prose after the formula kept: $html", html.contains(" then check the table"))
        }
    }

    @Test
    fun htmlKeepsAnOptionLabelAndFormulaOnOneLine() {
        // Owner screenshot: the wrapper must not push an option's formula onto
        // the next line. The writer's label, the stored option text and the
        // formula stay contiguous — no block element and no newline between
        // them.
        val mathml = "<math><mrow><mi>x</mi><mo>+</mo><mn>1</mn></mrow></math>"
        val paper = PaperDto(
            id = "p1",
            title = "Options",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Cat",
                    questions = listOf(
                        QuestionDto(
                            id = "q1",
                            text = "Pick one?",
                            options = listOf(
                                OptionDto(
                                    id = "a",
                                    text = "",
                                    elements = listOf(
                                        ContentElement.TextElement("A) "),
                                        ContentElement.MathElement(mathml)
                                    )
                                )
                            ),
                            correctOptionIds = emptyList()
                        )
                    )
                )
            )
        )
        val html = htmlOf(paper)
        val expected = "A) $inlineMathWrapper$mathml</span>"
        assertTrue("label and formula share one line: $html", html.contains(expected))
        assertFalse(
            "no block wrapper around the formula: $html",
            html.contains("<div class=\"math-block\"")
        )
        val expectedAt = html.indexOf(expected)
        assertTrue("the label precedes the formula: $html", expectedAt >= 0)
        assertFalse(
            "no newline injected between label and formula",
            html.substring(expectedAt, expectedAt + expected.length).contains("\n")
        )
    }

    @Test
    fun htmlKeepsASentenceAndItsTrailingFormulaContiguous() {
        // An explanation reads "… formula." — the period must follow the
        // formula on the same line, so text, wrapper and text stay in order.
        val mathml = "<math><msup><mi>c</mi><mn>2</mn></msup></math>"
        val paper = PaperDto(
            id = "p1",
            title = "Explanations",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Cat",
                    questions = listOf(
                        QuestionDto(
                            id = "q1",
                            text = "What is it?",
                            options = listOf(OptionDto(id = "a", text = "Yes")),
                            correctOptionIds = listOf("a"),
                            explanationElements = listOf(
                                ContentElement.TextElement("By the identity "),
                                ContentElement.MathElement(mathml),
                                ContentElement.TextElement(".")
                            )
                        )
                    )
                )
            )
        )
        val html = htmlOf(paper)
        val expected = "By the identity $inlineMathWrapper$mathml</span>."
        val start = html.indexOf("By the identity ")
        assertTrue("the explanation is rendered: $html", start >= 0)
        assertEquals(
            "text, wrapper and trailing period stay in order: $html",
            expected,
            html.substring(start, start + expected.length)
        )
        assertFalse(
            "no block wrapper around the formula: $html",
            html.contains("<div class=\"math-block\"")
        )
    }

    @Test
    fun htmlKeepsAMathSpaceMeaningfulAndRepairsALegacyBareElement() {
        // A cell formula using `<mspace/>`: the tag is emitted as one empty
        // element, so the atom after it is not swallowed.
        val spaced = htmlOf(cellChemPaper("a <mi>a</mi><mspace width=\"0.5em\"/><mi>b</mi> b"))
        assertTrue("space preserved: $spaced", spaced.contains("<mspace/>"))
        assertTrue("sibling survives: $spaced", spaced.contains("<mi>b</mi>"))
        assertFalse("never an open space tag: $spaced", spaced.contains("<mspace>"))

        // A bare run stored as a body MathElement is repaired too.
        val paper = PaperDto(
            id = "p1",
            title = "Legacy",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Cat",
                    questions = listOf(
                        QuestionDto(
                            id = "q1",
                            text = "Solve?",
                            elements = listOf(
                                ContentElement.TextElement("Solve: "),
                                ContentElement.MathElement("<msup><mi>x</mi><mn>2</mn></msup>")
                            ),
                            options = listOf(OptionDto(id = "a", text = "Yes")),
                            correctOptionIds = listOf("a")
                        )
                    )
                )
            )
        )
        val html = htmlOf(paper)
        assertTrue(
            "the legacy run is a complete block: $html",
            html.contains("<math><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow></math>")
        )
        assertFalse("never raw tag text: $html", html.contains("&lt;msup"))
    }

    @Test
    fun htmlLeavesAPlainTableOnItsOldMarkup() {
        // No chem cell: the markup and therefore the auto layout stay exactly
        // as they were, so an ordinary table's columns are unchanged.
        val html = htmlOf(richPaper())

        assertTrue(
            "plain table markup: $html",
            html.contains("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">")
        )
        assertFalse("no chem-table class: $html", html.contains("<table class="))
    }

    @Test
    fun htmlKeepsAWideStructureImageInsideAFixedLayoutTable() {
        // A wide PNG would widen an auto-layout grid past the page cap; the
        // chem table's fixed columns and image cap are what hold it inside.
        val wideBytes = ByteArray(24).also { b ->
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b, 0)
            b[16] = 0; b[17] = 0; b[18] = 0x06.toByte(); b[19] = 0x40 // width = 1600
            b[20] = 0; b[21] = 0; b[22] = 0x03.toByte(); b[23] = 0x84.toByte() // height = 900
        }
        val wide = "data:image/png;base64," +
            java.util.Base64.getEncoder().encodeToString(wideBytes)
        val element = ContentElement.ChemElement(
            smiles = "c1ccccc1",
            formula = "C6H6",
            caption = "Benzene",
            diagram = wide
        )
        val html = htmlOf(cellChemPaper(com.mcqapp.util.chemCellHtml(element)))

        val tableAt = html.indexOf("<table class=\"mcq-rich-table\"")
        val imageAt = html.indexOf("<img src=\"$wide\"")
        val closeAt = html.indexOf("</table>")
        assertTrue("the chem table is fixed-layout: $html", tableAt >= 0)
        assertTrue(
            "the wide structure image sits inside that table: $html",
            imageAt > tableAt && imageAt < closeAt
        )
        assertTrue(
            "its size is capped by the cell rule: $html",
            html.contains("table.mcq-rich-table td img{max-width:100%;height:auto;max-height:240px}")
        )
    }

    @Test
    fun htmlAnswerNamesTheOptionLabelNotItsContent() {
        val q = QuestionDto(
            id = "q1",
            text = "Pick one?",
            options = listOf(
                OptionDto(
                    id = "a",
                    text = "10<sup>-3</sup>",
                    elements = listOf(
                        com.mcqapp.domain.ContentElement.TextElement("10<sup>-3</sup>")
                    )
                )
            ),
            correctOptionIds = listOf("a")
        )
        val paper = PaperDto(
            id = "p1",
            title = "Paper",
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = listOf(q)))
        )
        val html = htmlOf(paper)
        // The answer names the label the option carries, so a reader can match
        // it against the list; the option keeps the content and its markup.
        assertTrue(html.contains("Answer: A</p>"))
        assertFalse(html.contains("Answer: 10"))
        assertTrue(html.contains("A) 10<sup>-3</sup>"))
        assertFalse(html.contains("&lt;sup&gt;"))
    }

    @Test
    fun htmlLabelsOptionsByPositionNotByStoredId() {
        // Imported banks key their options by whatever the source used, so the
        // label a reader sees is the position and the answer must name it.
        val q = QuestionDto(
            id = "q1",
            text = "Pick two?",
            options = listOf(
                OptionDto(id = "opt-1", text = "one"),
                OptionDto(id = "opt-2", text = "two"),
                OptionDto(id = "opt-3", text = "three")
            ),
            correctOptionIds = listOf("opt-3", "opt-1")
        )
        val paper = PaperDto(
            id = "p1",
            title = "Paper",
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = listOf(q)))
        )
        val html = htmlOf(paper)
        assertTrue(html.contains("A) one"))
        assertTrue(html.contains("C) three"))
        assertTrue(html.contains("Answer: A, C</p>"))
    }

    @Test
    fun quizHtmlAlsoAnswersWithLabels() {
        val q = QuestionDto(
            id = "q1",
            text = "Pick one?",
            options = listOf(
                OptionDto(id = "opt-1", text = "one"),
                OptionDto(id = "opt-2", text = "two")
            ),
            correctOptionIds = listOf("opt-2")
        )
        val paper = PaperDto(
            id = "p1",
            title = "Paper",
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = listOf(q)))
        )
        val html = htmlOf(paper, quiz = true)
        assertTrue(html.contains("Answer: B</p>"))
        assertFalse(html.contains("Answer: two"))
    }

    @Test
    fun stripInlineHtmlKeepsBareSymbols() {
        assertEquals("H2O", com.mcqapp.data.export.stripInlineHtml("H<sub>2</sub>O"))
        assertEquals("5 < 6 & 7", com.mcqapp.data.export.stripInlineHtml("5 < 6 & 7"))
        assertEquals("axb", com.mcqapp.data.export.stripInlineHtml("a<foo>x</foo>b"))
    }

    @Test
    fun mathToLinearPrefersTexAnnotation() {
        val mathml = "<math><semantics><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow>" +
            "<annotation encoding=\"application/x-tex\">x^{2} + 2x + 1 = 0</annotation></semantics></math>"
        assertEquals(
            "x^{2} + 2x + 1 = 0",
            com.mcqapp.data.export.mathToLinear(mathml)
        )
    }

    @Test
    fun mathToLinearStripsTagsWithoutAnnotation() {
        assertEquals(
            "x2+2",
            com.mcqapp.data.export.mathToLinear("<math><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>2</mn></math>")
        )
    }

    // ---- passage headers (D8: every member repeats its passage) ----

    private fun passagePaper(): PaperDto {
        val passage = com.mcqapp.data.io.PassageDto(
            id = "passage-1",
            title = "The water cycle",
            elements = listOf(com.mcqapp.domain.ContentElement.TextElement("Rain fills rivers."))
        )
        val members = listOf("q1", "q2").map { id ->
            QuestionDto(
                id = id,
                text = "Question $id?",
                options = listOf(OptionDto(id = "a", text = "A"), OptionDto(id = "b", text = "B")),
                correctOptionIds = listOf("a"),
                passageId = "passage-1"
            )
        }
        val standalone = QuestionDto(
            id = "q3",
            text = "Standalone?",
            options = listOf(OptionDto(id = "a", text = "A"), OptionDto(id = "b", text = "B")),
            correctOptionIds = listOf("a")
        )
        return PaperDto(
            id = "p1",
            title = "Paper",
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = members + standalone)),
            passages = listOf(passage)
        )
    }

    @Test
    fun htmlPrintsThePassageOnceAboveItsGroup() {
        val paper = passagePaper()
        val html = htmlOf(paper)
        // Two members share ONE header; the standalone question gets none.
        assertEquals(1, html.split("class=\"passage\"").size - 1)
        assertEquals(1, html.split("The water cycle").size - 1)
        assertTrue(html.contains("Rain fills rivers."))
        // The header sits above the first member and below nothing: the
        // standalone follows the group.
        val passageAt = html.indexOf("class=\"passage\"")
        val firstMemberAt = html.indexOf("Question q1?")
        val secondMemberAt = html.indexOf("Question q2?")
        val standaloneAt = html.indexOf("Standalone?")
        assertTrue("header must sit above the first member", passageAt < firstMemberAt)
        assertTrue("members stay together", firstMemberAt < secondMemberAt)
        assertTrue("standalone follows the group", secondMemberAt < standaloneAt)
    }

    @Test
    fun aSplitRunPrintsItsHeaderAgain() {
        // The same passage resuming after a standalone prints again — the
        // run-detection is per contiguous group, not per passage id.
        val paper = passagePaper()
        val split = paper.copy(
            categories = paper.categories.map { cat ->
                cat.copy(
                    questions = listOf(
                        cat.questions[0],           // member
                        cat.questions[2],           // standalone
                        cat.questions[1]            // member again
                    )
                )
            }
        )
        val html = htmlOf(split)
        assertEquals(2, html.split("class=\"passage\"").size - 1)
    }

    @Test
    fun htmlWithoutPassageDataRendersNoPassageBlock() {
        // A legacy paper carries no passages at all, so the members have
        // nothing to print even though they still name one.
        val html = htmlOf(passagePaper().copy(passages = emptyList()))
        assertFalse(html.contains("class=\"passage\""))
    }

    @Test
    fun htmlRendersALegacyPassageImageWithoutElements() {
        // A passage can carry a legacy image and no elements. The other two
        // writers print it — PDF appends the legacy image after its element
        // loop, DOCX embeds it unconditionally — while this block skipped it
        // and left a title with nothing under it.
        val src = "data:image/png;base64,iVBORw0KGgo="
        val paper = passagePaper().copy(
            passages = passagePaper().passages.map { it.copy(elements = emptyList(), image = src) }
        )

        val html = htmlOf(paper)

        assertTrue(html.contains("<p class=\"passage-title\">The water cycle</p>"))
        assertTrue("the legacy passage image must print", html.contains("src=\"$src\""))
        // One header for the group, so one copy of the image.
        assertEquals(1, html.split("src=\"$src\"").size - 1)
    }

    @Test
    fun quizHtmlPrintsThePassageOnceAboveItsGroup() {
        val paper = passagePaper()
        val html = htmlOf(paper, quiz = true)
        assertEquals(1, html.split("class=\"passage\"").size - 1)
    }

    @Test
    fun zipCarriesThePassageBlockInPaperJson() {
        val bytes = zipOf(passagePaper())
        var json = ""
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "paper.json") json = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertTrue(json.contains("\"passages\""))
        assertTrue(json.contains("The water cycle"))
        assertTrue(json.contains("\"passageId\":\"passage-1\"") || json.contains("\"passageId\": \"passage-1\""))
    }

    @Test
    fun aPaperWithSeveralCategoriesStaysOneValidJsonEnvelope() = runBlocking {
        // Regression: open() used to append the next category straight after
        // the previous one's ']}' with no comma, so two categories invalidated
        // the whole document — JSON_INLINE, and the paper.json inside ZIP and
        // DOCX (both go through this writer).
        val q1 = QuestionDto(
            id = "q1",
            text = "First question",
            options = listOf(
                OptionDto(id = "a", text = "Alpha"),
                OptionDto(id = "b", text = "Beta")
            ),
            correctOptionIds = listOf("a")
        )
        val q2 = q1.copy(id = "q2", text = "Second question")
        val dto = PaperDto(
            id = "p1",
            title = "Two branches",
            categories = listOf(
                CategoryDto(id = "c1", title = "First", questions = listOf(q1)),
                CategoryDto(id = "c2", title = "Second", questions = listOf(q2))
            )
        )
        val out = StringBuilder()
        PaperJsonWriter.writePaperJson(out, PaperStream.of(dto))

        // Parse it for real: an unterminated array is what the old shape threw.
        val root = Json.parseToJsonElement(out.toString()).jsonObject
        val categories = root.getValue("papers").jsonArray[0].jsonObject
            .getValue("categories").jsonArray
        assertEquals(2, categories.size)
        assertEquals(
            listOf("First", "Second"),
            categories.map { it.jsonObject.getValue("title").jsonPrimitive.content }
        )
    }

    // pdfWithPassagesStaysAValidPdf moved to PdfPaperWriterDeviceTest in
    // app/src/androidTest: the PDF byte assertions only run where the real
    // android.graphics.pdf.PdfDocument works (the CI emulator), not under
    // Robolectric, so they no longer sit here behind @Ignore.
}
