package com.mcqapp.ui.history

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.export.ExportFormat
import com.mcqapp.data.export.PaperExporter
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.util.ContentElements
import com.mcqapp.util.QuestionImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    repository: McqRepository,
    navController: NavController,
    viewModel: BookmarksViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportError by viewModel.exportError.collectAsStateWithLifecycle()

    var showFormatDialog by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf(ExportFormat.JSON_INLINE) }
    var pendingFormat by remember { mutableStateOf<ExportFormat?>(null) }

    fun onExportDocument(uri: Uri?) {
        val format = pendingFormat
        pendingFormat = null
        if (uri != null && format != null) viewModel.exportBookmarks(uri, format)
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.JSON_INLINE.mimeType)
    ) { uri: Uri? -> onExportDocument(uri) }
    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.ZIP.mimeType)
    ) { uri: Uri? -> onExportDocument(uri) }
    val exportHtmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.HTML.mimeType)
    ) { uri: Uri? -> onExportDocument(uri) }
    val exportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.PDF.mimeType)
    ) { uri: Uri? -> onExportDocument(uri) }
    val exportApkgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.APKG.mimeType)
    ) { uri: Uri? -> onExportDocument(uri) }

    fun exportSaver(format: ExportFormat) = when (format) {
        ExportFormat.JSON_INLINE -> exportJsonLauncher
        ExportFormat.ZIP -> exportZipLauncher
        ExportFormat.HTML, ExportFormat.HTML_QUIZ -> exportHtmlLauncher
        ExportFormat.PDF, ExportFormat.PDF_ANSWER_KEY -> exportPdfLauncher
        ExportFormat.APKG -> exportApkgLauncher
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bookmarks") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.questions.isNotEmpty()) {
                        IconButton(onClick = { showFormatDialog = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Export bookmarks")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.questions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No bookmarked questions")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.questions, key = { it.question.id }) { bookmarked ->
                    val question = bookmarked.question
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                com.mcqapp.ui.editor.EditorSession.clear()
                                navController.navigate(
                                    com.mcqapp.ui.navigation.editorRoute(
                                        question.id,
                                        bookmarked.paperId,
                                        question.categoryId
                                    )
                                )
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                ContentElements(
                                    question.elements,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (question.explanationElements.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    ContentElements(
                                        question.explanationElements,
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                QuestionImage(src = question.explanationImage, contentDescription = "Explanation image")
                            }
                            IconButton(onClick = { viewModel.removeBookmark(question.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove bookmark")
                            }
                        }
                    }
                }
            }
        }
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

    if (showFormatDialog) {
        AlertDialog(
            onDismissRequest = {
                showFormatDialog = false
                pendingFormat = null
            },
            title = { Text("Export ${state.questions.size} bookmarked questions") },
            text = {
                Column {
                    ExportFormat.entries.forEach { format ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { exportFormat = format }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = exportFormat == format,
                                onClick = { exportFormat = format }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(format.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    format.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showFormatDialog = false
                    pendingFormat = exportFormat
                    exportSaver(exportFormat).launch(
                        PaperExporter.fileNameFor(
                            com.mcqapp.data.io.BookmarkExport.PAPER_TITLE,
                            exportFormat
                        )
                    )
                }) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFormatDialog = false
                    pendingFormat = null
                }) { Text("Cancel") }
            }
        )
    }
}
