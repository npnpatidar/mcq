package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.LadderReason
import com.mcqapp.data.repository.LadderStore
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
 * The ladder's single writer (v26): every cut change owns a version row, the
 * seed happens once, a crash between the value and the row is repaired, and a
 * refit moves the weight version without inventing one on a reset.
 *
 * Robolectric + a real SQLite database, so like every DB-backed test it runs on
 * CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LadderStoreTest {

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

    @Test
    fun firstReadSeedsOnePriorVersionAndTheSecondDoesNot() = runBlocking {
        val ladder = LadderStore(db, store())
        val first = ladder.currentVersion()
        assertTrue(first > 0)
        val versions = db.ladderDao().allVersions()
        assertEquals(1, versions.size)
        assertEquals("prior", versions.single().reason)
        assertEquals(8L, versions.single().easySeconds)
        assertEquals(30L, versions.single().slowSeconds)

        assertEquals(first, ladder.currentVersion())
        assertEquals(1, db.ladderDao().allVersions().size)
        // The preferences carry the same version the row does.
        assertEquals(first, store().ladderVersionOrNull())
    }

    /** A Settings cut edit must write a version row, not just a preference. */
    @Test
    fun aManualCutEditWritesAVersionRow() = runBlocking {
        val repository = McqRepository(db, context)
        val before = repository.ladderVersion()
        repository.setSchedulerConfig(SchedulerConfig(fastSeconds = 10L))
        val after = repository.ladderVersion()
        assertEquals(before + 1, after)

        val row = db.ladderDao().version(after.toLong())!!
        assertEquals("manual", row.reason)
        assertEquals(10L, row.easySeconds)
        assertEquals(before.toLong(), row.previousId)
        assertEquals(10L, repository.schedulerConfigNow().fastSeconds)
    }

    /** Saving a config whose cuts did not change is not a new version. */
    @Test
    fun aSaveWithoutACutChangeDoesNotVersion() = runBlocking {
        val repository = McqRepository(db, context)
        val before = repository.ladderVersion()
        repository.setSchedulerConfig(SchedulerConfig(desiredRetention = 0.85))
        assertEquals(before, repository.ladderVersion())
        assertEquals(1, db.ladderDao().allVersions().size)
    }

    @Test
    fun aResetWritesItsOwnVersion() = runBlocking {
        val repository = McqRepository(db, context)
        repository.setSchedulerConfig(SchedulerConfig(fastSeconds = 12L, slowSeconds = 40L))
        val before = repository.ladderVersion()
        repository.resetSchedulerConfig()
        val after = repository.ladderVersion()
        assertTrue(after > before)
        assertEquals(8L, repository.schedulerConfigNow().fastSeconds)
        assertEquals("manual", db.ladderDao().version(after.toLong())!!.reason)
    }

    /**
     * A crash after the atomic preference write but before the row leaves a
     * version number with no history row; the next read repairs it from the
     * stored cuts rather than moving the number again.
     */
    @Test
    fun aMissingHistoryRowIsRepairedFromThePreferences() = runBlocking {
        val ladder = LadderStore(db, store())
        val version = ladder.setCuts(9L, 31L, LadderReason.MANUAL)
        // Simulate the crash: the row is gone, the preference is not.
        db.openHelper.writableDatabase.execSQL("DELETE FROM ladder_versions")
        assertEquals(version, ladder.currentVersion())
        val repaired = db.ladderDao().version(version.toLong())
        assertNotNull(repaired)
        assertEquals("prior", repaired!!.reason)
        assertEquals(9L, repaired.easySeconds)
        assertEquals(31L, repaired.slowSeconds)
    }

    @Test
    fun restoreSeedsANewPriorVersionContinuingTheNumbering() = runBlocking {
        val ladder = LadderStore(db, store())
        ladder.currentVersion()
        // Pretend the restored backup carried version 5 with its own cuts.
        db.ladderDao().insertVersion(
            com.mcqapp.data.local.LadderVersionEntity(
                id = 5,
                easySeconds = 11L,
                slowSeconds = 33L,
                effectiveFrom = 1L,
                reason = "prior"
            )
        )
        val seeded = ladder.seedAfterRestore()
        assertEquals(6, seeded)
        assertEquals("prior", db.ladderDao().version(6L)!!.reason)
        assertEquals(11L, db.ladderDao().version(6L)!!.easySeconds)
    }

    /** A refit bumps the weight version; a reset goes back to 0, not up. */
    @Test
    fun theFsrsFitVersionMovesWithTheWeightsAndResetsToZero() = runBlocking {
        val settings = store()
        assertEquals(0, settings.schedulerConfigNow().fsrsFitVersion)

        val fitted = SchedulerConfig().fsrsParameters.toMutableList().also { it[0] = 0.5 }
        settings.setFsrsParameters(fitted)
        assertEquals(1, settings.schedulerConfigNow().fsrsFitVersion)

        // The same set again is not a new fit.
        settings.setFsrsParameters(fitted)
        assertEquals(1, settings.schedulerConfigNow().fsrsFitVersion)

        settings.writeWeights(
            com.mcqapp.domain.FsrsScheduler.DEFAULT_FSRS_PARAMETERS,
            forceVersion = 0
        )
        assertEquals(0, settings.schedulerConfigNow().fsrsFitVersion)
    }
}
