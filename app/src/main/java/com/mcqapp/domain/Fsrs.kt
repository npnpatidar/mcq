package com.mcqapp.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * FSRS-7: the dual-trace memory model that replaces SM-2.
 *
 * Three numbers carry all the memory: [CardState.stability] (the slow trace),
 * [CardState.stabilityFast] (the fast trace) and [CardState.difficulty]
 * (1..10, how hard the material is for this learner). Every grade updates all
 * three from the official FSRS-7 formulas, ported from the reference
 * implementation (open-spaced-repetition/fsrs-rs `model_v7.rs` and ts-fsrs
 * `models/fsrs-7`) rather than from a prose description, and validated against
 * its published vectors in `FsrsSchedulerTest`.
 *
 * What FSRS-7 changes against FSRS-6, all of it load-bearing here:
 *
 *  - **Two stability traces instead of one.** Recall is a difficulty-modulated
 *    mixture of a fast and a slow forgetting curve, and the two traces are
 *    updated by separate parameter blocks. FSRS-7 has *no* same-day/short-term
 *    branch: a review ten minutes later runs the same two update formulas at a
 *    fractional elapsed time, so [elapsedDaysSinceLastReview] staying fractional
 *    is now part of the model, not a nicety.
 *  - **Stability is not an interval.** Neither trace alone, nor the mixture at
 *    t = S, is the interval at which recall reaches [desiredRetention]; that
 *    quantity has to be solved numerically ([intervalForRetention]). Anything
 *    that shows or exports "stability in days" as a delay is wrong.
 *
 * Deliberate deviations from the reference scheduler, inherited from the plan:
 *  - No learning/relearning step ladder. A failed card comes back after
 *    [SchedulerConfig.relearnMs] like SM-2's relearn path; the step ladder
 *    would add a second, parallel notion of "short interval" the study loop
 *    has no UI for. FSRS-7's fast trace still records the short-term gain.
 *  - **One rounding rule, pinned to TS-FSRS.** Both the initial state and every
 *    updated state are rounded to 8 decimals ([round8]). TS-FSRS rounds both;
 *    the audited `fsrs-rs` build does not round at all. The difference is at
 *    most 5e-9 per value, far below the 1e-4 parity tolerance the reference
 *    vectors are compared with, and rounding both sides keeps the app's state
 *    identical whichever path produced it (a first review vs. a later one).
 *  - No fuzz. Intervals are deterministic for a card and a given instant, so
 *    a preview matches what grading would persist at the moment it was drawn;
 *    elapsed time can still change the exact delay before the tap.
 *  - **Whole-day intervals.** The reference returns fractional days; this app
 *    stores `intervalDays: Int` because [DayBoundary.dueAfter], the Anki day
 *    mapping and every schedule surface are whole-day. Only the interval is
 *    rounded — the elapsed time fed into the model is exact.
 *  - **A card with memory but no fast trace is not an error.** The reference
 *    throws when either stability is non-positive. Imported cards (an `.apkg`
 *    carries no FSRS memory at all) and rows written before the FSRS-7 rebuild
 *    finished arrive with `stabilityFast = 0`; [fastStabilityOf] collapses those
 *    onto the slow trace, which blends two decays over one scale instead of
 *    claiming a within-day component nobody observed.
 *
 * Pure and deterministic: no clock, no randomness, no Android types.
 *
 * @param parameters the 34 FSRS-7 weights, in the reference order. Null — the
 *   default — takes [SchedulerConfig.fsrsParameters], so a fitted set from the
 *   optimizer flows into every scheduler without each call site changing.
 * @param desiredRetention the recall probability intervals target, 0..1.
 *   0.9 is the FSRS default.
 */
class FsrsScheduler(
    parameters: List<Double>? = null,
    val desiredRetention: Double = DEFAULT_DESIRED_RETENTION,
    private val config: SchedulerConfig = SchedulerConfig(),
    /**
     * The device's UTC offset, injected rather than read so the scheduler
     * stays deterministic.
     */
    private val zoneOffset: (Long) -> Long = DayBoundary::zoneOffset
) : Scheduler {

    private val w: DoubleArray = (parameters ?: config.fsrsParameters).toDoubleArray()

    init {
        require(w.size == PARAMETER_COUNT) {
            "FSRS-7 needs $PARAMETER_COUNT parameters, got ${w.size}"
        }
    }

    override val leechThreshold: Int get() = config.leechThreshold

    override fun initial(questionId: String): CardState = CardState(questionId = questionId)

    override fun next(state: CardState, grade: ReviewGrade, now: Long): CardState {
        val rating = grade.ordinal + 1
        val failed = grade == ReviewGrade.AGAIN
        val hadMemory = state.state != CardStateKind.NEW && state.stability > 0.0

        // Elapsed time in (fractional) days. Unlike FSRS-6 there is no same-day
        // shortcut to take here: FSRS-7 evaluates the same formulas at
        // fractional t, so a review 10 minutes later is t = 0.007, not a
        // separate short-term branch. A card may still carry memory without a
        // last-review timestamp (an Anki import whose history is unknown): "no
        // last review" on a card with memory means "reviewed about its interval
        // ago, and due now", which is what the sentinel below encodes.
        val elapsedDays = elapsedDaysSinceLastReview(state, now)

        val memory = if (hadMemory) {
            updateMemory(
                stability = state.stability,
                stabilityFast = fastStabilityOf(state),
                difficulty = state.difficulty,
                elapsedDays = elapsedDays,
                rating = rating
            )
        } else {
            initialMemory(rating)
        }

        // A failed graduated card is relearning; a failed never-graduated one
        // is still learning. Only a graduated card can lapse — a card that has
        // not yet survived one interval has no memory to lose, matching SM-2.
        val kind = when {
            failed && state.state == CardStateKind.REVIEW -> CardStateKind.RELEARNING
            failed -> if (state.state == CardStateKind.RELEARNING) {
                CardStateKind.RELEARNING
            } else {
                CardStateKind.LEARNING
            }
            else -> CardStateKind.REVIEW
        }
        val lapses = if (failed && state.state == CardStateKind.REVIEW) {
            state.lapses + 1
        } else {
            state.lapses
        }

        // A failure returns inside the sitting (real elapsed time, never
        // snapped); anything else takes the FSRS interval from the start of
        // the study day, as in Anki and SM-2.
        val interval: Int
        val dueAt: Long
        if (failed) {
            interval = 0
            dueAt = now + config.relearnMs
        } else {
            interval = intervalDaysFor(memory)
            dueAt = DayBoundary.dueAfter(now, interval, config.dayStartHour, zoneOffset(now))
        }

        return state.copy(
            ease = easeFromDifficulty(memory.difficulty),
            intervalDays = interval,
            dueAt = dueAt,
            reps = state.reviewCount + 1,
            lapses = lapses,
            leech = lapses >= config.leechThreshold,
            lastReviewedAt = now,
            difficulty = memory.difficulty,
            stability = memory.stability,
            stabilityFast = memory.stabilityFast,
            state = kind,
            reviewCount = state.reviewCount + 1
        )
    }

    /**
     * The FSRS-7 retrievability: predicted recall probability from the elapsed
     * time and the card's memory state. Zero for a card with no memory, as the
     * reference scheduler returns 0 for a never-reviewed card.
     */
    override fun retention(state: CardState, now: Long): Double =
        recallAt(state, elapsedDaysSinceLastReview(state, now))

    /**
     * [retention] with the elapsed time supplied directly, in fractional days.
     *
     * The optimizer replays each card's history at its own fractional gaps, so
     * it must not go through a millisecond timestamp (or through the
     * no-timestamp sentinel above); this is the shared entry point that keeps
     * training and scheduling on one formula.
     */
    internal fun recallAt(state: CardState, elapsedDays: Double): Double {
        if (state.stability <= 0.0) return 0.0
        val curve = prepareCurve(
            stability = state.stability.coerceIn(STABILITY_MIN, MAX_STABILITY),
            stabilityFast = fastStabilityOf(state).coerceIn(STABILITY_MIN, MAX_STABILITY),
            difficulty = state.difficulty.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
        )
        return recall(curve, elapsedDays)
    }

    /**
     * One review's **memory-only** update at an explicit fractional elapsed
     * time: the three state fields move, nothing else does.
     *
     * This is the same arithmetic [next] runs, split out because the optimizer
     * replays a whole history per gradient evaluation. Without the split, every
     * replayed review would pay for an interval solve — seven Newton steps plus
     * a possible 50-step bisection — which is exactly the kind of silent
     * regression that still produces correct numbers.
     */
    internal fun advanceMemory(state: CardState, rating: Int, elapsedDays: Double): CardState {
        val hadMemory = state.state != CardStateKind.NEW && state.stability > 0.0
        val memory = if (hadMemory) {
            updateMemory(
                stability = state.stability,
                stabilityFast = fastStabilityOf(state),
                difficulty = state.difficulty,
                elapsedDays = elapsedDays,
                rating = rating
            )
        } else {
            initialMemory(rating)
        }
        return state.copy(
            difficulty = memory.difficulty,
            stability = memory.stability,
            stabilityFast = memory.stabilityFast,
            // The first review graduates the card (or, for a card carrying no
            // memory at all, re-seeds it). Without moving off NEW the next
            // replayed review would initialize a second time instead of
            // updating, which is exactly the kind of silent history corruption
            // this split exists to prevent.
            state = CardStateKind.REVIEW,
            reviewCount = state.reviewCount + 1
        )
    }

    /**
     * Fractional days until [state]'s mixed recall curve reaches [retention].
     *
     * This is the replacement for FSRS-6's "stability *is* the interval at the
     * benchmark retention". It is not used by [next] (which rounds and snaps to
     * a study day) but is needed by the Anki import mapping and by anything
     * that wants to show a delay rather than a model quantity.
     *
     * @throws IllegalArgumentException when [retention] is outside (0, 1).
     */
    fun intervalForRetention(state: CardState, retention: Double): Double {
        require(retention > 0.0 && retention < 1.0) {
            "retention must be in (0, 1), got $retention"
        }
        return solveInterval(
            stability = state.stability.coerceIn(STABILITY_MIN, MAX_STABILITY),
            stabilityFast = fastStabilityOf(state).coerceIn(STABILITY_MIN, MAX_STABILITY),
            difficulty = state.difficulty.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY),
            retention = retention
        )
    }

    /**
     * Days since the card's last review, shared by [next] and [retention] so
     * the two can never disagree about what "never reviewed" means.
     */
    private fun elapsedDaysSinceLastReview(state: CardState, now: Long): Double {
        if (state.lastReviewedAt == 0L) {
            // No timestamp at all: a genuinely new card has elapsed 0, while a
            // card with memory was last reviewed ~its interval ago (it is due
            // now with that interval, so elapsed = interval is what its own
            // schedule claims) — retrievability lands on the retention target.
            return if (state.stability > 0.0) state.intervalDays.toDouble().coerceAtLeast(1.0)
            else 0.0
        }
        return ((now - state.lastReviewedAt).toDouble() / DAY_MS).coerceAtLeast(0.0)
    }

    override fun reset(state: CardState): CardState = initial(state.questionId)

    // --- FSRS-7 memory, in the reference implementation's order ---

    /** The three numbers that move. */
    private class Memory(
        val stability: Double,
        val stabilityFast: Double,
        val difficulty: Double
    )

    /**
     * The time-independent half of the forgetting curve. It depends only on the
     * card's memory state, so a caller that evaluates several elapsed times for
     * one state — the optimizer's loss, the interval solver — can prepare it
     * once.
     */
    private class Curve(
        val fastDecay: Double,
        val fastScale: Double,
        val slowDecay: Double,
        val slowScale: Double,
        val fastWeight: Double,
        val slowWeight: Double,
        val total: Double
    )

    /** D₀(G) = w₄ − e^(w₅·(G−1)) + 1, unclamped: the raw 1..10 scale anchor. */
    private fun difficultyZero(rating: Int): Double =
        w[4] - exp(w[5] * (rating - 1)) + 1.0

    /**
     * A first exposure: S₀ = w[G−1], Sf₀ = 0.8·S₀, D₀ clamped to 1..10.
     *
     * The 0.8 ratio is legitimate exactly here and after an `Again` — it is not
     * a migration formula for an already-learned card. Rounded with the same
     * rule as an update ([updateMemory]): see the class KDoc's parity note.
     */
    private fun initialMemory(rating: Int): Memory {
        val stability = w[rating - 1].coerceIn(STABILITY_MIN, INIT_S_MAX)
        return Memory(
            stability = round8(stability).coerceIn(STABILITY_MIN, MAX_STABILITY),
            stabilityFast = round8(stability * FAST_INIT_RATIO)
                .coerceIn(STABILITY_MIN, MAX_STABILITY),
            difficulty = round8(difficultyZero(rating))
                .coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
        )
    }

    /**
     * One review of a card that already has memory: both traces update, then
     * difficulty, then the state is rounded to 8 decimals and clamped — the
     * reference's `next_state`, minus the interval.
     *
     * Keeping this separate from [next] is what lets the optimizer replay
     * history without paying for an interval solve per review.
     */
    private fun updateMemory(
        stability: Double,
        stabilityFast: Double,
        difficulty: Double,
        elapsedDays: Double,
        rating: Int
    ): Memory {
        val s = stability.coerceIn(STABILITY_MIN, MAX_STABILITY)
        val sf = stabilityFast.coerceIn(STABILITY_MIN, MAX_STABILITY)
        val d = difficulty.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)

        val curve = prepareCurve(s, sf, d)
        val mixedRecall = recall(curve, elapsedDays)
        val fastRecall = fastRecall(curve, elapsedDays)

        val slow = traceUpdate(s, d, mixedRecall, rating, SLOW_OFFSET)
        var fast = traceUpdate(sf, d, fastRecall, rating, FAST_OFFSET)
        // A lapse cannot leave the fast trace far above the slow one: the
        // relearning schedule would then outlive the memory that produced it.
        if (rating == 1) fast = min(fast, slow * FAST_LAPSE_CAP)

        // Difficulty uses the PRE-review difficulty and the mixed recall.
        var delta = -w[6] * (rating - 3)
        if (rating == 1) delta *= mixedRecall + 0.1
        val reverted = DIFFICULTY_REVERSION * difficultyZero(4) +
            (1.0 - DIFFICULTY_REVERSION) * (d + (MAX_DIFFICULTY - d) * delta / 9.0)

        return Memory(
            stability = round8(slow).coerceIn(STABILITY_MIN, MAX_STABILITY),
            stabilityFast = round8(fast).coerceIn(STABILITY_MIN, MAX_STABILITY),
            difficulty = round8(reverted).coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
        )
    }

    /**
     * T_k(x, q, G): the new stability of one trace after a review.
     *
     * A lapse takes the post-lapse estimate L, capped at the current stability;
     * a success takes max(L, x · growth) so a Hard on easy material can never
     * *shrink* stability.
     */
    private fun traceUpdate(
        x: Double,
        difficulty: Double,
        recall: Double,
        rating: Int,
        offset: Int
    ): Double {
        val fail = w[offset + 3] * ((x + 1.0).pow(w[offset + 4]) - 1.0) *
            exp((1.0 - recall) * w[offset + 5])
        val lapse = min(x, fail)
        if (rating == 1) return lapse

        val hard = if (rating == 2) w[offset + 6] else 1.0
        val easy = if (rating == 4) w[offset + 7] else 1.0
        val growth = exp(w[offset] - 1.5) * (11.0 - difficulty) * x.pow(-w[offset + 1]) *
            (exp((1.0 - recall) * w[offset + 2]) - 1.0) * hard * easy
        return max(lapse, x * (1.0 + growth))
    }

    /** Clamps the pre-review state and precomputes the curve's constants. */
    private fun prepareCurve(stability: Double, stabilityFast: Double, difficulty: Double): Curve {
        val fastDecay = -w[23] * stabilityFast.pow(w[33] - 0.3)
        val clampedFastDecay = fastDecay.coerceIn(-MAX_DECAY, -MIN_DECAY)
        val fastFactor = exp(min(ln(w[25]) / clampedFastDecay, LOG_FACTOR_CEILING)) - 1.0

        val slowDecay = (-w[24]).coerceIn(-MAX_DECAY, -MIN_DECAY)
        val slowFactor = (w[26].pow(1.0 / slowDecay) - 1.0) *
            exp((difficulty - 5.0) * (w[32] - 0.3))

        val fastWeight = w[27] * stabilityFast.pow(-w[29])
        val slowWeight = w[28] * stability.pow(w[30]) *
            exp((difficulty - 5.0) * (w[31] - 0.5))

        return Curve(
            fastDecay = clampedFastDecay,
            fastScale = fastFactor / stabilityFast,
            slowDecay = slowDecay,
            slowScale = slowFactor / stability,
            fastWeight = fastWeight,
            slowWeight = slowWeight,
            total = fastWeight + slowWeight
        )
    }

    /** R_f: the raw fast-trace recall. */
    private fun fastRecall(curve: Curve, elapsedDays: Double): Double {
        val t = max(elapsedDays, 0.0)
        return (1.0 + curve.fastScale * t).pow(curve.fastDecay)
    }

    /** R: the difficulty-modulated mixture of both traces, with the epsilon floor. */
    private fun recall(curve: Curve, elapsedDays: Double): Double {
        val t = max(elapsedDays, 0.0)
        val slow = (1.0 + curve.slowScale * t).pow(curve.slowDecay)
        val mixed = (curve.fastWeight * fastRecall(curve, t) + curve.slowWeight * slow) /
            curve.total
        return mixed * (1.0 - 2e-5) + 1e-5
    }

    /** dR/dt of the mixture, for the interval solver's Newton step. */
    private fun recallDerivative(curve: Curve, elapsedDays: Double): Double {
        val t = max(elapsedDays, 0.0)
        val fastBase = 1.0 + curve.fastScale * t
        val slowBase = 1.0 + curve.slowScale * t
        val fast = curve.fastDecay * fastBase.pow(curve.fastDecay - 1.0) * curve.fastScale
        val slow = curve.slowDecay * slowBase.pow(curve.slowDecay - 1.0) * curve.slowScale
        return (curve.fastWeight * fast + curve.slowWeight * slow) / curve.total *
            (1.0 - 2e-5)
    }

    /** The whole-day interval [next] would schedule for [memory]. */
    private fun intervalDaysFor(memory: Memory): Int {
        val days = solveInterval(
            stability = memory.stability,
            stabilityFast = memory.stabilityFast,
            difficulty = memory.difficulty,
            retention = desiredRetention
        )
        // Clamp in Long, narrow last: the solver can sit past Int.MAX_VALUE, and
        // `Math.round(days).toInt()` wraps — 2³¹ becomes -2147483648 — so
        // narrowing first floors a card that should hit the ceiling to 1 day.
        return Math.round(days)
            .coerceIn(1L, config.maxIntervalDays.toLong())
            .toInt()
    }

    /**
     * Solves R(I) = [retention] for I in days: up to seven bounded Newton steps
     * in log-time, then the reference's bisection fallback when the recall
     * error is still above [NEWTON_TOLERANCE].
     */
    private fun solveInterval(
        stability: Double,
        stabilityFast: Double,
        difficulty: Double,
        retention: Double
    ): Double {
        val target = retention.coerceIn(RETENTION_MIN, RETENTION_MAX)
        if (target >= RETENTION_MAX) return 0.0

        val curve = prepareCurve(stability, stabilityFast, difficulty)
        val maxStability = max(stability, stabilityFast)

        var logT = ln(maxStability)
        for (iteration in 0 until NEWTON_ITERATIONS) {
            logT = logT.coerceIn(LOG_MIN_T, LOG_MAX_T)
            val t = exp(logT).coerceIn(MIN_ELAPSED_DAYS, MAX_STABILITY)
            val slope = min(recallDerivative(curve, t) * t, -1e-12)
            logT -= ((recall(curve, t) - target) / slope)
                .coerceIn(-MAX_NEWTON_STEP, MAX_NEWTON_STEP)
            if (!logT.isFinite()) break
        }

        val solved = exp(logT).coerceIn(0.0, MAX_STABILITY)
        if (abs(recall(curve, solved) - target) <= NEWTON_TOLERANCE) return solved

        // Bisection: interval recall is monotone decreasing in t, so a bracket
        // that starts at max(S, Sf) and doubles can always be closed.
        var low = 0.0
        var high = min(max(maxStability, 1.0), MAX_STABILITY)
        while (recall(curve, high) > target && high < MAX_STABILITY) {
            high = min(high * 2.0, MAX_STABILITY)
        }
        repeat(BISECTION_ITERATIONS) {
            val mid = (low + high) / 2.0
            if (recall(curve, mid) > target) low = mid else high = mid
        }
        return (low + high) / 2.0
    }

    /**
     * The fast trace, or the slow one when it is unknown. See the class KDoc:
     * the reference throws here; this app must keep scheduling imported and
     * not-yet-rebuilt cards.
     */
    private fun fastStabilityOf(state: CardState): Double =
        if (state.stabilityFast > 0.0) state.stabilityFast else state.stability

    /**
     * The app's one state-rounding rule, applied to initial and updated values
     * alike: 8 decimals, matching TS-FSRS. See the class KDoc for the pinned
     * parity choice and its bounds against the non-rounding `fsrs-rs` build.
     */
    private fun round8(value: Double): Double = Math.round(value * 1e8) / 1e8

    /** Mirror of difficulty on SM-2's 1.3..3.0 ease scale, for export and UI. */
    private fun easeFromDifficulty(difficulty: Double): Double = (
        EASE_CEIL - (difficulty - MIN_DIFFICULTY) *
            (EASE_CEIL - EASE_FLOOR) / (MAX_DIFFICULTY - MIN_DIFFICULTY)
        ).coerceIn(EASE_FLOOR, EASE_CEIL)

    companion object {
        const val DAY_MS = DayBoundary.DAY_MS

        const val PARAMETER_COUNT = 34

        /** The ease scale difficulty is mirrored onto. Fixed: Anki's bounds. */
        const val EASE_FLOOR = 1.3
        const val EASE_CEIL = 3.0

        /** The retention the reference's published vectors are quoted at. */
        const val BENCHMARK_RETENTION = 0.9

        const val DEFAULT_DESIRED_RETENTION = 0.9

        /** The model's lower stability bound; also the init clamp. */
        const val STABILITY_MIN = 0.0001

        /** The model's upper stability bound, ~100 years. */
        const val MAX_STABILITY = 36500.0

        /** The initial-stability ceiling of the reference's fit (w0..w3). */
        const val INIT_S_MAX = 100.0

        const val MIN_DIFFICULTY = 1.0

        const val MAX_DIFFICULTY = 10.0

        /**
         * The official unoptimized FSRS-7 weights, in reference order:
         * w0..w3 initial stability, w4..w5 initial difficulty, w6 difficulty
         * step, w7..w14 the slow trace's update block, w15..w22 the fast
         * trace's update block, w23..w33 the dual-trace forgetting curve.
         */
        val DEFAULT_FSRS_PARAMETERS: List<Double> = listOf(
            0.1104, 2.2395, 3.9221, 11.7841, 6.1686, 0.6457, 3.6807, 1.9795,
            0.0, 1.3826, 0.7024, 0.5999, 0.8146, 0.6398, 1.0, 1.3207,
            0.6707, 3.8668, 0.4416, 0.0934, 1.8631, 0.6162, 1.0869, 0.1567,
            0.0801, 0.2421, 0.9464, 0.1433, 0.7145, 0.0, 0.5667, 0.3734,
            0.5333, 0.3048
        )

        /** Sf₀ = 0.8·S₀, and the cap on Sf after a lapse. */
        private const val FAST_INIT_RATIO = 0.8
        private const val FAST_LAPSE_CAP = 0.8

        /** Score offsets of the two update blocks: `w[k]`..`w[k+7]`. */
        private const val SLOW_OFFSET = 7
        private const val FAST_OFFSET = 15

        /** Difficulty's fixed mean-reversion coefficient (was w7 in FSRS-6). */
        private const val DIFFICULTY_REVERSION = 0.01

        private const val MIN_DECAY = 0.01
        private const val MAX_DECAY = 0.95
        private const val LOG_FACTOR_CEILING = 60.0

        private const val NEWTON_ITERATIONS = 7
        private const val MAX_NEWTON_STEP = 4.0
        private const val NEWTON_TOLERANCE = 1e-3

        private const val BISECTION_ITERATIONS = 50

        private const val RETENTION_MIN = 0.0001
        private const val RETENTION_MAX = 0.9999

        private const val MIN_ELAPSED_DAYS = 1.0 / 86400.0
        private val LOG_MIN_T = ln(MIN_ELAPSED_DAYS)
        private val LOG_MAX_T = ln(MAX_STABILITY)
    }
}
