package com.mcqapp.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

/** The navigation drawer: destinations, per-paper category picker, import. */
@Composable
internal fun DrawerContent(
    papers: List<Paper>,
    onCloseDrawer: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPassages: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartTest: (paperId: String, categoryIds: List<String>) -> Unit,
    /**
     * The selection lives in [LibraryViewModel] (D2) — one owner for the
     * drawer and the card. `countsByPaper` feeds the per-row counts.
     */
    selectedByPaper: Map<String, Set<String>> = emptyMap(),
    onToggleCategory: (paperId: String, categoryId: String) -> Unit = { _, _ -> },
    onExpandCategories: (paperId: String) -> Unit = {},
    countsByPaper: Map<String, Map<String, CategoryCounts>> = emptyMap(),
    onImport: () -> Unit,
    /**
     * Starts a whole session over the ticked categories. Last in the list
     * because it is the newest callback; every call site passes it by name.
     */
    onStartStudy: (paperId: String, categoryIds: List<String>) -> Unit
) {
    var expandedPaperId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.menu),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )
        Divider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            item(contentType = "action") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onCloseDrawer()
                            onOpenHistory()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.History, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.history))
                }
            }
            item(contentType = "action") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onCloseDrawer()
                            onOpenStats()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.QueryStats, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.stats))
                }
            }
            item(contentType = "action") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onCloseDrawer()
                            onOpenBookmarks()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Bookmark, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.bookmarks))
                }
            }
            item(contentType = "action") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onCloseDrawer()
                            onOpenSearch()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.search))
                }
            }
            item(contentType = "action") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onCloseDrawer()
                            onOpenPassages()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Notes, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.passages))
                }
            }
            item(contentType = "action") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onCloseDrawer()
                            onOpenSettings()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.settings))
                }
            }
            item(contentType = "divider") { Divider() }
            items(papers, key = { it.id }, contentType = { "paper" }) { paper ->
                // The selection is read, not owned, here: it lives in the
                // ViewModel, so the card and the drawer cannot disagree (D2).
                val on = selectedByPaper[paper.id].orEmpty()
                // Once per (paper, selection), not per node: state() walks the
                // subtree, so a per-node call is O(n^2) on a large tree.
                val states = remember(paper.categories, on) {
                    CategorySelection.states(paper.categories, on)
                }
                val ticked = remember(paper.categories, on) {
                    CategorySelection.tickedIds(paper.categories, on)
                }
                val rolled = remember(paper.categories, countsByPaper[paper.id]) {
                    rollUpCategoryCounts(paper.categories, countsByPaper[paper.id].orEmpty())
                }
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedPaperId =
                                    if (expandedPaperId == paper.id) null else paper.id
                                if (expandedPaperId == paper.id) onExpandCategories(paper.id)
                            }
                            .testTag("drawer-paper-${paper.id}")
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (expandedPaperId == paper.id) Icons.Default.KeyboardArrowDown
                            else Icons.Default.KeyboardArrowRight,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            paper.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    AnimatedVisibility(visible = expandedPaperId == paper.id) {
                        Column(modifier = Modifier.padding(start = 24.dp)) {
                            CategoryTree(
                                nodes = paper.categories,
                                states = states,
                                counts = rolled,
                                onToggle = { id -> onToggleCategory(paper.id, id) }
                            )
                            if (ticked.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(8.dp)
                                ) {
                                    Button(
                                        onClick = { onStartStudy(paper.id, ticked) },
                                        modifier = Modifier.testTag("drawer-study")
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        // Ticked branches, not the expanded
                                        // scope: the label counts boxes.
                                        Text(
                                            stringResource(
                                                R.string.study_n_categories,
                                                ticked.size
                                            )
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { onStartTest(paper.id, ticked) },
                                        modifier = Modifier.testTag("drawer-test")
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.test_n_categories,
                                                ticked.size
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Divider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onImport,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.import_questions))
            }
        }
    }
}

@Composable
private fun CategoryTree(
    nodes: List<CategoryNode>,
    states: Map<String, CategoryCheckState>,
    counts: Map<String, CategoryCounts>,
    onToggle: (String) -> Unit
) {
    for (node in nodes) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TriStateCheckbox(
                state = when (states[node.id]) {
                    CategoryCheckState.CHECKED -> ToggleableState.On
                    CategoryCheckState.UNCHECKED -> ToggleableState.Off
                    CategoryCheckState.INDETERMINATE -> ToggleableState.Indeterminate
                    null -> ToggleableState.Off
                },
                onClick = { onToggle(node.id) },
                modifier = Modifier.testTag("drawer-cat-${node.id}")
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    node.title + if (node.totalQuestionCount > 0) " (${node.totalQuestionCount})" else "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Scheduling counts, not sizes: a row with nothing eligible
                // stays a plain title. totalQuestionCount above is a different
                // number — every question, eligible or not.
                val own = counts[node.id] ?: CategoryCounts()
                if (!own.isEmpty) {
                    Text(
                        categoryCountsLine(own),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("drawer-counts-${node.id}")
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(start = 24.dp)) {
            CategoryTree(
                nodes = node.children,
                states = states,
                counts = counts,
                onToggle = onToggle
            )
        }
    }
}
