package com.mcqapp

import com.mcqapp.data.docx.parseXml
import com.mcqapp.data.export.DocxPaperWriter
import com.mcqapp.data.export.imageDimensions
import com.mcqapp.data.export.parseDataUri
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/**
 * The Word export writes hand-rolled OOXML; these tests check the package
 * structure and the marker grammar the app's own DOCX importer reads back.
 */
class DocxPaperWriterTest {

    private fun pngDataUri(size: Int = 8): String =
        "data:image/png;base64," + java.util.Base64.getEncoder()
            .encodeToString(pngBytes(size, size))

    /** A minimal valid PNG: signature, IHDR with width/height, IEND. */
    private fun pngBytes(w: Int, h: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(byteArrayOf(0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()))
        out.write(byteArrayOf(
            (w shr 24).toByte(), (w shr 16).toByte(), (w shr 8).toByte(), w.toByte(),
            (h shr 24).toByte(), (h shr 16).toByte(), (h shr 8).toByte(), h.toByte()
        ))
        out.write(byteArrayOf(8, 6, 0, 0, 0))
        out.write(byteArrayOf(0, 0, 0, 0, 'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte()))
        return out.toByteArray()
    }

    private fun paper(imageSrc: String? = null): PaperDto {
        val q = QuestionDto(
            id = "q1",
            text = "What is H2O, <one> & \"why\"?",
            options = listOf(
                OptionDto(id = "a", text = "Alpha"),
                OptionDto(id = "b", text = "Beta")
            ),
            correctOptionIds = listOf("b"),
            explanation = "Because <reasons>"
        )
        return PaperDto(
            id = "p1",
            title = "Paper & Title",
            description = "Desc",
            durationMinutes = 30,
            negativeMarking = 0.25,
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = listOf(q)))
        )
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun documentXml(bytes: ByteArray): String =
        entries(bytes).getValue("word/document.xml").toString(Charsets.UTF_8)

    @Test
    fun packageStructureIsComplete() {
        val files = entries(DocxPaperWriter.paperToDocxBytes(paper()))
        for (name in listOf(
            "[Content_Types].xml", "_rels/.rels", "word/document.xml",
            "word/_rels/document.xml.rels", "word/styles.xml"
        )) {
            assertTrue("missing $name", files.containsKey(name))
        }
        // The parts must form XML a strict parser accepts.
        parseXml(files.getValue("word/document.xml"), "document")
        parseXml(files.getValue("word/styles.xml"), "styles")
        parseXml(files.getValue("[Content_Types].xml"), "content types")
    }

    @Test
    fun textIsEscapedAndUnknownTagsStayLiteral() {
        val xml = documentXml(DocxPaperWriter.paperToDocxBytes(paper()))
        // Stored text decodes to what the screen shows, then escapes for XML.
        // "<one>" is literal text (its own run, as the inline splitter
        // produces), the ampersand and quotes become entities.
        assertTrue(xml.contains("What is H2O, "))
        assertTrue(xml.contains("\u0026lt;one\u0026gt;"))
        assertTrue(xml.contains(" \u0026amp; \"why\"?"))
        assertTrue(xml.contains("Because "))
        assertTrue(xml.contains("\u0026lt;reasons\u0026gt;"))
        assertTrue(xml.contains("Paper \u0026amp; Title"))
    }

    @Test
    fun markerGrammarLines() {
        val xml = documentXml(DocxPaperWriter.paperToDocxBytes(paper()))
        assertTrue(xml.contains(">1.) <"))
        assertTrue(xml.contains(">(a) <"))
        assertTrue(xml.contains(">(b) <"))
        assertTrue(xml.contains(">Ans. b<"))
        assertTrue(xml.contains(">Exp: <"))
    }

    @Test
    fun headingsCarryTheTree() {
        val paper = PaperDto(
            id = "p",
            title = "T",
            categories = listOf(
                CategoryDto(id = "root", title = "Root"),
                CategoryDto(id = "child", title = "Child", parentId = "root"),
                CategoryDto(id = "grand", title = "Grand", parentId = "child")
            )
        )
        val xml = documentXml(DocxPaperWriter.paperToDocxBytes(paper))
        assertTrue(xml.contains("Heading1"))
        assertTrue(xml.contains(">Root</w:t>"))
        assertTrue(xml.contains("Heading2"))
        assertTrue(xml.contains("Heading3"))
        assertTrue(xml.contains("Heading4"))
    }

    @Test
    fun inlineTagsBecomeRunProperties() {
        val q = QuestionDto(
            id = "q",
            text = "<strong>b</strong><em>i</em><u>u</u><del>s</del>" +
                "<sub>down</sub><sup>up</sup><mark>hi</mark>",
            options = listOf(
                OptionDto(id = "a", text = "x"),
                OptionDto(id = "b", text = "y")
            ),
            correctOptionIds = listOf("a"),
            explanation = "e"
        )
        val xml = documentXml(
            DocxPaperWriter.paperToDocxBytes(
                PaperDto(id = "p", title = "T", categories = listOf(CategoryDto("c", "C", questions = listOf(q))))
            )
        )
        assertTrue(xml.contains("<w:b/>"))
        assertTrue(xml.contains("<w:i/>"))
        assertTrue(xml.contains("<w:u w:val=\"single\"/>"))
        assertTrue(xml.contains("<w:strike/>"))
        assertTrue(xml.contains("<w:vertAlign w:val=\"subscript\"/>"))
        assertTrue(xml.contains("<w:vertAlign w:val=\"superscript\"/>"))
        assertTrue(xml.contains("<w:highlight w:val=\"yellow\"/>"))
    }

    @Test
    fun mathBecomesOmath() {
        val q = QuestionDto(
            id = "q",
            elements = listOf(
                ContentElement.TextElement("v = "),
                ContentElement.MathElement("<math><mi>v</mi><mo>=</mo><mi>u</mi></math>")
            ),
            options = listOf(OptionDto(id = "a", text = "x"), OptionDto(id = "b", text = "y")),
            correctOptionIds = listOf("a"),
            explanation = "e"
        )
        val xml = documentXml(
            DocxPaperWriter.paperToDocxBytes(
                PaperDto(id = "p", title = "T", categories = listOf(CategoryDto("c", "C", questions = listOf(q))))
            )
        )
        assertTrue(xml.contains("<m:oMath>"))
        assertTrue(xml.contains("<m:t xml:space=\"preserve\">v</m:t>"))
    }

    @Test
    fun dataUriImageBecomesMediaAndDrawing() {
        val src = pngDataUri()
        val q = QuestionDto(
            id = "q",
            elements = listOf(ContentElement.ImageElement(src)),
            options = listOf(OptionDto(id = "a", text = "x"), OptionDto(id = "b", text = "y")),
            correctOptionIds = listOf("a")
        )
        val files = entries(
            DocxPaperWriter.paperToDocxBytes(
                PaperDto(id = "p", title = "T", categories = listOf(CategoryDto("c", "C", questions = listOf(q))))
            )
        )
        val media = files.keys.filter { it.startsWith("word/media/") }
        assertEquals(1, media.size)
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue(xml.contains("<w:drawing>"))
        assertTrue(xml.contains("r:embed=\"rIdMedia1\""))
        val rels = files.getValue("word/_rels/document.xml.rels").toString(Charsets.UTF_8)
        assertTrue(rels.contains("Target=\"media/"))
        // Same bytes as the source image.
        val expected = parseDataUri(src)!!.bytes
        assertTrue(files[media[0]]!!.contentEquals(expected))
    }

    @Test
    fun duplicateImagesAreStoredOnce() {
        val src = pngDataUri()
        val q = QuestionDto(
            id = "q",
            elements = listOf(
                ContentElement.ImageElement(src),
                ContentElement.ImageElement(src)
            ),
            options = listOf(OptionDto(id = "a", text = "x"), OptionDto(id = "b", text = "y")),
            correctOptionIds = listOf("a")
        )
        val files = entries(
            DocxPaperWriter.paperToDocxBytes(
                PaperDto(id = "p", title = "T", categories = listOf(CategoryDto("c", "C", questions = listOf(q))))
            )
        )
        assertEquals(1, files.keys.count { it.startsWith("word/media/") })
    }

    @Test
    fun passageBlockRidesAboveItsGroup() {
        val q1 = QuestionDto(
            id = "q1", text = "One",
            options = listOf(OptionDto(id = "a", text = "x"), OptionDto(id = "b", text = "y")),
            correctOptionIds = listOf("a"), passageId = "passage-1"
        )
        val q2 = QuestionDto(
            id = "q2", text = "Two",
            options = listOf(OptionDto(id = "a", text = "x"), OptionDto(id = "b", text = "y")),
            correctOptionIds = listOf("b"), passageId = "passage-1"
        )
        val bytes = DocxPaperWriter.paperToDocxBytes(
            PaperDto(id = "p", title = "T", categories = listOf(CategoryDto("c", "C", questions = listOf(q1, q2)))),
            passages = listOf(PassageDto(id = "passage-1", title = "The Passage", elements = listOf(
                ContentElement.TextElement("Shared context")
            )))
        )
        val xml = documentXml(bytes)
        assertTrue(xml.contains(">Passage: </w:t>"))
        assertTrue(xml.contains(">The Passage<"))
        assertTrue(xml.contains(">Shared context<"))
        // The passage body appears once, above both questions.
        assertEquals(1, xml.split("Shared context").size - 1)
    }

    @Test
    fun markerishContinuationLinesAreGuarded() {
        val q = QuestionDto(
            id = "q",
            elements = listOf(
                ContentElement.TextElement("first part<br>2.) looks like a question<br>(a) looks like an option")
            ),
            options = listOf(OptionDto(id = "a", text = "x"), OptionDto(id = "b", text = "y")),
            correctOptionIds = listOf("a")
        )
        val xml = documentXml(
            DocxPaperWriter.paperToDocxBytes(
                PaperDto(id = "p", title = "T", categories = listOf(CategoryDto("c", "C", questions = listOf(q))))
            )
        )
        // The dangerous lines carry one leading space after their break, so
        // the parser keeps them inside the stem. Break and text live in
        // separate runs; assert the guarded text run itself.
        assertTrue(xml.contains("<w:t xml:space=\"preserve\"> 2.) looks like a question"))
        assertTrue(xml.contains("<w:t xml:space=\"preserve\"> (a) looks like an option"))
    }

    @Test
    fun answerlessQuestionNamesFirstOption() {
        val q = QuestionDto(
            id = "q", text = "T",
            options = listOf(OptionDto(id = "a", text = "x"), OptionDto(id = "b", text = "y")),
            correctOptionIds = emptyList()
        )
        val xml = documentXml(
            DocxPaperWriter.paperToDocxBytes(
                PaperDto(id = "p", title = "T", categories = listOf(CategoryDto("c", "C", questions = listOf(q))))
            )
        )
        assertTrue(xml.contains(">Ans. a<"))
    }

    @Test
    fun questionWithoutOptionsIsLeftOut() {
        val q = QuestionDto(id = "q", text = "no options", options = emptyList())
        val xml = documentXml(
            DocxPaperWriter.paperToDocxBytes(
                PaperDto(id = "p", title = "T", categories = listOf(CategoryDto("c", "C", questions = listOf(q))))
            )
        )
        assertTrue(!xml.contains("no options"))
    }

    @Test
    fun topLevelQuestionsGetTheirOwnSection() {
        val q = QuestionDto(
            id = "q", text = "Top level",
            options = listOf(OptionDto(id = "a", text = "x"), OptionDto(id = "b", text = "y")),
            correctOptionIds = listOf("a")
        )
        val xml = documentXml(
            DocxPaperWriter.paperToDocxBytes(
                PaperDto(id = "p", title = "T", questions = listOf(q))
            )
        )
        assertTrue(xml.contains(">Top level<"))
    }

    @Test
    fun imageSizesAreReadFromHeaders() {
        assertEquals(8 to 8, imageDimensions(pngBytes(8, 8)))
        assertNull(imageDimensions("not an image".toByteArray()))
    }

    @Test
    fun jpegSizeIsScanned() {
        // SOF0 marker: FFC0, length 17, precision 8, height 20, width 30.
        // Padded past the 24-byte minimum the reader requires.
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, 0, 20, 0, 30,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0xFF.toByte(), 0xD9.toByte()
        )
        assertEquals(30 to 20, imageDimensions(bytes))
    }

    @Test
    fun remoteImageIsDroppedWithMeaningfulState() {
        val q = QuestionDto(
            id = "q",
            elements = listOf(ContentElement.ImageElement("https://example.com/x.png")),
            options = listOf(OptionDto(id = "a", text = "x"), OptionDto(id = "b", text = "y")),
            correctOptionIds = listOf("a")
        )
        val files = entries(
            DocxPaperWriter.paperToDocxBytes(
                PaperDto(id = "p", title = "T", categories = listOf(CategoryDto("c", "C", questions = listOf(q))))
            )
        )
        assertTrue("nothing may be written to media", files.keys.none { it.startsWith("word/media/") })
        assertNotNull(entries(DocxPaperWriter.paperToDocxBytes(paper())))
    }
}
