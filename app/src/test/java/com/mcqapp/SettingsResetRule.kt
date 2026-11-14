package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.repository.dataStore
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * The four settings keys that a grading test must not inherit from a
 * neighbour. They are removed before *and* after every test:
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
 *
 * The DataStore file is process-wide under Robolectric, so "before only" is
 * not enough: a class that fails mid-test would otherwise leak into the next.
 * Other keys (weights, pacing knobs) are a class's own business.
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
        fun clearGradingKeys(
            context: Context = ApplicationProvider.getApplicationContext()
        ) {
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs.remove(booleanPreferencesKey("correctness_only"))
                    prefs.remove(intPreferencesKey("grading_rules_version"))
                    prefs.remove(intPreferencesKey("ladder_version"))
                    prefs.remove(booleanPreferencesKey("install_first_start_new"))
                }
            }
        }
    }
}
