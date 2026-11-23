package com.mcqapp.ui.study

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.R
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SuspendSchedule
import com.mcqapp.data.repository.SuspendStore
import com.mcqapp.data.repository.SuspendedCard
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.clockMayRun
import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.crossedLeechThreshold
import com.mcqapp.domain.Scoring
import com.mcqapp.domain.Shuffle
import com.mcqapp.domain.Study
import com.mcqapp.domain.StudyCard
import com.mcqapp.domain.StudyReason
import com.mcqapp.domain.isFlashcard
import com.mcqapp.domain.mistakeIdsInScope
import com.mcqapp.util.IntervalFormat
import com.mcqapp.util.Logger
import kotlinx.coroutines.CompletableDeferred
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
    /** A tag/mistake pool: copy stays in the UI, only a resource id crosses. */
    data class Text(val resId: Int, val arg: String = "") : ScopeLabel
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
    /**
     * The card's presentation number in the current study day (1-based).
     * Loaded with the card so the button labels honour the same exposure budget
     * the grading path applies.
     */
    val exposuresInStudyDay: Int = 1,
    val selection: Set<String> = emptySet(),
    val revealed: Boolean = false,
    val grading: Boolean = false,
    /**
     * Whether the one-time grading hint has already been dismissed. Loaded from
     * persistent storage at session start; false on a new install. Kept in state
     * only so the screen can react without a second flow, and set the moment the
     * hint is dismissed so it never flashes again this session.
     */
    val gradingHintDismissed: Boolean = false,
    /**
     * Simplified mode: the card is graded from the answer instead of asking for
     * Again/Hard/Good/Easy. Read once per session; the scheduler underneath is
     * the same either way.
     */
    val simplified: Boolean = true,
    /**
     * Opt-in last-chance grade confirmation for the four-button Classic flow,
     * including self-graded cards there. Off by default, so one-tap grade-and-
     * advance is unchanged until the learner enables it in Settings. Simplified
     * ignores this flag entirely: its result card already offers Change grade,
     * so a self-graded card must go straight through [grade] and keep null
     * correctness — staging it would fabricate a "Recalled %" line.
     */
    val confirmGradeBeforeNext: Boolean = false,
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
     * Grade on correctness alone (v28). The clock keeps counting and the dwell
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
     * How many of [reviewed] were self-graded — a card, or a multiple-choice
     * question with no answer key. A sitting that contains one has no
     * correctness figure to report, so the summary omits the recall line.
     */
    val selfGradedReviewed: Int = 0,
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
     * One-shot notice after a grade first flags a card as a leech. Set on the
     * card served immediately after the crossing, cleared by the next pull, so
     * the "Now flagged as tricky" line is seen once and never repeats for a
     * card that stays flagged.
     */
    val justFlaggedLeech: Boolean = false,
    /**
     * True while the learner is on an explicit break from the strip. Both clocks
     * are banked and answering is parked, but the sitting stays open: Resume
     * restarts the clocks without losing the tally or ending the session.
     */
    val onBreak: Boolean = false,
    /** Whether this sitting is the tricky drill; drives its one-pass note. */
    val leechesOnly: Boolean = false,
    /**
     * What the header shows above the card: the whole paper, one category's
     * title, or a count. [ScopeLabel.WholePaper] renders as nothing.
     * Last, not next to [paperTitle]: two adjacent String fields invite a later
     * positional `copy(...)` to rebind the wrong one.
     */
    val scopeLabel: ScopeLabel = ScopeLabel.WholePaper,
    /** Cards hidden from this paper's Study pool, for the header's indicator. */
    val suspendedCount: Int = 0,
    /** A bury/suspend write is in flight; the card is claimed like a grade. */
    val cardActionInProgress: Boolean = false,
    /** A hidden-card write failed; rendered next to the grade error. */
    val cardActionError: String? = null,
    /** Whether the unsuspend dialog is open. */
    val showSuspendedDialog: Boolean = false,
    /** The rows shown in that dialog, refreshed after every unsuspend. */
    val suspendedCards: List<SuspendedCard> = emptyList()
) {
    val currentSelection: Set<String> get() = selection
    val isCurrentRevealed: Boolean get() = revealed

    /**
     * Whether a grade is staged before it is written. Simplified always does;
     * the four-button flows do only when the learner opted in. This one flag
     * drives the result card, Change grade, Next and the stop-time write, so
     * the three modes cannot disagree about when a review is persisted.
     */
    val stagedGrading: Boolean get() = simplified || confirmGradeBeforeNext

    /**
     * A zero-option question is a flashcard: the stem is the front and the
     * explanation is the back. Derived, never stored — options present means a
     * multiple-choice card, so nothing can disagree about which kind is on
     * screen. This is a *shape* fact and drives card labels and summary copy
     * only; it is not what decides whether the app may grade the question.
     */
    val isFlashcard: Boolean get() = question?.options?.isEmpty() == true

    /**
     * Whether the app knows the answer key and may therefore grade by it. A
     * question with no key (a card, or a multiple-choice question imported
     * ungraded) can never be marked right or wrong by comparing a selection to
     * an empty set: the app simply does not know. Those are self-graded.
     */
    val canAutoGrade: Boolean
        get() = question != null && question.correctOptionIds.isNotEmpty()

    /**
     * The learner grades this question's recall themselves: no key, so no Check
     * and no result card. True for every card and for every keyless
     * multiple-choice question — the two are one rule, not two.
     */
    val selfGraded: Boolean get() = question != null && !canAutoGrade

    /**
     * The summary's recall percentage is a correctness figure, and a
     * self-graded question has no right or wrong answer the app could know: the
     * learner grades their own recall. So a sitting that contains one omits the
     * line rather than reporting a score built out of self-grades.
     */
    val showsRecallAccuracy: Boolean get() = reviewed > 0 && selfGradedReviewed == 0

    /** The banked question time, truncated for display. */
    val questionElapsedSeconds: Long
        get() = questionElapsedMillis / 1000L

    /**
     * Active milliseconds on the card on screen: banked time plus the current
     * segment while the clock is running. The clock's colour is measured from
     * this, through the same half-up rounding a check uses, so the tint and the
     * stored grade cannot disagree in the last half-second of a band.
     */
    val dwellMillis: Long
        get() = questionElapsedMillis +
            if (clockRunning) {
                (SystemClock.elapsedRealtime() - questionStartedAt).coerceAtLeast(0L)
            } else {
                0L
            }

    /** [dwellMillis] truncated for the clock's text; a clock reading 3 s then 4 s is what a learner expects. */
    val dwellSeconds: Long
        get() = dwellMillis / 1000L

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

    /**
     * The one-time grading disclosure. Shown only while a card is genuinely on
     * screen — never during loading, an error or the summary — and only until
     * dismissed. The dismissal is persisted, so a recreation of the session
     * starts with [gradingHintDismissed] already true and this stays false.
     * A self-graded question never sees it: the hint explains how an MCQ is
     * graded, and a question without a key is graded by the learner's own four
     * buttons instead.
     */
    val showGradingHint: Boolean
        get() = !gradingHintDismissed && !selfGraded && !loading && loadError == null &&
            !finished && current != null && question != null
}

/** Test-only synchronisation for an in-flight pull (see [StudyViewModel.pullGateForTests]). */
internal class StudyPullGateForTests {
    val reached = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val completed = CompletableDeferred<Unit>()
}

/** Test-only synchronisation for an in-flight grade write (see [StudyViewModel.gradeGateForTests]). */
internal class StudyGradeGateForTests {
    val reached = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val completed = CompletableDeferred<Unit>()
}

/**
 * Pure gate for changing the proposed grade, shared by the ViewModel and the
 * screen's toggle. The write is asynchronous: a tap in the frame before
 * recomposition must not rewrite `pendingGrade` after `next()` captured it.
 * Keeping the predicate here makes that negative case JVM-testable.
 */
internal fun studyCanChangeGrade(state: StudyUiState): Boolean =
    !state.grading && state.stagedGrading && state.revealed &&
        state.pendingGrade != null && state.question != null

/**
 * Pure gate for the Study header's Bury/Suspend actions, shared by the menu and
 * the ViewModel. A card action hides the current card and pulls the next one, so
 * it must stand down while a grade is being written or is merely staged — a tap
 * in that window would silently discard the learner's grade decision.
 */
internal fun studyCanActOnCard(state: StudyUiState): Boolean =
    state.question != null && !state.finished && !state.grading &&
        !state.cardActionInProgress && !state.onBreak && state.pendingGrade == null

class StudyViewModel(
    application: Application,
    private val paperId: String,
    /** The ticked category ids from the route; empty means the whole paper. */
    private val categoryIds: List<String> = emptyList(),
    private val leechesOnly: Boolean = false,
    /** Folded tag carried by the route; restricts the pool to its questions. */
    private val tag: String = "",
    /** Mistake-scoped sitting; restricts the pool to currently-wrong questions. */
    private val mistakes: Boolean = false
) : AndroidViewModel(application) {

    /** The resolved route scope; never a schema or schedule change. */
    private val studyScope: com.mcqapp.domain.StudyScope =
        com.mcqapp.domain.StudyScope.fromRoute(categoryIds, tag, mistakes)

    private val repository: McqRepository = (application as McqApplication).repository

    /**
     * Suspend/bury writes go through their own store on the same database
     * singleton as [repository], so no grading path is involved and a flag is
     * never mistaken for a review.
     */
    private val suspendStore = SuspendStore(
        AppDatabase.get(application.applicationContext)
    )

    private val _state = MutableStateFlow(StudyUiState())
    val state: StateFlow<StudyUiState> = _state.asStateFlow()

    /** A plain, localized message; the exception itself stays in the log. */
    private fun friendly(messageRes: Int): String =
        getApplication<Application>().getString(messageRes)

    /** Seconds spent on the question on screen, published once per second. */
    private val _liveDwell = MutableStateFlow(0L)
    val liveDwell: StateFlow<Long> = _liveDwell.asStateFlow()

    /**
     * The same reading in milliseconds, published with it. The clock's band is
     * decided from this: [liveDwell] has already truncated the half-second the
     * grade's rounding uses, so a card at 5.6 s was coloured Easy and graded
     * Good. Kept as its own flow rather than re-derived to stay one tick.
     */
    private val _liveDwellMillis = MutableStateFlow(0L)
    val liveDwellMillis: StateFlow<Long> = _liveDwellMillis.asStateFlow()

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
     * Durable scope for writes that must outlive the screen. Not
     * [viewModelScope]: onCleared cancels that, so a grade write or the last
     * sitting's row launched there would be lost when the card is popped mid-write.
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

    /**
     * Set by the real [onCleared] (a ViewModelStore.clear(), not the normal
     * Back/Done path, which goes through [stop]). A grade in flight at that
     * moment is tallied on the durable [sessionScope] *after* onCleared ran —
     * possibly before any review was counted, so onCleared's endSitting saw
     * nothing to write. The grade's continuation must end the sitting once the
     * review is tallied; [sittingEnded] covers both exit paths.
     */
    private val cleared = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The end-of-sitting controller run; a restart waits for it. */
    private var sessionEndJob: Job? = null

    /**
     * The in-flight grade write, if any (next, grade, or the pending grade a
     * stop persists). A restart joins it before clearing the exit latches so a
     * review earned by the old sitting is tallied and closed into that sitting's
     * session row, instead of leaking its tally/error into the fresh sitting or
     * pulling a second card.
     */
    private var gradeWriteJob: Job? = null

    /**
     * The question whose grade write last succeeded. stop() uses this to tell
     * "graded, then advanced to another card" from "graded and stuck on the
     * same card": only the former leaves a stray card behind the summary, while
     * the latter must keep the card the learner has already paid for. Written
     * from the durable session-scope write, read on Main, hence @Volatile.
     */
    @Volatile
    private var lastPersistedGradeQuestionId: String? = null

    /**
     * Whether the sitting was ended while a grade was in flight: the normal
     * exit latches [stopped], a bare clear latches [cleared]. Either way the
     * persisted review has to be followed by exactly one study_sessions row,
     * written after the tally and guarded by endSitting's CAS.
     */
    private fun sittingEnded(): Boolean = stopped.get() || cleared.get()

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
     *
     * The drill is deliberately ONE PASS: [nextCard] walks this snapshot once
     * and never re-queues a leech that comes due again mid-sitting. The summary
     * says so plainly (`study_polish_drill_one_pass`) rather than implying the
     * drill keeps drilling until nothing is tricky; "Study again" starts a new
     * pass from the scheduler's *current* queue ([restart]), so a card that
     * lapsed since is included and one since cured is left out.
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
                // Read once per sitting. A dismissal writes this flag, so a new
                // ViewModel (rotation is the same instance, process death is a
                // fresh one) starts with the hint already hidden.
                val gradingHintDismissed = repository.studyGradingHintShown().first()
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
                    mistakes -> ScopeLabel.Text(com.mcqapp.R.string.of_scope_mistakes)
                    tag.isNotBlank() -> ScopeLabel.Text(com.mcqapp.R.string.of_scope_tag, tag)
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
                } else if (studyScope.isRestricted) {
                    drillCards = resolveScopedDrillCards()
                }
                // One cheap indexed read for the header's "N suspended"
                // indicator; never a driver of the pull, which reads its own
                // pools. A failure here must not stop the session loading.
                val hidden = try {
                    suspendStore.suspendedCards(paperId, System.currentTimeMillis()).size
                } catch (e: Exception) {
                    Logger.e("STUDY", "suspendedCards($paperId) failed", e)
                    0
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
                        gradingHintDismissed = gradingHintDismissed,
                        simplified = repository.simplifiedStudy().first(),
                        confirmGradeBeforeNext = repository.confirmGradeBeforeNext().first(),
                        introduceNewCards = config?.introduceNewCards ?: true,
                        scopeLabel = label,
                        suspendedCount = hidden,
                        leechesOnly = leechesOnly
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
                        loadError = friendly(R.string.study_session_start_failed)
                    )
                }
            }
        }
    }

    /**
     * The question ids a tag or mistake scope may serve, in the source's order:
     * the tag index's authored `(sortOrder, rowid)` order, or the standings'
     * most-recently-missed-first order. Split out so the empty-state fresh
     * probe and the queue resolver ask the same scope the same question.
     */
    private suspend fun resolveScopedAllowedIds(): List<String> =
        when (studyScope.kind) {
            com.mcqapp.domain.StudyScopeKind.TAG ->
                repository.questionIdsForStudyTagInScope(paperId, scopeIds, studyScope.tag)
            com.mcqapp.domain.StudyScopeKind.MISTAKES ->
                mistakeIdsInScope(repository.getMistakenQuestions(paperId), scopeIds)
            else -> emptyList()
        }

    private suspend fun resolveScopedDrillCards(): List<StudyCard> =
        repository.getStudyQueueForQuestions(paperId, resolveScopedAllowedIds())

    /**
     * Whether a restricted (tag/mistake) empty sitting has a new card behind the
     * off toggle. The paper-wide eligibility read would answer for questions
     * outside the scope, so the offer used to appear with nothing it could
     * actually serve. The scoped queue keeps the same authored order the real
     * sitting uses.
     */
    private suspend fun scopedFreshAvailable(): Boolean {
        val allowed = resolveScopedAllowedIds()
        if (allowed.isEmpty()) return false
        return repository
            .getStudyQueueForQuestions(paperId, allowed, introduceNewCards = true)
            .any { it.reason == StudyReason.NEW }
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
                    it.copy(grading = false, gradeError = friendly(R.string.study_next_card_failed))
                }
                return@launch
            } catch (e: Exception) {
                Logger.e("STUDY", "loadNext failed", e)
                _state.update {
                    it.copy(grading = false, gradeError = friendly(R.string.study_next_card_failed))
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
        if (failPullForTests) {
            failPullForTests = false
            throw IllegalStateException("simulated next-card pull failure")
        }
        var skipStreak = 0
        while (true) {
            // A stop can land while this pull was in flight (Retry -> Back).
            // Serving a card after the summary opened would un-finish the
            // sitting, and a second Back could not close it (stopped is latched).
            if (stopped.get()) return
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
            val schedulerConfig = studyConfigOrWarn(config, STALE_CONFIG_CLOCK_MESSAGE)
            val now = SystemClock.elapsedRealtime()
            // Fetched before the card is shown: the answer buttons and the grade
            // they produce must agree on how many exposures today the card has
            // already had, or a capped relearn would be previewed as 10 minutes.
            val exposures = try {
                repository.exposureInStudyDay(
                    paperId = paperId,
                    questionId = card.questionId,
                    config = studyConfigOrWarn(config, STALE_CONFIG_EXPOSURE_MESSAGE)
                )
            } catch (e: Exception) {
                Logger.e("STUDY", "exposureInStudyDay(${card.questionId}) failed", e)
                1
            }
            val gate = pullGateForTests
            if (gate != null) {
                pullGateForTests = null
                gate.reached.complete(Unit)
                gate.release.await()
            }
            // Check again after every suspension point above: the session may
            // have been stopped while the card was being fetched.
            if (stopped.get()) {
                gate?.completed?.complete(Unit)
                return
            }
            _state.update {
                it.copy(
                    current = card,
                    exposuresInStudyDay = exposures,
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
                    // A fresh card has not just crossed the leech threshold:
                    // the notice is set AFTER this pull by the grade that did.
                    justFlaggedLeech = false,
                    // The break always ends when a new card is served.
                    onBreak = false,
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
            // A new card resumes as soon as it is served when the session
            // already considers it on screen, instead of depending on the
            // re-composed body's visibility effect being the next thing to run.
            // The gate still refuses a card that is off screen, revealed, or
            // paused by the learner.
            resumeQuestionClock()
            refreshPreviews(exposures)
            publishDwell()
            // Re-anchor the ticker on the card's own start: a ticker launched
            // once at construction ticks on the ViewModel's phase, so the
            // clock could lag the card by up to a second.
            startDwellTicker()
            gate?.completed?.complete(Unit)
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
                // A tag/mistake scope must be asked about its own pool: the
                // paper-wide read offered the flip when only cards outside the
                // scope were fresh, and the sitting stayed empty after taking it.
                freshAvailable = if (studyScope.isRestricted) {
                    scopedFreshAvailable()
                } else {
                    repository.getStudyEligibility(
                        paperId,
                        introduceNewCards = true,
                        categoryIds = scopeIds
                    ).fresh > 0
                }
            } catch (e: Exception) {
                Logger.e("STUDY", "getStudyEligibility failed", e)
            }
        }
        // A tag or mistake scope is a restriction even when it covers the whole
        // paper (empty categoryIds), so its empty copy must name the scope.
        val scoped = scopeIds.isNotEmpty() || studyScope.isRestricted
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
                gradedAndStuck = false,
                pendingGrade = null,
                proposedGrade = null,
                gradeEdited = false,
                lastResult = null,
                questionStartedAt = 0L,
                questionElapsedMillis = 0L,
                gradedDwellSeconds = 0L,
                clockRunning = false,
                justFlaggedLeech = false,
                onBreak = false,
                sessionTotalSeconds = totalSeconds,
                finished = true,
                emptyReason = when {
                    it.reviewed > 0 -> EmptyReason.None
                    leechesOnly && scoped -> EmptyReason.NothingTrickyInScope
                    leechesOnly -> EmptyReason.NothingTricky
                    scoped -> EmptyReason.NothingDueInScope
                    else -> EmptyReason.NothingDue
                },
                freshAvailable = freshAvailable
            )
        }
        endSitting()
    }

    fun toggleOption(optionId: String) {
        val question = _state.value.question ?: return
        if (_state.value.revealed || _state.value.onBreak) return
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
        if (!clockCanRun(current)) {
            Logger.i(
                "STUDY",
                "clock refused: pausedByUser=$clockPausedByUser inView=$questionInView " +
                    "revealed=${current.revealed} loading=${current.loading} " +
                    "finished=${current.finished} onBreak=${current.onBreak}"
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        _state.update { it.copy(questionStartedAt = now, clockRunning = true) }
        publishDwell()
    }

    fun toggleQuestionClock() {
        val current = _state.value
        if (current.revealed || current.onBreak) return
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
        if (!_state.value.finished && !_state.value.onBreak) {
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
        Logger.i(
            "STUDY",
            "visibility=$visible running=${_state.value.clockRunning} " +
                "pausedByUser=$clockPausedByUser revealed=${_state.value.revealed} " +
                "finished=${_state.value.finished} onBreak=${_state.value.onBreak} " +
                "question=${_state.value.current?.questionId}"
        )
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
                !state.onBreak &&
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
        val current = _state.value
        _liveDwell.value = current.dwellSeconds
        _liveDwellMillis.value = current.dwellMillis
    }

    /**
     * Puts a fixed dwell on the card on screen and stops its clock, so a test
     * can reach a band (Hard starts at 60 s) without waiting for it. Only the
     * grading path reads this value, and nothing in the app calls it; the
     * `ForTests` suffix keeps that visible at the call site.
     */
    internal fun freezeDwellForTests(millis: Long) {
        _state.update {
            it.copy(
                questionElapsedMillis = millis,
                clockRunning = false
            )
        }
        publishDwell()
    }

    /**
     * Test-only fault injection: makes the next [pullCard] throw *after* its
     * grade has been persisted, reproducing a locked/full disk between the
     * write and the next card — the state [StudyUiState.gradedAndStuck]
     * describes. Fires once, then clears itself. Nothing in the app sets it.
     */
    internal var failPullForTests: Boolean = false

    /**
     * Test-only gate: when set, [pullCard] signals [StudyPullGateForTests.reached]
     * and then suspends on [StudyPullGateForTests.release] just before applying
     * the next card, so a test can interleave a stop() with an in-flight pull.
     * Cleared once used; nothing in the app sets it.
     */
    internal var pullGateForTests: StudyPullGateForTests? = null

    /**
     * Test-only gate: when set, the next grade write signals
     * [StudyGradeGateForTests.reached] and then suspends on
     * [StudyGradeGateForTests.release] before calling [McqRepository.recordStudyReview],
     * so a test can interleave a pop/back or a stop() with a write in flight.
     * Cleared once used; nothing in the app sets it.
     */
    internal var gradeGateForTests: StudyGradeGateForTests? = null

    /** Consumes [gradeGateForTests] once, just before a grade is persisted. */
    private suspend fun awaitGradeGateForTests() {
        val gate = gradeGateForTests ?: return
        gradeGateForTests = null
        gate.reached.complete(Unit)
        gate.release.await()
        gate.completed.complete(Unit)
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
        // A self-graded question has no answer the app can check, so a guess
        // declaration would have nothing to qualify. The switch is hidden for
        // one; this is the defence behind it.
        if (current.selfGraded) return
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
        // Defence: only a question whose key the app knows can be auto-graded.
        // A card has no options; an ungraded import has options but an empty
        // key. Comparing a selection to an empty key would call the answer
        // wrong and propose AGAIN for something the app cannot know, so both
        // are self-graded and the footer shows Show answer instead of Check.
        if (!current.canAutoGrade) return
        if (current.onBreak) return
        if (!current.simplified || current.revealed || current.grading) return
        pauseQuestionClock()
        val paused = _state.value
        // Freeze the graded dwell here, rounded once from the millisecond total.
        // Next, a grade change and Stop all persist this same value.
        val dwellSeconds = com.mcqapp.domain.Study.gradedDwellSeconds(paused.questionElapsedMillis)
        val schedulerConfig = studyConfigOrWarn(config, STALE_CONFIG_GRADE_MESSAGE)
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
            .previewDelays(scheduler, card, now, _state.value.exposuresInStudyDay)[grade]
            ?: return null
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
        // S4: a grade write already in flight owns the proposal. Ignore the tap
        // rather than let it rewrite pendingGrade/lastResult after Next read it.
        // On a break the shared gate still passes (the answer is staged), so
        // the break-specific refusal lives here: no edit while clocks are banked.
        if (current.onBreak) return
        if (!studyCanChangeGrade(current)) return
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
     * Stages the learner's own grade (Classic MCQ, keyless MCQ or flashcard)
     * when the opt-in "Confirm grade before next card" setting is on. Nothing
     * is written here: the result card, Change grade and Next all work on this
     * staged proposal, and [next] writes it through [grade]. With the setting
     * off this method is never called by the screen, so the existing one-tap
     * behaviour is untouched.
     */
    fun stageGrade(grade: ReviewGrade) {
        val current = _state.value
        val question = current.question ?: return
        if (current.grading || current.onBreak) return
        // Reveal is required exactly as in grade(): a learner cannot grade an
        // answer they have not been shown.
        if (current.selfGraded && !current.revealed) return
        if (!current.revealed) return
        pauseQuestionClock()
        val paused = _state.value
        // Classic has no Check, so this is where the dwell freezes. It is the
        // same frozen value a later grade change and Next persist.
        val dwellSeconds = com.mcqapp.domain.Study.gradedDwellSeconds(paused.questionElapsedMillis)
        _state.update {
            it.copy(
                revealed = true,
                gradedDwellSeconds = dwellSeconds,
                pendingGrade = grade,
                proposedGrade = null,
                gradeEdited = false,
                lastResult = buildResult(
                    question, it.selection, grade, it.isGuess, dwellSeconds,
                    System.currentTimeMillis()
                )
            )
        }
        Logger.d("STUDY", "staged ${question.id} -> $grade (confirm before next)")
    }

    /**
     * Persists the proposed grade and pulls the next card. Guarded like [grade]:
     * the write is async, so a second tap would persist two reviews for one
     * question.
     */
    fun next() {
        val current = _state.value
        // A grade already landed before the next-card pull failed: the review is
        // on disk, so Next must not write it a second time.
        if (current.gradedAndStuck) return
        val question = current.question ?: return
        val proposed = current.pendingGrade ?: return
        if (!current.stagedGrading || !current.revealed) return
        if (current.grading || current.onBreak) return
        if (!current.simplified) {
            // Opt-in four-button flow: the staged grade is written by the exact
            // same one-tap grade path, only later. review_log semantics — CHOSEN,
            // no app proposal, null correctness for self-graded — do not change.
            grade(proposed)
            return
        }
        _state.update { it.copy(grading = true, gradeError = null) }
        pauseQuestionClock()
        val dwellSeconds = _state.value.gradedDwellSeconds
        // sessionScope, not viewModelScope: a pop of the screen clears the
        // latter and would cancel this write after the tap was already spent.
        gradeWriteJob = sessionScope.launch {
            awaitGradeGateForTests()
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
                // Remember which card this write graded: stop() needs to know
                // whether the next card was served afterwards.
                lastPersistedGradeQuestionId = question.id
                Logger.i(
                    "STUDY",
                    "graded ${question.id} -> $proposed (auto), next in ${next.intervalDays}d, " +
                        "leech=${next.leech}"
                )
                advanceStats(proposed)
                // A stop or a bare clear during the write ends the session;
                // serving a new card would undo it (consolidated_bugs.md I4).
                // Either may have ended the sitting before this review was
                // tallied, so record the row now; endSitting's CAS keeps it
                // to exactly one row.
                if (sittingEnded()) {
                    endSitting()
                    _state.update { it.copy(grading = false) }
                    return@launch
                }
                val becameLeech = crossedLeechThreshold(current.current?.state, next)
                pullCard()
                // Set AFTER the pull: the notice belongs to the card just
                // served and is cleared by the following one.
                if (becameLeech && !_state.value.finished) {
                    _state.update { it.copy(justFlaggedLeech = true) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A closed database surfaces its failure as cancellation, not
                // as an exception; rethrow only when THIS job is actually
                // being cancelled, or the failed write reports nothing and
                // the buttons freeze mid-grade.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _state.update {
                    it.copy(
                        grading = false,
                        gradeError = if (persisted) friendly(R.string.study_next_card_failed)
                        else friendly(R.string.study_grade_save_failed),
                        gradedAndStuck = persisted
                    )
                }
            } catch (e: Exception) {
                Logger.e("STUDY", "recordStudyReview(${question.id}) failed", e)
                _state.update {
                    it.copy(
                        grading = false,
                        gradeError = if (persisted) friendly(R.string.study_next_card_failed)
                        else friendly(R.string.study_grade_save_failed),
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
        // A previous write for this card already succeeded (only the pull
        // failed): another grade would append a second review_log row.
        if (current.gradedAndStuck) return
        val question = current.question ?: return
        // Claim the card before launching: the write is async, so a second tap
        // would otherwise persist two reviews for the same question.
        if (current.grading || current.onBreak) return
        // Defence behind the footers: a self-graded question is graded from its
        // answer, and nothing may grade one the learner has not been shown. MCQ
        // grading is deliberately unchanged — its buttons have always implied
        // the reveal.
        if (current.selfGraded && !current.revealed) return
        _state.update { it.copy(grading = true, gradeError = null) }
        pauseQuestionClock()
        // Classic mode has no Check, so the grade tap is the freeze point.
        val paused = _state.value
        val dwellSeconds = com.mcqapp.domain.Study.gradedDwellSeconds(paused.questionElapsedMillis)
        _state.update { it.copy(gradedDwellSeconds = dwellSeconds) }
        // Durable scope so popping the screen mid-write cannot lose the grade.
        gradeWriteJob = sessionScope.launch {
            awaitGradeGateForTests()
            // Persist and pull are split: a pull failure after a persisted
            // grade must not read as a failed grade — the review is already
            // stored, and retrying would write a second one
            // (consolidated_bugs.md I3).
            var persisted = false
            try {
                // A self-graded question has no key, so there is no right or
                // wrong answer to record and no app proposal: the learner's own
                // button is the whole decision. Storing false/empty here would
                // claim the answer was wrong and contaminate the calibration,
                // which reads only AUTO rows.
                val selfGraded = current.selfGraded
                val next = repository.recordStudyReview(
                    paperId = paperId,
                    questionId = question.id,
                    grade = grade,
                    config = config,
                    dwellSeconds = dwellSeconds,
                    // The four-button flow has no guess switch: the learner is
                    // self-grading a revealed answer, which is the "earned"
                    // side of the dashboard by construction.
                    wasGuess = if (selfGraded) null else false,
                    wasCorrect = if (selfGraded) null else isCorrect(question, current.selection),
                    // Classic mode has no app proposal.
                    proposedGrade = null,
                    gradeSource = com.mcqapp.domain.GradeSource.CHOSEN,
                    wasSkipped = if (selfGraded) false else current.selection.isEmpty()
                )
                persisted = true
                // The one-tap grade may have advanced to another card by the
                // time stop() runs; remember the card this write settled.
                lastPersistedGradeQuestionId = question.id
                Logger.i(
                    "STUDY",
                    "graded ${question.id} -> $grade, next in ${next.intervalDays}d, " +
                        "leech=${next.leech}, selfGraded=$selfGraded"
                )
                advanceStats(grade, selfGraded = if (selfGraded) 1 else 0)
                // A stop or a bare clear that landed during the write may
                // have ended the sitting before this review was tallied;
                // record the row now (the CAS guard keeps it to exactly one).
                if (sittingEnded()) {
                    endSitting()
                    _state.update { it.copy(grading = false) }
                    return@launch
                }
                val becameLeech = crossedLeechThreshold(current.current?.state, next)
                pullCard()
                // Set AFTER the pull: the notice belongs to the card just
                // served and is cleared by the following one.
                if (becameLeech && !_state.value.finished) {
                    _state.update { it.copy(justFlaggedLeech = true) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A closed database surfaces its failure as cancellation, not
                // as an exception; rethrow only when THIS job is actually
                // being cancelled, or the failed write reports nothing and
                // the buttons freeze mid-grade.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _state.update {
                    it.copy(
                        grading = false,
                        gradeError = if (persisted) friendly(R.string.study_next_card_failed)
                        else friendly(R.string.study_grade_save_failed),
                        gradedAndStuck = persisted
                    )
                }
            } catch (e: Exception) {
                Logger.e("STUDY", "recordStudyReview(${question.id}) failed", e)
                _state.update {
                    it.copy(
                        grading = false,
                        gradeError = if (persisted) friendly(R.string.study_next_card_failed)
                        else friendly(R.string.study_grade_save_failed),
                        gradedAndStuck = persisted
                    )
                }
            }
        }
    }

    private fun advanceStats(grade: ReviewGrade, selfGraded: Int = 0) {
        _state.update {
            val reviewed = it.reviewed + 1
            it.copy(
                reviewed = reviewed,
                againCount = it.againCount + if (grade == ReviewGrade.AGAIN) 1 else 0,
                goodCount = it.goodCount + if (grade == ReviewGrade.AGAIN) 0 else 1,
                selfGradedReviewed = it.selfGradedReviewed + selfGraded,
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
        if (!current.stagedGrading || !current.revealed || current.grading ||
            question == null || proposed == null || current.gradedAndStuck
        ) {
            // Nothing staged to write: classic mode persists on each grade
            // button unless the learner opted in, an unchecked card has no
            // decision to keep, and a grade already persisted before a failed
            // pull must not be written again — a second review would double the
            // tally, FSRS state and review_log.
            // A grade that already advanced to a different card left that card
            // behind the summary, so clear it. When the graded card is still on
            // screen (graded-and-stuck, or a stop that landed during the write)
            // keep it — that is the card the learner's grade belongs to.
            finishSession(
                clearCard = lastPersistedGradeQuestionId != null &&
                    lastPersistedGradeQuestionId != current.question?.id
            )
            return
        }
        if (!current.simplified) {
            // Opt-in four-button flow: persist the staged grade through the
            // exact path Next uses. grade() sees the stop latch and neither
            // pulls another card nor writes twice (consolidated_bugs.md I4).
            // The card it graded is still in state though, so close the sitting
            // with it cleared: nothing may sit behind the summary waiting to be
            // graded a second time. The summary waits for the write so the tally
            // is already in it.
            grade(proposed)
            sessionScope.launch {
                gradeWriteJob?.join()
                finishSession(clearCard = true)
            }
            return
        }
        _state.update { it.copy(grading = true, gradeError = null) }
        val dwellSeconds = current.gradedDwellSeconds
        // Durable scope: "Done" can pop the screen while this write is in flight.
        gradeWriteJob = sessionScope.launch {
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
                lastPersistedGradeQuestionId = question.id
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
                    it.copy(gradeError = friendly(R.string.study_grade_save_failed))
                }
            } catch (e: Exception) {
                Logger.e("STUDY", "recordStudyReview(${question.id}) on stop failed", e)
                // The stop itself never blocks: a failed write is reported on
                // the summary instead of trapping the learner in the session.
                _state.update {
                    it.copy(gradeError = friendly(R.string.study_grade_save_failed))
                }
            }
            // Simplified graded this card in one tap, then the scheduler served
            // the next one. Close with no card behind the summary too — the
            // grade itself and its provenance are unchanged.
            finishSession(clearCard = true)
        }
    }

    /**
     * Closes the sitting into the summary, with the tally and the times frozen.
     * [clearCard] is set only by the opt-in staged-stop path: that card has just
     * been graded but was never advanced, so leaving it in state would show a
     * second gradeable card behind the summary. Other exits keep the card — a
     * graded-and-stuck stop, for instance, must still report the card it holds.
     */
    private fun finishSession(clearCard: Boolean = false) {
        val totalSeconds = _sessionSeconds.value
        endSitting()
        _state.update { state ->
            val base = if (clearCard) {
                state.copy(
                    current = null,
                    question = null,
                    passage = null,
                    previews = emptyMap(),
                    pendingGrade = null,
                    proposedGrade = null,
                    gradeEdited = false,
                    selection = emptySet(),
                    revealed = false,
                    lastResult = null
                )
            } else state
            base.copy(
                grading = false,
                finished = true,
                sessionTotalSeconds = totalSeconds,
                emptyReason = when {
                    // Work was done: a finished sitting with its tally.
                    state.reviewed > 0 -> EmptyReason.None
                    // Stopped before the first review. "Session complete /
                    // 0 reviewed" claimed a sitting that never happened; the
                    // summary says what actually did.
                    else -> EmptyReason.StoppedEarly
                }
            )
        }
    }

    /**
     * Bury the current card until the next study-day boundary. No grade and no
     * `review_log` row: the FSRS schedule is left exactly as it was, and the
     * card comes back automatically when the boundary passes.
     */
    fun buryCurrent() = actOnCurrent("bury") { questionId ->
        val hour = config?.dayStartHour ?: DayBoundary.DEFAULT_DAY_START_HOUR
        val deadline = SuspendSchedule.nextBoundary(System.currentTimeMillis(), hour)
        suspendStore.bury(paperId, questionId, deadline)
    }

    /** Suspend the current card until the learner explicitly unsuspends it. */
    fun suspendCurrent() = actOnCurrent("suspend") { questionId ->
        suspendStore.suspend(paperId, questionId)
    }

    /**
     * Shared claim/write/pull path for the two card actions. The write is
     * durable ([sessionScope]) like a grade, but never calls the grading path:
     * a buried or suspended card must not gain a review or move its due date.
     */
    private fun actOnCurrent(action: String, write: suspend (String) -> Unit) {
        val current = _state.value
        if (!studyCanActOnCard(current)) return
        val question = current.question ?: return
        pauseQuestionClock()
        _state.update {
            it.copy(
                grading = true,
                cardActionInProgress = true,
                cardActionError = null
            )
        }
        gradeWriteJob = sessionScope.launch {
            try {
                write(question.id)
                refreshSuspendedCount()
                // A stop (or a bare clear) that landed during the write owns
                // the exit; do not reopen the sitting with another card.
                if (sittingEnded()) {
                    endSitting()
                    _state.update { it.copy(grading = false) }
                    return@launch
                }
                pullCard()
            } catch (e: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _state.update {
                    it.copy(
                        grading = false,
                        cardActionError = friendly(R.string.suspend_action_failed)
                    )
                }
            } catch (e: Exception) {
                Logger.e("STUDY", "$action(${question.id}) failed", e)
                _state.update {
                    it.copy(
                        grading = false,
                        cardActionError = friendly(R.string.suspend_action_failed)
                    )
                }
            } finally {
                _state.update { it.copy(cardActionInProgress = false) }
            }
        }
    }

    /** Re-reads the header indicator after a flag write. */
    private suspend fun refreshSuspendedCount() {
        val count = try {
            suspendStore.suspendedCards(paperId, System.currentTimeMillis()).size
        } catch (e: Exception) {
            Logger.e("STUDY", "suspendedCards($paperId) refresh failed", e)
            _state.value.suspendedCount
        }
        _state.update { it.copy(suspendedCount = count) }
    }

    /** Opens the unsuspend route with a fresh list for this paper. */
    fun openSuspendedDialog() {
        viewModelScope.launch {
            try {
                val rows = suspendStore.suspendedCards(paperId, System.currentTimeMillis())
                _state.update {
                    it.copy(showSuspendedDialog = true, suspendedCards = rows, suspendedCount = rows.size)
                }
            } catch (e: Exception) {
                Logger.e("STUDY", "openSuspendedDialog failed", e)
                _state.update { it.copy(cardActionError = friendly(R.string.suspend_action_failed)) }
            }
        }
    }

    fun closeSuspendedDialog() {
        _state.update { it.copy(showSuspendedDialog = false) }
    }

    /**
     * Restores one card to the pool. The list is reloaded in the same call so
     * the dialog never shows a card that is already back in Study.
     */
    fun unsuspend(questionId: String) {
        viewModelScope.launch {
            try {
                suspendStore.unsuspend(paperId, questionId)
                val rows = suspendStore.suspendedCards(paperId, System.currentTimeMillis())
                _state.update {
                    it.copy(suspendedCards = rows, suspendedCount = rows.size)
                }
            } catch (e: Exception) {
                Logger.e("STUDY", "unsuspend($questionId) failed", e)
                _state.update { it.copy(cardActionError = friendly(R.string.suspend_action_failed)) }
            }
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
     * Banks both clocks without ending the sitting. The strip offers this
     * beside Dismiss: dismissing only silences the suggestion, while this stops
     * the timing until the learner is back. Answering is parked while on break
     * (the grade/option gates check [StudyUiState.onBreak]) so no dwell can be
     * charged to a card nobody is looking at.
     */
    fun takeBreak() {
        val current = _state.value
        if (current.finished || current.onBreak) return
        pauseQuestionClock()
        bankSessionClock()
        publishSessionSeconds()
        // The suggestion has been acted on; it must not reappear at this marker.
        _state.update { it.copy(onBreak = true, breakDismissedAt = it.reviewed) }
    }

    /** Leaves the break with the tally and the sitting intact; clocks restart. */
    fun resumeFromBreak() {
        if (!_state.value.onBreak) return
        resumeSessionClock()
        publishSessionSeconds()
        _state.update { it.copy(onBreak = false) }
        // The card clock restarts only if the card is still in view and was not
        // deliberately paused by the learner before the break.
        resumeQuestionClock()
    }

    /**
     * Dismisses the one-time grading disclosure for good. The state flips at
     * once (so the card is never left showing a stale hint), and the durable
     * write follows on IO: a rotation keeps the same ViewModel and reads the
     * flipped state, while a process death starts a fresh ViewModel that reads
     * the persisted flag. Neither brings the hint back.
     */
    fun dismissGradingHint() {
        if (_state.value.gradingHintDismissed) return
        _state.update { it.copy(gradingHintDismissed = true) }
        // sessionScope outlives onCleared; a fast Back must not drop the write.
        sessionScope.launch {
            try {
                repository.markStudyGradingHintShown()
            } catch (e: Exception) {
                // Best effort: the in-memory flag still hides it this session;
                // a failed write only risks the hint returning next process.
                Logger.e("STUDY", "markStudyGradingHintShown failed", e)
            }
        }
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
                // The empty state froze both clocks and closed the sitting
                // (finishEmpty). Taking the offer continues that sitting, so
                // re-base BOTH clocks and the sitting's wall-clock start before
                // the first card is pulled: pullCard only anchors the card's
                // segment from `questionInView`, and left the session clock
                // banked and the session row pointing at the empty sitting.
                resetSessionClock()
                sessionStartedAtWall = System.currentTimeMillis()
                sittingEnded.set(false)
                clockPausedByUser = false
                questionInView = true
                _state.update {
                    it.copy(
                        introduceNewCards = true,
                        loading = true,
                        sessionTotalSeconds = 0L,
                        emptyReason = EmptyReason.None,
                        onBreak = false,
                        justFlaggedLeech = false
                    )
                }
                // A tag/mistake sitting serves a snapshot resolved at load with
                // the old toggle off, so flipping the setting alone would leave
                // this scoped session empty. Re-resolve its pool from live data
                // before the pull; the whole-paper session pulls live already.
                if (studyScope.isRestricted) {
                    drillCards = resolveScopedDrillCards()
                    drillIndex = 0
                }
                pullCard()
                _state.update { it.copy(loading = false) }
            } catch (e: Exception) {
                Logger.e("STUDY", "enableNewCards failed", e)
                _state.update { it.copy(loading = false, loadError = friendly(R.string.study_session_start_failed)) }
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
    private fun refreshPreviews(exposureInStudyDay: Int = _state.value.exposuresInStudyDay) {
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
                        .previewDelays(
                            scheduler,
                            card,
                            System.currentTimeMillis(),
                            exposureInStudyDay
                        )
                        .mapValues { (_, delay) -> IntervalFormat.format(delay) }
                }
            )
        }
    }

    /** Re-pulls from the top so a session can be repeated. */
    fun restart() {
        // Durable IO scope, not viewModelScope: the grade write and the sitting's
        // row write this joins both live on sessionScope (IO). Joining them from
        // Main parks this coroutine across a dispatcher hop, and the fresh pull's
        // own DataStore/Room resumes then depend on the UI looper; running the
        // whole chain on IO keeps it independent of that pump. _state is a
        // thread-safe StateFlow, so the UI still sees every update.
        sessionScope.launch {
            try {
                // A grade earned by the old sitting may still be on the durable
                // session scope. Join it BEFORE clearing the exit latches: while
                // stopped/cleared still hold, its continuation closes the review
                // into the original sitting (one session row) and neither pulls a
                // second card nor lands a late error on the fresh sitting.
                gradeWriteJob?.join()
                // Never start a new sitting while the previous one's end-of-
                // sitting write is still running: its timing row must land
                // before the next session records its own start. Read after the
                // grade join, since that continuation may have just set it.
                sessionEndJob?.join()
                // "Study again" revives a session that stop() ended. Without this
                // the next grade persists and then the stopped guard suppresses
                // the pull, leaving the same revealed card to be graded — and
                // logged — again. Cleared only once the old sitting is fully
                // attributed and closed.
                stopped.set(false)
                // A fresh sitting has no grade of its own yet. Without this the
                // previous sitting's last grade would make this sitting's stop
                // clear a card that was never advanced.
                lastPersistedGradeQuestionId = null
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
                } else if (studyScope.isRestricted) {
                    // Tag and mistake pools are snapshots too: a card suspended
                    // mid-sitting, or a question answered correctly since, must
                    // not be replayed by "Study again".
                    drillCards = resolveScopedDrillCards()
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
                        selfGradedReviewed = 0,
                        questionMillis = 0L,
                        sessionTotalSeconds = 0L,
                        reviewsSinceNew = 0,
                        finished = false,
                        emptyReason = EmptyReason.None,
                        freshAvailable = false,
                        selection = emptySet(),
                        revealed = false,
                        grading = false,
                        gradedAndStuck = false,
                        pendingGrade = null,
                        proposedGrade = null,
                        gradeEdited = false,
                        lastResult = null,
                        // A failed write reported on the old summary must not
                        // follow the learner into the fresh sitting.
                        gradeError = null,
                        cardActionError = null,
                        cardActionInProgress = false,
                        showSuspendedDialog = false,
                        isGuess = false,
                        questionStartedAt = 0L,
                        questionElapsedMillis = 0L,
                        gradedDwellSeconds = 0L,
                        clockRunning = false,
                        breakDismissedAt = 0,
                        justFlaggedLeech = false,
                        onBreak = false,
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
                    it.copy(loading = false, loadError = friendly(R.string.study_session_start_failed))
                }
            }
        }
    }

    /**
     * Writes this sitting's study time once. Called from both exit paths, from
     * the end branch of an in-flight grade, and from [onCleared]; the flag
     * makes the race harmless. A sitting with no reviews is not recorded — and
     * does not burn the flag — so when a grade is still in flight at the exit,
     * the write's continuation calls this again once the review is tallied.
     * That covers a bare clear as well as stop(): the [sittingEnded] check in
     * the grade paths is not gated on [stopped] alone. The CAS keeps the two
     * calls to exactly one row.
     */
    private fun endSitting() {
        if (_state.value.reviewed <= 0) return
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
        // Latch the bare-clear path before flushing: a grade in flight on the
        // durable scope may tally its review after this returns, and its
        // continuation then has to end the sitting even though stop() was
        // never called.
        cleared.set(true)
        // The screen can be destroyed without either exit path running. Bank
        // the clocks first, then flush; the write is idempotent.
        bankSessionClock()
        publishSessionSeconds()
        endSitting()
        super.onCleared()
    }
}
