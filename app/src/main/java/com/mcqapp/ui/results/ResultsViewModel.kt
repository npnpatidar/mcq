package com.mcqapp.ui.results

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mcqapp.McqApplication
import com.mcqapp.data.local.ResultAggregates
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.NumberedResult
import com.mcqapp.domain.ReviewFilters
import com.mcqapp.util.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResultsUiState(
    val loading: Boolean = true,
    /** Set when the attempt could not be loaded, so the screen can say so. */
    val loadError: String? = null,
    val attempt: Attempt? = null,
    val bookmarked: Set<String> = emptySet(),
    /** Header tallies, one row from SQL instead of the whole result list. */
    val aggregates: ResultAggregates = ResultAggregates(),
    val categoryBreakdown: List<Pair<String, Pair<Int, Int>>> = emptyList(),
    val filter: String = ReviewFilters.ALL
)

/**
 * A review of one attempt. The rows stream in as a paged list, so a 250k
 * attempt no longer maps every `QuestionResult` (element JSON and option JSON
 * included) into memory before showing the first card; only the header
 * tallies and category breakdown are aggregated, in SQL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResultsViewModel(
    application: Application,
    private val attemptId: Long
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(ResultsUiState())
    val state: StateFlow<ResultsUiState> = _state.asStateFlow()

    private val request = MutableStateFlow(ResultsRequest(ReviewFilters.ALL, emptySet()))

    val pagingData: Flow<PagingData<NumberedResult>> = request
        .flatMapLatest { req ->
            Pager(
                PagingConfig(
                    pageSize = RESULTS_PAGE,
                    initialLoadSize = RESULTS_PAGE,
                    enablePlaceholders = false
                )
            ) {
                repository.resultPagingSource(attemptId, req.filter, req.savedIds)
            }.flow
        }
        .cachedIn(viewModelScope)

    /** Monotonic load counter so a stale in-flight load cannot overwrite a newer result. */
    private var loadGeneration = 0

    init {
        reload()
        viewModelScope.launch {
            repository.observeBookmarks().collect { ids ->
                val saved = ids.toSet()
                _state.update { it.copy(bookmarked = saved) }
                if (saved.isEmpty() && _state.value.filter == ReviewFilters.SAVED) {
                    updateFilter(ReviewFilters.ALL)
                } else {
                    request.update {
                        it.copy(savedIds = if (it.filter == ReviewFilters.SAVED) saved else emptySet())
                    }
                }
            }
        }
    }

    /** Loads the attempt. Exposed so a failed load can be retried. */
    fun reload() {
        val generation = ++loadGeneration
        viewModelScope.launch {
            try {
                _state.update { it.copy(loading = true, loadError = null) }
                val attempt = repository.getAttempt(attemptId)
                val aggregates = repository.getResultAggregates(attemptId)
                val breakdown = repository.getResultCategoryBreakdown(attemptId)
                    .map { it.title to (it.correct to it.graded) }
                Logger.i(
                    "RESULTVM",
                    "Loaded attempt '${attempt?.title}' with ${aggregates.total} results"
                )
                _state.update {
                    if (generation != loadGeneration) it
                    else it.copy(
                        loading = false,
                        attempt = attempt,
                        aggregates = aggregates,
                        categoryBreakdown = breakdown
                    )
                }
            } catch (e: Exception) {
                // Same defect A6 fixed for the test and study screens: leaving
                // `loading` true showed "Loading…" forever with no retry.
                Logger.e("RESULTVM", "Failed to load attempt $attemptId", e)
                _state.update {
                    if (generation != loadGeneration) it
                    else it.copy(loading = false, loadError = "Could not load this result: ${e.message}")
                }
            }
        }
    }

    fun updateFilter(filter: String) {
        _state.update { it.copy(filter = filter) }
        request.update {
            it.copy(
                filter = filter,
                savedIds = if (filter == ReviewFilters.SAVED) _state.value.bookmarked else emptySet()
            )
        }
    }

    fun toggleBookmark(questionId: String) {
        viewModelScope.launch {
            try {
                repository.toggleBookmark(questionId)
            } catch (e: Exception) {
                Logger.e("RESULTVM", "toggleBookmark($questionId) failed", e)
            }
        }
    }

    private data class ResultsRequest(val filter: String, val savedIds: Set<String>)

    companion object {
        private const val RESULTS_PAGE = 40
    }
}
