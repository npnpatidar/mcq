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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "my mistakes" pool is written by one transaction
 * ([McqRepository.addTagToQuestions]) instead of a write per question. Pinned
 * here because the pool spans many rows and a half-tagged bank is exactly the
 * state the single transaction exists to prevent.
 *
 * Robolectric opens a real SQLite database, so this runs on x86_64 CI only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MistakesPoolTagTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", listOf("Algebra")))
        repository.saveQuestion(question("q2", emptyList()))
        repository.saveQuestion(question("q3", listOf("Algebra", "Geometry")))
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun question(id: String, tags: List<String>) = Question(
        id = id,
        categoryId = "c1",
        text = "Q $id",
        options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
        correctOptionIds = setOf("a"),
        tags = tags
    )

    @Test
    fun everyQuestionGetsTheTagAndKeepsItsOwn() = runBlocking {
        val changed = repository.addTagToQuestions(listOf("q1", "q2", "q3"), POOL)

        assertEquals("all three rows should be tagged", 3, changed)
        val byId = repository.getQuestionsByIds(listOf("q1", "q2", "q3")).associateBy { it.id }
        assertEquals(listOf("Algebra", POOL), byId.getValue("q1").tags)
        assertEquals(listOf(POOL), byId.getValue("q2").tags)
        assertEquals(listOf("Algebra", "Geometry", POOL), byId.getValue("q3").tags)
    }

    @Test
    fun reTaggingIsIdempotentAndAddsNoDuplicate() = runBlocking {
        repository.addTagToQuestions(listOf("q1"), POOL)
        val again = repository.addTagToQuestions(listOf("q1", "q2"), POOL)

        // q1 already carries it; only q2 changes.
        assertEquals(1, again)
        assertEquals(listOf("Algebra", POOL), repository.getQuestion("q1")!!.tags)
        assertFalse(
            "the same tag must not be written twice",
            repository.getQuestion("q1")!!.tags.count { it == POOL } > 1
        )
    }

    @Test
    fun theTagIndexFindsTheWholePool() = runBlocking {
        repository.addTagToQuestions(listOf("q1", "q2", "q3"), POOL)

        // The exact-tag index (and its folded twin) is what the Study scope
        // query reads, so the transaction must leave it consistent.
        val pooled = repository.questionIdsForStudyTagInScope(
            "p1", emptyList(), POOL.lowercase()
        )
        assertEquals(setOf("q1", "q2", "q3"), pooled.toSet())
        assertTrue(pooled.all { it in setOf("q1", "q2", "q3") })
    }

    private companion object {
        const val POOL = "my mistakes"
    }
}
