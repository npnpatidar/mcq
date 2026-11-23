package com.mcqapp

import com.mcqapp.data.repository.SearchScopeFilter
import com.mcqapp.data.repository.matches
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionSearch
import com.mcqapp.ui.search.SearchRequest
import com.mcqapp.ui.search.isSearchPending
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paper/category chips narrow *which cards* search sees, independently of the
 * in-card Scope. A chip change is part of the debounced request so the exact
 * count and the pages always describe the same scope.
 */
class LibraryScaleSearchScopeTest {

    private fun hit(paperId: String, categoryId: String) = QuestionSearch.Hit(
        question = Question(
            id = "q", categoryId = categoryId, elements = emptyList(),
            options = emptyList(), correctOptionIds = emptySet()
        ),
        paperId = paperId,
        paperTitle = "Paper"
    )

    @Test
    fun emptyFilterMatchesEverything() {
        assertTrue(hit("p1", "c1").matches(SearchScopeFilter()))
    }

    @Test
    fun paperAndCategoryFiltersAreIndependent() {
        val filter = SearchScopeFilter(paperIds = setOf("p1"))
        assertTrue(hit("p1", "c2").matches(filter))
        assertFalse(hit("p2", "c1").matches(filter))

        val both = SearchScopeFilter(paperIds = setOf("p1"), categoryIds = setOf("c1"))
        assertTrue(hit("p1", "c1").matches(both))
        assertFalse(hit("p1", "c2").matches(both))
        assertFalse(hit("p2", "c1").matches(both))
    }

    @Test
    fun aChipChangeIsStillDebounced() {
        val committed = SearchRequest("bio", QuestionSearch.Scope.ALL)
        val withPaper = SearchRequest("bio", QuestionSearch.Scope.ALL, paperFilterIds = setOf("p1"))
        assertTrue(isSearchPending(withPaper, committed))

        val withCategory = SearchRequest("bio", QuestionSearch.Scope.ALL, categoryFilterIds = setOf("c1"))
        assertTrue(isSearchPending(withCategory, withPaper))
        assertFalse(isSearchPending(withPaper, withPaper))
    }
}
