package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.data.repository.dataStore
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
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
    fun tearDown() {
        db.close()
        // Leave the process-wide DataStore as it was found: its flags outlive
        // this class.
        runBlocking {
            ApplicationProvider.getApplicationContext<Context>().dataStore.edit { it.clear() }
        }
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
        db.cardStateDao().upsert(
            card(
                stability = 0.0,
                difficulty = 0.0,
                reps = 0,
                cardState = CardStateKind.NEW
            )
        )

        repository.rebuildFsrs7Memory()

        val untouched = db.cardStateDao().get("p1", "q1")!!
        assertEquals(0.0, untouched.stability, 0.0)
        assertEquals(0.0, untouched.stabilityFast, 0.0)
        assertEquals(CardStateKind.NEW, untouched.cardState)
        assertEquals(3, untouched.intervalDays)
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
        db.cardStateDao().upsert(
            card(stability = 10.0, difficulty = 5.0, lastReviewedAt = t0 + 5 * day)
        )
        db.cardStateDao().upsert(
            card(
                stability = 10.0,
                difficulty = 5.0,
                lastReviewedAt = t0 + 5 * day,
                questionId = "q2"
            )
        )
        insertLog(3, t0, stabilityAfter = 5.0, difficultyAfter = 5.0, stateBefore = "NEW")
        insertLog(3, t0 + 5 * day, stabilityAfter = 10.0, difficultyAfter = 5.0)
        val firstCardFirstRow = db.reviewLogDao().getAll().first().id
        insertLog(
            3, t0, stabilityAfter = 5.0, difficultyAfter = 5.0,
            stateBefore = "NEW", questionId = "q2"
        )
        insertLog(3, t0 + 5 * day, stabilityAfter = 10.0, difficultyAfter = 5.0, questionId = "q2")
        // Simulate a kill after the first page: q1 is done, the cursor is
        // persisted and the completion flag is not.
        settings.setFsrsRebuildCursor(firstCardFirstRow)
        assertFalse(settings.fsrs7RebuildDone())

        repository.rebuildFsrs7Memory()

        // q1 was on the finished page and is not replayed again...
        assertEquals(10.0, db.cardStateDao().get("p1", "q1")!!.stability, 0.0)
        // ...while q2, past the cursor, is replayed: its stale 10.0 is replaced
        // by the history's two Good reviews.
        val resumed = db.cardStateDao().get("p1", "q2")!!
        assertNotEquals(10.0, resumed.stability)
        assertTrue(resumed.stabilityFast > 0.0)
        assertTrue(settings.fsrs7RebuildDone())
    }

    // --- what the review loop actually persists ----------------------------

    /**
     * Two graded reviews written through the real study path, then read back
     * from Room, must equal the same two transitions run purely in memory.
     * This is the test the audit asks for that the pure scheduler vectors
     * cannot be: it covers the entity mapper, which is where the fast trace was
     * being dropped.
     */
    @Test
    fun twoPersistedReviewsMatchThePureSchedulerSequence() = runBlocking {
        repository.saveQuestion(question("q1"))
        val config = settings.schedulerConfigNow()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        val first = t0 + 5 * day
        val second = first + 4 * day

        repository.recordStudyReview(
            paperId = "p1", questionId = "q1", grade = ReviewGrade.GOOD,
            now = first, config = config
        )
        repository.recordStudyReview(
            paperId = "p1", questionId = "q1", grade = ReviewGrade.HARD,
            now = second, config = config
        )

        var expected = scheduler.initial("q1")
        expected = scheduler.next(expected, ReviewGrade.GOOD, first)
        expected = scheduler.next(expected, ReviewGrade.HARD, second)

        val stored = db.cardStateDao().get("p1", "q1")!!
        // Exact agreement: both sides run the same arithmetic on the same
        // config, so any difference is a lost field, not a rounding question.
        assertEquals(expected.stability, stored.stability, 0.0)
        assertEquals(expected.stabilityFast, stored.stabilityFast, 0.0)
        assertTrue("the fast trace must survive the write", stored.stabilityFast > 0.0)
        assertEquals(expected.difficulty, stored.difficulty, 0.0)
        assertEquals(expected.intervalDays, stored.intervalDays)
        assertEquals(expected.dueAt, stored.dueAt)
        assertEquals(expected.reps, stored.reps)
        assertEquals(expected.reviewCount, stored.reviewCount)
        assertEquals(expected.lastReviewedAt, stored.lastReviewedAt)
    }

    /**
     * The repair's guard is `stabilityFast = 0`: a row that already carries a
     * trace — because a review wrote it after the omission was discovered — is
     * never touched, even when the log claims a different value. A damaged row
     * beside it is still restored, so the isolation is per row rather than
     * all-or-nothing.
     */
    @Test
    fun theRepairNeverOverwritesARowAReviewAlreadyWrote() = runBlocking {
        db.cardStateDao().upsert(
            card(stability = 4.0, difficulty = 5.0, lastReviewedAt = t0 + day)
        )
        db.reviewLogDao().insert(
            ReviewLogEntity(
                paperId = "p1", questionId = "q1", rating = 3, reviewedAt = t0 + day,
                stateBefore = "NEW", stabilityAfter = 4.0, difficultyAfter = 5.0,
                stabilityFastAfter = 2.75, intervalDaysAfter = 3
            )
        )
        // q2 was graded by the fixed code: its row has a real trace.
        db.cardStateDao().upsert(
            card(stability = 8.0, difficulty = 4.0, lastReviewedAt = t0 + day, questionId = "q2")
                .copy(stabilityFast = 6.0)
        )
        insertLog(3, t0 + day, stabilityAfter = 8.0, difficultyAfter = 4.0, questionId = "q2")
        // A stale log row claiming a different fast value for q2.
        db.reviewLogDao().insert(
            ReviewLogEntity(
                paperId = "p1", questionId = "q2", rating = 3, reviewedAt = t0 + 2 * day,
                stateBefore = "REVIEW", stabilityAfter = 8.0, difficultyAfter = 4.0,
                stabilityFastAfter = 99.0, intervalDaysAfter = 3
            )
        )
        settings.completeFsrs7Rebuild()

        repository.rebuildFsrs7Memory()

        assertEquals(2.75, db.cardStateDao().get("p1", "q1")!!.stabilityFast, 0.0)
        assertEquals("a written trace must not be repaired", 6.0, db.cardStateDao().get("p1", "q2")!!.stabilityFast, 0.0)
    }

    /**
     * The audit's damaged-versus-legacy isolation: a row whose log records a
     * fast trace was damaged by the write bug and is restored from it; a row
     * whose log predates FSRS-7 has no trace to copy, so zero — "unknown" —
     * must survive rather than be filled with the slow trace here.
     */
    @Test
    fun theRepairRestoresADamagedRowAndLeavesAGenuineLegacyRowUnknown() = runBlocking {
        db.cardStateDao().upsert(
            card(stability = 4.0, difficulty = 5.0, lastReviewedAt = t0 + day)
        )
        db.reviewLogDao().insert(
            ReviewLogEntity(
                paperId = "p1", questionId = "q1", rating = 3, reviewedAt = t0 + day,
                stateBefore = "NEW", stabilityAfter = 4.0, difficultyAfter = 5.0,
                stabilityFastAfter = 2.75, intervalDaysAfter = 3
            )
        )
        db.cardStateDao().upsert(
            card(stability = 6.0, difficulty = 4.0, lastReviewedAt = t0 + day, questionId = "q2")
        )
        insertLog(3, t0 + day, stabilityAfter = 6.0, difficultyAfter = 4.0, questionId = "q2")
        settings.completeFsrs7Rebuild()

        repository.rebuildFsrs7Memory()

        assertEquals(2.75, db.cardStateDao().get("p1", "q1")!!.stabilityFast, 0.0)
        assertEquals(0.0, db.cardStateDao().get("p1", "q2")!!.stabilityFast, 0.0)
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

    /**
     * The audit's deterministic interleaving, with a *real* review: the rebuild
     * reads a card's rows, the learner grades the card, and only then does the
     * replay of the rows it read try to write. Because the CAS predicate is the
     * replay's own last review (`t0`), the card's stamp has already moved to the
     * review's instant, so the stale write misses and the review's memory is
     * what the row keeps — the exact sequence that used to overwrite it.
     */
    @Test
    fun aReviewThatLandsAfterThePageReadIsNotOverwritten() = runBlocking {
        repository.saveQuestion(question("q1"))
        db.cardStateDao().upsert(card(stability = 3.0, difficulty = 5.0, lastReviewedAt = t0))
        insertLog(3, t0, stabilityAfter = 3.0, difficultyAfter = 5.0, stateBefore = "NEW")

        // The rebuild's page read.
        val page = db.reviewLogDao().rowsForQuestions(listOf("q1"))
        assertEquals(t0, page.single().reviewedAt)

        // The live review commits before the replay writes.
        val live = repository.recordStudyReview(
            paperId = "p1", questionId = "q1", grade = ReviewGrade.GOOD, now = t0 + 2 * day
        )

        val applied = db.cardStateDao().rebuildMemoryIfUnchanged(
            paperId = "p1",
            questionId = "q1",
            lastReviewedAt = page.single().reviewedAt,
            stability = 1.0,
            stabilityFast = 1.0,
            difficulty = 1.0
        )

        assertEquals(0, applied)
        val stored = db.cardStateDao().get("p1", "q1")!!
        assertEquals(live.stability, stored.stability, 0.0)
        assertEquals(live.stabilityFast, stored.stabilityFast, 0.0)
        assertEquals(live.difficulty, stored.difficulty, 0.0)
    }

    /**
     * The history and the CAS predicate are the same snapshot: when the last
     * logged review does not match the card's stamp, the replay cannot claim
     * to describe this row and writes nothing. The old code read the stamp
     * separately, which is how a review that landed between the two reads was
     * overwritten by a stale replay.
     */
    @Test
    fun aHistoryThatDisagreesWithTheCardStampWritesNoMemory() = runBlocking {
        db.cardStateDao().upsert(
            card(stability = 7.0, difficulty = 6.0, lastReviewedAt = t0 + 2 * day)
        )
        insertLog(3, t0, stabilityAfter = 5.0, difficultyAfter = 6.0, stateBefore = "NEW")
        insertLog(3, t0 + day, stabilityAfter = 6.0, difficultyAfter = 6.0)

        repository.rebuildFsrs7Memory()

        val row = db.cardStateDao().get("p1", "q1")!!
        assertEquals(7.0, row.stability, 0.0)
        assertEquals(6.0, row.difficulty, 0.0)
        // The one-shot backstop still fills the fast trace from the slow one.
        assertEquals(7.0, row.stabilityFast, 0.0)
    }

    /**
     * The one-shot repair for rows saved while the entity mapper dropped the
     * fast trace: the review log's latest `stabilityFastAfter` is authoritative
     * and restored, without replaying anything.
     */
    @Test
    fun theOneShotRepairRestoresADroppedFastTrace() = runBlocking {
        db.cardStateDao().upsert(
            card(stability = 4.0, difficulty = 5.0, lastReviewedAt = t0 + day)
        )
        db.reviewLogDao().insert(
            ReviewLogEntity(
                paperId = "p1",
                questionId = "q1",
                rating = 3,
                reviewedAt = t0 + day,
                stateBefore = "NEW",
                stabilityAfter = 4.0,
                difficultyAfter = 5.0,
                stabilityFastAfter = 2.75,
                intervalDaysAfter = 3
            )
        )
        // Isolate the repair from the replay.
        settings.completeFsrs7Rebuild()

        repository.rebuildFsrs7Memory()

        val row = db.cardStateDao().get("p1", "q1")!!
        assertEquals(2.75, row.stabilityFast, 0.0)
        // Still only memory: the visible schedule did not move.
        assertEquals(3, row.intervalDays)
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

    // --- restoring an FSRS-6-era backup ------------------------------------

    /**
     * FSRS-010, end to end: a settings backup from a fitted v26 install carries
     * 21 weights and a fit version. Restoring it must
     *
     *  - retire the set for the FSRS-7 defaults (21 weights cannot be
     *    converted),
     *  - reset the fit version to zero — defaults nobody fitted are not a fit,
     *    whatever the backup claimed,
     *  - raise the one-shot notice,
     *  - and rebuild the fast traces **during the restore**, because a cursor
     *    from another device is meaningless here and waiting for the next
     *    launch would leave `Sf` unknown until then.
     */
    @Test
    fun aFittedFsrs6BackupIsRetiredAndRebuiltWithoutARelaunch() = runBlocking {
        val legacyFit = List(21) { 0.4 + it * 0.02 }
        val backup = McqBackupFormat.SettingsFile(
            mapOf(
                "fsrs_parameters" to McqBackupFormat.PreferenceEntry(
                    "string", JsonPrimitive(legacyFit.joinToString(","))
                ),
                "fsrs_fit_version" to McqBackupFormat.PreferenceEntry("int", JsonPrimitive(3))
            )
        )
        // The card the restore brings along: memory, no fast trace, one log row.
        db.cardStateDao().upsert(
            card(stability = 4.0, difficulty = 5.0, lastReviewedAt = t0 + day)
        )
        db.reviewLogDao().insert(
            ReviewLogEntity(
                paperId = "p1", questionId = "q1", rating = 3, reviewedAt = t0 + day,
                stateBefore = "NEW", stabilityAfter = 4.0, difficultyAfter = 5.0,
                stabilityFastAfter = 2.75, intervalDaysAfter = 3
            )
        )

        repository.settingsRestore(backup)

        val config = settings.schedulerConfigNow()
        assertEquals(FsrsScheduler.DEFAULT_FSRS_PARAMETERS, config.fsrsParameters)
        assertEquals("a retired legacy fit is version zero", 0, config.fsrsFitVersion)
        assertTrue(settings.fsrs7Notice().first())
        assertTrue("the restore must rebuild, not defer to the next launch", settings.fsrs7RebuildDone())
        assertTrue(db.cardStateDao().get("p1", "q1")!!.stabilityFast > 0.0)
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
        lastReviewedAt: Long = t0,
        cardState: CardStateKind = CardStateKind.REVIEW,
        questionId: String = "q1"
    ) = CardStateEntity(
        paperId = "p1",
        questionId = questionId,
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
        cardState = cardState,
        reviewCount = reps
    )

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    private suspend fun insertLog(
        rating: Int,
        reviewedAt: Long,
        stabilityAfter: Double,
        difficultyAfter: Double,
        stateBefore: String? = "REVIEW",
        questionId: String = "q1"
    ) {
        db.reviewLogDao().insert(
            ReviewLogEntity(
                paperId = "p1",
                questionId = questionId,
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
