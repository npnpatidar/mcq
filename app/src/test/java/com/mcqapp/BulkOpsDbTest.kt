package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * questions keep every field including the explanation image, cross-paper
 * moves reset the schedule while same-paper moves keep it, bulk edits keep
 * null fields — are pinned here through the real repository, so the SQL under
 * them cannot drift. Robolectric needs a real SQLite driver, so this test
 * only runs on x86_64 CI.
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
        repository.ensurePaperAndCategory("p1", "Paper 1", "c1b", "Cat 1b")
        repository.ensurePaperAndCategory("p2", "Paper 2", "c2", "Cat 2")
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

    private suspend fun schedule(paperId: String, questionId: String) {
        db.cardStateDao().upsert(
            CardStateEntity(
                paperId = paperId,
                questionId = questionId,
                ease = 2.5,
                intervalDays = 1,
                dueAt = 0L,
                reps = 1,
                lapses = 0,
                leech = false,
                lastReviewedAt = 0L,
                contentHash = ""
            )
        )
    }

    @Test
    fun duplicatePaperClonesIntoTheCopyNamespace() = runBlocking {
        assertEquals("p1-copy", repository.duplicatePaper("p1"))
        // q1-copy is taken by another paper's question, so q1 chains onward.
        val clone = repository.getQuestion("q1-copy-2")
        assertNotNull(clone)
        assertEquals("p1-copy-c1", clone!!.categoryId)
        assertEquals(PNG, clone.explanationImage)
        assertEquals(Difficulty.HARD, clone.difficulty)
        // q2 has no collision and takes the first suffix.
        assertEquals("q2-copy", repository.getQuestion("q2-copy")?.id)
        assertEquals("Paper 1 (copy)", db.paperDao().getById("p1-copy")?.title)
        // The originals are untouched.
        assertEquals("c1", repository.getQuestion("q1")?.categoryId)
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
        assertEquals("c1", repository.getQuestion("q1")?.categoryId)
    }

    @Test
    fun duplicateQuestionChainsSuffixes() = runBlocking {
        assertEquals("q1-copy-2", repository.duplicateQuestion("q1"))
        assertEquals(PNG, repository.getQuestion("q1-copy-2")?.explanationImage)
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

    @Test
    fun moveResetsOnlyCrossPaperSchedules() = runBlocking {
        schedule("p1", "q1")
        schedule("p2", "q1")
        // Same paper: the schedule still applies, so it is kept.
        repository.moveQuestionsToCategory(listOf("q1", "missing"), "c1b")
        assertNotNull(db.cardStateDao().get("p1", "q1"))
        assertNotNull(db.cardStateDao().get("p2", "q1"))
        // Another paper: the old rows would inflate the source paper's due
        // count, so the card starts over.
        repository.moveQuestionsToCategory(listOf("q1"), "c2")
        assertNull(db.cardStateDao().get("p1", "q1"))
        assertNull(db.cardStateDao().get("p2", "q1"))
        assertEquals("c2", repository.getQuestion("q1")?.categoryId)
    }
}
