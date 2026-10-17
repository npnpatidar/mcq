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
import kotlinx.coroutines.runBlocking
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
}
