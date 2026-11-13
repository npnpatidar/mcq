package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.GradingVersionEntity
import com.mcqapp.domain.SchedulerConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a grading version exists, as stored in `grading_versions.reason`. */
enum class GradingReason(val stored: String) {
    /** Seeded from the cuts already on disk; no change was made. */
    PRIOR("prior"),

    /** A learner edited a cut in Settings. */
    MANUAL("manual"),

    /** A learner switched the rule between timed and correctness-only. */
    RULE("rule")
}

/**
 * The only writer of the grade-inference cut points (v27).
 *
 * The cuts live in DataStore because the scheduler reads them on every pull;
 * this store is the one path that changes them, so every change also owns a
 * `grading_versions` row and every review can name the pair that graded it.
 * Nothing here adapts anything: a row is written when the learner edits a cut
 * and when a restore seeds the pair it imported, and never by the app itself.
 *
 * The write order is deliberate: the cuts and the version number land in one
 * DataStore edit, then the history row is inserted. A crash between the two
 * leaves a version number whose row is missing — repaired here on the next
 * read from the preferences — never a cut value without a version.
 */
internal class GradingRulesStore(
    private val db: AppDatabase,
    private val settings: SettingsStore
) {

    private val mutex = Mutex()

    /**
     * The current grading version, seeding a `prior` row on a store that has
     * never been versioned. Seeding is guarded by the presence of the version
     * key, not by an empty table, so two concurrent first reads cannot both
     * insert a seed.
     */
    suspend fun currentVersion(): Int = mutex.withLock { ensureVersionLocked() }

    /**
     * The whole-config write path: records a MANUAL version when a cut changed,
     * and a RULE version when the timing rule itself changed (the correctness
     * switch), so a Settings save of an unrelated knob does not manufacture a
     * version and two rules never share one.
     */
    suspend fun recordConfigCuts(config: SchedulerConfig): Int = mutex.withLock {
        val stored = settings.schedulerConfigNow()
        val cutsChanged = stored.fastSeconds != config.fastSeconds ||
            stored.slowSeconds != config.slowSeconds
        val ruleChanged = stored.correctnessOnlyGrading != config.correctnessOnlyGrading
        if (!cutsChanged && !ruleChanged) {
            return@withLock ensureVersionLocked()
        }
        val id = nextVersionId()
        settings.writeCuts(
            config.fastSeconds,
            config.slowSeconds,
            id,
            config.correctnessOnlyGrading
        )
        insert(
            id,
            config.fastSeconds,
            config.slowSeconds,
            if (ruleChanged && !cutsChanged) GradingReason.RULE else GradingReason.MANUAL,
            config.correctnessOnlyGrading
        )
        id
    }

    /**
     * A restore wipes the preferences and replays the backup's, including a
     * grading version id this install may never have seen. Seeding a fresh
     * `prior` row continuing from the highest restored id keeps the review log
     * resolvable: every row's version still points at a pair.
     */
    suspend fun seedAfterRestore(): Int = mutex.withLock {
        val config = settings.schedulerConfigNow()
        val id = nextVersionId()
        settings.writeCuts(
            config.fastSeconds,
            config.slowSeconds,
            id,
            config.correctnessOnlyGrading
        )
        insert(
            id,
            config.fastSeconds,
            config.slowSeconds,
            GradingReason.PRIOR,
            config.correctnessOnlyGrading
        )
        id
    }

    private suspend fun insert(
        id: Int,
        easySeconds: Long,
        slowSeconds: Long,
        reason: GradingReason,
        correctnessOnly: Boolean
    ) {
        db.gradingRulesDao().insertVersion(
            GradingVersionEntity(
                id = id.toLong(),
                easySeconds = easySeconds,
                slowSeconds = slowSeconds,
                effectiveFrom = System.currentTimeMillis(),
                reason = reason.stored,
                correctnessOnly = correctnessOnly
            )
        )
    }

    /**
     * Must run under [mutex]. Returns the current version, writing the missing
     * history row for an existing key first — that is the crash repair, and it
     * also covers a restore that carried a version number from elsewhere.
     *
     * A pair saved under an older build of the rule gets a fresh `prior`
     * version instead of reusing its id: the old id's `effectiveFrom` predates
     * the rule change, and an epoch that starts there would pool reviews graded
     * under the reading allowance with reviews graded without it.
     */
    private suspend fun ensureVersionLocked(): Int {
        if (settings.gradingRulesVersionNow() < SettingsStore.GRADING_RULES_VERSION) {
            // schedulerConfigNow() ignores the stale pair and returns 6/60.
            val config = settings.schedulerConfigNow()
            val id = nextVersionId()
            settings.writeCuts(
                config.fastSeconds,
                config.slowSeconds,
                id,
                config.correctnessOnlyGrading
            )
            insert(
                id,
                config.fastSeconds,
                config.slowSeconds,
                GradingReason.PRIOR,
                config.correctnessOnlyGrading
            )
            return id
        }
        val stored = settings.gradingVersionOrNull()
        if (stored != null && stored > 0) {
            if (db.gradingRulesDao().version(stored.toLong()) == null) {
                val config = settings.schedulerConfigNow()
                db.gradingRulesDao().insertVersion(
                    GradingVersionEntity(
                        id = stored.toLong(),
                        easySeconds = config.fastSeconds,
                        slowSeconds = config.slowSeconds,
                        effectiveFrom = System.currentTimeMillis(),
                        reason = GradingReason.PRIOR.stored,
                        correctnessOnly = config.correctnessOnlyGrading
                    )
                )
            }
            return stored
        }
        val config = settings.schedulerConfigNow()
        val id = nextVersionId()
        settings.writeCuts(
            config.fastSeconds,
            config.slowSeconds,
            id,
            config.correctnessOnlyGrading
        )
        insert(
            id,
            config.fastSeconds,
            config.slowSeconds,
            GradingReason.PRIOR,
            config.correctnessOnlyGrading
        )
        return id
    }

    /** The next free id, at least 1. Must run under [mutex]. */
    private suspend fun nextVersionId(): Int =
        ((db.gradingRulesDao().maxVersionId() ?: 0L) + 1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
}
