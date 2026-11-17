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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule

/**
 * §5.8 of the scaling plan: a cold start must not do database work on the main
 * thread. It *may* decide the first-start grading rule and run the FSRS-7 jobs
 * in the background, so "the database file must not exist after `onCreate`" is
 * not an invariant on the production path.
 *
 * This is the one class that runs the production application, because the
 * startup path itself is the subject. The rest of the suite gets
 * [TestMcqApplication] from `robolectric.properties`, which disables both the
 * probe and the FSRS-7 startup jobs.
 *
 * The startup path is pinned here:
 *  - `onCreate` returns without opening Room on the main thread. The probe's
 *    caller thread is recorded by [AppDatabase.hasNoPapersOrReviews]; a
 *    synchronous startup job would put the main thread's name there.
 *  - a new install (empty database) ends with the marker and the
 *    correctness-only seed, from the real probe.
 *  - an install that already holds a paper ends with neither. Deleting the
 *    probe launch makes both of the last two fail.
 *  - with every startup job disabled, `onCreate` alone leaves the database
 *    file absent. The production path's probe-thread assertion, not file
 *    existence, proves that its database work stayed off the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FreshStartupApplication::class)
class StartupTest {

    private val watchdog = HangWatchdogRule(seconds = 120)
    private val timeout = TimeoutRule.seconds(180)
    private val testName = TestName()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(watchdog)
        .around(timeout)
        .around(testName)

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

        // The probe's caller thread is the proof: it opened the database from
        // the startup job, not from `onCreate` on the main thread. File
        // existence would not distinguish those paths because the FSRS-7 jobs
        // also open Room.
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
        val stored = runOffMainWhilePumping(
            timeoutMs = 10_000,
            condition = "DataStore read of ${CORRECTNESS_ONLY.name} in StartupTest.${testName.methodName}"
        ) {
            withContext(Dispatchers.IO) { withTimeout(10_000) { context.dataStore.data.first()[CORRECTNESS_ONLY] } }
        }
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
    fun applicationOnCreateOpensNothingWhenTheStartupJobsAreDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("the test application did not come up", context is McqApplication)
        assertFalse(
            "Application.onCreate opened the database; only a startup job may open it",
            context.getDatabasePath(AppDatabase.NAME).exists()
        )
    }

    /**
     * Idles the main looper and polls the process-wide store until [key] has
     * [expected], or the timeout passes. Bounded rather than fixed-sleep: the
     * startup job's latency is not the test's subject. Each DataStore read runs
     * off the main thread while this thread pumps the paused main looper so a
     * Main-dispatched continuation can still progress.
     */
    private fun awaitStored(
        context: Context,
        key: Preferences.Key<Boolean>,
        expected: Boolean,
        timeoutMs: Long = 10_000
    ): Boolean {
        fun readValue(): Boolean? = runOffMainWhilePumping(
            timeoutMs = 2_000,
            condition = "DataStore read of ${key.name} in StartupTest.${testName.methodName}"
        ) {
            withContext(Dispatchers.IO) { withTimeout(2_000) { context.dataStore.data.first()[key] } }
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (readValue() == expected) return true
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        val value = readValue()
        check(value == expected) {
            "timed out after ${timeoutMs}ms waiting for ${key.name}==$expected in " +
                "StartupTest.${testName.methodName} (got $value)"
        }
        return true
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
        // Robolectric can create the custom Application before a method rule is
        // effective. Guard the whole setup here so its DataStore/SQLite work
        // cannot bypass the hard watchdog.
        HangWatchdogRule.guarded(
            seconds = 120,
            description = "FreshStartupApplication.onCreate"
        ) {
            runOffMainWhilePumping(
                timeoutMs = SettingsResetRule.CLEAR_DEADLINE_MS,
                condition = "FreshStartupApplication.onCreate key clear and DB reset before super.onCreate()"
            ) {
                withContext(Dispatchers.IO) {
                    withTimeout(SettingsResetRule.CLEAR_DEADLINE_MS) {
                        SettingsResetRule.clearGradingKeysSuspend(this@FreshStartupApplication)
                    }
                }
            }
            // Must complete before super.onCreate() launches the production freshness probe.
            AppDatabase.resetInstanceForTests()
            callSuperOnCreate()
        }
    }

    private fun callSuperOnCreate() {
        super.onCreate()
    }
}

/**
 * An upgrade: a paper is already in the database when `onCreate` runs, so the
 * production probe must answer "not new". Everything else is [McqApplication].
 */
class UpgradeStartupApplication : McqApplication() {
    override fun onCreate() {
        // See FreshStartupApplication: this setup can run before the method
        // rule, so it owns an explicit hard watchdog as well.
        HangWatchdogRule.guarded(
            seconds = 120,
            description = "UpgradeStartupApplication.onCreate"
        ) {
            runOffMainWhilePumping(
                timeoutMs = SettingsResetRule.CLEAR_DEADLINE_MS,
                condition = "UpgradeStartupApplication.onCreate setup before super.onCreate()"
            ) {
                withContext(Dispatchers.IO) {
                    withTimeout(SettingsResetRule.CLEAR_DEADLINE_MS) {
                        SettingsResetRule.clearGradingKeysSuspend(this@UpgradeStartupApplication)
                        // Reset and seed the paper on IO; must complete before super.onCreate().
                        AppDatabase.resetInstanceForTests()
                        AppDatabase.get(this@UpgradeStartupApplication).ensureOpen()
                        AppDatabase.get(this@UpgradeStartupApplication)
                            .paperDao()
                            .insertIgnore(PaperEntity(id = "existing", title = "Already here"))
                    }
                }
            }
            callSuperOnCreate()
        }
    }

    private fun callSuperOnCreate() {
        super.onCreate()
    }
}
