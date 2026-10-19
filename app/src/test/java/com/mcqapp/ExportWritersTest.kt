package com.mcqapp

import com.mcqapp.data.export.HtmlPaperWriter
import com.mcqapp.data.export.ZipPaperWriter
import com.mcqapp.data.export.parseDataUri
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class ExportWritersTest {

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

    @Ignore(
        "android.graphics.pdf.PdfDocument is not mockable in the plain JVM suite (see " +
            "PdfPaperWriterTest); this stays structural-only on a host where PdfDocument works."
    )
    @Test
    fun pdfWithPassagesStaysAValidPdf() {
        val bytes = pdfOf(passagePaper())
        assertTrue(bytes.size > 400)
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("%%EOF"))
    }
}
