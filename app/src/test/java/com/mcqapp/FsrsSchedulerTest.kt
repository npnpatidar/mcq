package com.mcqapp

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
 * The FSRS-6 engine against the reference implementation's own vectors
 * (open-spaced-repetition/py-fsrs, `tests/test_basic.py`), plus the behavioural
 * guarantees the study loop leans on: previews never lie, failures come back
 * the same sitting, intervals land on the day boundary.
 *
 * The math-exact tests use a midnight-anchored clock with a UTC zone and a
 * 00:00 day start, so a whole-day interval produces exactly that many days of
 * elapsed time — the condition the official vectors were recorded under.
 */
class FsrsSchedulerTest {

    /** Midnight UTC, so [DayBoundary.dueAfter] with dayStart 0 is exact. */
    private val t0 = 1_709_164_800_000L

    private val dayMs = DayBoundary.DAY_MS

    /** No offset, no boundary shift: due = graded + interval·24h exactly. */
    private fun exactScheduler(desiredRetention: Double = FsrsScheduler.DEFAULT_DESIRED_RETENTION) =
        FsrsScheduler(
            desiredRetention = desiredRetention,
            config = SchedulerConfig(dayStartHour = 0),
            zoneOffset = { 0L }
        )

    private fun at(days: Double): Long = t0 + (days * dayMs).toLong()

    // ---- official vector: the canonical benchmark sequence ----

    @Test
    fun canonicalBenchmarkSequenceMatchesTheReferenceValues() {
        // py-fsrs test_memo_state: Again then five Goods at [0,0,1,3,8,21]
        // days lands on stability 53.62691, difficulty 6.3574867. This walks
        // the initial, short-term, recall and difficulty formulas in one go.
        val scheduler = exactScheduler()
        val ratings = listOf(
            ReviewGrade.AGAIN,
            ReviewGrade.GOOD,
            ReviewGrade.GOOD,
            ReviewGrade.GOOD,
            ReviewGrade.GOOD,
            ReviewGrade.GOOD
        )
        val dayOffsets = listOf(0.0, 0.0, 1.0, 3.0, 8.0, 21.0)

        var state = scheduler.initial("q")
        ratings.forEachIndexed { i, grade ->
            state = scheduler.next(state, grade, at(dayOffsets.take(i + 1).sum()))
        }

        assertEquals(53.62691, state.stability, 1e-4)
        assertEquals(6.3574867, state.difficulty, 1e-4)
        assertEquals(CardStateKind.REVIEW, state.state)
    }

    // ---- official vector: the interval ladder for repeated Goods ----

    @Test
    fun repeatedGoodIntervalsMatchTheReferenceLadder() {
        // py-fsrs test_review_card reviews Good at each card's due and records
        // [0, 2, 11, 46, 163, 498]: the leading 0 is its learning-step ladder,
        // which this port replaces with an immediate graduation. Replaying the
        // same *history* — graduate, one same-day review, then each review
        // exactly one interval after the last — reproduces the reference's
        // difficulty lineage, so every later interval must match it exactly.
        val scheduler = exactScheduler()
        var state = scheduler.initial("q")
        var now = t0
        val intervals = mutableListOf<Int>()

        state = scheduler.next(state, ReviewGrade.GOOD, now)
        intervals += state.intervalDays

        now += 60_000L
        state = scheduler.next(state, ReviewGrade.GOOD, now)
        intervals += state.intervalDays

        repeat(4) {
            now += state.intervalDays * dayMs
            state = scheduler.next(state, ReviewGrade.GOOD, now)
            intervals += state.intervalDays
        }

        assertEquals(listOf(2, 2, 11, 46, 163, 498), intervals)
        assertEquals(CardStateKind.REVIEW, state.state)
    }

    // ---- initial states for all four ratings ----

    @Test
    fun firstExposureEarnsTheOfficialInitialStabilityAndDifficulty() {
        val scheduler = exactScheduler()
        // S0 = w[G-1]; D0 = w4 - e^(w5·(G-1)) + 1 clamped to 1..10.
        val expectedStability = mapOf(
            ReviewGrade.AGAIN to 0.212,
            ReviewGrade.HARD to 1.2931,
            ReviewGrade.GOOD to 2.3065,
            ReviewGrade.EASY to 8.2956
        )
        val expectedDifficulty = mapOf(
            ReviewGrade.AGAIN to 6.4133,
            ReviewGrade.HARD to 5.1122,
            ReviewGrade.GOOD to 2.1181,
            ReviewGrade.EASY to 1.0
        )
        expectedStability.forEach { (grade, stability) ->
            val next = scheduler.next(scheduler.initial("q"), grade, t0)
            assertEquals(stability, next.stability, 1e-9)
            assertEquals(expectedDifficulty.getValue(grade), next.difficulty, 1e-3)
        }
    }

    @Test
    fun onlyAFailureKeepsAFirstExposureInTheSitting() {
        val scheduler = exactScheduler()
        val kinds = ReviewGrade.entries.associateWith { grade ->
            scheduler.next(scheduler.initial("q"), grade, t0).state
        }
        assertEquals(CardStateKind.LEARNING, kinds.getValue(ReviewGrade.AGAIN))
        assertEquals(CardStateKind.REVIEW, kinds.getValue(ReviewGrade.HARD))
        assertEquals(CardStateKind.REVIEW, kinds.getValue(ReviewGrade.GOOD))
        assertEquals(CardStateKind.REVIEW, kinds.getValue(ReviewGrade.EASY))
    }

    // ---- retrievability ----

    @Test
    fun retrievabilityFollowsTheForgettingCurve() {
        val scheduler = exactScheduler()
        val state = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)
        val stability = state.stability

        assertEquals(0.0, scheduler.retention(scheduler.initial("q"), t0), 0.0)
        assertEquals(1.0, scheduler.retention(state, t0), 1e-9)
        // R(S) is the benchmark retention by construction of the decay factor.
        assertEquals(0.9, scheduler.retention(state, t0 + (stability * dayMs).toLong()), 1e-6)
        // R(2S) = (1 + 2·FACTOR)^DECAY ≈ 0.846.
        assertEquals(0.846, scheduler.retention(state, t0 + (2 * stability * dayMs).toLong()), 1e-3)
    }

    // ---- same-day behaviour ----

    @Test
    fun sameDayHardNeverDecreasesStability() {
        // py-fsrs test_same_day_hard_review_does_not_decrease_stability: the
        // short-term growth factor floors at 1.0 for Hard and up, so a Hard on
        // easy material records the struggle in difficulty, not in memory.
        val scheduler = exactScheduler()
        val graduated = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)

        val hard = scheduler.next(graduated, ReviewGrade.HARD, t0 + 60_000L)

        assertEquals(graduated.stability, hard.stability, 0.0)
        // Hard records the struggle: difficulty climbs the 1..10 scale.
        assertTrue(hard.difficulty > graduated.difficulty)
        assertEquals(CardStateKind.REVIEW, hard.state)
    }

    @Test
    fun sameDayGoodFromALearningCardGraduatesWithShortTermMemory() {
        val scheduler = exactScheduler()
        val learning = scheduler.next(scheduler.initial("q"), ReviewGrade.AGAIN, t0)
        assertEquals(CardStateKind.LEARNING, learning.state)

        val good = scheduler.next(learning, ReviewGrade.GOOD, t0 + 60_000L)

        assertTrue(good.stability > learning.stability)
        assertEquals(CardStateKind.REVIEW, good.state)
        assertTrue(good.intervalDays >= 1)
    }

    // ---- lapses, relearning and the leech flag ----

    @Test
    fun againOnAReviewCardRelearnsTheSameSittingAndCountsOneLapse() {
        val scheduler = exactScheduler()
        val review = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)

        val relearning = scheduler.next(review, ReviewGrade.AGAIN, t0 + 2 * dayMs)

        assertEquals(CardStateKind.RELEARNING, relearning.state)
        assertEquals(0, relearning.intervalDays)
        assertEquals(review.dueAt + 2 * dayMs + SchedulerConfig().relearnMs, relearning.dueAt)
        assertEquals(1, relearning.lapses)

        // Still recovering: another failure repeats the step, no new lapse.
        val still = scheduler.next(relearning, ReviewGrade.AGAIN, relearning.dueAt)
        assertEquals(CardStateKind.RELEARNING, still.state)
        assertEquals(1, still.lapses)

        // A pass graduates back to review with a multi-day interval.
        val recovered = scheduler.next(still, ReviewGrade.GOOD, still.dueAt)
        assertEquals(CardStateKind.REVIEW, recovered.state)
        assertTrue(recovered.intervalDays >= 1)
    }

    @Test
    fun stabilityNeverDropsBelowTheFloor() {
        // py-fsrs test_stability_lower_bound, at the same 1000 daily failures.
        val scheduler = exactScheduler()
        var state = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)
        repeat(1000) { i ->
            state = scheduler.next(state, ReviewGrade.AGAIN, t0 + (i + 2) * dayMs)
            assertTrue(state.stability >= FsrsScheduler.STABILITY_MIN)
            assertEquals(CardStateKind.RELEARNING, state.state)
        }
    }

    @Test
    fun eightLapsesFlagTheLeech() {
        val scheduler = exactScheduler()
        var state = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)
        // Each cycle: a graduated card fails a full day later (a real lapse),
        // then recovers the same sitting so the next failure lapses again.
        var now = state.dueAt + dayMs
        repeat(8) {
            state = scheduler.next(state, ReviewGrade.AGAIN, now)
            state = scheduler.next(state, ReviewGrade.GOOD, state.dueAt)
            now = state.dueAt + dayMs
        }
        assertEquals(8, state.lapses)
        assertTrue(state.leech)
    }

    // ---- difficulty ----

    @Test
    fun difficultyFloorsAtOneAfterRepeatedEasy() {
        // py-fsrs test_repeated_correct_reviews: ten same-day Easies end at the
        // difficulty floor, exactly.
        val scheduler = exactScheduler()
        var state = scheduler.next(scheduler.initial("q"), ReviewGrade.EASY, t0)
        repeat(9) { state = scheduler.next(state, ReviewGrade.EASY, t0 + 60_000L) }
        assertEquals(1.0, state.difficulty, 0.0)
    }

    // ---- intervals ----

    @Test
    fun intervalsLandOnTheStudyDayBoundary() {
        // Default config: 04:00 day start. A whole-day interval is counted from
        // the boundary, not from the instant of grading.
        val scheduler = FsrsScheduler(config = SchedulerConfig(), zoneOffset = { 0L })
        val now = t0 + 12 * 3_600_000L // 12:30
        val next = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, now)

        assertTrue(next.intervalDays >= 1)
        assertEquals(
            DayBoundary.dueAfter(now, next.intervalDays, SchedulerConfig().dayStartHour, 0L),
            next.dueAt
        )
    }

    @Test
    fun aFailureIsDueAfterTheRelearnDelayNotOnABoundary() {
        val scheduler = exactScheduler()
        val next = scheduler.next(scheduler.initial("q"), ReviewGrade.AGAIN, t0 + 3_600_000L)
        assertEquals(t0 + 3_600_000L + SchedulerConfig().relearnMs, next.dueAt)
    }

    @Test
    fun desiredRetentionScalesTheInterval() {
        // At 0.9 the interval equals the stability (2.3065 → 2 days); at 0.8
        // the same memory earns a longer interval.
        val lenient = exactScheduler(desiredRetention = 0.8)
        val next = lenient.next(lenient.initial("q"), ReviewGrade.GOOD, t0)
        assertEquals(8, next.intervalDays)
    }

    @Test
    fun intervalsNeverExceedTheConfiguredCeiling() {
        val scheduler = exactScheduler()
        var state = scheduler.next(scheduler.initial("q"), ReviewGrade.EASY, t0)
        repeat(60) { state = scheduler.next(state, ReviewGrade.EASY, state.dueAt) }
        assertEquals(SchedulerConfig().maxIntervalDays, state.intervalDays)
    }

    // ---- previews and wiring ----

    @Test
    fun previewDelaysMatchWhatGradingPersists() {
        val scheduler = exactScheduler()
        val review = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)
        listOf(scheduler.initial("q"), review).forEach { state ->
            val previews = Study.previewDelays(scheduler, state, t0)
            ReviewGrade.entries.forEach { grade ->
                val persisted = scheduler.next(state, grade, t0)
                assertEquals(persisted.dueAt - t0, previews.getValue(grade))
            }
        }
    }

    @Test
    fun aLongGapBeforeTheFirstReviewStillUsesTheLongTermPath() {
        // py-fsrs test_long_term_stability_learning_state: a learning card
        // reviewed a full day late must take the multi-day formula, not the
        // same-day one.
        val scheduler = exactScheduler()
        val learning = scheduler.next(scheduler.initial("q"), ReviewGrade.AGAIN, t0)
        val late = scheduler.next(learning, ReviewGrade.GOOD, t0 + dayMs)
        assertEquals(CardStateKind.REVIEW, late.state)
        assertTrue(late.stability > learning.stability)
    }

    @Test
    fun resetReturnsTheCardToNew() {
        val scheduler = exactScheduler()
        val reviewed = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)
        val reset = scheduler.reset(reviewed)
        assertEquals(scheduler.initial("q"), reset)
        assertTrue(reset.isNew)
    }

    @Test
    fun theEngineRequiresAllTwentyOneParameters() {
        try {
            FsrsScheduler(parameters = List(20) { 0.5 })
            throw AssertionError("expected a parameter-count failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("21"))
        }
    }
}
