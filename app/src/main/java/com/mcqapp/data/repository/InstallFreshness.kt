package com.mcqapp.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.mcqapp.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one-shot answer to "was this install new when the app started?", decided
 * from database content and persisted in the settings store.
 *
 * The answer cannot be recomputed later: the first thing a learner does on a
 * new install (browse the library, import a paper, start a test) creates the
 * database file and often a paper row, but none of that turns the install into
 * an upgrade. So the probe runs as early as the app can schedule it, the
 * result is written to a marker, and every later config read awaits that
 * decision instead of re-reading the database. The marker also survives a
 * process restart, which is what makes "import then relaunch" still a new
 * install.
 *
 * A restore replaces the store and the database, so it calls [invalidate]:
 * the cached answer described an install that no longer exists and the next
 * read must ask the restored database.
 *
 * Residual risk: if the probe fails on a genuinely new install and the learner
 * imports a paper before a retry succeeds, the retry answers from that paper
 * and the install is permanently treated as an upgrade — the marker is the only
 * record, so there is no later point at which the mistake can be detected, and
 * the correctness-only default is lost silently (the learner is never told).
 * Failing the other way (treating an unreadable install as new) would seed
 * correctness-only over restored history, which is the irreversible mistake;
 * the conservative answer is the lesser harm, and a full restore is the
 * recovery, because it invalidates the answer and re-probes.
 */
internal class InstallFreshness private constructor(
    private val context: Context,
    private val probe: suspend () -> Boolean,
    private val fixedAnswer: Boolean?
) {

    constructor(context: Context, probe: suspend () -> Boolean) :
        this(context, probe, null)

    private val mutex = Mutex()

    @Volatile
    private var decision: Boolean? = null

    /**
     * The persisted decision, probing at most once per process (or once after
     * a restore invalidated it). The probe and the marker write share [mutex];
     * a second caller waits for the first rather than asking the database
     * again. The marker is re-checked inside [edit] so a decision persisted by
     * another writer between the read and the write wins.
     *
     * A failing probe (a corrupt database, a migration that will not complete)
     * is never fatal: this runs from the startup job and from the first config
     * read, and either path throwing would turn a database problem into a
     * launch-time crash loop that also hides Settings, the screen a learner
     * needs to restore a backup. The failure is logged and answered as "not a
     * new install" — the conservative answer, because seeding correctness-only
     * over an existing history is the one mistake that cannot be undone. It is
     * neither persisted nor cached, so the next read retries the probe after
     * the learner has had a chance to repair the install.
     */
    suspend fun isNewInstall(): Boolean = mutex.withLock {
        fixedAnswer?.let { return it }
        decision?.let { return it }
        val stored = context.dataStore.data.first()[MARKER_KEY]
        if (stored != null) {
            decision = stored
            return stored
        }
        val probed = try {
            probe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(
                "REPO",
                "Install-freshness probe failed; treating this install as an upgrade",
                e
            )
            // Deliberately no marker write and no `decision`: a later attempt
            // (after the learner repairs the database or restores a backup)
            // must be free to succeed.
            return false
        }
        var answer = probed
        context.dataStore.edit { prefs ->
            val winner = prefs[MARKER_KEY]
            if (winner != null) {
                answer = winner
            } else {
                prefs[MARKER_KEY] = probed
            }
        }
        decision = answer
        answer
    }

    /**
     * A restore cleared the preferences and replaced the database: the cached
     * answer and its marker belong to the old install. Removing the marker
     * under [mutex] also covers a probe that was still running when the
     * restore landed — the next read cannot inherit its pre-restore answer.
     */
    suspend fun invalidate() {
        if (fixedAnswer != null) return
        mutex.withLock {
            decision = null
            context.dataStore.edit { it.remove(MARKER_KEY) }
        }
    }

    companion object {
        /**
         * The install-state marker. Excluded from backup snapshots: it
         * describes this installation, not a learner preference.
         */
        internal val MARKER_KEY = booleanPreferencesKey("install_first_start_new")

        /** A store with no database behind it, for tests that pin the seed. */
        fun fixed(context: Context, answer: Boolean): InstallFreshness =
            InstallFreshness(context, { answer }, answer)
    }
}
