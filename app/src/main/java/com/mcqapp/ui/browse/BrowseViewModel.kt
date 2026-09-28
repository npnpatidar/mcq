package com.mcqapp.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val loading: Boolean = true,
    val paper: Paper? = null,
    val questions: List<Question> = emptyList()
)

class BrowseViewModel(
    application: Application,
    private val paperId: String
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    init {
        Logger.i("BROWSEVM", "BrowseViewModel created: paperId=$paperId")
        viewModelScope.launch {
            try {
                val paper = repository.getPaper(paperId)
                val questions = repository.getQuestionsForPaper(paperId)
                Logger.i("BROWSEVM", "Loaded ${questions.size} questions for browsing")
                _state.value = BrowseUiState(loading = false, paper = paper, questions = questions)
            } catch (e: Exception) {
                Logger.e("BROWSEVM", "Failed to load questions for browsing", e)
            }
        }
    }

    fun deleteQuestion(questionId: String) {
        viewModelScope.launch {
            repository.deleteQuestion(questionId)
            _state.update { it.copy(questions = it.questions.filter { q -> q.id != questionId }) }
            Logger.i("BROWSEVM", "Deleted question $questionId")
        }
    }
}
