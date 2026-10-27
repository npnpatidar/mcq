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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class BookmarksUiState(val count: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(BookmarksUiState())
    val state: StateFlow<BookmarksUiState> = _state.asStateFlow()

    /**
     * One Pager generation: the bookmarks change signal invalidates the live
     * paging source, so removing (or adding) a bookmark from anywhere
     * refreshes the visible list in place, with [getRefreshKey] keeping the
     * reader's position. The old `flatMapLatest` rebuilt the whole pager —
     * and reset its scroll to the top — on every toggle
     * (consolidated_bugs.md F14).
     */
    private var liveSource: androidx.paging.PagingSource<com.mcqapp.data.repository.BookmarkPageKey, com.mcqapp.domain.BookmarkedQuestion>? = null

    val pagingData: Flow<PagingData<com.mcqapp.domain.BookmarkedQuestion>> =
        Pager(
            PagingConfig(
                pageSize = BOOKMARKS_PAGE,
                initialLoadSize = BOOKMARKS_PAGE,
                enablePlaceholders = false
            )
        ) {
            repository.bookmarkedPagingSource().also { liveSource = it }
        }.flow
            .cachedIn(viewModelScope)

    init {
        // The count, not the ids: a "bookmark everything" bank is one COUNT.
        viewModelScope.launch {
            repository.observeBookmarkCount().collect { count ->
                _state.value = BookmarksUiState(count = count)
            }
        }
        // One generation, invalidated in place: a source cannot observe the
        // tracker itself reliably (the observer needs the tracker's refresh
        // cycle), but the change flow demonstrably fires per write.
        viewModelScope.launch {
            repository.observeBookmarkChanges().collect { liveSource?.invalidate() }
        }
    }

    fun removeBookmark(questionId: String) {
        // Idempotent delete, not a toggle: the row is still composed while the
        // list refreshes, so a second tap used to re-add the bookmark it had
        // just removed (consolidated_bugs.md F15).
        viewModelScope.launch { repository.deleteBookmark(questionId) }
    }

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    fun exportBookmarks(
        uri: android.net.Uri,
        format: com.mcqapp.data.export.ExportFormat
    ) {
        viewModelScope.launch {
            try {
                // The output stream opens on IO: a slow or cloud-backed
                // provider used to open it on the main dispatcher and jank
                // (consolidated_bugs.md F18). Settings already did this.
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val stream = com.mcqapp.data.io.Exporter(repository.db()).bookmarkStream()
                        ?: throw IllegalStateException("No bookmarked questions to export")
                    (
                        getApplication<Application>().contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("Could not open output stream")
                        ).use { sink ->
                        com.mcqapp.data.export.PaperExporter(repository.db())
                            .exportStream(
                                stream,
                                com.mcqapp.data.io.BookmarkExport.PAPER_TITLE,
                                format,
                                sink,
                                twoColumnPdf = repository.pdfTwoColumn().first()
                            )
                    }
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
