package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Passage
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.Study
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Study header clock colours itself from the same dwell ladder the check
 * grades with: Easy below the first threshold, Good between the two, Hard at
 * or above the second. These tests pin the boundaries and the reading-time
 * extension, so the colour and the grade cannot drift apart.
 */
class StudyDwellBandTest {

    /** No readable text, so the ladder is exactly the configured thresholds. */
    private fun bareQuestion() = Question(
        id = "q1",
        categoryId = "c1",
        elements = emptyList(),
        options = emptyList(),
        correctOptionIds = emptySet()
    )

    @Test
    fun `the default ladder is easy under 8s, good to 29s, hard from 30s`() {
        val ladder = Study.dwellLadder(SchedulerConfig(), bareQuestion(), passage = null)

        assertEquals(Study.FAST_SECONDS, ladder.easySeconds)
        assertEquals(Study.SLOW_SECONDS, ladder.hardSeconds)
        assertEquals(Study.DwellBand.EASY, Study.dwellBand(0, ladder.easySeconds, ladder.hardSeconds))
        assertEquals(Study.DwellBand.EASY, Study.dwellBand(7, ladder.easySeconds, ladder.hardSeconds))
        assertEquals(Study.DwellBand.GOOD, Study.dwellBand(8, ladder.easySeconds, ladder.hardSeconds))
        assertEquals(Study.DwellBand.GOOD, Study.dwellBand(29, ladder.easySeconds, ladder.hardSeconds))
        assertEquals(Study.DwellBand.HARD, Study.dwellBand(30, ladder.easySeconds, ladder.hardSeconds))
        assertEquals(Study.DwellBand.HARD, Study.dwellBand(31, ladder.easySeconds, ladder.hardSeconds))
    }

    @Test
    fun `the clock boundaries agree with the grade boundaries`() {
        // The two boundaries in inferGrade's ladder, named once.
        val easySeconds = Study.FAST_SECONDS
        val hardSeconds = Study.SLOW_SECONDS

        assertEquals(ReviewGrade.EASY, Study.inferGrade(true, easySeconds - 1))
        assertEquals(
            Study.DwellBand.EASY,
            Study.dwellBand(easySeconds - 1, easySeconds, hardSeconds)
        )
        assertEquals(ReviewGrade.GOOD, Study.inferGrade(true, easySeconds))
        assertEquals(Study.DwellBand.GOOD, Study.dwellBand(easySeconds, easySeconds, hardSeconds))
        assertEquals(ReviewGrade.HARD, Study.inferGrade(true, hardSeconds))
        assertEquals(Study.DwellBand.HARD, Study.dwellBand(hardSeconds, easySeconds, hardSeconds))
    }

    @Test
    fun `reading time lifts both ends of the ladder by the same amount`() {
        val config = SchedulerConfig()
        // 15 seconds of reading (80 + 100 + 40 title + 80 body characters at
        // 20 chars/s), added to the configured minimum.
        val long = Question(
            id = "q1",
            categoryId = "c1",
            elements = listOf(ContentElement.TextElement("x".repeat(80))),
            options = listOf(QuestionOption("a", "y".repeat(100))),
            correctOptionIds = setOf("a")
        )
        val passage = Passage(
            id = "p1",
            categoryId = "c1",
            title = "t".repeat(40),
            elements = listOf(ContentElement.TextElement("z".repeat(80)))
        )
        val ladder = Study.dwellLadder(config, long, passage)

        assertEquals(Study.FAST_SECONDS + 15, ladder.easySeconds)
        assertEquals(Study.SLOW_SECONDS + 15, ladder.hardSeconds)
        assertEquals(
            "Good must stay reachable on a long question",
            config.slowSeconds - config.fastSeconds,
            ladder.hardSeconds - ladder.easySeconds
        )
        // A dwell that would be Hard on a short card is still Easy here.
        assertEquals(Study.DwellBand.EASY, Study.dwellBand(20, ladder.easySeconds, ladder.hardSeconds))
        assertEquals(Study.DwellBand.GOOD, Study.dwellBand(23, ladder.easySeconds, ladder.hardSeconds))
        assertEquals(Study.DwellBand.HARD, Study.dwellBand(45, ladder.easySeconds, ladder.hardSeconds))
    }

    @Test
    fun `retuned thresholds move the clock bands with the grade`() {
        val config = SchedulerConfig(fastSeconds = 5L, slowSeconds = 60L)
        val ladder = Study.dwellLadder(config, bareQuestion(), passage = null)

        assertEquals(5L, ladder.easySeconds)
        assertEquals(60L, ladder.hardSeconds)
        assertEquals(Study.DwellBand.EASY, Study.dwellBand(4, ladder.easySeconds, ladder.hardSeconds))
        assertEquals(Study.DwellBand.GOOD, Study.dwellBand(5, ladder.easySeconds, ladder.hardSeconds))
        assertEquals(Study.DwellBand.GOOD, Study.dwellBand(59, ladder.easySeconds, ladder.hardSeconds))
        assertEquals(Study.DwellBand.HARD, Study.dwellBand(60, ladder.easySeconds, ladder.hardSeconds))
        assertEquals(ReviewGrade.HARD, Study.inferGrade(true, 60, config = config))
    }
}
