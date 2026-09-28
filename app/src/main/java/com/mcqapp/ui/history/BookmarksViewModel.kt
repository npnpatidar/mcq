package com.mcqapp.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class BookmarksUiState(
    val questions: List<Question> = emptyList()
)

class BookmarksViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(BookmarksUiState())
    val state: StateFlow<BookmarksUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeBookmarks().collect { ids ->
                val questions = ids.mapNotNull { repository.getQuestion(it) }
                _state.value = BookmarksUiState(questions = questions)
            }
        }
    }

    fun removeBookmark(questionId: String) {
        viewModelScope.launch { repository.toggleBookmark(questionId) }
    }
}
