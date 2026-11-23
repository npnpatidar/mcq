package com.mcqapp

import com.mcqapp.data.io.CsvTsvParser
import com.mcqapp.domain.ContentElement
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

    /**
     * INCIDENT: `mapIndexedNotNull` compressed a-d positions when blank options
     * were dropped, so "b" selected the next surviving option and "d" was
     * rejected as an invalid answer. The answer column uses the original letters.
     */
    @Test
    fun aBlankOptionDoesNotShiftTheLetterAnswer() {
        val text = "question,a,b,c,d,answer\nCapital?,,New Delhi,Kolkata,Chennai,b\n"
        val result = CsvTsvParser.parse(text)
        assertTrue(result.warnings.isEmpty())
        assertEquals(0, result.skippedRows)
        val q = result.file.papers.single().categories.single().questions.single()
        assertEquals(3, q.options.size)
        assertEquals("New Delhi", q.options[0].text)
        assertEquals(listOf("csv-1-b"), q.correctOptionIds)
    }

    @Test
    fun theLastOptionStillAnswersWhenEarlierOptionsAreBlank() {
        val text = "question,a,b,c,d,answer\nCapital?,,,Kolkata,Chennai,d\n"
        val result = CsvTsvParser.parse(text)
        assertTrue(result.warnings.isEmpty())
        assertEquals(0, result.skippedRows)
        val q = result.file.papers.single().categories.single().questions.single()
        assertEquals(2, q.options.size)
        assertEquals(listOf("csv-1-d"), q.correctOptionIds)
    }

    @Test
    fun aBlankInterveningOptionDoesNotShiftAnOptionTextAnswer() {
        val text = "question,a,b,c,d,answer\nPick,,blue,green,red,RED\n"
        val q = CsvTsvParser.parse(text).file.papers.single().categories.single().questions.single()
        assertEquals(listOf("csv-1-d"), q.correctOptionIds)
    }

    @Test
    fun anAnswerNamingABlankOptionIsStillSkipped() {
        val text = "question,a,b,c,answer\nPick,,yes,no,a\n"
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

    /**
     * INCIDENT: `hasHeader` accepted `option1..4` / `options`, but
     * `normalizeHeader` mapped only `a-d` and `option a-d`. Every option column
     * was ignored, so the row became a blank flashcard with no back and the
     * answer key was discarded without a warning.
     */
    @Test
    fun numberedOptionColumnsAreMappedAndKeepTheAnswer() {
        val text = "question,option1,option2,option3,option4,answer\n" +
            "Capital of India?,Mumbai,New Delhi,Kolkata,Chennai,b\n"
        val result = CsvTsvParser.parse(text)
        assertTrue("clean file must not warn: ${result.warnings}", result.warnings.isEmpty())
        val q = result.file.papers.single().categories.single().questions.single()
        assertEquals(4, q.options.size)
        assertEquals("New Delhi", q.options[1].text)
        assertEquals(listOf("csv-1-b"), q.correctOptionIds)
    }

    @Test
    fun abbreviatedAndChoiceOptionHeadersAreMapped() {
        val text = "question,opt a,opt b,choice c,choice d,answer\n" +
            "Pick,red,blue,green,yellow,c\n"
        val q = CsvTsvParser.parse(text).file.papers.single().categories.single().questions.single()
        assertEquals(4, q.options.size)
        assertEquals(listOf("csv-1-c"), q.correctOptionIds)
    }

    /** A `back` column is the flashcard's back, exactly like `explanation`. */
    @Test
    fun questionAndExplanationHeaderBuildsAFlashcardBack() {
        val text = "question,explanation\nWhat is FSRS?,A spaced-repetition scheduler.\n"
        val q = CsvTsvParser.parse(text).file.papers.single().categories.single().questions.single()
        assertTrue("a card with no options is a flashcard", q.options.isEmpty())
        assertEquals("What is FSRS?", q.text)
        assertEquals("A spaced-repetition scheduler.", q.explanation)
        assertEquals(
            listOf(ContentElement.TextElement("A spaced-repetition scheduler.")),
            q.explanationElements
        )
    }

    @Test
    fun backHeaderIsTreatedAsTheFlashcardBack() {
        val text = "question,back\nFront text,Back text\n"
        val q = CsvTsvParser.parse(text).file.papers.single().categories.single().questions.single()
        assertTrue(q.options.isEmpty())
        assertEquals("Back text", q.explanation)
    }

    /**
     * A non-blank answer with no option column has nothing to name. Writing a
     * blank flashcard silently dropped the answer; skip it with a dropped row
     * warning instead.
     */
    @Test
    fun anAnswerWithNoOptionColumnsIsSkippedNotWrittenAsABlankCard() {
        val text = "question,answer\nWhat is 2+2?,a\n"
        val result = CsvTsvParser.parse(text)
        assertTrue(
            "the row must not become a blank card",
            result.file.papers.single().categories.single().questions.isEmpty()
        )
        assertEquals(1, result.skippedRows)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun aCombinedOptionsColumnIsReportedInsteadOfWrittenAsABlankCard() {
        val text = "question,options,answer\nPick one,red|blue,a\n"
        val result = CsvTsvParser.parse(text)
        assertTrue(result.file.papers.single().categories.single().questions.isEmpty())
        assertEquals(1, result.skippedRows)
    }
}
