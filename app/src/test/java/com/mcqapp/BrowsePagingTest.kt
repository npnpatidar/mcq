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
 * Browse pages its refs through Room instead of materialising the whole paper.
 * These pin the paging list contract: the `(category order, question order)`
 * sequence is the same as the old whole-list read, and the chip/category/text
 * filters select the same rows the in-memory filters used to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowsePagingTest {

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

    private suspend fun source(
        query: String = "",
        filter: BrowseListFilter = BrowseListFilter.ALL,
        categoryFilterIds: Set<String>? = null
    ): PagingSource<Int, QuestionRef> = repository.browseRefsSource(
        McqRepository.BrowseScope.of(
            paperId = "p1",
            query = query,
            filter = filter,
            categoryFilterIds = categoryFilterIds
        )
    )

    /** Walks every page at a small size, crossing window boundaries. */
    private suspend fun walk(factory: suspend () -> PagingSource<Int, QuestionRef>): List<String> =
        walkPaging(2, factory) { key ->
            if (key == null) PagingSource.LoadParams.Refresh(null, 2, false)
            else PagingSource.LoadParams.Append(key, 2, false)
        }.map { it.id }

    @Test
    fun pagesPreserveTheCategoryAndQuestionOrder() = runBlocking {
        save("q3", "c1", "Gamma"); save("q1", "c1", "Alpha"); save("q2", "c1", "Beta")
        save("q5", "c2", "Epsilon"); save("q4", "c2", "Delta")
        assertEquals(listOf("q3", "q1", "q2", "q5", "q4"), walk { source() })
    }

    @Test
    fun textQueryKeepsMatchingRowsInOrder() = runBlocking {
        save("q1", "c1", "Alpha"); save("q2", "c1", "Beta"); save("q3", "c2", "Alpha beta")
        assertEquals(listOf("q1", "q3"), walk { source(query = "Alpha") })
    }

    @Test
    fun theNoAnswerChipKeepsOnlyKeylessQuestions() = runBlocking {
        save("q1", "c1", "Alpha", withKey = true)
        save("q2", "c1", "Beta", withKey = false)
        save("q3", "c2", "Gamma", withKey = false)
        assertEquals(listOf("q2", "q3"), walk { source(filter = BrowseListFilter.NO_ANSWER) })
    }

    @Test
    fun theCategoryFilterRestrictsToTheGivenCategories() = runBlocking {
        save("q1", "c1", "Alpha"); save("q2", "c2", "Beta")
        assertEquals(
            listOf("q1"),
            walk { source(categoryFilterIds = setOf("c1")) }
        )
    }
}
