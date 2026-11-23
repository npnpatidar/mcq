package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.getOrderedIdsByIdsChunked
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SearchScopeFilter
import com.mcqapp.data.repository.searchCount
import com.mcqapp.domain.QuestionSearch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search header's exact total must come from the same two-phase pipeline
 * the pager uses, and the bulk export's ordered query must replace the old
 * per-id position lookups without changing the canonical list order.
 * Robolectric needs a real SQLite driver, so this runs on CI (x86_64).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryScaleQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper One", "c1", "Cat One")
            repository.ensurePaperAndCategory("p2", "Paper Two", "c2", "Cat Two")
            db.questionDao().upsertAll(
                listOf(
                    question("q1", "c1", "alpha one", sort = 1),
                    question("q2", "c1", "alpha two", sort = 0),
                    question("q3", "c2", "alpha three", sort = 0),
                    question("q4", "c1", "beta four", sort = 2)
                )
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun question(id: String, category: String, stem: String, sort: Int) = QuestionEntity(
        id = id, categoryId = category, text = """[{"type":"text","content":"$stem"}]""",
        searchText = stem, searchTextFolded = stem.lowercase(),
        tags = "", tagsFolded = "", sortOrder = sort
    )

    @Test
    fun exactCountMatchesTheTwoPhasePipelineAndHonoursChips() = runBlocking {
        assertEquals(3, repository.searchCount("alpha", QuestionSearch.Scope.ALL))
        assertEquals(
            1,
            repository.searchCount(
                "alpha", QuestionSearch.Scope.ALL,
                SearchScopeFilter(paperIds = setOf("p2"))
            )
        )
        assertEquals(
            1,
            repository.searchCount(
                "alpha", QuestionSearch.Scope.ALL,
                SearchScopeFilter(categoryIds = setOf("c2"))
            )
        )
        assertEquals(
            0,
            repository.searchCount(
                "alpha", QuestionSearch.Scope.ALL,
                SearchScopeFilter(paperIds = setOf("p1"), categoryIds = setOf("c2"))
            )
        )
        assertEquals(0, repository.searchCount("nothingmatches", QuestionSearch.Scope.ALL))
    }

    @Test
    fun theCountIsExactNotJustTheLoadedPage() = runBlocking {
        // A query that matches more than one 30-row page still counts them all.
        val many = (0 until 75).map { i ->
            question("many-$i", "c1", "commonword $i", sort = i)
        }
        db.questionDao().upsertAll(many)
        assertEquals(75, repository.searchCount("commonword", QuestionSearch.Scope.ALL))
    }

    @Test
    fun orderedSelectionUsesCanonicalBrowseOrder() = runBlocking {
        // c1 sorts before c2 by paper/category order; inside c1 the question
        // with sortOrder 0 comes first even though it was inserted second.
        assertEquals(
            listOf("q2", "q1", "q3"),
            db.questionDao().getOrderedIdsByIdsChunked(setOf("q1", "q2", "q3"))
        )
    }
}
