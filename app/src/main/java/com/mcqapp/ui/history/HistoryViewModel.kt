package com.mcqapp.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.R
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.Mastery
import com.mcqapp.domain.QuestionStats
import com.mcqapp.util.AppStrings
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryStatsUiState(
    /** R6: the top-lists are read on demand, so their load needs a visible state. */
    val loading: Boolean = true,
    val error: String? = null
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: McqRepository = (application as McqApplication).repository

    /** Friendly, localized copy for the learner; exception text goes to the log only. */
    private fun msg(resId: Int): String = AppStrings.getString(getApplication<Application>(), resId)

    val attempts: StateFlow<List<Attempt>> = repository.observeAttempts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * A saved, unfinished test also belongs on History: a learner looking at
     * past attempts is exactly the one who may want to finish the current one
     * before starting another. Re-read on every resume for the same reason as
     * the top lists.
     */
    private val _unfinishedTest = MutableStateFlow<com.mcqapp.domain.UnfinishedTest?>(null)
    val unfinishedTest: StateFlow<com.mcqapp.domain.UnfinishedTest?> = _unfinishedTest.asStateFlow()

    private val _hardest = MutableStateFlow<List<QuestionStats.Stat>>(emptyList())
    val hardest: StateFlow<List<QuestionStats.Stat>> = _hardest.asStateFlow()

    private val _weakest = MutableStateFlow<List<Mastery.CategoryMastery>>(emptyList())
    val weakest: StateFlow<List<Mastery.CategoryMastery>> = _weakest.asStateFlow()

    private val _statsState = MutableStateFlow(HistoryStatsUiState())
    val statsState: StateFlow<HistoryStatsUiState> = _statsState.asStateFlow()

    init {
        refresh()
        refreshUnfinishedTest()
    }

    /** Re-reads the one saved session; the single slot can change off-screen. */
    fun refreshUnfinishedTest() {
        viewModelScope.launch {
            _unfinishedTest.value = runCatching { repository.unfinishedTest() }.getOrNull()
        }
    }

    /**
     * Re-reads the two top-lists. The screen calls this when it comes back:
     * the backstack entry keeps its ViewModel, so a list read once when the
     * screen was created stays stale for as long as the entry lives — a
     * study session finished elsewhere never shows up in it. Stats re-reads
     * on every resume for the same reason.
     *
     * R6: a failure used to be logged and forgotten, leaving the last list
     * (possibly empty) on screen with no error and no way to try again. Now
     * loading/error are exposed and [refresh] is the Retry action.
     */
    fun refresh() {
        viewModelScope.launch {
            _statsState.update { it.copy(loading = true, error = null) }
            refreshUnfinishedTest()
            try {
                // Read the maintained lifetime aggregate instead of mapping
                // every result row (option and element JSON included) into
                // memory to re-derive two five-row lists.
                _hardest.value = repository.getHardestQuestions()
                _weakest.value = repository.getWeakestCategories()
                _statsState.update { it.copy(loading = false, error = null) }
            } catch (e: Exception) {
                Logger.e("HISTVM", "Failed to load question stats", e)
                _statsState.update {
                    it.copy(loading = false, error = msg(R.string.r2_res_history_stats_failed))
                }
            }
        }
    }
}
