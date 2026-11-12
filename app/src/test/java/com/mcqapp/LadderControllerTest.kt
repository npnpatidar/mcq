package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.LadderVersionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.LadderController
import com.mcqapp.data.repository.LadderStore
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.data.repository.StatsStore
import com.mcqapp.data.repository.dataStore
import com.mcqapp.domain.stats.LadderPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phase-5 glue: automatic mode is default off, and with enough precise
 * evidence an end-of-sitting run writes a block and an `automatic` version.
 *
 * Robolectric and a real database, so this runs on CI's x86_64 runners and not
 * on the ARM64 host. The decision rules themselves are pure-tested in
 * [LadderPolicyTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LadderControllerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val day = 86_400_000L
    private val t0 = 1_750_000_000_000L

    @Before
    fun setup() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            context.dataStore.edit { it.clear() }
            db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper"))
        }
    }

    @After
    fun teardown() {
        runBlocking {
            db.close()
            context.dataStore.edit { prefs -> prefs.clear() }
        }
    }

    private fun controller() = LadderController(
        db = db,
        settings = SettingsStore(context),
        ladderStore = LadderStore(db, SettingsStore(context)),
        statsStore = StatsStore(db, SettingsStore(context))
    )

    /** 600 Easy and 600 Good cards in block 0; Easy mostly wrong, Good mostly right. */
    private fun seedPreciseEvidence() = runBlocking {
        val rows = ArrayList<ReviewLogEntity>()
        for (index in 0 until 1_200) {
            val easy = index % 2 == 0
            val questionId = "q$index"
            val labelAt = t0 + (index % 20) * day
            val labelCorrect = if (easy) index % 10 == 0 else index % 10 in 1..7
            rows += ReviewLogEntity(
                paperId = "p1",
                questionId = questionId,
                rating = if (easy) 4 else 3,
                reviewedAt = labelAt,
                stateBefore = "REVIEW",
                intervalDaysAfter = 0,
                wasGuess = false,
                wasCorrect = labelCorrect,
                gradeSource = "AUTO",
                ladderVersion = 7,
                predictedRecall = 0.9,
                fsrsFitVersion = 0
            )
            rows += ReviewLogEntity(
                paperId = "p1",
                questionId = questionId,
                rating = 3,
                reviewedAt = labelAt + 5 * day,
                stateBefore = "REVIEW",
                intervalDaysAfter = 200,
                wasCorrect = labelCorrect,
                predictedRecall = 0.9
            )
        }
        db.reviewLogDao().insertAll(rows)
        // A versioned epoch with no earlier version: the controller may act on
        // the block, and the safety valve has nothing to walk back.
        SettingsStore(context).writeCuts(8L, 30L, 7)
        db.ladderDao().insertVersion(
            LadderVersionEntity(
                id = 7,
                easySeconds = 8L,
                slowSeconds = 30L,
                effectiveFrom = t0,
                reason = "prior"
            )
        )
    }

    @Test
    fun automaticModeIsOffByDefaultAndDoesNothing() = runBlocking {
        seedPreciseEvidence()
        val result = controller().run(now = t0 + 120 * day)

        assertEquals(LadderController.Result.Disabled, result)
        assertEquals(8L, SettingsStore(context).schedulerConfigNow().fastSeconds)
        assertTrue(db.ladderDao().allBlocks().isEmpty())
    }

    @Test
    fun aClosedWindowThatDecidedNothingIsStillRecorded() = runBlocking {
        // No reviews: the first window closes empty. The audit trail must show
        // a scored, declined window rather than a controller that never ran.
        SettingsStore(context).writeCuts(8L, 30L, 3)
        db.ladderDao().insertVersion(
            LadderVersionEntity(
                id = 3,
                easySeconds = 8L,
                slowSeconds = 30L,
                effectiveFrom = t0,
                reason = "prior"
            )
        )
        SettingsStore(context).setAutomaticLadderAdaptation(true)

        val result = controller().run(now = t0 + 120 * day)

        assertEquals(
            LadderPolicy.Status.BLOCK_EMPTY,
            (result as LadderController.Result.NoChange).status
        )
        val block = db.ladderDao().allBlocks().single()
        assertEquals(t0, block.startAt)
        assertEquals(0, block.easyPairs + block.goodPairs + block.hardPairs)
        assertEquals("insufficient-evidence", block.outcome)
    }

    @Test
    fun automaticModeAppliesOneStepAndRecordsItsBlock() = runBlocking {
        seedPreciseEvidence()
        SettingsStore(context).setAutomaticLadderAdaptation(true)

        val result = controller().run(now = t0 + 120 * day)

        assertTrue("expected an applied step, got $result", result is LadderController.Result.Applied)
        assertEquals(7L, SettingsStore(context).schedulerConfigNow().fastSeconds)
        val version = db.ladderDao().allVersions().last()
        assertEquals("automatic", version.reason)
        val block = db.ladderDao().allBlocks().single()
        assertEquals("lower-c1", block.outcome)
        assertEquals(block.id, version.blockId)
    }
}
