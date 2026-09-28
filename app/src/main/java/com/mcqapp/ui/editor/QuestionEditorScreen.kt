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
import androidx.compose.runtime.collectAsState
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
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Difficulty
import com.mcqapp.ui.EditorViewModelFactory
import com.mcqapp.util.ImageUtils
import com.mcqapp.util.Logger
import com.mcqapp.util.QuestionImage

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
    val state by viewModel.state.collectAsState()

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New question" else "Edit question") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (state.loading) {
            Text(
                "Loading…",
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
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

            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::updateText,
                label = { Text("Question text") },
                modifier = Modifier.fillMaxWidth()
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
                    label = { Text("Image URL (optional)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { pickImageLauncher.launch("image/*") }) {
                    Text("Pick")
                }
            }
            if (state.image.isNotBlank()) {
                QuestionImage(src = state.image, modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(Modifier.height(16.dp))
            Text("Options", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Tick the correct answer(s)",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(4.dp))

            state.options.forEach { option ->
                OptionEditorRow(
                    option = option,
                    onTextChange = { viewModel.updateOptionText(option.id, it) },
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
                Text("Add option")
            }

            Spacer(Modifier.height(16.dp))
            DifficultyDropdown(
                difficulty = state.difficulty,
                onSelect = viewModel::updateDifficulty
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.explanation,
                onValueChange = viewModel::updateExplanation,
                label = { Text("Explanation") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.tags,
                onValueChange = viewModel::updateTags,
                label = { Text("Tags (comma separated)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.save { navController.popBackStack() } },
                    modifier = Modifier.weight(1f),
                    enabled = state.text.isNotBlank() &&
                        state.options.size >= 2 &&
                        state.options.all { it.text.isNotBlank() }
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text("Save")
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
    onTextChange: (String) -> Unit,
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
            OutlinedTextField(
                value = option.text,
                onValueChange = onTextChange,
                label = { Text("Option ${option.id}") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onMoveUp) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove option")
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = option.image,
                onValueChange = onImageChange,
                label = { Text("Option image URL (optional)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { onPickImage() }) {
                Text("Pick")
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
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            flat.forEach { node ->
                DropdownMenuItem(
                    text = { Text("  ".repeat(node.depth) + node.title) },
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
            label = { Text("Difficulty") },
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
