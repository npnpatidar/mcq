package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.LadderBlockEntity
import com.mcqapp.data.local.LadderVersionEntity
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.Calibration
import com.mcqapp.domain.stats.LadderPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The cuts or version moved after a proposal was shown; nothing is applied. */
class LadderProposalStaleException(message: String) : IllegalStateException(message)

/** Why a ladder version exists, as stored in `ladder_versions.reason`. */
enum class LadderReason(val stored: String) {
    /** Seeded from the cuts already on disk; no change was made. */
    PRIOR("prior"),

    /** A learner edited a cut in Settings. */
    MANUAL("manual"),

    /** The learner accepted a proposal. */
    PROPOSAL("proposal"),

    /** The controller changed the cut by itself. */
    AUTOMATIC("automatic"),

    /** The controller walked the cut back. */
    ROLLBACK("rollback")
}

/**
 * The only writer of the grading ladder's cut points (v26).
 *
 * The cuts live in DataStore because the scheduler reads them on every pull;
 * this store is the one path that changes them, so every change can also own a
 * `ladder_versions` row. Two production writers bypassed that before: the
 * Settings rows (`SettingsStore.setSchedulerConfig`) and
 * `resetSchedulerConfig`. Both now route through here, because without it
 * `review_log.ladderVersion` is untrustworthy from the very first manual edit.
 *
 * The write order is deliberate: the cuts and the version number land in one
 * DataStore edit, then the history row is inserted. A crash between the two
 * leaves a version number whose row is missing — repaired here on the next
 * read from the preferences — never a cut value without a version.
 */
internal class LadderStore(
    private val db: AppDatabase,
    private val settings: SettingsStore
) {

    private val mutex = Mutex()

    /**
     * The current ladder version, seeding a `prior` row on a store that has
     * never been versioned. Seeding is guarded by the presence of the version
     * key, not by an empty table, so two concurrent first reads cannot both
     * insert a seed.
     */
    suspend fun currentVersion(): Int = mutex.withLock { ensureVersionLocked() }

    /** The stored version, without writing anything (0 when none exists). */
    suspend fun storedVersion(): Int = settings.ladderVersionOrNull() ?: 0

    /**
     * Changes one or both cuts and records why. [previousId] defaults to the
     * version being replaced; [blockId] is the evidence block that triggered a
     * proposal or automatic change and stays null for seeds and manual edits.
     */
    suspend fun setCuts(
        easySeconds: Long,
        slowSeconds: Long,
        reason: LadderReason,
        blockId: Long? = null,
        policyVersion: Int = POLICY_VERSION
    ): Int = mutex.withLock {
        val previous = ensureVersionLocked()
        val id = nextVersionId()
        // One atomic edit: the value and its version cannot disagree.
        settings.writeCuts(easySeconds, slowSeconds, id)
        db.ladderDao().insertVersion(
            LadderVersionEntity(
                id = id.toLong(),
                easySeconds = easySeconds,
                slowSeconds = slowSeconds,
                effectiveFrom = System.currentTimeMillis(),
                reason = reason.stored,
                previousId = previous.toLong(),
                blockId = blockId,
                policyVersion = policyVersion
            )
        )
        id
    }

    /**
     * The whole-config write path: records a MANUAL version only when the cuts
     * actually changed. Other fields are the scheduler store's business; this
     * call exists so a Settings save cannot move a cut without a version row.
     */
    suspend fun recordConfigCuts(config: SchedulerConfig): Int = mutex.withLock {
        val stored = settings.schedulerConfigNow()
        if (stored.fastSeconds == config.fastSeconds &&
            stored.slowSeconds == config.slowSeconds
        ) {
            return@withLock ensureVersionLocked()
        }
        val previous = ensureVersionLocked()
        val id = nextVersionId()
        settings.writeCuts(config.fastSeconds, config.slowSeconds, id)
        db.ladderDao().insertVersion(
            LadderVersionEntity(
                id = id.toLong(),
                easySeconds = config.fastSeconds,
                slowSeconds = config.slowSeconds,
                effectiveFrom = System.currentTimeMillis(),
                reason = LadderReason.MANUAL.stored,
                previousId = previous.toLong(),
                blockId = null,
                policyVersion = POLICY_VERSION
            )
        )
        id
    }

    /**
     * A restore wipes the preferences and replays the backup's. The restored
     * cuts may have been graded under a version this install never saw, and the
     * restored blocks' statistics were computed against rows that may no longer
     * exist, so a restore always seeds a fresh `prior` version continuing from
     * the highest restored id.
     */
    suspend fun seedAfterRestore(): Int = mutex.withLock {
        val config = settings.schedulerConfigNow()
        val id = nextVersionId()
        settings.writeCuts(config.fastSeconds, config.slowSeconds, id)
        db.ladderDao().insertVersion(
            LadderVersionEntity(
                id = id.toLong(),
                easySeconds = config.fastSeconds,
                slowSeconds = config.slowSeconds,
                effectiveFrom = System.currentTimeMillis(),
                reason = LadderReason.PRIOR.stored,
                previousId = (db.ladderDao().maxVersionId())?.takeIf { it > 0 },
                blockId = null,
                policyVersion = POLICY_VERSION
            )
        )
        id
    }

    /**
     * Records one closed evidence block that decided nothing, so the audit
     * trail shows a window that was scored and declined instead of looking like
     * a controller that never ran (plan §2.4). Idempotent: a window that already
     * has a row (a proposal applied or dismissed) is left alone.
     *
     * Returns the new row's id, or null when the window was already recorded.
     */
    suspend fun recordBlock(
        block: Calibration.Block,
        ladderVersion: Int,
        fsrsFitVersion: Int,
        outcome: String,
        followUpEasy: Double?,
        followUpGood: Double?,
        decidedAt: Long
    ): Long? = mutex.withLock {
        if (db.ladderDao().blockAtStart(block.startAt) != null) return@withLock null
        db.ladderDao().insertBlock(
            LadderBlockEntity(
                startAt = block.startAt,
                endAt = block.endAt,
                ladderVersion = ladderVersion,
                fsrsFitVersion = fsrsFitVersion,
                policyVersion = POLICY_VERSION,
                easyPairs = block.easyPairs,
                easyCards = block.easyCards,
                goodPairs = block.goodPairs,
                goodCards = block.goodCards,
                hardPairs = block.hardPairs,
                hardCards = block.hardCards,
                delta1 = block.delta1,
                delta1Low = block.delta1Low,
                delta1High = block.delta1High,
                delta2 = null,
                delta2Low = null,
                delta2High = null,
                followUpEasy = followUpEasy,
                followUpGood = followUpGood,
                outcome = outcome,
                decidedAt = decidedAt
            )
        )
    }

    /**
     * Applies the learner's accepted proposal: the evidence block lands first,
     * then the cut and its `proposal` version in one DataStore edit. Rejects a
     * proposal whose cuts, version or block no longer match what was shown.
     */
    suspend fun applyProposal(
        proposal: LadderPolicy.Proposal,
        reason: LadderReason = LadderReason.PROPOSAL,
        now: Long = System.currentTimeMillis()
    ): Int =
        mutex.withLock {
            requireCurrentProposalLocked(proposal)
            val blockId = db.ladderDao().insertBlock(proposal.toBlock(
                outcome = proposal.outcome,
                decidedAt = now
            ))
            val id = nextVersionId()
            settings.writeCuts(proposal.easySeconds, proposal.currentSlowSeconds, id)
            db.ladderDao().insertVersion(
                LadderVersionEntity(
                    id = id.toLong(),
                    easySeconds = proposal.easySeconds,
                    slowSeconds = proposal.currentSlowSeconds,
                    effectiveFrom = now,
                    reason = reason.stored,
                    previousId = proposal.ladderVersion.toLong(),
                    blockId = blockId,
                    policyVersion = POLICY_VERSION
                )
            )
            id
        }

    /**
     * A dismissed proposal is still evidence: the block is written as
     * `no-change`, so the same window cannot propose again and the next
     * proposal waits for a new closed block.
     */
    suspend fun dismissProposal(proposal: LadderPolicy.Proposal, now: Long = System.currentTimeMillis()): Long =
        mutex.withLock {
            requireCurrentProposalLocked(proposal)
            db.ladderDao().insertBlock(proposal.toBlock(outcome = "no-change", decidedAt = now))
        }

    /**
     * Walks one step back along the version chain. The new `rollback` row's
     * [LadderVersionEntity.previousId] is the *restored* version's previous id,
     * not the row being undone, so a second reset continues back rather than
     * stepping forward onto the change it just removed.
     */
    suspend fun rollback(now: Long = System.currentTimeMillis()): Int? = mutex.withLock {
        ensureVersionLocked()
        val currentId = settings.ladderVersionOrNull() ?: return@withLock null
        val current = db.ladderDao().version(currentId.toLong()) ?: return@withLock null
        val targetId = current.previousId ?: return@withLock null
        val target = db.ladderDao().version(targetId) ?: return@withLock null
        val id = nextVersionId()
        settings.writeCuts(target.easySeconds, target.slowSeconds, id)
        db.ladderDao().insertVersion(
            LadderVersionEntity(
                id = id.toLong(),
                easySeconds = target.easySeconds,
                slowSeconds = target.slowSeconds,
                effectiveFrom = now,
                reason = LadderReason.ROLLBACK.stored,
                previousId = target.previousId,
                blockId = null,
                policyVersion = POLICY_VERSION
            )
        )
        id
    }

    /**
     * Must run under [mutex]. A proposal is only valid for the exact cuts,
     * version and still-undecided block the report showed.
     */
    private suspend fun requireCurrentProposalLocked(proposal: LadderPolicy.Proposal) {
        val config = settings.schedulerConfigNow()
        if (config.ladderVersion != proposal.ladderVersion ||
            config.fastSeconds != proposal.currentEasySeconds ||
            config.slowSeconds != proposal.currentSlowSeconds
        ) {
            throw LadderProposalStaleException("The grading ladder changed after this proposal was shown")
        }
        if (db.ladderDao().blockAtStart(proposal.block.startAt) != null) {
            throw LadderProposalStaleException("This evidence block has already been decided")
        }
        if (proposal.easySeconds !in LadderPolicy.MIN_EASY_SECONDS..LadderPolicy.MAX_EASY_SECONDS ||
            proposal.easySeconds > config.slowSeconds - LadderPolicy.MIN_GAP_TO_HARD ||
            proposal.easySeconds == config.fastSeconds
        ) {
            throw LadderProposalStaleException("The proposed Easy cut is outside the allowed range")
        }
    }

    private fun LadderPolicy.Proposal.toBlock(
        outcome: String,
        decidedAt: Long
    ) = LadderBlockEntity(
        startAt = block.startAt,
        endAt = block.endAt,
        ladderVersion = ladderVersion,
        fsrsFitVersion = fsrsFitVersion,
        policyVersion = LadderPolicy.POLICY_VERSION,
        easyPairs = easyPairs,
        easyCards = easyCards,
        goodPairs = goodPairs,
        goodCards = goodCards,
        hardPairs = hardPairs,
        hardCards = hardCards,
        delta1 = delta1,
        delta1Low = delta1Low,
        delta1High = delta1High,
        delta2 = null,
        delta2Low = null,
        delta2High = null,
        followUpEasy = followUpEasy,
        followUpGood = followUpGood,
        outcome = outcome,
        decidedAt = decidedAt
    )

    /**
     * Must run under [mutex]. Returns the current version, writing the missing
     * history row for an existing key first — that is the crash repair, and it
     * also covers a restore that carried a version number from elsewhere.
     */
    private suspend fun ensureVersionLocked(): Int {
        val stored = settings.ladderVersionOrNull()
        if (stored != null && stored > 0) {
            if (db.ladderDao().version(stored.toLong()) == null) {
                val config = settings.schedulerConfigNow()
                db.ladderDao().insertVersion(
                    LadderVersionEntity(
                        id = stored.toLong(),
                        easySeconds = config.fastSeconds,
                        slowSeconds = config.slowSeconds,
                        effectiveFrom = System.currentTimeMillis(),
                        reason = LadderReason.PRIOR.stored,
                        previousId = db.ladderDao().maxVersionId().takeIf { it != stored.toLong() },
                        blockId = null,
                        policyVersion = POLICY_VERSION
                    )
                )
            }
            return stored
        }
        val config = settings.schedulerConfigNow()
        val id = nextVersionId()
        settings.writeCuts(config.fastSeconds, config.slowSeconds, id)
        db.ladderDao().insertVersion(
            LadderVersionEntity(
                id = id.toLong(),
                easySeconds = config.fastSeconds,
                slowSeconds = config.slowSeconds,
                effectiveFrom = System.currentTimeMillis(),
                reason = LadderReason.PRIOR.stored,
                previousId = null,
                blockId = null,
                policyVersion = POLICY_VERSION
            )
        )
        return id
    }

    /** The next free id, at least 1. Must run under [mutex]. */
    private suspend fun nextVersionId(): Int =
        ((db.ladderDao().maxVersionId() ?: 0L) + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    companion object {
        /** Which build of the decision rule wrote a row. */
        const val POLICY_VERSION = 1
    }
}
