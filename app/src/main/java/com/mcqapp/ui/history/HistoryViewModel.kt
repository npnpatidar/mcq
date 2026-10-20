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
        refresh()
    }

    /**
     * Re-reads the two top-lists. The screen calls this when it comes back:
     * the backstack entry keeps its ViewModel, so a list read once when the
     * screen was created stays stale for as long as the entry lives — a
     * study session finished elsewhere never shows up in it. Stats re-reads
     * on every resume for the same reason.
     */
    fun refresh() {
        viewModelScope.launch {
            try {
                // Read the maintained lifetime aggregate instead of mapping
                // every result row (option and element JSON included) into
                // memory to re-derive two five-row lists.
                _hardest.value = repository.getHardestQuestions()
                _weakest.value = repository.getWeakestCategories()
            } catch (e: Exception) {
                Logger.e("HISTVM", "Failed to load question stats", e)
            }
        }
    }
}
