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
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.CategorySelection
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

data class SearchUiState(
    val query: String = "",
    val scope: QuestionSearch.Scope = QuestionSearch.Scope.ALL,
    /** Empty = All papers; otherwise exactly one paper id. */
    val paperFilterIds: Set<String> = emptySet(),
    /**
     * Selected subtree ids, expanded and interpreted inside [paperFilterIds].
     * Empty = all categories in that paper. Storing them expanded is what lets
     * the search layer stay a flat, paper-AND-category membership test.
     */
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
 * The scope sheet applies changes immediately, so these functions are the whole
 * state transition. A paper and its categories travel together: changing paper
 * drops the old paper's categories, while re-choosing the same paper keeps them.
 * Selecting a category stores every id in its subtree, so
 * [SearchScopeFilter.matches] needs no knowledge of the category tree.
 * Kept as pure functions so the contract is JVM-testable without an Application.
 */
internal fun chooseSearchPaper(state: SearchUiState, paperId: String): SearchUiState =
    state.copy(
        paperFilterIds = setOf(paperId),
        categoryFilterIds = if (state.paperFilterIds == setOf(paperId)) {
            state.categoryFilterIds
        } else {
            emptySet()
        }
    )

internal fun toggleCategoryInPaper(
    state: SearchUiState,
    categoryId: String,
    categories: List<CategoryNode>
): SearchUiState {
    val subtree = CategorySelection.subtreeIds(CategorySelection.flatten(categories), categoryId)
        .orEmpty()
    if (subtree.isEmpty()) return state // unknown id: leave the selection alone
    val next = if (subtree.all { it in state.categoryFilterIds }) {
        state.categoryFilterIds - subtree
    } else {
        state.categoryFilterIds + subtree
    }
    return state.copy(categoryFilterIds = next)
}

/**
 * The paper the sheet is currently scoped to, with its category tree. There is
 * no sensible cross-paper interpretation of a category toggle: with no single
 * chosen paper (All papers), or a paper not yet loaded, nothing changes.
 */
internal fun toggleCategoryInChosenPaper(
    state: SearchUiState,
    categoryId: String,
    papers: List<Paper>
): SearchUiState {
    val chosenPaperId = state.paperFilterIds.singleOrNull() ?: return state
    val categories = papers.firstOrNull { it.id == chosenPaperId }?.categories ?: return state
    return toggleCategoryInPaper(state, categoryId, categories)
}

/** Categories-only clear for the sheet: the chosen paper stays put. */
internal fun clearCategoriesInPaper(state: SearchUiState): SearchUiState =
    state.copy(categoryFilterIds = emptySet())

/** "All papers": drop the paper and every category with it. */
internal fun clearSearchScope(state: SearchUiState): SearchUiState =
    state.copy(paperFilterIds = emptySet(), categoryFilterIds = emptySet())

/**
 * Drop ids that no longer exist in the loaded papers. A stale paper would keep
 * restricting the filter to zero hits while the trigger read "All papers"; a
 * stale category would do the same inside a live paper. An empty [papers] is
 * the initial-load state, not a deletion, so it must not clear anything.
 */
internal fun pruneSearchScope(state: SearchUiState, papers: List<Paper>): SearchUiState {
    if (papers.isEmpty()) return state
    val chosenPaperId = state.paperFilterIds.singleOrNull()
    if (chosenPaperId == null) {
        return if (state.categoryFilterIds.isEmpty()) {
            state
        } else {
            state.copy(categoryFilterIds = emptySet())
        }
    }
    val paper = papers.firstOrNull { it.id == chosenPaperId } ?: return clearSearchScope(state)
    val liveIds = CategorySelection.flatten(paper.categories).mapTo(mutableSetOf()) { it.first.id }
    val kept = state.categoryFilterIds intersect liveIds
    return if (kept == state.categoryFilterIds) state else state.copy(categoryFilterIds = kept)
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
 * Cross-paper search, paged, with an immediate-apply paper/category scope sheet
 * and an exact total.
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

    /** Papers, each carrying its category tree for the scope sheet. */
    val papers: StateFlow<List<Paper>> = repository.observePapers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.observeBrowseInvalidation().collect { invalidationRelay.invalidate() }
        }
        viewModelScope.launch {
            papers.collect { list -> _state.update { pruneSearchScope(it, list) } }
        }
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun updateScope(scope: QuestionSearch.Scope) {
        _state.update { it.copy(scope = scope) }
    }

    /** Choose one paper; re-choosing it keeps its category selection. */
    fun choosePaper(paperId: String) {
        _state.update { chooseSearchPaper(it, paperId) }
    }

    /** Toggle a whole category subtree inside the chosen paper, if one is chosen. */
    fun toggleCategory(categoryId: String) {
        _state.update { toggleCategoryInChosenPaper(it, categoryId, papers.value) }
    }

    /** Clear only the categories, leaving the chosen paper selected. */
    fun clearCategoriesInPaper() {
        _state.update { clearCategoriesInPaper(it) }
    }

    /** "All papers": clear the paper and its categories together. */
    fun clearScopeFilters() {
        _state.update { clearSearchScope(it) }
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
