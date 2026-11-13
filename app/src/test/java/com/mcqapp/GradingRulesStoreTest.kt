package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.GradingVersionEntity
import com.mcqapp.data.repository.GradingRulesStore
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.data.repository.dataStore
import com.mcqapp.domain.SchedulerConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cut points' single writer (v27): every cut change owns a version row, the
 * seed happens once, a crash between the value and the row is repaired, and a
 * pair saved under the old reading-allowance rule starts a new epoch instead of
 * reusing a version id whose `effectiveFrom` predates the rule change.
 *
 * Robolectric + a real SQLite database, so like every DB-backed test it runs on
 * CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GradingRulesStoreTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking {
            // A process-wide DataStore: start every test from an empty store.
            context.dataStore.edit { it.clear() }
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
        runBlocking { context.dataStore.edit { prefs -> prefs.clear() } }
    }

    private fun store() = SettingsStore(context)
    private fun rules() = GradingRulesStore(db, store())

    @Test
    fun firstReadSeedsOnePriorVersionAndTheSecondDoesNot() = runBlocking {
        val first = rules().currentVersion()
        assertTrue(first > 0)
        val versions = db.gradingRulesDao().allVersions()
        assertEquals(1, versions.size)
        assertEquals("prior", versions.single().reason)
        assertEquals(6L, versions.single().easySeconds)
        assertEquals(60L, versions.single().slowSeconds)

        assertEquals(first, rules().currentVersion())
        assertEquals(1, db.gradingRulesDao().allVersions().size)
        // The preferences carry the same version the row does.
        assertEquals(first, store().gradingVersionOrNull())
        assertEquals(first, store().schedulerConfigNow().gradingVersion)
    }

    /** A Settings cut edit must write a version row, not just a preference. */
    @Test
    fun aManualCutEditWritesAVersionRow() = runBlocking {
        val repository = McqRepository(db, context)
        val before = repository.gradingVersion()
        repository.setSchedulerConfig(SchedulerConfig(fastSeconds = 10L))
        val after = repository.gradingVersion()
        assertEquals(before + 1, after)

        val row = db.gradingRulesDao().version(after.toLong())!!
        assertEquals("manual", row.reason)
        assertEquals(10L, row.easySeconds)
        assertEquals(60L, row.slowSeconds)
        assertEquals(10L, repository.schedulerConfigNow().fastSeconds)
    }

    /** Saving a config whose cuts did not change is not a new version. */
    @Test
    fun aSaveWithoutACutChangeDoesNotVersion() = runBlocking {
        val repository = McqRepository(db, context)
        val before = repository.gradingVersion()
        repository.setSchedulerConfig(SchedulerConfig(desiredRetention = 0.85))
        assertEquals(before, repository.gradingVersion())
        assertEquals(1, db.gradingRulesDao().allVersions().size)
    }

    @Test
    fun aResetWritesItsOwnVersion() = runBlocking {
        val repository = McqRepository(db, context)
        repository.setSchedulerConfig(SchedulerConfig(fastSeconds = 12L, slowSeconds = 40L))
        val before = repository.gradingVersion()
        repository.resetSchedulerConfig()
        val after = repository.gradingVersion()
        assertTrue(after > before)
        assertEquals(6L, repository.schedulerConfigNow().fastSeconds)
        assertEquals(6L, db.gradingRulesDao().version(after.toLong())!!.easySeconds)
        assertEquals(60L, db.gradingRulesDao().version(after.toLong())!!.slowSeconds)
    }

    /**
     * A crash after the atomic preference write but before the row leaves a
     * version number with no history row; the next read repairs it from the
     * stored cuts rather than moving the number again.
     */
    @Test
    fun aMissingHistoryRowIsRepairedFromThePreferences() = runBlocking {
        val store = store()
        store.writeCuts(9L, 31L, 4)
        db.openHelper.writableDatabase.execSQL("DELETE FROM grading_versions")

        assertEquals(4, rules().currentVersion())
        val repaired = db.gradingRulesDao().version(4L)
        assertNotNull(repaired)
        assertEquals("prior", repaired!!.reason)
        assertEquals(9L, repaired.easySeconds)
        assertEquals(31L, repaired.slowSeconds)
    }

    @Test
    fun restoreSeedsANewPriorVersionContinuingTheNumbering() = runBlocking {
        rules().currentVersion()
        // Pretend the restored backup carried version 5 with its own cuts.
        db.gradingRulesDao().insertVersion(
            GradingVersionEntity(
                id = 5,
                easySeconds = 11L,
                slowSeconds = 33L,
                effectiveFrom = 1L,
                reason = "prior"
            )
        )
        // A restore rewrites the preferences before the seed reads them.
        store().writeCuts(11L, 33L, 5)
        val seeded = rules().seedAfterRestore()
        assertEquals(6, seeded)
        assertEquals("prior", db.gradingRulesDao().version(6L)!!.reason)
        assertEquals(11L, db.gradingRulesDao().version(6L)!!.easySeconds)
    }

    /**
     * The upgrade case: v26 left a version id and a pair tuned for the reading
     * allowance. The id's `effectiveFrom` predates the absolute rule, so reusing
     * it would pool two grading rules in one calibration epoch.
     */
    @Test
    fun aPairSavedUnderAnOlderRuleStartsANewEpoch() = runBlocking {
        db.gradingRulesDao().insertVersion(
            GradingVersionEntity(
                id = 1,
                easySeconds = 8L,
                slowSeconds = 30L,
                effectiveFrom = 1_000L,
                reason = "prior"
            )
        )
        context.dataStore.edit { prefs ->
            prefs[doublePreferencesKey("anki_fast_seconds")] = 8.0
            prefs[doublePreferencesKey("anki_slow_seconds")] = 30.0
            prefs[intPreferencesKey("ladder_version")] = 1
            // No grading_rules_version: this store was last written by v26.
        }

        // Before the first versioned read the stale pair is already ignored.
        assertEquals(6L, store().schedulerConfigNow().fastSeconds)
        assertEquals(0, store().schedulerConfigNow().gradingVersion)

        val fresh = rules().currentVersion()
        assertEquals(2, fresh)
        assertEquals(6L, db.gradingRulesDao().version(2L)!!.easySeconds)
        assertEquals(60L, db.gradingRulesDao().version(2L)!!.slowSeconds)
        // The old row keeps its pair, so every pre-v27 review still resolves it.
        assertEquals(8L, db.gradingRulesDao().version(1L)!!.easySeconds)
        assertEquals(fresh, store().schedulerConfigNow().gradingVersion)
        assertEquals(6L, store().schedulerConfigNow().fastSeconds)
    }
}
