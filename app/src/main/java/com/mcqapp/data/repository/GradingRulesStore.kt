package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.GradingVersionEntity
import com.mcqapp.domain.SchedulerConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Why a grading version exists, as written by this build.
 *
 * The live grading-rule writer emits only `prior`, `manual` or `rule`. Rows
 * migrated or restored from an older build may carry the legacy values
 * `proposal`, `automatic` or `rollback`. A stored reason is opaque history,
 * not a value that may be parsed against this enum.
 */
enum class GradingReason(val stored: String) {
    /** Seeded from the cuts already on disk; no change was made. */
    PRIOR("prior"),

    /** A learner edited a cut in Settings. */
    MANUAL("manual"),

    /** A learner switched the rule between timed and correctness-only. */
    RULE("rule")
}

/**
 * The only writer of the grade-inference cut points (v28).
 *
 * The cuts live in DataStore because the scheduler reads them on every pull;
 * this store is the one path that changes them, so every change also owns a
 * `grading_versions` row and every review can name the pair that graded it.
 * Nothing here adapts anything and nothing is written just because the app
 * ran. A row is written when the learner edits a cut or switches the rule,
 * when Reset puts the defaults back, when a restore leaves a version whose row
 * cannot describe the pair it restored, when a stored version number has no
 * row or a row whose epoch is unusable, and once on the first read after an
 * upgrade from before the absolute rule — that upgrade retires the old pair
 * and seeds the shipped one, and rewrites the upgrade gate in the same edit,
 * so it runs at most once per install.
 *
 * The write order is deliberate: the cuts and the version number land in one
 * DataStore edit, then the history row is inserted. A crash between the two
 * leaves a version number whose row is missing — repaired here on the next
 * read from the preferences — never a cut value without a version.
 */
internal open class GradingRulesStore(
    private val db: AppDatabase,
    private val settings: SettingsStore
) {

    private val mutex = Mutex()

    /**
     * The current grading version, seeding a `prior` row on a store that has
     * never been versioned. Seeding is guarded by the presence of the version
     * key, not by an empty table, so two concurrent first reads cannot both
     * insert a seed.
     *
     * Open only so a test can substitute a failing store and prove the
     * statistics read refuses to build a version-0 report.
     */
    open suspend fun currentVersion(): Int {
        warmDatabase()
        return mutex.withLock { ensureVersionLocked() }
    }

    /**
     * The whole-config write path: records a MANUAL version when a cut changed,
     * and a RULE version when the timing rule itself changed (the correctness
     * switch), so a Settings save of an unrelated knob does not manufacture a
     * version and two rules never share one.
     */
    suspend fun recordConfigCuts(config: SchedulerConfig): Int {
        warmDatabase()
        return mutex.withLock {
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
    }

    /**
     * Records the pair `Reset study settings` put back. The caller
     * ([McqRepository.resetSchedulerConfig]) only reaches this when the
     * store's single reset edit reported that the effective pair changed, so
     * this always mints a row: Reset is a user action on the cuts, and
     * `manual` is what it is. A reset whose pair was already the defaults
     * never gets here, so moving an unrelated pacing knob cannot end the
     * calibration epoch. The row captures [config]'s rule, so a
     * correctness-only install never files the reset under a tinted clock.
     */
    suspend fun recordResetCuts(config: SchedulerConfig): Int {
        warmDatabase()
        return mutex.withLock {
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
                GradingReason.MANUAL,
                config.correctnessOnlyGrading
            )
            id
        }
    }

    /**
     * Warms the first SQLite open on the IO dispatcher before any DAO access in
     * this store. All public entry points go through here so a first-use writer
     * cannot race the connection pool open against a caller holding the main
     * thread. [AppDatabase.ensureOpen] is one-shot per instance, so later
     * version reads keep this call but pay neither a dispatcher hop nor a
     * second physical open. Called outside [mutex] to keep locking simple and
     * deterministic.
     */
    private suspend fun warmDatabase() {
        db.ensureOpen()
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
     * also covers a restore that carried a version number whose row did not
     * survive.
     *
     * A stored id is reused only when its row exists, describes the pair being
     * used and carries a usable start: the same `easySeconds`/`slowSeconds`
     * and an `effectiveFrom` above 0 and no later than now. Everything else
     * gets a fresh `prior` version stamped now instead. The one reuse that
     * compares nothing is an id whose row is missing: it keeps the id and gets
     * a new row for the stored pair, because a crash between the cut write and
     * the row insert leaves exactly that state and there is no older row to
     * distrust.
     *
     * Comparing the pair covers a restore whose row at that id describes
     * different cuts, and a backup written on a device whose clock stamped the
     * row at or before 0 or in the future. Every restore wipes the tables and
     * replays the backup's own rows, so the first is defence in depth rather
     * than a reachable path.
     *
     * The pair matters because `Calibration.report` filters on
     * `reviewedAt >= epochStart` alone: an epoch borrowed from another pair
     * would count reviews graded under that pair as evidence for these cuts.
     * The timestamp matters because an epoch that starts at no real instant
     * either swallows the whole log or excludes all of it.
     *
     * A pair saved under an older build of the rule gets a fresh `prior`
     * version for the same reason: the old id's `effectiveFrom` predates the
     * rule change, and an epoch that starts there would pool reviews graded
     * under the reading allowance with reviews graded without it.
     *
     * `grading_versions` stays append-only either way: a pair that cannot
     * reuse an epoch gets a new id, never a rewritten row, so every historic
     * review still resolves the pair that actually graded it.
     */
    private suspend fun ensureVersionLocked(): Int {
        // One snapshot decides. Reading the rule gate separately from the pair
        // let a restore land between the two reads: the gate said "stale", the
        // pair said "restored", and the store minted a fresh id for a key it
        // would otherwise have reused — restarting the very epoch this guard
        // exists to preserve. A zero id from the same snapshot means exactly
        // the cases that need a new `prior` row: nothing stored yet, or a pair
        // saved under the retired reading-allowance rule, which
        // `schedulerConfigNow()` already replaces with the shipped pair.
        val config = settings.schedulerConfigNow()
        if (config.gradingVersion > 0) {
            val stored = config.gradingVersion
            val row = db.gradingRulesDao().version(stored.toLong())
            if (row == null) {
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
                return stored
            }
            // The row exists; it may still be the wrong epoch for this pair
            // (see the KDoc above). Only an exact pair match at a usable
            // instant is kept — everything else falls through to a new
            // `prior` row stamped now.
            val samePair = row.easySeconds == config.fastSeconds &&
                row.slowSeconds == config.slowSeconds
            val usableStart = row.effectiveFrom > 0L &&
                row.effectiveFrom <= System.currentTimeMillis()
            if (samePair && usableStart) return stored
        }
        // Keep the pair this rule is about to retire: it cannot be
        // reinterpreted, but it should not vanish without a trace.
        settings.retainRetiredCuts()
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
