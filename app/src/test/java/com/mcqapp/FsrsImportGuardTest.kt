package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.Study
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between imports and the engine: a schedule can arrive with memory
 * (a stability and a due date) but no last-review timestamp, because the
 * package carries no review log. "Never reviewed" and "reviewed at the
 * epoch" must remain distinct, or the card takes the same-day formula
 * forever and ranks as fully forgotten (audit finding 3) — so the guard is
 * keyed off memory, not off the sentinel alone.
 */
class FsrsImportGuardTest {

    /** Midnight UTC, so [DayBoundary.dueAfter] with dayStart 0 is exact. */
    private val t0 = 1_709_164_800_000L

    private val dayMs = DayBoundary.DAY_MS

    private fun exactScheduler() = FsrsScheduler(
        desiredRetention = FsrsScheduler.DEFAULT_DESIRED_RETENTION,
        config = SchedulerConfig(dayStartHour = 0),
        zoneOffset = { 0L }
    )

    /**
     * The shape `AnkiScheduling.fromAnki` used to fabricate when a card was
     * due today with no review log: real memory, no last-review instant.
     */
    private fun importedMemoryCardWithoutLastReview(scheduler: FsrsScheduler): CardState =
        scheduler.initial("q").copy(
            intervalDays = 30,
            stability = 30.0,
            difficulty = 5.0,
            ease = 2.5,
            state = CardStateKind.REVIEW,
            reps = 10,
            reviewCount = 10
        )

    @Test
    fun aMemoryCardWithoutLastReviewTakesTheRecallFormulaOnFirstGrading() {
        val scheduler = exactScheduler()
        val imported = importedMemoryCardWithoutLastReview(scheduler)
        assertTrue(imported.lastReviewedAt == 0L && imported.stability > 0.0)

        val graded = scheduler.next(imported, ReviewGrade.GOOD, t0 + dayMs)
        // The same-day branch floors growth at 1.0 and would park stability
        // at 30.000 forever; the recall formula for an on-time card grows it.
        assertTrue(
            "same-day branch fired: stability stayed ${graded.stability}",
            graded.stability > imported.stability
        )
        assertEquals(CardStateKind.REVIEW, graded.state)
    }

    @Test
    fun aMemoryCardWithoutLastReviewIsNotRankedAsForgotten() {
        val scheduler = exactScheduler()
        val imported = importedMemoryCardWithoutLastReview(scheduler)

        // Its retrievability is the card's own claim: it is due precisely at
        // its interval, so predicted recall lands on the retention target —
        // nowhere near the LOW_RECALL_RISK band of a truly forgotten card.
        val retention = scheduler.retention(imported, t0)
        assertEquals(FsrsScheduler.BENCHMARK_RETENTION, retention, 1e-9)
        assertTrue(retention > Study.LOW_RECALL_RISK_RETENTION)
    }

    @Test
    fun aNeverReviewedCardStillStartsAtZero() {
        val scheduler = exactScheduler()
        val fresh = scheduler.initial("q")

        // The sentinel must keep its original meaning for the card it was
        // invented for: elapsed 0, no retrievability, first grade takes the
        // initial-state formulas.
        assertEquals(0.0, scheduler.retention(fresh, t0), 0.0)
        val graded = scheduler.next(fresh, ReviewGrade.GOOD, t0)
        // S₀(Good) = w2 = 2.3065 days.
        assertEquals(2.3065, graded.stability, 1e-9)
    }

    @Test
    fun everyGradeKeepsRepsAndReviewCountMirrored() {
        // reps and reviewCount are written by two separate expressions that
        // are always equal by construction; pin the invariant so a future
        // edit cannot desync them silently (audit finding 4).
        val scheduler = exactScheduler()
        var state = scheduler.initial("q")
        assertEquals(state.reps, state.reviewCount)

        var now = t0
        // One pass over every grade value, walking the card through learning,
        // graduation, lapses and recovery — reps only ever moves on a grade.
        for (grade in ReviewGrade.values()) {
            state = scheduler.next(state, grade, now)
            now += state.intervalDays * dayMs
            assertEquals(
                "grade $grade left reps ${state.reps} and reviewCount ${state.reviewCount} desynced",
                state.reviewCount,
                state.reps
            )
        }
    }
}
