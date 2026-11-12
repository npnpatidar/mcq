package com.mcqapp.domain.stats

import com.mcqapp.domain.CardStateKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Grade-level calibration (plan phase 2): does the dwell ladder's Easy window
 * actually predict recall better than Good?
 *
 * The estimand is the **adjacent contrast** of mean prediction error, not a
 * per-grade error against zero:
 *
 * ```
 * δ1 = mean(correct - predicted | Easy) - mean(correct - predicted | Good)
 * δ2 = mean(correct - predicted | Hard) - mean(correct - predicted | Good)
 * ```
 *
 * The grade group is defined by the cut being tuned, so a per-grade error is a
 * property of the current partition, not an estimate of what moving the cut
 * would do. A common miscalibration (this learner's log is +6.7 pp overall)
 * cancels in the contrast. δ2 is reported and never acted on in v1.
 *
 * Everything here is pure: pairing, exclusions, intervals and block assignment
 * can be tested without a database.
 */
object Calibration {

    /** A pair needs a scheduled outcome horizon of this long to count as closed. */
    const val BLOCK_DAYS = 28

    /** How long a label's outcome has to arrive before its block is scored. */
    const val OUTCOME_HORIZON_DAYS = 90

    /** One day of slack before an outcome counts as missing rather than pending. */
    const val FOLLOW_UP_GRACE_DAYS = 1

    const val FOLLOW_UP_GATE = 0.70
    const val FOLLOW_UP_GAP = 0.10

    const val DIRECTION_THRESHOLD = 0.05
    const val MIN_PAIRS = 300
    const val MIN_CARDS_PER_CLASS = 40
    const val DESCRIPTIVE_CARDS = 20
    const val MAX_HALF_WIDTH = 0.05

    const val RESAMPLES = 5_000
    const val SEED = 20261111L

    private const val DAY_MS = 86_400_000L
    private const val BLOCK_MS = BLOCK_DAYS * DAY_MS

    /** The three ladder grades, in output order. */
    enum class LadderGrade(val rating: Int, val stored: String) {
        HARD(2, "Hard"),
        GOOD(3, "Good"),
        EASY(4, "Easy");
    }

    /** One review-log row, with only the fields calibration reads. */
    data class Row(
        val id: Long,
        val paperId: String,
        val questionId: String,
        val rating: Int,
        val reviewedAt: Long,
        val gradeSource: String?,
        val proposedRating: Int?,
        val ladderVersion: Int,
        val fsrsFitVersion: Int?,
        val predictedRecall: Double?,
        val stateBefore: CardStateKind?,
        val wasGuess: Boolean?,
        val wasCorrect: Boolean?,
        val wasSkipped: Boolean?,
        val intervalDaysAfter: Int?
    )

    /** One usable label → outcome pair. */
    data class Sample(
        val paperId: String,
        val questionId: String,
        val grade: LadderGrade,
        /** 0.0 or 1.0. */
        val outcome: Double,
        val predicted: Double,
        val labelReviewedAt: Long
    ) {
        val residual: Double get() = outcome - predicted
    }

    /** Why a candidate pair was dropped; each has its own counter for the UI. */
    enum class Exclusion {
        LABEL_NOT_AUTO,
        LABEL_AGAIN,
        LABEL_GUESS,
        LABEL_SKIPPED,
        NEW_EITHER_ROW,
        NO_FORECAST,
        NO_OUTCOME
    }

    data class ExclusionCount(val reason: Exclusion, val count: Int)

    /** One grade's descriptive numbers. */
    data class GradeStat(
        val grade: LadderGrade,
        val pairs: Int,
        val cards: Int,
        /** Observed recall in this grade's paired outcomes. */
        val recall: Double?,
        val predictedMean: Double?,
        val meanResidual: Double?
    )

    enum class IntervalMethod { BCA, T, NONE }

    /** The adjacent contrast and its 90% card-clustered interval. */
    data class Contrast(
        val value: Double?,
        val low: Double?,
        val high: Double?,
        val method: IntervalMethod,
        /** The smaller of the two classes' distinct-card counts. */
        val bindingCards: Int
    ) {
        val halfWidth: Double? get() = if (low == null || high == null) null else (high - low) / 2

        /**
         * Plan §2.1. Easy underperforming Good means the Easy window is too
         * wide, so the cut moves down; the reverse moves it up. δ2 is never a
         * movement in v1.
         */
        val direction: Direction
            get() = when {
                value == null -> Direction.NONE
                value <= -Calibration.DIRECTION_THRESHOLD -> Direction.LOWER
                value >= Calibration.DIRECTION_THRESHOLD -> Direction.RAISE
                else -> Direction.NONE
            }
    }

    enum class Direction { LOWER, RAISE, NONE }

    /** Follow-up coverage among labels whose outcome is due, per grade. */
    data class FollowUp(
        val grade: LadderGrade,
        val due: Int,
        val observed: Int
    ) {
        val rate: Double? get() = if (due == 0) null else observed.toDouble() / due
    }

    /** The first-exposure stratum; reported, never fitted (plan §2.2). */
    data class FirstExposure(
        val grade: LadderGrade,
        val count: Int,
        val correct: Int
    ) {
        val recall: Double? get() = if (count == 0) null else correct.toDouble() / count
    }

    /** One fixed 28-day window of label dates. */
    data class Block(
        val index: Int,
        val startAt: Long,
        val endAt: Long,
        val pairs: Int,
        val easyPairs: Int,
        val goodPairs: Int,
        val hardPairs: Int,
        val closed: Boolean,
        /**
         * Easy-vs-Good δ for this window alone. Present only on the last two
         * closed windows, which are all the phase-5 safety valve inspects, so
         * the report does not pay for a bootstrap over every historical block.
         */
        val delta1: Double? = null,
        val delta1Low: Double? = null,
        val delta1High: Double? = null
    ) {
        /** The block's own minimum detectable effect: half its interval. */
        val minimumDetectableEffect: Double?
            get() = if (delta1Low == null || delta1High == null) null
            else (delta1High - delta1Low) / 2.0
    }

    /** Plan §4.1, stated as three numbers the UI can show even when short. */
    data class Readiness(
        val pairs: Int,
        val bindingCards: Int,
        val halfWidth: Double?,
        val calendarDays: Int,
        val refitInEpoch: Boolean
    ) {
        val volumeMet: Boolean get() = pairs >= Calibration.MIN_PAIRS
        val cardsMet: Boolean get() = bindingCards >= Calibration.MIN_CARDS_PER_CLASS
        val precisionMet: Boolean get() = (halfWidth ?: 1.0) <= Calibration.MAX_HALF_WIDTH
        val calendarMet: Boolean get() = calendarDays >= Calibration.BLOCK_DAYS
        val ready: Boolean
            get() = volumeMet && cardsMet && precisionMet && calendarMet && !refitInEpoch
    }

    /** Everything the retention surface renders. */
    data class Report(
        val pairs: Int,
        val excluded: Int,
        val exclusions: List<ExclusionCount>,
        val grades: List<GradeStat>,
        val contrast1: Contrast,
        val contrast2: Contrast,
        val followUp: List<FollowUp>,
        /** Plan §2.2: a compared class below 70% follow-up, or a >10 pp gap, fails. */
        val followUpGatePassed: Boolean,
        val followUpGap: Double?,
        val firstExposure: List<FirstExposure>,
        val blocks: List<Block>,
        val epochStart: Long,
        val epochDays: Int,
        val readiness: Readiness,
        /** True when the Hard contrast is below its own precision gate. */
        val hardUnderpowered: Boolean
    ) {
        val hasPairs: Boolean get() = pairs > 0
        val isEmpty: Boolean get() = pairs == 0 && firstExposure.all { it.count == 0 }
    }

    /**
     * Builds the report. [now] decides which blocks are closed and which
     * labels' outcomes are due; [epochStart] is the effective time of the
     * current ladder version (0 when no version exists).
     */
    fun report(
        reviews: List<Row>,
        now: Long,
        epochStart: Long,
        refitInEpoch: Boolean = false
    ): Report {
        val byCard = reviews.groupBy { it.paperId to it.questionId }
        val pairs = ArrayList<Sample>()
        val exclusions = HashMap<Exclusion, Int>()
        fun drop(reason: Exclusion) {
            exclusions[reason] = (exclusions[reason] ?: 0) + 1
        }

        val followDue = HashMap<LadderGrade, Int>()
        val followObserved = HashMap<LadderGrade, Int>()
        val firstExposure = ArrayList<FirstExposure>()

        val firstCounts = HashMap<LadderGrade, IntArray>() // count, correct
        reviews.forEach { row ->
            if (row.stateBefore == CardStateKind.NEW && row.wasCorrect != null) {
                val grade = gradeOf(row.rating) ?: return@forEach
                val counts = firstCounts.getOrPut(grade) { IntArray(2) }
                counts[0]++
                if (row.wasCorrect) counts[1]++
            }
        }
        LadderGrade.entries.forEach { grade ->
            val counts = firstCounts[grade] ?: IntArray(2)
            firstExposure += FirstExposure(grade, counts[0], counts[1])
        }

        byCard.values.forEach { rows ->
            val ordered = rows.sortedWith(compareBy({ it.reviewedAt }, { it.id }))
            for (index in ordered.indices) {
                val label = ordered[index]
                val outcome = ordered.getOrNull(index + 1)
                // Follow-up counts every label whose scheduled outcome is due,
                // whether the outcome arrived or not, so long intervals are not
                // punished for being long (plan §2.2).
                if (exclusionOf(label) == null) {
                    val grade = gradeOf(label.rating)
                    if (grade != null) {
                        val dueAt = label.reviewedAt +
                            (label.intervalDaysAfter ?: 0) * DAY_MS +
                            FOLLOW_UP_GRACE_DAYS * DAY_MS
                        if (dueAt <= now) {
                            followDue[grade] = (followDue[grade] ?: 0) + 1
                            if (outcome != null) {
                                followObserved[grade] = (followObserved[grade] ?: 0) + 1
                            }
                        }
                    }
                }
                if (outcome == null) continue
                val grade = labelGrade(label) ?: run {
                    exclusionOf(label)?.let(::drop)
                    continue
                }
                exclusionOf(label)?.let { drop(it); continue }
                if (label.stateBefore == CardStateKind.NEW ||
                    outcome.stateBefore == CardStateKind.NEW
                ) {
                    drop(Exclusion.NEW_EITHER_ROW); continue
                }
                if (label.predictedRecall == null || outcome.predictedRecall == null) {
                    drop(Exclusion.NO_FORECAST); continue
                }
                val predicted = outcome.predictedRecall
                val correct = outcome.wasCorrect ?: run {
                    drop(Exclusion.NO_OUTCOME); continue
                }
                pairs += Sample(
                    paperId = label.paperId,
                    questionId = label.questionId,
                    grade = grade,
                    outcome = if (correct) 1.0 else 0.0,
                    predicted = predicted,
                    labelReviewedAt = label.reviewedAt
                )
            }
        }

        val grades = LadderGrade.entries.map { grade -> gradeStat(grade, pairs) }
        val easy = pairs.filter { it.grade == LadderGrade.EASY }
        val good = pairs.filter { it.grade == LadderGrade.GOOD }
        val hard = pairs.filter { it.grade == LadderGrade.HARD }
        val contrast1 = contrast(easy, good)
        val contrast2 = contrast(hard, good)

        val followUp = LadderGrade.entries.map { grade ->
            FollowUp(grade, followDue[grade] ?: 0, followObserved[grade] ?: 0)
        }
        // Follow-up is a gate on the decision, not on the display: a class
        // whose outcomes are mostly not back yet is not evidence.
        val rates = followUp.mapNotNull { it.rate }
        val followUpGap = if (rates.size >= 2) rates.max() - rates.min() else null
        val followUpGatePassed = rates.all { it >= FOLLOW_UP_GATE } &&
            (followUpGap == null || followUpGap <= FOLLOW_UP_GAP)

        val blocks = blocks(pairs, epochStart, now)

        val epochFrom = when {
            epochStart > 0L -> epochStart
            pairs.isEmpty() -> now
            else -> pairs.minOf { it.labelReviewedAt }
        }
        val epochSeconds = (now - epochFrom).coerceAtLeast(0L)
        val epochDays = ceil(epochSeconds.toDouble() / DAY_MS).toInt()
        val binding = minOf(
            easy.map { it.paperId to it.questionId }.toSet().size,
            good.map { it.paperId to it.questionId }.toSet().size
        )
        val readiness = Readiness(
            pairs = pairs.size,
            bindingCards = binding,
            halfWidth = contrast1.halfWidth,
            calendarDays = epochDays,
            refitInEpoch = refitInEpoch
        )
        val hardUnderpowered = contrast2.bindingCards < MIN_CARDS_PER_CLASS
        return Report(
            pairs = pairs.size,
            excluded = exclusions.values.sum(),
            exclusions = Exclusion.entries.mapNotNull { reason ->
                exclusions[reason]?.let { ExclusionCount(reason, it) }
            },
            grades = grades,
            contrast1 = contrast1,
            contrast2 = contrast2,
            followUp = followUp,
            followUpGatePassed = followUpGatePassed,
            followUpGap = followUpGap,
            firstExposure = firstExposure,
            blocks = blocks,
            epochStart = epochFrom,
            epochDays = epochDays,
            readiness = readiness,
            hardUnderpowered = hardUnderpowered
        )
    }

    /** The label-side exclusions; null means the label is usable. */
    private fun exclusionOf(label: Row): Exclusion? = when {
        label.gradeSource != "AUTO" -> Exclusion.LABEL_NOT_AUTO
        label.rating == 1 -> Exclusion.LABEL_AGAIN
        label.wasGuess == true -> Exclusion.LABEL_GUESS
        label.wasSkipped == true -> Exclusion.LABEL_SKIPPED
        else -> null
    }

    /** The automatable label grade; Again has none (it is never the ladder's). */
    private fun labelGrade(label: Row): LadderGrade? = gradeOf(label.rating)

    private fun gradeOf(rating: Int): LadderGrade? =
        LadderGrade.entries.firstOrNull { it.rating == rating }

    private fun gradeStat(grade: LadderGrade, pairs: List<Sample>): GradeStat {
        val rows = pairs.filter { it.grade == grade }
        if (rows.isEmpty()) return GradeStat(grade, 0, 0, null, null, null)
        return GradeStat(
            grade = grade,
            pairs = rows.size,
            cards = rows.map { it.paperId to it.questionId }.toSet().size,
            recall = rows.sumOf { it.outcome } / rows.size,
            predictedMean = rows.sumOf { it.predicted } / rows.size,
            meanResidual = rows.sumOf { it.residual } / rows.size
        )
    }

    /**
     * The card-clustered 90% interval. Resampling cards rather than pairs keeps
     * a card's reviews from counting as independent facts; both classes are
     * resampled together so their covariance is preserved.
     */
    fun contrast(
        first: List<Sample>,
        good: List<Sample>,
        resamples: Int = RESAMPLES,
        seed: Long = SEED
    ): Contrast {
        val cardsFirst = cardsOf(first)
        val cardsGood = cardsOf(good)
        val binding = minOf(cardsFirst.size, cardsGood.size)
        if (first.isEmpty() || good.isEmpty()) {
            return Contrast(null, null, null, IntervalMethod.NONE, binding)
        }
        val observed = mean(first) - mean(good)
        // A class whose every outcome is identical has no estimable variance;
        // a percentile bootstrap over it would include zero for the wrong
        // reason (plan §2.3), so no interval is claimed.
        if (isDegenerate(cardsFirst) || isDegenerate(cardsGood)) {
            return Contrast(observed, null, null, IntervalMethod.NONE, binding)
        }
        if (binding < DESCRIPTIVE_CARDS) {
            return Contrast(observed, null, null, IntervalMethod.NONE, binding)
        }
        if (binding < MIN_CARDS_PER_CLASS) {
            val interval = tInterval(cardsFirst, cardsGood, observed)
                ?: return Contrast(observed, null, null, IntervalMethod.NONE, binding)
            return Contrast(observed, interval.first, interval.second, IntervalMethod.T, binding)
        }
        val deltas = DoubleArray(resamples)
        val random = Random(seed)
        for (i in 0 until resamples) {
            val a = resampleMean(cardsFirst, random)
            val b = resampleMean(cardsGood, random)
            deltas[i] = a - b
        }
        deltas.sort()
        // BCa: bias correction from the bootstrap distribution and
        // acceleration from the jackknife over both classes' cards.
        val z0 = inverseNormal(
            (deltas.count { it < observed }.toDouble() / resamples)
                .coerceIn(0.0005, 0.9995)
        )
        val acceleration = acceleration(cardsFirst, cardsGood)
        fun adjusted(alpha: Double): Double {
            val z = inverseNormal(alpha)
            val denom = 1.0 - acceleration * (z0 + z)
            return normalCdf(z0 + (z0 + z) / denom)
        }
        val lowQ = adjusted(0.05).coerceIn(0.0, 0.999)
        val highQ = adjusted(0.95).coerceIn(0.001, 1.0)
        val low = deltas[(lowQ * (resamples - 1)).toInt()]
        val high = deltas[(highQ * (resamples - 1)).toInt()]
        return Contrast(observed, low, high, IntervalMethod.BCA, binding)
    }

    /** Card key → its residuals; the resampling unit. */
    private fun cardsOf(pairs: List<Sample>): List<DoubleArray> =
        pairs.groupBy { it.paperId to it.questionId }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .values.map { rows -> rows.map { it.residual }.toDoubleArray() }

    private fun mean(pairs: List<Sample>): Double = pairs.sumOf { it.residual } / pairs.size

    private fun resampleMean(cards: List<DoubleArray>, random: Random): Double {
        var sum = 0.0
        var count = 0
        repeat(cards.size) {
            val card = cards[random.nextInt(cards.size)]
            sum += card.sum()
            count += card.size
        }
        return sum / count
    }

    private fun isDegenerate(cards: List<DoubleArray>): Boolean {
        val all = cards.flatMap { it.toList() }
        return all.isEmpty() || all.all { it == all.first() }
    }

    /** Welch t interval on per-card means, 90%. */
    private fun tInterval(
        first: List<DoubleArray>,
        good: List<DoubleArray>,
        observed: Double
    ): kotlin.Pair<Double, Double>? {
        fun cardMeans(cards: List<DoubleArray>) = cards.map { it.average() }
        val a = cardMeans(first)
        val b = cardMeans(good)
        fun variance(values: List<Double>): Double {
            if (values.size < 2) return 0.0
            val m = values.average()
            return values.sumOf { (it - m) * (it - m) } / (values.size - 1)
        }
        val va = variance(a)
        val vb = variance(b)
        val se = sqrt(va / a.size + vb / b.size)
        if (se == 0.0) return null
        // 90% two-sided, df floored at 19 in this tier; t is ≈1.70 there and
        // falls slowly. Using 1.70 is conservative for 20–39 cards.
        val t = 1.70
        return (observed - t * se) to (observed + t * se)
    }

    /** Jackknife acceleration over the pooled card list. */
    private fun acceleration(first: List<DoubleArray>, good: List<DoubleArray>): Double {
        val cards = first + good
        if (cards.size < 2) return 0.0
        fun deltaWithout(skip: Int): Double {
            val a = first.filterIndexed { i, _ -> i != skip }
            val b = good.filterIndexed { i, _ -> first.size + i != skip }
            if (a.isEmpty() || b.isEmpty()) return 0.0
            return a.flatMap { it.toList() }.average() - b.flatMap { it.toList() }.average()
        }
        val jack = cards.indices.map { deltaWithout(it) }
        val avg = jack.average()
        val num = jack.sumOf { (avg - it) * (avg - it) * (avg - it) }
        val den = 6.0 * Math.pow(jack.sumOf { (avg - it) * (avg - it) }, 1.5)
        return if (den == 0.0) 0.0 else num / den
    }

    /** Fixed 28-day windows anchored at the epoch start; only closed ones score. */
    private fun blocks(pairs: List<Sample>, epochStart: Long, now: Long): List<Block> {
        if (pairs.isEmpty()) return emptyList()
        val anchor = if (epochStart > 0L) epochStart else pairs.minOf { it.labelReviewedAt }
        val byBlock = pairs.groupBy { ((it.labelReviewedAt - anchor) / BLOCK_MS).toInt() }
        val closed = byBlock.keys.sorted().filter { index ->
            anchor + index * BLOCK_MS + BLOCK_MS + OUTCOME_HORIZON_DAYS * DAY_MS <= now
        }
        val safetyWindow = closed.takeLast(2).toSet()
        return byBlock.keys.sorted().map { index ->
            val start = anchor + index * BLOCK_MS
            val rows = byBlock[index].orEmpty()
            val isClosed = index in closed
            val contrast = if (index in safetyWindow) {
                contrast(
                    first = rows.filter { it.grade == LadderGrade.EASY },
                    good = rows.filter { it.grade == LadderGrade.GOOD },
                    seed = SEED + index
                )
            } else {
                null
            }
            Block(
                index = index,
                startAt = start,
                endAt = start + BLOCK_MS,
                pairs = rows.size,
                easyPairs = rows.count { it.grade == LadderGrade.EASY },
                goodPairs = rows.count { it.grade == LadderGrade.GOOD },
                hardPairs = rows.count { it.grade == LadderGrade.HARD },
                closed = isClosed,
                delta1 = contrast?.value,
                delta1Low = contrast?.low,
                delta1High = contrast?.high
            )
        }
    }

    // --- normal distribution, for the BCa adjustment ------------------------

    private fun normalCdf(x: Double): Double = 0.5 * (1.0 + erf(x / sqrt(2.0)))

    /** Abramowitz–Stegun 7.1.26; plenty for a bootstrap correction. */
    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val y = 1.0 - (
            (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t +
                0.254829592) * t
            ) * exp(-x * x)
        return if (x >= 0) y else -y
    }

    /** Acklam's inverse-normal approximation (|error| < 4.5e-4). */
    private fun inverseNormal(p: Double): Double {
        val a = doubleArrayOf(
            -3.969683028665376e+01, 2.209460984245205e+02,
            -2.759285104469687e+02, 1.383577518672690e+02,
            -3.066479806614716e+01, 2.506628277459239e+00
        )
        val b = doubleArrayOf(
            -5.447609879822406e+01, 1.615858368580409e+02,
            -1.556989798598866e+02, 6.680131188771972e+01,
            -1.328068155288572e+01
        )
        val c = doubleArrayOf(
            -7.784894002430293e-03, -3.223964580411365e-01,
            -2.400758277161838e+00, -2.549732539343734e+00,
            4.374664141464968e+00, 2.938163982698783e+00
        )
        val d = doubleArrayOf(
            7.784695709041462e-03, 3.224671290700398e-01,
            2.445134137142996e+00, 3.754408661907416e+00
        )
        val pLow = 0.02425
        val pHigh = 1 - pLow
        return when {
            p < pLow -> {
                val q = sqrt(-2 * Math.log(p))
                (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
            p <= pHigh -> {
                val q = p - 0.5
                val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1)
            }
            else -> {
                val q = sqrt(-2 * Math.log(1 - p))
                -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
        }
    }

    /** The measured half-width's stated minimum detectable effect. */
    fun minimumDetectableEffect(halfWidth: Double): Double = abs(halfWidth)
}
