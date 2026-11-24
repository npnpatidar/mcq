package com.mcqapp

import com.mcqapp.data.io.SelectionExport
import com.mcqapp.data.repository.BookmarkScopeFilter
import com.mcqapp.data.repository.filterBookmarksByScope
import com.mcqapp.domain.BookmarkedQuestion
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.ui.history.BookmarksUiState
import com.mcqapp.ui.history.clearBookmarkCategories
import com.mcqapp.ui.history.clearBookmarkScope
import com.mcqapp.ui.history.chooseBookmarkPaper
import com.mcqapp.ui.history.pruneBookmarkScope
import com.mcqapp.ui.history.scopedBookmarkExportDto
import com.mcqapp.ui.history.scope
import com.mcqapp.ui.history.toggleBookmarkCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure contract of the Bookmarks scope sheet: paper and categories travel
 * together, a tick stores the whole subtree, stale ids are pruned, and the
 * filter is a flat paper-AND-category membership test. JVM-only — no repository.
 */
class BookmarksScopeTest {

    private val physics = Paper(
        id = "p1",
        title = "Physics",
        categories = listOf(
            CategoryNode(id = "p1-c1", paperId = "p1", title = "Mechanics", parentId = null),
            CategoryNode(
                id = "p1-c2", paperId = "p1", title = "Optics", parentId = null,
                children = listOf(
                    CategoryNode(id = "p1-c2a", paperId = "p1", title = "Lenses", parentId = "p1-c2")
                )
            )
        )
    )
    private val chemistry = Paper(
        id = "p2",
        title = "Chemistry",
        categories = listOf(
            // Same title as p1-c1: ids, not titles, decide membership.
            CategoryNode(id = "p2-c1", paperId = "p2", title = "Mechanics", parentId = null)
        )
    )
    private val papers = listOf(physics, chemistry)

    private fun state(
        paperId: String? = null,
        categoryIds: Set<String> = emptySet()
    ) = BookmarksUiState(filterPaperId = paperId, categoryFilterIds = categoryIds)

    private fun item(id: String, paperId: String, categoryId: String) = BookmarkedQuestion(
        paperId = paperId,
        question = Question(
            id = id, categoryId = categoryId, elements = emptyList(),
            options = emptyList(), correctOptionIds = emptySet()
        )
    )

    @Test
    fun choosingAPaperSetsASinglePaperAndDropsTheOldPapersCategories() {
        val scoped = state("p1", setOf("p1-c1"))
        val switched = chooseBookmarkPaper(scoped, "p2")
        assertEquals("p2", switched.filterPaperId)
        assertEquals(emptySet<String>(), switched.categoryFilterIds)

        // Switching to a paper with no categories also drops them.
        assertEquals(emptySet<String>(), chooseBookmarkPaper(state("p1", setOf("p1-c1")), "p3").categoryFilterIds)
    }

    @Test
    fun rechoosingTheSamePaperKeepsItsCategorySelection() {
        val scoped = state("p1", setOf("p1-c2", "p1-c2a"))
        assertEquals(scoped, chooseBookmarkPaper(scoped, "p1"))
    }

    @Test
    fun togglingACategoryStoresItsWholeSubtreeAndTogglesItBackOff() {
        val on = toggleBookmarkCategory(state("p1"), "p1-c2", papers)
        assertEquals(setOf("p1-c2", "p1-c2a"), on.categoryFilterIds)

        val off = toggleBookmarkCategory(on, "p1-c2", papers)
        assertEquals(emptySet<String>(), off.categoryFilterIds)
    }

    @Test
    fun togglingOneBranchDoesNotTouchAnotherPaperOrBranch() {
        val on = toggleBookmarkCategory(state("p1"), "p1-c1", papers)
        assertEquals(setOf("p1-c1"), on.categoryFilterIds)
        // A second independent root adds; the first stays.
        val two = toggleBookmarkCategory(on, "p1-c2a", papers)
        assertEquals(setOf("p1-c1", "p1-c2a"), two.categoryFilterIds)
    }

    @Test
    fun togglingWithNoChosenPaperOrAnUnknownIdIsANoop() {
        assertEquals(state(), toggleBookmarkCategory(state(), "p1-c1", papers))
        assertEquals(state("p1"), toggleBookmarkCategory(state("p1"), "missing", papers))
    }

    @Test
    fun clearingCategoriesKeepsThePaperButClearScopeDropsBoth() {
        val scoped = state("p1", setOf("p1-c1"))
        assertEquals(state("p1"), clearBookmarkCategories(scoped))
        assertEquals(state(), clearBookmarkScope(scoped))
    }

    @Test
    fun pruningDropsAStalePaperAndOnlyStaleCategories() {
        assertEquals(state(), pruneBookmarkScope(state("gone", setOf("gone-c")), papers))
        val kept = pruneBookmarkScope(state("p1", setOf("p1-c1", "gone-c")), papers)
        assertEquals("p1", kept.filterPaperId)
        assertEquals(setOf("p1-c1"), kept.categoryFilterIds)
        // A category id belonging to another paper is stale too.
        assertEquals(
            setOf("p1-c1"),
            pruneBookmarkScope(state("p1", setOf("p1-c1", "p2-c1")), papers).categoryFilterIds
        )
    }

    @Test
    fun pruningWithNoLoadedPapersIsANoop() {
        val scoped = state("p1", setOf("p1-c1"))
        assertEquals(scoped, pruneBookmarkScope(scoped, emptyList()))
        assertEquals(state(), pruneBookmarkScope(state(), emptyList()))
    }

    @Test
    fun scopeIsDerivedFromTheWholeAppliedSelection() {
        assertEquals(BookmarkScopeFilter(), state().scope())
        assertEquals(BookmarkScopeFilter("p1"), state("p1").scope())
        assertEquals(
            BookmarkScopeFilter("p1", setOf("p1-c2", "p1-c2a")),
            state("p1", setOf("p1-c2", "p1-c2a")).scope()
        )
    }

    @Test
    fun theFilterChecksPaperAndExpandedCategoryMembership() {
        val rows = listOf(
            item("q1", "p1", "p1-c1"),
            item("q2", "p1", "p1-c2a"),
            item("q3", "p2", "p2-c1")
        )
        // All papers.
        assertEquals(rows, filterBookmarksByScope(rows, BookmarkScopeFilter()))
        // A paper with no categories: every category in that paper.
        assertEquals(
            listOf("q1", "q2"),
            filterBookmarksByScope(rows, BookmarkScopeFilter("p1")).map { it.question.id }
        )
        // Expanded subtree ids: q2 (Lenses) is kept without naming its parent.
        assertEquals(
            listOf("q2"),
            filterBookmarksByScope(
                rows, BookmarkScopeFilter("p1", setOf("p1-c2", "p1-c2a"))
            ).map { it.question.id }
        )
        // A paper/category pair matching neither row yields nothing.
        assertTrue(
            filterBookmarksByScope(rows, BookmarkScopeFilter("p2", setOf("p1-c1"))).isEmpty()
        )
    }

    @Test
    fun matchesRejectsACategoryFromAnotherPaperEvenWithTheSameTitle() {
        val scope = BookmarkScopeFilter("p1", setOf("p1-c1"))
        assertTrue(scope.matches("p1", "p1-c1"))
        assertTrue(!scope.matches("p1", "p2-c1"))
        assertTrue(!scope.matches("p2", "p1-c1"))
    }

    private val bookmarks = listOf(
        item("q1", "p1", "p1-c1"),
        item("q2", "p1", "p1-c2a"),
        item("q3", "p2", "p2-c1")
    )
    private val titles = mapOf("p1" to "Physics", "p2" to "Chemistry")

    /**
     * The export/share sink is the pure helper, not a hand-built list: with two
     * papers bookmarked, scoping to one must keep only that paper's question —
     * the helper, not the test, decides which questions reach the DTO.
     */
    @Test
    fun scopedExportContainsOnlyMatchingBookmarks() {
        val physics = scopedBookmarkExportDto(bookmarks, BookmarkScopeFilter("p1"), titles)!!
        assertEquals(listOf("Physics"), physics.categories.map { it.title })
        assertEquals(listOf("q1", "q2"), physics.categories.single().questions.map { it.id })
        assertTrue("the Chemistry bookmark must not reach the sink",
            physics.categories.none { it.title == "Chemistry" })

        val chemistry = scopedBookmarkExportDto(
            bookmarks, BookmarkScopeFilter("p2", setOf("p2-c1")), titles
        )!!
        assertEquals(listOf("Chemistry"), chemistry.categories.map { it.title })
        assertEquals(listOf("q3"), chemistry.categories.single().questions.map { it.id })
        assertTrue("the Physics bookmarks must not reach the sink",
            chemistry.categories.none { it.title == "Physics" })
    }

    /**
     * Positional "bookmarked-N" ids retitle an imported row when the scope
     * changes which titles are exported. The scoped DTO must reuse the stable
     * selection-style id of the title, independent of the other titles present.
     */
    @Test
    fun scopedExportCategoryIdsAreStableAcrossScopes() {
        val physics = scopedBookmarkExportDto(bookmarks, BookmarkScopeFilter("p1"), titles)!!
        val chemistry = scopedBookmarkExportDto(bookmarks, BookmarkScopeFilter("p2"), titles)!!
        assertEquals(
            SelectionExport.categoryIdFor("Physics"), physics.categories.single().id
        )
        assertEquals(
            SelectionExport.categoryIdFor("Chemistry"), chemistry.categories.single().id
        )
        assertNotEquals(
            "different titles must not collide across scoped exports",
            physics.categories.single().id,
            chemistry.categories.single().id
        )
        // Dropping the other paper from the candidate set must not move the id.
        val chemistryAlone = scopedBookmarkExportDto(
            listOf(item("q3", "p2", "p2-c1")), BookmarkScopeFilter("p2"), titles
        )!!
        assertEquals(chemistry.categories.single().id, chemistryAlone.categories.single().id)
    }

    @Test
    fun anEmptyScopedExportYieldsNoSink() {
        assertEquals(
            null,
            scopedBookmarkExportDto(bookmarks, BookmarkScopeFilter("p3"), titles)
        )
        assertEquals(
            null,
            scopedBookmarkExportDto(emptyList(), BookmarkScopeFilter(), titles)
        )
    }

    /** Mirrors the Passages structure test: the scoped empty copy exists in both locales. */
    @Test
    fun scopedEmptyStringExistsInBothLocales() {
        val en = File("src/main/res/values/strings_bookmarks_scope.xml").readText()
        val hi = File("src/main/res/values-hi/strings_bookmarks_scope_hi.xml").readText()
        listOf(en, hi).forEach { xml ->
            assertTrue(
                "missing scoped empty string",
                "<string name=\"no_bookmarks_in_scope\">" in xml
            )
        }
    }
}
