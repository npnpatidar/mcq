package com.mcqapp.ui.search

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.ui.common.ScopeModalSheet
import com.mcqapp.ui.common.ScopeSheetContent
import com.mcqapp.ui.common.ScopeStep
import com.mcqapp.ui.common.ScopeTrigger

/** The two sheet steps. Kept saveable in [SearchScreen] across rotation. */
internal typealias SearchScopeStep = ScopeStep

/**
 * Under the search box: one button for *which cards* are searched. The All /
 * Questions / Options chips stay separate — they narrow *where in a card* to
 * look. Selecting a category applies immediately, so the summary is a pure
 * read of state and never holds a draft.
 */
@Composable
internal fun SearchScopeTrigger(
    state: SearchUiState,
    papers: List<Paper>,
    onClick: () -> Unit
) {
    val paper = papers.firstOrNull { it.id == state.paperFilterIds.singleOrNull() }
    ScopeTrigger(
        paper = paper,
        selectedCategoryIds = state.categoryFilterIds,
        onClick = onClick
    )
}

/**
 * Modal host. Delegates to the shared sheet so Search, Bookmarks and Passages
 * get the identical two-step choreography and tag contract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScopeSheet(
    papers: List<Paper>,
    selectedCategoryIds: Set<String>,
    showCategories: Boolean,
    chosenPaperId: String?,
    onChoosePaper: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onClearCategoriesInPaper: () -> Unit,
    onClearScope: () -> Unit,
    onBackToPapers: () -> Unit,
    onDismiss: () -> Unit
) {
    ScopeModalSheet(
        papers = papers,
        selectedCategoryIds = selectedCategoryIds,
        step = if (showCategories) ScopeStep.CATEGORIES else ScopeStep.PAPERS,
        chosenPaperId = chosenPaperId,
        onChoosePaper = onChoosePaper,
        onToggleCategory = onToggleCategory,
        onClearCategoriesInPaper = onClearCategoriesInPaper,
        onClearScope = onClearScope,
        onBackToPapers = onBackToPapers,
        onDismiss = onDismiss
    )
}

/**
 * Stateless two-step body for UI tests. The [showCategories] Boolean is kept so
 * the existing test harness stays unchanged; it maps onto the shared step enum.
 */
@Composable
internal fun SearchScopeSheetContent(
    papers: List<Paper>,
    selectedCategoryIds: Set<String>,
    showCategories: Boolean,
    chosenPaperId: String?,
    onChoosePaper: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onClearCategoriesInPaper: () -> Unit,
    onClearScope: () -> Unit,
    onBackToPapers: () -> Unit,
    onDismiss: () -> Unit
) {
    ScopeSheetContent(
        papers = papers,
        selectedCategoryIds = selectedCategoryIds,
        step = if (showCategories) ScopeStep.CATEGORIES else ScopeStep.PAPERS,
        chosenPaperId = chosenPaperId,
        onChoosePaper = onChoosePaper,
        onToggleCategory = onToggleCategory,
        onClearCategoriesInPaper = onClearCategoriesInPaper,
        onClearScope = onClearScope,
        onBackToPapers = onBackToPapers,
        onDismiss = onDismiss
    )
}

/** Pre-order ancestors of a flattened category row, joined with a chevron. */
internal fun categoryPath(flat: List<Pair<CategoryNode, Int>>, index: Int): String =
    com.mcqapp.ui.common.categoryPath(flat, index)
