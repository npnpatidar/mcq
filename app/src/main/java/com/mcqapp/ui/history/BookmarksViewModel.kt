package com.mcqapp.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.util.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class BookmarksUiState(val count: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(BookmarksUiState())
    val state: StateFlow<BookmarksUiState> = _state.asStateFlow()

    /**
     * Rebuilt whenever the bookmark set changes, so removing (or adding) a
     * bookmark from anywhere refreshes the visible list. Pages carry content
     * only as they scroll in.
     */
    val pagingData: Flow<PagingData<com.mcqapp.domain.BookmarkedQuestion>> =
        repository.observeBookmarks()
            .map { it.size }
            .distinctUntilChanged()
            .flatMapLatest {
                Pager(
                    PagingConfig(
                        pageSize = BOOKMARKS_PAGE,
                        initialLoadSize = BOOKMARKS_PAGE,
                        enablePlaceholders = false
                    )
                ) {
                    repository.bookmarkedPagingSource()
                }.flow
            }
            .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            repository.observeBookmarks().collect { ids ->
                _state.value = BookmarksUiState(count = ids.size)
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
                val file = (
                    getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Could not open output stream")
                    ).use { sink ->
                    com.mcqapp.data.export.PaperExporter(repository.db())
                        .exportDto(
                            dto,
                            com.mcqapp.data.io.BookmarkExport.PAPER_TITLE,
                            format,
                            sink,
                            twoColumnPdf = repository.pdfTwoColumn().first()
                        )
                }
                Logger.i("BOOKVM", "Exported ${file.fileName}")
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

    companion object {
        private const val BOOKMARKS_PAGE = 50
    }
}
