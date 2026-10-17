package com.mcqapp

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.repository.BrowseListFilter
import com.mcqapp.data.repository.McqRepository
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
 * The editor's browse cursor (size, anchor index, id-at-index) must describe
 * exactly the order the paging list shows, so Prev/Next and "N of M" agree
 * with what the reader sees — without ever materialising the filtered list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseCursorTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
        runBlocking {
            db.paperDao().upsert(PaperEntity(id = "p1", title = "Paper"))
            db.categoryDao().upsert(CategoryEntity(id = "c1", paperId = "p1", title = "Alpha", sortOrder = 0))
            db.categoryDao().upsert(CategoryEntity(id = "c2", paperId = "p1", title = "Beta", sortOrder = 1))
        }
    }

    @After
    fun tearDown() = db.close()

    private fun save(id: String, categoryId: String, text: String, withKey: Boolean = true) =
        runBlocking {
            repository.saveQuestion(
                Question(
                    id = id,
                    categoryId = categoryId,
                    elements = listOf(ContentElement.TextElement(text)),
                    options = listOf(QuestionOption("a", "A")),
                    correctOptionIds = if (withKey) setOf("a") else emptySet()
                )
            )
        }

    private fun scope(
        query: String = "",
        filter: BrowseListFilter = BrowseListFilter.ALL,
        categoryFilterIds: Set<String>? = null
    ): McqRepository.BrowseScope = McqRepository.BrowseScope.of(
        paperId = "p1",
        query = query,
        filter = filter,
        categoryFilterIds = categoryFilterIds
    )

    private suspend fun pagedOrder(scope: McqRepository.BrowseScope): List<String> =
        walkPaging(2, { repository.browseRefsSource(scope) }) { key ->
            if (key == null) PagingSource.LoadParams.Refresh(null, 2, false)
            else PagingSource.LoadParams.Append(key, 2, false)
        }.map { it.id }

    @Test
    fun theCursorMatchesThePagedOrder() = runBlocking {
        save("q3", "c1", "Gamma"); save("q1", "c1", "Alpha"); save("q2", "c1", "Beta")
        save("q5", "c2", "Epsilon"); save("q4", "c2", "Delta")
        val scope = scope()
        val expected = pagedOrder(scope)
        assertEquals(expected.size, repository.browseFilteredCount(scope))
        expected.forEachIndexed { index, id ->
            assertEquals("id at $index", id, repository.browseFilteredIdAt(index, scope))
            assertEquals("index of $id", index, repository.browseFilteredIndexOf(id, scope))
        }
        assertEquals(null, repository.browseFilteredIdAt(expected.size, scope))
    }

    @Test
    fun aScopeWithNoMatchesHasNoCursor() = runBlocking {
        save("q1", "c1", "Alpha")
        val scope = scope(query = "zzz")
        assertEquals(0, repository.browseFilteredCount(scope))
        assertEquals(null, repository.browseFilteredIdAt(0, scope))
    }

    @Test
    fun theNoAnswerChipNarrowsTheCursorScope() = runBlocking {
        save("q1", "c1", "Alpha", withKey = true)
        save("q2", "c1", "Beta", withKey = false)
        save("q3", "c2", "Gamma", withKey = false)
        val scope = scope(filter = BrowseListFilter.NO_ANSWER)
        assertEquals(listOf("q2", "q3"), pagedOrder(scope))
        assertEquals(2, repository.browseFilteredCount(scope))
        assertEquals(0, repository.browseFilteredIndexOf("q2", scope))
        assertEquals(1, repository.browseFilteredIndexOf("q3", scope))
        assertEquals("q3", repository.browseFilteredIdAt(1, scope))
    }

    @Test
    fun aMissingAnchorIndexesToTheStartAndIsCaughtByTheIdCheck() = runBlocking {
        save("q1", "c1", "Alpha")
        val scope = scope()
        // The count-before of an absent anchor is 0; the deep-link check
        // compares the id at that index so it can tell "first" from "absent".
        assertEquals(0, repository.browseFilteredIndexOf("missing", scope))
        assertEquals("q1", repository.browseFilteredIdAt(0, scope))
    }
}
