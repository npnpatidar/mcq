package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.BookmarkEntity
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.CorrectAnswerEntity
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.QuestionStatsEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.restoreQuestions
import com.mcqapp.data.repository.snapshotQuestions
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

/**
 * Undo of a destructive delete must restore every dependent row — schedule,
 * review history, lifetime stats, bookmark, passage membership — and must not
 * clobber a later write that recreated the same id before Undo was tapped.
 * Robolectric needs a real SQLite driver, so this test runs on CI (x86_64).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestionDeleteUndoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper"))
            db.categoryDao().insertIgnore(CategoryEntity(id = "c1", paperId = "p1", title = "Category"))
        }
    }

    @After
    fun tearDown() = db.close()

    private fun question(id: String, text: String, passage: String? = null) = QuestionEntity(
        id = id, categoryId = "c1", passageId = passage, text = text,
        searchText = text, searchTextFolded = text.lowercase(), tagsFolded = ""
    )

    private fun seedFullQuestion(id: String, passage: String? = null) = runBlocking {
        db.questionDao().upsert(question(id, "original stem", passage))
        db.optionDao().upsertAll(
            listOf(OptionEntity(id, "o1", "choice", "choice", "choice"))
        )
        db.correctAnswerDao().upsertAll(listOf(CorrectAnswerEntity(id, "o1")))
        db.cardStateDao().upsert(
            CardStateEntity("p1", id, 2.5, 5, 1_000L, 3, 1, false, 900L, "hash")
        )
        db.reviewLogDao().insert(ReviewLogEntity(paperId = "p1", questionId = id, rating = 3, reviewedAt = 900L))
        db.questionStatsDao().insertAll(
            listOf(
                QuestionStatsEntity(
                    questionId = id, paperId = "p1", paperTitle = "Paper",
                    categoryTitle = "Category", text = "original stem",
                    totalAttempts = 4, gradedCount = 3, correctCount = 2, wrongCount = 1,
                    skipCount = 0, ungradedCount = 1, lastResultIsCorrect = true,
                    lastFinishedAt = 900L
                )
            )
        )
        db.bookmarkDao().add(BookmarkEntity(id, 800L))
    }

    private fun delete(id: String) = runBlocking {
        db.questionDao().deleteById(id)
        db.cardStateDao().deleteByQuestion(id)
        db.reviewLogDao().deleteByQuestion(id)
        db.questionStatsDao().deleteByQuestion(id)
        db.bookmarkDao().remove(id)
    }

    @Test
    fun restoreBringsBackEveryDependentRow() = runBlocking {
        seedFullQuestion("q1", passage = "passage-1")
        val snapshot = db.snapshotQuestions(setOf("q1"))
        assertEquals(1, snapshot.size)
        delete("q1")
        assertNull(db.questionDao().getById("q1"))

        assertEquals(1, db.restoreQuestions(snapshot))
        assertNotNull(db.questionDao().getById("q1"))
        assertEquals("passage-1", db.questionDao().getById("q1")!!.passageId)
        assertEquals(1, db.optionDao().getByQuestion("q1").size)
        assertEquals(1, db.correctAnswerDao().getCorrectIds("q1").size)
        assertEquals(1, db.cardStateDao().getForQuestions(listOf("q1")).size)
        assertEquals(1, db.reviewLogDao().getByQuestions(listOf("q1")).size)
        assertEquals(1, db.questionStatsDao().getByQuestions(listOf("q1")).size)
        assertEquals(1, db.bookmarkDao().getForQuestions(listOf("q1")).size)
    }

    @Test
    fun undoAfterALaterWriteDoesNotClobberTheNewRow() = runBlocking {
        seedFullQuestion("q1")
        val snapshot = db.snapshotQuestions(setOf("q1"))
        delete("q1")

        // A later write (an import or a re-created card) lands before Undo.
        db.questionDao().upsert(question("q1", "newer content"))
        db.bookmarkDao().add(BookmarkEntity("q1", 5_000L))

        assertEquals(0, db.restoreQuestions(snapshot))
        assertEquals("newer content", db.questionDao().getById("q1")!!.searchText)
        assertEquals(0, db.cardStateDao().getForQuestions(listOf("q1")).size)
        assertEquals(0, db.reviewLogDao().getByQuestions(listOf("q1")).size)
        assertEquals(5_000L, db.bookmarkDao().getForQuestions(listOf("q1")).first().createdAt)
    }

    @Test
    fun aSecondUndoIsANoOp() = runBlocking {
        seedFullQuestion("q1")
        val snapshot = db.snapshotQuestions(setOf("q1"))
        delete("q1")
        assertEquals(1, db.restoreQuestions(snapshot))
        assertEquals(0, db.restoreQuestions(snapshot))
    }
}
