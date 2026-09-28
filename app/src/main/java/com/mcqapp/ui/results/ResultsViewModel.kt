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
import kotlinx.coroutines.launch

data class ResultsUiState(
    val loading: Boolean = true,
    val attempt: Attempt? = null,
    val results: List<QuestionResult> = emptyList()
) {
    val categoryBreakdown: List<Pair<String, Pair<Int, Int>>>
        get() {
            val byCategory = results.groupBy { it.categoryTitle }
            return byCategory.map { (title, list) ->
                title to (list.count { it.isCorrect } to list.size)
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
        Logger.i("RESULTVM", "ResultsViewModel created: attemptId=$attemptId")
        viewModelScope.launch {
            try {
                val attempt = repository.getAttempt(attemptId)
                val results = repository.getAttemptResults(attemptId)
                Logger.i("RESULTVM", "Loaded attempt '${attempt?.title}' with ${results.size} results")
                _state.value = ResultsUiState(
                    loading = false,
                    attempt = attempt,
                    results = results
                )
            } catch (e: Exception) {
                Logger.e("RESULTVM", "Failed to load attempt $attemptId", e)
            }
        }
    }
}
