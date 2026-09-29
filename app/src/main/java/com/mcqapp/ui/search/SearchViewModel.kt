package com.mcqapp.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.QuestionSearch
import com.mcqapp.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val scope: QuestionSearch.Scope = QuestionSearch.Scope.ALL,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val hits: List<QuestionSearch.Hit> = emptyList()
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query, searched = false) }
        rerun()
    }

    fun updateScope(scope: QuestionSearch.Scope) {
        _state.update { it.copy(scope = scope, searched = false) }
        rerun()
    }

    private fun rerun() {
        val query = _state.value.query
        val scope = _state.value.scope
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searching = false, hits = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(searching = true) }
            try {
                val hits = repository.searchGlobal(query, scope)
                Logger.d("SEARCHVM", "search('$query', $scope) -> ${hits.size} hits")
                _state.update { it.copy(searching = false, searched = true, hits = hits) }
            } catch (e: Exception) {
                Logger.e("SEARCHVM", "search failed", e)
                _state.update { it.copy(searching = false, searched = true) }
            }
        }
    }
}
