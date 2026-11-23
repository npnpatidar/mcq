package com.mcqapp

import com.mcqapp.data.io.CsvTsvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the hand-written RFC-4180 bank reader. */
class CsvTsvParserTest {

    @Test
    fun quotedFieldsKeepCommasQuotesAndNewlines() {
        val rows = CsvTsvParser.parseRows(
            "q,a,b\r\n\"What is \"\"2+2\"\"?\",\"four, five\",\"line1\nline2\"\r\n"
        )
        assertEquals(2, rows.size)
        assertEquals("What is \"2+2\"?", rows[1][0])
        assertEquals("four, five", rows[1][1])
        assertEquals("line1\nline2", rows[1][2])
    }

    @Test
    fun csvHeaderMapsByNameAndBuildsQuestions() {
        val text = "question,option a,option b,option c,option d,answer,explanation,tags\n" +
            "Capital of India?,Mumbai,New Delhi,Kolkata,Chennai,b,On the Yamuna.,india;gk\n"
        val result = CsvTsvParser.parse(text, paperTitle = "GK")
        val paper = result.file.papers.single()
        assertEquals("GK", paper.title)
        val q = paper.categories.single().questions.single()
        assertEquals("Capital of India?", q.text)
        assertEquals(4, q.options.size)
        assertEquals(listOf("csv-1-b"), q.correctOptionIds)
        assertEquals("On the Yamuna.", q.explanation)
        assertEquals(listOf("india", "gk"), q.tags)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun tsvHeaderIsDetectedWhenFileUsesTabs() {
        val text = "question\ta\tb\tc\td\tanswer\texplanation\ttags\n" +
            "2+2?\t3\t4\t5\t6\tb\tmath\talgebra\n"
        val q = CsvTsvParser.parse(text).file.papers.single().categories.single().questions.single()
        assertEquals("2+2?", q.text)
        assertEquals("4", q.options[1].text)
        assertEquals(listOf("csv-1-b"), q.correctOptionIds)
    }

    @Test
    fun positionalRowsWithoutHeaderUseFixedOrder() {
        val text = "2+2?,3,4,5,6,b,math\n"
        val q = CsvTsvParser.parse(text).file.papers.single().categories.single().questions.single()
        assertEquals("2+2?", q.text)
        assertEquals(4, q.options.size)
        assertEquals(listOf("csv-1-b"), q.correctOptionIds)
    }

    @Test
    fun answerMayBeOptionTextAndCardWithoutOptionsIsFlashcard() {
        val text = "question,a,b,answer\nPick one,red,blue,BLUE\nFront,,,\n"
        val questions = CsvTsvParser.parse(text).file.papers.single().categories.single().questions
        assertEquals(2, questions.size)
        assertEquals(listOf("csv-1-b"), questions[0].correctOptionIds)
        assertTrue(questions[1].options.isEmpty())
        assertEquals("Front", questions[1].text)
    }

    @Test
    fun invalidAnswerRowIsSkippedWithWarning() {
        val text = "question,a,answer\nQ,one,z\n"
        val result = CsvTsvParser.parse(text)
        assertTrue(result.file.papers.single().categories.single().questions.isEmpty())
        assertEquals(1, result.skippedRows)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun tabInsideCsvFieldStaysContent() {
        val text = "question,a,b,answer\n\"line1\tline2\",x,y,a\n"
        val q = CsvTsvParser.parse(text).file.papers.single().categories.single().questions.single()
        assertEquals("line1\tline2", q.text)
        assertEquals(listOf("csv-1-a"), q.correctOptionIds)
    }

    @Test
    fun bomIsStrippedAndJsonIsNotCsv() {
        assertEquals("Q", CsvTsvParser.parseRows("\uFEFFQ,a,b\n")[0][0])
        assertFalse(CsvTsvParser.looksLikeCsvTsv("{\"papers\":[]}"))
        assertTrue(CsvTsvParser.looksLikeCsvTsv("question,answer\nQ,a\n"))
    }
}
