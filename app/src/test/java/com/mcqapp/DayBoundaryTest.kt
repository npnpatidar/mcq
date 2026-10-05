package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.Sm2Scheduler
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Due dates were `now + interval * 24h`, so a 1-day card answered at 23:00 came
 * back at 23:00 the next evening and a study day rolled over at local midnight.
 * Anki counts whole days from a study day that starts at 04:00 instead.
 *
 * Every case pins the zone rather than leaning on the host default, otherwise
 * the suite would pass or fail depending on where it runs.
 */
class DayBoundaryTest {

    private val day = DayBoundary.DAY_MS
    private val hour = 3_600_000L

    /** 2026-03-14T00:00:00Z: an exact UTC midnight, so every expectation below
     *  is plain arithmetic rather than a calendar calculation. */
    private val midnightUtc = 1_773_446_400_000L

    /** 20:00 UTC — late enough that the next 04:00 boundary is only hours away. */
    private val lateEvening = midnightUtc + 20 * hour

    private fun scheduler(boundaryHour: Int, zoneOffsetMillis: Long = 0L) = Sm2Scheduler(
        SchedulerConfig(dayStartHour = boundaryHour, relearnMs = 10 * 60 * 1000L),
        zoneOffset = { zoneOffsetMillis }
    )

    // --- startOfDay ---

    @Test
    fun `the boundary hour is the start of the study day`() {
        // Midnight belongs to the study day that began at 04:00 the day before.
        assertEquals(
            midnightUtc - 20 * hour,
            DayBoundary.startOfDay(midnightUtc, dayStartHour = 4, zoneOffsetMillis = 0)
        )
    }

    @Test
    fun `the boundary itself is included in the new day`() {
        val boundary = midnightUtc - 20 * hour
        assertEquals(
            boundary,
            DayBoundary.startOfDay(boundary, dayStartHour = 4, zoneOffsetMillis = 0)
        )
        // One millisecond earlier is still the previous day.
        assertEquals(
            boundary - day,
            DayBoundary.startOfDay(boundary - 1, dayStartHour = 4, zoneOffsetMillis = 0)
        )
    }

    @Test
    fun `a zero boundary is local midnight`() {
        assertEquals(
            midnightUtc,
            DayBoundary.startOfDay(midnightUtc, dayStartHour = 0, zoneOffsetMillis = 0)
        )
    }

    @Test
    fun `instants before the epoch floor downwards`() {
        // A truncating remainder would round towards zero and land after the
        // boundary instead of before it.
        assertEquals(
            -day + 4 * hour,
            DayBoundary.startOfDay(-hour, dayStartHour = 4, zoneOffsetMillis = 0)
        )
    }

    @Test
    fun `an out of range boundary hour is clamped`() {
        assertEquals(
            DayBoundary.startOfDay(midnightUtc, dayStartHour = 23, zoneOffsetMillis = 0),
            DayBoundary.startOfDay(midnightUtc, dayStartHour = 99, zoneOffsetMillis = 0)
        )
    }

    @Test
    fun `the boundary sits at local four in the morning in a zone east of UTC`() {
        // Asia/Tokyo is UTC+9 with no daylight saving, so the arithmetic is exact.
        val tokyo = 9 * hour
        val start = DayBoundary.startOfDay(midnightUtc, dayStartHour = 4, zoneOffsetMillis = tokyo)
        // The boundary has to land on 04:00 of the Tokyo wall clock, wherever in
        // the UTC day that falls.
        assertEquals(4 * hour, Math.floorMod(start + tokyo, day))
    }

    // --- dueAfter ---

    @Test
    fun `a one day card is due at the next boundary not 24 hours later`() {
        val due = DayBoundary.dueAfter(lateEvening, 1, dayStartHour = 4, zoneOffsetMillis = 0)
        // Answered at 20:00, due at 04:00 the next morning: eight hours, not 24.
        assertEquals(8 * hour, due - lateEvening)
    }

    @Test
    fun `a one day card graded before the boundary is due later that same morning`() {
        // 01:00 still belongs to the previous study day, so "tomorrow" is the
        // 04:00 boundary three hours away.
        val smallHours = midnightUtc + hour
        assertEquals(
            3 * hour,
            DayBoundary.dueAfter(smallHours, 1, dayStartHour = 4, zoneOffsetMillis = 0) - smallHours
        )
    }

    @Test
    fun `interval days count from the start of the study day`() {
        assertEquals(
            DayBoundary.startOfDay(midnightUtc, 4, 0) + 7 * day,
            DayBoundary.dueAfter(midnightUtc, 7, 4, 0)
        )
    }

    @Test
    fun `a negative interval is treated as due now`() {
        assertEquals(
            DayBoundary.startOfDay(midnightUtc, 4, 0),
            DayBoundary.dueAfter(midnightUtc, -3, 4, 0)
        )
    }

    // --- scheduler integration ---

    @Test
    fun `the scheduler aligns a graded card to the boundary`() {
        val sched = scheduler(boundaryHour = 4)
        val next = sched.next(CardState("q1"), ReviewGrade.GOOD, lateEvening)
        assertEquals(1, next.intervalDays)
        assertEquals(8 * hour, next.dueAt - lateEvening)
    }

    @Test
    fun `the relearn delay is not snapped to a boundary`() {
        // A failed card must come back inside the sitting, so its delay stays a
        // real elapsed time.
        val sched = scheduler(boundaryHour = 4)
        val graded = sched.next(
            CardState("q1").copy(reps = 3, intervalDays = 9), ReviewGrade.AGAIN, midnightUtc
        )
        assertEquals(midnightUtc + 10 * 60 * 1000L, graded.dueAt)
        assertEquals(0, graded.intervalDays)
    }

    @Test
    fun `a midnight boundary is respected by the scheduler`() {
        val sched = scheduler(boundaryHour = 0)
        val next = sched.next(CardState("q1"), ReviewGrade.GOOD, lateEvening)
        // Next local midnight, which is four hours after an 20:00 answer.
        assertEquals(midnightUtc + day, next.dueAt)
        assertEquals(4 * hour, next.dueAt - lateEvening)
    }

    @Test
    fun `every interval lands on the boundary`() {
        val sched = scheduler(boundaryHour = 4)
        var state = CardState("q1")
        repeat(6) { step ->
            state = sched.next(state, ReviewGrade.GOOD, midnightUtc + step * 3 * hour)
            assertEquals(
                "interval ${state.intervalDays}d must start on the boundary",
                4 * hour,
                Math.floorMod(state.dueAt, day)
            )
        }
    }

    @Test
    fun `the boundary hour is configurable end to end`() {
        val sched = scheduler(boundaryHour = 5)
        val next = sched.next(CardState("q1"), ReviewGrade.GOOD, midnightUtc)
        assertEquals(midnightUtc + 5 * hour, next.dueAt)
    }

    @Test
    fun `an out of range boundary hour is clamped by the config`() {
        assertEquals(23, SchedulerConfig(dayStartHour = 99).sanitized().dayStartHour)
        assertEquals(0, SchedulerConfig(dayStartHour = -1).sanitized().dayStartHour)
    }
}