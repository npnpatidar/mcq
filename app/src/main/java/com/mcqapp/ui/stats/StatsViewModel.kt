package com.mcqapp.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.StatsStore
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.Paper
import com.mcqapp.domain.stats.StatsAggregator
import com.mcqapp.domain.stats.StatsBundle
import com.mcqapp.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the statistics payload: one fat repository read, then in-memory
 * re-aggregation whenever the range or paper scope changes. Aggregation runs
 * on [Dispatchers.Default] under a generation counter, so a slow first build
 * can never overwrite a newer selection.
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as McqApplication).repository

    data class UiState(
        val loading: Boolean = true,
        val loadError: String? = null,
        val bundle: StatsBundle? = null,
        /** questionId → plain text, for the ranked lists. */
        val questionText: Map<String, String> = emptyMap(),
        val range: StatsAggregator.Range = StatsAggregator.Range.D30,
        /** Null means every paper, Anki's Collection view. */
        val scopePaperId: String? = null,
        val papers: List<Paper> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The drill-down sheet's content; null while closed. */
    data class CardDetail(
        val paperId: String,
        val questionId: String,
        val text: String?,
        val state: com.mcqapp.domain.CardState?,
        val retrievability: Double?,
        val history: List<com.mcqapp.domain.stats.ReviewHistoryEntry>
    )

    private val _cardDetail = MutableStateFlow<CardDetail?>(null)
    val cardDetail: StateFlow<CardDetail?> = _cardDetail.asStateFlow()

    private var generation = 0
    private var raw: StatsStore.RawData? = null

    init {
        refresh()
        viewModelScope.launch {
            val papers = runCatching { repository.observePapers().first() }.getOrDefault(emptyList())
            _state.update { it.copy(papers = papers) }
        }
    }

    /** Opens the drill-down sheet for one card and loads its history. */
    fun openCard(paperId: String, questionId: String) {
        _cardDetail.value = CardDetail(paperId, questionId, null, null, null, emptyList())
        viewModelScope.launch {
            try {
                val question = repository.getQuestion(questionId)
                val cardState = repository.cardState(paperId, questionId)
                val history = repository.reviewHistory(questionId)
                val retrievability = cardState?.takeIf { it.stability > 0.0 }?.let { state ->
                    val config = raw?.config ?: repository.schedulerConfigNow()
                    FsrsScheduler(
                        desiredRetention = config.desiredRetention,
                        config = config
                    ).retention(state, System.currentTimeMillis())
                }
                _cardDetail.value = CardDetail(
                    paperId = paperId,
                    questionId = questionId,
                    text = question?.text,
                    state = cardState,
                    retrievability = retrievability,
                    history = history
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("STATS", "Failed to load card detail", e)
                _cardDetail.value = null
            }
        }
    }

    fun dismissCard() {
        _cardDetail.value = null
    }

    /** Re-reads the database and rebuilds; cheap enough to call on resume. */
    fun refresh() {
        viewModelScope.launch {
            try {
                val loaded = repository.statsRawData()
                raw = loaded
                recompute(loaded)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("STATS", "Failed to load statistics", e)
                _state.update { it.copy(loading = false, loadError = e.message ?: "error") }
            }
        }
    }

    fun setRange(range: StatsAggregator.Range) {
        _state.update { it.copy(range = range) }
        raw?.let { recompute(it) }
    }

    fun setScope(paperId: String?) {
        _state.update { it.copy(scopePaperId = paperId) }
        raw?.let { recompute(it) }
    }

    private fun recompute(loaded: StatsStore.RawData) {
        val current = _state.value
        val scope = current.scopePaperId
        val range = current.range
        val gen = ++generation
        viewModelScope.launch(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val scheduler = FsrsScheduler(
                desiredRetention = loaded.config.desiredRetention,
                config = loaded.config
            )
            val input = StatsAggregator.Input(
                reviews = loaded.reviews,
                cards = loaded.cards,
                categoryOfQuestion = loaded.categoryOfQuestion,
                paperTitleOf = loaded.paperTitleOf,
                scheduler = scheduler,
                config = loaded.config,
                now = now,
                zoneOffsetMillis = DayBoundary.zoneOffset(now)
            )
            val bundle = StatsAggregator.build(input, scope, range)
            withContext(Dispatchers.Main.immediate) {
                if (gen == generation) {
                    _state.update { it.copy(bundle = bundle, loading = false, loadError = null) }
                }
            }
            // The ranked lists are small; their question texts are a follow-up
            // read so the graphs never wait on the questions table.
            val ids = (bundle.weakest + bundle.leeches + bundle.responseTime.slowest)
                .map { it.questionId }
                .distinct()
            if (ids.isNotEmpty()) {
                val texts = runCatching { repository.getQuestionsByIds(ids) }
                    .getOrDefault(emptyList())
                    .associate { it.id to it.text }
                withContext(Dispatchers.Main.immediate) {
                    if (gen == generation) {
                        _state.update { it.copy(questionText = texts) }
                    }
                }
            }
        }
    }
}
