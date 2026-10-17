package com.mcqapp.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.StatsStore
import com.mcqapp.domain.DayBoundary
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the statistics payload one tab at a time: the repository read is
 * cached, then each tab is built on demand, so a screen visit aggregates the
 * section being looked at rather than all of them. Building runs on
 * [Dispatchers.Default] under a generation counter, so a slow build can never
 * overwrite a newer selection or a newer tab.
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

    /**
     * The loaded log, kept so a range or scope change re-slices without a
     * database read. It is the only thing here that scales with library size.
     */
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

    /** Shows a tab, building its slice if this selection hasn't been built. */
    fun selectTab(tab: Tab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab) }
        loadVisible()
    }

    /** Re-reads the database and reloads the visible tab. */
    fun refresh() {
        viewModelScope.launch {
            try {
                val loaded = repository.statsRawData()
                raw = loaded
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

    /** Builds the visible tab unless its slice is already loaded and current. */
    private fun loadVisible() {
        val loaded = raw ?: return
        val current = _state.value
        if (current.visibleSliceLoaded && !sectionsStale) {
            _state.update { it.copy(loading = false, loadError = null) }
            return
        }
        val gen = ++generation
        _state.update { it.copy(loading = true, loadError = null) }
        val tab = current.tab
        val scope = current.scopePaperId
        val range = current.range
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val now = System.currentTimeMillis()
                val input = inputOf(loaded, now)
                val built = when (tab) {
                    Tab.OVERVIEW -> current.copy(overview = StatsAggregator.buildOverview(input, scope, range))
                    Tab.RETENTION -> current.copy(retention = StatsAggregator.buildRetention(input, scope, range))
                    Tab.WORKLOAD -> current.copy(workload = StatsAggregator.buildWorkload(input, scope, range))
                    Tab.MEMORY -> current.copy(memory = StatsAggregator.buildMemory(input, scope, range))
                    Tab.CARDS -> current.copy(cards = StatsAggregator.buildCards(input, scope, range))
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

    private fun inputOf(loaded: StatsStore.RawData, now: Long): StatsAggregator.Input =
        StatsAggregator.Input(
            reviews = loaded.reviews,
            cards = loaded.cards,
            categoryOfQuestion = loaded.categoryOfQuestion,
            paperTitleOf = loaded.paperTitleOf,
            scheduler = FsrsScheduler(
                desiredRetention = loaded.config.desiredRetention,
                config = loaded.config
            ),
            config = loaded.config,
            now = now,
            zoneOffsetMillis = DayBoundary.zoneOffset(now)
        )

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
