package com.mcqapp

import com.mcqapp.data.docx.questionJsonForTest
import com.mcqapp.data.docx.splitDocxMarkersForTest
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.domain.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The README documents the exact JSON and `.docx` shapes the importers accept.
 * These tests feed the documented snippets to the real parsers, so a README
 * that drifts from the code (or the reverse) fails here rather than in somebody's
 * import.
 */
class ReadmeExamplesTest {

    private val readme: String by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "README.md")
            if (candidate.isFile && candidate.readText().startsWith("# MCQ App")) {
                return@lazy candidate.readText()
            }
            dir = dir.parentFile
        }
        error("README.md not found above ${File("").absolutePath}")
    }

    /** The first fenced code block appearing after [anchor], with any info string dropped. */
    private fun blockAfter(anchor: String): String {
        val start = readme.indexOf(anchor)
        assertTrue("README anchor missing: $anchor", start >= 0)
        val fence = readme.indexOf("```", start)
        assertTrue("no code block after: $anchor", fence > start)
        val body = readme.indexOf('\n', fence)
        assertTrue("unterminated fence after: $anchor", body > fence)
        val end = readme.indexOf("\n```", body)
        assertTrue("unterminated block after: $anchor", end > body)
        return readme.substring(body + 1, end).trim()
    }

    /** XML text escaping for sample lines embedded in a generated document. */
    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * The Word example in the README must parse, and the `)` of `1.)` must not
     * leak into the question text.
     */
    @Test
    fun docxExampleImportsCleanly() {
        val questions = splitDocxMarkersForTest(blockAfter("A complete, valid document:"))
        assertEquals(2, questions.size)

        assertEquals("1.)", questions[0].num)
        assertEquals("Which gas do plants absorb during photosynthesis?", questions[0].stemHtml)
        assertEquals("Oxygen", questions[0].options["a"])
        assertEquals("Carbon dioxide", questions[0].options["b"])
        assertEquals("Nitrogen", questions[0].options["c"])
        assertEquals("Hydrogen", questions[0].options["d"])
        assertEquals("b", questions[0].answer)
        assertEquals("Photosynthesis fixes carbon dioxide into glucose using light energy.", questions[0].explanationHtml)

        assertEquals("What is the capital of France?", questions[1].stemHtml)
        assertEquals("Paris", questions[1].options["c"])
        assertEquals("c", questions[1].answer)
    }

    /** The same example end-to-end, so the documented answer letters really resolve. */
    @Test
    fun docxExampleResolvesAnswerKeys() {
        val raw = splitDocxMarkersForTest(blockAfter("A complete, valid document:"))
        val array = raw.joinToString(",", "[", "]") { questionJsonForTest(it).toString() }
        val file = LegacyParser.parse(array)
        val questions = file.papers[0].categories[0].questions
        assertEquals(listOf("b"), questions[0].correctOptionIds)
        assertEquals(listOf("c"), questions[1].correctOptionIds)
        assertTrue(file.warnings.isEmpty())
    }

    /** The `)` in `N.)` is mandatory: `1.` alone finds no questions. */
    @Test
    fun questionMarkerRequiresClosingParenthesis() {
        val withoutParen = "1. What is 2+2?\n(a) 3\n(b) 4\n(c) 5\n(d) 6\nAns. a\nExp: because"
        try {
            splitDocxMarkersForTest(withoutParen)
            fail("expected a marker without ')' to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("No questions found"))
        }
    }

    /** The documented `Passage:` example: one standalone question, two members. */
    @Test
    fun docxPassageExampleGroupsMembers() {
        val body = blockAfter("### The `Passage:` marker")
        val paragraphs = body.lines().filter { it.isNotBlank() }.joinToString("") { line ->
            "<w:p><w:r><w:t>${xmlEscape(line)}</w:t></w:r></w:p>"
        }
        val documentXml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>$paragraphs</w:body></w:document>"
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray())
            zip.closeEntry()
        }
        val file = LegacyParser.parse(com.mcqapp.data.docx.parseDocx(baos.toByteArray()).json)
        assertEquals(1, file.passages.size)
        assertEquals("The water cycle", file.passages.single().title)
        val questions = file.papers.single().categories.single().questions
        assertEquals(3, questions.size)
        // The pre-marker question stays standalone; the two after it are members.
        assertEquals(null, questions[0].passageId)
        assertEquals("passage-1", questions[1].passageId)
        assertEquals("passage-1", questions[2].passageId)
        assertTrue(file.warnings.isEmpty())
    }

    /** A bare array is wrapped into one paper and one category, with a..d option ids. */
    @Test
    fun jsonBareArrayExample() {
        val file = LegacyParser.parse(blockAfter("**1. Bare array**"))
        assertTrue(file.warnings.isEmpty())
        assertEquals(1, file.papers.size)
        assertEquals("Imported Questions", file.papers[0].title)
        assertEquals(1, file.papers[0].categories.size)
        assertEquals("Uncategorized", file.papers[0].categories[0].title)

        val question = file.papers[0].categories[0].questions.single()
        assertTrue(question.text.contains("Red Planet"))
        assertEquals(listOf("a", "b", "c", "d"), question.options.map { it.id })
        assertEquals(emptyList<String>(), question.correctOptionIds)
        assertTrue(file.bookmarks.isEmpty())
        assertTrue(file.attempts.isEmpty())
        assertTrue(file.scheduling.isEmpty())
    }

    /** Every documented element type is really read. */
    @Test
    fun jsonRichContentExample() {
        val snippet = blockAfter("**4. Rich content.**")
        val file = LegacyParser.parse(
            """{"papers":[{"title":"T","categories":[{"title":"C","questions":[$snippet]}]}]}"""
        )
        val question = file.papers[0].categories[0].questions.single()

        assertTrue(question.elements.any { it is ContentElement.TextElement })
        assertTrue(question.elements.any { it is ContentElement.MathElement })
        val image = question.elements.filterIsInstance<ContentElement.ImageElement>().single()
        assertTrue(image.src.startsWith("data:image/png;base64,"))
        val table = question.elements.filterIsInstance<ContentElement.TableElement>().single()
        assertEquals(listOf(listOf("Ruler", "Year"), listOf("Akbar", "1556")), table.rows)

        // options_elements keys are the option ids the answer key must reference.
        assertEquals(listOf("a", "b"), question.options.map { it.id })
        assertEquals("Akbar — 1556", question.options[0].text)
    }

    /** Numeric answers are zero-based, as the README states. */
    @Test
    fun numericAnswerIsZeroBased() {
        val file = LegacyParser.parse(
            """[{"text":"q","options":["alpha","beta","gamma","delta"],"answer":"1"}]"""
        )
        assertEquals(
            listOf("b"),
            file.papers[0].categories[0].questions.single().correctOptionIds
        )
    }
}
