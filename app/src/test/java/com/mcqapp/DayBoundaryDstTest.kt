package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.StatsAggregator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/**
 * DST regression for the study-day boundary. Europe/Berlin jumps 02:00→03:00 in
 * spring and 03:00→02:00 in autumn, while a study day starts at 04:00, so the
 * boundary is never inside the jump — but the *day* is 23 or 25 real hours long.
 *
 * Every constant below is the local 04:00 instant of that calendar date. The
 * previous implementation added a fixed 24 h to a boundary computed with the
 * offset at the review instant: each due-time expectation is one hour off under
 * that rule, and the fixed-offset day bucketing merges the two distinct days.
 */
class DayBoundaryDstTest {

    private val berlin = TimeZone.getTimeZone("Europe/Berlin")
    private val hour = 3_600_000L

    @Test
    fun `springForwardDueLandsOnLocalFourAmOfTargetDate`() {
        val reviewedAt = 1_711_828_800_000L // 2024-03-30 20:00Z, CET (+1)
        val due = DayBoundary.dueAfter(reviewedAt, 1, 4, berlin)
        assertEquals(1_711_850_400_000L, due) // 2024-03-31 04:00 CEST
        assertEquals(4 * hour, Math.floorMod(due + berlin.getOffset(due), DayBoundary.DAY_MS))
    }

    @Test
    fun `fallBackDueLandsOnLocalFourAmOfTargetDate`() {
        val reviewedAt = 1_729_972_800_000L // 2024-10-26 20:00Z, CEST (+2)
        val due = DayBoundary.dueAfter(reviewedAt, 1, 4, berlin)
        assertEquals(1_729_998_000_000L, due) // 2024-10-27 04:00 CET
        assertEquals(4 * hour, Math.floorMod(due + berlin.getOffset(due), DayBoundary.DAY_MS))
    }

    @Test
    fun `theBoundaryIsTwentyThreeRealHoursAcrossSpringForward`() {
        val before = 1_711_767_600_000L // 2024-03-30 04:00 CET
        val after = 1_711_850_400_000L // 2024-03-31 04:00 CEST
        assertEquals(23 * hour, after - before)
        assertEquals(before, DayBoundary.startOfDay(before + 1, 4, berlin))
        assertEquals(after, DayBoundary.startOfDay(after + 1, 4, berlin))
    }

    @Test
    fun `aReviewJustBeforeTheBoundaryIsNotCountedOnTheNextStudyDay`() {
        // This is the day start StudyStore.exposureInStudyDay uses. The two
        // instants are an hour apart in real time but on different study days;
        // the old offset-at-review code put the first one an hour late.
        val beforeBoundary = 1_711_848_600_000L // 2024-03-31 01:30Z = 03:30 CEST
        val afterBoundary = 1_711_852_200_000L // 2024-03-31 02:30Z = 04:30 CEST
        assertEquals(1_711_767_600_000L, DayBoundary.startOfDay(beforeBoundary, 4, berlin))
        assertEquals(1_711_850_400_000L, DayBoundary.startOfDay(afterBoundary, 4, berlin))
    }

    @Test
    fun `theSchedulerDueDateUsesTheCalendarBoundaryNotAFixedDay`() {
        val scheduler = FsrsScheduler(config = SchedulerConfig(dayStartHour = 4), zone = berlin)
        val reviewedAt = 1_711_828_800_000L
        val next = scheduler.next(CardState("q"), ReviewGrade.GOOD, reviewedAt)
        val expected = DayBoundary.boundaryOf(
            DayBoundary.studyDayIndex(reviewedAt, 4, berlin) + next.intervalDays,
            4,
            berlin
        )
        assertEquals(expected, next.dueAt)
        assertEquals(4 * hour, Math.floorMod(next.dueAt + berlin.getOffset(next.dueAt), DayBoundary.DAY_MS))
    }

    @Test
    fun `sessionsEitherSideOfAStudyBoundaryGroupIntoDistinctDays`() {
        // The Statistics load happens in summer (+2), but these reviews are in
        // winter (+1): the old single-offset bucketing put both in one day.
        val summerNow = 1_719_835_200_000L // 2024-07-01 12:00Z
        val accumulator = StatsAggregator.SessionsAccumulator(
            scopePaperId = null,
            range = StatsAggregator.Range.ALL,
            now = summerNow,
            cfg = SchedulerConfig(),
            zone = berlin,
            collectEntries = true
        )
        accumulator.add(replayReview(1_705_285_800_000L, "early")) // 2024-01-15 02:30Z = 03:30 CET
        accumulator.add(replayReview(1_705_289_400_000L, "late")) // 2024-01-15 03:30Z = 04:30 CET

        val sessions = accumulator.toData().sessions
        assertEquals(2, sessions.size)
        val days = sessions.map { it.day }.sorted()
        assertEquals(2, days.distinct().size)
        assertEquals(1L, days[1] - days[0])
    }

    private fun replayReview(at: Long, id: String) = ReplayReview(
        paperId = "p",
        questionId = id,
        rating = 3,
        reviewedAt = at,
        elapsedSeconds = 60L,
        stateBefore = CardStateKind.REVIEW,
        stabilityAfter = 2.0,
        difficultyAfter = 5.0,
        intervalDaysAfter = 3,
        predictedRetention = 0.9,
        intervalBeforeDays = 3
    )
}
