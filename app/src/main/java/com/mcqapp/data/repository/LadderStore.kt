package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.LadderBlockEntity
import com.mcqapp.data.local.LadderVersionEntity
import com.mcqapp.domain.SchedulerConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** Records one closed evidence block (including one that decided nothing). */
    suspend fun recordBlock(block: LadderBlockEntity): Long =
        db.ladderDao().insertBlock(block)

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
