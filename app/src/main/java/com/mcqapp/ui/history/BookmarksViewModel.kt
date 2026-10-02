package com.mcqapp.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class BookmarksUiState(
    val questions: List<Question> = emptyList()
)

class BookmarksViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(BookmarksUiState())
    val state: StateFlow<BookmarksUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeBookmarks().collect { ids ->
                // Was three queries per bookmark, re-run on every toggle.
                val questions = repository.getQuestionsByIds(ids)
                _state.value = BookmarksUiState(questions = questions)
            }
        }
    }

    fun removeBookmark(questionId: String) {
        viewModelScope.launch { repository.toggleBookmark(questionId) }
    }

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    fun exportBookmarks(
        uri: android.net.Uri,
        format: com.mcqapp.data.export.ExportFormat
    ) {
        viewModelScope.launch {
            try {
                val dto = repository.getBookmarkExportDto()
                    ?: throw IllegalStateException("No bookmarked questions to export")
                val result = com.mcqapp.data.export.PaperExporter(repository.db())
                    .exportDto(
                        dto,
                        com.mcqapp.data.io.BookmarkExport.PAPER_TITLE,
                        format,
                        twoColumnPdf = repository.pdfTwoColumn().first()
                    )
                Logger.i("BOOKVM", "Exported ${result.fileName} (${result.bytes.size} bytes)")
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    it.write(result.bytes)
                } ?: throw IllegalStateException("Could not open output stream")
                _exportError.value = null
            } catch (e: Exception) {
                Logger.e("BOOKVM", "Export failed", e)
                _exportError.value = "Export failed: ${e.message}"
            }
        }
    }

    fun dismissError() {
        _exportError.value = null
    }
}
