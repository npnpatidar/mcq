package com.mcqapp

import com.mcqapp.data.export.ReportPdf
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.ReportRow
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestReportBuilder
import com.mcqapp.domain.TestReportInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan §9.4: the report PDF's laid-out text. `PdfDocument` cannot be built on
 * this host (see `PdfPaperWriterTest`), so the content is asserted through the
 * pure [ReportPdf.lines] seam the writer draws from.
 */
class ReportPdfTest {

    private fun attempt(
        total: Int = 2,
        correct: Int = 1,
        wrong: Int = 1,
        skipped: Int = 0
    ) = Attempt(
        id = 7L,
        paperId = "p1",
        title = "Polity Paper",
        totalQuestions = total,
        correctCount = correct,
        wrongCount = wrong,
        skippedCount = skipped,
        score = correct.toDouble(),
        maxScore = total.toDouble(),
        durationSeconds = 120,
        finishedAt = 1_700_000_000_000L,
        testType = "RANDOM",
        requestedCount = total,
        scopeKey = ScopeKey.wholePaper("p1"),
        samplingVersion = TestPlan.SAMPLING_VERSION,
        exclusionMode = ExclusionMode.NONE.name
    )

    private fun row(id: String, correct: Boolean, dwell: Long = 10L) = ReportRow(
        questionId = id,
        categoryTitle = "Polity",
        currentCategoryId = "c1",
        dwellSeconds = dwell,
        isCorrect = correct,
        graded = true,
        answered = true
    )

    private fun report(
        rows: List<ReportRow> = listOf(row("a", true), row("b", false))
    ) = TestReportBuilder.build(
        TestReportInput(
            attempt = attempt(),
            rows = rows,
            paper = Paper(
                id = "p1",
                title = "Polity Paper",
                negativeMarking = 0.0,
                categories = emptyList()
            )
        )
    )

    private fun texts(lines: List<ReportPdf.ReportLine>) = lines.map { it.text }

    @Test
    fun theHeaderAndScoreSectionsAreLaidOut() {
        val lines = ReportPdf.lines(report(), generatedAt = 1_700_000_000_000L)
        val text = texts(lines)

        assertEquals("MCQ Report", text.first())
        assertTrue(text.contains("Paper: Polity Paper"))
        assertTrue(text.any { it.startsWith("Attempt: 7 · ") })
        assertTrue(text.any { it.startsWith("Finished: ") })

        assertTrue(text.contains("Score"))
        assertTrue(text.contains("1/2 correct · 50% accuracy"))
        assertTrue(
            text.contains("Correct 1, wrong 1, skipped 0, ungraded 0")
        )
        // The last line is the injected generation timestamp.
        assertEquals(
            "Generated " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(1_700_000_000_000L)),
            text.last()
        )
    }

    @Test
    fun theTopicSectionCarriesEachTopicsScore() {
        val lines = ReportPdf.lines(report())
        val topics = lines.indexOfFirst { it.text == "Topics" }
        assertTrue("Topics heading is present", topics >= 0)
        assertEquals(ReportPdf.Style.HEADING, lines[topics].style)
        val row = lines[topics + 1]
        assertEquals(ReportPdf.Style.BODY, row.style)
        assertTrue(row.text.startsWith("Polity: 1/2 (50%)"))
    }

    @Test
    fun anEmptyReportStillLaysOutWithoutATopicOrActionSection() {
        val lines = ReportPdf.lines(report(rows = emptyList()))
        val text = texts(lines)
        assertTrue(text.contains("Score"))
        // With no rows the header still counts the questions the attempt
        // recorded, so the denominator is the attempt's total, not zero.
        assertTrue(text.contains("0/2 correct · 0% accuracy"))
        assertTrue(text.contains("No timing data recorded."))
        assertFalse(text.contains("Topics"))
        assertFalse(text.contains("Recommended actions"))
        // The disclaimer is always the second-to-last line.
        assertEquals(
            "Test schedule unchanged. Study scheduling is separate from mock/practice tests.",
            text[text.lastIndex - 1]
        )
    }
}
