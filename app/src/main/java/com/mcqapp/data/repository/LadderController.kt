package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.Calibration
import com.mcqapp.domain.stats.LadderPolicy
import com.mcqapp.util.Logger

/**
 * Phase 5's automatic ladder controller. It runs only at the end of a sitting,
 * never while a card is in flight, and only when the learner has switched
 * automatic grading on (default off).
 *
 * Order matters: the safety valve sees the current version's two post-change
 * windows first and may walk the change back. Only if it stays silent does the
 * ordinary phase-4 policy get a chance to propose another step.
 */
internal class LadderController(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val ladderStore: LadderStore,
    private val statsStore: StatsStore
) {

    sealed interface Result {
        /** The setting is off; nothing was read or written. */
        data object Disabled : Result

        /** The valve or the policy declined; [status] says which guard. */
        data class NoChange(val status: LadderPolicy.Status) : Result

        /** An automatic proposal was applied. */
        data class Applied(val version: Int, val proposal: LadderPolicy.Proposal) : Result

        /** The version chain was walked back; null when there was no previous version. */
        data class RolledBack(val version: Int?) : Result
    }

    suspend fun run(now: Long = System.currentTimeMillis()): Result {
        if (!settings.automaticLadderAdaptationNow()) return Result.Disabled

        // Reading the config seeds a version on an install that has never had
        // one; a version-0 review cannot anchor an epoch.
        ladderStore.currentVersion()
        val config = settings.schedulerConfigNow()
        val current = config.ladderVersion
            .takeIf { it > 0 }
            ?.let { db.ladderDao().version(it.toLong()) }
            ?: return Result.NoChange(LadderPolicy.Status.NOT_READY)
        val previous = current.previousId?.let { db.ladderDao().version(it) }

        val report = statsStore.loadCalibrationReport(
            scopePaperId = null,
            now = now,
            config = config
        )

        // The valve only guards changes this controller or an accepted
        // proposal made. Manual edits are the learner's own intent and are not
        // auto-reverted.
        if (previous != null &&
            current.reason in GUARDED_REASONS &&
            LadderPolicy.safetyValve(
                blocks = report.blocks,
                previousEasySeconds = previous.easySeconds,
                currentEasySeconds = current.easySeconds
            )
        ) {
            val version = ladderStore.rollback(now)
            Logger.i("LADDER", "safety valve rolled back version ${current.id} -> ${version ?: "none"}")
            return Result.RolledBack(version)
        }

        val decided = db.ladderDao().allBlocks().map { it.startAt }.toSet()
        val evaluation = LadderPolicy.evaluate(report, config, decided, now)
        val proposal = evaluation.proposal
        if (proposal == null) {
            recordClosedBlocks(evaluation, report, config, now)
            return Result.NoChange(evaluation.status)
        }

        val version = ladderStore.applyProposal(
            proposal = proposal,
            reason = LadderReason.AUTOMATIC,
            now = now
        )
        Logger.i(
            "LADDER",
            "automatic Easy cut ${proposal.currentEasySeconds}s -> ${proposal.easySeconds}s " +
                "(δ ${proposal.delta1}, version $version)"
        )
        return Result.Applied(version, proposal)
    }

    /**
     * Plan §2.4: every closed block leaves a row, including one that decided
     * nothing. Without this, a controller that scores a window and declines is
     * indistinguishable from one that stopped running, and the safety valve's
     * history has holes.
     *
     * The latest window carries the outcome the policy reached, *unless* it is
     * merely not ready yet: readiness is measured over the whole epoch and can
     * still be met before the next window closes, and a row here would read as
     * `ALREADY_DECIDED` and suppress that chance. Such a window gets its
     * `insufficient-evidence` row on the first run after a newer window closes,
     * which is when it can no longer be acted on.
     *
     * `recordBlock` skips any window that already has a row, so applying and
     * dismissing keep their own outcomes.
     */
    private suspend fun recordClosedBlocks(
        evaluation: LadderPolicy.Evaluation,
        report: Calibration.Report,
        config: SchedulerConfig,
        now: Long
    ) {
        if (evaluation.status == LadderPolicy.Status.BLOCK_OPEN) return
        val followUp = report.followUp.associateBy { it.grade }
        val followUpEasy = followUp[Calibration.LadderGrade.EASY]?.rate
        val followUpGood = followUp[Calibration.LadderGrade.GOOD]?.rate
        val latestStart = evaluation.blockStartAt

        report.blocks
            .filter { it.closed && it.startAt != latestStart }
            .forEach { block ->
                ladderStore.recordBlock(
                    block = block,
                    ladderVersion = config.ladderVersion,
                    fsrsFitVersion = config.fsrsFitVersion,
                    outcome = "insufficient-evidence",
                    followUpEasy = followUpEasy,
                    followUpGood = followUpGood,
                    decidedAt = now
                )
            }

        val startAt = latestStart ?: return
        if (evaluation.status == LadderPolicy.Status.ALREADY_DECIDED ||
            evaluation.status == LadderPolicy.Status.NOT_READY
        ) {
            return
        }
        val block = report.blocks.firstOrNull { it.startAt == startAt }
            ?: emptyBlock(startAt, report.epochStart)
        val outcome = when (evaluation.status) {
            LadderPolicy.Status.REFIT -> "frozen-refit"
            LadderPolicy.Status.BLOCK_EMPTY -> "insufficient-evidence"
            else -> "no-change"
        }
        ladderStore.recordBlock(
            block = block,
            ladderVersion = config.ladderVersion,
            fsrsFitVersion = config.fsrsFitVersion,
            outcome = outcome,
            followUpEasy = followUpEasy,
            followUpGood = followUpGood,
            decidedAt = now
        )
    }

    /** A closed window that held no usable pair still gets an audit row. */
    private fun emptyBlock(startAt: Long, epochStart: Long) = Calibration.Block(
        index = ((startAt - epochStart) / BLOCK_MS).toInt(),
        startAt = startAt,
        endAt = startAt + BLOCK_MS,
        pairs = 0,
        easyPairs = 0,
        easyCards = 0,
        goodPairs = 0,
        goodCards = 0,
        hardPairs = 0,
        hardCards = 0,
        closed = true
    )

    private companion object {
        const val BLOCK_MS = Calibration.BLOCK_DAYS * 86_400_000L

        val GUARDED_REASONS = setOf(
            LadderReason.PROPOSAL.stored,
            LadderReason.AUTOMATIC.stored
        )
    }
}
