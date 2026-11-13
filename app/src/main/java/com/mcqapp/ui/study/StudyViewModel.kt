package com.mcqapp.ui.study

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.clockMayRun
import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.Scoring
import com.mcqapp.domain.Shuffle
import com.mcqapp.domain.Study
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.random.Random

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

/**
 * Why an empty session is empty. The copy is in strings.xml, resolved by
 * StudyScreen. [StoppedEarly] is the voluntary stop before any review: the
 * sitting is over, but "Session complete / 0 reviewed" would claim work that
 * never happened.
 */
enum class EmptyReason {
    None,
    StoppedEarly,
    NothingDue,
    NothingDueInScope,
    NothingTricky,
    NothingTrickyInScope
}

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
    /** A grade or advance failed to persist; shown inline so the card is not lost. */
    val gradeError: String? = null,
    /** A grade WAS persisted but the next card failed to load: the card on
     *  screen is already graded, so the buttons stand down instead of
     *  inviting a double review (consolidated_bugs.md I3). */
    val gradedAndStuck: Boolean = false,
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
    /**
     * Time already banked for the card on screen, excluding the current
     * segment. Milliseconds are the source of truth: every segment is banked
     * exactly, and the clock display truncates once. Fractional seconds used to
     * be dropped per segment, so a card interrupted four times lost up to four
     * seconds against its Easy budget.
     */
    val questionElapsedMillis: Long = 0L,
    /**
     * The whole-second dwell frozen at Check, rounded half-up. Next, a grade
     * change and Stop all persist this value; none of them rereads the clock.
     */
    val gradedDwellSeconds: Long = 0L,
    /** Whether the question clock is running and should be shown as live. */
    val clockRunning: Boolean = false,
    /**
     * The dwell thresholds the card on screen is graded against. Absolute, so
     * the header clock and the check path read the same configured pair and can
     * never disagree about where the card sits.
     */
    val dwellEasySeconds: Long = Study.FAST_SECONDS,
    val dwellHardSeconds: Long = Study.SLOW_SECONDS,
    /**
     * Grade on correctness alone (v27). The clock keeps counting and the dwell
     * is still stored for later analysis, but it no longer colours itself or
     * decides the grade: the cuts above are unused while this is true.
     */
    val correctnessOnlyGrading: Boolean = false,
    /** The grade Check proposed; Next persists it. Null until checked. */
    val pendingGrade: ReviewGrade? = null,
    /**
     * The app's original proposal. [pendingGrade] is rewritten by
     * [StudyViewModel.changeGrade]; this one is not, so the saved row can say
     * what the app decided before the learner edited it.
     */
    val proposedGrade: ReviewGrade? = null,
    /** Whether [changeGrade] ran for this card; sticky, even if changed back. */
    val gradeEdited: Boolean = false,
    /** What the last check decided, for the result card. */
    val lastResult: StudyResult? = null,
    /** Every bookmarked question id, so the header can toggle the current one. */
    val bookmarked: Set<String> = emptySet(),
    // Session stats: a record of what happened, never a target.
    val reviewed: Int = 0,
    val againCount: Int = 0,
    val goodCount: Int = 0,
    /**
     * Milliseconds of this sitting spent on questions. Banked per segment and
     * truncated only for display, so the summary does not lose a fraction each
     * time the question clock pauses.
     */
    val questionMillis: Long = 0L,
    /**
     * The sitting's total seconds, frozen when it ends. Set once, at the
     * summary, so the figure there stops counting instead of growing while
     * the learner reads it.
     */
    val sessionTotalSeconds: Long = 0L,
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

    /** The banked question time, truncated for display. */
    val questionElapsedSeconds: Long
        get() = questionElapsedMillis / 1000L

    /**
     * Total active seconds on the card on screen: banked time plus the current
     * segment while the clock is running. Truncated only here, for display;
     * [gradedDwellSeconds] is what a check freezes and stores.
     */
    val dwellSeconds: Long
        get() = (
            questionElapsedMillis +
                if (clockRunning) {
                    (SystemClock.elapsedRealtime() - questionStartedAt).coerceAtLeast(0L)
                } else {
                    0L
                }
            ) / 1000L

    /** Seconds of this sitting spent on the question itself, for the summary. */
    val questionSeconds: Long
        get() = questionMillis / 1000L

    /**
     * Seconds of this sitting spent on the answers and explanations: the
     * sitting clock runs while the question clock does not. Clamped because
     * the two clocks are banked at slightly different instants.
     */
    val explanationSeconds: Long
        get() = (sessionTotalSeconds - questionSeconds).coerceAtLeast(0L)

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

    /**
     * Active seconds in this sitting, published once per second. Kept out of
     * [StudyUiState] for the same reason as [liveDwell]: a per-second state
     * copy would recompose the whole question card.
     */
    private val _sessionSeconds = MutableStateFlow(0L)
    val sessionSeconds: StateFlow<Long> = _sessionSeconds.asStateFlow()

    /** Time already banked before the current foreground segment. */
    private var sessionBankedSeconds = 0L

    /**
     * When the current foreground segment started, on the monotonic clock:
     * a wall-clock jump must not turn a sitting into hours (or negative).
     */
    private var sessionStartedAt = SystemClock.elapsedRealtime()
    private var sessionRunning = true

    private var dwellJob: Job? = null
    private var clockPausedByUser = false

    /** Wall-clock start of this sitting, for the study_sessions row. */
    private var sessionStartedAtWall = System.currentTimeMillis()

    /** One row per sitting; both exit paths and onCleared race here. */
    private val sittingEnded = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Owns the session-row write. Not [viewModelScope]: onCleared cancels that,
     * and the last sitting must still be recorded.
     */
    private val sessionScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /**
     * The last visibility the card reported (viewport or a covering surface).
     * Remembered because the screen's visibility effect only re-runs when the
     * flag flips; a foreground has to ask what it last said.
     */
    private var questionInView = true

    /** Set by [stop]; an in-flight grade honours it instead of reopening the
     *  session. Reset when a new session loads. */
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The end-of-sitting controller run; a restart waits for it. */
    private var sessionEndJob: Job? = null

    init {
        load()
        startDwellTicker()
        // One observer for the session: the header's bookmark icon has to
        // follow changes made here, in a test, or on the bookmarks screen.
        viewModelScope.launch {
            repository.observeBookmarks().collect { ids ->
                _state.update { it.copy(bookmarked = ids.toSet()) }
            }
        }
    }

    /** Read once per session, for the button previews. */
    private var config: SchedulerConfig? = null

    /**
     * Whether this sitting shuffles options, and the seed its order derives
     * from (0 when the setting is off).
     *
     * Read when the session loads, never in [pullCard]: the pull path has a
     * query budget (StudyPullQueryCountTest), and a per-card read would also
     * let a settings change rearrange a card already on screen. Per-question
     * and seed-stable (see [Shuffle.optionsFor]), so a revealed card keeps the
     * order its selection was made in.
     */
    private var optionShuffleEnabled = false
    private var optionShuffleSeed = 0L

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


    /** The load in flight; a retry cancels it rather than awaiting it — a
     *  stuck load (a closed database parks its query) must not block the
     *  retry the user asked for (consolidated_bugs.md I6). */
    private var loadJob: kotlinx.coroutines.Job? = null

    private fun load() {
        stopped.set(false)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
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
                // One read per sitting, before the first pull: see the field.
                optionShuffleEnabled = repository.shuffleOptions().first()
                optionShuffleSeed = if (optionShuffleEnabled) Random.nextLong() else 0L
                if (optionShuffleEnabled) {
                    Logger.i("STUDY", "Shuffled study options: seed=$optionShuffleSeed")
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
        // A double-tap ran two loads, both ending in pullCard, and the
        // new-card counter climbed twice (consolidated_bugs.md I6). The
        // in-flight load is cancelled, so the double-load cannot happen and
        // a stuck one cannot block the retry.
        load()
    }

    /**
     * Resumes after a pull failure following a persisted grade: the review is
     * stored, so only the next card is needed (consolidated_bugs.md I3).
     */
    fun loadNext() {
        val current = _state.value
        if (!current.gradedAndStuck || current.grading) return
        _state.update { it.copy(grading = true, gradeError = null) }
        viewModelScope.launch {
            try {
                pullCard()
            } catch (e: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _state.update {
                    it.copy(grading = false, gradeError = "Could not load the next card: ${e.message}")
                }
                return@launch
            } catch (e: Exception) {
                Logger.e("STUDY", "loadNext failed", e)
                _state.update {
                    it.copy(grading = false, gradeError = "Could not load the next card: ${e.message}")
                }
                return@launch
            }
        }
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
        var skipStreak = 0
        while (true) {
            val card = nextCard()
            if (card == null) {
                finishEmpty()
                return
            }
            val question = repository.getQuestion(card.questionId)
                ?.let { Shuffle.optionsFor(it, optionShuffleSeed) }
            if (question == null) {
                // The question vanished between the pull's SQL read and this
                // fetch. The scheduler path re-pulls the very same card, so
                // the loop never changed anything and the session hung with a
                // spinner; a bounded streak ends it instead
                // (consolidated_bugs.md I5).
                if (++skipStreak > 3) {
                    Logger.w("STUDY", "pullCard: $skipStreak cards vanished mid-pull; ending the session")
                    finishEmpty()
                    return
                }
                continue
            }
            skipStreak = 0
            val passage = question.passageId?.let { pid ->
                repository.getPassagesByIds(listOf(pid)).firstOrNull()
            }
            // The clock's colour and the check's grade read the same absolute
            // pair, so the two can never disagree about where the card sits.
            val schedulerConfig = config ?: SchedulerConfig()
            val now = SystemClock.elapsedRealtime()
            _state.update {
                it.copy(
                    current = card,
                    question = question,
                    passage = passage,
                    dwellEasySeconds = schedulerConfig.fastSeconds,
                    dwellHardSeconds = schedulerConfig.slowSeconds,
                    correctnessOnlyGrading = schedulerConfig.correctnessOnlyGrading,
                    selection = emptySet(),
                    revealed = false,
                    grading = false,
                    gradedAndStuck = false,
                    pendingGrade = null,
                    proposedGrade = null,
                    gradeEdited = false,
                    lastResult = null,
                    isGuess = false,
                    questionStartedAt = now,
                    questionElapsedMillis = 0L,
                    gradedDwellSeconds = 0L,
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
        // The sitting is over even though nobody pressed anything: freeze both
        // clocks here, or the summary's total keeps ticking while it is read.
        pauseQuestionClock()
        bankSessionClock()
        publishSessionSeconds()
        val totalSeconds = _sessionSeconds.value
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
                proposedGrade = null,
                gradeEdited = false,
                lastResult = null,
                questionStartedAt = 0L,
                questionElapsedMillis = 0L,
                gradedDwellSeconds = 0L,
                clockRunning = false,
                sessionTotalSeconds = totalSeconds,
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
        endSitting()
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
        val now = SystemClock.elapsedRealtime()
        // Banked once and added to both clocks: the card's own dwell, and the
        // sitting's question time, which the summary splits from explanation
        // time. Banking here means every path that stops the clock (reveal,
        // scroll away, background, stop) is counted exactly once.
        val segment = (now - current.questionStartedAt).coerceAtLeast(0L)
        _state.update {
            it.copy(
                questionStartedAt = now,
                questionElapsedMillis = it.questionElapsedMillis + segment,
                questionMillis = it.questionMillis + segment,
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
        val now = SystemClock.elapsedRealtime()
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

    /**
     * Backgrounding never turns a deliberate pause into a resume, and it
     * stops the sitting's clock: time in another app is not study time.
     */
    fun onAppBackgrounded() {
        pauseQuestionClock()
        bankSessionClock()
        publishSessionSeconds()
    }

    /** The gate decides: a hidden card or a deliberate pause stays paused. */
    fun onAppForegrounded() {
        resumeQuestionClock()
        if (!_state.value.finished) {
            resumeSessionClock()
            publishSessionSeconds()
        }
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
                publishSessionSeconds()
            }
        }
    }

    private fun publishDwell() {
        _liveDwell.value = _state.value.dwellSeconds
    }

    /** Banks the foreground segment and stops the sitting clock. */
    private fun bankSessionClock(now: Long = SystemClock.elapsedRealtime()) {
        if (!sessionRunning) return
        sessionBankedSeconds +=
            ((now - sessionStartedAt).coerceAtLeast(0L) / 1000L)
        sessionRunning = false
    }

    private fun resumeSessionClock(now: Long = SystemClock.elapsedRealtime()) {
        if (sessionRunning) return
        sessionStartedAt = now
        sessionRunning = true
    }

    private fun publishSessionSeconds() {
        val live = if (sessionRunning) {
            (SystemClock.elapsedRealtime() - sessionStartedAt).coerceAtLeast(0L) / 1000L
        } else {
            0L
        }
        _sessionSeconds.value = sessionBankedSeconds + live
    }

    /** Resets the sitting clock for a fresh pass. */
    private fun resetSessionClock() {
        sessionBankedSeconds = 0L
        sessionStartedAt = SystemClock.elapsedRealtime()
        sessionRunning = true
        _sessionSeconds.value = 0L
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
        // Freeze the graded dwell here, rounded once from the millisecond total.
        // Next, a grade change and Stop all persist this same value.
        val dwellSeconds = com.mcqapp.domain.Study.gradedDwellSeconds(paused.questionElapsedMillis)
        val schedulerConfig = config ?: com.mcqapp.domain.SchedulerConfig()
        val grade = com.mcqapp.domain.Study.autoGrade(
            correctOptionIds = question.correctOptionIds,
            selection = paused.selection,
            dwellSeconds = dwellSeconds,
            isGuess = paused.isGuess,
            config = schedulerConfig
        )
        _state.update {
            it.copy(
                revealed = true,
                gradedDwellSeconds = dwellSeconds,
                pendingGrade = grade,
                proposedGrade = grade,
                gradeEdited = false,
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
            correct = isCorrect(question, selection),
            grade = grade,
            wasGuess = wasGuess,
            dwellSeconds = dwellSeconds,
            nextIn = IntervalFormat.format(delay)
        )
    }

    /**
     * One definition of "right" for the log and the result card, so the stored
     * flag can never disagree with what the learner was told. The result
     * haptic shares it through [Scoring], so the pulse cannot disagree with the
     * card either. An empty selection is not correct: nothing was answered.
     */
    private fun isCorrect(question: Question, selection: Set<String>): Boolean =
        Scoring.isCorrect(selection, question.correctOptionIds)

    /** Replaces the proposed grade before it is persisted. */
    fun changeGrade(grade: ReviewGrade) {
        val current = _state.value
        val question = current.question ?: return
        if (!current.simplified || !current.revealed || current.pendingGrade == null) return
        val now = System.currentTimeMillis()
        // The dwell was frozen at Check; changing the grade must not reread a
        // clock that could have moved while the answer was being read.
        val dwellSeconds = current.gradedDwellSeconds
        // Sticky on purpose: changing Good back to the proposed Easy still
        // means a learner decided, so the row is EDITED, not AUTO.
        _state.update {
            it.copy(
                pendingGrade = grade,
                gradeEdited = true,
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
        _state.update { it.copy(grading = true, gradeError = null) }
        pauseQuestionClock()
        val dwellSeconds = _state.value.gradedDwellSeconds
        viewModelScope.launch {
            var persisted = false
            try {
                val next = repository.recordStudyReview(
                    paperId = paperId,
                    questionId = question.id,
                    grade = proposed,
                    config = config,
                    dwellSeconds = dwellSeconds,
                    wasGuess = current.isGuess,
                    wasCorrect = isCorrect(question, current.selection),
                    proposedGrade = current.proposedGrade,
                    gradeSource = com.mcqapp.domain.GradeProvenance.source(
                        current.proposedGrade,
                        edited = current.gradeEdited
                    ),
                    wasSkipped = current.selection.isEmpty()
                )
                persisted = true
                Logger.i(
                    "STUDY",
                    "graded ${question.id} -> $proposed (auto), next in ${next.intervalDays}d, " +
                        "leech=${next.leech}"
                )
                advanceStats(proposed)
                // stop() during the write ends the session; serving a new
                // card would undo it (consolidated_bugs.md I4).
                if (stopped.get()) {
                    _state.update { it.copy(grading = false) }
                    return@launch
                }
                pullCard()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A closed database surfaces its failure as cancellation, not
                // as an exception; rethrow only when THIS job is actually
                // being cancelled, or the failed write reports nothing and
                // the buttons freeze mid-grade.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _state.update {
                    it.copy(
                        grading = false,
                        gradeError = if (persisted) "Could not load the next card: ${e.message}"
                        else "Could not save your grade: ${e.message}",
                        gradedAndStuck = persisted
                    )
                }
            } catch (e: Exception) {
                Logger.e("STUDY", "recordStudyReview(${question.id}) failed", e)
                _state.update {
                    it.copy(
                        grading = false,
                        gradeError = if (persisted) "Could not load the next card: ${e.message}"
                        else "Could not save your grade: ${e.message}",
                        gradedAndStuck = persisted
                    )
                }
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
        _state.update { it.copy(grading = true, gradeError = null) }
        pauseQuestionClock()
        // Classic mode has no Check, so the grade tap is the freeze point.
        val paused = _state.value
        val dwellSeconds = com.mcqapp.domain.Study.gradedDwellSeconds(paused.questionElapsedMillis)
        _state.update { it.copy(gradedDwellSeconds = dwellSeconds) }
        viewModelScope.launch {
            // Persist and pull are split: a pull failure after a persisted
            // grade must not read as a failed grade — the review is already
            // stored, and retrying would write a second one
            // (consolidated_bugs.md I3).
            var persisted = false
            try {
                val next = repository.recordStudyReview(
                    paperId = paperId,
                    questionId = question.id,
                    grade = grade,
                    config = config,
                    dwellSeconds = dwellSeconds,
                    // The four-button flow has no guess switch: the learner is
                    // self-grading a revealed answer, which is the "earned"
                    // side of the dashboard by construction.
                    wasGuess = false,
                    wasCorrect = isCorrect(question, current.selection),
                    // Classic mode has no app proposal.
                    proposedGrade = null,
                    gradeSource = com.mcqapp.domain.GradeSource.CHOSEN,
                    wasSkipped = current.selection.isEmpty()
                )
                persisted = true
                Logger.i(
                    "STUDY",
                    "graded ${question.id} -> $grade, next in ${next.intervalDays}d, " +
                        "leech=${next.leech}"
                )
                advanceStats(grade)
                if (stopped.get()) {
                    _state.update { it.copy(grading = false) }
                    return@launch
                }
                pullCard()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A closed database surfaces its failure as cancellation, not
                // as an exception; rethrow only when THIS job is actually
                // being cancelled, or the failed write reports nothing and
                // the buttons freeze mid-grade.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _state.update {
                    it.copy(
                        grading = false,
                        gradeError = if (persisted) "Could not load the next card: ${e.message}"
                        else "Could not save your grade: ${e.message}",
                        gradedAndStuck = persisted
                    )
                }
            } catch (e: Exception) {
                Logger.e("STUDY", "recordStudyReview(${question.id}) failed", e)
                _state.update {
                    it.copy(
                        grading = false,
                        gradeError = if (persisted) "Could not load the next card: ${e.message}"
                        else "Could not save your grade: ${e.message}",
                        gradedAndStuck = persisted
                    )
                }
            }
        }
    }

    private fun advanceStats(grade: ReviewGrade) {
        _state.update {
            val reviewed = it.reviewed + 1
            it.copy(
                reviewed = reviewed,
                againCount = it.againCount + if (grade == ReviewGrade.AGAIN) 1 else 0,
                goodCount = it.goodCount + if (grade == ReviewGrade.AGAIN) 0 else 1,
                // A review whose write lands after the stop already showed the
                // "nothing reviewed" summary has to move it back to the tally:
                // the sitting did have work, the disk was just slow.
                emptyReason = if (
                    it.finished &&
                    it.emptyReason == EmptyReason.StoppedEarly &&
                    reviewed > 0
                ) {
                    EmptyReason.None
                } else {
                    it.emptyReason
                }
            )
        }
    }

    /**
     * A voluntary stopping point. Never blocks and never judges: the session
     * just closes with its tally, and the card on screen stays exactly where
     * the scheduler put it — due when it is due.
     *
     * A card that was already checked is the exception: Check proposes a grade
     * and the footer promises "Next in …", so the review is written here the
     * way Next would write it, before the summary claims the tally.
     */
    fun stop() {
        // Set before the state flip: an in-flight grade checks this after its
        // persist and refuses to serve a new card, which used to reopen the
        // session the learner just ended (consolidated_bugs.md I4). Also the
        // one-stop-per-sitting guard: back and "Done for now" can both arrive,
        // and the pending write below must never run twice.
        if (!stopped.compareAndSet(false, true)) return
        if (_state.value.finished) return
        // Both clocks stop before any write: the summary's figures are the
        // ones up to the stop, not the ones after a slow disk.
        pauseQuestionClock()
        bankSessionClock()
        publishSessionSeconds()
        val current = _state.value
        val question = current.question
        val proposed = current.pendingGrade
        if (!current.simplified || !current.revealed || current.grading ||
            question == null || proposed == null
        ) {
            // Nothing was pending: classic mode persists on each grade button,
            // and an unchecked card has no decision to keep.
            finishSession()
            return
        }
        _state.update { it.copy(grading = true, gradeError = null) }
        val dwellSeconds = current.gradedDwellSeconds
        viewModelScope.launch {
            try {
                val next = repository.recordStudyReview(
                    paperId = paperId,
                    questionId = question.id,
                    grade = proposed,
                    config = config,
                    dwellSeconds = dwellSeconds,
                    wasGuess = current.isGuess,
                    wasCorrect = isCorrect(question, current.selection),
                    proposedGrade = current.proposedGrade,
                    gradeSource = com.mcqapp.domain.GradeProvenance.source(
                        current.proposedGrade,
                        edited = current.gradeEdited
                    ),
                    wasSkipped = current.selection.isEmpty()
                )
                Logger.i(
                    "STUDY",
                    "graded ${question.id} -> $proposed (on stop), next in " +
                        "${next.intervalDays}d, leech=${next.leech}"
                )
                advanceStats(proposed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Same rule as next(): only a real cancellation of this job is
                // rethrown; a closed database must still reach the summary.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _state.update {
                    it.copy(gradeError = "Could not save your grade: ${e.message}")
                }
            } catch (e: Exception) {
                Logger.e("STUDY", "recordStudyReview(${question.id}) on stop failed", e)
                // The stop itself never blocks: a failed write is reported on
                // the summary instead of trapping the learner in the session.
                _state.update {
                    it.copy(gradeError = "Could not save your grade: ${e.message}")
                }
            }
            finishSession()
        }
    }

    /** Closes the sitting into the summary, with the tally and the times frozen. */
    private fun finishSession() {
        val totalSeconds = _sessionSeconds.value
        endSitting()
        _state.update {
            it.copy(
                grading = false,
                finished = true,
                sessionTotalSeconds = totalSeconds,
                emptyReason = when {
                    // Work was done: a finished sitting with its tally.
                    it.reviewed > 0 -> EmptyReason.None
                    // Stopped before the first review. "Session complete /
                    // 0 reviewed" claimed a sitting that never happened; the
                    // summary says what actually did.
                    else -> EmptyReason.StoppedEarly
                }
            )
        }
    }

    /**
     * Flips the bookmark on the card on screen. The observer above repaints
     * the icon; a failure stays off the card because a lost bookmark must not
     * interrupt a review.
     */
    fun toggleBookmarkCurrent() {
        val question = _state.value.question ?: return
        viewModelScope.launch {
            try {
                repository.toggleBookmark(question.id)
            } catch (e: Exception) {
                Logger.e("STUDY", "toggleBookmark(${question.id}) failed", e)
            }
        }
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
        viewModelScope.launch {
            try {
                // Re-base on the STORED config, not the session-start snapshot:
                // writing the snapshot's weights would revert a fit performed
                // mid-session (the same defect SettingsViewModel fixed).
                val stored = try {
                    repository.schedulerConfigNow()
                } catch (_: Exception) {
                    current
                }
                val updated = stored.copy(introduceNewCards = true)
                config = updated
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
     * path uses, so a button shows the interval grading would schedule at the
     * instant the preview is drawn. Time keeps moving between draw and tap, so
     * the label is a preview, not a promise. Nothing is persisted, and a card
     * whose schedule is unknown gets no labels rather than a guess.
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
        // "Study again" revives a session that stop() ended. Without this the
        // first grade persists and then the stopped guard suppresses the pull,
        // leaving the same revealed card to be graded — and logged — again.
        stopped.set(false)
        viewModelScope.launch {
            try {
                // Never start a new sitting while the previous one's end-of-
                // sitting write is still running: its timing row must land
                // before the next session records its own start.
                sessionEndJob?.join()
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
                // "Study again" is a new sitting, so it draws a new option
                // order even though the cards are the same ones.
                optionShuffleSeed = if (optionShuffleEnabled) Random.nextLong() else 0L
                _state.update {
                    it.copy(
                        reviewed = 0,
                        againCount = 0,
                        goodCount = 0,
                        questionMillis = 0L,
                        sessionTotalSeconds = 0L,
                        reviewsSinceNew = 0,
                        finished = false,
                        emptyReason = EmptyReason.None,
                        freshAvailable = false,
                        selection = emptySet(),
                        revealed = false,
                        grading = false,
                        pendingGrade = null,
                        proposedGrade = null,
                        gradeEdited = false,
                        lastResult = null,
                        // A failed write reported on the old summary must not
                        // follow the learner into the fresh sitting.
                        gradeError = null,
                        isGuess = false,
                        questionStartedAt = 0L,
                        questionElapsedMillis = 0L,
                        gradedDwellSeconds = 0L,
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
                resetSessionClock()
                // A restart is a new sitting, with its own session row.
                sessionStartedAtWall = System.currentTimeMillis()
                sittingEnded.set(false)
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

    /**
     * Writes this sitting's study time once. Called from both exit paths and
     * from [onCleared]; the flag makes the race harmless. A sitting with no
     * reviews is not recorded.
     */
    private fun endSitting() {
        if (_state.value.reviewed <= 0) {
            sittingEnded.set(true)
            return
        }
        if (!sittingEnded.compareAndSet(false, true)) return
        val startedAt = sessionStartedAtWall
        val endedAt = System.currentTimeMillis()
        val activeSeconds = _sessionSeconds.value
        sessionEndJob = sessionScope.launch {
            try {
                repository.recordStudySession(paperId, startedAt, endedAt, activeSeconds)
            } catch (e: Exception) {
                // Best effort: a lost timing row must never disturb the summary.
                Logger.e("STUDY", "recordStudySession($paperId) failed", e)
            }
        }
    }

    override fun onCleared() {
        // The screen can be destroyed without either exit path running. Bank
        // the clocks first, then flush; the write is idempotent.
        bankSessionClock()
        publishSessionSeconds()
        endSitting()
        super.onCleared()
    }
}
