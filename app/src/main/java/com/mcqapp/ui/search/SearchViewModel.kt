package com.mcqapp.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mcqapp.McqApplication
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.repository.FilteredSearchPagingSource
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SearchCount
import com.mcqapp.data.repository.SearchScopeFilter
import com.mcqapp.data.repository.searchCount
import com.mcqapp.data.repository.searchCountBounded
import com.mcqapp.domain.Paper
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
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A chip option: a paper, or a category labelled with its paper. */
data class SearchChip(val id: String, val title: String, val paperId: String, val isPaper: Boolean)

data class SearchUiState(
    val query: String = "",
    val scope: QuestionSearch.Scope = QuestionSearch.Scope.ALL,
    /** Selected paper ids; empty = all papers. */
    val paperFilterIds: Set<String> = emptySet(),
    /** Selected category ids; empty = all categories. */
    val categoryFilterIds: Set<String> = emptySet()
)

internal data class SearchRequest(
    val query: String,
    val scope: QuestionSearch.Scope,
    val paperFilterIds: Set<String> = emptySet(),
    val categoryFilterIds: Set<String> = emptySet()
) {
    val filter: SearchScopeFilter get() = SearchScopeFilter(paperFilterIds, categoryFilterIds)
    val hasScopeFilter: Boolean get() = paperFilterIds.isNotEmpty() || categoryFilterIds.isNotEmpty()
}

/**
 * The two chip families are mutually exclusive: [SearchScopeFilter.matches]
 * ANDs them, and categories belong to papers, so a paper id together with a
 * category id from a different paper can only ever produce an empty result.
 * Choosing either family therefore clears the other. Kept as pure functions so
 * the contract is JVM-testable without an Application.
 */
internal fun togglePaperFilter(state: SearchUiState, paperId: String): SearchUiState =
    state.copy(
        paperFilterIds = if (paperId in state.paperFilterIds) emptySet() else setOf(paperId),
        categoryFilterIds = emptySet()
    )

internal fun toggleCategoryFilter(state: SearchUiState, categoryId: String): SearchUiState {
    val next = if (categoryId in state.categoryFilterIds) {
        state.categoryFilterIds - categoryId
    } else {
        state.categoryFilterIds + categoryId
    }
    return state.copy(categoryFilterIds = next, paperFilterIds = emptySet())
}

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
 * Cross-paper search, paged, with paper/category chips and an exact total.
 * The old path asked the repository for every hit at once, which for a
 * one-letter query over a 250k-question bank materialised the entire match set
 * before showing a single row. Here the first page paints after the 300 ms
 * debounce and the rest streams in as the list is scrolled; the total is
 * computed from the same two-phase pipeline on IO, so it is exact rather than
 * Paging's loaded-page "120+".
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    // The WHOLE request is debounced, not the query alone: a scope tap used
    // to re-emit immediately with the last DEBOUNCED query, painting stale
    // results for 300 ms. With the request as the unit, every chip and every
    // keystroke restart the same window and commit together.
    private val committed: SharedFlow<SearchRequest> = _state
        .map { it.request() }
        .distinctUntilChanged()
        .debounce(SEARCH_DEBOUNCE_MS)
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val currentRequest: Flow<SearchRequest> = _state.map { it.request() }

    val debouncing: StateFlow<Boolean> = combine(currentRequest, committed) { current, request ->
        isSearchPending(current, request)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The query the visible hits were produced for: the committed (debounced)
     * one, never the live text field.
     */
    val committedQuery: StateFlow<String> = committed
        .map { it.query }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * The header count for the committed request: a bounded "N+" first so a
     * broad query shows something immediately, then the exact total. Null only
     * before the first committed request resolves.
     */
    val resultCount: StateFlow<SearchCount?> = committed
        .transform { request ->
            if (request.query.isBlank()) {
                emit(SearchCount(0, true))
            } else {
                val bounded = repository.searchCountBounded(
                    request.query, request.scope, request.filter
                )
                emit(bounded)
                if (!bounded.exact) {
                    emit(
                        SearchCount(
                            repository.searchCount(request.query, request.scope, request.filter),
                            true
                        )
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * B4 — hold the one live paging source and invalidate it in place on any
     * question/passage write. In-place (not rebuilding the Pager) keeps the
     * reader's position.
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
                    FilteredSearchPagingSource(
                        fetch = { offset, limit -> repository.searchPage(request.query, request.scope, offset, limit) },
                        filter = request.filter
                    ).also { invalidationRelay.liveSource = it }
                }.flow
            }
        }
        .cachedIn(viewModelScope)

    /** Papers for the chip row. */
    val papers: StateFlow<List<Paper>> = repository.observePapers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Categories for the chip row, paired with their owning paper's title. */
    val chips: StateFlow<List<SearchChip>> = combine(
        repository.observePapers(),
        repository.db().categoryDao().observeAll()
    ) { papers, categories ->
        val paperById = papers.associateBy { it.id }
        categories.mapNotNull { category ->
            paperById[category.paperId]?.let {
                SearchChip(category.id, category.title, category.paperId, isPaper = false)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    /** One paper chip at a time; tapping the active one clears it. Choosing a paper clears the category chips. */
    fun togglePaper(paperId: String) {
        _state.update { togglePaperFilter(it, paperId) }
    }

    /** Several categories at once; choosing one clears a stale paper filter. */
    fun toggleCategory(categoryId: String) {
        _state.update { toggleCategoryFilter(it, categoryId) }
    }

    fun clearScopeFilters() {
        _state.update { it.copy(paperFilterIds = emptySet(), categoryFilterIds = emptySet()) }
    }

    private val _bookmarkedIds = MutableStateFlow<Set<String>>(emptySet())
    /** Bookmark state for the rows currently on screen. */
    val bookmarkedIds: StateFlow<Set<String>> = _bookmarkedIds.asStateFlow()

    /** Loads bookmark flags for the visible hits in one (chunked) query. */
    fun observeBookmarks(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val found = repository.db().bookmarkDao()
                .getForQuestionsChunked(ids)
                .map { it.questionId }.toHashSet()
            _bookmarkedIds.update { current ->
                // Keep flags for rows still on screen; drop the rest.
                (current intersect ids.toSet()) + found
            }
        }
    }

    /** Bookmark toggle reused from the Bookmarks screen's own action. */
    fun toggleBookmark(questionId: String) {
        viewModelScope.launch {
            repository.toggleBookmark(questionId)
            _bookmarkedIds.update { current ->
                if (questionId in current) current - questionId else current + questionId
            }
        }
    }

    private fun SearchUiState.request(): SearchRequest = SearchRequest(
        query.trim(), scope, paperFilterIds, categoryFilterIds
    )

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
