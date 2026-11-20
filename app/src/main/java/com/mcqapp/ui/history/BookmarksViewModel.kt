package com.mcqapp.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mcqapp.McqApplication
import com.mcqapp.R
import com.mcqapp.data.export.ExportFormat
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.ui.results.OneShotEvents
import com.mcqapp.util.AppStrings
import com.mcqapp.util.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookmarksUiState(
    /** Count of bookmarks whose question and category still resolve, R7. */
    val count: Int = 0,
    /** False until the first bookmark observation lands, so no empty-state flash. */
    val loaded: Boolean = false,
    /** R8: dialog state in the ViewModel survives rotation. */
    val showFormatDialog: Boolean = false,
    val exportFormat: ExportFormat = ExportFormat.JSON_INLINE
)

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: McqRepository = (application as McqApplication).repository

    /** Friendly, localized copy for the learner; exception text goes to the log only. */
    private fun msg(resId: Int): String = AppStrings.getString(getApplication<Application>(), resId)

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
        // R7: the header must count the same rows the list can resolve. The
        // raw table count includes dangling bookmarks (a question or its
        // category was deleted), which the pager drops. Recompute from the
        // bookmark ids through the same two-table resolution the pager uses.
        viewModelScope.launch {
            repository.observeBookmarks().collect { ids ->
                val count = resolvableCount(ids)
                _state.update { it.copy(count = count, loaded = true) }
            }
        }
        // One generation, invalidated in place: a source cannot observe the
        // tracker itself reliably (the observer needs the tracker's refresh
        // cycle), but the change flow demonstrably fires per write.
        viewModelScope.launch {
            repository.observeBookmarkChanges().collect { liveSource?.invalidate() }
        }
    }

    /** Resolvable = a bookmark whose question exists and whose category exists. */
    private suspend fun resolvableCount(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val db = repository.db()
        val questions = db.questionDao().getByIdsChunked(ids)
        if (questions.isEmpty()) return 0
        val categoryIds = db.categoryDao()
            .getByIdsChunked(questions.map { it.categoryId }.distinct())
            .map { it.id }
            .toHashSet()
        return questions.count { it.categoryId in categoryIds }
    }

    fun removeBookmark(questionId: String) {
        // Idempotent delete, not a toggle: the row is still composed while the
        // list refreshes, so a second tap used to re-add the bookmark it had
        // just removed (consolidated_bugs.md F15).
        viewModelScope.launch { repository.deleteBookmark(questionId) }
    }

    /**
     * Undo for [removeBookmark]: add the bookmark back, but only when it is
     * gone. The guarded read keeps a double undo — or an undo after the row
     * was re-bookmarked elsewhere — from toggling a live bookmark off again.
     */
    fun restoreBookmark(questionId: String) {
        viewModelScope.launch {
            if (!repository.isBookmarked(questionId)) repository.toggleBookmark(questionId)
        }
    }

    // ----- R8: dialog state owned by the ViewModel, rotation-safe. -----

    fun openFormatDialog() {
        _state.update { it.copy(showFormatDialog = true) }
    }

    fun closeFormatDialog() {
        _state.update { it.copy(showFormatDialog = false) }
    }

    fun selectFormat(format: ExportFormat) {
        _state.update { it.copy(exportFormat = format) }
    }

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    fun exportBookmarks(
        uri: android.net.Uri,
        format: ExportFormat
    ) {
        viewModelScope.launch {
            try {
                // The output stream opens on IO: a slow or cloud-backed
                // provider used to open it on the main dispatcher and jank
                // (consolidated_bugs.md F18). Settings already did this.
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val stream = com.mcqapp.data.io.Exporter(repository.db()).bookmarkStream()
                        ?: throw IllegalStateException("No bookmarked questions to export")
                    com.mcqapp.util.renderToTempThenCopy(
                        cacheDir = getApplication<Application>().cacheDir,
                        openUriOutput = { mode ->
                            getApplication<Application>().contentResolver.openOutputStream(uri, mode)
                                ?: throw IllegalStateException("Could not open output stream")
                        },
                        render = { sink ->
                            com.mcqapp.data.export.PaperExporter(repository.db())
                                .exportStream(
                                    stream,
                                    com.mcqapp.data.io.BookmarkExport.PAPER_TITLE,
                                    format,
                                    sink,
                                    twoColumnPdf = repository.pdfTwoColumn().first()
                                )
                        }
                    )
                }
                Logger.i("BOOKVM", "Exported ${file.fileName}")
                _exportError.value = null
            } catch (e: Exception) {
                Logger.e("BOOKVM", "Export failed", e)
                _exportError.value = msg(R.string.final_bookmarks_export_failed)
            }
        }
    }

    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    /** R1: the share chooser is a one-shot event; the render owns a VM coroutine. */
    val shareEvents = OneShotEvents<android.content.Intent>()

    fun shareBookmarks(format: ExportFormat) {
        viewModelScope.launch {
            if (_isSharing.value) return@launch
            val intent = renderShareIntent(format)
            // Close the dialog only after the render finishes, never mid-share.
            closeFormatDialog()
            if (intent != null) shareEvents.send(intent)
        }
    }

    private suspend fun renderShareIntent(
        format: ExportFormat
    ): android.content.Intent? {
        _isSharing.value = true
        try {
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val app = getApplication<Application>()
                val stream = com.mcqapp.data.io.Exporter(repository.db()).bookmarkStream()
                    ?: throw IllegalStateException("No bookmarked questions to export")
                val file = com.mcqapp.util.nextSharedFile(
                    app.cacheDir,
                    com.mcqapp.data.export.PaperExporter.fileNameFor(
                        com.mcqapp.data.io.BookmarkExport.PAPER_TITLE,
                        format
                    )
                )
                val exported = java.io.FileOutputStream(file).use { sink ->
                    com.mcqapp.data.export.PaperExporter(repository.db())
                        .exportStream(
                            stream,
                            com.mcqapp.data.io.BookmarkExport.PAPER_TITLE,
                            format,
                            sink,
                            twoColumnPdf = repository.pdfTwoColumn().first()
                        )
                }
                Logger.i("BOOKVM", "Shared ${exported.fileName} (${file.length()} bytes)")
                com.mcqapp.util.shareIntent(app, file, format.mimeType)
            }
        } catch (e: Exception) {
            Logger.e("BOOKVM", "Share failed", e)
            _exportError.value = msg(R.string.final_bookmarks_export_failed)
            return null
        } finally {
            _isSharing.value = false
        }
    }

    fun dismissError() {
        _exportError.value = null
    }

    companion object {
        private const val BOOKMARKS_PAGE = 50
    }
}
