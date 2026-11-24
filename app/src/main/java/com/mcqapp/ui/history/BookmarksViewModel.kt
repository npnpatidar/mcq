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
import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.io.BookmarkExport
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.SelectionExport
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.repository.BookmarkScopeFilter
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.PaperFilteredBookmarkSource
import com.mcqapp.data.repository.RemovedBookmarks
import com.mcqapp.data.repository.filterBookmarksByScope
import com.mcqapp.data.repository.removeBookmarksBulk
import com.mcqapp.data.repository.restoreBookmarksBulk
import com.mcqapp.domain.BookmarkedQuestion
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.Paper
import com.mcqapp.ui.results.OneShotEvents
import com.mcqapp.util.AppStrings
import com.mcqapp.util.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookmarksUiState(
    /** Count of bookmarks matching the applied scope, whose question and category still resolve (R7). */
    val count: Int = 0,
    /** True when bookmarks exist anywhere, used to distinguish a scoped-empty from a truly empty list. */
    val hasAnyBookmarks: Boolean = false,
    /** False until the first bookmark observation lands, so no empty-state flash. */
    val loaded: Boolean = false,
    /** R8: dialog state in the ViewModel survives rotation. */
    val showFormatDialog: Boolean = false,
    val exportFormat: ExportFormat = ExportFormat.JSON_INLINE,
    /** Chosen paper id: null = all papers. */
    val filterPaperId: String? = null,
    /**
     * Ticked subtree ids inside [filterPaperId], expanded to every descendant.
     * Empty = all categories in that paper. Storing them expanded is what lets
     * the filter stay a flat paper-AND-category membership test.
     */
    val categoryFilterIds: Set<String> = emptySet()
)

/** The applied scope read from state — one value for paging, count and export. */
internal fun BookmarksUiState.scope(): BookmarkScopeFilter =
    BookmarkScopeFilter(filterPaperId, categoryFilterIds)

/**
 * The scope sheet applies changes immediately, so these are the whole state
 * transition. A paper and its categories travel together: changing paper drops
 * the old paper's categories, while re-choosing the same paper keeps them.
 * Kept as pure functions so the contract is JVM-testable without an Application.
 */
internal fun chooseBookmarkPaper(state: BookmarksUiState, paperId: String): BookmarksUiState =
    state.copy(
        filterPaperId = paperId,
        categoryFilterIds = if (state.filterPaperId == paperId) {
            state.categoryFilterIds
        } else {
            emptySet()
        }
    )

/** Toggle a whole subtree inside the chosen paper; a no-op with no chosen paper. */
internal fun toggleBookmarkCategory(
    state: BookmarksUiState,
    categoryId: String,
    papers: List<Paper>
): BookmarksUiState {
    val paperId = state.filterPaperId ?: return state
    val categories = papers.firstOrNull { it.id == paperId }?.categories ?: return state
    val subtree = CategorySelection.subtreeIds(
        CategorySelection.flatten(categories), categoryId
    ).orEmpty()
    if (subtree.isEmpty()) return state
    val next = if (subtree.all { it in state.categoryFilterIds }) {
        state.categoryFilterIds - subtree
    } else {
        state.categoryFilterIds + subtree
    }
    return state.copy(categoryFilterIds = next)
}

/** Categories-only clear for the sheet: the chosen paper stays put. */
internal fun clearBookmarkCategories(state: BookmarksUiState): BookmarksUiState =
    state.copy(categoryFilterIds = emptySet())

/** "All papers": clear the paper and its categories together. */
internal fun clearBookmarkScope(state: BookmarksUiState): BookmarksUiState =
    state.copy(filterPaperId = null, categoryFilterIds = emptySet())

/**
 * Drop ids that no longer exist in the loaded papers. A stale paper would keep
 * restricting the filter to zero hits while the trigger read "All papers"; a
 * stale category would do the same inside a live paper. An empty [papers] is
 * the initial-load state, not a deletion, so it must not clear anything.
 */
internal fun pruneBookmarkScope(state: BookmarksUiState, papers: List<Paper>): BookmarksUiState {
    if (papers.isEmpty()) return state
    val paperId = state.filterPaperId
    if (paperId == null) {
        return if (state.categoryFilterIds.isEmpty()) state else clearBookmarkScope(state)
    }
    val paper = papers.firstOrNull { it.id == paperId } ?: return clearBookmarkScope(state)
    val liveIds = CategorySelection.flatten(paper.categories).mapTo(mutableSetOf()) { it.first.id }
    val kept = state.categoryFilterIds intersect liveIds
    return if (kept == state.categoryFilterIds) state else state.copy(categoryFilterIds = kept)
}

/**
 * The whole "scoped bookmarks → exported DTO" step, pure so the sink that
 * decides WHICH questions are written is unit-testable without a repository.
 * Null means the scope has no bookmarks and nothing may be exported.
 *
 * The category ids are [SelectionExport.categoryIdFor] of the paper title, not
 * [BookmarkExport]'s positional `bookmarked-N`: a scoped export is a subset
 * whose title set changes with the scope, so a positional id would hand the
 * same id to different titles across exports and retitle a row on re-import.
 */
internal fun scopedBookmarkExportDto(
    bookmarks: List<BookmarkedQuestion>,
    scope: BookmarkScopeFilter,
    paperTitleByPaperId: Map<String, String>
): PaperDto? {
    val scoped = filterBookmarksByScope(bookmarks, scope)
    if (scoped.isEmpty()) return null
    val dto = BookmarkExport.paperDto(
        questions = scoped.map { it.question },
        paperTitleByCategoryId = scoped.associate {
            it.question.categoryId to (paperTitleByPaperId[it.paperId] ?: "Other")
        }
    )
    return dto.copy(
        categories = dto.categories.map { it.copy(id = SelectionExport.categoryIdFor(it.title)) }
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: McqRepository = (application as McqApplication).repository

    /** Friendly, localized copy for the learner; exception text goes to the log only. */
    private fun msg(resId: Int): String = AppStrings.getString(getApplication<Application>(), resId)

    private val _state = MutableStateFlow(BookmarksUiState())
    val state: StateFlow<BookmarksUiState> = _state.asStateFlow()

    /** Single source of truth for paging, count and export; recomputes only on a real scope change. */
    private val appliedScope: StateFlow<BookmarkScopeFilter> = _state
        .map { it.scope() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, BookmarkScopeFilter())

    /** Papers for the scope sheet and for provenance titles. */
    val papers: StateFlow<List<Paper>> = repository.observePapers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val paperTitles: StateFlow<Map<String, String>> = papers.map { list ->
        list.associate { it.id to it.title }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * One Pager generation per applied scope: the source invalidates itself on
     * bookmark writes, so removing from anywhere refreshes the visible list in
     * place. Changing the scope rebuilds a filtered source (and so resets the
     * scroll, which is the expected meaning of a new scope).
     */
    private var liveSource: androidx.paging.PagingSource<com.mcqapp.data.repository.BookmarkPageKey, com.mcqapp.domain.BookmarkedQuestion>? = null

    val pagingData: Flow<PagingData<com.mcqapp.domain.BookmarkedQuestion>> =
        appliedScope.flatMapLatest { scope ->
            Pager(
                PagingConfig(
                    pageSize = BOOKMARKS_PAGE,
                    initialLoadSize = BOOKMARKS_PAGE,
                    enablePlaceholders = false
                )
            ) {
                PaperFilteredBookmarkSource(repository.bookmarkedPagingSource(), scope)
                    .also { liveSource = it }
            }.flow
        }.cachedIn(viewModelScope)

    /** Null until the first real bookmark observation, so R7's header never flashes "empty". */
    private val _bookmarkIds = MutableStateFlow<List<String>?>(null)

    init {
        viewModelScope.launch {
            repository.observeBookmarks().collect { _bookmarkIds.value = it }
        }
        viewModelScope.launch {
            // The count must be the same scoped, resolvable set the list can
            // show. The raw table count includes dangling bookmarks (a question
            // or its category was deleted), which the pager drops. Recompute
            // from the bookmark ids through the same two-table resolution the
            // pager uses, then apply the whole applied scope — not just the paper.
            combine(_bookmarkIds, appliedScope, papers) { ids, scope, paperList ->
                if (ids == null) null else Triple(ids, scope, paperList)
            }.filterNotNull().collect { (ids, scope, paperList) ->
                val counts = resolvableCounts(
                    ids,
                    listOf(scope, BookmarkScopeFilter()),
                    paperIdByCategoryOf(paperList)
                )
                _state.update {
                    it.copy(
                        count = counts[0],
                        hasAnyBookmarks = counts[1] > 0,
                        loaded = true
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeBookmarkChanges().collect { liveSource?.invalidate() }
        }
        viewModelScope.launch {
            papers.collect { list -> _state.update { pruneBookmarkScope(it, list) } }
        }
    }

    /** Resolvable counts for each scope in one pass: a bookmark whose question
     * and category exist and whose paper/category match the scope. */
    private suspend fun resolvableCounts(
        ids: List<String>,
        scopes: List<BookmarkScopeFilter>,
        paperIdByCategory: Map<String, String>
    ): List<Int> {
        if (ids.isEmpty()) return List(scopes.size) { 0 }
        val db = repository.db()
        val questions = db.questionDao().getByIdsChunked(ids)
        if (questions.isEmpty()) return List(scopes.size) { 0 }
        val presentCategories = db.categoryDao()
            .getByIdsChunked(questions.map { it.categoryId }.distinct())
            .map { it.id }
            .toHashSet()
        return scopes.map { scope ->
            questions.count { q ->
                q.categoryId in presentCategories &&
                    scope.matches(paperIdByCategory[q.categoryId], q.categoryId)
            }
        }
    }

    private fun paperIdByCategoryOf(list: List<Paper>): Map<String, String> =
        list.flatMap { paper ->
            CategorySelection.flatten(paper.categories).map { it.first.id to it.first.paperId }
        }.toMap()

    // ----- Scope sheet actions: mirror Search's immediate-apply contract. -----

    /** Choose one paper; re-choosing it keeps its category selection. */
    fun choosePaper(paperId: String) {
        _state.update { chooseBookmarkPaper(it, paperId) }
    }

    /** Toggle a whole category subtree inside the chosen paper, if one is chosen. */
    fun toggleCategory(categoryId: String) {
        _state.update { toggleBookmarkCategory(it, categoryId, papers.value) }
    }

    /** Clear only the categories, leaving the chosen paper selected. */
    fun clearCategoriesInPaper() {
        _state.update { clearBookmarkCategories(it) }
    }

    /** "All papers": clear the paper and its categories together. */
    fun clearScopeFilters() {
        _state.update { clearBookmarkScope(it) }
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

    /** Bulk removal with a restorable snapshot, for the snackbar Undo. */
    private var lastRemoved: RemovedBookmarks? = null

    fun removeBookmarks(questionIds: Set<String>, onRemoved: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val removed = repository.removeBookmarksBulk(questionIds)
                lastRemoved = removed
                onRemoved(removed.size)
            } catch (e: Exception) {
                Logger.e("BOOKVM", "Bulk bookmark removal failed", e)
                onRemoved(0)
            }
        }
    }

    /** Guarded restore; a later write that re-bookmarked a row wins. */
    fun undoBulkRemove(onResult: (Int) -> Unit) {
        val snapshot = lastRemoved ?: run { onResult(0); return }
        lastRemoved = null
        viewModelScope.launch {
            try {
                onResult(repository.restoreBookmarksBulk(snapshot))
            } catch (e: Exception) {
                Logger.e("BOOKVM", "Bulk bookmark undo failed", e)
                onResult(0)
            }
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

    /**
     * The stream the export/share writers consume. Unscoped keeps the paginated
     * bookmark stream unchanged (no whole-list materialisation); a scoped view
     * hands every bookmark to [scopedBookmarkExportDto], which alone decides
     * which questions reach the sink, then adds only their passages.
     */
    private suspend fun bookmarkStreamForScope(): PaperStream? {
        val scope = appliedScope.value
        if (scope.isAll) return com.mcqapp.data.io.Exporter(repository.db()).bookmarkStream()
        val ids = repository.observeBookmarks().first()
        if (ids.isEmpty()) return null
        val questions = repository.getQuestionsByIds(ids)
        val paperIdByCategory = paperIdByCategoryOf(papers.value)
        val bookmarks = questions.mapNotNull { question ->
            paperIdByCategory[question.categoryId]?.let {
                BookmarkedQuestion(it, question)
            }
        }
        val dto = scopedBookmarkExportDto(
            bookmarks = bookmarks,
            scope = scope,
            paperTitleByPaperId = papers.value.associate { it.id to it.title }
        ) ?: return null
        val exporter = com.mcqapp.data.export.PaperExporter(repository.db())
        val withPassages = dto.copy(
            passages = exporter.passagesOfIds(
                dto.categories.flatMap { it.questions }.mapNotNull { it.passageId }.distinct()
            )
        )
        return PaperStream.of(withPassages)
    }

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
                    val stream = bookmarkStreamForScope()
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
                val stream = bookmarkStreamForScope()
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
