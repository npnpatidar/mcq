package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.StudyMode
import com.mcqapp.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule

/**
 * A: `Reset study settings` is a pacing action, so the Settings screen must
 * show the rule the store kept, not the default snapshot. The old code wrote
 * `SchedulerConfig()` into UI state, so a correctness-only learner saw the
 * switch turn off — and the next unrelated edit persisted that `false`.
 *
 * B: a generic Settings write may carry a snapshot that predates a Reset, a
 * restore or a cut save. Only the field the screen actually edits may land;
 * the rule and the cuts are re-based on the store.
 *
 * Robolectric + the real repository, so like every DB-backed test it runs on
 * CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class SettingsViewModelResetTest {

    @get:Rule
    val timeout: TimeoutRule = TimeoutRule.seconds(180)

    @get:Rule
    val testName = TestName()

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @get:Rule
    val settingsReset = SettingsResetRule()

    @After
    fun cleanup() {
        // Reset deliberately keeps the rule, so the rule above clears it after
        // every test as well as before the next one.
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Database work must not block Robolectric's paused main thread. */
    private fun <T> dbBlocking(block: suspend () -> T): T = runBlocking {
        withContext(Dispatchers.IO) { withTimeout(30_000) { block() } }
    }

    /** Idles the main looper until [condition] holds, or gives up. */
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        val ok = condition()
        check(ok) { "timed out waiting for condition in ${testName.methodName}" }
        return true
    }

    /**
     * The loading gate at its seam: the screen must publish *nothing* before
     * the stored, seeded config has been read. The old code started from a
     * non-null `SchedulerConfig()`, so a tap in that window wrote the defaults
     * over the fresh-install seed. Asserted right after construction, before
     * the init collector can deliver anything.
     */
    @Test
    fun theConfigStaysNullUntilTheStoredValueArrives() {
        val viewModel = SettingsViewModel(app)
        assertNull(
            "a non-null initial config would let the loading window write defaults",
            viewModel.schedulerConfig.value
        )
    }

    /**
     * Regression for the former first-DAO-before-open hang: a fresh, unopened
     * database must complete its first production repository operation without
     * a test-only warm-up and without depending on progress from a blocked main
     * looper. Bounded so a regression fails instead of hanging the run.
     */
    @Test
    fun firstDatabaseOpenAndSeedPathCompletes() {
        val context = app
        val originalDb = com.mcqapp.data.local.AppDatabase.get(context)
        com.mcqapp.data.local.AppDatabase.resetInstanceForTests()
        val freshDb = com.mcqapp.data.local.AppDatabase.get(context)
        try {
            val freshRepository = com.mcqapp.data.repository.McqRepository(freshDb, context)
            val config = dbBlocking { freshRepository.schedulerConfigNow() }
            assertNotNull("seeding the first config must complete", config)
            assertTrue(config.gradingVersion > 0)
        } finally {
            freshDb.close()
            com.mcqapp.data.local.AppDatabase.restoreInstanceForTests(originalDb)
        }
    }

    @Test
    fun resetKeepsTheRuleInTheUiAndInTheStore() {
        dbBlocking {
            repository.setSchedulerConfig(
                SchedulerConfig(
                    correctnessOnlyGrading = true,
                    fastSeconds = 10L,
                    slowSeconds = 40L
                )
            )
        }
        val beforeVersion = dbBlocking { repository.gradingVersion() }
        val viewModel = SettingsViewModel(app)
        awaitCondition {
            val config = viewModel.schedulerConfig.value
            config != null &&
                config.correctnessOnlyGrading &&
                config.fastSeconds == 10L
        }

        viewModel.resetSchedulerConfig()
        // The version is the discriminator: the defaults carry version 0, and
        // an unchanged pair would keep `beforeVersion`. Awaiting only the
        // knobs let the init collector's re-emit hide a ViewModel that had
        // published `SchedulerConfig()` in the meantime.
        awaitCondition {
            val config = viewModel.schedulerConfig.value
            config != null &&
                config.correctnessOnlyGrading &&
                config.fastSeconds == 6L &&
                config.slowSeconds == 60L &&
                config.gradingVersion > beforeVersion
        }
        val ui = viewModel.schedulerConfig.value
        assertNotNull("the screen lost the config after Reset", ui)
        dbBlocking {
            val stored = repository.schedulerConfigNow()
            assertTrue("Reset must not switch the rule off", stored.correctnessOnlyGrading)
            assertEquals(6L, stored.fastSeconds)
            assertEquals(60L, stored.slowSeconds)
            assertEquals(
                "the screen must show the version the store actually wrote",
                stored.gradingVersion,
                ui!!.gradingVersion
            )
            assertTrue(
                "Reset that changed the pair must open a new epoch",
                stored.gradingVersion > beforeVersion
            )
        }

        // An unrelated edit after the reset carries the rule the learner has,
        // not the "off" the old snapshot showed.
        viewModel.updateSchedulerConfig(
            ui!!.copy(studyMode = StudyMode.EXPLORE_NEW)
        )
        awaitCondition {
            viewModel.schedulerConfig.value?.studyMode == StudyMode.EXPLORE_NEW
        }
        dbBlocking {
            val stored = repository.schedulerConfigNow()
            assertTrue(
                "an unrelated edit must not write the old UI snapshot's false rule",
                stored.correctnessOnlyGrading
            )
            assertEquals(
                "the unrelated edit should still have been written",
                StudyMode.EXPLORE_NEW,
                stored.studyMode
            )
        }
    }

    /**
     * The Settings screen writes the whole config for a knob edit. A snapshot
     * built before a rule switch or a cut save still says the old rule and
     * cuts; re-basing only the weights used to write that pair back and mint a
     * spurious version. The stale fields must be ignored, the edit must land.
     */
    @Test
    fun aStaleSnapshotCannotWriteTheRuleOrTheCutsBack() {
        dbBlocking {
            repository.setSchedulerConfig(
                SchedulerConfig(
                    correctnessOnlyGrading = true,
                    fastSeconds = 9L,
                    slowSeconds = 40L
                )
            )
        }
        val viewModel = SettingsViewModel(app)
        awaitCondition { viewModel.schedulerConfig.value != null }

        // Exactly the defaults a screen composed before the fixture's save
        // would still be holding.
        viewModel.updateSchedulerConfig(
            SchedulerConfig(
                correctnessOnlyGrading = false,
                fastSeconds = 6L,
                slowSeconds = 60L,
                desiredRetention = 0.85
            )
        )
        awaitCondition {
            viewModel.schedulerConfig.value?.desiredRetention == 0.85
        }
        dbBlocking {
            val stored = repository.schedulerConfigNow()
            assertTrue(
                "a generic edit must never rewrite the rule from a stale snapshot",
                stored.correctnessOnlyGrading
            )
            assertEquals(
                "a generic edit must never rewrite the cuts from a stale snapshot",
                9L,
                stored.fastSeconds
            )
            assertEquals(40L, stored.slowSeconds)
            assertEquals(0.85, stored.desiredRetention, 0.0001)
        }
    }
}
