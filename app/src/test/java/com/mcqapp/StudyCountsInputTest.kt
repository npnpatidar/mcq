package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.flow.first
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
 * The library re-reads the study counts on every resume, so they are counted
 * from ids and content hashes rather than by loading every question with its
 * options and answer key.
 *
 * The risk in doing that is the hash. Scheduling compares a stored card's hash
 * against the question's to notice an edit and reschedule from scratch, so a
 * hash computed differently here would make every card look edited and reset it
 * — on every resume, for every question, silently. These tests pin the hash to
 * the same value the question path produces.
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

    /** The hash the scheduling path derives from a loaded question. */
    private suspend fun expectedHash(id: String): String =
        repository.getQuestionsForPaper("p1").first { it.id == id }.let { q ->
            ContentHash.of(q.text, q.options.map { it.text }, q.options.map { it.image })
        }

    @Test
    fun countingSchedulesTheSameHashTheQuestionPathWould() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "root", "Root")
        val childId = repository.addCategory("p1", "Child", parentId = "root")
        repository.saveQuestion(question("q1", "root", "First question?"))
        repository.saveQuestion(question("q2", childId, "Second question?"))

        repository.getStudyCounts("p1")

        val stored = db.cardStateDao().getByPaper("p1").associateBy { it.questionId }
        assertEquals("both questions, nested one included", 2, stored.size)
        assertEquals(expectedHash("q1"), stored.getValue("q1").contentHash)
        assertEquals(expectedHash("q2"), stored.getValue("q2").contentHash)
    }

    @Test
    fun countingTwiceDoesNotRescheduleAnything() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", "c1", "Stable question?"))

        val first = repository.getStudyCounts("p1")
        val rowsAfterFirst = db.cardStateDao().getByPaper("p1")
        val second = repository.getStudyCounts("p1")
        val rowsAfterSecond = db.cardStateDao().getByPaper("p1")

        assertEquals("counts must be stable", first, second)
        assertEquals(
            "a drifting hash would reseed the card on every resume",
            rowsAfterFirst,
            rowsAfterSecond
        )
    }

    @Test
    fun anEditedQuestionIsStillRescheduledFromScratch() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", "c1", "Before the edit?"))
        repository.getStudyCounts("p1")
        val original = db.cardStateDao().getByPaper("p1").single()

        repository.saveQuestion(question("q1", "c1", "After the edit?"))
        repository.getStudyCounts("p1")
        val afterEdit = db.cardStateDao().getByPaper("p1").single()

        assertEquals(
            "the new content hash must be persisted",
            expectedHash("q1"),
            afterEdit.contentHash
        )
        assertTrue(
            "the old hash described different text, so the card must reset",
            original.contentHash != afterEdit.contentHash
        )
        // A reset card is new again, so it shows up as fresh.
        assertEquals(1, repository.getStudyCounts("p1").fresh)
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
     * The badge has to describe the session the Study button opens, so the
     * counts are pinned against the queue that the same limits produce.
     */
    @Test
    fun theBadgeAgreesWithTheQueueItAdvertises() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.setSchedulerConfig(
            com.mcqapp.domain.SchedulerConfig(newLimit = 2, reviewLimit = 3)
        )
        repeat(6) { repository.saveQuestion(question("q$it", "c1", "Q $it")) }

        val counts = repository.getStudyCounts("p1", now = 1_000_000L)
        val queue = repository.getStudyQueue("p1", now = 1_000_000L)
        assertEquals(
            "the badge must not promise more cards than the queue serves",
            queue.size,
            counts.due + counts.leeches + counts.fresh
        )
        assertEquals("the new limit must cap the badge", 2, counts.fresh)
        assertEquals("the rest are reported as waiting", 4, counts.freshWaiting)
        assertEquals(4, counts.waiting)
    }

    @Test
    fun aPaperWithNoQuestionsCountsNothing() = runBlocking {
        repository.ensurePaperAndCategory("empty", "Empty", "c", "Cat")
        assertEquals(0, repository.getStudyCounts("empty").due)
    }
}