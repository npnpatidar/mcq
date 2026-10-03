package com.mcqapp.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import android.app.Application
import android.widget.Toast
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Difficulty
import com.mcqapp.ui.EditorViewModelFactory
import com.mcqapp.util.ContentElements
import com.mcqapp.util.ImageUtils
import com.mcqapp.util.Logger
import com.mcqapp.util.QuestionImage
import com.mcqapp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionEditorScreen(
    repository: McqRepository,
    questionId: String,
    paperId: String,
    categoryId: String,
    navController: NavController
) {
    val context = LocalContext.current
    val viewModel: EditorViewModel = viewModel(
        key = "editor-$questionId-$paperId-$categoryId",
        factory = EditorViewModelFactory(
            context.applicationContext as Application,
            questionId,
            paperId,
            categoryId
        )
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val encoded = ImageUtils.encodeImageUri(context, uri)
            if (encoded != null) {
                viewModel.updateImage(encoded)
            } else {
                Logger.e("EDITOR", "Failed to encode question image")
            }
        }
    }

    val pickExplanationImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val encoded = ImageUtils.encodeImageUri(context, uri)
            if (encoded != null) {
                viewModel.updateExplanationImage(encoded)
            } else {
                Logger.e("EDITOR", "Failed to encode explanation image")
            }
        }
    }

    var pickingOptionImageId by remember { mutableStateOf<String?>(null) }
    val pickOptionImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val optionId = pickingOptionImageId
        pickingOptionImageId = null
        if (uri != null && optionId != null) {
            val encoded = ImageUtils.encodeImageUri(context, uri)
            if (encoded != null) {
                viewModel.updateOptionImage(optionId, encoded)
            } else {
                Logger.e("EDITOR", "Failed to encode option image for $optionId")
            }
        }
    }

    // Generic gallery picker for image blocks: stores the block's write-back
    // until the gallery returns, then delivers the encoded image to it.
    var pendingBlockPick by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val pickBlockImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val writeBack = pendingBlockPick
        pendingBlockPick = null
        if (uri != null && writeBack != null) {
            val encoded = ImageUtils.encodeImageUri(context, uri)
            if (encoded != null) {
                writeBack(encoded)
            } else {
                Logger.e("EDITOR", "Failed to encode block image")
            }
        }
    }
    val pickBlockImage: ((String) -> Unit) -> Unit = { onPicked ->
        pendingBlockPick = onPicked
        pickBlockImageLauncher.launch("image/*")
    }

    val canProceed = viewModel.canProceed()
    val queueIndex = EditorSession.index
    val queueSize = EditorSession.ids.size
    val prevId = EditorSession.prevId
    val nextId = EditorSession.nextId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New question" else "Edit question") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (EditorSession.hasQueue && queueSize > 0) {
                        Text(
                            "${queueIndex + 1}/$queueSize",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        TextButton(
                            onClick = {
                                prevId?.let {
                                    viewModel.moveToQuestion(it) { saved ->
                                        if (saved) Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = canProceed && prevId != null
                        ) {
                            Text(stringResource(R.string.prev))
                        }
                        TextButton(
                            onClick = {
                                nextId?.let {
                                    viewModel.moveToQuestion(it) { saved ->
                                        if (saved) Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = canProceed && nextId != null
                        ) {
                            Text(stringResource(R.string.next))
                        }
                    }
                }
            )
        }
    ) { padding ->
        val showLoading = com.mcqapp.util.rememberDelayedVisibility(state.loading)
        if (showLoading) {
            Text(
                stringResource(R.string.loading),
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (state.categories.isNotEmpty()) {
                CategoryDropdown(
                    categories = state.categories,
                    selectedId = state.categoryId,
                    onSelect = { viewModel.updateCategory(it) }
                )
                Spacer(Modifier.height(8.dp))
            }

            Text(stringResource(R.string.question), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            BlockListEditor(
                elements = state.elements,
                onAddBlock = viewModel::addBlock,
                onUpdateBlock = viewModel::updateBlock,
                onRemoveBlock = viewModel::removeBlock,
                onMoveUp = viewModel::moveBlockUp,
                onMoveDown = viewModel::moveBlockDown,
                onUpdateCell = viewModel::updateTableCell,
                onAddRow = viewModel::addTableRow,
                onRemoveRow = viewModel::removeTableRow,
                onAddColumn = viewModel::addTableColumn,
                onRemoveColumn = viewModel::removeTableColumn,
                onPickImage = pickBlockImage
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.image,
                    onValueChange = viewModel::updateImage,
                    label = { Text(stringResource(R.string.image_url_optional)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { pickImageLauncher.launch("image/*") }) {
                    Text(stringResource(R.string.pick))
                }
            }
            if (state.image.isNotBlank()) {
                QuestionImage(src = state.image, modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.preview), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                ContentElements(state.elements, modifier = Modifier.padding(12.dp))
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.options), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.tick_the_correct_answer_s),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(4.dp))

            state.options.forEach { option ->
                OptionEditorRow(
                    option = option,
                    onAddBlock = { viewModel.addOptionBlock(option.id, it) },
                    onUpdateBlock = { index, element ->
                        viewModel.updateOptionBlock(option.id, index, element)
                    },
                    onRemoveBlock = { viewModel.removeOptionBlock(option.id, it) },
                    onMoveBlockUp = { viewModel.moveOptionBlockUp(option.id, it) },
                    onMoveBlockDown = { viewModel.moveOptionBlockDown(option.id, it) },
                    onPickBlockImage = pickBlockImage,
                    onImageChange = { viewModel.updateOptionImage(option.id, it) },
                    onPickImage = {
                        pickingOptionImageId = option.id
                        pickOptionImageLauncher.launch("image/*")
                    },
                    onToggleCorrect = { viewModel.toggleCorrect(option.id) },
                    onMoveUp = { viewModel.moveOptionUp(option.id) },
                    onMoveDown = { viewModel.moveOptionDown(option.id) },
                    onRemove = { viewModel.removeOption(option.id) },
                    canRemove = state.options.size > 2
                )
                Spacer(Modifier.height(8.dp))
            }

            OutlinedButton(onClick = viewModel::addOption) {
                Text(stringResource(R.string.add_option))
            }

            Spacer(Modifier.height(16.dp))
            DifficultyDropdown(
                difficulty = state.difficulty,
                onSelect = viewModel::updateDifficulty
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.marks,
                onValueChange = viewModel::updateMarks,
                label = { Text(stringResource(R.string.marks_default_1)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.explanation), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            // No table cell callbacks: the ViewModel exposes cell ops for the
            // question body only, so explanation tables render read-only.
            BlockListEditor(
                elements = state.explanationElements,
                onAddBlock = viewModel::addExplanationBlock,
                onUpdateBlock = viewModel::updateExplanationBlock,
                onRemoveBlock = viewModel::removeExplanationBlock,
                onMoveUp = viewModel::moveExplanationBlockUp,
                onMoveDown = viewModel::moveExplanationBlockDown,
                onPickImage = pickBlockImage
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.explanationImage,
                    onValueChange = viewModel::updateExplanationImage,
                    label = { Text(stringResource(R.string.explanation_image_url_optional)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { pickExplanationImageLauncher.launch("image/*") }) {
                    Text(stringResource(R.string.pick))
                }
            }
            if (state.explanationImage.isNotBlank()) {
                QuestionImage(src = state.explanationImage, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.tags,
                onValueChange = viewModel::updateTags,
                label = { Text(stringResource(R.string.tags_comma_separated)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.save { navController.popBackStack() } },
                    modifier = Modifier.weight(1f),
                    enabled = canProceed
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text(stringResource(R.string.save))
                }
                if (!state.isNew) {
                    OutlinedButton(onClick = {
                        viewModel.delete { navController.popBackStack() }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun OptionEditorRow(
    option: OptionEditorState,
    onAddBlock: (EditorBlockType) -> Unit,
    onUpdateBlock: (index: Int, element: ContentElement) -> Unit,
    onRemoveBlock: (index: Int) -> Unit,
    onMoveBlockUp: (index: Int) -> Unit,
    onMoveBlockDown: (index: Int) -> Unit,
    onPickBlockImage: ((String) -> Unit) -> Unit,
    onImageChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onToggleCorrect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = option.isCorrect, onCheckedChange = { onToggleCorrect() })
            Text(
                "Option ${option.id}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onMoveUp) {
                Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.move_up))
            }
            IconButton(onClick = onMoveDown) {
                Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.move_down))
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_option))
                }
            }
        }
        // Option tables render read-only: options rarely need tables and the
        // ViewModel exposes cell ops for the question body only, so full grid
        // editing is omitted here to keep scope sane.
        BlockListEditor(
            elements = option.elements,
            onAddBlock = onAddBlock,
            onUpdateBlock = onUpdateBlock,
            onRemoveBlock = onRemoveBlock,
            onMoveUp = onMoveBlockUp,
            onMoveDown = onMoveBlockDown,
            onPickImage = onPickBlockImage
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = option.image,
                onValueChange = onImageChange,
                label = { Text(stringResource(R.string.option_image_url_optional)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { onPickImage() }) {
                Text(stringResource(R.string.pick))
            }
        }
        if (option.image.isNotBlank()) {
            QuestionImage(
                src = option.image,
                modifier = Modifier
                    .padding(start = 48.dp, top = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(
    categories: List<CategoryNode>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    val flat = flattenCategories(categories)
    var expanded by remember { mutableStateOf(false) }
    val selectedTitle = flat.firstOrNull { it.id == selectedId }?.title ?: "Select category"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedTitle,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.category)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            flat.forEach { node ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.label).repeat(node.depth) + node.title) },
                    onClick = {
                        onSelect(node.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DifficultyDropdown(
    difficulty: Difficulty,
    onSelect: (Difficulty) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = difficulty.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.difficulty)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Difficulty.entries.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.label) },
                    onClick = {
                        onSelect(d)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun flattenCategories(
    nodes: List<CategoryNode>,
    depth: Int = 0
): List<FlatCategory> =
    nodes.flatMap { node ->
        listOf(FlatCategory(node.id, node.title, depth)) +
            flattenCategories(node.children, depth + 1)
    }

private data class FlatCategory(val id: String, val title: String, val depth: Int)
