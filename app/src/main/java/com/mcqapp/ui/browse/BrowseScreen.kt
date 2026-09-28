package com.mcqapp.ui.browse

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.paper?.title ?: "Browse questions") },
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

        if (state.questions.isEmpty()) {
            Text(
                "No questions in this paper",
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                val filter = remember { mutableStateOf("All") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("All", "No answer", "No explanation", "No category").forEach { label ->
                        FilterChip(
                            selected = filter.value == label,
                            onClick = { filter.value = label },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val filtered = when (filter.value) {
                    "No answer" -> state.questions.filter { it.correctOptionIds.isEmpty() }
                    "No explanation" -> state.questions.filter { it.explanation.isBlank() }
                    "No category" -> state.questions.filter { it.categoryId.isBlank() }
                    else -> state.questions
                }
                if (filtered.isEmpty()) {
                    Text(
                        "No questions match this filter",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                filtered.forEachIndexed { index, question ->
                    BrowseQuestionCard(
                        index = index + 1,
                        question = question,
                        onEdit = {
                            navController.navigate(
                                "editor?questionId=${question.id}&paperId=$paperId&categoryId=${question.categoryId}"
                            )
                        },
                        onDelete = { viewModel.deleteQuestion(question.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowseQuestionCard(
    index: Int,
    question: com.mcqapp.domain.Question,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$index.",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 8.dp)
                )
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
            }

            if (question.explanation.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Explanation: ${question.explanation}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit question")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete question")
                }
            }
        }
    }
}
