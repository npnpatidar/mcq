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

    const val MIN_ATTEMPTED = 20
    const val MIN_PER_HALF = 5
    const val GUESSING_MIN = 20

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
     * Point-biserial discrimination is meaningful across attempts (item
     * correct/incorrect vs rest-of-test score). A single attempt cannot yield a
     * stable discrimination estimate; callers with one attempt should show
     * "sample too small" rather than a misleading 0.
     */
    fun pointBiserial(items: List<Double>): List<Discrimination> {
        if (items.size < 2) return emptyList()
        return items.indices.map { Discrimination(it, 0.0, Discrimination.Flag.WEAK) }
    }

    /**
     * Point-biserial across attempts: [itemIndexScores] has one list per
     * attempt, each the full item score vector in the same order. Returns one
     * [Discrimination] per item position.
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
     * Guessing correction for [k]-option items: `(p - 1/k) / (1 - 1/k)`.
     * Suppressed (returns null) below [GUESSING_MIN] items.
     */
    fun guessingCorrection(correct: Int, total: Int, k: Int = 4): GuessingEstimate? {
        if (total < GUESSING_MIN || k < 2) return null
        val p = correct.toDouble() / total
        val chance = 1.0 / k
        val corrected = (p - chance) / (1.0 - chance)
        return GuessingEstimate(
            correctedProportion = corrected.coerceIn(-1.0, 1.0),
            rawProportion = p,
            k = k
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

    private fun safe(v: Double): Double = if (v.isFinite()) v else 0.0
}
