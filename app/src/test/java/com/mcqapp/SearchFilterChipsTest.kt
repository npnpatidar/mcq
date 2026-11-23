package com.mcqapp

import com.mcqapp.ui.search.SearchUiState
import com.mcqapp.ui.search.toggleCategoryFilter
import com.mcqapp.ui.search.togglePaperFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paper and category chips are mutually exclusive scope filters. The search
 * scope [com.mcqapp.data.repository.SearchScopeFilter.matches] ANDs the two,
 * and categories belong to papers, so leaving both selected can only produce
 * an empty result set. These are the pure state transitions behind the chips.
 */
class SearchFilterChipsTest {

    @Test
    fun choosingAPaperClearsTheCategoryChips() {
        val state = SearchUiState(categoryFilterIds = setOf("c1", "c2"))
        val next = togglePaperFilter(state, "p1")

        assertEquals(setOf("p1"), next.paperFilterIds)
        assertTrue("a paper filter must clear every category", next.categoryFilterIds.isEmpty())
    }

    @Test
    fun choosingACategoryClearsThePaperChip() {
        val state = SearchUiState(paperFilterIds = setOf("p1"))
        val next = toggleCategoryFilter(state, "c1")

        assertEquals(setOf("c1"), next.categoryFilterIds)
        assertTrue("a category filter must clear the paper", next.paperFilterIds.isEmpty())
    }

    @Test
    fun severalCategoriesCanBeSelectedTogether() {
        val first = toggleCategoryFilter(SearchUiState(), "c1")
        val second = toggleCategoryFilter(first, "c2")

        assertEquals(setOf("c1", "c2"), second.categoryFilterIds)
        assertTrue(second.paperFilterIds.isEmpty())
    }

    @Test
    fun retappingTheActiveChipClearsOnlyItsOwnFamily() {
        val paper = togglePaperFilter(SearchUiState(), "p1")
        assertTrue(togglePaperFilter(paper, "p1").paperFilterIds.isEmpty())

        val category = toggleCategoryFilter(SearchUiState(), "c1")
        assertTrue(toggleCategoryFilter(category, "c1").categoryFilterIds.isEmpty())
    }

    @Test
    fun choosingAnotherPaperReplacesTheOldOne() {
        val first = togglePaperFilter(SearchUiState(), "p1")
        val second = togglePaperFilter(first, "p2")

        assertEquals(setOf("p2"), second.paperFilterIds)
        assertTrue(second.categoryFilterIds.isEmpty())
    }
}
