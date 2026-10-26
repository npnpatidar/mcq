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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class SearchUiState(
    val query: String = "",
    val scope: QuestionSearch.Scope = QuestionSearch.Scope.ALL
)

private data class SearchRequest(val query: String, val scope: QuestionSearch.Scope)

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
    private val committed: Flow<SearchRequest> = _state
        .map { SearchRequest(it.query.trim(), it.scope) }
        .distinctUntilChanged()
        .debounce(SEARCH_DEBOUNCE_MS)

    val debouncing: StateFlow<Boolean> = combine(
        _state.map { it.query.trim() },
        committed
    ) { raw, request -> raw.isNotBlank() && raw != request.query }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val pagingData: Flow<PagingData<QuestionSearch.Hit>> = committed
        .flatMapLatest { request ->
            if (request.query.isBlank()) {
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
                }.flow
            }
        }
        .cachedIn(viewModelScope)

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
