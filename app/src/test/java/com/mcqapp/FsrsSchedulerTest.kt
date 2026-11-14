package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.Study
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FSRS-7 engine against the reference implementation's own vectors
 * (open-spaced-repetition/fsrs-rs `model_v7.rs`, mirrored by ts-fsrs
 * `models/fsrs-7`), plus the behavioural guarantees the study loop leans on:
 * previews never lie, failures come back the same sitting, intervals land on
 * the day boundary, and a card whose fast trace is unknown still schedules.
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

    // ---- official vector: the canonical dual-trace sequence ----

    @Test
    fun canonicalBenchmarkSequenceMatchesTheReferenceValues() {
        // fsrs-rs / ts-fsrs reference suite: Again on a new card, then five
        // Goods with gaps [0, 1, 3, 8, 21] days. This walks the two trace
        // updates, the mixture curve and the fixed-coefficient difficulty
        // reversion in one go. Expected (difficulty, stability, stabilityFast)
        // after each review:
        val expected = listOf(
            Triple(6.1686, 0.1104, 0.0883),
            Triple(6.1092, 0.1104, 0.0883),
            Triple(6.0504, 0.9169, 44.4442),
            Triple(5.9922, 3.5338, 193.5973),
            Triple(5.9346, 10.1860, 407.6265),
            Triple(5.8775, 25.9857, 700.5589)
        )
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
            val (difficulty, stability, stabilityFast) = expected[i]
            assertClose("difficulty at step ${i + 1}", difficulty, state.difficulty)
            assertClose("stability at step ${i + 1}", stability, state.stability)
            assertClose("fast stability at step ${i + 1}", stabilityFast, state.stabilityFast)
        }
        assertEquals(CardStateKind.REVIEW, state.state)
    }

    /** The reference's published values are f32; ours are f64. 1e-4 of scale is parity. */
    private fun assertClose(what: String, expected: Double, actual: Double) {
        assertEquals(what, expected, actual, 1e-4 * maxOf(1.0, expected))
    }

    // ---- initial states for all four ratings ----

    @Test
    fun firstExposureEarnsTheOfficialInitialStabilityAndDifficulty() {
        val scheduler = exactScheduler()
        // S0 = w[G-1]; Sf0 = 0.8·S0; D0 = w4 - e^(w5·(G-1)) + 1 clamped to 1..10.
        val expectedStability = mapOf(
            ReviewGrade.AGAIN to 0.1104,
            ReviewGrade.HARD to 2.2395,
            ReviewGrade.GOOD to 3.9221,
            ReviewGrade.EASY to 11.7841
        )
        val expectedDifficulty = mapOf(
            ReviewGrade.AGAIN to 6.1686,
            ReviewGrade.HARD to 5.2613,
            ReviewGrade.GOOD to 3.5307,
            ReviewGrade.EASY to 1.0
        )
        expectedStability.forEach { (grade, stability) ->
            val next = scheduler.next(scheduler.initial("q"), grade, t0)
            assertEquals(stability, next.stability, 1e-9)
            // The fast trace starts at 80% of the slow one — initialization only.
            assertEquals(stability * 0.8, next.stabilityFast, 1e-9)
            assertEquals(expectedDifficulty.getValue(grade), next.difficulty, 1e-3)
        }
    }

    /**
     * The app pins one state-rounding rule to TS-FSRS: initial and updated
     * values are both rounded to 8 decimals. A custom, many-decimal weight
     * vector is the only way to see it — the defaults are already short.
     */
    @Test
    fun initialAndUpdatedStatesShareTheTsFsrsRoundingRule() {
        val weights = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.toMutableList().also {
            it[2] = 3.922123456789
        }
        val scheduler = FsrsScheduler(
            parameters = weights,
            config = SchedulerConfig(dayStartHour = 0),
            zoneOffset = { 0L }
        )
        val first = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)
        val rounded = Math.round(weights[2] * 1e8) / 1e8
        assertEquals(rounded, first.stability, 0.0)
        assertEquals(Math.round(rounded * 0.8 * 1e8) / 1e8, first.stabilityFast, 0.0)
        assertEquals(
            Math.round(first.difficulty * 1e8) / 1e8,
            first.difficulty,
            0.0
        )
        val second = scheduler.next(first, ReviewGrade.GOOD, at(3.0))
        assertEquals(Math.round(second.stability * 1e8) / 1e8, second.stability, 0.0)
        assertEquals(Math.round(second.stabilityFast * 1e8) / 1e8, second.stabilityFast, 0.0)
        assertEquals(Math.round(second.difficulty * 1e8) / 1e8, second.difficulty, 0.0)
    }

    /**
     * FSRS-014's golden vector under a **custom, many-decimal** weight set — the
     * defaults are short enough to hide the 8-decimal state rounding. Pinned at
     * exact equality because the rule is arithmetic: initial stabilities and
     * difficulties are rounded on construction, and an update rounds both
     * traces plus difficulty again. The `Sf = 0.8·S` first-exposure ratio is
     * rounded *after* the ratio, and a `Hard` update keeps the fast trace growing
     * far past the slow one, which is the branch a short default vector cannot
     * exercise.
     */
    @Test
    fun aCustomWeightVectorFollowsThePinnedRoundingRule() {
        val weights = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.toMutableList().also {
            it[0] = 0.1104123456789
            it[2] = 3.922123456789
            it[4] = 6.1686999999
            it[7] = 1.9795111111
            it[10] = 0.7024333333
            it[24] = 0.0801555555
            it[26] = 0.9464444444
        }
        val scheduler = FsrsScheduler(
            parameters = weights,
            config = SchedulerConfig(dayStartHour = 0),
            zoneOffset = { 0L }
        )

        data class Expected(val s: Double, val sf: Double, val d: Double)
        val initials = mapOf(
            ReviewGrade.AGAIN to Expected(0.11041235, 0.08832988, 6.1687),
            ReviewGrade.HARD to Expected(2.2395, 1.7916, 5.26137831),
            ReviewGrade.GOOD to Expected(3.92212346, 3.13769877, 3.53082398),
            ReviewGrade.EASY to Expected(11.7841, 9.42728, 1.0)
        )
        initials.forEach { (grade, expected) ->
            val next = scheduler.next(scheduler.initial("q"), grade, t0)
            assertEquals("$grade S", expected.s, next.stability, 0.0)
            assertEquals("$grade Sf", expected.sf, next.stabilityFast, 0.0)
            assertEquals("$grade D", expected.d, next.difficulty, 0.0)
        }

        val graduated = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)
        val at = t0 + 3 * 86_400_000L
        assertEquals(0.9146835939710306, scheduler.retention(graduated, at), 1e-12)
        val updates = mapOf(
            ReviewGrade.AGAIN to Triple(Expected(1.20590326, 0.25625999, 8.81317704), 0, 0.0),
            ReviewGrade.HARD to Triple(Expected(7.7125007, 101.89279132, 6.11703732), 7, 0.0),
            ReviewGrade.GOOD to Triple(Expected(9.84643925, 163.40237347, 3.49781674), 23, 0.0),
            ReviewGrade.EASY to Triple(Expected(9.84643925, 177.3293737, 1.0), 47, 0.0)
        )
        updates.forEach { (grade, expected) ->
            val next = scheduler.next(graduated, grade, at)
            val state = expected.first
            assertEquals("$grade S", state.s, next.stability, 0.0)
            assertEquals("$grade Sf", state.sf, next.stabilityFast, 0.0)
            assertEquals("$grade D", state.d, next.difficulty, 0.0)
            assertEquals("$grade interval", expected.second, next.intervalDays)
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
    fun retrievabilityFollowsTheDualTraceCurve() {
        val scheduler = exactScheduler()
        val state = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)

        assertEquals(0.0, scheduler.retention(scheduler.initial("q"), t0), 0.0)
        // R(0) is the reference's epsilon floor, not exactly 1.
        assertEquals(0.99999, scheduler.retention(state, t0), 1e-5)

        // Strictly decreasing in elapsed time, with no bumps.
        var previous = scheduler.retention(state, t0)
        listOf(0.5, 1.0, 2.0, 5.0, 20.0, 100.0).forEach { days ->
            val current = scheduler.retention(state, t0 + (days * dayMs).toLong())
            assertTrue("recall rose at $days d", current < previous)
            previous = current
        }
    }

    @Test
    fun stabilityIsNoLongerTheBenchmarkInterval() {
        // The trap this upgrade removes: at the benchmark retention FSRS-6's
        // interval WAS the stability. Under FSRS-7 the mixture at t = S sits
        // well above 0.9, so the interval has to be solved.
        val scheduler = exactScheduler()
        val state = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)

        val atStability = scheduler.retention(state, t0 + (state.stability * dayMs).toLong())
        assertNotEquals(0.9, atStability, 1e-3)
        assertTrue("R(S) should overshoot the target", atStability > 0.9)
    }

    @Test
    fun theIntervalSolverRoundTripsTheTarget() {
        val scheduler = exactScheduler()
        val states = listOf(
            scheduler.next(scheduler.initial("q"), ReviewGrade.AGAIN, t0),
            scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0),
            scheduler.next(scheduler.initial("q"), ReviewGrade.EASY, t0)
        )
        val targets = listOf(0.75, 0.8, 0.9, 0.95)

        states.forEach { state ->
            targets.forEach { target ->
                val days = scheduler.intervalForRetention(state, target)
                assertTrue("$target gave $days", days > 0.0)
                val recall = scheduler.retention(state, t0 + (days * dayMs).toLong())
                assertEquals("target $target for S=${state.stability}", target, recall, 1e-3)
            }
        }
    }

    @Test
    fun theIntervalSolverRejectsImpossibleTargets() {
        val scheduler = exactScheduler()
        val state = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)
        listOf(0.0, -1.0, 1.0, 1.5).forEach { target ->
            try {
                scheduler.intervalForRetention(state, target)
                throw AssertionError("expected a rejection for $target")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("(0, 1)"))
            }
        }
    }

    // ---- same-day behaviour: no separate branch any more ----

    @Test
    fun aSameDayReviewStillMovesTheFastTrace() {
        // FSRS-7 has no short-term formula; the fast trace exists so a review
        // ten minutes later is modelled rather than special-cased.
        val scheduler = exactScheduler()
        val graduated = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)

        val again = scheduler.next(graduated, ReviewGrade.AGAIN, t0 + 10 * 60_000L)
        assertTrue(again.stability < graduated.stability)
        assertTrue(again.stabilityFast <= again.stability)
        assertEquals(CardStateKind.RELEARNING, again.state)
    }

    /**
     * The audit's "fractional, zero, and negative elapsed time" vector, negative
     * end: a device clock that jumps backwards (or a review timestamp recorded
     * in the future) must read as *zero* elapsed, never as a negative t. A
     * negative t would make the mixed curve increase with time and hand the
     * optimizer a meaningless gradient, so the clamp is part of the rule.
     */
    @Test
    fun aClockThatRunsBackwardsReadsAsZeroElapsed() {
        val scheduler = exactScheduler()
        val graduated = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)
        val skewed = t0 - 30 * dayMs

        assertEquals(
            "a backwards clock cannot predict more than a same-instant review",
            scheduler.retention(graduated, t0),
            scheduler.retention(graduated, skewed),
            0.0
        )
        val atSkew = scheduler.next(graduated, ReviewGrade.GOOD, skewed)
        val atSameInstant = scheduler.next(graduated, ReviewGrade.GOOD, t0)
        assertEquals(atSameInstant.stability, atSkew.stability, 0.0)
        assertEquals(atSameInstant.stabilityFast, atSkew.stabilityFast, 0.0)
        assertEquals(atSameInstant.difficulty, atSkew.difficulty, 0.0)
        assertEquals(atSameInstant.intervalDays, atSkew.intervalDays)
        assertTrue(atSkew.stability > 0.0)
    }

    @Test
    fun sameDayHardNeverDecreasesStability() {
        // A successful review can never shrink a trace: the growth term floors
        // at 1.0 through the max(lapse, x·growth) in the update.
        val scheduler = exactScheduler()
        val graduated = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)

        val hard = scheduler.next(graduated, ReviewGrade.HARD, t0 + 60_000L)

        assertTrue(hard.stability >= graduated.stability)
        assertTrue(hard.stabilityFast >= graduated.stabilityFast)
        // Hard records the struggle: difficulty climbs the 1..10 scale.
        assertTrue(hard.difficulty > graduated.difficulty)
        assertEquals(CardStateKind.REVIEW, hard.state)
    }

    @Test
    fun aTenMinuteGapAndAFullDayGapAreNotTheSameReview() {
        // The whole point of fractional elapsed time: 10 minutes and 1 day run
        // the same formulas at different t, not two different formulas.
        val scheduler = exactScheduler()
        val graduated = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)

        val sameDay = scheduler.next(graduated, ReviewGrade.GOOD, t0 + 10 * 60_000L)
        val nextDay = scheduler.next(graduated, ReviewGrade.GOOD, t0 + dayMs)

        assertTrue(sameDay.stability < nextDay.stability)
        assertTrue(sameDay.stabilityFast < nextDay.stabilityFast)
    }

    @Test
    fun sameDayGoodFromALearningCardGraduates() {
        val scheduler = exactScheduler()
        val learning = scheduler.next(scheduler.initial("q"), ReviewGrade.AGAIN, t0)
        assertEquals(CardStateKind.LEARNING, learning.state)

        val good = scheduler.next(learning, ReviewGrade.GOOD, t0 + 60_000L)

        assertTrue(good.stability > learning.stability)
        assertEquals(CardStateKind.REVIEW, good.state)
        assertTrue(good.intervalDays >= 1)
    }

    // ---- an unknown fast trace is not an error ----

    @Test
    fun anUnknownFastTraceCollapsesOntoTheSlowOne() {
        // Imported cards and cards written before the FSRS-7 rebuild have no
        // fast trace. The reference throws; this app must keep scheduling. An
        // unknown Sf is read as S, never as a within-day component.
        val scheduler = exactScheduler()
        val known = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)
        val unknown = known.copy(stabilityFast = 0.0)
        val explicit = known.copy(stabilityFast = known.stability)

        val atThreeDays = t0 + 3 * dayMs
        assertEquals(
            scheduler.retention(explicit, atThreeDays),
            scheduler.retention(unknown, atThreeDays),
            0.0
        )
        val recall = scheduler.retention(unknown, atThreeDays)
        assertTrue(recall.isFinite() && recall in 0.0..1.0)

        val graded = scheduler.next(unknown, ReviewGrade.GOOD, atThreeDays)
        assertTrue(graded.stability.isFinite() && graded.stabilityFast.isFinite())
        assertTrue(graded.stabilityFast > 0.0)
    }

    @Test
    fun anImportedCardWithNoReviewTimestampStillSchedules() {
        // A card carrying memory but no last review: elapsed is taken from its
        // own interval, so the update is a long-gap one, never t = 0.
        val scheduler = exactScheduler()
        val imported = CardState(
            questionId = "q",
            intervalDays = 30,
            lastReviewedAt = 0L,
            difficulty = 5.0,
            stability = 30.0,
            stabilityFast = 0.0,
            state = CardStateKind.REVIEW,
            reviewCount = 5
        )
        assertTrue(scheduler.retention(imported, t0) > 0.0)
        val graded = scheduler.next(imported, ReviewGrade.GOOD, t0)
        assertTrue(graded.stability > imported.stability)
        assertTrue(graded.intervalDays >= 1)
    }

    // ---- lapses, relearning and the leech flag ----

    @Test
    fun againOnAReviewCardRelearnsTheSameSittingAndCountsOneLapse() {
        val scheduler = exactScheduler()
        val review = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)

        val relearning = scheduler.next(review, ReviewGrade.AGAIN, t0 + 2 * dayMs)

        assertEquals(CardStateKind.RELEARNING, relearning.state)
        assertEquals(0, relearning.intervalDays)
        // The failure schedules from the failure moment, never snapped: the
        // review was graded at t0 and failed two days later.
        assertEquals(at(2.0) + SchedulerConfig().relearnMs, relearning.dueAt)
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
        val scheduler = exactScheduler()
        var state = scheduler.next(scheduler.initial("q"), ReviewGrade.GOOD, t0)
        repeat(1000) { i ->
            state = scheduler.next(state, ReviewGrade.AGAIN, t0 + (i + 2) * dayMs)
            assertTrue(state.stability >= FsrsScheduler.STABILITY_MIN)
            assertTrue(state.stabilityFast >= FsrsScheduler.STABILITY_MIN)
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
        val scheduler = exactScheduler()
        var state = scheduler.next(scheduler.initial("q"), ReviewGrade.EASY, t0)
        repeat(9) { state = scheduler.next(state, ReviewGrade.EASY, t0 + 60_000L) }
        assertEquals(1.0, state.difficulty, 0.0)
    }

    // ---- intervals ----

    @Test
    fun repeatedGoodIntervalsFollowTheModel() {
        // This is the app's own ladder, NOT a reference vector: without learning
        // steps the replay is graduate, one same-day review, then each review one
        // interval after the last. The values are asserted so a change to the
        // port shows up here rather than as a claim of reference parity — the
        // canonical sequence above is the parity test.
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

        assertEquals(listOf(5, 7, 33, 114, 358, 1078), intervals)
        assertEquals(CardStateKind.REVIEW, state.state)
    }

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
        val state = exactScheduler().next(exactScheduler().initial("q"), ReviewGrade.GOOD, t0)
        val interval = { target: Double ->
            exactScheduler(target).intervalForRetention(state, target)
        }

        assertTrue(interval(0.8) > interval(0.9))
        assertTrue(interval(0.9) > interval(0.97))
        // A 0.97 target on a fresh card lands inside the same day, which the
        // whole-day scheduler floors to a one-day interval.
        assertTrue(interval(0.97) < 1.0)
    }

    @Test
    fun intervalsNeverExceedTheConfiguredCeiling() {
        val scheduler = exactScheduler()
        var state = scheduler.next(scheduler.initial("q"), ReviewGrade.EASY, t0)
        repeat(60) { state = scheduler.next(state, ReviewGrade.EASY, state.dueAt) }
        assertEquals(SchedulerConfig().maxIntervalDays, state.intervalDays)
    }

    @Test
    fun aStateAboveTheModelBoundIsClampedNotWrapped() {
        // The interval used to be computed from an unbounded stability and then
        // narrowed to Int, so anything past Int.MAX_VALUE wrapped. FSRS-7 clamps
        // the state to the model bound first, and the solver can never return
        // more than that either.
        val scheduler = exactScheduler()
        val longAgo = CardState(
            questionId = "q",
            intervalDays = 30,
            lastReviewedAt = t0,
            difficulty = 5.0,
            stability = 2_147_483_648.0,
            stabilityFast = 2_147_483_648.0,
            state = CardStateKind.REVIEW,
            reviewCount = 5
        )

        val next = scheduler.next(longAgo, ReviewGrade.GOOD, t0 + 30 * dayMs)

        assertTrue(next.stability <= FsrsScheduler.MAX_STABILITY)
        assertTrue(next.stabilityFast <= FsrsScheduler.MAX_STABILITY)
        assertEquals(SchedulerConfig().maxIntervalDays, next.intervalDays)
    }

    @Test
    fun everyFittedWeightSetAtTheBoundsStillSchedules() {
        // The optimizer clips to these bounds; a legal fit must never produce a
        // NaN state or a nonsense interval, at either corner.
        val lower = SchedulerConfig(fsrsParameters = com.mcqapp.domain.FsrsOptimizer.LOWER_BOUNDS.toList())
        val upper = SchedulerConfig(fsrsParameters = com.mcqapp.domain.FsrsOptimizer.UPPER_BOUNDS.toList())

        listOf(lower, upper).forEach { config ->
            val scheduler = FsrsScheduler(config = config, zoneOffset = { 0L })
            var state = scheduler.initial("q")
            ReviewGrade.entries.forEach { grade ->
                val next = scheduler.next(state, grade, t0)
                assertTrue("${grade.name} stability", next.stability.isFinite())
                assertTrue("${grade.name} fast stability", next.stabilityFast.isFinite())
                assertTrue("${grade.name} difficulty", next.difficulty.isFinite())
                // A failure is due inside the sitting and carries interval 0.
                val floor = if (grade == ReviewGrade.AGAIN) 0 else 1
                assertTrue(
                    "${grade.name} interval ${next.intervalDays}",
                    next.intervalDays in floor..config.maxIntervalDays
                )
                state = next
            }
        }
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
    fun theEngineRequiresAllThirtyFourParameters() {
        try {
            FsrsScheduler(parameters = List(21) { 0.5 })
            throw AssertionError("expected a parameter-count failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("34"))
        }
    }
}
