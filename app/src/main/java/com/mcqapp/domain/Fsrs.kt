package com.mcqapp.domain

import kotlin.math.pow

/**
 * FSRS-6: the three-component memory model that replaces SM-2.
 *
 * Two numbers carry all the memory: [CardState.stability] (days until recall
 * probability falls to the benchmark retention) and [CardState.difficulty]
 * (1..10, how hard the material is for this learner). Every grade updates both
 * from the official FSRS-6 formulas, ported from the reference implementation
 * (open-spaced-repetition/py-fsrs) rather than from a prose description, and
 * validated against its published vectors in `FsrsSchedulerTest`.
 *
 * Deliberate deviations from the reference scheduler, both inherited from the
 * plan:
 *  - No learning/relearning step ladder. A failed card comes back after
 *    [SchedulerConfig.relearnMs] like SM-2's relearn path; the step ladder
 *    would add a second, parallel notion of "short interval" the study loop
 *    has no UI for.
 *  - No fuzz. Intervals are deterministic for a card and a given instant, so
 *    a preview matches what grading would persist at the moment it was drawn;
 *    elapsed time can still change the exact delay before the tap.
 *
 * Pure and deterministic: no clock, no randomness, no Android types.
 *
 * @param parameters the 21 FSRS-6 weights, in the reference order. Null — the
 *   default — takes [SchedulerConfig.fsrsParameters], so a fitted set from the
 *   optimizer flows into every scheduler without each call site changing.
 * @param desiredRetention the recall probability intervals target, 0..1.
 *   0.9 is the FSRS default: with it, a card's interval equals its stability.
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
            "FSRS-6 needs $PARAMETER_COUNT parameters, got ${w.size}"
        }
    }

    override val leechThreshold: Int get() = config.leechThreshold

    /** The power-law decay of recall over time: R = (1 + FACTOR·t/S)^DECAY. */
    private val decay: Double = -w[20]

    /**
     * Chosen so that R(S, S) = the benchmark retention of 0.9 — deliberately
     * the *benchmark* retention, not [desiredRetention]: the decay curve is a
     * property of memory, while the retention target only scales intervals.
     */
    private val factor: Double = BENCHMARK_RETENTION.pow(1.0 / decay) - 1.0

    override fun initial(questionId: String): CardState = CardState(questionId = questionId)

    override fun next(state: CardState, grade: ReviewGrade, now: Long): CardState {
        val rating = grade.ordinal + 1
        val failed = grade == ReviewGrade.AGAIN
        val hadMemory = state.state != CardStateKind.NEW && state.stability > 0.0

        // Elapsed time in (fractional) days. Whole-day values land exactly on
        // the reference implementation's day arithmetic, and the < 1 day test
        // below matches its floor-to-days boundary: 23.9h is a same-day
        // review, 24.1h is not.
        // A card may carry memory without a last-review timestamp (an Anki
        // import whose history is unknown): "no last review" on a card with
        // memory means "reviewed about its interval ago, and due now", not
        // "reviewed this instant" — so the same-day shortcut must not fire and
        // the card takes the recall formula for a card due exactly on time.
        val elapsedDays = elapsedDaysSinceLastReview(state, now)

        val stability = when {
            // First exposure: the formulas for never-studied memory.
            !hadMemory -> initialStability(rating)
            // Same-day review: short-term memory only, no retrievability term.
            elapsedDays < 1.0 -> shortTermStability(state.stability, rating)
            else -> {
                val retrievability = retrievabilityOf(state.stability, elapsedDays)
                if (failed) {
                    forgetStability(state.difficulty, state.stability, retrievability)
                } else {
                    recallStability(state.difficulty, state.stability, retrievability, rating)
                }
            }
        }

        // Difficulty always updates once memory exists, same-day or not.
        val difficulty = if (hadMemory) {
            nextDifficulty(state.difficulty, rating)
        } else {
            initialDifficulty(rating, clamp = true)
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
            interval = intervalFor(stability)
            dueAt = DayBoundary.dueAfter(now, interval, config.dayStartHour, zoneOffset(now))
        }

        return state.copy(
            ease = if (difficulty > 0.0) easeFromDifficulty(difficulty) else state.ease,
            intervalDays = interval,
            dueAt = dueAt,
            reps = state.reviewCount + 1,
            lapses = lapses,
            leech = lapses >= config.leechThreshold,
            lastReviewedAt = now,
            difficulty = difficulty,
            stability = stability,
            state = kind,
            reviewCount = state.reviewCount + 1
        )
    }

    /**
     * The FSRS retrievability: predicted recall probability from the elapsed
     * time and the card's stability. Zero for a card with no memory, as the
     * reference scheduler returns 0 for a never-reviewed card.
     */
    override fun retention(state: CardState, now: Long): Double {
        if (state.stability <= 0.0) return 0.0
        return retrievabilityOf(state.stability, elapsedDaysSinceLastReview(state, now))
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

    // --- FSRS-6 formulas, in the reference implementation's order ---

    /** S₀(G) = w[G−1]: the stability a first exposure earns. */
    private fun initialStability(rating: Int): Double =
        w[rating - 1].coerceAtLeast(STABILITY_MIN)

    /** D₀(G) = w₄ − e^(w₅·(G−1)) + 1, on the 1..10 difficulty scale. */
    private fun initialDifficulty(rating: Int, clamp: Boolean): Double {
        val d = w[4] - Math.exp(w[5] * (rating - 1)) + 1.0
        return if (clamp) d.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY) else d
    }

    /**
     * D' = w₇·D₀(Easy) + (1−w₇)·(D + linearDamping(ΔD)), where ΔD = −w₆·(G−3)
     * and the damping shrinks as difficulty approaches its ceiling. Mean
     * reversion pulls difficulty back toward the unclamped D₀(Easy) anchor.
     */
    private fun nextDifficulty(difficulty: Double, rating: Int): Double {
        val delta = -(w[6] * (rating - 3))
        val damped = (MAX_DIFFICULTY - difficulty) * delta / (MAX_DIFFICULTY - 1.0)
        val reverted = w[7] * initialDifficulty(ReviewGrade.EASY.ordinal + 1, clamp = false) +
            (1 - w[7]) * (difficulty + damped)
        return reverted.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }

    /**
     * Same-day review: S' = S·e^(w₁₇·(G−3+w₁₈))·S^(−w₁₉). A success must never
     * *shrink* stability, so the growth factor floors at 1.0 for Hard and up —
     * the raw formula can dip just under 1 for a Hard on easy material.
     */
    private fun shortTermStability(stability: Double, rating: Int): Double {
        var growth = Math.exp(w[17] * (rating - 3 + w[18])) * stability.pow(-w[19])
        if (rating > 1) growth = growth.coerceAtLeast(1.0)
        return (stability * growth).coerceAtLeast(STABILITY_MIN)
    }

    /**
     * Successful recall after ≥ 1 day:
     * S' = S·(1 + e^w₈·(11−D)·S^(−w₉)·(e^(w₁₀·(1−R))−1)·hardPenalty·easyBonus).
     * Growth is largest for easy material reviewed near the edge of forgetting.
     */
    private fun recallStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: Int
    ): Double {
        val hardPenalty = if (rating == 2) w[15] else 1.0
        val easyBonus = if (rating == 4) w[16] else 1.0
        val growth = 1.0 +
            Math.exp(w[8]) * (11.0 - difficulty) * stability.pow(-w[9]) *
            (Math.exp((1.0 - retrievability) * w[10]) - 1.0) * hardPenalty * easyBonus
        return (stability * growth).coerceAtLeast(STABILITY_MIN)
    }

    /**
     * Lapse after ≥ 1 day: the long-term post-lapse estimate, capped by the
     * short-term one so an interval barely survived is not rewarded with a
     * long relearning schedule.
     */
    private fun forgetStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double
    ): Double {
        val longTerm = w[11] * difficulty.pow(-w[12]) *
            ((stability + 1.0).pow(w[13]) - 1.0) *
            Math.exp((1.0 - retrievability) * w[14])
        val shortTerm = stability / Math.exp(w[17] * w[18])
        return minOf(longTerm, shortTerm).coerceAtLeast(STABILITY_MIN)
    }

    /** R(t, S) = (1 + FACTOR·t/S)^DECAY: the forgetting curve. */
    private fun retrievabilityOf(stability: Double, elapsedDays: Double): Double =
        (1.0 + factor * elapsedDays / stability).pow(decay).coerceIn(0.0, 1.0)

    /**
     * Days until retrievability falls to [desiredRetention]:
     * I = (S/FACTOR)·(r^(1/DECAY) − 1), rounded to whole days and clamped.
     * At the default 0.9 this is simply S.
     */
    private fun intervalFor(stability: Double): Int {
        val days = (stability / factor) * (desiredRetention.pow(1.0 / decay) - 1.0)
        // Clamp in Long, narrow last: `days` can sit past Int.MAX_VALUE, and
        // `Math.round(days).toInt()` wraps — 2³¹ becomes -2147483648, 3e9
        // becomes -1294967296 — so narrowing first floors a card that should
        // hit the ceiling to 1 day. Reachable with a fitted set: the
        // optimizer's bounds allow w17/w18 = 2.0 and w19 = 0.01, and the
        // same-day branch, which has no retrievability damping, then grows
        // stability a few hundredfold per grade (1.4e11 by the fifth in the
        // test's sequence). The long-term formula is unbounded for the same
        // reason w[9]'s lower bound is 0.0: it removes the S^(−w₉) damping.
        return Math.round(days)
            .coerceIn(1L, config.maxIntervalDays.toLong())
            .toInt()
    }

    /** Mirror of difficulty on SM-2's 1.3..3.0 ease scale, for export and UI. */
    private fun easeFromDifficulty(difficulty: Double): Double = (
        EASE_CEIL - (difficulty - MIN_DIFFICULTY) *
            (EASE_CEIL - EASE_FLOOR) / (MAX_DIFFICULTY - MIN_DIFFICULTY)
        ).coerceIn(EASE_FLOOR, EASE_CEIL)

    companion object {
        const val DAY_MS = DayBoundary.DAY_MS

        const val PARAMETER_COUNT = 21

        /** The ease scale difficulty is mirrored onto. Fixed: Anki's bounds. */
        const val EASE_FLOOR = 1.3
        const val EASE_CEIL = 3.0

        /** The retention the decay curve is anchored to. Fixed, not a setting. */
        const val BENCHMARK_RETENTION = 0.9

        const val DEFAULT_DESIRED_RETENTION = 0.9

        const val STABILITY_MIN = 0.001

        const val MIN_DIFFICULTY = 1.0

        const val MAX_DIFFICULTY = 10.0

        /**
         * The official unoptimized FSRS-6 weights, in reference order:
         * w0..w3 initial stability, w4..w5 initial difficulty, w6..w7
         * difficulty updates, w8..w10 recall growth, w11..w14 post-lapse
         * stability, w15 Hard penalty, w16 Easy bonus, w17..w19 short-term
         * memory, w20 the decay (negated).
         */
        val DEFAULT_FSRS_PARAMETERS: List<Double> = listOf(
            0.212,
            1.2931,
            2.3065,
            8.2956,
            6.4133,
            0.8334,
            3.0194,
            0.001,
            1.8722,
            0.1666,
            0.796,
            1.4835,
            0.0614,
            0.2629,
            1.6483,
            0.6014,
            1.8729,
            0.5425,
            0.0912,
            0.0658,
            0.1542
        )
    }
}
