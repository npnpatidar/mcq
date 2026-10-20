package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Passage
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
 * A session order is streamed out of the database a category at a time and a
 * keyset page at a time, where it used to be one paper-wide join. These pin
 * that the streamed order is the join's order — categories by
 * `(sortOrder, title)`, questions by `(sortOrder, rowid)` inside each — that
 * the keyset cursor walks a category without skipping or repeating a row, and
 * that the ungraded counts are aggregates rather than a count of ids.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionOrderLoadTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
        runBlocking {
            db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper"))
            // B (sortOrder 0) first, then C and A tied on sortOrder 1 — C
            // wins on title.
            db.categoryDao().upsert(CategoryEntity(id = "cA", paperId = "p1", title = "Alpha", sortOrder = 1))
            db.categoryDao().upsert(CategoryEntity(id = "cB", paperId = "p1", title = "Beta", sortOrder = 0))
            db.categoryDao().upsert(CategoryEntity(id = "cC", paperId = "p1", title = "Aardvark", sortOrder = 1))
            repository.savePassage(Passage(id = "pa", categoryId = "cB", title = "Passage"))
            question("q-b1", "cB", passageId = "pa")
            question("q-b2", "cB", passageId = "pa")
            question("q-b3", "cB", hasKey = false)
            question("q-c1", "cC", hasKey = false)
            question("q-a1", "cA")
            question("q-a2", "cA")
        }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun question(
        id: String,
        categoryId: String,
        passageId: String? = null,
        hasKey: Boolean = true
    ) = repository.saveQuestion(
        Question(
            id = id,
            categoryId = categoryId,
            passageId = passageId,
            elements = listOf(ContentElement.TextElement("Q $id")),
            options = listOf(QuestionOption("$id-a", "A")),
            correctOptionIds = if (hasKey) setOf("$id-a") else emptySet()
        )
    )

    @Test
    fun aPaperStreamsItsCategoriesInTheJoinOrder() = runBlocking {
        assertEquals(
            listOf("q-b1", "q-b2", "q-b3", "q-c1", "q-a1", "q-a2"),
            repository.getSessionOrderForPaper("p1").idsInOrder()
        )
    }

    @Test
    fun aCategorySelectionIsOrderedBySortOrderThenTitle() = runBlocking {
        assertEquals(
            listOf("q-c1", "q-a1", "q-a2"),
            repository.getSessionOrderForCategories(listOf("cA", "cC")).idsInOrder()
        )
    }

    @Test
    fun aMissingCategorySelectionIsAnEmptyOrder() = runBlocking {
        assertTrue(repository.getSessionOrderForCategories(listOf("nope")).isEmpty)
        assertTrue(repository.getSessionOrderForPaper("nope").isEmpty)
    }

    @Test
    fun theKeysetCursorWalksACategoryWithoutSkippingOrRepeating() = runBlocking {
        val seen = mutableListOf<String>()
        var afterSortOrder = Int.MIN_VALUE
        var afterRowId = Long.MIN_VALUE
        while (true) {
            val page = db.questionDao().pageSessionRefsByCategory("cB", afterSortOrder, afterRowId, 1)
            if (page.isEmpty()) break
            assertEquals("one row per page", 1, page.size)
            seen.add(page.first().id)
            afterSortOrder = page.first().sortOrder
            afterRowId = page.first().rowId
        }
        assertEquals(listOf("q-b1", "q-b2", "q-b3"), seen)
    }

    @Test
    fun theStreamedRowsCarryThePassageAndTheOrderKeepsItsBlockTogether() = runBlocking {
        val page = db.questionDao().pageSessionRefsByCategory("cB", Int.MIN_VALUE, Long.MIN_VALUE, 10)
        assertEquals(
            "the paged reader carries each row's passage",
            listOf("pa", "pa", null),
            page.map { it.passageId }
        )
        val ids = repository.getSessionOrderForPaper("p1").shuffled(20261017L, shuffle = true).idsInOrder()
        assertEquals(
            "passage members stay adjacent through the shuffle",
            1,
            Math.abs(ids.indexOf("q-b1") - ids.indexOf("q-b2"))
        )
    }

    @Test
    fun ungradedCountsAreAggregatesPerPaperAndPerSelection() = runBlocking {
        assertEquals(2, repository.countUngradedForPaper("p1"))
        assertEquals(1, repository.countUngradedForCategories(listOf("cB")))
        assertEquals(1, repository.countUngradedForCategories(listOf("cA", "cC")))
        assertEquals(0, repository.countUngradedForCategories(emptyList()))
    }
}
