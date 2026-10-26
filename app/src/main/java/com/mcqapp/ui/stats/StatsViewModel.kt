package com.mcqapp.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.Paper
import com.mcqapp.domain.stats.CardsData
import com.mcqapp.domain.stats.MemoryData
import com.mcqapp.domain.stats.OverviewData
import com.mcqapp.domain.stats.RetentionData
import com.mcqapp.domain.stats.StatsAggregator
import com.mcqapp.domain.stats.WorkloadData
import com.mcqapp.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the statistics payload one tab at a time. Every tab aggregates while
 * its own rows stream past an accumulator, so no tab ever builds the whole log
 * or the whole card library, and each is built on demand — a screen visit pays
 * only for the section being looked at. Building runs on [Dispatchers.Default]
 * under a generation counter, so a slow build can never overwrite a newer
 * selection or a newer tab.
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as McqApplication).repository

    /** One tab of the statistics screen; each owns a slice of the payload. */
    enum class Tab { OVERVIEW, RETENTION, WORKLOAD, MEMORY, CARDS }

    data class UiState(
        val tab: Tab = Tab.OVERVIEW,
        val loading: Boolean = true,
        val loadError: String? = null,
        val overview: OverviewData? = null,
        val retention: RetentionData? = null,
        val workload: WorkloadData? = null,
        val memory: MemoryData? = null,
        val cards: CardsData? = null,
        /** questionId → plain text, for the ranked lists. */
        val questionText: Map<String, String> = emptyMap(),
        val range: StatsAggregator.Range = StatsAggregator.Range.D30,
        /** Null means every paper, Anki's Collection view. */
        val scopePaperId: String? = null,
        val papers: List<Paper> = emptyList()
    ) {
        /** True once the visible tab's slice has been built for this selection. */
        val visibleSliceLoaded: Boolean
            get() = when (tab) {
                Tab.OVERVIEW -> overview != null
                Tab.RETENTION -> retention != null
                Tab.WORKLOAD -> workload != null
                Tab.MEMORY -> memory != null
                Tab.CARDS -> cards != null
            }
    }

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

    /** True when the held slices were built for a superseded scope or range. */
    private var sectionsStale = false

    init {
        // No refresh() here: the screen's ON_RESUME observer fires on first
        // composition (the destination composes while the activity is
        // RESUMED), and the old init call started a second full-log replay
        // whose result the generation check then discarded
        // (consolidated_bugs.md E1).
        viewModelScope.launch {
            // E14: observed, not read once — a paper created while this
            // screen lives joins the scope selector without a restart.
            repository.observePapers().collect { papers ->
                _state.update { it.copy(papers = papers) }
            }
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
                    val config = repository.schedulerConfigNow()
                    FsrsScheduler(
                        desiredRetention = config.desiredRetention,
                        config = config
                    ).retention(state, System.currentTimeMillis())
                }
                // The sheet has moved on while this continuation read: a
                // second open re-labelled it (its placeholder carries the
                // new question's id), and a dismissal closed it. Assigning
                // anyway would swap the sheet back to a question the user no
                // longer has open — or, worst case, reopen a sheet they
                // dismissed — so this result is dropped unassigned.
                if (_cardDetail.value?.questionId != questionId) return@launch
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
                // Same interference rule as above: only clear the sheet when
                // it still belongs to this open's question.
                if (_cardDetail.value?.questionId == questionId) {
                    _cardDetail.value = null
                }
            }
        }
    }

    fun dismissCard() {
        _cardDetail.value = null
    }

    /** Shows a tab, building its slice if this selection hasn't been built. */
    fun selectTab(tab: Tab) {
        if (_state.value.tab == tab) {
            // A failed build has nothing on screen; re-selecting it retries
            // (consolidated_bugs.md E8).
            if (_state.value.loadError != null) refresh()
            return
        }
        _state.update { it.copy(tab = tab) }
        loadVisible()
    }

    /** Re-reads the database and reloads the visible tab. */
    fun refresh() {
        viewModelScope.launch {
            try {
                // The reads are redone on demand: rows logged since the last
                // visit must be picked up, and a tab that needs neither must
                // not pay for them.
                dropSections()
                loadVisible()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("STATS", "Failed to load statistics", e)
                _state.update { it.copy(loading = false, loadError = e.message ?: "error") }
            }
        }
    }

    fun setRange(range: StatsAggregator.Range) {
        if (_state.value.range == range) return
        _state.update { it.copy(range = range) }
        dropSections()
        loadVisible()
    }

    fun setScope(paperId: String?) {
        if (_state.value.scopePaperId == paperId) return
        _state.update { it.copy(scopePaperId = paperId) }
        dropSections()
        loadVisible()
    }

    /**
     * Marks every built slice as belonging to a superseded selection. Bumping
     * the generation discards any build still in flight, so a stale tab cannot
     * land after a newer scope or range. The slices themselves stay on screen
     * and are replaced as they are rebuilt, so changing the range does not
     * blank the page.
     */
    private fun dropSections() {
        generation++
        sectionsStale = true
    }

    /** The build in flight; a superseding load cancels it (E2). */
    private var loadJob: kotlinx.coroutines.Job? = null

    /** Builds the visible tab unless its slice is already loaded and current. */
    private fun loadVisible() {
        val current = _state.value
        if (current.visibleSliceLoaded && !sectionsStale) {
            _state.update { it.copy(loading = false, loadError = null) }
            return
        }
        // Rapid tab/range changes used to leave several concurrent full-log
        // passes running, each discarded on completion (consolidated_bugs.md
        // E2). The generation check stays as a second guard.
        loadJob?.cancel()
        val gen = ++generation
        _state.update { it.copy(loading = true, loadError = null) }
        val tab = current.tab
        val scope = current.scopePaperId
        val range = current.range
        loadJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val now = System.currentTimeMillis()
                val built = when (tab) {
                    // Each tab aggregates while its own rows stream past an
                    // accumulator; none reads a loaded list.
                    Tab.OVERVIEW -> current.copy(
                        overview = repository.statsOverview(scope, range, now)
                    )
                    Tab.RETENTION -> current.copy(
                        retention = repository.statsRetention(scope, range, now)
                    )
                    Tab.WORKLOAD -> current.copy(
                        workload = repository.statsWorkload(scope, range, now)
                    )
                    Tab.MEMORY -> current.copy(
                        memory = repository.statsMemory(scope, range, now)
                    )
                    Tab.CARDS -> current.copy(
                        cards = repository.statsCards(scope, range, now)
                    )
                }
                withContext(Dispatchers.Main.immediate) {
                    if (gen == generation) {
                        sectionsStale = false
                        _state.update { it.onlyFresh(tab, built) }
                    }
                }
                // The ranked lists are small; their question texts are a
                // follow-up read so the charts never wait on the questions table.
                val ids = rankedIds(built, tab)
                if (ids.isNotEmpty()) {
                    val texts = runCatching { repository.getQuestionsByIds(ids) }
                        .getOrDefault(emptyList())
                        .associate { it.id to it.text }
                    withContext(Dispatchers.Main.immediate) {
                        if (gen == generation) {
                            _state.update { it.copy(questionText = it.questionText + texts) }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("STATS", "Failed to load statistics", e)
                withContext(Dispatchers.Main.immediate) {
                    if (gen == generation) {
                        _state.update { it.copy(loading = false, loadError = e.message ?: "error") }
                    }
                }
            }
        }
    }

    /** The question ids whose text the freshly built tab displays. */
    private fun rankedIds(built: UiState, tab: Tab): List<String> = when (tab) {
        Tab.OVERVIEW -> built.overview?.weakest
        Tab.WORKLOAD -> built.workload?.responseTime?.slowest
        Tab.CARDS -> built.cards?.leeches
        Tab.RETENTION, Tab.MEMORY -> null
    }.orEmpty().map { it.questionId }.distinct()

    /**
     * Keeps the tab just built and drops the rest: they were built for the same
     * superseded selection, so a later tab switch must not show them.
     */
    private fun UiState.onlyFresh(tab: Tab, built: UiState): UiState = when (tab) {
        Tab.OVERVIEW -> copy(
            overview = built.overview, retention = null, workload = null, memory = null, cards = null
        )
        Tab.RETENTION -> copy(
            overview = null, retention = built.retention, workload = null, memory = null, cards = null
        )
        Tab.WORKLOAD -> copy(
            overview = null, retention = null, workload = built.workload, memory = null, cards = null
        )
        Tab.MEMORY -> copy(
            overview = null, retention = null, workload = null, memory = built.memory, cards = null
        )
        Tab.CARDS -> copy(
            overview = null, retention = null, workload = null, memory = null, cards = built.cards
        )
    }.copy(loading = false, loadError = null)
}
