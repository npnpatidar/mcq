package com.mcqapp.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.ui.BrowseViewModelFactory
import com.mcqapp.util.Logger
import com.mcqapp.util.QuestionImage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(
    repository: McqRepository,
    paperId: String,
    navController: NavController,
    viewModel: BrowseViewModel = viewModel(
        key = "browse-$paperId",
        factory = BrowseViewModelFactory(
            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application,
            paperId
        )
    )
) {
    val state by viewModel.state.collectAsState()
    val allPapers by viewModel.papers.collectAsState()
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) "${selectedIds.size} selected"
                        else state.paper?.title ?: "Browse questions"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectionMode) {
                            selectionMode = false
                            selectedIds = emptySet()
                        } else {
                            navController.popBackStack()
                        }
                    }) {
                        Icon(
                            if (selectionMode) Icons.Default.Close
                            else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(
                            onClick = { showMoveDialog = true },
                            enabled = selectedIds.isNotEmpty()
                        ) { Text("Move") }
                        TextButton(
                            onClick = { showDeleteConfirm = true },
                            enabled = selectedIds.isNotEmpty()
                        ) { Text("Delete") }
                    } else {
                        TextButton(onClick = { selectionMode = true }) { Text("Select") }
                    }
                }
            )
        }
    ) { padding ->
        // Delayed spinner: fast loads show blank instead of flashing text.
        val showLoading = com.mcqapp.util.rememberDelayedVisibility(state.loading)
        if (showLoading) {
            Text(
                "Loading…",
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            )
            return@Scaffold
        }
        if (state.loading) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            return@Scaffold
        }

        if (state.questions.isEmpty()) {
            Text(
                "No questions in this paper",
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            )
            return@Scaffold
        }

        // Hoisted + memoized: cards used to render inside a single lazy item
        // via forEach, defeating virtualization and OOMing big papers.
        val filter = remember { mutableStateOf("All") }
        val questions = state.questions
        val filterValue = filter.value
        var searchQuery by remember { mutableStateOf("") }
        val searched = remember(questions, searchQuery) {
            com.mcqapp.domain.QuestionSearch.filter(questions, searchQuery)
        }
        val filtered = remember(searched, filterValue, state.paper) {
            val titles = flattenCategories(state.paper?.categories ?: emptyList())
                .associate { it.first.id to it.first.title }
            when (filterValue) {
                "No answer" -> searched.filter { it.correctOptionIds.isEmpty() }
                "No explanation" -> searched.filter { it.explanation.isBlank() }
                "Uncategorized" -> com.mcqapp.domain.QuestionSearch
                    .filterUncategorized(searched, titles)
                else -> searched
            }
        }
        val filteredIds = remember(filtered) { filtered.map { it.id } }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search text, tags, options") },
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("All", "No answer", "No explanation", "Uncategorized").forEach { label ->
                        FilterChip(
                            selected = filter.value == label,
                            onClick = { filter.value = label },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No questions match this filter",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
            val reorderEnabled = filterValue == "All" && searchQuery.isBlank()
            itemsIndexed(filtered, key = { _, question -> question.id }) { index, question ->
                BrowseQuestionCard(
                    index = index + 1,
                    question = question,
                    selectionMode = selectionMode,
                    selected = question.id in selectedIds,
                    canMoveUp = reorderEnabled &&
                        com.mcqapp.domain.Reorder.canMove(filtered, index, -1),
                    canMoveDown = reorderEnabled &&
                        com.mcqapp.domain.Reorder.canMove(filtered, index, +1),
                    onMoveUp = {
                        viewModel.swapQuestions(question.id, filtered[index - 1].id)
                    },
                    onMoveDown = {
                        viewModel.swapQuestions(question.id, filtered[index + 1].id)
                    },
                    onToggleSelect = {
                        selectedIds = selectedIds.toMutableSet().apply {
                            if (!add(question.id)) remove(question.id)
                        }
                    },
                    onEdit = {
                        com.mcqapp.ui.editor.EditorSession.start(
                            ids = filteredIds,
                            index = index
                        )
                        navController.navigate(
                            "editor?questionId=${question.id}&paperId=$paperId&categoryId=${question.categoryId}"
                        )
                    },
                    onDelete = { viewModel.deleteQuestion(question.id) },
                    onDuplicate = { viewModel.duplicateQuestion(question.id) }
                )
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete ${selectedIds.size} questions?") },
                text = { Text("This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteQuestions(selectedIds)
                        selectedIds = emptySet()
                        selectionMode = false
                        showDeleteConfirm = false
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                }
            )
        }

        if (showMoveDialog) {
            // Local to the dialog: forgotten on dismiss, so every open starts fresh.
            var moveIsCopy by remember { mutableStateOf(false) }
            var movePaperId by remember { mutableStateOf(paperId) }
            var paperMenuOpen by remember { mutableStateOf(false) }
            val movePaper = remember(allPapers, movePaperId) {
                allPapers.find { it.id == movePaperId }
            }
            val targets = remember(movePaper) {
                flattenCategories(movePaper?.categories ?: emptyList())
            }
            AlertDialog(
                onDismissRequest = { showMoveDialog = false },
                title = {
                    Text("${if (moveIsCopy) "Copy" else "Move"} ${selectedIds.size} questions to…")
                },
                text = {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !moveIsCopy,
                                onClick = { moveIsCopy = false },
                                label = { Text("Move") }
                            )
                            FilterChip(
                                selected = moveIsCopy,
                                onClick = { moveIsCopy = true },
                                label = { Text("Copy") }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        ExposedDropdownMenuBox(
                            expanded = paperMenuOpen,
                            onExpandedChange = { paperMenuOpen = it }
                        ) {
                            OutlinedTextField(
                                value = movePaper?.title ?: "Select paper",
                                onValueChange = {},
                                readOnly = true,
                                singleLine = true,
                                label = { Text("Paper") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = paperMenuOpen)
                                },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                            )
                            DropdownMenu(
                                expanded = paperMenuOpen,
                                onDismissRequest = { paperMenuOpen = false }
                            ) {
                                allPapers.forEach { paper ->
                                    DropdownMenuItem(
                                        text = { Text(paper.title) },
                                        onClick = {
                                            movePaperId = paper.id
                                            paperMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        targets.forEach { (node, depth) ->
                            TextButton(
                                onClick = {
                                    if (moveIsCopy) {
                                        viewModel.copyQuestions(selectedIds, node.id)
                                    } else {
                                        viewModel.moveQuestions(selectedIds, node.id)
                                    }
                                    selectedIds = emptySet()
                                    selectionMode = false
                                    showMoveDialog = false
                                }
                            ) {
                                Text(
                                    "${"— ".repeat(depth)}${node.title} (${node.questionCount})",
                                    maxLines = 1
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showMoveDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun BrowseQuestionCard(
    index: Int,
    question: com.mcqapp.domain.Question,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                } else {
                    Text(
                        "$index.",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(
                    question.text,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
            }
            QuestionImage(src = question.image, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(8.dp))
            question.options.forEach { option ->
                val isCorrect = option.id in question.correctOptionIds
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        if (isCorrect) "✓" else "○",
                        color = if (isCorrect) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(20.dp),
                        fontWeight = if (isCorrect) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(option.text, style = MaterialTheme.typography.bodyMedium)
                }
                if (option.image != null) {
                    QuestionImage(
                        src = option.image,
                        modifier = Modifier.padding(start = 20.dp, top = 2.dp)
                    )
                }
            }

            if (question.explanation.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Explanation: ${question.explanation}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            QuestionImage(src = question.explanationImage)

            Spacer(Modifier.height(8.dp))
            if (!selectionMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canMoveUp || canMoveDown) {
                        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                        }
                        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                        }
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit question")
                    }
                    IconButton(onClick = onDuplicate) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate question")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete question")
                    }
                }
            }
        }
    }
}

private fun flattenCategories(
    nodes: List<com.mcqapp.domain.CategoryNode>,
    depth: Int = 0
): List<Pair<com.mcqapp.domain.CategoryNode, Int>> = buildList {
    for (node in nodes) {
        add(node to depth)
        addAll(flattenCategories(node.children, depth + 1))
    }
}
