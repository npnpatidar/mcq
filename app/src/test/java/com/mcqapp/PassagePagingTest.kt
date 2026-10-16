package com.mcqapp

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionRef
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
 * A passage's members page straight from Room. These pin the contract the
 * screen relies on: the paged order matches the old whole-list read, another
 * passage's members never leak in, and the Prev/Next id list is the same
 * order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassagePagingTest {

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
        }
    }

    @After
    fun tearDown() = db.close()

    private fun save(id: String, passageId: String, text: String) = runBlocking {
        repository.saveQuestion(
            Question(
                id = id,
                categoryId = "c1",
                elements = listOf(ContentElement.TextElement(text)),
                options = listOf(QuestionOption("a", "A")),
                correctOptionIds = setOf("a"),
                passageId = passageId
            )
        )
    }

    private suspend fun walk(source: PagingSource<Int, QuestionRef>): List<String> {
        val ids = mutableListOf<String>()
        var key: Int? = null
        while (true) {
            val result = source.load(PagingSource.LoadParams.Refresh(key, 2, false))
            val page = result as PagingSource.LoadResult.Page
            ids += page.data.map { it.id }
            key = page.nextKey ?: break
        }
        return ids
    }

    @Test
    fun pagesPreserveOrderAcrossPageBoundaries() = runBlocking {
        save("q1", "pa", "One"); save("q2", "pa", "Two"); save("q3", "pa", "Three")
        save("q4", "pa", "Four"); save("q5", "pa", "Five")
        save("z1", "pb", "Other passage")
        assertEquals(
            listOf("q1", "q2", "q3", "q4", "q5"),
            walk(repository.passageMembersSource("pa"))
        )
    }

    @Test
    fun theSessionQueueMatchesThePagedOrder() = runBlocking {
        save("q3", "pa", "Three"); save("q1", "pa", "One"); save("q2", "pa", "Two")
        val paged = walk(repository.passageMembersSource("pa"))
        assertEquals(paged, repository.getPassageMemberIds("pa"))
    }

    @Test
    fun anEmptyPassagePagesNothing() = runBlocking {
        save("q1", "pa", "One")
        assertEquals(emptyList<String>(), walk(repository.passageMembersSource("missing")))
    }
}
