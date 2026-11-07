package com.mcqapp.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.CategoryCheckState
import com.mcqapp.domain.CategoryCounts
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.Paper
import com.mcqapp.domain.rollUpCategoryCounts
import com.mcqapp.domain.totalCategoryCounts
import com.mcqapp.util.Logger

/** How a paper card's category area is presented (D1): one state, not two booleans. */
private enum class CardExpansion { Collapsed, Show, Manage }

/**
 * Saves the card's mode by name. An enum is Serializable, but spelling the
 * saver out keeps a future non-Serializable member from silently dropping the
 * state that makes Back from Browse land where the tap happened.
 */
private val CardExpansionSaver = Saver<CardExpansion, String>(
    save = { it.name },
    restore = { CardExpansion.valueOf(it) }
)

/**
 * The compact per-row count line the card and the drawer share: non-zero
 * terms only, the leeches word matching the card's "N tricky". Both callers
 * are composable, so the terms resolve from plurals at call time.
 */
@Composable
internal fun categoryCountsLine(counts: CategoryCounts): String =
    listOfNotNull(
        if (counts.due > 0) pluralStringResource(R.plurals.counts_due, counts.due, counts.due) else null,
        if (counts.fresh > 0) pluralStringResource(R.plurals.counts_new, counts.fresh, counts.fresh) else null,
        if (counts.leeches > 0) {
            pluralStringResource(R.plurals.counts_tricky, counts.leeches, counts.leeches)
        } else null
    ).joinToString(" · ")

/** One library card: a paper's actions, category management. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaperCard(
    paper: Paper,
    onStart: () -> Unit,
    onBrowse: (tickedIds: List<String>) -> Unit,
    onExport: (tickedIds: List<String>) -> Unit,
    onDelete: () -> Unit,
    onAddCategory: () -> Unit,
    onEditQuestion: (String, String) -> Unit,
    onAddQuestion: (String) -> Unit,
    onEditPaper: () -> Unit = {},
    onScopedTest: (List<String>) -> Unit = {},
    onMoveCategory: (String, Int) -> Unit = { _, _ -> },
    onNestCategory: (String) -> Unit = {},
    onPromoteCategory: (String) -> Unit = {},
    onEditCategory: (CategoryNode) -> Unit = {},
    onOpenCategory: (CategoryNode) -> Unit = {},
    onDeleteCategory: (String) -> Unit = {},
    onStudy: () -> Unit = {},
    onStudyLeeches: () -> Unit = {},
    mistakeCount: Int = 0,
    dueCount: Int = 0,
    freshCount: Int = 0,
    leechCount: Int = 0,
    selected: Set<String> = emptySet(),
    onToggleCategory: (String) -> Unit = {},
    onStartScopedStudy: (List<String>) -> Unit = {},
    categoryCounts: Map<String, CategoryCounts> = emptyMap(),
    onExpandCategories: () -> Unit = {},
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMovePaper: (Int) -> Unit = {}
) {
    // Once per (tree, selection), not per node: state() walks the subtree, so
    // a per-node call is O(n^2) on a large tree — the drawer computes the
    // same way. Declared before the drill state, whose bound reads `ticked`.
    val states = remember(paper.categories, selected) {
        CategorySelection.states(paper.categories, selected)
    }
    val ticked = remember(paper.categories, selected) {
        CategorySelection.tickedIds(paper.categories, selected)
    }
    val rolled = remember(paper.categories, categoryCounts) {
        rollUpCategoryCounts(paper.categories, categoryCounts)
    }
    val scopeTotal = remember(rolled, ticked) { totalCategoryCounts(rolled, ticked) }

    // One state with a mode (D1): two booleans allowed Show and Manage at
    // once, and two animated sections competed for the space.
    //
    // Saved, not plain `remember`: leaving the library for Browse and coming
    // back must land on the same card in the same mode — the tap that left was
    // inside this expanded section, so returning to a collapsed card loses the
    // place as well as the scroll offset (the section's height is what the
    // list was scrolled past).
    var expansion by rememberSaveable(stateSaver = CardExpansionSaver) {
        mutableStateOf(CardExpansion.Collapsed)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        paper.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("paper-title-${paper.id}")
                    )
                    if (paper.description.isNotBlank()) {
                        Text(
                            paper.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        buildString {
                            append("${paper.totalQuestions} questions")
                            if (paper.durationMinutes > 0) append(" • ${paper.durationMinutes} min")
                            if (paper.negativeMarking > 0) append(" • -${paper.negativeMarking} neg")
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                // The eye opens the read-only picker (D7); it sits left of
                // Manage, the same order as Show then Edit in the toolbar.
                IconButton(
                    onClick = {
                        expansion = if (expansion == CardExpansion.Show) {
                            CardExpansion.Collapsed
                        } else {
                            CardExpansion.Show
                        }
                        if (expansion == CardExpansion.Show) onExpandCategories()
                    },
                    modifier = Modifier.testTag("paper-show-${paper.id}")
                ) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.show_categories)
                    )
                }
                IconButton(
                    onClick = {
                        expansion = if (expansion == CardExpansion.Manage) {
                            CardExpansion.Collapsed
                        } else {
                            CardExpansion.Manage
                        }
                        if (expansion == CardExpansion.Manage) onExpandCategories()
                    },
                    modifier = Modifier.testTag("paper-manage-${paper.id}")
                ) {
                    // Tune, not a second pencil: Edit paper is now a
                    // first-class action in the row below, and two identical
                    // icons for different screens were a coin toss.
                    Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.manage))
                }
            }
            Spacer(Modifier.height(8.dp))
            // Study alone on its own full-width row: its label changes with
            // the due/new counts, and inside a FlowRow that kept resizing the
            // button and shuffling its neighbours. Text stays centred; the
            // remaining actions wrap below.
            Button(
                onClick = onStudy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("paper-study-${paper.id}"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        dueCount > 0 && freshCount > 0 ->
                            stringResource(R.string.paper_study_due_new, dueCount, freshCount)
                        dueCount > 0 ->
                            stringResource(R.string.paper_study_due, dueCount)
                        freshCount > 0 ->
                            stringResource(R.string.paper_study_new, freshCount)
                        else -> stringResource(R.string.study)
                    }
                )
            }
            // The scoped session (D5, D6), full width under the paper's own
            // Study button and outside the FlowRow: a count-bearing label in
            // the row would resize its neighbours. It exists only while
            // something is ticked, so an untouched card is unchanged.
            if (ticked.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onStartScopedStudy(ticked) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("paper-study-selection-${paper.id}"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when {
                            scopeTotal.due > 0 && scopeTotal.fresh > 0 ->
                                stringResource(
                                    R.string.paper_study_selection_due_new,
                                    scopeTotal.due, scopeTotal.fresh
                                )
                            scopeTotal.due > 0 ->
                                stringResource(R.string.paper_study_selection_due, scopeTotal.due)
                            scopeTotal.fresh > 0 ->
                                stringResource(R.string.paper_study_selection_new, scopeTotal.fresh)
                            else -> stringResource(R.string.paper_study_selection)
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Every action except Study is an icon. The buttons' widths
                // moved with their labels (and with the due/new counts in
                // Study), so the row wrapped differently on every card and a
                // phone showed two or three across. Icons keep one predictable
                // row and let the destructive and paper-level actions sit on
                // the card itself instead of behind Manage.
                IconButton(
                    onClick = { if (ticked.isEmpty()) onStart() else onScopedTest(ticked) },
                    modifier = Modifier.testTag("paper-test-${paper.id}")
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.test_action)
                    )
                }
                if (leechCount > 0) {
                    // Used to run the identical handler as "Study", so the
                    // label promised the tricky questions and delivered the
                    // ordinary due+new queue.
                    IconButton(onClick = onStudyLeeches) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = pluralStringResource(
                                R.plurals.counts_tricky, leechCount, leechCount
                            )
                        )
                    }
                }
                IconButton(
                    onClick = { onExport(ticked) },
                    modifier = Modifier.testTag("paper-export-${paper.id}")
                ) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = stringResource(R.string.export)
                    )
                }
                IconButton(
                    onClick = { onBrowse(ticked) },
                    modifier = Modifier.testTag("paper-browse-${paper.id}")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = stringResource(R.string.browse)
                    )
                }
                IconButton(
                    onClick = onAddCategory,
                    modifier = Modifier.testTag("paper-add-category-${paper.id}")
                ) {
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = stringResource(R.string.add_category)
                    )
                }
                IconButton(
                    onClick = onEditPaper,
                    modifier = Modifier.testTag("paper-edit-${paper.id}")
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit_paper)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("paper-delete-${paper.id}")
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_paper)
                    )
                }
                // Library reorder: up/down among the cards themselves. The
                // store normalises the sequence, so only the edges are
                // disabled.
                IconButton(
                    onClick = { onMovePaper(-1) },
                    enabled = canMoveUp,
                    modifier = Modifier.testTag("paper-move-up-${paper.id}")
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.move_paper_up)
                    )
                }
                IconButton(
                    onClick = { onMovePaper(+1) },
                    enabled = canMoveDown,
                    modifier = Modifier.testTag("paper-move-down-${paper.id}")
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.move_paper_down)
                    )
                }
            }
            AnimatedVisibility(visible = expansion != CardExpansion.Collapsed) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.categories), style = MaterialTheme.typography.labelLarge)
                    when (expansion) {
                        // Show is read-only (D7): pick what to study, manage
                        // nothing. No manage icons, no Add/Delete row.
                        CardExpansion.Show ->
                            paper.categories.forEach { node ->
                                ShowCategoryRow(
                                    node = node,
                                    depth = 0,
                                    states = states,
                                    rolled = rolled,
                                    onToggle = onToggleCategory
                                )
                            }
                        CardExpansion.Manage ->
                            ManageCategoryBlock(
                                paper = paper,
                                onMoveCategory = onMoveCategory,
                                onNestCategory = onNestCategory,
                                onPromoteCategory = onPromoteCategory,
                                onEditCategory = onEditCategory,
                                onOpenCategory = onOpenCategory,
                                onDeleteCategory = onDeleteCategory,
                                onEditQuestion = onEditQuestion,
                                onAddQuestion = onAddQuestion
                            )
                        CardExpansion.Collapsed -> Unit
                    }
                }
            }
        }
    }
}

/**
 * Show mode's row (D7): checkbox, title, and the subtree's non-zero counts.
 * No manage icons — this surface picks a study scope, it does not edit.
 */
@Composable
private fun ShowCategoryRow(
    node: CategoryNode,
    depth: Int,
    states: Map<String, CategoryCheckState>,
    rolled: Map<String, CategoryCounts>,
    onToggle: (String) -> Unit
) {
    Column(modifier = Modifier.padding(start = (depth * 24).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TriStateCheckbox(
                state = when (states[node.id]) {
                    CategoryCheckState.CHECKED -> ToggleableState.On
                    CategoryCheckState.UNCHECKED -> ToggleableState.Off
                    CategoryCheckState.INDETERMINATE -> ToggleableState.Indeterminate
                    null -> ToggleableState.Off
                },
                onClick = { onToggle(node.id) },
                modifier = Modifier.testTag("card-cat-${node.id}")
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    node.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val counts = rolled[node.id] ?: CategoryCounts()
                // A row with no eligible cards stays a plain title.
                if (!counts.isEmpty) {
                    Text(
                        categoryCountsLine(counts),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("card-counts-${node.id}")
                    )
                }
            }
        }
        node.children.forEach { child ->
            ShowCategoryRow(child, depth + 1, states, rolled, onToggle)
        }
    }
}

/**
 * Manage mode's block: the category rows. Add category, Edit paper and Delete
 * paper moved out to the card's always-visible icon row, so managing a paper
 * no longer starts with opening this expansion.
 *
 * Expanded is the default and a collapsed branch is remembered per category id
 * for as long as the card stays on screen: Manage is about rearranging the
 * tree, so hiding a branch has to be a deliberate act. It is not saved across
 * rotation, matching the card's own expansion state.
 */
@Composable
private fun ManageCategoryBlock(
    paper: Paper,
    onMoveCategory: (String, Int) -> Unit,
    onNestCategory: (String) -> Unit,
    onPromoteCategory: (String) -> Unit,
    onEditCategory: (CategoryNode) -> Unit,
    onOpenCategory: (CategoryNode) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onEditQuestion: (String, String) -> Unit,
    onAddQuestion: (String) -> Unit
) {
    // Collapsed branches by category id. Saved for the same reason as the
    // card's mode: Browse is one tap away from a row, and Back must not re-open
    // every branch the user had folded away.
    var collapsedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val onToggleCollapsed: (String) -> Unit = { id ->
        collapsedIds = if (id in collapsedIds) collapsedIds - id else collapsedIds + id
    }
    Column {
        paper.categories.forEachIndexed { index, node ->
            ManageCategoryRow(
                node = node,
                depth = 0,
                canMoveUp = index > 0,
                canMoveDown = index < paper.categories.lastIndex,
                isCollapsed = { it in collapsedIds },
                onToggleCollapsed = onToggleCollapsed,
                onMoveCategory = onMoveCategory,
                onNestCategory = onNestCategory,
                onPromoteCategory = onPromoteCategory,
                onEditCategory = onEditCategory,
                onOpenCategory = onOpenCategory,
                onDeleteCategory = onDeleteCategory,
                onEditQuestion = onEditQuestion,
                onAddQuestion = onAddQuestion
            )
        }
    }
}

/**
 * One category line: chevron, title (opens the category in Browse), `⋮`.
 *
 * The row used to split over two lines because seven 48dp controls do not fit
 * a phone: on a 360dp screen the card leaves 296dp, the arrows took 192dp and
 * the name got ~104dp, i.e. fourteen characters before the ellipsis. One line
 * of chevron + title + `⋮` gives the name ~200dp, and the actions that left the
 * row are labelled menu items rather than four unlabelled arrows — easier to
 * hit, and possible to hear. 48dp stays: UiAccessibilityTest rejects less.
 */
@Composable
private fun ManageCategoryRow(
    node: CategoryNode,
    depth: Int,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    isCollapsed: (String) -> Boolean = { false },
    onToggleCollapsed: (String) -> Unit = {},
    onMoveCategory: (String, Int) -> Unit = { _, _ -> },
    onNestCategory: (String) -> Unit = {},
    onPromoteCategory: (String) -> Unit = {},
    onEditCategory: (CategoryNode) -> Unit = {},
    onOpenCategory: (CategoryNode) -> Unit = {},
    onDeleteCategory: (String) -> Unit = {},
    onEditQuestion: (String, String) -> Unit,
    onAddQuestion: (String) -> Unit
) {
    val collapsed = isCollapsed(node.id)
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(start = (depth * 16).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (node.children.isNotEmpty()) {
                IconButton(
                    onClick = { onToggleCollapsed(node.id) },
                    modifier = Modifier.testTag("cat-expand-${node.id}")
                ) {
                    Icon(
                        if (collapsed) {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = stringResource(
                            if (collapsed) R.string.expand_category else R.string.collapse_category
                        )
                    )
                }
            } else {
                // A leaf holds the chevron's width so sibling titles line up.
                // A spacer, not a disabled button: there is nothing to expand,
                // and a control that reads out but never acts is worse.
                Spacer(Modifier.width(48.dp))
            }
            Text(
                node.title + if (node.totalQuestionCount > 0) " (${node.totalQuestionCount})" else "",
                style = MaterialTheme.typography.bodyMedium,
                // Two lines only when the name needs them; the tree indents
                // another 16dp per level.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenCategory(node) }
                    .padding(vertical = 12.dp)
                    .testTag("cat-title-${node.id}")
            )
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("cat-actions-${node.id}")
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.category_actions, node.title)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_category)) },
                        onClick = {
                            menuOpen = false
                            onEditCategory(node)
                        },
                        modifier = Modifier.testTag("cat-edit-${node.id}")
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_question)) },
                        onClick = {
                            menuOpen = false
                            onAddQuestion(node.id)
                        },
                        modifier = Modifier.testTag("cat-add-question-${node.id}")
                    )
                    HorizontalDivider()
                    // The four moves keep the tree-order rules the arrows had:
                    // up/down need a sibling, nesting needs a row above, and
                    // promoting needs a parent.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_up)) },
                        onClick = {
                            menuOpen = false
                            onMoveCategory(node.id, -1)
                        },
                        enabled = canMoveUp,
                        modifier = Modifier.testTag("cat-move-up-${node.id}")
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_down)) },
                        onClick = {
                            menuOpen = false
                            onMoveCategory(node.id, +1)
                        },
                        enabled = canMoveDown,
                        modifier = Modifier.testTag("cat-move-down-${node.id}")
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.nest_category)) },
                        onClick = {
                            menuOpen = false
                            onNestCategory(node.id)
                        },
                        enabled = canMoveUp,
                        modifier = Modifier.testTag("cat-nest-${node.id}")
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.promote_category)) },
                        onClick = {
                            menuOpen = false
                            onPromoteCategory(node.id)
                        },
                        enabled = depth > 0,
                        modifier = Modifier.testTag("cat-promote-${node.id}")
                    )
                    HorizontalDivider()
                    // Last, behind a divider, in the error colour: category
                    // deletion is immediate (no dialog), so it must not sit
                    // next to Move by muscle memory.
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.delete_category),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDeleteCategory(node.id)
                        },
                        modifier = Modifier.testTag("cat-delete-${node.id}")
                    )
                }
            }
        }
        if (!collapsed) {
            node.children.forEachIndexed { index, child ->
                ManageCategoryRow(
                    node = child,
                    depth = depth + 1,
                    canMoveUp = index > 0,
                    canMoveDown = index < node.children.lastIndex,
                    isCollapsed = isCollapsed,
                    onToggleCollapsed = onToggleCollapsed,
                    onMoveCategory = onMoveCategory,
                    onNestCategory = onNestCategory,
                    onPromoteCategory = onPromoteCategory,
                    onEditCategory = onEditCategory,
                    onOpenCategory = onOpenCategory,
                    onDeleteCategory = onDeleteCategory,
                    onEditQuestion = onEditQuestion,
                    onAddQuestion = onAddQuestion
                )
            }
        }
    }
}
