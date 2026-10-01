package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository behavior against a real (in-memory) Room database under
 * Robolectric: grading weights, bookmark persistence and bulk duplication.
 * Pure scoring math lives in ScoringTest; this pins the DB wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun question(
        id: String,
        correct: Set<String>,
        marks: Double = 1.0,
        categoryId: String = "c1"
    ) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = correct,
        marks = marks
    )

    @Test
    fun bookmarkTogglePersists() = runBlocking {
        assertFalse(repository.isBookmarked("q1"))
        repository.toggleBookmark("q1")
        assertTrue(repository.isBookmarked("q1"))
        repository.toggleBookmark("q1")
        assertFalse(repository.isBookmarked("q1"))
    }

    @Test
    fun saveAttemptWeightsMarksAndExcludesUngraded() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a"), marks = 2.0))
        repository.saveQuestion(question("q2", setOf("q2-a"), marks = 3.0))
        repository.saveQuestion(question("q3", emptySet(), marks = 5.0))
        val questions = repository.getQuestionsForPaper("p1")
        assertEquals(3, questions.size)
        val attemptId = repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = mapOf("q1" to setOf("q1-a"), "q2" to setOf("nope"), "q3" to setOf("x")),
            negativeMarking = 0.25,
            durationSeconds = 60,
            finishedAt = System.currentTimeMillis(),
            dwellSeconds = mapOf("q1" to 30L, "q2" to 90L)
        )
        val attempt = repository.getAttempt(attemptId)!!
        assertEquals(1, attempt.correctCount)
        assertEquals(1, attempt.wrongCount)
        assertEquals(0, attempt.skippedCount)
        assertEquals(2.0 - 0.75, attempt.score, 0.0001)
        assertEquals(5.0, attempt.maxScore, 0.0001)
        val results = repository.getAttemptResults(attemptId)
        assertEquals(3, results.size)
        val dwellById = results.associate { it.questionId to it.dwellSeconds }
        assertEquals(30L, dwellById["q1"])
        assertEquals(90L, dwellById["q2"])
        assertEquals(0L, dwellById["q3"])
        // The ungraded row stores the same single isCorrect computation the
        // counters use: false, matching its exclusion from the score above.
        val correctById = results.associate { it.questionId to it.isCorrect }
        assertEquals(true, correctById["q1"])
        assertEquals(false, correctById["q2"])
        assertEquals(false, correctById["q3"])
    }

    @Test
    fun duplicateQuestionCopiesContentWithFreshId() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a"), marks = 2.0))
        val copyId = repository.duplicateQuestion("q1")
        assertEquals("q1-copy", copyId)
        val copy = repository.getQuestion(copyId!!)!!
        assertEquals("Q q1", copy.text)
        assertEquals(setOf("q1-a"), copy.correctOptionIds)
        assertEquals(2.0, copy.marks, 0.0001)
        assertEquals(2, repository.getQuestionsForPaper("p1").size)
    }

    // --- spaced repetition ---

    @Test
    fun studyQueueIsAllNewForAFreshPaper() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        val queue = repository.getStudyQueue("p1")
        assertEquals(2, queue.size)
        assertTrue(queue.all { it.reason == com.mcqapp.domain.StudyReason.NEW })
    }

    @Test
    fun studyQueueIsEmptyForAnUnknownPaper() = runBlocking {
        assertTrue(repository.getStudyQueue("nope").isEmpty())
    }

    @Test
    fun studyQueueRespectsTheNewLimit() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repeat(5) { repository.saveQuestion(question("q$it", setOf("q$it-a"))) }
        assertEquals(2, repository.getStudyQueue("p1", newLimit = 2).size)
    }

    @Test
    fun reviewingAQuestionPersistsItsSchedule() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        val next = repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        assertEquals(1, next.reps)
        assertEquals(1, next.intervalDays)
        assertEquals(1000L + com.mcqapp.domain.SchedulerConfig().let { 24*60*60*1000L }, next.dueAt)
        val stored = db.cardStateDao().get("p1", "q1")
        assertEquals(1, stored!!.reps)
        assertEquals(com.mcqapp.domain.SchedulerConfig().defaultEase, stored.ease, 0.0001)
    }

    @Test
    fun aReviewedQuestionStopsBeingNew() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        val counts = repository.getStudyCounts("p1", now = 2000L)
        assertEquals(0, counts.due)
    }

    @Test
    fun badgeCountsAgreeWithWhatTheQueueOffers() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        // A past correct answer must be reflected in the badge even before any
        // study session has run, otherwise the library promises cards that the
        // queue then refuses to show.
        val questions = repository.getQuestionsForPaper("p1")
        repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = mapOf("q1" to setOf("q1-a"), "q2" to setOf("q2-b")),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = 5000L,
            dwellSeconds = mapOf("q1" to 15L, "q2" to 15L)
        )
        val counts = repository.getStudyCounts("p1", now = 6000L)
        // q1 graded correct -> scheduled into the future, so not new and not due.
        // q2 graded wrong -> relearning later today, so also not due yet.
        assertEquals(0, counts.due)
        assertEquals(0, counts.fresh)

        // Once the relearning window opens, the badge and the queue agree.
        val dueAt = 5000L + com.mcqapp.domain.SchedulerConfig().relearnMs
        val later = repository.getStudyCounts("p1", now = dueAt)
        assertEquals(1, later.due)
        val queue = repository.getStudyQueue("p1", now = dueAt)
        assertEquals(later.due, queue.count { it.reason != com.mcqapp.domain.StudyReason.NEW })
    }

    @Test
    fun aFailedReviewLeavesTheQuestionNewAndNotDue() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        val again = repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.AGAIN, now = 1000L)
        assertEquals(0, again.reps)
        assertEquals(0, again.lapses)
        // Due later today, not due as a day-scheduled card.
        val counts = repository.getStudyCounts("p1", now = 1000L)
        assertEquals(0, counts.due)
    }

    @Test
    fun editingAQuestionResetsItsSchedule() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 2000L)
        val before = db.cardStateDao().get("p1", "q1")!!
        assertTrue(before.reps >= 2)
        // Change the text, so the stored hash no longer matches.
        repository.saveQuestion(question("q1", setOf("q1-a")).copy(text = "Rewritten"))
        repository.getStudyQueue("p1")
        val after = db.cardStateDao().get("p1", "q1")!!
        assertEquals(0, after.reps)
        assertEquals(0, after.lapses)
    }

    @Test
    fun `a rewritten question is reset once, not on every load`() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q1", setOf("q1-a")).copy(text = "Rewritten"))
        repository.getStudyQueue("p1")
        // The reset must store the new hash, otherwise the next load sees a
        // stale hash again and wipes any progress made since.
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 5000L)
        val progress = db.cardStateDao().get("p1", "q1")!!
        assertTrue(progress.reps >= 1)
        repository.getStudyQueue("p1")
        assertEquals(progress, db.cardStateDao().get("p1", "q1"))
    }

    @Test
    fun historySeedsNewCardsSoAnInstallStartsWarm() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        val questions = repository.getQuestionsForPaper("p1")
        repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = mapOf("q1" to setOf("q1-a")),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = 5000L,
            dwellSeconds = mapOf("q1" to 15L)
        )
        // The seeded card is scheduled for tomorrow, so it is not due yet.
        assertTrue(repository.getStudyQueue("p1", now = 6000L).isEmpty())
        val stored = db.cardStateDao().get("p1", "q1")!!
        assertEquals("history must seed a card, not leave it new", 1, stored.reps)
        assertEquals(5000L + com.mcqapp.domain.SchedulerConfig().let { 24*60*60*1000L }, stored.dueAt)
        // And it becomes due once that date passes.
        val later = 5000L + com.mcqapp.domain.SchedulerConfig().let { 24*60*60*1000L }
        val queue = repository.getStudyQueue("p1", now = later)
        assertEquals(1, queue.size)
        assertEquals(com.mcqapp.domain.StudyReason.DUE, queue.first().reason)
    }

    @Test
    fun skippedAnswersDoNotSeedMemory() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        val questions = repository.getQuestionsForPaper("p1")
        repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = emptyMap(),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = 5000L
        )
        val card = repository.getStudyQueue("p1", now = 6000L).first()
        assertEquals(com.mcqapp.domain.StudyReason.NEW, card.reason)
        assertEquals(0, card.state.reps)
    }

    @Test
    fun ungradedQuestionsDoNotSeedMemory() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", emptySet()))
        val questions = repository.getQuestionsForPaper("p1")
        repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = mapOf("q1" to setOf("q1-a")),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = 5000L
        )
        val card = repository.getStudyQueue("p1", now = 6000L).first()
        assertEquals(0, card.state.reps)
    }

    @Test
    fun historyFromAnotherPaperIsIgnored() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.ensurePaperAndCategory("p2", "Other", "c2", "Cat2")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        val questions = repository.getQuestionsForPaper("p2")
        repository.saveAttempt(
            paperId = "p2",
            paperTitle = "Other",
            questions = questions,
            selections = mapOf("q1" to setOf("q1-a")),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = 5000L
        )
        val card = repository.getStudyQueue("p1", now = 6000L).first()
        assertEquals(0, card.state.reps)
    }

    @Test
    fun bulkDeleteClearsSchedules() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        repository.recordStudyReview("p1", "q2", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        repository.deleteQuestions(listOf("q1", "q2"))
        assertTrue(db.cardStateDao().getByPaper("p1").isEmpty())
    }

    @Test
    fun deletingAQuestionClearsItsAnswerKeyAndBookmark() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.toggleBookmark("q1")
        assertEquals(listOf("q1-a"), db.correctAnswerDao().getCorrectIds("q1"))
        repository.deleteQuestion("q1")
        assertTrue(db.correctAnswerDao().getCorrectIds("q1").isEmpty())
        assertTrue(db.bookmarkDao().getAll().isEmpty())
    }

    @Test
    fun deletingAPaperClearsItsQuestionsBookmarks() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.toggleBookmark("q1")
        repository.deletePaper("p1")
        assertTrue(db.correctAnswerDao().getCorrectIds("q1").isEmpty())
        assertTrue(db.bookmarkDao().getAll().isEmpty())
    }

    @Test
    fun deletingACategoryClearsItsQuestionsBookmarks() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.toggleBookmark("q1")
        repository.deleteCategory("c1")
        assertTrue(db.correctAnswerDao().getCorrectIds("q1").isEmpty())
        assertTrue(db.bookmarkDao().getAll().isEmpty())
    }

    @Test
    fun getByCategoriesMatchesThePerCategoryFetch() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        // A second ensurePaperAndCategory would REPLACE the paper row and
        // cascade-delete c1 (categories.paperId FK), so insert c2 directly.
        db.categoryDao().upsert(CategoryEntity(id = "c2", paperId = "p1", title = "Cat2"))
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a"), categoryId = "c2"))
        repository.saveQuestion(question("q3", setOf("q3-a")))
        val viaPerCategory = listOf("c1", "c2").flatMap { db.questionDao().getByCategory(it) }.map { it.id }
        val viaIn = db.questionDao().getByCategories(listOf("c1", "c2")).map { it.id }
        assertEquals(viaPerCategory, viaIn)
        assertEquals(listOf("q2"), db.questionDao().getByCategories(listOf("c2")).map { it.id })
        // The repository guards the empty-IN case (invalid SQL) before the DAO.
        assertTrue(repository.getQuestionsForCategories(emptyList()).isEmpty())
    }

    @Test
    fun getQuestionsForCategoriesReturnsEveryQuestionOnce() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        // Insert c2 directly: re-upserting the paper would cascade-delete c1.
        db.categoryDao().upsert(CategoryEntity(id = "c2", paperId = "p1", title = "Cat2"))
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        val questions = repository.getQuestionsForCategories(listOf("c1", "c2"))
        assertEquals(setOf("q1", "q2"), questions.map { it.id }.toSet())
        assertTrue(repository.getQuestionsForCategories(emptyList()).isEmpty())
    }

    @Test
    fun observeByIdEmitsOnlyTheTargetQuestion() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        val emission = db.questionDao().observeById("q1").first()
        assertEquals("q1", emission?.id)
        assertEquals(listOf("q1-a"), emission?.let { db.correctAnswerDao().getCorrectIds(it.id) })
    }
}
