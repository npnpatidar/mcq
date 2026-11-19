package com.mcqapp

import com.mcqapp.data.anki.AnkiScheduling
import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.domain.DayBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/**
 * Round-2 high fixes in the Anki bridge:
 *  - D1: the configured leech threshold drives the derived `leech` flag;
 *  - D2: import and export share the calendar study-day model across DST.
 *
 * Pure JVM; no SQLite/Robolectric needed.
 */
class Round2AnkiSchedulingTest {

    private val crt = 1_700_000_000L

    private fun imported(lapses: Int, threshold: Int? = null) = AnkiScheduling.fromAnki(
        type = AnkiScheduling.TYPE_REVIEW,
        queue = AnkiScheduling.QUEUE_REVIEW,
        due = 2,
        ivl = 2,
        factor = 2500,
        reps = 20,
        lapses = lapses,
        crtSeconds = crt,
        lastReviewedAtMillis = crt * 1000L,
        leechThreshold = threshold ?: AnkiScheduling.LEECH_THRESHOLD
    )!!

    // ---- D1: leech threshold ----

    @Test
    fun defaultThresholdKeepsTheHistoricalEightLapseRule() {
        assertFalse("5 lapses < 8", imported(5).leech)
        assertFalse("7 lapses < 8", imported(7).leech)
        assertTrue("8 lapses == 8", imported(8).leech)
    }

    @Test
    fun configuredThresholdOfThreeFlipsAFiveLapseCard() {
        val state = imported(5, threshold = 3)
        assertTrue("5 lapses >= configured 3", state.leech)
        assertEquals(5, state.lapses)
    }

    @Test
    fun thresholdUsesGreaterThanOrEqualAndStaysOffBelowIt() {
        assertFalse("2 lapses < 3", imported(2, threshold = 3).leech)
        assertTrue("3 lapses == 3", imported(3, threshold = 3).leech)
        assertFalse("5 lapses < 6", imported(5, threshold = 6).leech)
    }

    // ---- D2: DST-symmetric day conversion ----

    /**
     * Export at a study-day boundary, re-import the resulting Anki day count,
     * and require the very same boundary back. Europe/Berlin has both DST
     * transitions inside these windows, so a calendar day is 23 or 25 real
     * hours. On the old fixed-offset `fromAnki` the fall-back case lands one
     * study day early (adding a flat 24h to 04:00 CEST reaches 03:00 CET, which
     * truncates back to the previous day), so this test fails on the old code.
     */
    @Test
    fun dayCountRoundTripsAcrossBothBerlinDstTransitions() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            // spring-forward 2024-03-31 (23 h), fall-back 2024-10-27 (25 h)
            assertRoundTrip(LocalDate.of(2024, 3, 30), 23L)
            assertRoundTrip(LocalDate.of(2024, 10, 26), 25L)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun assertRoundTrip(day: LocalDate, expectedGapHours: Long) {
        val zone = DayBoundary.defaultZone()
        val dayIndex = day.toEpochDay()
        val crtMillis = DayBoundary.boundaryOf(dayIndex, 4, zone)
        val dueMillis = DayBoundary.boundaryOf(dayIndex + 1, 4, zone)
        assertEquals(
            "$day study day should be $expectedGapHours h across DST",
            expectedGapHours * 3_600_000L,
            dueMillis - crtMillis
        )

        val card = AnkiScheduling.toAnki(
            CardScheduleDto(
                intervalDays = 1,
                dueAt = dueMillis,
                reps = 7,
                lapses = 1,
                lastReviewedAt = crtMillis
            ),
            crtSeconds = crtMillis / 1000L,
            nowMillis = crtMillis
        )
        assertEquals("one study day out", 1, card.due)

        val back = AnkiScheduling.fromAnki(
            type = card.type,
            queue = card.queue,
            due = card.due,
            ivl = card.ivl,
            factor = card.factor,
            reps = card.reps,
            lapses = card.lapses,
            crtSeconds = crtMillis / 1000L,
            lastReviewedAtMillis = crtMillis
        )
        assertNotNull(back)
        assertEquals("re-imported due boundary at $day", dueMillis, back!!.dueAt)
    }
}
