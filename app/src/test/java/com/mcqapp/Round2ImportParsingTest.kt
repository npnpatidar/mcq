package com.mcqapp

import com.mcqapp.data.io.FALLBACK_PASSAGE_TITLE
import com.mcqapp.data.io.LegacyParser
import java.io.ByteArrayInputStream
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.ImportWarningSeverity
import com.mcqapp.domain.droppedParserMessage
import com.mcqapp.domain.parserWarningSeverity
import com.mcqapp.domain.parserWarningText
import com.mcqapp.ui.importscreen.typedParserWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** I5 and D6 at the pure parser seams (no Android/Room). */
class Round2ImportParsingTest {

    // ---- I5: severity comes from the producer, not the wording -------------

    @Test
    fun droppedContentWarningKeepsItsDroppedSeverityAndCleanText() {
        val raw = droppedParserMessage("Ignoring an unsupported top-level element <w:sdt> — only paragraphs and tables are read.")
        // The producer tag never reaches the learner.
        assertEquals(
            "Ignoring an unsupported top-level element <w:sdt> — only paragraphs and tables are read.",
            parserWarningText(raw)
        )
        val warning = typedParserWarnings(listOf(raw)).single()
        assertEquals(ImportWarningSeverity.DROPPED, warning.severity)
        assertEquals(parserWarningText(raw), warning.message)
        assertTrue("producer tag must not reach the learner", !warning.message.contains("r2-dropped"))
    }

    @Test
    fun benignNoticeWithoutSkippedWordStaysAdvisory() {
        // A notice that mentions no "skipped" at all, produced as advisory.
        val raw = "Backup restore: 2 papers will be restored"
        val warning = typedParserWarnings(listOf(raw)).single()
        assertEquals(ImportWarningSeverity.ADVISORY, warning.severity)
    }

    @Test
    fun advisoryThatHappensToContainSkippedIsNotMisclassified() {
        // Negative: the old substring classifier would have called this DROPPED.
        val raw = "No rows were skipped — the file imported cleanly"
        val warning = typedParserWarnings(listOf(raw)).single()
        assertEquals(ImportWarningSeverity.ADVISORY, warning.severity)
    }

    @Test
    fun aProducerCanMarkADropWhoseWordingHasNoSkippedWord() {
        val raw = droppedParserMessage("Content in an unsupported element was discarded")
        assertEquals(ImportWarningSeverity.DROPPED, typedParserWarnings(listOf(raw)).single().severity)
    }

    @Test
    fun wrongShapePassagesArrayIsReportedAsADrop() {
        // readArrayOrWarn: a non-list `passages` drops everything inside it.
        val file = LegacyParser.parse("""{"papers":[],"passages":5}""")
        val warning = typedParserWarnings(file.warnings).single()
        assertEquals(ImportWarningSeverity.DROPPED, warning.severity)
        assertTrue(warning.message.contains("\"passages\""))
    }

    @Test
    fun wrongShapeAttemptsArrayIsReportedAsADropInStreamingToo() {
        // streamArrayOrWarn: the streaming twin of the above, used for attempts.
        val sink = object : LegacyParser.ImportStreamSink {
            override fun onPaper(header: LegacyParser.PaperHeader) = Unit
            override fun onCategory(category: com.mcqapp.data.io.CategoryDto) = Unit
            override fun onQuestions(categoryId: String, questions: List<com.mcqapp.data.io.QuestionDto>) = Unit
        }
        val tail = LegacyParser.streamImport(
            ByteArrayInputStream("""{"papers":[],"attempts":5}""".toByteArray()), sink
        )
        val warning = typedParserWarnings(tail.warnings).single()
        assertEquals(ImportWarningSeverity.DROPPED, warning.severity)
        assertTrue(warning.message.contains("\"attempts\""))
    }

    // ---- I6: stable fallback tokens produced for blank headings/passages ----

    @Test
    fun aPassageWithoutATitleGetsTheStableToken() {
        // LegacyParser's passage reader falls back to the token when the title
        // field is absent; DOCX/Anki producers apply the same token for a blank
        // one (see Round2ImportLocalizationTest for the token values/copy).
        val json = """
            {"papers":[{"id":"p","title":"P","categories":[
              {"id":"c","title":"C","questions":[]}
            ]}],
             "passages":[{"id":"pass1","elements":[]}]}
        """.trimIndent()
        val file = LegacyParser.parse(json)
        assertEquals(FALLBACK_PASSAGE_TITLE, file.passages[0].title)
    }

    // ---- D6: unknown cardState with positive stability keeps its memory -----

    private fun craftedJson(cardState: String?) = buildString {
        append("{\"version\":1,\"papers\":[{\"id\":\"p\",\"title\":\"P\",\"categories\":[")
        append("{\"id\":\"c\",\"title\":\"C\",\"questions\":[")
        append("{\"id\":\"q1\",\"text\":\"Q\",\"question_elements\":[{\"type\":\"text\",\"content\":\"Q\"}]")
        append(",\"options_elements\":{\"o0\":[{\"type\":\"text\",\"content\":\"Yes\"}]}")
        append(",\"correctOptionIds\":[\"o0\"]}]}")
        append("]}],\"scheduling\":{\"q1\":{")
        append("\"stability\":12.0,\"difficulty\":5.0,\"intervalDays\":30,\"reps\":4")
        if (cardState != null) append(",\"cardState\":\"$cardState\"")
        append("}}}")
    }

    @Test
    fun unknownCardStateWithMemoryFallsBackToReviewNotNew() {
        // Crafted file: stability > 0 but an unreadable cardState token.
        // Defaulting to NEW would make the scheduler discard the 12.0 stability.
        val file = LegacyParser.parse(craftedJson("NOT_A_STATE"))
        assertEquals(CardStateKind.REVIEW.name, file.scheduling["q1"]!!.cardState)
        assertEquals(12.0, file.scheduling["q1"]!!.stability, 0.0001)
    }

    @Test
    fun unknownCardStateWithoutMemoryStaysNew() {
        // Negative: a genuinely new card has zero stability and must stay NEW.
        val file = LegacyParser.parse(craftedJson("GARBAGE").replace("\"stability\":12.0", "\"stability\":0.0"))
        assertEquals(CardStateKind.NEW.name, file.scheduling["q1"]!!.cardState)
    }

    @Test
    fun explicitCardStateIsHonoured() {
        val file = LegacyParser.parse(craftedJson("LEARNING"))
        assertEquals(CardStateKind.LEARNING.name, file.scheduling["q1"]!!.cardState)
    }

    @Test
    fun absentCardStateWithMemoryAlsoFallsBackToReview() {
        val file = LegacyParser.parse(craftedJson(null))
        assertEquals(CardStateKind.REVIEW.name, file.scheduling["q1"]!!.cardState)
    }
}
