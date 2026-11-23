package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.repository.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The store factory is injected so a test can own its DataStore instead of
 * sharing the process-wide write actor. Two stores built from two fresh
 * factories must not see each other's preferences; the production default
 * (not passed here) is still the single `Context.dataStore` singleton.
 *
 * Robolectric/SQLite — CI-only (x86_64); compiles on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreshDataStoreFactoryTest {

    private val seq = AtomicLong()

    private fun freshStore(context: Context, scope: CoroutineScope) =
        PreferenceDataStoreFactory.create(scope = scope) {
            File(context.filesDir, "fresh-prefs-${System.nanoTime()}-${seq.incrementAndGet()}.preferences_pb")
        }

    @Test
    fun twoStoresFromTheFactoryDoNotSharePreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val a = SettingsStore(context, freshInstall = false, storeFactory = { freshStore(context, scope) })
            val b = SettingsStore(context, freshInstall = false, storeFactory = { freshStore(context, scope) })

            a.setThemeMode("dark")
            a.setShuffleQuestions(true)

            assertEquals("dark", a.themeMode().first())
            assertEquals(true, a.shuffleQuestions().first())
            // A fresh store starts at the documented defaults: no leakage from
            // the sibling store, and the write above went through the same
            // editPreferences IO hop production uses.
            assertEquals("system", b.themeMode().first())
            assertEquals(false, b.shuffleQuestions().first())
        } finally {
            scope.cancel()
        }
    }
}
