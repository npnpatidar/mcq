package com.mcqapp.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.Mastery
import com.mcqapp.domain.QuestionStats
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: McqRepository = (application as McqApplication).repository

    val attempts: StateFlow<List<Attempt>> = repository.observeAttempts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _hardest = MutableStateFlow<List<QuestionStats.Stat>>(emptyList())
    val hardest: StateFlow<List<QuestionStats.Stat>> = _hardest.asStateFlow()

    private val _weakest = MutableStateFlow<List<Mastery.CategoryMastery>>(emptyList())
    val weakest: StateFlow<List<Mastery.CategoryMastery>> = _weakest.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val results = repository.getAllQuestionResults()
                _hardest.value = QuestionStats.hardest(QuestionStats.aggregate(results))
                val attempts = repository.getAttempts()
                _weakest.value = Mastery.weakest(Mastery.perCategory(attempts, results))
            } catch (e: Exception) {
                Logger.e("HISTVM", "Failed to load question stats", e)
            }
        }
    }
}
