package com.mcqapp

import com.mcqapp.data.repository.matches
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionSearch
import com.mcqapp.ui.search.SearchRequest
import com.mcqapp.ui.search.SearchUiState
import com.mcqapp.ui.search.chooseSearchPaper
import com.mcqapp.ui.search.clearCategoriesInPaper
import com.mcqapp.ui.search.clearSearchScope
import com.mcqapp.ui.search.pruneSearchScope
import com.mcqapp.ui.search.toggleCategoryInChosenPaper
import com.mcqapp.ui.search.toggleCategoryInPaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scope sheet applies immediately. A paper and its categories are one
 * scope: choosing a different paper drops the old paper's categories, while
 * selecting a category stores its WHOLE subtree as expanded ids. That flat set
 * is all [com.mcqapp.data.repository.SearchScopeFilter.matches] needs — it
 * still ANDs paper and category, so no repository/filter change is involved.
 */
class SearchFilterChipsTest {

    private fun cat(
        id: String,
        parent: String? = null,
        children: List<CategoryNode> = emptyList()
    ) = CategoryNode(id = id, paperId = "p1", title = id, parentId = parent, children = children)

    /** root -> {a -> {a1}, b}: a three-level tree with a sibling leaf. */
    private fun tree() = listOf(
        cat(
            "root",
            children = listOf(
                cat("a", "root", listOf(cat("a1", "a"))),
                cat("b", "root")
            )
        )
    )

    private fun hit(paperId: String, categoryId: String) = QuestionSearch.Hit(
        question = Question(
            id = "q-$paperId-$categoryId",
            categoryId = categoryId,
            elements = emptyList(),
            options = emptyList(),
            correctOptionIds = emptySet()
        ),
        paperId = paperId,
        paperTitle = "Paper"
    )

    private fun SearchUiState.request() = SearchRequest(query, scope, paperFilterIds, categoryFilterIds)

    @Test
    fun choosingAPaperKeepsItsCategoriesWhenTheSamePaperIsChosenAgain() {
        val state = SearchUiState(paperFilterIds = setOf("p1"), categoryFilterIds = setOf("c1", "c2"))

        val next = chooseSearchPaper(state, "p1")

        assertEquals(setOf("p1"), next.paperFilterIds)
        assertEquals(setOf("c1", "c2"), next.categoryFilterIds)
    }

    @Test
    fun choosingAPaperFromAllPapersStartsWithNoCategories() {
        val next = chooseSearchPaper(SearchUiState(categoryFilterIds = setOf("c1")), "p1")

        assertEquals(setOf("p1"), next.paperFilterIds)
        assertTrue("a new paper has no categories carried over", next.categoryFilterIds.isEmpty())
    }

    @Test
    fun switchingPaperDropsTheOldPapersCategories() {
        val state = SearchUiState(paperFilterIds = setOf("p1"), categoryFilterIds = setOf("c1", "c2"))

        val next = chooseSearchPaper(state, "p2")

        assertEquals(setOf("p2"), next.paperFilterIds)
        assertTrue("categories belong to the previous paper", next.categoryFilterIds.isEmpty())
    }

    @Test
    fun severalCategoriesCanBeSelectedTogetherInsideTheChosenPaper() {
        val categories = listOf(cat("c1"), cat("c2"))
        val start = chooseSearchPaper(SearchUiState(), "p1")

        val first = toggleCategoryInPaper(start, "c1", categories)
        val second = toggleCategoryInPaper(first, "c2", categories)

        assertEquals(setOf("c1", "c2"), second.categoryFilterIds)
        assertEquals(setOf("p1"), second.paperFilterIds)
    }

    @Test
    fun retogglingOneCategoryRemovesOnlyThatCategory() {
        val categories = listOf(cat("c1"), cat("c2"), cat("c3"))
        val first = toggleCategoryInPaper(SearchUiState(), "c1", categories)
        val second = toggleCategoryInPaper(first, "c2", categories)

        val third = toggleCategoryInPaper(second, "c1", categories)

        assertEquals(setOf("c2"), third.categoryFilterIds)
    }

    @Test
    fun selectingALeafKeepsOnlyItsExactId() {
        val selected = toggleCategoryInPaper(SearchUiState(), "a1", tree())

        assertEquals(setOf("a1"), selected.categoryFilterIds)
        assertTrue(hit("p1", "a1").matches(selected.request().filter))
        assertFalse("the parent is not implied by a leaf", hit("p1", "a").matches(selected.request().filter))
    }

    @Test
    fun clearingCategoriesKeepsThePaperQueryAndScope() {
        val state = SearchUiState(
            query = "bio",
            scope = QuestionSearch.Scope.OPTIONS,
            paperFilterIds = setOf("p1"),
            categoryFilterIds = setOf("c1", "c2")
        )

        val next = clearCategoriesInPaper(state)

        assertEquals(setOf("p1"), next.paperFilterIds)
        assertTrue(next.categoryFilterIds.isEmpty())
        assertEquals("bio", next.query)
        assertEquals(QuestionSearch.Scope.OPTIONS, next.scope)
    }

    @Test
    fun allPapersClearsBothPaperAndCategories() {
        val state = SearchUiState(
            query = "bio",
            scope = QuestionSearch.Scope.OPTIONS,
            paperFilterIds = setOf("p1"),
            categoryFilterIds = setOf("c1", "c2")
        )

        val next = clearSearchScope(state)

        assertTrue(next.paperFilterIds.isEmpty())
        assertTrue(next.categoryFilterIds.isEmpty())
        assertTrue(next.request().filter.isAll)
        assertEquals("bio", next.query)
        assertEquals(QuestionSearch.Scope.OPTIONS, next.scope)
    }

    @Test
    fun selectingAParentStoresItsWholeSubtreeInTheRequest() {
        val selected = toggleCategoryInPaper(chooseSearchPaper(SearchUiState(), "p1"), "root", tree())

        val request = selected.request()
        assertEquals(setOf("root", "a", "a1", "b"), request.filter.categoryIds)
        assertEquals(setOf("p1"), request.filter.paperIds)
        assertTrue(request.hasScopeFilter)
    }

    @Test
    fun aHitInAChildCategoryMatchesTheExpandedFilterAndPaperStillAnds() {
        val filter = toggleCategoryInPaper(chooseSearchPaper(SearchUiState(), "p1"), "root", tree())
            .request().filter

        assertTrue(hit("p1", "root").matches(filter))
        assertTrue(hit("p1", "a").matches(filter))
        assertTrue("a hit in a grandchild is inside the selected subtree", hit("p1", "a1").matches(filter))
        assertTrue(hit("p1", "b").matches(filter))
        assertFalse(hit("p1", "other").matches(filter))
        assertFalse("the paper half of the AND still applies", hit("p2", "a1").matches(filter))
    }

    @Test
    fun togglingTheParentAgainRemovesTheWholeSubtree() {
        val start = chooseSearchPaper(SearchUiState(), "p1")
        val selected = toggleCategoryInPaper(start, "root", tree())

        val cleared = toggleCategoryInPaper(selected, "root", tree())

        assertTrue(cleared.categoryFilterIds.isEmpty())
        assertTrue(cleared.request().filter.categoryIds.isEmpty())
        assertEquals(setOf("p1"), cleared.paperFilterIds)
    }

    @Test
    fun aPartiallySelectedParentTogglesAllOfItsMissingIdsOn() {
        val partial = SearchUiState(paperFilterIds = setOf("p1"), categoryFilterIds = setOf("root"))

        val next = toggleCategoryInPaper(partial, "root", tree())

        assertEquals(setOf("root", "a", "a1", "b"), next.categoryFilterIds)
    }

    @Test
    fun togglingAChildInsideASelectedParentRemovesOnlyThatBranch() {
        val selected = toggleCategoryInPaper(chooseSearchPaper(SearchUiState(), "p1"), "root", tree())

        val next = toggleCategoryInPaper(selected, "a", tree())

        assertEquals(setOf("root", "b"), next.categoryFilterIds)
        val filter = next.request().filter
        assertFalse(hit("p1", "a1").matches(filter))
        assertTrue(hit("p1", "b").matches(filter))
    }

    @Test
    fun siblingSubtreesAccumulateWithoutDoubleCounting() {
        val forest = tree() + listOf(cat("otherRoot", children = listOf(cat("z", "otherRoot"))))
        val first = toggleCategoryInPaper(SearchUiState(), "root", forest)

        val second = toggleCategoryInPaper(first, "otherRoot", forest)

        assertEquals(setOf("root", "a", "a1", "b", "otherRoot", "z"), second.categoryFilterIds)
    }

    @Test
    fun anUnknownCategoryIdLeavesTheSelectionUntouched() {
        val state = SearchUiState(
            paperFilterIds = setOf("p1"),
            categoryFilterIds = setOf("c1", "c2")
        )

        assertEquals(state, toggleCategoryInPaper(state, "missing", tree()))
    }

    // Under All papers (or while the chosen paper is not loaded) there is no
    // tree in which a category id has meaning, so the VM wrapper is a no-op.

    @Test
    fun togglingACategoryWithNoChosenPaperDoesNothing() {
        val state = SearchUiState(paperFilterIds = emptySet(), categoryFilterIds = setOf("a"))
        val papers = listOf(Paper("p1", "Paper 1", categories = tree()))

        assertEquals(state, toggleCategoryInChosenPaper(state, "root", papers))
    }

    @Test
    fun togglingACategoryWhosePaperIsNotLoadedDoesNothing() {
        val state = chooseSearchPaper(SearchUiState(), "missing")
        val papers = listOf(Paper("p1", "Paper 1", categories = tree()))

        assertEquals(state, toggleCategoryInChosenPaper(state, "root", papers))
    }

    @Test
    fun togglingACategoryUsesOnlyTheChosenPapersTree() {
        val papers = listOf(
            Paper("p1", "Paper 1", categories = tree()),
            Paper("p2", "Paper 2", categories = listOf(cat("z")))
        )
        val state = chooseSearchPaper(SearchUiState(), "p1")

        val next = toggleCategoryInChosenPaper(state, "root", papers)

        assertEquals(setOf("root", "a", "a1", "b"), next.categoryFilterIds)
        assertEquals(setOf("p1"), next.paperFilterIds)
    }

    // Orphaned ids: the loaded papers are the authority on what still exists.

    @Test
    fun pruningBeforePapersLoadKeepsTheState() {
        val state = SearchUiState(paperFilterIds = setOf("p1"), categoryFilterIds = setOf("a1"))

        assertEquals(state, pruneSearchScope(state, emptyList()))
    }

    @Test
    fun pruningADeletedPaperClearsBothSetsButNotTheQuery() {
        val state = SearchUiState(
            query = "bio",
            scope = QuestionSearch.Scope.OPTIONS,
            paperFilterIds = setOf("gone"),
            categoryFilterIds = setOf("a1", "b")
        )
        val papers = listOf(Paper("p1", "Paper 1", categories = tree()))

        val next = pruneSearchScope(state, papers)

        assertTrue(next.paperFilterIds.isEmpty())
        assertTrue(next.categoryFilterIds.isEmpty())
        assertEquals("bio", next.query)
        assertEquals(QuestionSearch.Scope.OPTIONS, next.scope)
    }

    @Test
    fun pruningDropsDeletedCategoriesAndKeepsThePaperAndSurvivors() {
        val state = SearchUiState(
            paperFilterIds = setOf("p1"),
            categoryFilterIds = setOf("root", "a1", "b", "deleted")
        )
        val papers = listOf(Paper("p1", "Paper 1", categories = tree()))

        val next = pruneSearchScope(state, papers)

        assertEquals(setOf("p1"), next.paperFilterIds)
        assertEquals(setOf("root", "a1", "b"), next.categoryFilterIds)
        assertTrue(hit("p1", "a1").matches(next.request().filter))
        assertFalse(hit("p1", "deleted").matches(next.request().filter))
    }

    @Test
    fun pruningAHealthyScopeLeavesItUntouched() {
        val state = SearchUiState(paperFilterIds = setOf("p1"), categoryFilterIds = setOf("a1", "b"))
        val papers = listOf(Paper("p1", "Paper 1", categories = tree()))

        assertEquals(state, pruneSearchScope(state, papers))
    }

    @Test
    fun pruningClearsStaleCategoriesUnderAllPapers() {
        val state = SearchUiState(paperFilterIds = emptySet(), categoryFilterIds = setOf("a1"))
        val papers = listOf(Paper("p1", "Paper 1", categories = tree()))

        val next = pruneSearchScope(state, papers)

        assertTrue(next.paperFilterIds.isEmpty())
        assertTrue(next.categoryFilterIds.isEmpty())
        assertTrue(next.request().filter.isAll)
    }
}
