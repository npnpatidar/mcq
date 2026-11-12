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
import com.mcqapp.domain.stats.Calibration
import com.mcqapp.domain.stats.LadderPolicy
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
        // A restore rewrites the preferences before the seed reads them.
        store().writeCuts(11L, 33L, 5)
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

    private fun proposal(
        version: Int,
        startAt: Long = 1_000L,
        currentEasy: Long = 8L,
        easy: Long = 7L
    ) = LadderPolicy.Proposal(
        easySeconds = easy,
        currentEasySeconds = currentEasy,
        currentSlowSeconds = 30L,
        stepSeconds = 1L,
        direction = Calibration.Direction.LOWER,
        ladderVersion = version,
        fsrsFitVersion = 0,
        block = Calibration.Block(
            index = 0,
            startAt = startAt,
            endAt = startAt + 28L * 86_400_000L,
            pairs = 400,
            easyPairs = 100,
            easyCards = 80,
            goodPairs = 80,
            goodCards = 65,
            hardPairs = 40,
            hardCards = 20,
            closed = true
        ),
        delta1 = -0.08,
        delta1Low = -0.13,
        delta1High = -0.03,
        easyPairs = 100,
        easyCards = 80,
        goodPairs = 80,
        goodCards = 65,
        hardPairs = 40,
        hardCards = 35,
        followUpEasy = 0.94,
        followUpGood = 0.92,
        outcome = "lower-c1"
    )

    @Test
    fun applyingAProposalWritesTheBlockThenTheVersionAndCuts() = runBlocking {
        val ladder = LadderStore(db, store())
        val version = ladder.currentVersion()

        val newVersion = ladder.applyProposal(proposal(version), now = 5_000L)

        assertEquals(7L, store().schedulerConfigNow().fastSeconds)
        assertEquals(30L, store().schedulerConfigNow().slowSeconds)
        val row = db.ladderDao().version(newVersion.toLong())!!
        assertEquals("proposal", row.reason)
        assertEquals(version.toLong(), row.previousId)
        assertEquals("lower-c1", db.ladderDao().allBlocks().single().outcome)
        assertEquals(db.ladderDao().allBlocks().single().id, row.blockId)
        assertEquals(newVersion, store().schedulerConfigNow().ladderVersion)
    }

    @Test
    fun dismissingAProposalRecordsNoChangeAndMovesNothing() = runBlocking {
        val ladder = LadderStore(db, store())
        val version = ladder.currentVersion()

        ladder.dismissProposal(proposal(version), now = 5_000L)

        assertEquals(8L, store().schedulerConfigNow().fastSeconds)
        assertEquals(version, store().schedulerConfigNow().ladderVersion)
        assertEquals(1, db.ladderDao().allVersions().size)
        val block = db.ladderDao().blockAtStart(1_000L)!!
        assertEquals("no-change", block.outcome)
        assertEquals(5_000L, block.decidedAt)
    }

    @Test
    fun aStaleOrAlreadyDecidedProposalIsRejected() = runBlocking {
        val ladder = LadderStore(db, store())
        val version = ladder.currentVersion()
        val proposal = proposal(version)
        ladder.applyProposal(proposal)

        var rejected = false
        try {
            ladder.applyProposal(proposal)
        } catch (e: com.mcqapp.data.repository.LadderProposalStaleException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(1, db.ladderDao().allBlocks().size)
    }

    @Test
    fun repeatedRollbacksWalkBackAlongTheVersionChain() = runBlocking {
        val ladder = LadderStore(db, store())
        ladder.currentVersion()
        ladder.setCuts(9L, 31L, LadderReason.MANUAL)
        ladder.setCuts(7L, 31L, LadderReason.PROPOSAL)

        val first = ladder.rollback()!!
        assertEquals(9L, db.ladderDao().version(first.toLong())!!.easySeconds)
        assertEquals("rollback", db.ladderDao().version(first.toLong())!!.reason)
        assertEquals(9L, store().schedulerConfigNow().fastSeconds)

        val second = ladder.rollback()!!
        assertEquals(8L, db.ladderDao().version(second.toLong())!!.easySeconds)
        assertEquals(8L, store().schedulerConfigNow().fastSeconds)
        // The seed is the end of the chain; a third reset has nowhere to go.
        assertEquals(null, ladder.rollback())
    }
}
