package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.StudyReason
import kotlinx.coroutines.flow.first
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
 * The library re-reads the study counts on every resume, so they are counted
 * from the questions and their schedules in SQL rather than by resolving every
 * question's card state — a model of a million-question paper is hundreds of
 * megabytes, and resolving it also used to write a schedule row for every
 * question it had not seen.
 *
 * Two things have to stay true for that to be safe. Reading the badge must not
 * write anything, so a browse through the library cannot leave a million rows
 * behind. And scheduling still compares a stored card's hash against the
 * question's to notice an edit and reschedule from scratch, so the hash the
 * grading path writes has to be the one the question path derives — a
 * different one would make every card look edited and reset it, on every
 * resume, for every question, silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyCountsInputTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
    }

    @After
    fun tearDown() = db.close()

    private fun question(id: String, categoryId: String, text: String) = Question(
        id = id,
        categoryId = categoryId,
        text = text,
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    /**
     * The hash the question row carries, together with the one the scheduling
     * path derives from the loaded question. The row is the source of truth —
     * the importer and `saveQuestion` both write it — so the two must agree
     * before the card can be compared against either.
     */
    private suspend fun expectedHash(id: String): String {
        val question = repository.getQuestionsForPaper("p1").first { it.id == id }
        val row = db.questionDao().getById(id)!!.contentHash
        assertEquals("the row and the loaded question must hash alike", row, ContentHash.of(question))
        return row
    }

    @Test
    fun gradingStoresTheHashTheQuestionPathWould() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "root", "Root")
        val childId = repository.addCategory("p1", "Child", parentId = "root")
        repository.saveQuestion(question("q1", "root", "First question?"))
        repository.saveQuestion(question("q2", childId, "Second question?"))

        repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = 1_000L)

        val stored = db.cardStateDao().get("p1", "q1")
        assertNotNull("grading writes the card's schedule", stored)
        assertEquals(
            "a drifting hash would make the card look edited on every resume",
            expectedHash("q1"),
            stored!!.contentHash
        )
    }

    /**
     * The shape that made Study look stuck: an option whose picture lives in
     * the elements (the bundled bank's `q-vis-*`), with no scalar mirror. The
     * importer's hash and the study path's used to disagree on exactly those,
     * so every pull reset the card as edited and served that same question
     * again while the review counter climbed.
     */
    @Test
    fun aFailedQuestionWithAnOptionPictureDoesNotComeStraightBack() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(
            Question(
                id = "qvis",
                categoryId = "c1",
                elements = listOf(ContentElement.TextElement("Which two shapes are quadrilaterals?")),
                options = listOf(
                    QuestionOption(
                        "qvis-a",
                        listOf(
                            ContentElement.ImageElement("rect.png"),
                            ContentElement.TextElement(" Rectangle")
                        )
                    ),
                    QuestionOption("qvis-b", "Circle")
                ),
                correctOptionIds = setOf("qvis-a")
            )
        )
        repository.saveQuestion(question("q2", "c1", "Second question?"))

        val first = repository.getNextStudyCard("p1", now = 1_000L)
        assertEquals("the first card in authored order", "qvis", first!!.questionId)
        repository.recordStudyReview("p1", "qvis", ReviewGrade.AGAIN, now = 1_000L)

        val stored = db.cardStateDao().get("p1", "qvis")
        assertNotNull("grading writes the card's schedule", stored)
        assertEquals(
            "a picture in the elements must not make the card look edited",
            expectedHash("qvis"),
            stored!!.contentHash
        )
        assertEquals(
            "a failed card waits for its relearn step, so the session moves on",
            "q2",
            repository.getNextStudyCard("p1", now = 1_000L)!!.questionId
        )
    }

    @Test
    fun readingTheBadgeWritesNoSchedules() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", "c1", "Stable question?"))

        val first = repository.getStudyCounts("p1")
        val second = repository.getStudyCounts("p1")

        assertEquals("counts must be stable", first, second)
        assertEquals("nobody has been studied yet, so it is new", 1, first.fresh)
        assertTrue(
            "a badge read must not write a schedule for a question nobody studied",
            db.cardStateDao().getByPaper("p1").isEmpty()
        )
    }

    @Test
    fun anEditedQuestionIsNewAgainUntilItIsGradedOnceMore() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", "c1", "Before the edit?"))
        repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = 1_000L)
        // Scheduled into the future by the grade, so with no edit it is neither
        // due nor new.
        assertEquals(0, repository.getStudyCounts("p1", now = 1_000L).fresh)

        repository.saveQuestion(question("q1", "c1", "After the edit?"))

        assertEquals(
            "the stored hash described other text, so the card is new again",
            1,
            repository.getStudyCounts("p1", now = 1_000L).fresh
        )
        val card = repository.getNextStudyCard("p1", now = 1_000L)
        assertNotNull("an edited card is offered again", card)
        assertEquals("q1", card!!.questionId)
        assertEquals(
            "and it is offered as new material, exactly as a reset row would",
            StudyReason.NEW,
            card.reason
        )
    }

    @Test
    fun anEmptyPaperCountsNothing() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        val counts = repository.getStudyCounts("p1")
        assertEquals(0, counts.due)
        assertEquals(0, counts.fresh)
        assertEquals(0, counts.leeches)
        assertTrue(db.cardStateDao().getByPaper("p1").isEmpty())
    }

    /**
     * The badge has to describe the session the Study button opens. With no
     * daily caps, every eligible card is both counted and served — the badge
     * can never promise a remainder the queue then withholds.
     */
    @Test
    fun theBadgeAgreesWithTheQueueItAdvertises() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repeat(6) { repository.saveQuestion(question("q$it", "c1", "Q $it")) }

        val counts = repository.getStudyCounts("p1", now = 1_000_000L)
        val queue = repository.getStudyQueue("p1", now = 1_000_000L)
        assertEquals(
            "the badge must not promise more cards than the queue serves",
            queue.size,
            counts.due + counts.leeches + counts.fresh
        )
        assertEquals("every new card is counted", 6, counts.fresh)
        assertEquals("and served", 6, queue.size)
    }

    @Test
    fun aPaperWithNoQuestionsCountsNothing() = runBlocking {
        repository.ensurePaperAndCategory("empty", "Empty", "c", "Cat")
        assertEquals(0, repository.getStudyCounts("empty").due)
    }
}
