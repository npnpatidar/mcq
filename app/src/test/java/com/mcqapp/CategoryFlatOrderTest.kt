package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.repository.McqRepository
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
 * The flat `(sortOrder, title)` category order is the one order every surface
 * serves — the paper-wide session order, the scoped pool SQL inside each
 * chunk, browse's paging cursor. A scoped study screen's chunk boundaries must
 * sit on it too, or a scope of over 500 categories is walked in a different
 * relative category order than the scoped test session next door delivers
 * (third_bugs.md finding #1).
 *
 * Robolectric needs a real SQLite driver, so this test only runs on x86_64 CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoryFlatOrderTest {

    private lateinit var appContext: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        appContext = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, appContext)
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper"))
        db.paperDao().insertIgnore(PaperEntity(id = "p2", title = "Other"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun category(id: String, title: String, sortOrder: Int, paperId: String = "p1") =
        db.categoryDao().upsert(
            CategoryEntity(id = id, paperId = paperId, title = title, parentId = null, sortOrder = sortOrder)
        )

    @Test
    fun theScopeComesBackInFlatOrderWhateverOrderItWentIn() = runBlocking {
        // Two categories tie on title by intent: the (sortOrder, title)
        // comparison must resolve both, and the input list orders both ways.
        category("cz", "Zebra", 2)
        category("ca", "Apple", 0)
        category("cm", "Mango", 1)

        val expected = listOf("ca", "cm", "cz")
        assertEquals(expected, repository.categoryFlatOrder("p1", listOf("cz", "cm", "ca")))
        assertEquals(expected, repository.categoryFlatOrder("p1", listOf("ca", "cm", "cz")))
    }

    @Test
    fun foreignAndUnknownIdsContributeNothing() = runBlocking {
        category("ca", "Apple", 0)
        category("cz", "Zebra", 2)
        category("foreign", "Foreign", 1, paperId = "p2")

        assertEquals(
            listOf("ca", "cz"),
            repository.categoryFlatOrder("p1", listOf("foreign", "unknown-id", "ca", "cz"))
        )
    }

    private suspend fun question(id: String, categoryId: String) {
        repository.saveQuestion(
            Question(
                id = id,
                categoryId = categoryId,
                text = "Q $id",
                options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
                correctOptionIds = setOf("$id-a")
            )
        )
    }

    // The alignment end to end: a scope handed in the wrong order (what the
    // pre-order tree walk yields when sortOrders disagree with tree shape —
    // the shape an arbitrary imported `(parent, sortOrder)` matrix
    // produces), mapped through [McqRepository.categoryFlatOrder] the way
    // the study screen does at scope construction, and chunked into the
    // pool queries at the 500-id wall, must serve its fresh cards in
    // exactly the flat order — the same relative category order
    // `getSessionOrderForCategories` streams. Without the mapping, the
    // chunk sequence alone scrambles the scope across the wall.
    @Test
    fun overTheChunkWallTheStudyQueueWalksTheScopeInFlatOrder() = runBlocking {
        // 600 categories: sortOrders strictly descending with id, so chunking
        // the ids in input (ascending) order would put the mid-index
        // categories first regardless of what the flat order says.
        (1..600).forEach { index ->
            val id = "c$index"
            category(id, "Cat $index", sortOrder = 1000 - index)
            question("q-$id", id)
        }
        val preOrderLikeInput = (1..600).map { "c$it" }
        val expectedFlat = preOrderLikeInput.reversed()

        val flatScope = repository.categoryFlatOrder("p1", preOrderLikeInput)
        assertEquals("the scope re-orders to flat regardless of input", expectedFlat, flatScope)

        val served = repository.getStudyQueue("p1", categoryIds = flatScope)
            .map { it.questionId.removePrefix("q-") }
        assertEquals(expectedFlat, served)

        // And the scoped test session streams the same relative category order.
        val sessionOrderIds = repository.getSessionOrderForCategories("p1", flatScope)
            .idsInOrder()
        // One question per category, id "q-<categoryId>": map back to the
        // category id the expected list is written in.
        assertEquals(expectedFlat, sessionOrderIds.map { it.removePrefix("q-") })
    }
}
