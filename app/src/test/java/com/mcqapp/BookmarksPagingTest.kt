package com.mcqapp

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.BookmarkEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.BookmarkedQuestion
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bookmarks page instead of loading every bookmarked question's content at
 * once. These pin the list contract: newest bookmark first, paired with its
 * paper id, with content only on the requested pages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookmarksPagingTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
        runBlocking { repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat") }
    }

    @After
    fun tearDown() = db.close()

    private fun saveQuestion(id: String) = runBlocking {
        repository.saveQuestion(
            Question(
                id = id,
                categoryId = "c1",
                elements = listOf(ContentElement.TextElement("Q $id")),
                options = listOf(QuestionOption("a", "A")),
                correctOptionIds = setOf("a")
            )
        )
    }

    private fun bookmark(id: String, createdAt: Long) = runBlocking {
        db.bookmarkDao().add(BookmarkEntity(id, createdAt))
    }

    private suspend fun walk(): List<BookmarkedQuestion> =
        walkPaging(2, { repository.bookmarkedPagingSource() }) { key ->
            if (key == null) PagingSource.LoadParams.Refresh(null, 2, false)
            else PagingSource.LoadParams.Append(key, 2, false)
        }

    @Test
    fun newestBookmarkFirstAcrossPages() = runBlocking {
        saveQuestion("q1"); saveQuestion("q2"); saveQuestion("q3")
        bookmark("q1", 3L); bookmark("q2", 1L); bookmark("q3", 2L)
        val rows = walk()
        assertEquals(listOf("q1", "q3", "q2"), rows.map { it.question.id })
        assertEquals(listOf("p1", "p1", "p1"), rows.map { it.paperId })
        assertEquals("Q q1", rows.first().question.text)
    }

    /**
     * The screen reads a number, not the ids: a count is one `COUNT(*)` and a
     * refresh needs only to know that the table moved.
     */
    @Test
    fun theCountIsOneNumberAndTheChangeSignalIsPerWrite() = runBlocking {
        saveQuestion("q1"); saveQuestion("q2")
        assertEquals(0, repository.observeBookmarkCount().first())

        val signals = java.util.concurrent.atomic.AtomicInteger()
        val collector = launch {
            repository.observeBookmarkChanges().collect { signals.incrementAndGet() }
        }
        try {
            delay(100)

            bookmark("q1", 1L)
            awaitAtLeast(signals, 1)
            assertEquals(1, repository.observeBookmarkCount().first())

            bookmark("q2", 2L)
            awaitAtLeast(signals, 2)
            assertEquals(2, repository.observeBookmarkCount().first())

            // Toggling back off is a write too, and the number follows.
            repository.toggleBookmark("q1")
            awaitAtLeast(signals, 3)
            assertEquals(1, repository.observeBookmarkCount().first())
        } finally {
            collector.cancel()
        }
    }

    private suspend fun awaitAtLeast(signals: java.util.concurrent.atomic.AtomicInteger, n: Int) {
        withTimeout(10_000) {
            while (signals.get() < n) delay(10)
        }
    }
}
