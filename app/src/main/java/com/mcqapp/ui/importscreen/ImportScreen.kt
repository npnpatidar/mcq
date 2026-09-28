package com.mcqapp.ui.importscreen

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.util.QuestionImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    uri: String,
    navController: NavController,
    viewModel: ImportViewModel = viewModel(key = "import-$uri")
) {
    val state by viewModel.state.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadJson(android.net.Uri.parse(uri))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import JSON") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (state.loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Loading JSON…")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text("Paper details", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.paperTitle,
                        onValueChange = viewModel::updatePaperTitle,
                        label = { Text("Paper title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.paperDescription,
                        onValueChange = viewModel::updatePaperDescription,
                        label = { Text("Description (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = if (state.durationMinutes == 0) "" else state.durationMinutes.toString(),
                            onValueChange = { input -> viewModel.updateDuration(input.filter { it.isDigit() }.toIntOrNull() ?: 0) },
                            label = { Text("Duration (min)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = if (state.negativeMarking == 0.0) "" else state.negativeMarking.toString(),
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() || it == '.' }
                                viewModel.updateNegativeMarking(filtered.toDoubleOrNull() ?: 0.0)
                            },
                            label = { Text("Negative marking") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.categoryName,
                        onValueChange = viewModel::updateCategoryName,
                        label = { Text("Category name for all questions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Questions (${state.questions.size})", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                }

                items(state.questions, key = { it.id }) { question ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    question.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.deleteQuestion(question.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove question")
                                }
                            }
                            QuestionImage(src = question.image, modifier = Modifier.padding(top = 4.dp))
                            question.options.forEach { option ->
                                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                                    val isCorrect = option.id in question.correctOptionIds
                                    Text(
                                        if (isCorrect) "✓" else "○",
                                        color = if (isCorrect) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.width(18.dp),
                                        fontWeight = if (isCorrect) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(option.text, style = MaterialTheme.typography.bodySmall)
                                }
                                if (option.image != null) {
                                    QuestionImage(
                                        src = option.image,
                                        modifier = Modifier.padding(start = 18.dp, top = 1.dp)
                                    )
                                }
                            }
                            if (question.explanation.isNotBlank()) {
                                Text(
                                    "Explanation: ${question.explanation}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    viewModel.import {
                        navController.navigate("library") {
                            popUpTo("library") { inclusive = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.paperTitle.isNotBlank() && state.questions.isNotEmpty() && !state.importing
            ) {
                if (state.importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Import ${state.questions.size} questions")
            }
        }
    }
}
