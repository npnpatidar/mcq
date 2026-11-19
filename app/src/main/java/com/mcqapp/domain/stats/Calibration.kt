package com.mcqapp.domain.stats

import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.SchedulerConfig
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Grade-level calibration (plan phase 2): does the dwell cuts' Easy window
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

    /**
     * Answer-time evidence is much cheaper than outcome evidence: it needs no
     * matured pairs, only enough correct answers to place two percentiles.
     */
    const val MIN_DWELL_ROWS = 200
    const val MIN_DWELL_CARDS = 30
    const val MIN_DWELL_DAYS = 14

    /** The share of correct answers each outer band should receive, in percent. */
    const val DWELL_EASY_PERCENT = 10
    const val DWELL_HARD_PERCENT = 90

    /**
     * The Easy cut's floor, taken from the domain clamp rather than restated:
     * a 1 s cut is not worth proposing, because a correct answer under one
     * second is stored as 0 and graded Good, so the Easy band would be empty
     * and the proposal would snap back to 2 s on save.
     */
    const val MIN_EASY_SECONDS = SchedulerConfig.MIN_FAST_SECONDS

    /**
     * The answer-time pair's own floor: the estimate's noise floor, not the
     * domain clamp. A percentile of whole-second dwells cannot place a cut
     * below the resolution of its own measurement, so the answer-time
     * suggestion never proposes less than this. It coincides with
     * [MIN_EASY_SECONDS] today and is deliberately a separate number: every
     * answer-time fast cut is clamped to
     * `maxOf(MIN_DWELL_FAST_SECONDS, MIN_EASY_SECONDS)`, so this estimator
     * floor can never drift below the domain's legal floor.
     */
    const val MIN_DWELL_FAST_SECONDS = 2L
    const val MIN_DWELL_GAP_SECONDS = 5L
    const val MAX_DWELL_SLOW_SECONDS = 120L

    const val RESAMPLES = 5_000
    const val SEED = 20261111L

    private const val DAY_MS = 86_400_000L
    private const val BLOCK_MS = BLOCK_DAYS * DAY_MS

    /** The three automatable grades, in output order. */
    enum class GradeClass(val rating: Int, val stored: String) {
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
        val gradingVersion: Int,
        val fsrsFitVersion: Int?,
        val predictedRecall: Double?,
        val stateBefore: CardStateKind?,
        val wasGuess: Boolean?,
        val wasCorrect: Boolean?,
        val wasSkipped: Boolean?,
        val intervalDaysAfter: Int?,
        /**
         * Whole-second dwell; null when the caller had no trustworthy
         * measurement for this row. 0 is a measurement too: every answer given
         * inside the first second is stored as 0
         * ([com.mcqapp.domain.Study.gradedDwellSeconds]).
         */
        val elapsedSeconds: Long? = null,
        /**
         * Whether the grading version that produced this row ignored the clock.
         * Answer-time evidence only trusts these rows: when the clock is tinted
         * it feeds the cuts back to the learner, so the dwell distribution
         * would partly measure the cuts rather than the person.
         */
        val correctnessOnly: Boolean = false
    )

    /** One usable label → outcome pair. */
    data class Sample(
        val paperId: String,
        val questionId: String,
        val grade: GradeClass,
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
        val grade: GradeClass,
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

    /** One re-tune step never moves the Easy cut further than this. */
    const val MAX_STEP_SECONDS = 4

    /**
     * A read-only cut proposal: the report's own direction turned into the pair
     * to try next. Nothing applies it — the learner edits the two fields in the
     * scheduler settings, and the next report judges the result.
     *
     * The step is bounded because a contrast says which way to move and roughly
     * how far the evidence sits from agreement, not where the optimum is: one
     * report of a few hundred pairs cannot resolve seconds on its own.
     */
    data class CutSuggestion(
        val currentFast: Long,
        val currentSlow: Long,
        val nextFast: Long,
        val nextSlow: Long,
        val status: Status,
        /** How many reviews the proposal is based on; 0 when not applicable. */
        val evidence: Int = 0,
        /**
         * The gate that stopped an answer-time pair, so the UI can name the real
         * blocker instead of always quoting the row count. Null when no gate
         * was involved.
         */
        val blockingGate: Gate? = null,
        /** Distinct questions behind [evidence]; 0 when no gate needed them. */
        val evidenceCards: Int = 0,
        /** Days spanned by the answers behind [evidence]; 0 when not counted. */
        val evidenceDays: Int = 0
    ) {
        enum class Status {
            /** The gates are not met, so no number would be honest. */
            NEEDS_EVIDENCE,

            /** Easy and Good are calibrated alike; leave the pair alone. */
            KEEP,

            /** The Easy cut moves down. */
            STEP_DOWN,

            /** The Easy cut moves up. */
            STEP_UP,

            /**
             * A direction-specific wall: for a downward step, the Easy cut is
             * already at its floor; for an upward step, the Hard cut caps how
             * far the Easy cut may rise.
             */
            AT_LIMIT,

            /**
             * Chosen from answer times alone, before the first timed review:
             * a starting pair, not a retention finding.
             */
            FROM_TIMES
        }

        /** The gates of [suggestCutsFromDwell], in the order they are checked. */
        enum class Gate {
            /** Too few correct, clock-neutral answers. */
            ROWS,

            /** Too few distinct questions behind them. */
            CARDS,

            /** Recorded over too short a span to be a pace. */
            DAYS
        }

        val changes: Boolean get() = nextFast != currentFast || nextSlow != currentSlow
    }

    /**
     * Converts the report into the pair to try next. The Hard cut is shown but
     * never moved: δ2 is not acted on, so a suggestion that moved it would
     * launder a number the evidence does not support.
     *
     * A downward step stops at [MIN_EASY_SECONDS] rather than being refused:
     * the step size is the evidence's estimate, the floor is the domain's
     * limit, and a learner at 3 s with strong evidence is still one legal
     * second away from the floor. In that direction,
     * [CutSuggestion.Status.AT_LIMIT] means the Easy cut is already at the
     * floor.
     *
     * An upward step is different: the Hard cut caps how far the Easy cut may
     * rise, so [CutSuggestion.Status.AT_LIMIT] means the proposed target
     * would reach or pass the Hard cut. The target is not clamped down to
     * `slow - 1`: a one-second Good band is nearly the degenerate pair the
     * app refuses elsewhere.
     */
    fun suggestCuts(report: Report, fastSeconds: Long, slowSeconds: Long): CutSuggestion {
        fun keep(status: CutSuggestion.Status) =
            CutSuggestion(fastSeconds, slowSeconds, fastSeconds, slowSeconds, status)

        if (!report.readiness.ready || !report.followUpGatePassed) {
            return keep(CutSuggestion.Status.NEEDS_EVIDENCE)
        }
        val contrast = report.contrast1
        val value = contrast.value ?: return keep(CutSuggestion.Status.NEEDS_EVIDENCE)
        // One second per 5 pp of contrast, so a wider gap moves further,
        // but never past [MAX_STEP_SECONDS].
        val step = (abs(value) / DIRECTION_THRESHOLD)
            .roundToInt()
            .coerceIn(1, MAX_STEP_SECONDS)
            .toLong()
        return when (contrast.direction) {
            Direction.LOWER -> {
                // The step is the evidence's estimate, so it is left alone;
                // the target is only clamped to the floor
                // ([SchedulerConfig.sanitized] rejects anything below
                // [MIN_EASY_SECONDS], and a 1 s cut leaves no measured second
                // below it that could ever grade Easy). Landing exactly on the
                // floor is a legal move, so only a clamp that leaves the cut
                // unchanged is "at the limit".
                val target = (fastSeconds - step).coerceAtLeast(MIN_EASY_SECONDS)
                if (target >= fastSeconds) keep(CutSuggestion.Status.AT_LIMIT)
                else CutSuggestion(
                    fastSeconds, slowSeconds, target, slowSeconds,
                    CutSuggestion.Status.STEP_DOWN
                )
            }

            Direction.RAISE -> {
                val target = fastSeconds + step
                // The cross-field rule requires fast < slow, so the Hard cut is
                // the hard wall; a proposal equal to it is not a proposal.
                if (target >= slowSeconds) keep(CutSuggestion.Status.AT_LIMIT)
                else CutSuggestion(
                    fastSeconds, slowSeconds, target, slowSeconds,
                    CutSuggestion.Status.STEP_UP
                )
            }

            Direction.NONE -> keep(CutSuggestion.Status.KEEP)
        }
    }

    /**
     * The first pair a learner can be offered: the cuts that would put about a
     * tenth of their correct, unguessed answers on each side, taken from the
     * dwell history itself. No labels and no outcomes are needed, so it works
     * while the learner grades on correctness only — which is exactly the state
     * in which no Easy or Hard label exists to calibrate.
     *
     * Only rows graded with the clock ignored are used ([Row.correctnessOnly]):
     * a tinted clock feeds the cuts back to the learner, so those dwells are
     * not a free measurement of their pace. Outcomes are not consulted, so this
     * says nothing about retention; it places the bands where the learner's own
     * pace already separates fast from slow.
     *
     * The percentiles are taken over answers, not over cards, so a few heavily
     * repeated questions weigh more than the gate's 30-card floor suggests:
     * 200 answers on 30 cards resolve the tenth percentile from roughly 30
     * distinct readings. Read the result as a pace quantile, not a precise cut
     * — [MIN_DWELL_CARDS] is the guard against a single card setting both ends.
     *
     * Timing convention: `elapsedSeconds == null` means "not measured" — the
     * caller had no trustworthy measurement for that row — and is excluded
     * everywhere. It is not an old-row marker; the column has existed for many
     * versions, so an old row can carry a value like any other. A stored 0 is
     * a measurement — every answer inside the first second is stored as 0 —
     * so it counts toward all three gates and enters the percentile as 1 s.
     * Dropping it would discard exactly the fast tail p10 is read from, bias
     * the Easy cut upward and keep a fast learner permanently short of
     * [MIN_DWELL_ROWS]. The grade those rows already have is untouched: 0 still
     * grades Good ([com.mcqapp.domain.Study.inferGrade]); only this estimate
     * reads it as one second.
     */
    fun suggestCutsFromDwell(
        reviews: List<Row>,
        now: Long,
        fastSeconds: Long,
        slowSeconds: Long
    ): CutSuggestion {
        val usable = reviews.filter {
            it.correctnessOnly &&
                it.elapsedSeconds != null &&
                it.wasCorrect == true &&
                it.wasGuess != true &&
                it.wasSkipped != true
        }
        val cards = usable.map { it.paperId to it.questionId }.toSet()
        val first = if (usable.isEmpty()) now else usable.minOf { it.reviewedAt }
        val days = ((now - first) / DAY_MS).toInt().coerceAtLeast(0)
        fun insufficient(gate: CutSuggestion.Gate) = CutSuggestion(
            currentFast = fastSeconds,
            currentSlow = slowSeconds,
            nextFast = fastSeconds,
            nextSlow = slowSeconds,
            status = CutSuggestion.Status.NEEDS_EVIDENCE,
            evidence = usable.size,
            blockingGate = gate,
            evidenceCards = cards.size,
            evidenceDays = days
        )
        // Checked one at a time so the "not yet" line can name the gate that
        // actually failed: with 250 answers on 12 cards, quoting the row count
        // printed "250 of 200" and hid the real requirement.
        if (usable.size < MIN_DWELL_ROWS) return insufficient(CutSuggestion.Gate.ROWS)
        if (cards.size < MIN_DWELL_CARDS) return insufficient(CutSuggestion.Gate.CARDS)
        if (now - first < MIN_DWELL_DAYS * DAY_MS) return insufficient(CutSuggestion.Gate.DAYS)

        // Sub-second answers are stored as 0 and are real observations of the
        // fast end, so they enter as one second rather than being dropped.
        val sorted = usable.map { it.elapsedSeconds!!.coerceAtLeast(1L) }.sorted()
        val fast = percentile(sorted, DWELL_EASY_PERCENT)
            .coerceAtLeast(maxOf(MIN_DWELL_FAST_SECONDS, MIN_EASY_SECONDS))
        val slow = percentile(sorted, DWELL_HARD_PERCENT)
            .coerceAtMost(MAX_DWELL_SLOW_SECONDS)
        // A tight pace would otherwise name two cuts a second apart, where a
        // one-second rounding error moves a third of the answers.
        val wideSlow = slow.coerceAtLeast(fast + MIN_DWELL_GAP_SECONDS)
            .coerceAtMost(MAX_DWELL_SLOW_SECONDS)
        val wideFast = fast.coerceAtMost(wideSlow - MIN_DWELL_GAP_SECONDS)
            .coerceAtLeast(maxOf(MIN_DWELL_FAST_SECONDS, MIN_EASY_SECONDS))
        return CutSuggestion(
            currentFast = fastSeconds,
            currentSlow = slowSeconds,
            nextFast = wideFast,
            nextSlow = wideSlow,
            status = CutSuggestion.Status.FROM_TIMES,
            evidence = usable.size
        )
    }

    /**
     * Linear-interpolated percentile over an ascending list, half-up rounded.
     * Integer arithmetic on purpose: `0.9 * 199` is a hair under 179.1 in
     * binary, which turns an exact 20 s into 19 s.
     */
    private fun percentile(sorted: List<Long>, percent: Int): Long {
        if (sorted.isEmpty()) return 0L
        val numerator = percent.toLong() * (sorted.size - 1)
        val low = (numerator / 100).toInt()
        val remainder = (numerator % 100).toInt()
        if (remainder == 0) return sorted[low]
        val high = (low + 1).coerceAtMost(sorted.lastIndex)
        return (sorted[low] * (100 - remainder) + sorted[high] * remainder + 50) / 100
    }

    /** Follow-up coverage among labels whose outcome is due, per grade. */
    data class FollowUp(
        val grade: GradeClass,
        val due: Int,
        val observed: Int
    ) {
        val rate: Double? get() = if (due == 0) null else observed.toDouble() / due
    }

    /** The first-exposure stratum; reported, never fitted (plan §2.2). */
    data class FirstExposure(
        val grade: GradeClass,
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
        val easyCards: Int,
        val goodPairs: Int,
        val goodCards: Int,
        val hardPairs: Int,
        val hardCards: Int,
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
     * current grading version (0 when no version exists), moved forward when a
     * refit ended the epoch.
     *
     * Only labels dated inside the epoch are pooled (plan §2.4): a review from
     * before the current grading version, or before the current weight set, was
     * graded and forecast under different rules, so it is not evidence about
     * the cuts in force now. Its row may still supply an outcome for a label
     * inside the epoch; the label side is what starts the epoch.
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

        val followDue = HashMap<GradeClass, Int>()
        val followObserved = HashMap<GradeClass, Int>()
        val firstExposure = ArrayList<FirstExposure>()

        val firstCounts = HashMap<GradeClass, IntArray>() // count, correct
        reviews.forEach { row ->
            if (epochStart > 0L && row.reviewedAt < epochStart) return@forEach
            if (row.stateBefore == CardStateKind.NEW && row.wasCorrect != null) {
                val grade = gradeOf(row.rating) ?: return@forEach
                val counts = firstCounts.getOrPut(grade) { IntArray(2) }
                counts[0]++
                if (row.wasCorrect) counts[1]++
            }
        }
        GradeClass.entries.forEach { grade ->
            val counts = firstCounts[grade] ?: IntArray(2)
            firstExposure += FirstExposure(grade, counts[0], counts[1])
        }

        byCard.values.forEach { rows ->
            val ordered = rows.sortedWith(compareBy({ it.reviewedAt }, { it.id }))
            for (index in ordered.indices) {
                val label = ordered[index]
                // Pre-epoch labels are dropped before any counter sees them:
                // follow-up and the exclusions describe this epoch's evidence.
                if (epochStart > 0L && label.reviewedAt < epochStart) continue
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

        val grades = GradeClass.entries.map { grade -> gradeStat(grade, pairs) }
        val easy = pairs.filter { it.grade == GradeClass.EASY }
        val good = pairs.filter { it.grade == GradeClass.GOOD }
        val hard = pairs.filter { it.grade == GradeClass.HARD }
        val contrast1 = contrast(easy, good)
        val contrast2 = contrast(hard, good)

        val followUp = GradeClass.entries.map { grade ->
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

    /** The automatable label grade; Again has none (it is never one of these cuts). */
    private fun labelGrade(label: Row): GradeClass? = gradeOf(label.rating)

    private fun gradeOf(rating: Int): GradeClass? =
        GradeClass.entries.firstOrNull { it.rating == rating }

    private fun gradeStat(grade: GradeClass, pairs: List<Sample>): GradeStat {
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
     * a card's reviews from counting as independent facts. The two classes are
     * drawn as ONE paired cluster sample: a single card-index draw is reused for
     * both, so a card that contributed residuals to Easy and to Good enters both
     * means together and their covariance is preserved (ST1).
     *
     * Upstream references: `scipy.stats.bootstrap`'s `paired=True` draws "an
     * array of indices and uses the same indices for all arrays" — see
     * https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.bootstrap.html
     * — and the cluster-bootstrap requirement to resample whole clusters while
     * preserving within-cluster correlation (Cameron, Gelbach & Miller,
     * https://www.nber.org/system/files/working_papers/t0344/t0344.pdf).
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
        // Paired cluster bootstrap: one card-index sample per replicate, used
        // for BOTH classes. A card shared by the two classes therefore always
        // contributes to both means of that replicate, keeping their covariance;
        // drawing the classes independently (the old code) silently models them
        // as unrelated and understates the interval.
        val keyedFirst = cardsByKey(first)
        val keyedGood = cardsByKey(good)
        val unionCards = (keyedFirst.keys + keyedGood.keys)
            .sortedWith(compareBy<CardKey>({ it.paperId }, { it.questionId }))
        val firstMean = mean(first)
        val goodMean = mean(good)
        val random = Random(seed)
        val deltas = DoubleArray(resamples)
        repeat(resamples) { i ->
            var aSum = 0.0
            var bSum = 0.0
            var aCount = 0
            var bCount = 0
            repeat(unionCards.size) {
                val card = unionCards[random.nextInt(unionCards.size)]
                keyedFirst[card]?.let { residuals ->
                    aSum += residuals.sum()
                    aCount += residuals.size
                }
                keyedGood[card]?.let { residuals ->
                    bSum += residuals.sum()
                    bCount += residuals.size
                }
            }
            // A draw with no card from a class cannot estimate that class's mean.
            // With >= MIN_CARDS_PER_CLASS cards per class this is vanishingly
            // rare; fall back to the observed mean rather than emit NaN, so the
            // seeded stream stays defined and reproducible.
            val a = if (aCount > 0) aSum / aCount else firstMean
            val b = if (bCount > 0) bSum / bCount else goodMean
            deltas[i] = a - b
        }
        deltas.sort()
        // BCa: bias correction from the bootstrap distribution and
        // acceleration from the jackknife over both classes' cards.
        val z0 = inverseNormal(
            (deltas.count { it < observed }.toDouble() / resamples)
                .coerceIn(0.0005, 0.9995)
        )
        val acceleration = acceleration(keyedFirst, keyedGood)
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

    /** Card identity, kept as a key so a shared card can be paired across classes. */
    private data class CardKey(val paperId: String, val questionId: String)

    /** Card key → that card's residuals, in a deterministic (paper, question) order. */
    private fun cardsByKey(pairs: List<Sample>): Map<CardKey, DoubleArray> =
        pairs.groupBy { CardKey(it.paperId, it.questionId) }
            .toSortedMap(compareBy<CardKey>({ it.paperId }, { it.questionId }))
            .mapValues { (_, rows) -> rows.map { it.residual }.toDoubleArray() }

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
        val aTerm = va / a.size
        val bTerm = vb / b.size
        val se = sqrt(aTerm + bTerm)
        if (se == 0.0) return null
        // Welch–Satterthwaite degrees of freedom, floored at 19 because this
        // tier guarantees >= DESCRIPTIVE_CARDS (20) cards per class. The old
        // fixed t = 1.70 under-covers at that floor: the exact two-sided 90%
        // critical value at df = 19 is ~1.729. Deriving it from df fixes the
        // floor without widening tiers that genuinely have more information.
        val df = if (aTerm + bTerm > 0.0) {
            ((aTerm + bTerm) * (aTerm + bTerm)) /
                (aTerm * aTerm / (a.size - 1) + bTerm * bTerm / (b.size - 1))
        } else {
            MIN_WELCH_DF
        }
        val t = welchTCritical(df)
        return (observed - t * se) to (observed + t * se)
    }

    /** The smallest Welch df this tier can resolve. */
    private const val MIN_WELCH_DF = 19.0

    /**
     * Two-sided 90% Student-t critical value for [dfRaw] degrees of freedom,
     * via the Cornish–Fisher expansion from the normal 95th percentile. Accurate
     * to <1e-4 for df >= 19, which is this tier's whole domain. df is floored at
     * 19, where the expansion gives ~1.7291 — the exact two-sided 90% value —
     * so the tier's floor is no longer the old, under-covering 1.70.
     */
    internal fun welchTCritical(dfRaw: Double): Double {
        val df = dfRaw.coerceAtLeast(MIN_WELCH_DF)
        val z = inverseNormal(0.95) // ≈ 1.6448536269514722
        val z2 = z * z
        val z3 = z2 * z
        val z5 = z3 * z2
        val z7 = z5 * z2
        val c1 = (z3 + z) / (4.0 * df)
        val c2 = (5.0 * z5 + 16.0 * z3 + 3.0 * z) / (96.0 * df * df)
        val c3 = (3.0 * z7 + 19.0 * z5 + 17.0 * z3 - 15.0 * z) /
            (384.0 * df * df * df)
        return z + c1 + c2 + c3
    }

    /**
     * Jackknife acceleration over the UNION of cards. Deleting a shared card
     * removes it from BOTH classes (the old code deleted it from one only, so
     * the acceleration was computed under a different scheme than the paired
     * bootstrap). This keeps the jackknife consistent with the resampling above.
     */
    private fun acceleration(
        first: Map<CardKey, DoubleArray>,
        good: Map<CardKey, DoubleArray>
    ): Double {
        val union = (first.keys + good.keys)
            .sortedWith(compareBy<CardKey>({ it.paperId }, { it.questionId }))
        if (union.size < 2) return 0.0
        fun deltaWithout(skip: CardKey): Double {
            val a = first.filterKeys { it != skip }.values.flatMap { it.toList() }
            val b = good.filterKeys { it != skip }.values.flatMap { it.toList() }
            if (a.isEmpty() || b.isEmpty()) return 0.0
            return a.average() - b.average()
        }
        val jack = union.map { deltaWithout(it) }
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
                    first = rows.filter { it.grade == GradeClass.EASY },
                    good = rows.filter { it.grade == GradeClass.GOOD },
                    seed = SEED + index
                )
            } else {
                null
            }
            val easyRows = rows.filter { it.grade == GradeClass.EASY }
            val goodRows = rows.filter { it.grade == GradeClass.GOOD }
            val hardRows = rows.filter { it.grade == GradeClass.HARD }
            Block(
                index = index,
                startAt = start,
                endAt = start + BLOCK_MS,
                pairs = rows.size,
                easyPairs = easyRows.size,
                easyCards = easyRows.map { it.paperId to it.questionId }.toSet().size,
                goodPairs = goodRows.size,
                goodCards = goodRows.map { it.paperId to it.questionId }.toSet().size,
                hardPairs = hardRows.size,
                hardCards = hardRows.map { it.paperId to it.questionId }.toSet().size,
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
