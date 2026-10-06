package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.BookmarkEntity
import com.mcqapp.data.local.countForQuestionsChunked
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.local.getMatchesByContentHashesChunked
import com.mcqapp.data.local.removeAllChunked
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
 * F2: `IN (:ids)` queries overflow SQLite's bind limit (999 up to API 30;
 * minSdk is 26) on large banks. The chunked wrappers cap every statement at
 * 500 bind vars; these prove the wrappers return complete results once the id
 * count forces several chunks — including past the 999 that used to throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChunkedQueriesTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    /** Past both the 500 chunk size and the old 999 bind limit. */
    private val ids = (1..1200).map { "cq$it" }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        ids.forEach { id ->
            repository.saveQuestion(
                Question(
                    id = id,
                    categoryId = "c1",
                    text = "Q $id",
                    options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
                    correctOptionIds = setOf("$id-a")
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `question lookup survives several chunks`() = runBlocking {
        val found = db.questionDao().getByIdsChunked(ids)
        assertEquals(ids.size, found.size)
        assertEquals(ids.toSet(), found.map { it.id }.toSet())
    }

    @Test
    fun `option lookup survives several chunks`() = runBlocking {
        val found = db.optionDao().getForQuestionsChunked(ids)
        assertEquals(ids.size * 2, found.size)
    }

    @Test
    fun `hash matching survives several chunks`() = runBlocking {
        val hashes = db.questionDao().getByIdsChunked(ids).map { it.contentHash }
        assertEquals(ids.size, hashes.size)
        val matches = db.questionDao().getMatchesByContentHashesChunked(hashes)
        assertEquals(ids.size, matches.size)
    }

    @Test
    fun `bookmark count and removal survive several chunks`() = runBlocking {
        val bookmarked = ids.take(700)
        bookmarked.forEach { db.bookmarkDao().add(BookmarkEntity(it)) }
        assertEquals(700, db.bookmarkDao().countForQuestionsChunked(ids))
        db.bookmarkDao().removeAllChunked(ids)
        assertEquals(0, db.bookmarkDao().countForQuestionsChunked(ids))
    }

    @Test
    fun `re-importing a 1200-question paper touches the chunked collision path`() = runBlocking {
        val dqids = (1..1200).map { "dq$it" }
        val questions = dqids.map { id ->
            com.mcqapp.data.io.QuestionDto(
                id = id,
                text = "Q $id",
                options = listOf(
                    com.mcqapp.data.io.OptionDto(id = "$id-a", text = "A"),
                    com.mcqapp.data.io.OptionDto(id = "$id-b", text = "B")
                ),
                correctOptionIds = listOf("$id-a")
            )
        }
        val file = McqFileDto(
            papers = listOf(
                com.mcqapp.data.io.PaperDto(
                    id = "p2",
                    title = "Paper Two",
                    categories = listOf(
                        com.mcqapp.data.io.CategoryDto(id = "c2", title = "Cat Two", questions = questions)
                    )
                )
            )
        )
        val first = Importer(db, updateAnswersOnDuplicate = false).import(file)
        assertEquals(1200, first.newQuestions)
        val report = Importer(db, updateAnswersOnDuplicate = false).import(file)
        // All duplicates: nothing new, nothing lost, no bind-limit crash.
        assertEquals(1200, report.duplicateQuestions)
        assertEquals(0, report.newQuestions)
        assertEquals(0, report.updatedQuestions)
        assertEquals(0, report.newQuestions)
        assertTrue(db.questionDao().getByIdsChunked(ids).size == 1200)
    }
}
