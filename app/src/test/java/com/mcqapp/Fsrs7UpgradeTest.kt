package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.data.repository.dataStore
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one-time FSRS-6 → FSRS-7 card rebuild.
 *
 * FSRS-6 stored one stability; FSRS-7 stores two, and the second one cannot be
 * invented from the first — it has to come from replaying the review history
 * through the new model. What this test pins is the *plumbing* around that
 * replay, which is where an upgrade can silently damage a library:
 *
 *  - the schedule the learner sees must not move (a card due today stays due
 *    today; only the memory behind its future intervals changes),
 *  - the run must be resumable and idempotent,
 *  - a review that lands mid-rebuild must win over the replay's stale state,
 *  - a card with memory but no log (a pure Anki import) must get `Sf = S`,
 *    never the 0.8× initialization ratio, which would be a fabrication.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Fsrs7UpgradeTest {

    private val t0 = 1_700_000_000_000L
    private val day = DayBoundary.DAY_MS

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The DataStore is process-wide, and the rebuild is gated on a
        // persisted "done" flag: without a reset, whichever case finishes a
        // rebuild first would make every later one a silent no-op.
        context.dataStore.edit { it.clear() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        // `card_state` and `review_log` carry a foreign key to the paper, so
        // the seeded rows need their paper to exist first.
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        // The rebuild reads the *stored* weights, so the migration has to have
        // run first, exactly as it does at startup.
        repository.ensureFsrs7()
        settings = SettingsStore(context)
    }

    @After
    fun tearDown() = runBlocking {
        db.close()
        // Leave the process-wide DataStore as it was found: its flags outlive
        // this class.
        ApplicationProvider.getApplicationContext<Context>().dataStore.edit { it.clear() }
    }

    // --- the two writes, and the one that must not happen -------------------

    /**
     * The replay rewrites the memory and nothing else. Every column the study
     * loop's due-date, counter and leech logic reads keeps its old value.
     */
    @Test
    fun rebuildRewritesOnlyTheMemoryColumns() = runBlocking {
        db.cardStateDao().upsert(
            card(
                stability = 12.0,
                difficulty = 5.5,
                intervalDays = 21,
                dueAt = t0 + 21 * day,
                reps = 4,
                lapses = 1,
                leech = true,
                lastReviewedAt = t0 + 9 * day
            )
        )
        insertLog(3, t0, stabilityAfter = 6.0, difficultyAfter = 5.5, stateBefore = "NEW")
        insertLog(3, t0 + 3 * day, stabilityAfter = 8.0, difficultyAfter = 5.2)
        insertLog(2, t0 + 9 * day, stabilityAfter = 12.0, difficultyAfter = 5.5)

        repository.rebuildFsrs7Memory()

        val rebuilt = db.cardStateDao().get("p1", "q1")!!
        // The schedule the learner sees is untouched...
        assertEquals(21, rebuilt.intervalDays)
        assertEquals(t0 + 21 * day, rebuilt.dueAt)
        assertEquals(4, rebuilt.reps)
        assertEquals(1, rebuilt.lapses)
        assertTrue(rebuilt.leech)
        assertEquals(t0 + 9 * day, rebuilt.lastReviewedAt)
        assertEquals(2.5, rebuilt.ease, 0.0)
        assertEquals("hash1", rebuilt.contentHash)
        // ...and the memory was recomputed from the log, not copied from the
        // stale row: the seeded 12.0/5.5 pair is not what three reviews
        // including a Hard produce.
        assertNotEquals(12.0, rebuilt.stability)
        assertNotEquals(6.0, rebuilt.stability)
        assertTrue("the fast trace must be filled", rebuilt.stabilityFast > 0.0)
        assertTrue(rebuilt.stabilityFast.isFinite())
        assertTrue(settings.fsrs7RebuildDone())
    }

    /** A card with memory but no log is a pure import: `Sf` collapses onto `S`. */
    @Test
    fun aCardWithoutALogGetsItsFastTraceFromTheSlowOne() = runBlocking {
        db.cardStateDao().upsert(
            card(stability = 30.0, difficulty = 6.0, intervalDays = 30, dueAt = t0 + 30 * day)
        )

        repository.rebuildFsrs7Memory()

        val rebuilt = db.cardStateDao().get("p1", "q1")!!
        assertEquals(30.0, rebuilt.stability, 0.0)
        assertEquals(30.0, rebuilt.stabilityFast, 0.0)
        // Nothing to replay, so the schedule is not even touched by a write.
        assertEquals(30, rebuilt.intervalDays)
    }

    /** A card with no memory at all must stay empty, not become a 0-day memory. */
    @Test
    fun aNewCardWithoutMemoryStaysEmpty() = runBlocking {
        db.cardStateDao().upsert(card(stability = 0.0, difficulty = 0.0))

        repository.rebuildFsrs7Memory()

        val untouched = db.cardStateDao().get("p1", "q1")!!
        assertEquals(0.0, untouched.stability, 0.0)
        assertEquals(0.0, untouched.stabilityFast, 0.0)
        assertEquals(CardStateKind.NEW, untouched.cardState)
    }

    // --- resumability and idempotence ---------------------------------------

    /**
     * The completion flag — not the model version — is what stops a second
     * run: `ensureFsrs7` stamps the version before the replay has happened, so
     * gating on it would skip the rebuild entirely.
     */
    @Test
    fun aCompletedRebuildIsNotRunAgain() = runBlocking {
        db.cardStateDao().upsert(card(stability = 20.0, difficulty = 5.0))
        insertLog(3, t0, stabilityAfter = 5.0, difficultyAfter = 5.0, stateBefore = "NEW")
        insertLog(3, t0 + 4 * day, stabilityAfter = 20.0, difficultyAfter = 5.0)

        repository.rebuildFsrs7Memory()
        val first = db.cardStateDao().get("p1", "q1")!!
        assertTrue(settings.fsrs7RebuildDone())
        assertEquals(0L, settings.fsrsRebuildCursor())

        // A second launch must be a no-op, even if the row is edited by hand.
        db.cardStateDao().upsert(first.copy(stabilityFast = 123.0))
        repository.rebuildFsrs7Memory()
        assertEquals(123.0, db.cardStateDao().get("p1", "q1")!!.stabilityFast, 0.0)
    }

    /**
     * A process kill mid-rebuild leaves a cursor; the next run resumes from it
     * rather than restarting the whole replay, and still finishes correctly.
     */
    @Test
    fun anInterruptedRebuildResumesFromItsCursor() = runBlocking {
        db.cardStateDao().upsert(card(stability = 10.0, difficulty = 5.0))
        insertLog(3, t0, stabilityAfter = 5.0, difficultyAfter = 5.0, stateBefore = "NEW")
        insertLog(3, t0 + 5 * day, stabilityAfter = 10.0, difficultyAfter = 5.0)
        // Simulate a kill after the first page: the cursor is persisted and the
        // completion flag is not.
        settings.setFsrsRebuildCursor(db.reviewLogDao().getAll().first().id)
        assertFalse(settings.fsrs7RebuildDone())

        repository.rebuildFsrs7Memory()

        val rebuilt = db.cardStateDao().get("p1", "q1")!!
        assertTrue(rebuilt.stabilityFast > 0.0)
        assertTrue(settings.fsrs7RebuildDone())
    }

    // --- the race the CAS exists for ---------------------------------------

    /**
     * A review that lands between the stamp read and the update owns the row:
     * the compare-and-set misses and nothing is overwritten. Tested on the DAO
     * directly, because the window cannot be opened from outside the job.
     */
    @Test
    fun aStaleStampLosesToTheLiveReview() = runBlocking {
        db.cardStateDao().upsert(
            card(stability = 1.0, difficulty = 3.0, lastReviewedAt = t0)
        )
        // The review that won the race.
        db.cardStateDao().upsert(
            card(stability = 9.0, difficulty = 4.0, lastReviewedAt = t0 + day)
        )

        val applied = db.cardStateDao().rebuildMemoryIfUnchanged(
            paperId = "p1",
            questionId = "q1",
            lastReviewedAt = t0, // the stamp read before the review landed
            stability = 2.0,
            stabilityFast = 1.5,
            difficulty = 3.5
        )

        assertEquals(0, applied)
        val winner = db.cardStateDao().get("p1", "q1")!!
        assertEquals(9.0, winner.stability, 0.0)
        assertEquals(0.0, winner.stabilityFast, 0.0)
        assertEquals(4.0, winner.difficulty, 0.0)

        // The fresh stamp applies.
        assertEquals(
            1,
            db.cardStateDao().rebuildMemoryIfUnchanged(
                paperId = "p1",
                questionId = "q1",
                lastReviewedAt = t0 + day,
                stability = 2.0,
                stabilityFast = 1.5,
                difficulty = 3.5
            )
        )
    }

    // --- the seeded (imported) history rule --------------------------------

    /**
     * An imported history's first row records a post-state it was never
     * observed producing, so replay seeds from that row and applies only the
     * rest. A pre-v27 row carries no fast trace, so the seed collapses it onto
     * the slow one — the same rule the scheduler applies at read time.
     */
    @Test
    fun anImportedHistorySeedsFromItsFirstRecordedState() = runBlocking {
        db.cardStateDao().upsert(
            card(stability = 40.0, difficulty = 7.0, lastReviewedAt = t0 + 6 * day)
        )
        insertLog(
            3, t0,
            stabilityAfter = 4.0,
            difficultyAfter = 7.0,
            stateBefore = "REVIEW"
        )
        insertLog(3, t0 + 6 * day, stabilityAfter = 40.0, difficultyAfter = 7.0)

        repository.rebuildFsrs7Memory()

        val rebuilt = db.cardStateDao().get("p1", "q1")!!
        // Seeded at 4.0 with one Good six days later: growth, not a reset to
        // the initial-stability table (which would land near 4 days again).
        assertTrue(
            "expected growth from the seeded 4.0, got ${rebuilt.stability}",
            rebuilt.stability > 4.0
        )
        assertTrue(rebuilt.stabilityFast > 0.0)
        assertTrue(rebuilt.stabilityFast.isFinite())
        assertEquals(7.0, rebuilt.difficulty, 0.5)
    }

    // --- helpers -----------------------------------------------------------

    private fun card(
        stability: Double,
        difficulty: Double,
        intervalDays: Int = 3,
        dueAt: Long = t0 + 3 * day,
        reps: Int = 2,
        lapses: Int = 0,
        leech: Boolean = false,
        lastReviewedAt: Long = t0
    ) = CardStateEntity(
        paperId = "p1",
        questionId = "q1",
        ease = 2.5,
        intervalDays = intervalDays,
        dueAt = dueAt,
        reps = reps,
        lapses = lapses,
        leech = leech,
        lastReviewedAt = lastReviewedAt,
        contentHash = "hash1",
        difficulty = difficulty,
        stability = stability,
        // A pre-FSRS-7 row: the fast trace has not been derived yet.
        stabilityFast = 0.0,
        cardState = CardStateKind.REVIEW,
        reviewCount = reps
    )

    private suspend fun insertLog(
        rating: Int,
        reviewedAt: Long,
        stabilityAfter: Double,
        difficultyAfter: Double,
        stateBefore: String? = "REVIEW"
    ) {
        db.reviewLogDao().insert(
            ReviewLogEntity(
                paperId = "p1",
                questionId = "q1",
                rating = rating,
                reviewedAt = reviewedAt,
                elapsedSeconds = 10,
                stateBefore = stateBefore,
                stabilityAfter = stabilityAfter,
                difficultyAfter = difficultyAfter,
                intervalDaysAfter = 3
                // stabilityFastAfter is deliberately left null: this is the
                // shape a pre-v27 install upgrades from.
            )
        )
    }
}
