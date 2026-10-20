package com.mcqapp

import android.content.Context
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
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

    /**
     * The list pages by cursor, not by OFFSET: deleting a bookmark that sorts
     * AHEAD of an already-issued cursor (the window between page 1's end and
     * page 2's cursor used the row count, so a removed row slid every later
     * page one slot) must not shift the remaining pages. Page 2's cursor is
     * the last row it carried (createdAt 2, "q2"); deleting q3 (createdAt 3,
     * strictly newer) leaves page 3 = q1 under keyset, while the OFFSET
     * implementation recomputed OFFSET 4 over the shortened list and delivered
     * an empty page — q1 silently vanished from the scrolled list.
     *
     * Room/SQLite — CI is the authority. Mutant: restore the OFFSET query and
     * `nextKey = offset + loadSize` — this test goes red on the walk's total.
     */
    @Test
    fun deletingAheadOfTheCursorDoesNotShiftFollowingPages() = runBlocking {
        saveQuestion("q1"); saveQuestion("q2"); saveQuestion("q3"); saveQuestion("q4"); saveQuestion("q5")
        bookmark("q1", 1L); bookmark("q2", 2L); bookmark("q3", 3L); bookmark("q4", 4L); bookmark("q5", 5L)

        val source = repository.bookmarkedPagingSource()
        val page1 = source.load(PagingSource.LoadParams.Refresh(null, 2, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("q5", "q4"), page1.data.map { it.question.id })
        val page2 = source.load(PagingSource.LoadParams.Append(page1.nextKey!!, 2, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("q3", "q2"), page2.data.map { it.question.id })

        repository.toggleBookmark("q3")

        val page3 = source.load(PagingSource.LoadParams.Append(page2.nextKey!!, 2, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("q1"), page3.data.map { it.question.id })
        assertEquals(
            "the scrolled list must keep every undeleted bookmark, none skipped or duplicated",
            listOf("q5", "q4", "q3", "q2", "q1").filter { it != "q3" },
            (page1 + page2 + page3).map { it.question.id }
        )
    }

    /**
     * getRefreshKey is coherent: an invalidation while the reader stands at
     * the second page re-loads from that page's own cursor instead of
     * scrolling the list back to the newest bookmark. Previously it was null,
     * meaning the screen restarted from the top on every refresh signal.
     */
    @Test
    fun aRefreshBackedTheReachedPositionResumesFromItsCursor() = runBlocking {
        saveQuestion("q1"); saveQuestion("q2"); saveQuestion("q3"); saveQuestion("q4")
        bookmark("q1", 1L); bookmark("q2", 2L); bookmark("q3", 3L); bookmark("q4", 4L)

        val source = repository.bookmarkedPagingSource()
        val page1 = source.load(PagingSource.LoadParams.Refresh(null, 2, false)) as PagingSource.LoadResult.Page
        val page2 = source.load(PagingSource.LoadParams.Append(page1.nextKey!!, 2, false)) as PagingSource.LoadResult.Page

        val state = PagingState(
            pages = listOf(page1, page2),
            anchorPosition = 2, // the head of page 2: the reader has reached q3
            config = PagingConfig(pageSize = 2),
            leadingPlaceholderCount = 0
        )
        assertEquals(page1.nextKey, source.getRefreshKey(state))
    }
}
