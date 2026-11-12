package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.data.repository.dataStore
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.StudyMode
import com.mcqapp.domain.TestPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DataStore persistence for the FSRS-era scheduler settings.
 *
 * The FSRS switch removed the Anki-parity quota knobs (new/review daily caps)
 * and left three settings the pull-based loop reads: desired retention, the
 * ranking mode and whether new cards are introduced at all. A defaulting bug
 * here is invisible — an old `anki_*` value, or a missing key, would quietly
 * change what the study pull serves — so the round-trip, the fresh-install
 * defaults and the legacy inertness are pinned together.
 *
 * Robolectric, not a mock: DataStore is the real production wiring and needs
 * only the file system, no database, so these run on the ARM64 host too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositorySettingsTest {

    private fun store(): SettingsStore =
        SettingsStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun automaticLadderAdaptationStartsOffAndRoundTrips() = runBlocking {
        assertFalse(store().automaticLadderAdaptationNow())
        store().setAutomaticLadderAdaptation(true)
        assertTrue(store().automaticLadderAdaptationNow())
    }

    /**
     * The DataStore is process-wide, and v26 gave the scheduler config two
     * generation fields (`ladderVersion`, `fsrsFitVersion`) that other test
     * classes write. Clear them before a whole-config comparison, not only
     * after this class, or a neighbour's version leaks in.
     */
    @Before
    fun resetGenerationKeys() {
        runBlocking {
            ApplicationProvider.getApplicationContext<Context>().dataStore.edit { prefs ->
                prefs.remove(stringPreferencesKey("fsrs_parameters"))
                prefs.remove(intPreferencesKey("fsrs_fit_version"))
                prefs.remove(intPreferencesKey("ladder_version"))
            }
        }
    }

    @After
    fun restoreDefaults() {
        // The DataStore singleton is JVM-wide under Robolectric with the file
        // pinned by the first accessor: a config written for one test would
        // silently drive every later test in the run. Re-assert a blank slate.
        runBlocking {
            store().resetSchedulerConfig()
            store().clearTestProgress()
            store().setHapticsEnabled(true)
            store().setAutomaticLadderAdaptation(false)
            // Fitted weights ride a separate key that resetSchedulerConfig
            // deliberately leaves alone; clear it here or one test's fit would
            // leak into every later assertion in the run.
            ApplicationProvider.getApplicationContext<Context>().dataStore.edit {
                it.remove(stringPreferencesKey("fsrs_parameters"))
                // The generation fields move with the weights/ladder; they
                // are not pacing knobs and would otherwise leak a bump into
                // the next test's whole-config comparison.
                it.remove(intPreferencesKey("fsrs_fit_version"))
                it.remove(intPreferencesKey("ladder_version"))
                // The last test length is a plain preference, not scheduler
                // state: clear it too so one test's 60 cannot seed the next.
                it.remove(intPreferencesKey("last_test_length"))
            }
        }
    }

    @Test
    fun aFreshStoreYieldsTheDocumentedDefaults() = runBlocking {
        val config = store().schedulerConfigNow()
        val defaults = SchedulerConfig()
        assertEquals(defaults.studyMode, config.studyMode)
        assertEquals(StudyMode.MEMORY_FIRST, config.studyMode)
        assertEquals(defaults.introduceNewCards, config.introduceNewCards)
        assertTrue(config.introduceNewCards)
        assertEquals(defaults.desiredRetention, config.desiredRetention, 0.0001)
        assertEquals(0.9, config.desiredRetention, 0.0001)
    }

    /**
     * The legacy `anki_*` quota keys are inert by omission: nothing reads
     * them. An install that still carries them reads exactly like a fresh
     * one, which is what this asserts by round-tripping only the FSRS-era
     * settings and confirming none of the removed fields reappear.
     */
    @Test
    fun hapticsAreOnByDefaultAndSurviveARoundTrip() = runBlocking {
        assertTrue("a fresh install starts with haptics on", store().hapticsEnabled().first())
        store().setHapticsEnabled(false)
        assertFalse(store().hapticsEnabled().first())
        store().setHapticsEnabled(true)
        assertTrue(store().hapticsEnabled().first())
    }

    @Test
    fun theHapticsPreferenceRidesTheBackup() = runBlocking {
        store().setHapticsEnabled(false)
        val snapshot = store().snapshot()
        assertTrue(
            "a settings backup must carry the haptics switch",
            snapshot.preferences.containsKey("haptics_enabled")
        )

        store().setHapticsEnabled(true)
        store().restore(snapshot)
        assertFalse("restoring the backup must restore the switch", store().hapticsEnabled().first())
    }

    @Test
    fun noQuotaSettingsSurviveRoundTrip() = runBlocking {
        val store = store()
        store.setSchedulerConfig(
            SchedulerConfig(
                studyMode = StudyMode.EXPLORE_NEW,
                introduceNewCards = false,
                desiredRetention = 0.97
            )
        )
        val config = store.schedulerConfigNow()
        assertEquals(StudyMode.EXPLORE_NEW, config.studyMode)
        assertFalse(config.introduceNewCards)
        assertEquals(0.97, config.desiredRetention, 0.0001)

        val fields = SchedulerConfig::class.java.declaredFields.map { it.name }
        assertTrue(
            "the removed quota fields must not come back into the config: $fields",
            fields.none { it.contains("Limit", ignoreCase = true) || it == "newLimit" }
        )
    }

    @Test
    fun everyFsrsSettingSurvivesARoundTrip() = runBlocking {
        val store = store()
        val written = SchedulerConfig(
            maxIntervalDays = 365,
            relearnMs = 5 * 60 * 1000L,
            leechThreshold = 4,
            desiredRetention = 0.85,
            studyMode = StudyMode.EXPLORE_NEW,
            introduceNewCards = false,
            dayStartHour = 6,
            fastSeconds = 10L,
            slowSeconds = 40L
        )
        store.setSchedulerConfig(written)
        val read = store.schedulerConfigNow()
        // The fit version is a weight-set generation, not a pacing value the
        // caller writes; writing a new parameter list bumps it.
        assertEquals(
            written.sanitized().copy(
                ladderVersion = read.ladderVersion,
                fsrsFitVersion = read.fsrsFitVersion
            ),
            read
        )
    }

    @Test
    fun outOfRangeValuesAreClampedRatherThanCorruptingTheStore() = runBlocking {
        val store = store()
        store.setSchedulerConfig(
            SchedulerConfig(
                desiredRetention = 0.5,
                studyMode = StudyMode.EXPLORE_NEW,
                dayStartHour = 99
            )
        )
        val config = store.schedulerConfigNow()
        assertEquals(0.75, config.desiredRetention, 0.0001)
        // sanitized() clamps the day start to the 0..23 range it can express:
        // 99 lands on the ceiling, not on any "sensible" hour.
        assertEquals(23, config.dayStartHour)
    }

    /**
     * The exact store an old install upgrades from: the quota rows are still
     * on disk, written by the pre-quota-removal build. Nothing reads them, so
     * the config must read exactly like a fresh install — the caps must not
     * resurface as a daily limit no screen shows any more.
     */
    @Test
    fun aLegacyQuotaStoreReadsLikeAFreshInstall() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.dataStore.edit { prefs ->
            prefs[doublePreferencesKey("anki_new_limit")] = 999.0
            prefs[doublePreferencesKey("anki_review_limit")] = 500.0
            prefs[doublePreferencesKey("anki_new_ignore_review_limit")] = 1.0
        }
        assertEquals(SchedulerConfig().sanitized(), store().schedulerConfigNow())
        // A 999-cap install would have crawled or starved the queue; the
        // defaults stand, quota caps included.
        assertEquals(36500, store().schedulerConfigNow().maxIntervalDays)
    }

    /**
     * A torn write or a hand-edited key must not wedge the scheduler: an
     * unreadable mode name falls back to the default rather than crashing
     * the read, and the next write replaces the bad value with a real mode.
     */
    @Test
    fun aCorruptStudyModeFallsBackToMemoryFirstAndSelfHeals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.dataStore.edit { it[stringPreferencesKey("study_mode")] = "EXPLODE" }
        assertEquals(StudyMode.MEMORY_FIRST, store().schedulerConfigNow().studyMode)

        val store = store()
        store.setSchedulerConfig(SchedulerConfig(desiredRetention = 0.85))
        val config = store.schedulerConfigNow()
        assertEquals(StudyMode.MEMORY_FIRST.name, context.dataStore.data
            .first()[stringPreferencesKey("study_mode")])
        // Unchanged: a fallback config is still a valid config round-trip.
        assertEquals(StudyMode.MEMORY_FIRST, config.studyMode)
        assertEquals(0.85, config.desiredRetention, 0.0001)
    }

    @Test
    fun resetRestoresTheFsrsDefaults() = runBlocking {
        val store = store()
        store.setSchedulerConfig(
            SchedulerConfig(
                desiredRetention = 0.97,
                studyMode = StudyMode.EXPLORE_NEW,
                introduceNewCards = false
            )
        )
        store.resetSchedulerConfig()
        val config = store.schedulerConfigNow()
        // Pacing only: the reset deliberately leaves the fitted weights — and
        // therefore their generation — alone.
        assertEquals(
            SchedulerConfig().sanitized().copy(
                ladderVersion = config.ladderVersion,
                fsrsFitVersion = config.fsrsFitVersion
            ),
            config
        )
        assertTrue(config.introduceNewCards)
    }

    // --- optimized weights ---

    /** Fitted weights persist as their own setting, independent of pacing. */
    @Test
    fun fittedFsrsParametersSurviveARoundTrip() = runBlocking {
        val store = store()
        val fitted = SchedulerConfig().fsrsParameters.toMutableList().also {
            it[0] = 0.5
            it[20] = 0.2
        }
        store.setSchedulerConfig(SchedulerConfig(fsrsParameters = fitted))
        assertEquals(fitted, store.schedulerConfigNow().fsrsParameters)
    }

    /** A torn CSV write falls back to the official defaults, not to garbage. */
    @Test
    fun malformedFsrsParametersFallBackToDefaults() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.dataStore.edit { it[stringPreferencesKey("fsrs_parameters")] = "1,2,3" }
        assertEquals(SchedulerConfig().fsrsParameters, store().schedulerConfigNow().fsrsParameters)
    }

    /**
     * Resetting pacing restores the documented scheduler defaults but must
     * not throw away a fit the user asked to keep — the optimizer's own
     * "Restore defaults" button is what clears the weights.
     */
    @Test
    fun resettingPacingLeavesFittedWeightsAlone() = runBlocking {
        val store = store()
        val fitted = SchedulerConfig().fsrsParameters.toMutableList().also { it[0] = 0.5 }
        store.setSchedulerConfig(SchedulerConfig(fsrsParameters = fitted, desiredRetention = 0.95))
        store.resetSchedulerConfig()
        val config = store.schedulerConfigNow()
        assertEquals(0.9, config.desiredRetention, 0.0001)
        assertEquals(fitted, config.fsrsParameters)
    }

    /**
     * The optimizer persists through a single-key edit, never a whole-config
     * round trip — so writing weights alone must leave the pacing knobs
     * exactly as they were, and a pacing write must not be needed to store
     * a fit.
     */
    /**
     * A settings restore replaces every preference, but the resume snapshot
     * is not a setting: clearing it here used to destroy a live in-progress
     * test on a settings-only restore.
     */
    @Test
    fun settingsRestorePreservesTheLiveResumeSnapshot() = runBlocking {
        val store = store()
        store.saveTestProgress("""{"paperId":"p1"}""")
        store.restore(McqBackupFormat.SettingsFile())
        assertEquals("""{"paperId":"p1"}""", store.loadTestProgress())
    }

    /** Nor does an export carry the session slot into another device's restore. */
    @Test
    fun settingsSnapshotOmitsTheResumeSnapshot() = runBlocking {
        val store = store()
        store.saveTestProgress("""{"paperId":"p1"}""")
        assertFalse("in_progress_test" in store.snapshot().preferences)
    }

    @Test
    fun writingWeightsAloneLeavesPacingUntouched() = runBlocking {
        val store = store()
        store.setSchedulerConfig(SchedulerConfig(desiredRetention = 0.95))
        val fitted = SchedulerConfig().fsrsParameters.toMutableList().also { it[0] = 0.5 }
        store.setFsrsParameters(fitted)
        val config = store.schedulerConfigNow()
        assertEquals(0.95, config.desiredRetention, 0.0001)
        assertEquals(fitted, config.fsrsParameters)
    }

    /**
     * Plan §10: the setup sheet starts from the last accepted length, and a
     * fresh install keeps the documented default of 20.
     */
    @Test
    fun theLastTestLengthRoundTripsAndDefaultsToTwenty() = runBlocking {
        val store = store()
        assertEquals(TestPlan.DEFAULT_LENGTH, store.lastTestLength().first())
        store.setLastTestLength(60)
        assertEquals(60, store.lastTestLength().first())
        store.setLastTestLength(20)
        assertEquals(20, store.lastTestLength().first())
    }
}
