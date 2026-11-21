package com.mcqapp.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.QuestionSearch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val scope: QuestionSearch.Scope = QuestionSearch.Scope.ALL
)

internal data class SearchRequest(val query: String, val scope: QuestionSearch.Scope)

/**
 * True while the live request differs from the last committed one — i.e. the
 * debounce window is still open. Comparing the WHOLE request is the point: a
 * scope-only change used to read as "not debouncing" and show the previous
 * scope's hits with no "Searching…" cue for the whole 300 ms.
 */
internal fun isSearchPending(current: SearchRequest, committed: SearchRequest): Boolean =
    current.query.isNotBlank() && current != committed

/** A blank query is a prompt to type, never a "Searching…" state. */
internal fun searchShowsTypeHint(query: String, debouncing: Boolean): Boolean =
    query.isBlank() && !debouncing

/**
 * Cross-paper search, paged. The old path asked the repository for every hit
 * at once, which for a one-letter query over a 250k-question bank
 * materialised the entire match set (element JSON and base64 images included)
 * before showing a single row. Here the first page paints after the 300 ms
 * debounce and the rest streams in as the list is scrolled.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    // The WHOLE request is debounced, not the query alone: a scope tap used
    // to re-emit immediately with the last DEBOUNCED query, painting stale
    // results for 300 ms while the pending debounce kept running
    // (consolidated_bugs.md B3). With the request as the unit, a scope change
    // and a keystroke both restart the same window and commit together.
    //
    // Shared, not cold: the result flow, the pending cue and the row snippet
    // all have to agree on which request is committed. A cold flow would give
    // each collector its own debounce timer, so for a few milliseconds the
    // rows and the snippet could be describing two different queries.
    private val committed: SharedFlow<SearchRequest> = _state
        .map { SearchRequest(it.query.trim(), it.scope) }
        .distinctUntilChanged()
        .debounce(SEARCH_DEBOUNCE_MS)
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val currentRequest: Flow<SearchRequest> = _state
        .map { SearchRequest(it.query.trim(), it.scope) }

    val debouncing: StateFlow<Boolean> = combine(currentRequest, committed) { current, request ->
        isSearchPending(current, request)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The query the visible hits were produced for: the committed (debounced)
     * one, never the live text field. A row's "Answer: …" snippet must explain
     * *its own* match — with the raw field, rows from the previous 300 ms
     * window could show a snippet for text typed after they loaded.
     */
    val committedQuery: StateFlow<String> = committed
        .map { it.query }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * B4 — the custom search paging source is not invalidated by Room, so a
     * delete/edit elsewhere left stale hits on the retained back stack. Hold
     * the one live source and invalidate it in place on any question/passage
     * write. In-place (not rebuilding the Pager) keeps the reader's position —
     * the same approach as BookmarksViewModel.observeBookmarkChanges.
     */
    private val invalidationRelay = SearchInvalidationRelay()

    val pagingData: Flow<PagingData<QuestionSearch.Hit>> = committed
        .flatMapLatest { request ->
            if (request.query.isBlank()) {
                invalidationRelay.liveSource = null
                flowOf(PagingData.empty<QuestionSearch.Hit>())
            } else {
                Pager(
                    PagingConfig(
                        pageSize = SEARCH_PAGE,
                        initialLoadSize = SEARCH_PAGE,
                        enablePlaceholders = false
                    )
                ) {
                    repository.searchPagingSource(request.query, request.scope)
                        .also { invalidationRelay.liveSource = it }
                }.flow
            }
        }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            repository.observeBrowseInvalidation().collect { invalidationRelay.invalidate() }
        }
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun updateScope(scope: QuestionSearch.Scope) {
        _state.update { it.copy(scope = scope) }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val SEARCH_PAGE = 30
    }
}

/**
 * B4 — holds the one live paging source so writes can invalidate it in place.
 * Extracted from [SearchViewModel] purely so the invalidate-on-write behaviour
 * is a JVM-testable seam: a fake source counts `invalidate()` calls.
 */
internal class SearchInvalidationRelay {
    @Volatile
    var liveSource: androidx.paging.PagingSource<Int, QuestionSearch.Hit>? = null

    fun invalidate() {
        liveSource?.invalidate()
    }
}
