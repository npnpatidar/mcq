package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.QuestionContentMapper
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.QuestionStore
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val PNG = "data:image/png;base64,AAA"

/**
 * The bulk question operations move, copy and edit whole selections inside a
 * single transaction. Their observable semantics — copy ids chain `-copy`
 * suffixes even when a same-named question lives in another paper, cloned
 * questions keep every field including the explanation image, copies append
 * after the target's current tail, a cross-paper move carries the schedule,
 * history and statistics to the destination while a same-paper move is a no-op,
 * bulk edits keep null fields — are pinned here through the real repository, so
 * the SQL under them cannot drift. Robolectric needs a real SQLite driver, so
 * this test only runs on x86_64 CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BulkOpsDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p1", "Paper 1", "c1", "Cat 1")
        repository.ensurePaperAndCategory("p2", "Paper 2", "c2", "Cat 2")
        // ensurePaperAndCategory REPLACE-upserts the paper row, which cascades
        // away its categories; a second p1 category must be inserted directly.
        db.categoryDao().upsert(CategoryEntity(id = "c1b", paperId = "p1", title = "Cat 1b"))
        repository.saveQuestion(question("q1"))
        repository.saveQuestion(question("q2"))
        // A question in another paper occupying q1's copy namespace.
        repository.saveQuestion(question("q1-copy", categoryId = "c2"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun question(id: String, categoryId: String = "c1") = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        options = listOf(
            QuestionOption("a", "right"),
            QuestionOption("b", "wrong")
        ),
        correctOptionIds = setOf("a"),
        explanation = "why $id",
        explanationImage = PNG,
        difficulty = Difficulty.HARD,
        marks = 2.0,
        tags = listOf("t$id")
    )

    private suspend fun schedule(
        paperId: String,
        questionId: String,
        dueAt: Long = 0L,
        intervalDays: Int = 1,
        difficulty: Double = 0.0,
        stability: Double = 0.0,
        stabilityFast: Double = 0.0,
        reps: Int = 1,
        reviewCount: Int = 1
    ) {
        db.cardStateDao().upsert(
            CardStateEntity(
                paperId = paperId,
                questionId = questionId,
                ease = 2.5,
                intervalDays = intervalDays,
                dueAt = dueAt,
                reps = reps,
                lapses = 0,
                leech = false,
                lastReviewedAt = 0L,
                contentHash = "",
                difficulty = difficulty,
                stability = stability,
                stabilityFast = stabilityFast,
                cardState = CardStateKind.REVIEW,
                reviewCount = reviewCount
            )
        )
    }

    private suspend fun seedReviews(paperId: String, questionId: String, count: Int = 2) {
        db.reviewLogDao().insertAll(
            (1..count).map { i ->
                ReviewLogEntity(
                    paperId = paperId,
                    questionId = questionId,
                    rating = 2 + i,
                    reviewedAt = 1_000L * i,
                    elapsedSeconds = 10L * i,
                    stateBefore = "REVIEW",
                    stabilityAfter = i.toDouble(),
                    stabilityFastAfter = i / 2.0,
                    difficultyAfter = 5.0,
                    intervalDaysAfter = i,
                    wasCorrect = true,
                    gradeSource = "AUTO"
                )
            }
        )
    }

    private suspend fun historyOf(questionId: String) =
        db.reviewLogDao().historyForQuestion(questionId)
            .map { Triple(it.id, it.rating, it.reviewedAt) }

    private suspend fun seedStats(questionId: String) {
        db.questionStatsDao().bumpQuestionStats(
            questionId = questionId,
            paperId = "p1",
            paperTitle = "Paper 1",
            categoryTitle = "Cat 1",
            text = "Q $questionId",
            total = 3,
            graded = 3,
            correct = 2,
            wrong = 1,
            skip = 0,
            ungraded = 0,
            repCorrect = 2,
            repWrong = 1,
            repSkip = 0,
            finishedAt = 5_000L,
            lastGradedIsCorrect = true,
            lastGradedFinishedAt = 5_000L
        )
    }

    @Test
    fun copyAppendsAfterTheTargetTailAndKeepsChildren() = runBlocking {
        // c2 already holds q1-copy at sortOrder 0.
        repository.copyQuestionsToCategory(listOf("q2", "q1"), "c2")
        // Clones append after the tail, in the order given.
        assertEquals(1, db.questionDao().getById("q2-copy")?.sortOrder)
        assertEquals(2, db.questionDao().getById("q1-copy-2")?.sortOrder)
        // Every child lands with its clone, and the content hash still
        // matches the source (scheduling keys off it).
        val copy = repository.getQuestion("q1-copy-2")!!
        assertEquals("c2", copy.categoryId)
        assertEquals("Q q1", copy.text)
        assertEquals(listOf("a", "b"), copy.options.map { it.id })
        assertEquals(setOf("a"), copy.correctOptionIds)
        assertEquals("why q1", copy.explanation)
        assertEquals(PNG, copy.explanationImage)
        assertEquals(Difficulty.HARD, copy.difficulty)
        assertEquals(2.0, copy.marks, 0.0)
        assertEquals(listOf("tq1"), copy.tags)
        assertEquals(
            db.questionDao().getById("q1")?.contentHash,
            db.questionDao().getById("q1-copy-2")?.contentHash
        )
    }

    @Test
    fun copyQuestionsToCategoryChainsSuffixes() = runBlocking {
        repository.copyQuestionsToCategory(listOf("q1", "missing"), "c2")
        // q1-copy is taken by another paper's question, so q1 chains onward.
        val copy = repository.getQuestion("q1-copy-2")
        assertNotNull(copy)
        assertEquals("c2", copy!!.categoryId)
        assertEquals(PNG, copy.explanationImage)
        assertNull(repository.getQuestion("missing-copy"))
        // The unknown id wrote nothing and consumed no slot.
        assertEquals(0, db.questionDao().getById("q1-copy")?.sortOrder)
        assertEquals(1, db.questionDao().getById("q1-copy-2")?.sortOrder)
        assertEquals("c1", repository.getQuestion("q1")?.categoryId)
    }

    @Test
    fun duplicateQuestionChainsSuffixes() = runBlocking {
        assertEquals("q1-copy-2", repository.duplicateQuestion("q1"))
        assertEquals(PNG, repository.getQuestion("q1-copy-2")?.explanationImage)
    }

    /**
     * A bulk edit rewrites `tags` wholesale through the COALESCE update, and
     * it used to leave the folded twin at its previous value: editing `alpha`
     * to `CAFÉ` made tag search `café` miss the row and `alpha` keep matching
     * a tag the row no longer has. The pair travels together, folded in
     * Kotlin by the caller — `bulkUpdateQuestions` is the editor's bulk-edit
     * path, so the failure was reachable from the UI.
     */
    @Test
    fun aBulkEditsNewTagsAreFoldedWithTheColumn() = runBlocking {
        val seed = repository.getQuestion("q1")!!
        assertEquals(listOf("tq1"), seed.tags)

        repository.bulkUpdateQuestions(
            listOf("q1"), marks = null, difficulty = null, tags = listOf("CAFÉ")
        )

        assertEquals(listOf("CAFÉ"), repository.getQuestion("q1")!!.tags)
        assertEquals(
            "the folded twin must follow the rewritten tags",
            "café", db.questionDao().getById("q1")!!.tagsFolded
        )
        assertTrue(
            "café must find the row through its edited tag; got "
                + db.questionDao().search("café", "café", 10, 0).map { it.id },
            db.questionDao().search("café", "café", 10, 0).any { it.id == "q1" }
        )
        assertFalse(
            "tq1 must stop matching the replaced tag; got "
                + db.questionDao().search("tq1", "tq1", 10, 0).map { it.id },
            db.questionDao().search("tq1", "tq1", 10, 0).any { it.id == "q1" }
        )

        // The null-keeps contract extends to the pair: a bulk edit that does
        // not touch tags keeps both columns.
        repository.bulkUpdateQuestions(listOf("q1"), marks = 4.0, difficulty = null, tags = null)
        assertEquals("café", db.questionDao().getById("q1")!!.tagsFolded)
    }

    @Test
    fun bulkUpdateAppliesNonNullsAndKeepsNulls() = runBlocking {
        repository.bulkUpdateQuestions(
            listOf("q1", "missing"), marks = 3.5, difficulty = null, tags = null
        )
        val q = repository.getQuestion("q1")!!
        assertEquals(3.5, q.marks, 0.0)
        assertEquals(Difficulty.HARD, q.difficulty)
        assertEquals(listOf("tq1"), q.tags)
    }

    /**
     * The move dialog asks for the real scope before it opens: one selected
     * passage member moves its whole group, so counting only the ticked ids
     * understated the move (question_actions_audit.md QA-02). Unknown ids are
     * ignored and an empty selection resolves to an empty scope.
     */
    @Test
    fun moveScopeExpandsPassageMembers() = runBlocking {
        val passageId = repository.savePassage(
            com.mcqapp.domain.Passage(id = "", categoryId = "c1", title = "Group")
        )
        repository.assignQuestionsToPassage(listOf("q1", "q2"), passageId)

        assertEquals(setOf("q1", "q2"), repository.moveGroupIds(setOf("q1")))
        assertEquals(setOf("q1", "q2"), repository.moveGroupIds(setOf("q1", "missing")))
        assertTrue(repository.moveGroupIds(emptySet()).isEmpty())
    }

    @Test
    fun moveTransfersScheduleHistoryAndStatsAcrossPapers() = runBlocking {
        // q1 lives in c1 (paper p1). Give its card distinctive memory fields so
        // a re-key can be told apart from a fabricated/new card.
        schedule(
            "p1", "q1", dueAt = 12_345L, intervalDays = 9, difficulty = 4.2,
            stability = 12.5, stabilityFast = 6.25, reps = 7, reviewCount = 7
        )
        seedReviews("p1", "q1", 2)
        seedStats("q1")
        val source = db.cardStateDao().get("p1", "q1")!!
        val historyBefore = historyOf("q1")
        assertEquals(2, historyBefore.size)

        // Same-paper move (c1 -> c1b, both p1): everything stays untouched.
        repository.moveQuestionsToCategory(listOf("q1", "missing"), "c1b")
        assertEquals(source, db.cardStateDao().get("p1", "q1"))
        assertNull("no destination row for a same-paper move", db.cardStateDao().get("p2", "q1"))
        assertEquals(historyBefore, historyOf("q1"))
        assertEquals("p1", db.questionStatsDao().get("q1")!!.paperId)
        assertNull("an unknown id is ignored", repository.getQuestion("missing"))

        // Cross-paper move (c1b -> c2, p2): schedule, history and stats follow.
        repository.moveQuestionsToCategory(listOf("q1"), "c2")
        assertEquals("c2", repository.getQuestion("q1")?.categoryId)

        val moved = db.cardStateDao().get("p2", "q1")
        assertNotNull(moved)
        assertEquals(source.copy(paperId = "p2"), moved)
        assertNull("no card left under the old paper", db.cardStateDao().get("p1", "q1"))

        assertEquals("review ids, ratings and times survive", historyBefore, historyOf("q1"))
        assertEquals(0, db.reviewLogDao().countByPaper("p1"))
        assertEquals(2, db.reviewLogDao().countByPaper("p2"))

        val stats = db.questionStatsDao().get("q1")!!
        assertEquals("p2", stats.paperId)
        assertEquals("Paper 2", stats.paperTitle)
        assertEquals("Cat 2", stats.categoryTitle)
        assertEquals("counters are unchanged", 3, stats.totalAttempts)
    }

    @Test
    fun aPassageGroupMovesTogetherWithEveryMembersScheduleAndHistory() = runBlocking {
        val passageId = repository.savePassage(
            com.mcqapp.domain.Passage(id = "", categoryId = "c1", title = "Group")
        )
        // Assigning to a passage resets card_state on the membership change, so
        // seed schedules and history only after the group is formed.
        repository.assignQuestionsToPassage(listOf("q1", "q2"), passageId)
        schedule("p1", "q1", dueAt = 111L, intervalDays = 3)
        schedule("p1", "q2", dueAt = 222L, intervalDays = 5)
        seedReviews("p1", "q1", 2)
        seedReviews("p1", "q2", 3)

        // Moving one member moves the whole group.
        repository.moveQuestionsToCategory(listOf("q1"), "c2")

        assertEquals("c2", repository.getQuestion("q1")?.categoryId)
        assertEquals("c2", repository.getQuestion("q2")?.categoryId)
        assertEquals(passageId, repository.getQuestion("q2")?.passageId)
        assertEquals("c2", db.passageDao().getById(passageId)?.categoryId)

        assertEquals(111L, db.cardStateDao().get("p2", "q1")!!.dueAt)
        assertEquals(3, db.cardStateDao().get("p2", "q1")!!.intervalDays)
        assertEquals(222L, db.cardStateDao().get("p2", "q2")!!.dueAt)
        assertEquals(5, db.cardStateDao().get("p2", "q2")!!.intervalDays)
        assertNull(db.cardStateDao().get("p1", "q1"))
        assertNull(db.cardStateDao().get("p1", "q2"))
        assertEquals(0, db.reviewLogDao().countByPaper("p1"))
        assertEquals(5, db.reviewLogDao().countByPaper("p2"))
    }

    @Test
    fun aCrossPaperMoveKeepsTheDestinationCardOnCollision() = runBlocking {
        // q1 has a card in the source paper and a distinct card already in p2.
        schedule("p1", "q1", dueAt = 111L, intervalDays = 3)
        schedule("p2", "q1", dueAt = 999L, intervalDays = 7, difficulty = 9.9)
        seedReviews("p1", "q1", 2) // source history — must be discarded
        seedReviews("p2", "q1", 1) // destination's own history — must survive
        seedStats("q1")
        val destinationBefore = db.cardStateDao().get("p2", "q1")!!
        val rowsBefore = db.reviewLogDao().getAll().filter { it.questionId == "q1" }
        val destinationHistory = rowsBefore.filter { it.paperId == "p2" }
        val sourceHistory = rowsBefore.filter { it.paperId == "p1" }
        assertEquals(1, destinationHistory.size)
        assertEquals(2, sourceHistory.size)

        repository.moveQuestionsToCategory(listOf("q1"), "c2")

        // The destination card wins untouched (no fabricated merge), and the
        // source card is gone.
        assertEquals(destinationBefore, db.cardStateDao().get("p2", "q1"))
        assertNull(db.cardStateDao().get("p1", "q1"))
        // The merge was DELIBERATELY REJECTED: those source reviews describe the
        // card that lived in p1, not the destination card. Grafting them onto
        // the kept card would invent a memory nobody earned, so the source
        // history is dropped everywhere — not re-keyed under the destination
        // and not left behind in p1 — while the destination's own rows stay.
        val rowsAfter = db.reviewLogDao().getAll().filter { it.questionId == "q1" }
        assertEquals(destinationHistory, rowsAfter)
        assertTrue(
            "no source history anywhere",
            rowsAfter.none { it in sourceHistory }
        )
        assertEquals(1, db.reviewLogDao().countByPaper("p2"))
        assertEquals(0, db.reviewLogDao().countByPaper("p1"))
        // Counters belong to the kept card and stay; only the display paper /
        // category re-point at the destination.
        val stats = db.questionStatsDao().get("q1")!!
        assertEquals("p2", stats.paperId)
        assertEquals("Paper 2", stats.paperTitle)
        assertEquals("Cat 2", stats.categoryTitle)
        assertEquals("counters are unchanged", 3, stats.totalAttempts)
    }

    @Test
    fun moveReportsRekeyedRowsOnlyForCrossPaperMoves() = runBlocking {
        // The repository invalidates the calibration cache only when the store
        // reports a changed row; assert that count directly.
        val store = QuestionStore(db, QuestionContentMapper(db))
        seedReviews("p1", "q1", 2)
        assertEquals(0, store.moveQuestionsToCategory(listOf("q1"), "c1b"))
        assertEquals(2, store.moveQuestionsToCategory(listOf("q1"), "c2"))

        // A collision deletes the source's history instead of re-keying it;
        // those deleted rows still count so the cache is invalidated.
        schedule("p1", "q2")
        schedule("p2", "q2")
        seedReviews("p1", "q2", 3)
        seedReviews("p2", "q2", 1)
        assertEquals(3, store.moveQuestionsToCategory(listOf("q2"), "c2"))
        // A drop and a re-key both report 3, so assert *where* the rows landed:
        // the source rows are gone, not grafted under the kept p2 card, whose
        // own single row survives.
        val q2Rows = db.reviewLogDao().getAll().filter { it.questionId == "q2" }
        assertEquals("source history dropped, destination's kept", 1, q2Rows.size)
        assertEquals("p2", q2Rows.single().paperId)
        assertTrue("no source rows left under p1", q2Rows.none { it.paperId == "p1" })
    }
}
