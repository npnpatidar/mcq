package com.mcqapp.domain.stats

import kotlin.math.sqrt

/**
 * Slice 4 (stats-phase3.md §4): reliability and item analysis for a
 * representative (or, where the UI scopes it, practice) test family. Every
 * function is pure and deterministic so the JVM tests can pin the rules.
 *
 * None of this is shown when the sample cannot support it; callers gate on
 * [splitHalf] returning [SplitHalfResult.TooSmall].
 */
object Reliability {

    data class AttemptItems(
        val attemptId: Long,
        /** 1.0 for correct, 0.0 for incorrect/unanswered-in-scoring sense as passed. */
        val scores: List<Double>
    )

    sealed interface SplitHalfResult {
        data class Value(
            val halfCorrelation: Double,
            /** Spearman–Brown prophecy applied to the half correlation. */
            val reliability: Double,
            val attempted: Int
        ) : SplitHalfResult

        data class TooSmall(val attempted: Int, val reason: String) : SplitHalfResult
    }

    data class SemResult(
        val semPoints: Double,
        val semPercent: Double,
        val sdPoints: Double,
        val reliability: Double
    )

    data class Discrimination(
        val questionIndex: Int,
        val pointBiserial: Double,
        val flag: Flag
    ) {
        enum class Flag { REVIEW, WEAK, OK }
    }

    data class GuessingEstimate(
        val correctedProportion: Double,
        val rawProportion: Double,
        val k: Int
    )

    /** The same correction when the paper's items do not share one `k`. */
    data class ChanceEstimate(
        val correctedProportion: Double,
        val rawProportion: Double,
        /** The average uniform-guessing chance across the scored items. */
        val chance: Double
    )

    /**
     * One question's discrimination across attempts. The key is the question
     * id, not a position: two attempts of the same family can ask the same
     * questions in a different order.
     */
    data class ItemDiscrimination(
        val questionId: String,
        val pointBiserial: Double,
        val flag: Discrimination.Flag
    )

    const val MIN_ATTEMPTED = 20
    const val MIN_PER_HALF = 5
    const val GUESSING_MIN = 20

    /**
     * A discrimination estimate needs more than two points to mean anything;
     * below this the report says nothing rather than a noisy r.
     */
    const val MIN_DISCRIMINATION_ATTEMPTS = 5

    /**
     * Split-half odd/even by the order the items are passed (the question order
     * in the test), with Spearman–Brown correction. Requires >= [MIN_ATTEMPTED]
     * attempted items and >= [MIN_PER_HALF] in each half.
     */
    fun splitHalf(scores: List<Double>): SplitHalfResult {
        val n = scores.size
        if (n < MIN_ATTEMPTED) {
            return SplitHalfResult.TooSmall(n, "sample too small")
        }
        val odd = scores.filterIndexed { index, _ -> index % 2 == 0 }
        val even = scores.filterIndexed { index, _ -> index % 2 == 1 }
        if (odd.size < MIN_PER_HALF || even.size < MIN_PER_HALF) {
            return SplitHalfResult.TooSmall(n, "sample too small")
        }
        val r = pearson(odd, even)
        val reliability = spearmanBrown(r)
        return SplitHalfResult.Value(
            halfCorrelation = safe(r),
            reliability = safe(reliability),
            attempted = n
        )
    }

    /** Spearman–Brown prophecy formula: 2r / (1 + r). */
    fun spearmanBrown(halfCorrelation: Double): Double {
        val denom = 1.0 + halfCorrelation
        if (denom == 0.0) return 0.0
        return 2.0 * halfCorrelation / denom
    }

    /**
     * Standard error of measurement. Shown only when [reliability] is positive
     * and finite and [sdPoints] is finite. A perfect/zero-variance score returns
     * null (no NaN).
     */
    fun sem(sdPoints: Double, reliability: Double): SemResult? {
        if (!sdPoints.isFinite() || !reliability.isFinite()) return null
        if (reliability <= 0.0) return null
        val factor = 1.0 - reliability
        if (factor < 0.0) return null
        val sem = sdPoints * sqrt(factor)
        if (!sem.isFinite()) return null
        return SemResult(
            semPoints = sem,
            semPercent = sem, // caller scales to percent if scores are percents
            sdPoints = sdPoints,
            reliability = reliability
        )
    }

    /**
     * KR-20 for binary (0/1) items. Returns null when undefined (n < 2, zero
     * variance, or items not in {0,1} range in a way that breaks the formula).
     */
    fun kr20(itemScores: List<List<Double>>): Double? {
        if (itemScores.isEmpty()) return null
        val n = itemScores.size
        if (n < 2) return null
        val k = itemScores.first().size
        if (k < 2) return null
        if (itemScores.any { it.size != k }) return null

        val totals = itemScores.map { it.sum() }
        val totalVariance = variance(totals)
        if (totalVariance <= 0.0 || !totalVariance.isFinite()) return null

        var pqSum = 0.0
        for (j in 0 until k) {
            val p = itemScores.map { it[j] }.average()
            val q = 1.0 - p
            pqSum += p * q
        }
        val result = (k.toDouble() / (k - 1.0)) * (1.0 - pqSum / totalVariance)
        return if (result.isFinite()) result else null
    }

    /**
     * Point-biserial discrimination across attempts: [itemIndexScores] has one
     * list per attempt, each the full item score vector in the same order.
     * Returns one [Discrimination] per item position.
     */
    fun pointBiserialAcrossAttempts(itemIndexScores: List<List<Double>>): List<Discrimination> {
        if (itemIndexScores.isEmpty()) return emptyList()
        val k = itemIndexScores.first().size
        if (itemIndexScores.any { it.size != k }) return emptyList()
        if (itemIndexScores.size < 2) return List(k) { Discrimination(it, 0.0, Discrimination.Flag.WEAK) }

        val totals = itemIndexScores.map { it.sum() }
        return (0 until k).map { j ->
            val itemVals = itemIndexScores.map { it[j] }
            val restTotals = itemIndexScores.mapIndexed { i, scores -> totals[i] - scores[j] }
            val r = pearson(itemVals, restTotals)
            val safeR = safe(r)
            Discrimination(j, safeR, flagFor(safeR))
        }
    }

    private fun flagFor(r: Double): Discrimination.Flag = when {
        r < 0.0 -> Discrimination.Flag.REVIEW
        r < 0.15 -> Discrimination.Flag.WEAK
        else -> Discrimination.Flag.OK
    }

    /**
     * One attempt's graded rows, in the order they were asked. The builder
     * aligns these by question id, so two attempts of the same family may ask
     * the questions in different orders.
     */
    data class AttemptVector(val questionIds: List<String>, val scores: List<Double>)

    /**
     * Point-biserial discrimination per question id across attempts. A
     * question needs at least [MIN_DISCRIMINATION_ATTEMPTS] attempts that asked
     * it; below that it is absent from the map rather than reported as 0.
     * Attempts that did not ask the question are skipped, so a shuffled bank
     * still yields a per-question estimate.
     */
    fun discriminationByQuestion(
        attempts: List<AttemptVector>
    ): Map<String, ItemDiscrimination> {
        if (attempts.size < MIN_DISCRIMINATION_ATTEMPTS) return emptyMap()
        val totals = attempts.map { it.scores.sum() }
        val askedIn = LinkedHashMap<String, MutableList<Int>>()
        attempts.forEachIndexed { index, attempt ->
            for (id in attempt.questionIds) {
                askedIn.getOrPut(id) { mutableListOf() } += index
            }
        }
        val result = LinkedHashMap<String, ItemDiscrimination>()
        for ((questionId, indices) in askedIn) {
            if (indices.size < MIN_DISCRIMINATION_ATTEMPTS) continue
            val itemValues = ArrayList<Double>(indices.size)
            val restValues = ArrayList<Double>(indices.size)
            for (index in indices) {
                val attempt = attempts[index]
                val position = attempt.questionIds.indexOf(questionId)
                if (position < 0) continue
                val item = attempt.scores[position]
                itemValues += item
                restValues += totals[index] - item
            }
            if (itemValues.size < MIN_DISCRIMINATION_ATTEMPTS) continue
            val r = safe(pearson(itemValues, restValues))
            result[questionId] = ItemDiscrimination(questionId, r, flagFor(r))
        }
        return result
    }

    /**
     * Uniform-guessing chance of a correct answer: `1 / C(optionCount,
     * correctCount)`. A single-correct 4-option item gives 1/4; a 4-option
     * "pick 2" item gives 1/6, because only one of the six pairs is the key.
     */
    fun chanceOf(optionCount: Int, correctCount: Int): Double? {
        if (optionCount < 2 || correctCount < 1 || correctCount > optionCount) return null
        val combinations = binomial(optionCount, correctCount)
        if (combinations <= 0.0 || !combinations.isFinite()) return null
        return 1.0 / combinations
    }

    /**
     * Guessing correction for [k]-option items: `(p - 1/k) / (1 - 1/k)`.
     * Suppressed (returns null) below [GUESSING_MIN] items.
     */
    fun guessingCorrection(correct: Int, total: Int, k: Int = 4): GuessingEstimate? {
        if (k < 2) return null
        val est = guessingCorrectionWithChance(correct, total, 1.0 / k) ?: return null
        return GuessingEstimate(
            correctedProportion = est.correctedProportion,
            rawProportion = est.rawProportion,
            k = k
        )
    }

    /**
     * The same correction when the paper's items do not share one `k` (mixed
     * option counts, multi-correct items): [chance] is the average chance
     * level. Suppressed below [GUESSING_MIN] items and for a chance level that
     * is not a real probability.
     */
    fun guessingCorrectionWithChance(
        correct: Int,
        total: Int,
        chance: Double
    ): ChanceEstimate? {
        if (total < GUESSING_MIN) return null
        if (!chance.isFinite() || chance <= 0.0 || chance >= 1.0) return null
        val p = correct.toDouble() / total
        val corrected = (p - chance) / (1.0 - chance)
        return ChanceEstimate(
            correctedProportion = corrected.coerceIn(-1.0, 1.0),
            rawProportion = p,
            chance = chance
        )
    }

    private fun pearson(x: List<Double>, y: List<Double>): Double {
        val n = minOf(x.size, y.size)
        if (n < 2) return 0.0
        val mx = x.take(n).average()
        val my = y.take(n).average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in 0 until n) {
            val vx = x[i] - mx
            val vy = y[i] - my
            num += vx * vy
            dx += vx * vx
            dy += vy * vy
        }
        val denom = sqrt(dx * dy)
        if (denom == 0.0) return 0.0
        val r = num / denom
        return if (r.isFinite()) r.coerceIn(-1.0, 1.0) else 0.0
    }

    private fun variance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    /** C(n, k) in doubles: option counts are small, the product is not. */
    private fun binomial(n: Int, k: Int): Double {
        val kk = minOf(k, n - k)
        var result = 1.0
        for (i in 1..kk) {
            result = result * (n - kk + i) / i
        }
        return result
    }

    private fun safe(v: Double): Double = if (v.isFinite()) v else 0.0
}
