package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.history.BookmarksViewModel
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Bookmarks trash button drops a bookmark, never the question: the owner
 * tapped it expecting the question to disappear and then found it still in
 * Study. These pin the owner's expectation — after a removal the question is
 * still one of the paper's questions, and only the bookmark is gone — plus the
 * Undo path that puts the bookmark back without toggling a live one off.
 *
 * Robolectric needs a real SQLite driver, so the database cases only run on
 * x86_64; CI is the authority.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class BookmarkRemovalKeepsQuestionTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(
                Question(
                    id = "q1",
                    categoryId = "c1",
                    text = "Q1",
                    options = listOf(QuestionOption("a", "A")),
                    correctOptionIds = setOf("a")
                )
            )
        }
    }

    @After
    fun tearDown() {
        try {
            db.close()
        } finally {
            // The ViewModel case uses the process-wide database; drop it so no
            // later class inherits this fixture.
            AppDatabase::class.java
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .set(null, null)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun paperQuestionIds(): List<String> = runBlocking {
        repository.getQuestionsForPaper("p1").map { it.id }
    }

    private fun bookmarkCount(): Int = runBlocking { repository.observeBookmarkCount().first() }

    /** The owner's exact expectation: dropping the bookmark keeps the question. */
    @Test
    fun removingTheBookmarkKeepsTheQuestionInThePaper() {
        runBlocking { repository.toggleBookmark("q1") }
        assertEquals(listOf("q1"), paperQuestionIds())
        assertEquals(1, bookmarkCount())

        runBlocking { repository.deleteBookmark("q1") }

        assertEquals(
            "the question itself must survive the bookmark removal",
            listOf("q1"),
            paperQuestionIds()
        )
        assertFalse("only the bookmark is gone", runBlocking { repository.isBookmarked("q1") })
        assertEquals(0, bookmarkCount())
    }

    /** Undo adds the row back, and back exactly once. */
    @Test
    fun togglingBookmarkAgainRestoresItExactlyOnce() {
        runBlocking { repository.toggleBookmark("q1") }
        runBlocking { repository.deleteBookmark("q1") }
        assertEquals(0, bookmarkCount())

        runBlocking { repository.toggleBookmark("q1") }

        assertTrue(runBlocking { repository.isBookmarked("q1") })
        assertEquals("a restore must not write a second bookmark row", 1, bookmarkCount())
        assertEquals(listOf("q1"), paperQuestionIds())
    }

    /**
     * The Undo path is guarded, not a blind toggle: restoring a bookmark that
     * is already live must leave it live rather than switch it off.
     */
    @Test
    fun guardedRestoreOfALiveBookmarkLeavesItBookmarked() {
        runBlocking { repository.toggleBookmark("q1") }

        // The same guard BookmarksViewModel.restoreBookmark applies.
        runBlocking {
            if (!repository.isBookmarked("q1")) repository.toggleBookmark("q1")
        }

        assertTrue(runBlocking { repository.isBookmarked("q1") })
        assertEquals(1, bookmarkCount())
    }

    /**
     * The ViewModel half of the undo, against the real repository: remove, then
     * restore, then restore again — the second restore must stay a no-op.
     */
    @Test
    fun restoreBookmarkReAddsAfterRemoveBookmark() {
        val app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
        val real = app.repository
        runBlocking {
            real.ensurePaperAndCategory("pv", "Paper", "cv", "Cat")
            real.saveQuestion(
                Question(
                    id = "qv",
                    categoryId = "cv",
                    text = "QV",
                    options = listOf(QuestionOption("a", "A")),
                    correctOptionIds = setOf("a")
                )
            )
            real.toggleBookmark("qv")
        }

        val viewModel = BookmarksViewModel(app)
        viewModel.removeBookmark("qv")
        assertTrue(
            "the removal must land",
            awaitIdle { !runBlocking { real.isBookmarked("qv") } }
        )

        viewModel.restoreBookmark("qv")
        assertTrue(
            "Undo puts the bookmark back",
            awaitIdle { runBlocking { real.isBookmarked("qv") } }
        )

        // A second Undo finds the bookmark live and must not toggle it off.
        viewModel.restoreBookmark("qv")
        settle()
        assertTrue(runBlocking { real.isBookmarked("qv") })

        assertEquals(
            "removing a bookmark never removes the question",
            listOf("qv"),
            runBlocking { real.getQuestionsForPaper("pv").map { it.id } }
        )
    }

    private fun settle() {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitIdle(condition: () -> Boolean): Boolean {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }
}
