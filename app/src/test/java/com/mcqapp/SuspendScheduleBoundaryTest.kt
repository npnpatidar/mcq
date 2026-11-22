package com.mcqapp

import com.mcqapp.data.repository.SuspendSchedule
import com.mcqapp.domain.DayBoundary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Bury is a study-day boundary, not a fixed 24-hour timer. A card hidden at
 * 23:00 comes back at the next 04:00 — five real hours later — and one hidden
 * at 03:59 comes back in a minute. This is pure JVM (no Room, no Robolectric).
 */
class SuspendScheduleBoundaryTest {

    private val utc = DayBoundary.utc()
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun aLateEveningBuryReturnsAtTheNextMorningBoundary() {
        val buriedAt = at(22, 23, 0)
        assertEquals(at(23, 4), SuspendSchedule.nextBoundary(buriedAt, 4, utc))
    }

    @Test
    fun aBuryJustBeforeTheBoundaryReturnsInMinutes() {
        val buriedAt = at(22, 3, 59)
        assertEquals(at(22, 4), SuspendSchedule.nextBoundary(buriedAt, 4, utc))
    }

    @Test
    fun aBuryExactlyOnTheBoundaryWaitsAWholeDay() {
        val buriedAt = at(22, 4)
        assertEquals(at(23, 4), SuspendSchedule.nextBoundary(buriedAt, 4, utc))
    }

    @Test
    fun aCustomDayStartHourIsHonoured() {
        // DayBoundary.studyDayIndex puts an instant before 06:00 in the
        // *previous* study day, so dueAfter(now, 1) is that day's 06:00 — the
        // same calendar morning. But 23:00 is already in today's study day,
        // so its next boundary is tomorrow's 06:00. Both are the same rule.
        assertEquals(at(22, 6), SuspendSchedule.nextBoundary(at(22, 5, 0), 6, utc))
        assertEquals(at(22, 6), SuspendSchedule.nextBoundary(at(22, 5, 30), 6, utc))
        assertEquals(at(23, 6), SuspendSchedule.nextBoundary(at(22, 23, 0), 6, utc))
        // Exactly on the boundary waits a whole study day.
        assertEquals(at(23, 6), SuspendSchedule.nextBoundary(at(22, 6), 6, utc))
    }
}
