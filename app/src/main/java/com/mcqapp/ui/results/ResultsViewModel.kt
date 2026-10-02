package com.mcqapp.ui.results

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.QuestionResult
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResultsUiState(
    val loading: Boolean = true,
    /** Set when the attempt could not be loaded, so the screen can say so. */
    val loadError: String? = null,
    val attempt: Attempt? = null,
    val results: List<QuestionResult> = emptyList(),
    val bookmarked: Set<String> = emptySet()
) {
    val categoryBreakdown: List<Pair<String, Pair<Int, Int>>>
        get() {
            val byCategory = results.groupBy { it.categoryTitle }
            return byCategory.map { (title, list) ->
                // Ungraded (no answer key) questions are out of the denominator.
                val graded = list.filter { it.correctOptionIds.isNotEmpty() }
                title to (graded.count { it.isCorrect } to graded.size)
            }.sortedBy { it.first }
        }
}

class ResultsViewModel(
    application: Application,
    private val attemptId: Long
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(ResultsUiState())
    val state: StateFlow<ResultsUiState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            repository.observeBookmarks().collect { ids ->
                _state.update { it.copy(bookmarked = ids.toSet()) }
            }
        }
    }

    /** Loads the attempt. Exposed so a failed load can be retried. */
    fun reload() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(loading = true, loadError = null) }
                val attempt = repository.getAttempt(attemptId)
                val results = repository.getAttemptResults(attemptId)
                Logger.i("RESULTVM", "Loaded attempt '${attempt?.title}' with ${results.size} results")
                _state.value = ResultsUiState(
                    loading = false,
                    attempt = attempt,
                    results = results,
                    bookmarked = _state.value.bookmarked
                )
            } catch (e: Exception) {
                // Same defect A6 fixed for the test and study screens: leaving
                // `loading` true showed "Loading…" forever with no retry.
                Logger.e("RESULTVM", "Failed to load attempt $attemptId", e)
                _state.update {
                    it.copy(loading = false, loadError = "Could not load this result: ${e.message}")
                }
            }
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
}
