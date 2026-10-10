package com.mcqapp.ui.editor

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Passage
import com.mcqapp.util.ContentElements
import com.mcqapp.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PassagesUiState(
    val loading: Boolean = true,
    /** memberCount per passage id, refreshed with the list. */
    val memberCounts: Map<String, Int> = emptyMap(),
    val papers: List<com.mcqapp.domain.Paper> = emptyList()
)

class PassagesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(PassagesUiState())
    val state: StateFlow<PassagesUiState> = _state.asStateFlow()

    val passages: StateFlow<List<Passage>> = repository.observePassages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.observePapers().collect { papers ->
                _state.update { it.copy(loading = false, papers = papers) }
            }
        }
        viewModelScope.launch {
            passages.collect { list ->
                val counts = list.associate { passage ->
                    passage.id to repository.passageMemberCount(passage.id)
                }
                _state.update { it.copy(memberCounts = counts, loading = false) }
            }
        }
    }

    fun save(passage: Passage) {
        viewModelScope.launch { repository.savePassage(passage) }
    }

    fun delete(passageId: String, onRefused: (Int) -> Unit) {
        viewModelScope.launch {
            val members = repository.passageMemberCount(passageId)
            if (members > 0) {
                onRefused(members)
            } else {
                repository.deletePassage(passageId)
            }
        }
    }

    fun move(passageId: String, delta: Int) {
        viewModelScope.launch { repository.movePassage(passageId, delta) }
    }
}

/**
 * Passage management: list with member counts, create/edit dialogs, guarded
 * delete (D5) and reorder. Reached from the library; editing a question's
 * membership lives in the question editor's passage picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassagesScreen(
    repository: McqRepository,
    navController: androidx.navigation.NavController
) {
    val viewModel: PassagesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val passages by viewModel.passages.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Passage?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteRefusal by remember { mutableStateOf<Pair<String, Int>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.passages)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_passage))
            }
        }
    ) { padding ->
        if (passages.isEmpty() && !state.loading) {
            Text(
                stringResource(R.string.passages_empty),
                modifier = Modifier.padding(padding).padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(passages, key = { it.id }) { passage ->
                    val paperTitle = state.papers
                        .firstOrNull { paper -> paper.categories.any { it.id == passage.categoryId } }
                        ?.title.orEmpty()
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        passage.title.ifBlank { stringResource(R.string.passage) },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (paperTitle.isNotBlank()) {
                                        Text(
                                            paperTitle,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Text(
                                        stringResource(
                                            R.string.passage_member_count,
                                            state.memberCounts[passage.id] ?: 0
                                        ),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                IconButton(onClick = {
                                    viewModel.move(passage.id, -1)
                                }) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp,
                                        contentDescription = stringResource(R.string.move_up)
                                    )
                                }
                                IconButton(onClick = {
                                    viewModel.move(passage.id, +1)
                                }) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.move_down)
                                    )
                                }
                                IconButton(onClick = { editing = passage }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.edit_passage)
                                    )
                                }
                                IconButton(onClick = {
                                    viewModel.delete(passage.id) { members ->
                                        deleteRefusal = passage.id to members
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.delete_passage)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            ContentElements(
                                passage.elements,
                                textStyle = MaterialTheme.typography.bodySmall,
                                maxLines = 4
                            )
                        }
                    }
                }
            }
        }

        if (creating || editing != null) {
            PassageEditDialog(
                initial = editing,
                papers = state.papers,
                onSave = { passage ->
                    viewModel.save(passage)
                    creating = false
                    editing = null
                },
                onDismiss = {
                    creating = false
                    editing = null
                }
            )
        }

        deleteRefusal?.let { (_, members) ->
            AlertDialog(
                onDismissRequest = { deleteRefusal = null },
                title = { Text(stringResource(R.string.delete_passage)) },
                text = { Text(stringResource(R.string.passage_has_members_warning, members)) },
                confirmButton = {
                    TextButton(onClick = { deleteRefusal = null }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }
    }
}

/** Create/edit dialog: title, body and the paper category it lives in. */
@Composable
private fun PassageEditDialog(
    initial: Passage?,
    papers: List<com.mcqapp.domain.Paper>,
    onSave: (Passage) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var text by remember { mutableStateOf(initial?.text.orEmpty()) }
    var categoryId by remember { mutableStateOf(initial?.categoryId.orEmpty()) }

    val categories = papers.flatMap { paper ->
        fun flatten(nodes: List<com.mcqapp.domain.CategoryNode>): List<com.mcqapp.domain.CategoryNode> =
            nodes.flatMap { listOf(it) + flatten(it.children) }
        flatten(paper.categories)
    }
    androidx.compose.runtime.LaunchedEffect(categories) {
        if (categoryId.isBlank()) {
            categoryId = categories.firstOrNull()?.id.orEmpty()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) stringResource(R.string.new_passage)
                else stringResource(R.string.edit_passage)
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.passage_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.passage_text)) },
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )
                Spacer(Modifier.height(8.dp))
                CategoryPicker(
                    categories = categories,
                    selectedId = categoryId,
                    onSelect = { categoryId = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        Passage(
                            id = initial?.id.orEmpty(),
                            categoryId = categoryId,
                            title = title.trim(),
                            elements = listOf(
                                com.mcqapp.domain.ContentElement.TextElement(text)
                            )
                        )
                    )
                },
                enabled = title.isNotBlank() && categoryId.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(
    categories: List<com.mcqapp.domain.CategoryNode>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected?.title ?: stringResource(R.string.category),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.category)) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            categories.forEach { node ->
                DropdownMenuItem(
                    text = { Text(node.title) },
                    onClick = {
                        onSelect(node.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
