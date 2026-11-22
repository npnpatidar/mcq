package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.repository.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.rules.ExternalResource

/**
 * The process-wide preference keys that these tests must not inherit from
 * a neighbour. They are removed before *and* after every test:
 *
 *  - `correctness_only` and `grading_rules_version` are the measurement rule
 *    and its build gate; Reset deliberately leaves them alone, so a test
 *    cannot rely on a reset to clear them.
 *  - `ladder_version` is the calendar version id; a leftover id makes the
 *    next seed look like a repair and reuses the previous test's epoch.
 *  - `install_first_start_new` is the first-start answer. The default
 *    Robolectric application is [TestMcqApplication], whose startup probe does
 *    not run, so a marker in the store always belongs to a test that opted
 *    into the production application (or built `InstallFreshness` by hand).
 *    It must not steer the next test's fixture.
 *  - `haptics_enabled` and `last_test_length` are written by other classes.
 *    The former's unset default is on, so an inherited false value would
 *    fail a default-state assertion; the latter's value would seed another
 *    test's request.
 *  - `confirm_grade_before_next` is an opt-in study flag; an inherited true
 *    would change the default one-tap grading path in an unrelated test.
 *
 * The DataStore file is process-wide under Robolectric, so "before only" is
 * not enough: a class that fails mid-test would otherwise leak into the next.
 * Despite its historical name, this clears the process-wide preference keys
 * these tests touch, not only grading ones. Other keys (weights, pacing knobs)
 * are a class's own business.
 *
 * [clearGradingKeys] takes the [Context] explicitly for the startup
 * applications in `StartupTest`: they must clear before `super.onCreate()`,
 * where `ApplicationProvider` is not yet usable.
 */
class SettingsResetRule : ExternalResource() {

    override fun before() {
        clearGradingKeys()
    }

    override fun after() {
        clearGradingKeys()
    }

    companion object {
        /**
         * How long the *wait* for the clear may take (the pump's deadline in
         * [clearGradingKeys]); the DataStore edit itself is deliberately NOT
         * cancellable — see [clearGradingKeysSuspend].
         */
        internal const val CLEAR_DEADLINE_MS = 60_000L

        /**
         * Clears the process-wide grading keys without ever cancelling the
         * DataStore edit.
         *
         * Cancellation must never reach a DataStore write: `updateData` wraps
         * the caller's own coroutine context into the actor message and runs
         * the transform under it inside the single serialized write actor
         * (androidx datastore-core `DataStoreImpl.updateData`), so a caller
         * cancelled mid-edit — a `withTimeout` whose deadline fires while the
         * runner is loaded — delivers its `CancellationException` inside that
         * one loop. The actor then stops consuming, and every later DataStore
         * call in the process parks forever: all worker threads idle, the
         * test thread stuck in `runBlocking`. That is the exact shape of the
         * wedges this suite has hit (jstack: `Fsrs7SelectionTest.setUp`
         * inside `dataStore.edit`, whole JVM otherwise parked).
         *
         * The bound lives in the caller instead: [runOffMainWhilePumping]
         * abandons the wait at its deadline without cancelling the edit, so a
         * real hang is still reported, just never poisoned.
         */
        suspend fun clearGradingKeysSuspend(
            context: Context = ApplicationProvider.getApplicationContext()
        ) {
            // Bounded and off the paused Robolectric main thread so DataStore
            // I/O can complete without stalling unrelated background work.
            withContext(Dispatchers.IO) {
                context.dataStore.edit { prefs ->
                    prefs.remove(booleanPreferencesKey("correctness_only"))
                    prefs.remove(intPreferencesKey("grading_rules_version"))
                    prefs.remove(intPreferencesKey("ladder_version"))
                    prefs.remove(booleanPreferencesKey("install_first_start_new"))
                    prefs.remove(booleanPreferencesKey("haptics_enabled"))
                    prefs.remove(intPreferencesKey("last_test_length"))
                    prefs.remove(booleanPreferencesKey("confirm_grade_before_next"))
                }
            }
        }

        fun clearGradingKeys(
            context: Context = ApplicationProvider.getApplicationContext()
        ) {
            runOffMainWhilePumping(
                timeoutMs = CLEAR_DEADLINE_MS,
                condition = "SettingsResetRule.clearGradingKeys"
            ) {
                clearGradingKeysSuspend(context)
            }
        }
    }
}
