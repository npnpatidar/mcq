package com.mcqapp.domain.stats

import com.mcqapp.domain.SchedulerConfig
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Decides whether the dwell ladder may act on an epoch's evidence, and if so
 * how far to move the Easy cut (plan phase 4).
 *
 * Two questions stay separate:
 *
 * - **May the app tune at all?** [Calibration.Readiness] answers it: volume,
 *   per-class card count, interval precision, calendar floor and no refit
 *   inside the epoch. Volume alone is not enough — the 300-pair floor exists
 *   so a technicality cannot become a decision on 40 observations, while the
 *   5 pp half-width is the gate that actually says the estimate can support a
 *   one-step change.
 * - **Should it move the cut now?** Every guard in [evaluate] must hold in
 *   addition: a 5 pp effect, a card-clustered interval that excludes zero, the
 *   due-ness follow-up gate, one step per closed block, and the cut bounds.
 *
 * Nothing here writes. The caller records the returned proposal's block and
 * only then asks the learner to apply it, so a dismissed proposal is still
 * evidence that the controller ran.
 */
object LadderPolicy {

    /** Which build of this rule produced a block or version row. */
    const val POLICY_VERSION = 1

    const val MIN_EFFECT = Calibration.DIRECTION_THRESHOLD
    const val STEP_FRACTION = 0.10
    const val DEFAULT_EASY_SECONDS = 8L
    const val NEAR_DEFAULT_SECONDS = 3L
    const val NEAR_DEFAULT_MAX_STEP = 2L
    const val MIN_EASY_SECONDS = 3L
    const val MAX_EASY_SECONDS = 60L
    const val MIN_GAP_TO_HARD = 5L

    private const val DAY_MS = 86_400_000L
    private const val BLOCK_MS = Calibration.BLOCK_DAYS * DAY_MS

    /** Why the controller is not proposing a step; each maps to one UI line. */
    enum class Status {
        /** The latest 28-day block, plus its outcome horizon, is still open. */
        BLOCK_OPEN,

        /** The latest closed block has no usable pair; an older block must not stand in. */
        BLOCK_EMPTY,

        /** A refit falls inside this epoch; its forecast is not a stable yardstick. */
        REFIT,

        /** Volume, cards, precision or calendar floor not met. */
        NOT_READY,

        /** This block already produced a proposal or a no-change decision. */
        ALREADY_DECIDED,

        /** Ready, but the effect is small or its interval spans zero. */
        NO_EFFECT,

        /** The proposed step would leave the allowed Easy range. */
        BOUNDS,

        /** A single step is worth offering. */
        PROPOSE
    }

    /**
     * One actionable proposal. Everything needed to write the evidence block
     * travels with it, so applying does not recompute a statistic the learner
     * never saw.
     */
    data class Proposal(
        val easySeconds: Long,
        val currentEasySeconds: Long,
        val currentSlowSeconds: Long,
        val stepSeconds: Long,
        val direction: Calibration.Direction,
        val ladderVersion: Int,
        val fsrsFitVersion: Int,
        val block: Calibration.Block,
        val delta1: Double,
        val delta1Low: Double,
        val delta1High: Double,
        val easyPairs: Int,
        val easyCards: Int,
        val goodPairs: Int,
        val goodCards: Int,
        val hardPairs: Int,
        val hardCards: Int,
        val followUpEasy: Double?,
        val followUpGood: Double?,
        /** `raise-c1` or `lower-c1`, as stored on the block row. */
        val outcome: String
    )

    data class Evaluation(
        val status: Status,
        val proposal: Proposal? = null,
        /**
         * The latest window that has closed, in this report's epoch
         * coordinates, even when the answer is not a proposal. The caller
         * records this window so a declined block is still evidence. Null
         * before any window has closed.
         */
        val blockStartAt: Long? = null
    )

    /**
     * [decidedBlockStarts] are the `startAt` values already present in
     * `ladder_blocks`. Only the latest closed block can be acted on, so an
     * already-decided one suppresses a second step until a newer block closes.
     */
    fun evaluate(
        report: Calibration.Report,
        config: SchedulerConfig,
        decidedBlockStarts: Set<Long>,
        now: Long
    ): Evaluation {
        // A report carries only windows that contain a pair. Compute the latest
        // calendar window that has closed and refuse to let an older, denser
        // one stand in for an empty newer one.
        if (report.epochStart <= 0L || config.ladderVersion <= 0) {
            return Evaluation(Status.NOT_READY)
        }
        val latestIndex = ((now - report.epochStart - Calibration.OUTCOME_HORIZON_DAYS * DAY_MS) / BLOCK_MS).toInt() - 1
        if (latestIndex < 0) return Evaluation(Status.BLOCK_OPEN)
        val latestStart = report.epochStart + latestIndex * BLOCK_MS
        val block = report.blocks.firstOrNull { it.startAt == latestStart }
            ?: return Evaluation(Status.BLOCK_EMPTY, blockStartAt = latestStart)
        if (!block.closed) return Evaluation(Status.BLOCK_OPEN, blockStartAt = latestStart)
        if (block.startAt in decidedBlockStarts) {
            return Evaluation(Status.ALREADY_DECIDED, blockStartAt = latestStart)
        }
        if (report.readiness.refitInEpoch) {
            return Evaluation(Status.REFIT, blockStartAt = latestStart)
        }
        if (!report.readiness.ready) {
            return Evaluation(Status.NOT_READY, blockStartAt = latestStart)
        }
        if (!report.followUpGatePassed) {
            return Evaluation(Status.NOT_READY, blockStartAt = latestStart)
        }

        val contrast = report.contrast1
        val value = contrast.value
            ?: return Evaluation(Status.NO_EFFECT, blockStartAt = latestStart)
        val low = contrast.low
            ?: return Evaluation(Status.NO_EFFECT, blockStartAt = latestStart)
        val high = contrast.high
            ?: return Evaluation(Status.NO_EFFECT, blockStartAt = latestStart)
        // The interval has to exclude zero, not merely miss it after rounding.
        if (low <= 0.0 && high >= 0.0) {
            return Evaluation(Status.NO_EFFECT, blockStartAt = latestStart)
        }
        val minimumDetectable = report.readiness.halfWidth
            ?: return Evaluation(Status.NO_EFFECT, blockStartAt = latestStart)
        if (abs(value) < MIN_EFFECT || abs(value) < minimumDetectable) {
            return Evaluation(Status.NO_EFFECT, blockStartAt = latestStart)
        }
        val direction = when {
            value <= -MIN_EFFECT -> Calibration.Direction.LOWER
            value >= MIN_EFFECT -> Calibration.Direction.RAISE
            else -> return Evaluation(Status.NO_EFFECT, blockStartAt = latestStart)
        }

        val current = config.fastSeconds
        val step = stepSeconds(current)
        val proposed = when (direction) {
            Calibration.Direction.LOWER -> current - step
            Calibration.Direction.RAISE -> current + step
            Calibration.Direction.NONE -> return Evaluation(Status.NO_EFFECT)
        }
        val sanitized = config.copy(fastSeconds = proposed).sanitized()
        if (sanitized.fastSeconds != proposed ||
            proposed !in MIN_EASY_SECONDS..MAX_EASY_SECONDS ||
            proposed > config.slowSeconds - MIN_GAP_TO_HARD
        ) {
            return Evaluation(Status.BOUNDS, blockStartAt = latestStart)
        }

        fun grade(which: Calibration.LadderGrade): Calibration.GradeStat =
            report.grades.first { it.grade == which }
        val easy = grade(Calibration.LadderGrade.EASY)
        val good = grade(Calibration.LadderGrade.GOOD)
        val hard = grade(Calibration.LadderGrade.HARD)
        val followUp = report.followUp.associateBy { it.grade }

        return Evaluation(
            status = Status.PROPOSE,
            blockStartAt = latestStart,
            proposal = Proposal(
                easySeconds = proposed,
                currentEasySeconds = current,
                currentSlowSeconds = config.slowSeconds,
                stepSeconds = step,
                direction = direction,
                ladderVersion = config.ladderVersion,
                fsrsFitVersion = config.fsrsFitVersion,
                block = block,
                delta1 = value,
                delta1Low = low,
                delta1High = high,
                easyPairs = easy.pairs,
                easyCards = easy.cards,
                goodPairs = good.pairs,
                goodCards = good.cards,
                hardPairs = hard.pairs,
                hardCards = hard.cards,
                followUpEasy = followUp[Calibration.LadderGrade.EASY]?.rate,
                followUpGood = followUp[Calibration.LadderGrade.GOOD]?.rate,
                outcome = if (direction == Calibration.Direction.LOWER) "lower-c1" else "raise-c1"
            )
        )
    }

    /**
     * Plan §4.4's safety valve, run before an automatic step. A change is
     * walked back only when **both** of the last two closed windows show δ1
     * beyond their own minimum detectable effect in the direction the change
     * was meant to fix. One noisy block is not enough, and "the next block did
     * not improve" would be regression to the mean.
     */
    fun safetyValve(
        blocks: List<Calibration.Block>,
        previousEasySeconds: Long,
        currentEasySeconds: Long
    ): Boolean {
        if (previousEasySeconds == currentEasySeconds) return false
        val lower = currentEasySeconds < previousEasySeconds
        val closed = blocks.filter { it.closed }.takeLast(2)
        if (closed.size < 2) return false
        return closed.all { block ->
            val delta = block.delta1 ?: return@all false
            val mde = block.minimumDetectableEffect ?: return@all false
            if (lower) delta <= -mde else delta >= mde
        }
    }

    /**
     * One step: ten percent of the current cut, at least one second, and no
     * more than two seconds while the cut is near its default. The measured
     * band mass (3.15% of reviews within ±1 s) is why a bigger step would buy
     * a larger unmeasured change rather than better evidence.
     */
    fun stepSeconds(currentEasySeconds: Long): Long {
        val raw = (currentEasySeconds * STEP_FRACTION).roundToLong().coerceAtLeast(1L)
        val nearDefault = abs(currentEasySeconds - DEFAULT_EASY_SECONDS) <= NEAR_DEFAULT_SECONDS
        return if (nearDefault) raw.coerceAtMost(NEAR_DEFAULT_MAX_STEP) else raw
    }
}
