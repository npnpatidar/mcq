package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
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
            ?: return Result.NoChange(evaluation.status)

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

    private companion object {
        val GUARDED_REASONS = setOf(
            LadderReason.PROPOSAL.stored,
            LadderReason.AUTOMATIC.stored
        )
    }
}
