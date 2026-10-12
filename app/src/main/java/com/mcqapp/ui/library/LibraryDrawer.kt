package com.mcqapp.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.util.Logger

/** The navigation drawer: destinations, per-paper category picker, import. */
@Composable
internal fun DrawerContent(
    papers: List<Paper>,
    onCloseDrawer: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPassages: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartTest: (paperId: String, categoryIds: List<String>) -> Unit,
    onImport: () -> Unit,
    onDeleteCategory: (String) -> Unit,
    onMoveCategory: (String, Int) -> Unit,
    onNestCategory: (String) -> Unit,
    onPromoteCategory: (String) -> Unit,
    onEditCategory: (com.mcqapp.domain.CategoryNode) -> Unit
) {
    var expandedPaperId by remember { mutableStateOf<String?>(null) }
    val checkedCategories = remember { mutableStateOf(setOf<String>()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.menu),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )
        Divider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
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
            item {
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
            item {
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
            item {
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
            item {
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
            item { Divider() }
            items(papers, key = { it.id }) { paper ->
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedPaperId =
                                    if (expandedPaperId == paper.id) null else paper.id
                            }
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
                                checked = checkedCategories.value,
                                onToggle = { id ->
                                    checkedCategories.value = checkedCategories.value.toMutableSet().apply {
                                        if (!add(id)) remove(id)
                                    }
                                },
                                onDelete = onDeleteCategory,
                                onMove = onMoveCategory,
                                onNest = onNestCategory,
                                onPromote = onPromoteCategory,
                                onEdit = onEditCategory
                            )
                            val selected = checkedCategories.value
                            if (selected.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        Logger.i("LIB", "Drawer start test: paperId=${paper.id}, " +
                                            "categories=${selected.size} selected")
                                        onStartTest(paper.id, selected.toList())
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Start test (${selected.size} categories)")
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
    checked: Set<String>,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onNest: (String) -> Unit,
    onPromote: (String) -> Unit,
    onEdit: (CategoryNode) -> Unit
) {
    for ((index, node) in nodes.withIndex()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = node.id in checked,
                onCheckedChange = { onToggle(node.id) }
            )
            Text(
                node.title + if (node.totalQuestionCount > 0) " (${node.totalQuestionCount})" else "",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Outline gestures, inline on the node: up/down reorder among
            // siblings, -> nests into the category above (nothing moves
            // visually), <- promotes out of the parent, and the pencil opens
            // rename + re-parent.
            IconButton(onClick = { onMove(node.id, -1) }, enabled = index > 0) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.move_up),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { onMove(node.id, +1) }, enabled = index < nodes.size - 1) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.move_down),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { onNest(node.id) }, enabled = index > 0) {
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.nest_category),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { onPromote(node.id) }) {
                Icon(
                    Icons.Default.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.promote_category),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { onEdit(node) }) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.edit_category),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { onDelete(node.id) }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_category),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Column(modifier = Modifier.padding(start = 24.dp)) {
            CategoryTree(
                nodes = node.children,
                checked = checked,
                onToggle = onToggle,
                onDelete = onDelete,
                onMove = onMove,
                onNest = onNest,
                onPromote = onPromote,
                onEdit = onEdit
            )
        }
    }
}
