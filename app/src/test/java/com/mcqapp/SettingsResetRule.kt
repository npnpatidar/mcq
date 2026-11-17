package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.repository.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.rules.ExternalResource

/**
 * The six process-wide preference keys that these tests must not inherit from
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
         * DataStore serialises every read and write through one process-wide
         * actor, and a full CI run leaves many ViewModel collectors attached to
         * it. On a loaded runner a six-key edit can take well over 30 s, so the
         * bound is generous — but still below every enclosing watchdog (the
         * tightest is 90 s), so a real deadlock is reported instead of hanging.
         */
        internal const val CLEAR_DEADLINE_MS = 60_000L

        suspend fun clearGradingKeysSuspend(
            context: Context = ApplicationProvider.getApplicationContext()
        ) {
            // Bounded and off the paused Robolectric main thread so DataStore
            // I/O can complete without stalling unrelated background work.
            withContext(Dispatchers.IO) {
                withTimeout(CLEAR_DEADLINE_MS) {
                    context.dataStore.edit { prefs ->
                        prefs.remove(booleanPreferencesKey("correctness_only"))
                        prefs.remove(intPreferencesKey("grading_rules_version"))
                        prefs.remove(intPreferencesKey("ladder_version"))
                        prefs.remove(booleanPreferencesKey("install_first_start_new"))
                        prefs.remove(booleanPreferencesKey("haptics_enabled"))
                        prefs.remove(intPreferencesKey("last_test_length"))
                    }
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
