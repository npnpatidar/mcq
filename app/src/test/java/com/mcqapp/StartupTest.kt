package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.repository.InstallFreshness
import com.mcqapp.data.repository.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * §5.8 of the scaling plan: a cold start must not do database work on the main
 * thread. It *may* decide the first-start grading rule in the background — the
 * answer has to be taken before the learner can import a paper — so "the
 * database file must not exist after `onCreate`" is no longer an invariant.
 *
 * This is the one class that runs the production application, because the
 * startup path itself is the subject. The rest of the suite gets
 * [TestMcqApplication] from `robolectric.properties`, which skips the probe.
 *
 * Three things are pinned here:
 *  - `onCreate` returns without opening Room on the main thread. The probe's
 *    caller thread is recorded by [AppDatabase.hasNoPapersOrReviews]; a
 *    synchronous startup job would put the main thread's name there.
 *  - a new install (empty database) ends with the marker and the
 *    correctness-only seed, from the real probe.
 *  - an install that already holds a paper ends with neither. Deleting the
 *    probe launch makes both of the last two fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FreshStartupApplication::class)
class StartupTest {

    @After
    fun clearInstallState() {
        // The probe writes the process-wide marker, and the seed writes
        // `correctness_only`. A SettingsResetRule cannot be used here: its
        // `before()` runs after the application was created, so it could
        // delete the marker this class exists to observe. Clearing afterwards
        // keeps the next class from inheriting either answer.
        SettingsResetRule.clearGradingKeys()
    }

    @Test
    fun aNewInstallSeedsCorrectnessOnlyFromTheBackgroundProbe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("the production application did not come up", context is McqApplication)

        assertTrue(
            "the startup job did not persist its answer within the timeout",
            awaitStored(context, InstallFreshness.MARKER_KEY, true)
        )
        assertTrue(
            "a new install must be seeded correctness-only",
            awaitStored(context, CORRECTNESS_ONLY, true)
        )

        // The probe opened the database — but from the startup job, not from
        // `onCreate` on the main thread. Without this the file test alone
        // would pass even if the job were made synchronous.
        assertTrue(
            "the background probe never opened the database",
            context.getDatabasePath(AppDatabase.NAME).exists()
        )
        val probeThread = AppDatabase.freshnessProbeThread
        assertNotNull("the startup job never ran the freshness probe", probeThread)
        assertNotEquals(
            "the first-start probe ran on the main thread: `onCreate` must not open the database synchronously",
            Looper.getMainLooper().thread.name,
            probeThread
        )
    }

    @Test
    @Config(application = UpgradeStartupApplication::class)
    fun anInstallWithHistoryIsNotSeeded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("the production application did not come up", context is McqApplication)

        assertTrue(
            "the startup job did not answer \"not new\" within the timeout",
            awaitStored(context, InstallFreshness.MARKER_KEY, expected = false)
        )
        // The marker is written by the seed decision itself, so the seed has
        // already run: an upgrade must not have written the rule either.
        val stored = runBlocking { context.dataStore.data.first()[CORRECTNESS_ONLY] }
        assertTrue(
            "an install that already holds a paper must not be seeded correctness-only",
            stored != true
        )
    }

    /**
     * With the startup job disabled there is nothing that could open Room, so
     * the direct assertion is back: `onCreate` alone leaves the file absent.
     * This is also the shape every other Robolectric class runs under.
     */
    @Test
    @Config(application = TestMcqApplication::class)
    fun applicationOnCreateOpensNothingWhenTheProbeIsDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("the test application did not come up", context is McqApplication)
        assertFalse(
            "Application.onCreate opened the database; only the startup job may",
            context.getDatabasePath(AppDatabase.NAME).exists()
        )
    }

    /**
     * Idles the main looper and polls the process-wide store until [key] has
     * [expected], or the timeout passes. Bounded rather than fixed-sleep: the
     * startup job's latency is not the test's subject.
     */
    private fun awaitStored(
        context: Context,
        key: Preferences.Key<Boolean>,
        expected: Boolean,
        timeoutMs: Long = 10_000
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (runBlocking { context.dataStore.data.first()[key] } == expected) return true
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return runBlocking { context.dataStore.data.first()[key] } == expected
    }

    private companion object {
        val CORRECTNESS_ONLY = booleanPreferencesKey("correctness_only")
    }
}

/**
 * The production startup path from a known-empty install.
 *
 * Robolectric keeps static state between tests in one sandbox, so the
 * process-wide DataStore and the cached Room instance may still describe the
 * previous test. Both are reset before `super.onCreate()` launches the real
 * probe: the keys it writes are cleared, and [AppDatabase.resetInstanceForTests]
 * drops the cached database so the probe opens this test's own empty file.
 */
class FreshStartupApplication : McqApplication() {
    override fun onCreate() {
        runBlocking { SettingsResetRule.clearGradingKeys(this@FreshStartupApplication) }
        AppDatabase.resetInstanceForTests()
        super.onCreate()
    }
}

/**
 * An upgrade: a paper is already in the database when `onCreate` runs, so the
 * production probe must answer "not new". Everything else is [McqApplication].
 */
class UpgradeStartupApplication : McqApplication() {
    override fun onCreate() {
        runBlocking { SettingsResetRule.clearGradingKeys(this@UpgradeStartupApplication) }
        AppDatabase.resetInstanceForTests()
        runBlocking {
            AppDatabase.get(this@UpgradeStartupApplication)
                .paperDao()
                .insertIgnore(PaperEntity(id = "existing", title = "Already here"))
        }
        super.onCreate()
    }
}
