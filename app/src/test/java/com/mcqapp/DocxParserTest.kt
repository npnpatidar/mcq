package com.mcqapp

import com.mcqapp.data.docx.fieldElements
import com.mcqapp.data.docx.isDocxArchive
import com.mcqapp.data.docx.parseDocx
import com.mcqapp.data.docx.questionJsonForTest
import com.mcqapp.data.docx.rawQuestionForTest
import com.mcqapp.data.docx.splitDocxMarkersForTest
import com.mcqapp.data.io.LegacyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocxParserTest {

    private fun fixtureBytes(): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("fixtures/sample.docx")
            ?: throw IllegalStateException("missing test fixture")
        return stream.use { it.readBytes() }
    }

    @Test
    fun fixtureParsesThreeQuestions() {
        val parsed = parseDocx(fixtureBytes())
        val file = LegacyParser.parse(parsed.json)
        val questions = file.papers[0].categories[0].questions
        assertEquals(3, questions.size)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun formattingRunsSurvive() {
        val parsed = parseDocx(fixtureBytes())
        val file = LegacyParser.parse(parsed.json)
        val questions = file.papers[0].categories[0].questions
        val text = questions[0].text
        assertTrue(text.contains("H<sub>2</sub>O"))
        assertTrue(text.contains("<sup>x</sup>"))
        assertTrue(text.contains("<strong>bold</strong>"))
        assertTrue(text.contains("<em>ital</em>"))
        val exp = questions[2].explanationElements.joinToString("") {
            (it as com.mcqapp.domain.ContentElement.TextElement).text
        }
        assertTrue(exp.contains("<mark>yes</mark>"))
        assertTrue(exp.contains("<u>ok</u>"))
        assertTrue(exp.contains("<del>no</del>"))
    }

    @Test
    fun equationBecomesMathElement() {
        val parsed = parseDocx(fixtureBytes())
        val file = LegacyParser.parse(parsed.json)
        val elements = file.papers[0].categories[0].questions[0].elements
        assertTrue(elements.any {
            it is com.mcqapp.domain.ContentElement.MathElement &&
                it.mathml.contains("<msup>")
        })
    }

    @Test
    fun tableAndImageElements() {
        val parsed = parseDocx(fixtureBytes())
        val file = LegacyParser.parse(parsed.json)
        val elements = file.papers[0].categories[0].questions[1].elements
        val table = elements.filterIsInstance<com.mcqapp.domain.ContentElement.TableElement>().single()
        assertEquals(listOf(listOf("King", "Year"), listOf("Akbar", "1556")), table.rows)
        val explanation = file.papers[0].categories[0].questions[1].explanationElements
        val image = explanation.filterIsInstance<com.mcqapp.domain.ContentElement.ImageElement>().single()
        assertTrue(image.src.startsWith("data:image/png;base64,"))
    }

    @Test
    fun answersResolve() {
        val parsed = parseDocx(fixtureBytes())
        val file = LegacyParser.parse(parsed.json)
        val questions = file.papers[0].categories[0].questions
        assertEquals(listOf("b"), questions[0].correctOptionIds)
        assertEquals(listOf("a"), questions[1].correctOptionIds)
        assertEquals(listOf("c"), questions[2].correctOptionIds)
    }

    @Test
    fun missingAnswerFailsLoudly() {
        val bad = "1.) Stem\n(a) A\n(b) B\n(c) C\n(d) D\nExp: Why."
        try {
            splitDocxMarkersForTest(bad)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1.)"))
            assertTrue(e.message!!.contains("answer"))
        }
    }

    @Test
    fun noMarkersFailsLoudly() {
        try {
            splitDocxMarkersForTest("Just some prose, no markers.")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("N.)"))
        }
    }

    @Test
    fun fixtureSniffsAsDocx() {
        assertTrue(isDocxArchive(fixtureBytes()))
    }

    @Test
    fun nonZipSniffsAsNotDocx() {
        assertTrue(!isDocxArchive("{\"papers\": []}".toByteArray()))
        assertTrue(!isDocxArchive(ByteArray(0)))
    }

    @Test
    fun zipWithoutDocumentXmlSniffsAsNotDocx() {
        val buf = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buf).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("collection.anki2"))
            zip.write(ByteArray(16))
            zip.closeEntry()
        }
        assertTrue(!isDocxArchive(buf.toByteArray()))
    }

    @Test
    fun notADocxFailsLoudly() {        try {
            parseDocx("definitely not a zip".toByteArray())
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(".docx", ignoreCase = true))
        }
    }

    @Test
    fun fieldElementsSplitImgAndTable() {
        val els = fieldElements(
            "See <img src=\"data:image/png;base64,AAA\"> plus " +
                "<table border=\"1\"><tr><td>a</td><td>b</td></tr></table> end"
        )
        assertEquals(listOf("text", "image", "text", "table", "text"), els.map { it["type"] })
        val table = els[3]["content"] as List<List<String>>
        assertEquals(listOf(listOf("a", "b")), table)
    }

    @Test
    fun questionJsonShapeMatchesImporter() {
        val q = rawQuestionForTest(
            num = "1.)",
            stemHtml = "What?",
            options = mapOf("a" to "Yes", "b" to "No", "c" to "Maybe", "d" to "Later"),
            answer = "a",
            explanationHtml = "Because."
        )
        val json = questionJsonForTest(q).toString()
        // LegacyParser must accept it end-to-end (question_num ignored).
        val file = LegacyParser.parse("[$json]")
        val parsed = file.papers[0].categories[0].questions.single()
        assertEquals("What?", parsed.text)
        assertEquals(listOf("a"), parsed.correctOptionIds)
    }
}
