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
            db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper"))
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
        walkPaging(2, { repository.browseRefsSource(scope, repository.browseCategoryRowIdCsv(scope)) }) { key ->
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

    /**
     * Category ids are arbitrary imported strings, and one whose id LITERALLY
     * contains a comma is the INSTR split's undoing: the stored csv
     * `,a,b,` was found by BOTH the needle `,a,` (neighbour `a` leaked into a
     * comma-id-only selection) and the needle `,a,b,` (the comma-id row
     * leaked into a selection of the two plain siblings a and b) — and
     * whichever of those fired, the count and Prev/Next cursor drifted with
     * the list, all three sharing the one wrong expression. The filter now
     * binds the selected categories' ROWIDS as the csv (a number cannot
     * contain a comma), and the list, count, id-at-index and index-of
     * queries share that same expression, so every caller agrees.
     *
     * Room/SQLite — CI is the authority. Mutant: bind ids instead of rowids
     * (the old INSTR) — both selections below go red with the leaked rows.
     */
    @Test
    fun anIdContainingACommaDoesNotLeakAnybody() = runBlocking {
        db.categoryDao().upsert(CategoryEntity(id = "a", paperId = "p1", title = "A", sortOrder = 0))
        db.categoryDao().upsert(CategoryEntity(id = "b", paperId = "p1", title = "B", sortOrder = 1))
        db.categoryDao().upsert(CategoryEntity(id = "a,b", paperId = "p1", title = "Comma", sortOrder = 2))
        db.categoryDao().upsert(CategoryEntity(id = "phys", paperId = "p1", title = "C", sortOrder = 3))
        save("q-a1", "a", "One"); save("q-b1", "b", "Two")
        save("q-c1", "a,b", "Three"); save("q-c2", "a,b", "Four"); save("q-p1", "phys", "Five")

        // The comma-id selected ALONE: its own two questions, no neighbour.
        val alone = scope(categoryFilterIds = setOf("a,b"))
        assertEquals(listOf("q-c1", "q-c2"), pagedOrder(alone))
        assertEquals("the count path agrees with the list", 2, repository.browseFilteredCount(alone))
        assertEquals("q-c1", repository.browseFilteredIdAt(0, alone))
        assertEquals(null, repository.browseFilteredIdAt(2, alone))

        // The two plain siblings a and b selected together: exactly their
        // questions — the comma-id row must not sneak in through the csv.
        val siblings = scope(categoryFilterIds = setOf("a", "b"))
        assertEquals(listOf("q-a1", "q-b1"), pagedOrder(siblings))
        assertEquals("the count path agrees with the list", 2, repository.browseFilteredCount(siblings))
    }
}
