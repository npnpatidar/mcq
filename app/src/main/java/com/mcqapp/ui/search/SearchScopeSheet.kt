package com.mcqapp.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.CategoryCheckState
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.Paper

/** The two sheet steps. Kept saveable in [SearchScreen] across rotation. */
internal enum class SearchScopeStep { PAPERS, CATEGORIES }

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
    // The state holds every id in a ticked subtree (the search layer's flat
    // contract). The user ticked ROWS, not descendants, so the summary counts
    // the maximal ticked nodes only.
    val ticked = paper?.let { CategorySelection.tickedIds(it.categories, state.categoryFilterIds) }
    val tickedCount = ticked?.size ?: 0
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search-scope-button")
    ) {
        Text(
            text = when {
                paper == null -> stringResource(R.string.search_filter_all_papers)
                tickedCount == 0 -> paper.title
                else -> pluralStringResource(
                    R.plurals.search_scope_summary_categories,
                    tickedCount,
                    paper.title,
                    tickedCount
                )
            },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Modal host. `skipPartiallyExpanded` matters here: a partial sheet clipped the
 * last category row behind a drag on short screens, the same failure the test
 * setup sheet fixed (TestSetupSheet.kt). The content itself is stateless and can
 * be hosted directly by UI tests.
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("search-scope-sheet")
    ) {
        // Must be INSIDE the sheet: a parent BackHandler registers on the
        // Activity dispatcher, which the modal dialog never sees. Material3's
        // own dismiss callback stays enabled: on step 2 this later-registered
        // handler wins, on step 1 it is disabled so M3 dismisses normally.
        // Do NOT freeze dialog back properties here: that value is read once
        // when the dialog wrapper is created, so a sheet opened at step 2
        // could never be dismissed after stepping back.
        BackHandler(enabled = showCategories) { onBackToPapers() }
        SearchScopeSheetContent(
            papers = papers,
            selectedCategoryIds = selectedCategoryIds,
            showCategories = showCategories,
            chosenPaperId = chosenPaperId,
            onChoosePaper = onChoosePaper,
            onToggleCategory = onToggleCategory,
            onClearCategoriesInPaper = onClearCategoriesInPaper,
            onClearScope = onClearScope,
            onBackToPapers = onBackToPapers,
            onDismiss = onDismiss
        )
    }
}

/**
 * Stateless two-step body, extracted so the choreography (paper first, then
 * its categories) is testable without a ViewModel, a repository or a device.
 * Every tap applies straight to the applied selection; there is no draft.
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
    val chosenPaper = papers.firstOrNull { it.id == chosenPaperId }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
    ) {
        if (showCategories && chosenPaper != null) {
            CategoryStep(
                paper = chosenPaper,
                selectedCategoryIds = selectedCategoryIds,
                onToggleCategory = onToggleCategory,
                onClearCategoriesInPaper = onClearCategoriesInPaper,
                onBackToPapers = onBackToPapers
            )
        } else {
            PaperStep(
                papers = papers,
                onChoosePaper = onChoosePaper,
                onClearScope = onClearScope,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun PaperStep(
    papers: List<Paper>,
    onChoosePaper: (String) -> Unit,
    onClearScope: () -> Unit,
    onDismiss: () -> Unit
) {
    Text(
        text = stringResource(R.string.search_scope_choose_paper),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("search-scope-title-papers")
    )
    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag("search-scope-papers-list")
    ) {
        SheetRow(
            tag = "search-scope-all-papers",
            label = stringResource(R.string.search_filter_all_papers)
        ) {
            onClearScope()
            onDismiss()
        }
        papers.forEach { paper ->
            SheetRow(
                tag = "search-scope-paper-${paper.id}",
                label = paper.title
            ) { onChoosePaper(paper.id) }
        }
    }
}

@Composable
private fun CategoryStep(
    paper: Paper,
    selectedCategoryIds: Set<String>,
    onToggleCategory: (String) -> Unit,
    onClearCategoriesInPaper: () -> Unit,
    onBackToPapers: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onBackToPapers,
            modifier = Modifier.testTag("search-scope-back")
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back)
            )
        }
        Text(
            text = stringResource(R.string.search_scope_choose_categories),
            style = MaterialTheme.typography.titleMedium
        )
    }
    // The title is one ellipsised line, but TalkBack must hear the whole name —
    // two papers can share a category title, so the paper name is the disambiguator.
    Text(
        text = paper.title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .semantics { contentDescription = paper.title }
            .testTag("search-scope-title-categories")
    )
    Spacer(Modifier.height(8.dp))
    val flat = CategorySelection.flatten(paper.categories)
    val states = CategorySelection.states(paper.categories, selectedCategoryIds)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag("search-scope-categories-list")
    ) {
        SheetRow(
            tag = "search-scope-all-categories",
            label = stringResource(R.string.search_scope_all_categories_in, paper.title)
        ) { onClearCategoriesInPaper() }
        flat.forEachIndexed { index, (node, depth) ->
            val checkState = states[node.id] ?: CategoryCheckState.UNCHECKED
            // Two papers AND two branches inside one paper can share a title:
            // the whole pre-order path is the only unambiguous label.
            val path = categoryPath(flat, index)
            val qualified = stringResource(
                R.string.search_scope_category_with_paper, path, paper.title
            )
            val checkLabel = when (checkState) {
                CategoryCheckState.CHECKED -> stringResource(R.string.r2_study_option_selected)
                CategoryCheckState.INDETERMINATE -> stringResource(R.string.search_scope_category_partial)
                CategoryCheckState.UNCHECKED -> stringResource(R.string.r2_study_option_not_selected)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = (depth * 16).dp)
                    .testTag("search-scope-category-${node.id}")
                    .clickable { onToggleCategory(node.id) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = qualified
                        role = Role.Checkbox
                        toggleableState = when (checkState) {
                            CategoryCheckState.CHECKED -> ToggleableState.On
                            CategoryCheckState.INDETERMINATE -> ToggleableState.Indeterminate
                            CategoryCheckState.UNCHECKED -> ToggleableState.Off
                        }
                        stateDescription = checkLabel
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                TriStateCheckbox(
                    state = when (checkState) {
                        CategoryCheckState.CHECKED -> ToggleableState.On
                        CategoryCheckState.INDETERMINATE -> ToggleableState.Indeterminate
                        CategoryCheckState.UNCHECKED -> ToggleableState.Off
                    },
                    // The whole row is the target; a nested checkbox would steal taps.
                    onClick = null
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = qualified,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Pre-order ancestors of [index] joined with a chevron: "Optics › Lenses".
 * [CategorySelection.flatten] guarantees a node's parent is the nearest earlier
 * entry one level up, so no separate parent map is needed. Pure by design so
 * the same-title-in-one-paper rule is unit-testable without a composition.
 */
internal fun categoryPath(flat: List<Pair<CategoryNode, Int>>, index: Int): String {
    if (index !in flat.indices) return ""
    val parts = mutableListOf(flat[index].first.title)
    var depth = flat[index].second
    var i = index - 1
    while (depth > 0 && i >= 0) {
        while (i >= 0 && flat[i].second != depth - 1) i--
        if (i < 0) break
        parts.add(0, flat[i].first.title)
        depth--
        i--
    }
    return parts.joinToString(" › ")
}

/** A 48 dp clickable target with a single ellipsised line. */
@Composable
private fun SheetRow(tag: String, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label }
        )
    }
}
