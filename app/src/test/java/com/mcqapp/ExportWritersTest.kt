package com.mcqapp

import com.mcqapp.data.export.HtmlPaperWriter
import com.mcqapp.data.export.ZipPaperWriter
import com.mcqapp.data.export.parseDataUri
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val html = HtmlPaperWriter.paperToHtml(samplePaper())
        assertTrue(html.contains("Pick &lt;one&gt; &amp; &quot;all&quot;?"))
        assertTrue(html.contains("Paper &amp; Title"))
        assertTrue(html.contains("Because &lt;reasons&gt;"))
        assertTrue(html.contains("class=\"correct\""))
        assertFalse(html.contains("<one>"))
    }

    @Test
    fun htmlEmbedsDataUriImage() {
        val src = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val html = HtmlPaperWriter.paperToHtml(samplePaper(src))
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
        val html = HtmlPaperWriter.paperToHtml(paper)
        assertEquals(1, html.split("<img src=\"$questionSrc\"").size - 1)
        assertEquals(1, html.split("<img src=\"$optionSrc\"").size - 1)
    }

    @Test
    fun quizHtmlHidesAnswersBehindToggle() {
        val html = HtmlPaperWriter.paperToQuizHtml(samplePaper())
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
        val bytes = ZipPaperWriter.paperToZipBytes(samplePaper(src))

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

    @Test
    fun zipKeepsRemoteUrlsUntouched() {
        val bytes = ZipPaperWriter.paperToZipBytes(
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
        val html = HtmlPaperWriter.paperToHtml(richPaper())
        assertTrue(html.contains("H<sub>2</sub>O <strong>x</strong>"))
        assertFalse(html.contains("&lt;sub&gt;"))
    }

    @Test
    fun htmlEscapesUnknownInlineTags() {
        val html = HtmlPaperWriter.paperToHtml(richPaper())
        assertFalse(html.contains("<foo>"))
        assertTrue(html.contains("&lt;foo&gt;y&lt;/foo&gt;"))
    }

    @Test
    fun htmlRendersTableAndMathElements() {
        val html = HtmlPaperWriter.paperToHtml(richPaper())
        assertTrue(html.contains("<td>a&lt;b</td>"))
        assertTrue(html.contains("<math><mi>x</mi></math>"))
    }

    @Test
    fun htmlAnswerLineRendersInlineTags() {
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
        val html = HtmlPaperWriter.paperToHtml(paper)
        assertTrue(html.contains("Answer: 10<sup>-3</sup>"))
        assertFalse(html.contains("&lt;sup&gt;"))
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
}
