package com.mcqapp.ui.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.clockMayRun
import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.StudyCard
import com.mcqapp.domain.StudyReason
import com.mcqapp.util.IntervalFormat
import com.mcqapp.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What an automatic check decided, for the result card.
 *
 * Everything the card needs to render honestly: whether the answer matched,
 * which grade that produced, and the delay that grade schedules.
 */
data class StudyResult(
    val correct: Boolean,
    val grade: ReviewGrade,
    val wasGuess: Boolean,
    val dwellSeconds: Long,
    val nextIn: String
)

/** Why an empty session is empty. The copy is in strings.xml, resolved by StudyScreen. */
enum class EmptyReason { None, NothingDue, NothingDueInScope, NothingTricky, NothingTrickyInScope }

/**
 * What the session is scoped to. The split keeps user-facing copy out of the
 * ViewModel: a Robolectric unit test runs without app resources (C19), so any
 * getString here dies in every scope test.
 */
sealed interface ScopeLabel {
    data object WholePaper : ScopeLabel
    data class One(val title: String) : ScopeLabel
    data class Many(val count: Int) : ScopeLabel
}

/**
 * A pull-based study session: there is no prebuilt queue and no target. The
 * session holds the card on screen plus a running tally of what happened; the
 * next card arrives by asking the scheduler what to pull now, so a failed card
 * can come back within the same sitting and the session ends only when nothing
 * is eligible any more.
 */
data class StudyUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val paperTitle: String = "",
    /** The card the session pulled, or null when nothing is eligible. */
    val current: StudyCard? = null,
    val question: Question? = null,
    /** The current card's passage, if it belongs to one. */
    val passage: com.mcqapp.domain.Passage? = null,
    /** Formatted delay per grade for the card on screen, shown on the buttons. */
    val previews: Map<ReviewGrade, String> = emptyMap(),
    val selection: Set<String> = emptySet(),
    val revealed: Boolean = false,
    val grading: Boolean = false,
    /**
     * Simplified mode: the card is graded from the answer instead of asking for
     * Again/Hard/Good/Easy. Read once per session; the scheduler underneath is
     * the same either way.
     */
    val simplified: Boolean = true,
    /**
     * The learner declared this attempt a guess before answering. Locked at
     * reveal and reset on every advance, so it can never be set retroactively.
     */
    val isGuess: Boolean = false,
    /** When the active question-clock segment started. */
    val questionStartedAt: Long = 0L,
    /** Time already banked for the card on screen, excluding the current segment. */
    val questionElapsedSeconds: Long = 0L,
    /** Whether the question clock is running and should be shown as live. */
    val clockRunning: Boolean = false,
    /** The grade Check proposed; Next persists it. Null until checked. */
    val pendingGrade: ReviewGrade? = null,
    /** What the last check decided, for the result card. */
    val lastResult: StudyResult? = null,
    // Session stats: a record of what happened, never a target.
    val reviewed: Int = 0,
    val againCount: Int = 0,
    val goodCount: Int = 0,
    /** Reviews served since the last new card; explore mode paces on this. */
    val reviewsSinceNew: Int = 0,
    /** True once the pull has run dry — nothing eligible left right now. */
    val finished: Boolean = false,
    /** Why the session is empty; only set when nothing was ever studied. */
    val emptyReason: EmptyReason = EmptyReason.None,
    /** Whether new cards can be introduced at all (drives the empty-state hint). */
    val introduceNewCards: Boolean = true,
    /** New cards exist but are switched off, so the empty state can offer them. */
    val freshAvailable: Boolean = false,
    /** The reviewed-count the break strip was dismissed at, so it can return at the next milestone. */
    val breakDismissedAt: Int = 0,
    /**
     * What the header shows above the card: the whole paper, one category's
     * title, or a count. [ScopeLabel.WholePaper] renders as nothing.
     * Last, not next to [paperTitle]: two adjacent String fields invite a later
     * positional `copy(...)` to rebind the wrong one.
     */
    val scopeLabel: ScopeLabel = ScopeLabel.WholePaper
) {
    val currentSelection: Set<String> get() = selection
    val isCurrentRevealed: Boolean get() = revealed

    /**
     * Total active seconds on the card on screen: banked time plus the current
     * segment while the clock is running.
     */
    val dwellSeconds: Long
        get() = questionElapsedSeconds +
            if (clockRunning) {
                (System.currentTimeMillis() - questionStartedAt).coerceAtLeast(0L) / 1000L
            } else {
                0L
            }

    /**
     * Suggested after every ten reviews. Dismissible, never blocking, and it
     * returns only at the next multiple of ten.
     */
    val showBreakStrip: Boolean
        get() = reviewed > 0 && reviewed % 10 == 0 && breakDismissedAt < reviewed
}

class StudyViewModel(
    application: Application,
    private val paperId: String,
    /** The ticked category ids from the route; empty means the whole paper. */
    private val categoryIds: List<String> = emptyList(),
    private val leechesOnly: Boolean = false
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(StudyUiState())
    val state: StateFlow<StudyUiState> = _state.asStateFlow()

    /** Seconds spent on the question on screen, published once per second. */
    private val _liveDwell = MutableStateFlow(0L)
    val liveDwell: StateFlow<Long> = _liveDwell.asStateFlow()

    private var dwellJob: Job? = null
    private var clockPausedByUser = false

    /**
     * The last visibility the card reported (viewport or a covering surface).
     * Remembered because the screen's visibility effect only re-runs when the
     * flag flips; a foreground has to ask what it last said.
     */
    private var questionInView = true

    init {
        load()
        startDwellTicker()
    }

    /** Read once per session, for the button previews. */
    private var config: SchedulerConfig? = null

    /**
     * The route's ticked ids expanded to the served scope (§6.4). The route
     * keeps the ticked ids because that is what the progress snapshot compares;
     * every query takes this expanded list, and empty still means whole paper.
     */
    private var scopeIds: List<String> = emptyList()

    /**
     * The tricky drill serves a fixed list of the paper's leeches; the main
     * session pulls card by card from the scheduler. Non-null switches the
     * pull source to this list.
     */
    private var drillCards: List<StudyCard>? = null
    private var drillIndex = 0

    private fun load() {
        viewModelScope.launch {
            try {
                val paper = repository.getPaper(paperId)
                config = repository.schedulerConfigNow()
                // Expand once, from the paper already loaded: the route carries
                // the ticked ids (so the snapshot keeps matching when a
                // category is added later), and every query gets the scope.
                // The expansion decides the scope's membership only. The
                // category order the scope is served in is the flat
                // `(sortOrder, title)` order the session order and every SQL
                // surface use — recomputed here once per open, because a
                // >500-category scope's chunk boundaries must line up with
                // that order for the scoped study session to walk categories
                // in the same relative order the scoped test session next
                // door delivers (in-chunk the pool SQL re-sorts regardless).
                scopeIds = if (categoryIds.isEmpty()) {
                    emptyList()
                } else {
                    repository.categoryFlatOrder(
                        paperId,
                        CategorySelection.orderedIds(paper?.categories.orEmpty(), categoryIds)
                    )
                }
                val label = when {
                    categoryIds.isEmpty() -> ScopeLabel.WholePaper
                    categoryIds.size == 1 -> ScopeLabel.One(
                        CategorySelection.titleOf(paper?.categories.orEmpty(), categoryIds.first())
                            .orEmpty()
                    )
                    else -> ScopeLabel.Many(categoryIds.size)
                }
                if (leechesOnly) {
                    drillCards = repository.getStudyQueue(
                        paperId,
                        leechesOnly = true,
                        categoryIds = scopeIds
                    )
                }
                // The card on screen is fetched when it is pulled, not the
                // whole paper up front: a 169k-question paper never fits.
                Logger.i(
                    "STUDY",
                    "Study session: paper=$paperId, drill=$leechesOnly, " +
                        "scope=${scopeIds.size}, cards=${drillCards?.size ?: "pull"}"
                )
                _state.update {
                    it.copy(
                        loading = false,
                        paperTitle = paper?.title ?: "Study",
                        simplified = repository.simplifiedStudy().first(),
                        introduceNewCards = config?.introduceNewCards ?: true,
                        scopeLabel = label
                    )
                }
                pullCard()
            } catch (e: Exception) {
                // `finished = true` with an empty reason rendered the success
                // branch: "Session complete / 0 reviewed". A failure has to
                // look like a failure.
                Logger.e("STUDY", "Failed to load study session", e)
                _state.update {
                    it.copy(
                        loading = false,
                        loadError = "Could not start the session: ${e.message}"
                    )
                }
            }
        }
    }

    /** Re-runs a failed load after the user asks for it. */
    fun retry() {
        _state.update { it.copy(loading = true, loadError = null) }
        load()
    }

    /** The next card: the drill's fixed list, or whatever the scheduler pulls. */
    private suspend fun nextCard(): StudyCard? {
        val list = drillCards ?: return repository.getNextStudyCard(
            paperId,
            _state.value.reviewsSinceNew,
            categoryIds = scopeIds
        )
        val card = list.getOrNull(drillIndex)
        if (card != null) drillIndex++
        return card
    }

    /**
     * Pulls the next card onto the screen. Cards whose question vanished
     * mid-session are skipped; when nothing eligible remains the session ends.
     */
    private suspend fun pullCard() {
        while (true) {
            val card = nextCard()
            if (card == null) {
                finishEmpty()
                return
            }
            val question = repository.getQuestion(card.questionId) ?: continue
            val passage = question.passageId?.let { pid ->
                repository.getPassagesByIds(listOf(pid)).firstOrNull()
            }
            val now = System.currentTimeMillis()
            _state.update {
                it.copy(
                    current = card,
                    question = question,
                    passage = passage,
                    selection = emptySet(),
                    revealed = false,
                    grading = false,
                    pendingGrade = null,
                    lastResult = null,
                    isGuess = false,
                    questionStartedAt = now,
                    questionElapsedSeconds = 0L,
                    // A fresh card is clocked only if the screen last said it
                    // is in view. Advancing from the fixed footer can accept a
                    // card while the previous one was scrolled past; a flip to
                    // visible reports and resumes through the same gate.
                    clockRunning = questionInView,
                    reviewsSinceNew = if (card.reason == StudyReason.NEW) 0 else it.reviewsSinceNew + 1,
                    finished = false
                )
            }
            clockPausedByUser = false
            refreshPreviews()
            publishDwell()
            return
        }
    }

    /** The pull ran dry: a summary if work was done, an honest empty state if not. */
    private suspend fun finishEmpty() {
        var freshAvailable = false
        if (!leechesOnly && _state.value.reviewed == 0) {
            try {
                // Ask as if new cards were on: the empty state offers exactly
                // that flip, and the toggle being off is what emptied the
                // session — counting under the off flag would always say no.
                freshAvailable = repository
                    .getStudyEligibility(paperId, introduceNewCards = true, categoryIds = scopeIds)
                    .fresh > 0
            } catch (e: Exception) {
                Logger.e("STUDY", "getStudyEligibility failed", e)
            }
        }
        _state.update {
            it.copy(
                current = null,
                question = null,
                passage = null,
                previews = emptyMap(),
                selection = emptySet(),
                revealed = false,
                grading = false,
                pendingGrade = null,
                lastResult = null,
                questionStartedAt = 0L,
                questionElapsedSeconds = 0L,
                clockRunning = false,
                finished = true,
                emptyReason = when {
                    it.reviewed > 0 -> EmptyReason.None
                    leechesOnly && scopeIds.isNotEmpty() -> EmptyReason.NothingTrickyInScope
                    leechesOnly -> EmptyReason.NothingTricky
                    scopeIds.isNotEmpty() -> EmptyReason.NothingDueInScope
                    else -> EmptyReason.NothingDue
                },
                freshAvailable = freshAvailable
            )
        }
    }

    fun toggleOption(optionId: String) {
        val question = _state.value.question ?: return
        if (_state.value.revealed) return
        _state.update { state ->
            val current = state.selection
            val updated = if (question.isMultiCorrect) {
                if (current.contains(optionId)) current - optionId else current + optionId
            } else {
                setOf(optionId)
            }
            state.copy(selection = updated)
        }
    }

    fun reveal() {
        if (_state.value.revealed) return
        pauseQuestionClock()
        Logger.d("STUDY", "reveal question=${_state.value.question?.id}")
        _state.update { it.copy(revealed = true) }
    }

    /**
     * Banks the active clock segment and stops it. Safe to call repeatedly.
     */
    fun pauseQuestionClock() {
        val current = _state.value
        if (!current.clockRunning) return
        val now = System.currentTimeMillis()
        val elapsed = current.questionElapsedSeconds +
            ((now - current.questionStartedAt).coerceAtLeast(0L) / 1000L)
        _state.update {
            it.copy(
                questionStartedAt = now,
                questionElapsedSeconds = elapsed,
                clockRunning = false
            )
        }
        publishDwell()
    }

    /**
     * Starts a new active clock segment, unless the clock is already running
     * or the gate refuses: the card is not on screen, the learner paused it, or
     * it is not answerable.
     */
    fun resumeQuestionClock() {
        val current = _state.value
        if (current.clockRunning) return
        if (!clockCanRun(current)) return
        val now = System.currentTimeMillis()
        _state.update { it.copy(questionStartedAt = now, clockRunning = true) }
        publishDwell()
    }

    fun toggleQuestionClock() {
        val current = _state.value
        if (current.revealed) return
        if (current.clockRunning) {
            clockPausedByUser = true
            pauseQuestionClock()
        } else {
            clockPausedByUser = false
            resumeQuestionClock()
        }
    }

    /** Backgrounding never turns a deliberate pause into a resume. */
    fun onAppBackgrounded() {
        pauseQuestionClock()
    }

    /** The gate decides: a hidden card or a deliberate pause stays paused. */
    fun onAppForegrounded() {
        resumeQuestionClock()
    }

    /**
     * The question card left the viewport: stop charging reading time, and
     * start charging again when it returns. A deliberate pause always wins —
     * [resumeQuestionClock] refuses to run while the learner has paused.
     */
    fun setQuestionVisible(visible: Boolean) {
        questionInView = visible
        if (visible) resumeQuestionClock() else pauseQuestionClock()
    }

    private fun clockCanRun(state: StudyUiState): Boolean =
        clockMayRun(
            questionInView = questionInView,
            pausedByUser = clockPausedByUser,
            answerable = !state.loading &&
                state.loadError == null &&
                !state.finished &&
                !state.revealed &&
                state.question != null
        )

    private fun startDwellTicker() {
        dwellJob?.cancel()
        dwellJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                publishDwell()
            }
        }
    }

    private fun publishDwell() {
        _liveDwell.value = _state.value.dwellSeconds
    }

    /**
     * Marks the current attempt as a guess. Only before reveal: once the answer
     * is visible a guess declaration would be retroactive, which defeats the
     * pre-commit. Resets on every advance.
     */
    fun setGuess(guessing: Boolean) {
        val current = _state.value
        if (!current.simplified || current.revealed) return
        _state.update { it.copy(isGuess = guessing) }
    }

    /**
     * Grades the current card from its answer without persisting. Check proposes,
     * Next disposes: the proposal sits in [StudyUiState.pendingGrade] so a
     * change-grade affordance edits it rather than writing twice.
     */
    fun check() {
        val current = _state.value
        val question = current.question ?: return
        if (!current.simplified || current.revealed || current.grading) return
        pauseQuestionClock()
        val paused = _state.value
        val dwellSeconds = paused.questionElapsedSeconds
        val schedulerConfig = config ?: com.mcqapp.domain.SchedulerConfig()
        // Reading time lifts both ends of the ladder by the same amount, so
        // Good stays reachable on long questions and deliberation beyond the
        // Easy window is not mistaken for a struggle.
        val easyAt = com.mcqapp.domain.Study.easyThresholdSeconds(
            config = schedulerConfig,
            question = question,
            passage = paused.passage
        )
        val grade = com.mcqapp.domain.Study.autoGrade(
            correctOptionIds = question.correctOptionIds,
            selection = paused.selection,
            dwellSeconds = dwellSeconds,
            isGuess = paused.isGuess,
            config = schedulerConfig,
            fastSeconds = easyAt,
            slowSeconds = schedulerConfig.slowSeconds + (easyAt - schedulerConfig.fastSeconds)
        )
        _state.update {
            it.copy(
                revealed = true,
                pendingGrade = grade,
                lastResult = buildResult(
                    question, paused.selection, grade, paused.isGuess, dwellSeconds,
                    System.currentTimeMillis()
                )
            )
        }
        Logger.d(
            "STUDY",
            "checked ${question.id} -> $grade (guess=${paused.isGuess}, dwell=${dwellSeconds}s)"
        )
    }

    private fun buildResult(
        question: Question,
        selection: Set<String>,
        grade: ReviewGrade,
        wasGuess: Boolean,
        dwellSeconds: Long,
        now: Long
    ): StudyResult? {
        val schedulerConfig = config ?: return null
        val card = _state.value.current?.state ?: return null
        val scheduler = com.mcqapp.domain.FsrsScheduler(
            desiredRetention = schedulerConfig.desiredRetention,
            config = schedulerConfig
        )
        val delay = com.mcqapp.domain.Study
            .previewDelays(scheduler, card, now)[grade] ?: return null
        return StudyResult(
            correct = selection.isNotEmpty() && selection == question.correctOptionIds,
            grade = grade,
            wasGuess = wasGuess,
            dwellSeconds = dwellSeconds,
            nextIn = IntervalFormat.format(delay)
        )
    }

    /** Replaces the proposed grade before it is persisted. */
    fun changeGrade(grade: ReviewGrade) {
        val current = _state.value
        val question = current.question ?: return
        if (!current.simplified || !current.revealed || current.pendingGrade == null) return
        val now = System.currentTimeMillis()
        val dwellSeconds = current.questionElapsedSeconds
        _state.update {
            it.copy(
                pendingGrade = grade,
                lastResult = buildResult(
                    question,
                    it.selection,
                    grade,
                    it.isGuess,
                    dwellSeconds,
                    now
                )
            )
        }
    }

    /**
     * Persists the proposed grade and pulls the next card. Guarded like [grade]:
     * the write is async, so a second tap would persist two reviews for one
     * question.
     */
    fun next() {
        val current = _state.value
        val question = current.question ?: return
        val proposed = current.pendingGrade ?: return
        if (!current.simplified || !current.revealed) return
        if (current.grading) return
        _state.update { it.copy(grading = true) }
        pauseQuestionClock()
        val dwellSeconds = _state.value.questionElapsedSeconds
        viewModelScope.launch {
            try {
                val next = repository.recordStudyReview(
                    paperId, question.id, proposed, dwellSeconds
                )
                Logger.i(
                    "STUDY",
                    "graded ${question.id} -> $proposed (auto), next in ${next.intervalDays}d, " +
                        "leech=${next.leech}"
                )
                advanceStats(proposed)
                pullCard()
            } catch (e: Exception) {
                Logger.e("STUDY", "recordStudyReview(${question.id}) failed", e)
                _state.update { it.copy(grading = false) }
            }
        }
    }

    /**
     * Applies a grade, persists the new schedule, and pulls the next card. The
     * learner sees the answer before grading, so the grade is self-reported —
     * that is the whole point of the four-button vocabulary.
     */
    fun grade(grade: ReviewGrade) {
        val current = _state.value
        val question = current.question ?: return
        // Claim the card before launching: the write is async, so a second tap
        // would otherwise persist two reviews for the same question.
        if (current.grading) return
        _state.update { it.copy(grading = true) }
        pauseQuestionClock()
        val dwellSeconds = _state.value.questionElapsedSeconds
        viewModelScope.launch {
            try {
                val next = repository.recordStudyReview(
                    paperId, question.id, grade, dwellSeconds
                )
                Logger.i(
                    "STUDY",
                    "graded ${question.id} -> $grade, next in ${next.intervalDays}d, " +
                        "leech=${next.leech}"
                )
                advanceStats(grade)
                pullCard()
            } catch (e: Exception) {
                Logger.e("STUDY", "recordStudyReview(${question.id}) failed", e)
                _state.update { it.copy(grading = false) }
            }
        }
    }

    private fun advanceStats(grade: ReviewGrade) {
        _state.update {
            it.copy(
                reviewed = it.reviewed + 1,
                againCount = it.againCount + if (grade == ReviewGrade.AGAIN) 1 else 0,
                goodCount = it.goodCount + if (grade == ReviewGrade.AGAIN) 0 else 1
            )
        }
    }

    /**
     * A voluntary stopping point. Never blocks and never judges: the session
     * just closes with its tally, and the card on screen stays exactly where
     * the scheduler put it — due when it is due.
     */
    fun stop() {
        pauseQuestionClock()
        _state.update { it.copy(finished = true, emptyReason = EmptyReason.None) }
    }

    fun dismissBreak() {
        _state.update { it.copy(breakDismissedAt = it.reviewed) }
    }

    /**
     * Turns new-card introduction on from the empty state and pulls again, so
     * the offer is one tap from being taken up.
     */
    fun enableNewCards() {
        val current = config ?: return
        val updated = current.copy(introduceNewCards = true)
        config = updated
        viewModelScope.launch {
            try {
                repository.setSchedulerConfig(updated)
                _state.update { it.copy(introduceNewCards = true, loading = true) }
                pullCard()
                _state.update { it.copy(loading = false) }
            } catch (e: Exception) {
                Logger.e("STUDY", "enableNewCards failed", e)
                _state.update { it.copy(loading = false, loadError = "Could not start the session: ${e.message}") }
            }
        }
    }

    /**
     * The four button labels for whichever card is on screen.
     *
     * Read off the same pure [com.mcqapp.domain.FsrsScheduler.next] the grading
     * path uses, so a button cannot promise an interval the scheduler would not
     * schedule. Nothing is persisted, and a card whose schedule is unknown gets
     * no labels rather than a guess.
     */
    private fun refreshPreviews() {
        val cfg = config ?: return
        val scheduler = com.mcqapp.domain.FsrsScheduler(
            desiredRetention = cfg.desiredRetention,
            config = cfg
        )
        _state.update { state ->
            val card = state.current?.state
            state.copy(
                previews = if (card == null) {
                    emptyMap()
                } else {
                    com.mcqapp.domain.Study
                        .previewDelays(scheduler, card, System.currentTimeMillis())
                        .mapValues { (_, delay) -> IntervalFormat.format(delay) }
                }
            )
        }
    }

    /** Re-pulls from the top so a session can be repeated. */
    fun restart() {
        viewModelScope.launch {
            try {
                // A drill restarts from the scheduler now, not from the list
                // it loaded when the screen opened: "Study again" promises a
                // fresh pass, and the world has moved on — cards graded
                // during the first pass carry new schedules, a card that
                // lapsed since (here or from another session) has joined the
                // drill, and one since cured or reset by a question edit has
                // left it. The main session pulls live and needs no re-pull.
                if (leechesOnly) {
                    drillCards = repository.getStudyQueue(
                        paperId,
                        leechesOnly = true,
                        categoryIds = scopeIds
                    )
                }
                drillIndex = 0
                _state.update {
                    it.copy(
                        reviewed = 0,
                        againCount = 0,
                        goodCount = 0,
                        reviewsSinceNew = 0,
                        finished = false,
                        emptyReason = EmptyReason.None,
                        freshAvailable = false,
                        selection = emptySet(),
                        revealed = false,
                        grading = false,
                        pendingGrade = null,
                        lastResult = null,
                        isGuess = false,
                        questionStartedAt = 0L,
                        questionElapsedSeconds = 0L,
                        clockRunning = false,
                        breakDismissedAt = 0,
                        // The stale card must not linger: a restart is a fresh
                        // pull, and during the gap the loading screen speaks —
                        // otherwise the old card would be what "Study again"
                        // shows first, whatever the scheduler now serves.
                        current = null,
                        question = null,
                        passage = null,
                        previews = emptyMap(),
                        loading = true
                    )
                }
                pullCard()
                clockPausedByUser = false
                _state.update { it.copy(loading = false) }
            } catch (e: Exception) {
                // loading was set true before the pull; the screen's `when`
                // checks it first, so a skipped reset would hide this error
                // behind a spinner forever (the stale card was already nulled).
                Logger.e("STUDY", "restart failed", e)
                _state.update {
                    it.copy(loading = false, loadError = "Could not start the session: ${e.message}")
                }
            }
        }
    }
}
