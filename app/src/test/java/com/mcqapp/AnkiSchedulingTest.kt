package com.mcqapp

import com.mcqapp.data.anki.AnkiScheduling
import com.mcqapp.data.io.CardScheduleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Anki's `due` field means different things per card state, so the conversion
 * is pinned here rather than only through a package, where a wrong answer would
 * look like a plausible date. The rules come from Anki's own importer,
 * `rslib/src/import_export/package/apkg/import/cards.rs`.
 */
class AnkiSchedulingTest {

    private val day = 86_400_000L
    // A fixed epoch so the day arithmetic does not depend on when the suite runs.
    private val crt = 1_700_000_000L

    @Test
    fun reviewCardDueInFiveDaysKeepsItsIntervalAndEase() {
        val state = AnkiScheduling.fromAnki(
            type = AnkiScheduling.TYPE_REVIEW,
            queue = AnkiScheduling.QUEUE_REVIEW,
            due = 5,
            ivl = 5,
            factor = 2500,
            reps = 7,
            lapses = 1,
            crtSeconds = crt,
            lastReviewedAtMillis = crt * 1000L
        )
        assertNotNull(state)
        assertEquals(5, state!!.intervalDays)
        assertEquals(7, state.reps)
        assertEquals(1, state.lapses)
        assertEquals(2.5, state.ease, 0.0001)
        assertTrue(state.dueAt > crt * 1000L)
        // Five days out, within a day of slack for Anki's 04:00 rollover.
        assertTrue(
            "due in ${state.dueAt - crt * 1000L}ms",
            state.dueAt - crt * 1000L in (4 * day)..(6 * day)
        )
    }

    @Test
    fun reviewDueTodayIsNotInTheFuture() {
        val state = AnkiScheduling.fromAnki(
            type = AnkiScheduling.TYPE_REVIEW,
            queue = AnkiScheduling.QUEUE_REVIEW,
            due = 0,
            ivl = 0,
            factor = 2500,
            reps = 1,
            lapses = 0,
            crtSeconds = crt
        )!!
        // due = 0 is the collection's own day, which has already started.
        assertTrue("dueAt=${state.dueAt}", state.dueAt <= crt * 1000L)
    }

    @Test
    fun learningCardTakesItsDueAsAPlainTimestamp() {
        val dueSeconds = 1_700_000_600
        val state = AnkiScheduling.fromAnki(
            type = AnkiScheduling.TYPE_LEARN,
            queue = AnkiScheduling.QUEUE_LEARN,
            due = dueSeconds,
            // Anki's learning interval is a step in minutes, not days.
            ivl = 10,
            factor = 2500,
            reps = 1,
            lapses = 0,
            crtSeconds = crt
        )!!
        assertEquals(dueSeconds * 1000L, state.dueAt)
        // Days are the wrong unit here, so no interval is claimed.
        assertEquals(0, state.intervalDays)
    }

    @Test
    fun dayLearnCardIsAnchoredToTheCollectionCreationDate() {
        val state = AnkiScheduling.fromAnki(
            type = AnkiScheduling.TYPE_RELEARN,
            queue = AnkiScheduling.QUEUE_DAY_LEARN,
            due = 3,
            ivl = 3,
            factor = 2500,
            reps = 4,
            lapses = 2,
            crtSeconds = crt
        )!!
        assertEquals(3, state.intervalDays)
        assertTrue(state.dueAt in (crt * 1000L + 2 * day)..(crt * 1000L + 4 * day))
    }

    @Test
    fun newCardCarriesNothing() {
        // Anki's new-card `due` is a queue position, not a date, so importing it
        // as one would give the card a due time it never had.
        assertNull(
            AnkiScheduling.fromAnki(
                type = AnkiScheduling.TYPE_NEW,
                queue = AnkiScheduling.QUEUE_NEW,
                due = 7,
                ivl = 0,
                factor = 0,
                reps = 0,
                lapses = 0,
                crtSeconds = crt
            )
        )
    }

    @Test
    fun suspendedCardCarriesNothing() {
        listOf(-1, -2, -3).forEach { queue ->
            assertNull(
                "queue $queue",
                AnkiScheduling.fromAnki(
                    type = AnkiScheduling.TYPE_REVIEW,
                    queue = queue,
                    due = 4,
                    ivl = 4,
                    factor = 2500,
                    reps = 9,
                    lapses = 0,
                    crtSeconds = crt
                )
            )
        }
    }

    @Test
    fun eightLapsesMakesALeech() {
        val state = AnkiScheduling.fromAnki(
            type = AnkiScheduling.TYPE_REVIEW,
            queue = AnkiScheduling.QUEUE_REVIEW,
            due = 2,
            ivl = 2,
            factor = 1400,
            reps = 20,
            lapses = 8,
            crtSeconds = crt
        )!!
        assertTrue(state.leech)
        assertEquals(1.4, state.ease, 0.0001)
    }

    @Test
    fun easeStaysInsideOurScale() {
        val clamped = AnkiScheduling.fromAnki(
            type = AnkiScheduling.TYPE_REVIEW,
            queue = AnkiScheduling.QUEUE_REVIEW,
            due = 1,
            ivl = 1,
            factor = 9900,
            reps = 2,
            lapses = 0,
            crtSeconds = crt
        )!!
        assertEquals(3.0, clamped.ease, 0.0001)
    }

    @Test
    fun reviewedCardExportsAsAReviewCardDueTodayWhenItIsDueToday() {
        val now = crt * 1000L
        val card = AnkiScheduling.toAnki(
            CardScheduleDto(ease = 2.6, intervalDays = 5, dueAt = now, reps = 7, lapses = 1),
            crtSeconds = crt,
            nowMillis = now
        )
        assertEquals(AnkiScheduling.TYPE_REVIEW, card.type)
        assertEquals(AnkiScheduling.QUEUE_REVIEW, card.queue)
        assertEquals(0, card.due)
        assertEquals(5, card.ivl)
        assertEquals(2600, card.factor)
        assertEquals(7, card.reps)
        assertEquals(1, card.lapses)
    }

    @Test
    fun reviewedCardExportsItsRemainingDays() {
        val now = crt * 1000L
        val card = AnkiScheduling.toAnki(
            CardScheduleDto(intervalDays = 5, dueAt = now + 5 * day, reps = 7),
            crtSeconds = crt,
            nowMillis = now
        )
        assertEquals(5, card.due)
        assertEquals(5, card.ivl)
    }

    @Test
    fun overdueCardNeverExportsANegativeDueDay() {
        val now = crt * 1000L
        val card = AnkiScheduling.toAnki(
            CardScheduleDto(intervalDays = 3, dueAt = now - 40 * day, reps = 9),
            crtSeconds = crt,
            nowMillis = now
        )
        assertEquals(0, card.due)
    }

    @Test
    fun unstudiedCardExportsAsNew() {
        val card = AnkiScheduling.toAnki(
            CardScheduleDto(),
            crtSeconds = crt,
            nowMillis = crt * 1000L
        )
        assertEquals(AnkiScheduling.TYPE_NEW, card.type)
        assertEquals(AnkiScheduling.QUEUE_NEW, card.queue)
        assertEquals(0, card.reps)
    }

    @Test
    fun learningCardExportsAsALearningCardWithATimestamp() {
        val now = crt * 1000L
        val dueAt = now + 10 * 60 * 1000L
        val card = AnkiScheduling.toAnki(
            CardScheduleDto(dueAt = dueAt, reps = 0),
            crtSeconds = crt,
            nowMillis = now
        )
        assertEquals(AnkiScheduling.TYPE_LEARN, card.type)
        assertEquals(AnkiScheduling.QUEUE_LEARN, card.queue)
        assertEquals(dueAt / 1000L, card.due.toLong())
        assertEquals(10, card.ivl)
    }

    /**
     * Export then import must land on the same day. The two directions use
     * different arithmetic, so a shared mistake in either would cancel out in
     * per-direction tests but not here.
     */
    @Test
    fun scheduleSurvivesAWriteAndReadRoundTrip() {
        val original = TimeZone.getDefault()
        try {
            // A half-hour offset zone, so a naive "minus the offset" truncation
            // would land on the wrong day.
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            val now = crt * 1000L
            listOf(0L, day, 5 * day, 60 * day).forEach { remaining ->
                val state = CardScheduleDto(
                    ease = 2.35,
                    intervalDays = (remaining / day).toInt(),
                    dueAt = now + remaining,
                    reps = 12,
                    lapses = 2,
                    leech = false,
                    lastReviewedAt = now
                )
                val card = AnkiScheduling.toAnki(state, crt, now)
                val back = AnkiScheduling.fromAnki(
                    type = card.type,
                    queue = card.queue,
                    due = card.due,
                    ivl = card.ivl,
                    factor = card.factor,
                    reps = card.reps,
                    lapses = card.lapses,
                    crtSeconds = crt,
                    lastReviewedAtMillis = state.lastReviewedAt
                )!!
                val expectedDay = startOfStudyDay(now + remaining)
                assertEquals(
                    "remaining=${remaining / day}d",
                    expectedDay,
                    back.dueAt
                )
                assertEquals(2.35, back.ease, 0.001)
                assertEquals(12, back.reps)
                assertEquals(2, back.lapses)
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /**
     * Mirrors the production anchoring, which is the study-day boundary rather
     * than local midnight. Both sides of the round trip have to agree on what a
     * "day" is or the comparison below would be testing the wrong thing.
     */
    /**
     * A card due at the next 04:00 boundary is one day out, even when the package
     * is written at 23:00. The day count used to be measured from the creation
     * instant and rounded, which called such a card "due today" and pushed it a
     * day early in Anki.
     */
    @Test
    fun aCardDueAtTheNextBoundaryExportsAsOneDayOut() {
        val original = TimeZone.getDefault()
        try {
            // A half-hour offset zone, so the boundary does not land on a round hour.
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            val zone = { millis: Long -> TimeZone.getDefault().getOffset(millis).toLong() }
            // 23:00 local: inside the study day that began at 04:00.
            val gradedAt = com.mcqapp.domain.DayBoundary
                .startOfDay(0L, 4, zone(0L)) + 19 * 3_600_000L
            val dueAt = com.mcqapp.domain.DayBoundary
                .dueAfter(gradedAt, 1, 4, zone(gradedAt))
            val crtSeconds = gradedAt / 1000L

            val card = AnkiScheduling.toAnki(
                CardScheduleDto(
                    ease = 2.5,
                    intervalDays = 1,
                    dueAt = dueAt,
                    reps = 1,
                    lapses = 0,
                    leech = false,
                    lastReviewedAt = gradedAt
                ),
                crtSeconds,
                gradedAt
            )
            assertEquals("a card due at the next boundary is one day out", 1, card.due)

            // And the round trip lands back on the very instant it came from.
            val back = AnkiScheduling.fromAnki(
                type = card.type,
                queue = card.queue,
                due = card.due,
                ivl = card.ivl,
                factor = card.factor,
                reps = card.reps,
                lapses = card.lapses,
                crtSeconds = crtSeconds,
                lastReviewedAtMillis = gradedAt
            )!!
            assertEquals(dueAt, back.dueAt)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun startOfStudyDay(millis: Long): Long = com.mcqapp.domain.DayBoundary.startOfDay(
        millis,
        com.mcqapp.domain.DayBoundary.DEFAULT_DAY_START_HOUR,
        TimeZone.getDefault().getOffset(millis).toLong()
    )
}
