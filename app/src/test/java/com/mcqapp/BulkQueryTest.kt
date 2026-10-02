package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
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
 * Bulk reads exist to replace per-item query loops. SQLite also caps bind
 * variables per statement (999 up to API 30; this app supports 26), so the
 * `IN (:ids)` paths must chunk rather than fail on a large bank.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BulkQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun question(id: String, categoryId: String = "c1") = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    @Test
    fun bulkLoadFollowsTheRequestedOrderAndDropsUnknownIds() = runBlocking {
        repository.saveQuestion(question("q1"))
        repository.saveQuestion(question("q2"))
        repository.saveQuestion(question("q3"))

        val loaded = repository.getQuestionsByIds(listOf("q3", "missing", "q1"))
        assertEquals(listOf("q3", "q1"), loaded.map { it.id })
        // Options come back with the question, not as empty shells.
        assertEquals(2, loaded.first().options.size)
    }

    @Test
    fun bulkLoadOfNothingIsEmpty() = runBlocking {
        assertTrue(repository.getQuestionsByIds(emptyList()).isEmpty())
    }

    @Test
    fun bulkLoadCrossesTheBindVariableCeiling() = runBlocking {
        // 1200 ids is past the 999-variable limit the chunked wrappers exist
        // for, so this fails outright without them on API 26-30.
        val ids = (0 until 1200).map { "q$it" }
        ids.forEach { repository.saveQuestion(question(it)) }
        val loaded = repository.getQuestionsByIds(ids)
        assertEquals(1200, loaded.size)
        assertEquals("q0", loaded.first().id)
        assertEquals("q1199", loaded.last().id)
    }

    @Test
    fun bookmarkExportStillCarriesPaperTitles() = runBlocking {
        repository.saveQuestion(question("q1"))
        repository.toggleBookmark("q1")
        val dto = repository.getBookmarkExportDto()!!
        assertEquals("Paper", dto.categories.first().title)
    }

    @Test
    fun aStoredResultKeepsItsCategoryTitle() = runBlocking {
        repository.saveQuestion(question("q1"))
        repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = repository.getQuestionsForPaper("p1"),
            selections = mapOf("q1" to setOf("q1-a")),
            negativeMarking = 0.0,
            durationSeconds = 5,
            finishedAt = 1L
        )
        assertEquals("Cat", repository.getAllQuestionResults().single().categoryTitle)
    }

    @Test
    fun seedingStudyStateIsIdempotent() = runBlocking {
        repository.saveQuestion(question("q1"))
        val first = repository.getStudyCounts("p1")
        val second = repository.getStudyCounts("p1")
        // The transaction wrapped the seeding writes; reading twice must not
        // disturb the counts.
        assertEquals(first.fresh, second.fresh)
        assertEquals(first.due, second.due)
    }
}
