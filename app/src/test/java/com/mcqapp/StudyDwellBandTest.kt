package com.mcqapp

import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.Study
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Study header clock colours itself from the same absolute cut pair the
 * check grades with: Easy below [SchedulerConfig.fastSeconds], Good below
 * [SchedulerConfig.slowSeconds], Hard at or above it, and a neutral first
 * second before any time is banked. These tests pin the boundaries and the
 * fact that reading time no longer moves them.
 */
class StudyDwellBandTest {

    private val config = SchedulerConfig()

    private fun band(
        dwellSeconds: Long,
        config: SchedulerConfig = this.config
    ) = Study.dwellBand(dwellSeconds, config.fastSeconds, config.slowSeconds)

    @Test
    fun `the default bands are easy under 6s, good to 59s, hard from 60s`() {
        assertEquals(6L, Study.FAST_SECONDS)
        assertEquals(60L, Study.SLOW_SECONDS)
        assertEquals(6L, config.fastSeconds)
        assertEquals(60L, config.slowSeconds)

        assertEquals(Study.DwellBand.NEUTRAL, band(0))
        assertEquals(Study.DwellBand.EASY, band(1))
        assertEquals(Study.DwellBand.EASY, band(5))
        assertEquals(Study.DwellBand.GOOD, band(6))
        assertEquals(Study.DwellBand.GOOD, band(59))
        assertEquals(Study.DwellBand.HARD, band(60))
        assertEquals(Study.DwellBand.HARD, band(61))
    }

    @Test
    fun `the clock boundaries agree with the grade boundaries`() {
        assertEquals(ReviewGrade.EASY, Study.inferGrade(true, config.fastSeconds - 1))
        assertEquals(Study.DwellBand.EASY, band(config.fastSeconds - 1))
        assertEquals(ReviewGrade.GOOD, Study.inferGrade(true, config.fastSeconds))
        assertEquals(Study.DwellBand.GOOD, band(config.fastSeconds))
        assertEquals(ReviewGrade.HARD, Study.inferGrade(true, config.slowSeconds))
        assertEquals(Study.DwellBand.HARD, band(config.slowSeconds))
    }

    /**
     * The guarantee the calibration depends on: from the first banked second
     * onward, the colour must name exactly the grade a Check at that instant
     * would produce. Only 0 is deliberately neutral, where grade inference has
     * no timing to read and calls the card Good.
     */
    @Test
    fun `the clock names the same grade from the first second`() {
        val configs = listOf(
            SchedulerConfig(),
            SchedulerConfig(fastSeconds = 4L, slowSeconds = 45L),
            SchedulerConfig(fastSeconds = 5L, slowSeconds = 90L)
        )
        for (config in configs) {
            for (dwell in 1L..(config.slowSeconds + 5L)) {
                val expected = Study.inferGrade(
                    isCorrect = true,
                    dwellSeconds = dwell,
                    config = config
                )
                assertEquals(
                    "dwell ${dwell}s on ${config.fastSeconds}/${config.slowSeconds}",
                    expected,
                    band(dwell, config).toGrade()
                )
            }
        }
    }

    @Test
    fun `the neutral first second is not a grade claim`() {
        assertEquals(
            Study.DwellBand.NEUTRAL,
            Study.dwellBand(0, Study.FAST_SECONDS, Study.SLOW_SECONDS)
        )
        assertEquals(ReviewGrade.GOOD, Study.inferGrade(true, 0))
    }

    @Test
    fun `a sub-second answer is untimed and the rest rounds half up once`() {
        assertEquals(0L, Study.gradedDwellSeconds(-1L))
        assertEquals(0L, Study.gradedDwellSeconds(0L))
        assertEquals(0L, Study.gradedDwellSeconds(499L))
        // 500-999 ms used to round up to a measured second and grade Easy; the
        // first second is untimed, so it is stored as 0.
        assertEquals(0L, Study.gradedDwellSeconds(500L))
        assertEquals(0L, Study.gradedDwellSeconds(999L))
        assertEquals(1L, Study.gradedDwellSeconds(1_000L))
        assertEquals(1L, Study.gradedDwellSeconds(1_499L))
        assertEquals(2L, Study.gradedDwellSeconds(1_500L))
        assertEquals(8L, Study.gradedDwellSeconds(7_500L))
    }

    /**
     * The rule the owner set: inside the first second, a right answer is Good
     * and a wrong one is Again. Neither reads the cut pair, so the fastest tap
     * cannot earn Easy.
     */
    @Test
    fun `an answer inside the first second is good when right and again when wrong`() {
        for (millis in listOf(0L, 300L, 500L, 900L, 999L)) {
            val dwell = Study.gradedDwellSeconds(millis)
            assertEquals(
                "correct at ${millis}ms",
                ReviewGrade.GOOD,
                Study.autoGrade(
                    correctOptionIds = setOf("a"),
                    selection = setOf("a"),
                    dwellSeconds = dwell,
                    isGuess = false
                )
            )
            assertEquals(
                "wrong at ${millis}ms",
                ReviewGrade.AGAIN,
                Study.autoGrade(
                    correctOptionIds = setOf("a"),
                    selection = setOf("b"),
                    dwellSeconds = dwell,
                    isGuess = false
                )
            )
        }
        // And the boundary itself is measured: a whole second grades Easy.
        assertEquals(ReviewGrade.EASY, Study.inferGrade(true, Study.gradedDwellSeconds(1_000L)))
    }

    @Test
    fun `retuned thresholds move the clock bands with the grade`() {
        val retuned = SchedulerConfig(fastSeconds = 3L, slowSeconds = 120L)
        assertEquals(Study.DwellBand.EASY, band(2, retuned))
        assertEquals(Study.DwellBand.GOOD, band(3, retuned))
        assertEquals(Study.DwellBand.GOOD, band(119, retuned))
        assertEquals(Study.DwellBand.HARD, band(120, retuned))
        assertEquals(ReviewGrade.HARD, Study.inferGrade(true, 120, config = retuned))
    }

    private fun Study.DwellBand.toGrade(): ReviewGrade = when (this) {
        Study.DwellBand.NEUTRAL -> error("a banked dwell is never neutral")
        Study.DwellBand.EASY -> ReviewGrade.EASY
        Study.DwellBand.GOOD -> ReviewGrade.GOOD
        Study.DwellBand.HARD -> ReviewGrade.HARD
    }
}
