package com.mcqapp.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.util.Logger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    repository: McqRepository,
    navController: NavController,
    viewModel: LibraryViewModel = viewModel()
) {
    val papers by viewModel.papers.collectAsState()
    val importReport by viewModel.importReport.collectAsState()
    val exportError by viewModel.exportError.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showPaperDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var categoryDialogPaperId by remember { mutableStateOf("") }
    var categoryDialogParentId by remember { mutableStateOf<String?>(null) }
    var pendingExportPaperId by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importJson(it) } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            val paperId = pendingExportPaperId
            if (paperId != null) viewModel.exportPaper(it, paperId) else viewModel.exportAll(it)
        }
        pendingExportPaperId = null
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    papers = papers,
                    onCloseDrawer = { scope.launch { drawerState.close() } },
                    onOpenHistory = {
                        scope.launch { drawerState.close() }
                        navController.navigate("history")
                    },
                    onOpenBookmarks = {
                        scope.launch { drawerState.close() }
                        navController.navigate("bookmarks")
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        navController.navigate("settings")
                    },
                    onStartTest = { paperId, categoryIds ->
                        scope.launch { drawerState.close() }
                        val csv = categoryIds.joinToString(",")
                        navController.navigate("test?paperId=$paperId&categories=$csv")
                    },
                    onImport = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    onDeleteCategory = { id -> viewModel.deleteCategory(id) }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("MCQ App") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Open menu")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showPaperDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New paper")
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (papers.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "No papers yet",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.loadSampleData() }) {
                                Text("Load sample paper")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 88.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(papers, key = { it.id }) { paper ->
                            PaperCard(
                                paper = paper,
                                onStart = { navController.navigate("test?paperId=${paper.id}&categories=") },
                                onBrowse = { navController.navigate("browse/${paper.id}") },
                                onExport = {
                                    pendingExportPaperId = paper.id
                                    exportLauncher.launch("${paper.title.replace(" ", "-")}.json")
                                },
                                onDelete = { viewModel.deletePaper(paper.id) },
                                onAddCategory = {
                                    categoryDialogPaperId = paper.id
                                    categoryDialogParentId = null
                                    showCategoryDialog = true
                                },
                                onEditQuestion = { questionId, categoryId ->
                                    navController.navigate(
                                        "editor?questionId=$questionId&paperId=${paper.id}&categoryId=$categoryId"
                                    )
                                },
                                onAddQuestion = { categoryId ->
                                    navController.navigate(
                                        "editor?questionId=&paperId=${paper.id}&categoryId=$categoryId"
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPaperDialog) {
        PaperDialog(
            onDismiss = { showPaperDialog = false },
            onSave = { title, description, duration, negative ->
                viewModel.addPaper(title, description, duration, negative)
                showPaperDialog = false
            }
        )
    }

    if (showCategoryDialog) {
        CategoryDialog(
            papers = papers,
            paperId = categoryDialogPaperId,
            parentId = categoryDialogParentId,
            onDismiss = { showCategoryDialog = false },
            onSave = { title, parent ->
                viewModel.addCategory(categoryDialogPaperId, title, parent)
                showCategoryDialog = false
            }
        )
    }

    importReport?.let { report ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissImportReport() },
            title = { Text("Import complete") },
            text = {
                Text(
                    "Papers: ${report.newPapers} new, ${report.updatedPapers} updated\n" +
                        "Questions: ${report.newQuestions} new, ${report.updatedQuestions} updated"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissImportReport() }) { Text("OK") }
            }
        )
    }

    exportError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun DrawerContent(
    papers: List<Paper>,
    onCloseDrawer: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartTest: (paperId: String, categoryIds: List<String>) -> Unit,
    onImport: () -> Unit,
    onDeleteCategory: (String) -> Unit
) {
    var expandedPaperId by remember { mutableStateOf<String?>(null) }
    val checkedCategories = remember { mutableStateOf(setOf<String>()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Menu",
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
                    Text("History")
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
                    Text("Bookmarks")
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
                    Text("Settings")
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
                                onDelete = onDeleteCategory
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onImport,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Import JSON")
            }
        }
    }
}

@Composable
private fun CategoryTree(
    nodes: List<CategoryNode>,
    checked: Set<String>,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    for (node in nodes) {
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
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onDelete(node.id) }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete category",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Column(modifier = Modifier.padding(start = 24.dp)) {
            CategoryTree(nodes = node.children, checked = checked, onToggle = onToggle, onDelete = onDelete)
        }
    }
}

@Composable
private fun PaperCard(
    paper: Paper,
    onStart: () -> Unit,
    onBrowse: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onAddCategory: () -> Unit,
    onEditQuestion: (String, String) -> Unit,
    onAddQuestion: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(paper.title, style = MaterialTheme.typography.titleMedium)
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
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Default.Edit, contentDescription = "Manage")
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = {
                    Logger.i("LIB", "Start paper: paperId=${paper.id}")
                    onStart()
                }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Start")
                }
                TextButton(onClick = onExport) {
                    Text("Export")
                }
                OutlinedButton(onClick = onBrowse) {
                    Text("Browse")
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text("Categories", style = MaterialTheme.typography.labelLarge)
                    paper.categories.forEach { node ->
                        CategoryRow(
                            node = node,
                            depth = 0,
                            onEditQuestion = onEditQuestion,
                            onAddQuestion = onAddQuestion
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onAddCategory, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Add category")
                        }
                        TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Delete paper")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    node: CategoryNode,
    depth: Int,
    onEditQuestion: (String, String) -> Unit,
    onAddQuestion: (String) -> Unit
) {
    Column(modifier = Modifier.padding(start = (depth * 16).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                node.title + if (node.totalQuestionCount > 0) " (${node.totalQuestionCount})" else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onAddQuestion(node.id) }, modifier = Modifier.height(32.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Add question", modifier = Modifier.padding(0.dp))
            }
        }
        node.children.forEach { child ->
            CategoryRow(
                node = child,
                depth = depth + 1,
                onEditQuestion = onEditQuestion,
                onAddQuestion = onAddQuestion
            )
        }
    }
}

@Composable
private fun PaperDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Double) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New paper") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it.filter { c -> c.isDigit() } },
                    label = { Text("Duration (minutes, 0 = untimed)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = negative,
                    onValueChange = { input -> negative = input.filter { it.isDigit() || it == '.' } },
                    label = { Text("Negative marking (e.g. 0.33)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        title.trim(),
                        description.trim(),
                        duration.toIntOrNull() ?: 0,
                        negative.toDoubleOrNull() ?: 0.0
                    )
                },
                enabled = title.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CategoryDialog(
    papers: List<Paper>,
    paperId: String,
    parentId: String?,
    onDismiss: () -> Unit,
    onSave: (title: String, parentId: String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    val paper = papers.firstOrNull { it.id == paperId }
    val categories = paper?.categories ?: emptyList()
    var selectedParent by remember { mutableStateOf(parentId) }
    var dropdownOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("Parent category", style = MaterialTheme.typography.labelMedium)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedParent == null,
                            onClick = { selectedParent = null }
                        )
                        Text("None (top level)")
                    }
                    categories.forEach { node ->
                        ParentOptions(
                            node = node,
                            depth = 0,
                            selectedParent = selectedParent,
                            onSelect = { selectedParent = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), selectedParent) },
                enabled = title.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ParentOptions(
    node: CategoryNode,
    depth: Int,
    selectedParent: String?,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = node.id == selectedParent,
            onClick = { onSelect(node.id) }
        )
        Text(node.title)
    }
    node.children.forEach { child ->
        ParentOptions(child, depth + 1, selectedParent, onSelect)
    }
}
