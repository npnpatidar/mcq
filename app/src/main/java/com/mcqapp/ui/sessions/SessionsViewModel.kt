package com.mcqapp.ui.sessions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.domain.stats.SessionsData
import com.mcqapp.domain.stats.StatsAggregator
import com.mcqapp.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The sidebar's session history. It loads every sitting in the chosen range
 * with the topics those sittings touched, on the default dispatcher, and keeps
 * the last successful payload on screen while a new range loads.
 */
class SessionsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as McqApplication).repository

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val range: StatsAggregator.Range = StatsAggregator.Range.D30,
        val data: SessionsData? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var generation = 0

    /** Re-reads the log for the current range; the screen calls this on resume. */
    fun refresh() = load(_state.value.range)

    fun setRange(range: StatsAggregator.Range) {
        if (_state.value.range == range) return
        _state.update { it.copy(range = range) }
        load(range)
    }

    private fun load(range: StatsAggregator.Range) {
        loadJob?.cancel()
        val gen = ++generation
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val now = System.currentTimeMillis()
                val data = repository.sessionHistory(null, range, now)
                withContext(Dispatchers.Main.immediate) {
                    if (gen == generation) {
                        _state.update { it.copy(loading = false, error = null, data = data) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("SESSIONS", "Failed to load the session history", e)
                withContext(Dispatchers.Main.immediate) {
                    if (gen == generation) {
                        _state.update {
                            it.copy(loading = false, error = e.message ?: "error")
                        }
                    }
                }
            }
        }
    }
}
