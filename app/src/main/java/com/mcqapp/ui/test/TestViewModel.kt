package com.mcqapp.ui.test

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.domain.AnswerReview
import com.mcqapp.domain.AttemptMetadata
import com.mcqapp.domain.AutoAdvance
import com.mcqapp.domain.BoundedSample
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.clockMayRun
import com.mcqapp.domain.Countdown
import com.mcqapp.domain.Dwell
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.SessionOrder
import com.mcqapp.domain.SessionReviewPagingSource
import com.mcqapp.domain.Shuffle
import com.mcqapp.domain.TestAnswer
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestSetup
import com.mcqapp.domain.TestSnapshot
import com.mcqapp.domain.TestType
import com.mcqapp.domain.TimerWarnings
import com.mcqapp.domain.signals.Breadth
import com.mcqapp.domain.signals.Selection
import com.mcqapp.domain.signals.TestSelector
import com.mcqapp.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class TestUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val paper: Paper? = null,
    /**
     * The session's order — ids and passage membership, held as one pool with
     * offsets rather than one object per question, so a million-question paper
     * is tens of megabytes and not hundreds. The question on screen is
     * resolved by id on navigation, and its passage membership is what keeps a
     * comprehension set together through the shuffle and a drill's cut.
     */
    val order: SessionOrder = SessionOrder.empty(),
    /** The question on screen, resolved by id from its ref. */
    val current: Question? = null,
    /** The passage the resolved question belongs to, when it has one. */
    val currentPassage: com.mcqapp.domain.Passage? = null,
    /** Questions with no answer key — excluded from scoring entirely. */
    val ungradedTotal: Int = 0,
    val currentIndex: Int = 0,
    val selections: Map<String, Set<String>> = emptyMap(),
    val revealed: Set<String> = emptySet(),
    val flagged: Set<String> = emptySet(),
    val remainingSeconds: Int = 0,
    val totalSeconds: Int = 0,
    val submitted: Boolean = false,
    val saving: Boolean = false,
    /** Result rows written so far while a large paper is being saved. */
    val saveDone: Int = 0,
    val saveTotal: Int = 0,
    val saveError: String? = null,
    val attemptId: Long? = null,
    val practiceMode: Boolean = false,
    val strictMode: Boolean = false,
    val autoAdvance: Boolean = false,
    val mistakesOnly: Boolean = false,
    val isDrill: Boolean = false,
    /** The requested drill count; a passage block may push [order] past it. */
    val drillRequested: Int = 0,
    /** The bounded-Test selector name; null on a legacy session (section 12.2). */
    val testType: String? = null,
    /** The plan's requested length (0 on a legacy session); [questionCount] is the shown count. */
    val requestedCount: Int = 0,
    val resumeOffer: TestSnapshot? = null,
    /** How many saved questions still resolve; the dialog warns below 20. */
    val resumeOfferCount: Int = 0,
    /** Whether the offered snapshot predates the bounded-Test metadata. */
    val resumeOfferLegacy: Boolean = false,
    val dwellSeconds: Map<String, Long> = emptyMap(),
    /** Whether the per-question clock currently accrues time. */
    val dwellActive: Boolean = false,
    /** Whether the learner paused the per-question clock by tapping it. */
    val dwellPausedByUser: Boolean = false,
    val timeWarning: String? = null,
    val bookmarked: Set<String> = emptySet(),
    /** Whether the pre-submit review sheet is open; its rows page separately. */
    val reviewOpen: Boolean = false
) {
    /** The question on screen. */
    val currentQuestion: Question? get() = current

    val answeredCount: Int get() = selections.count { it.value.isNotEmpty() }
    val isCurrentRevealed: Boolean get() = current?.id in revealed
    val questionCount: Int get() = order.size
}

@kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TestViewModel(
    application: Application,
    private val paperId: String,
    private val categoryIds: List<String>,
    private val mistakesOnly: Boolean = false,
    private val drillCount: Int = 0,
    private val drillMinutes: Int = 0,
    /** Per-attempt duration from the start picker; 0 = untimed. */
    private val durationMinutes: Int = com.mcqapp.domain.ExamTiming.USE_PAPER_DURATION,
    /**
     * The bounded-Test selector name from the route, or blank for a legacy
     * deep link. A missing type is legacy, never Random (section 16 C5).
     */
    private val routeTestType: String = "",
    /** The plan's requested length; 0 for a legacy link. */
    private val routeRequestedCount: Int = 0,
    /** The route's exclusion rule name; blank means [ExclusionMode.NONE]. */
    private val routeExclusionMode: String = "",
    /** The Weakest/Mixed breadth name; blank means the medium default. */
    private val routeBreadth: String = "",
    /** The custom type's choice (phase 3: tag or difficulty name), or blank. */
    private val routeVariant: String = ""
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    /**
     * The bounded plan this session runs, or null for a legacy deep link
     * (whole paper, category scope, mistakes practice or drill). The plan is
     * assembled in [load] once the paper — and therefore the expanded scope —
     * is known, and it is what the save writes into the attempt metadata.
     */
    private var sessionPlan: TestPlan? = null

    /** Canonical expanded scope key (section 12.1); never empty after load. */
    private var sessionScopeKey: String = ""

    /**
     * True when this session's order is the paper's mistakes — a bounded
     * `MISTAKES` plan or the legacy mistakes route. The banner, the resume
     * identity and the resume recount all read this, not the raw route flag.
     */
    private var isMistakesSession: Boolean = false

    /**
     * Set when the resume slot holds a *different* session of this paper and
     * this bounded session is about to take it. The first save drops the old
     * answer rows (section 7.6): they are keyed by paper, so leaving them would
     * join the abandoned session's answers into this one on the next resume.
     * Merely opening the screen does not clear them — the saved session stays
     * resumable from its own route until this one actually writes.
     */
    private var dropStaleAnswersOnSave: Boolean = false

    private val _state = MutableStateFlow(TestUiState())
    val state: StateFlow<TestUiState> = _state.asStateFlow()

    /**
     * The pre-submit review rows, paged over the session order. Rows resolve
     * only as the sheet scrolls, so reviewing a 250k-question session never
     * holds more than a page of question content.
     */
    private val reviewPaging =
        MutableStateFlow<Flow<PagingData<AnswerReview.Row>>>(flowOf(PagingData.empty()))
    val reviewPagingData: Flow<PagingData<AnswerReview.Row>> = reviewPaging.flatMapLatest { it }

    /**
     * Seconds spent on the question currently on screen, ticking once a
     * second.
     *
     * Separate from [TestUiState.dwellSeconds] on purpose: the header reads
     * this, so the per-second tick invalidates only the header instead of the
     * whole question body and its images.
     */
    private val _liveDwell = MutableStateFlow(0L)
    val liveDwell: StateFlow<Long> = _liveDwell.asStateFlow()

    private var timerJob: Job? = null
    private var dwellJob: Job? = null
    /** Wall-clock anchor of [startTimer]: the remaining seconds and the moment. */
    private var timerAnchorRemaining: Int = 0
    private var timerAnchorElapsed: Long = 0

    /**
     * The last visibility the screen reported (viewport or a covering surface).
     * Remembered because the screen's visibility effect only re-runs when the
     * flag flips; a foreground has to ask what it last said.
     */
    private var questionInView = true
    /** Seed for the per-question option shuffle; 0 when options stay put. */
    private var optionShuffleSeed: Long = 0L
    /** The session's shuffle/drill seeds, so the resume snapshot can
     *  reproduce the presented order from a few bytes. */
    private var questionShuffleEnabled: Boolean = false
    private var sessionSeed: Long = 0L
    private var drillSampleSeed: Long = 0L
    /** The order a pending resume would present; rebuilt while the base order
     *  is still loaded, so the unshuffled one is not retained. */
    private var resumeOrder: SessionOrder = SessionOrder.empty()

    /**
     * The id list of a resumed *legacy* snapshot (one with no shuffle seed).
     * Autosaves keep writing it — and a null seed — so a second resume replays
     * the same order instead of stamping the fresh random seed over it and
     * silently reverting to the authored order.
     */
    private var legacyResumedIds: List<String>? = null

    /** The card resolved ahead of the visible one, keyed by its ref id. */
    private var prefetched: Pair<String, LoadedCard>? = null

    private data class LoadedCard(
        val question: Question,
        val passage: com.mcqapp.domain.Passage?
    )

    private companion object {
        /** Questions resolved per review-sheet page. */
        const val REVIEW_PAGE = 100
    }
    // Monotonic anchors (consolidated_bugs.md D10): the countdown already
    // ran on elapsedRealtime; dwell and the untimed duration join it, so an
    // NTP or manual clock change cannot distort either or store a negative
    // duration. Wall time is kept only for `finishedAt`.
    private var startElapsedMs: Long = 0
    private var lastNavElapsedMs: Long = 0

    /**
     * Dwell banked per question as of the last navigation. The published
     * [TestUiState.dwellSeconds] adds the time spent on the current question
     * since then, so the running total is derived from this plus the
     * navigation anchor rather than accumulated once per second.
     */
    private val dwellCommitted = mutableMapOf<String, Long>()
    private val warnedThresholds = mutableSetOf<Int>()

    init {
        Logger.i("TESTVM", "TestViewModel created: paperId=$paperId, categoryIds=${categoryIds.size} categories")
        viewModelScope.launch {
            repository.observeBookmarks().collect { ids ->
                _state.update { it.copy(bookmarked = ids.toSet()) }
            }
        }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                Logger.d("TESTVM", "Loading paper $paperId")
                val paper = repository.getPaper(paperId)
                // The route carries the ticked ids; the served scope is their
                // expansion over the paper's tree. A parent id alone matches no
                // question (the queries filter questions.categoryId), so
                // everything below this line takes the expanded list — only the
                // resume snapshot keeps the ticked ids it was saved with.
                val scopeIds = if (categoryIds.isEmpty()) {
                    emptyList()
                } else {
                    CategorySelection.orderedIds(paper?.categories.orEmpty(), categoryIds)
                }
                // One canonical key for the attempt metadata and the resume
                // snapshot. The whole paper is its own constant, never the
                // empty string; a scoped key hashes the expansion computed
                // above, so the tick order cannot leak into it (section 12.1).
                val scopeKey = if (categoryIds.isEmpty()) {
                    ScopeKey.wholePaper(paperId)
                } else {
                    ScopeKey.ofExpanded(scopeIds)
                }
                sessionScopeKey = scopeKey
                val plan = buildPlan(scopeKey)
                Logger.d(
                    "TESTVM",
                    "Loaded paper '${paper?.title}', categories=${paper?.categories?.size ?: 0}, " +
                        "scope=${scopeIds.size}, key=$scopeKey, plan=${plan?.type ?: "legacy"}"
                )
                // One shape for the whole session: a bounded `MISTAKES` plan is
                // a mistakes practice even when the legacy route flag is off,
                // so the banner, the resume identity and the ungraded recount
                // all have to agree with the order query below.
                val mistakesSession = plan?.type == TestType.MISTAKES || mistakesOnly
                isMistakesSession = mistakesSession
                // Session order only: the load streams the ids and passage
                // membership out of the database, so a million-question paper
                // never materialises its refs, let alone its content.
                val baseOrder = if (mistakesSession) {
                    mistakenOrderInScope(scopeIds)
                } else if (categoryIds.isEmpty()) {
                    repository.getSessionOrderForPaper(paperId)
                } else {
                    repository.getSessionOrderForCategories(paperId, scopeIds)
                }
                // Repeat avoidance (section 9.8): drop the whole blocks a
                // recent comparable attempt asked, but only when the remaining
                // pool is still at least twice the request. A rule that could
                // not exclude anything is recorded as NONE, so an attempt is
                // never labelled a fresh sample under a rule it did not follow.
                val effectivePlan: TestPlan?
                val order: SessionOrder
                var recentIds: Set<String> = emptySet()
                if (plan != null && plan.exclusionMode == ExclusionMode.RECENT_3) {
                    recentIds = if (plan.type?.supportsFreshSample == true) {
                        repository.recentComparableQuestionIds(
                            paperId = paperId,
                            testTypes = TestType.representativeNames(),
                            scopeKey = scopeKey,
                            requestedCount = plan.count,
                            limit = 3
                        )
                    } else {
                        emptySet()
                    }
                    val filtered = baseOrder.excludingBlocksContaining(recentIds)
                    val applied = filtered.size < baseOrder.size &&
                        TestPlan.freshSampleFits(filtered.size, plan.count)
                    order = if (applied) filtered else baseOrder
                    effectivePlan = if (applied) {
                        plan
                    } else {
                        plan.copy(exclusionMode = ExclusionMode.NONE)
                    }
                    Logger.i(
                        "TESTVM",
                        "Repeat avoidance: asked=${recentIds.size}, base=${baseOrder.size}, " +
                            "after=${filtered.size}, applied=$applied"
                    )
                } else {
                    order = baseOrder
                    effectivePlan = plan
                }

                // Phase-2 focus selectors rank the scope's evidence once and
                // freeze the chosen ids. The plan records the targets and the
                // fill composition, and a resume replays these ids instead of
                // re-ranking after the metrics move.
                val focusSelection = if (effectivePlan?.type?.usesSignalSelector == true) {
                    val signals = repository.getQuestionSignals(paperId, scopeIds)
                    val pool = if (recentIds.isEmpty()) {
                        signals
                    } else {
                        signals.filterNot { it.questionId in recentIds }
                    }
                    val breadth = Breadth.entries
                        .firstOrNull { it.name == routeBreadth }
                        ?: Breadth.MEDIUM
                    // The custom pools (phase 3) are the same reads the setup
                    // sheet previews: the closest the live selection can
                    // come to the preview.
                    val customPool = when (effectivePlan.type) {
                        TestType.BOOKMARKED -> repository.bookmarkedIdsInScope(
                            paperId, categoryIds
                        ).toSet()
                        TestType.TAG -> effectivePlan.variant?.let {
                            repository.questionIdsForTagInScope(
                                paperId, categoryIds, com.mcqapp.domain.TagNormalizer.fold(it)
                            ).toSet()
                        } ?: emptySet()
                        TestType.FIXED_PANEL -> effectivePlan.variant
                            ?.toLongOrNull()
                            ?.let { panelId -> repository.panel(panelId)?.questionIds?.toSet() }
                            ?: emptySet()
                        else -> emptySet()
                    }
                    val medianDwellSeconds = if (effectivePlan.type == TestType.SLOWEST) {
                        repository.medianDwellSecondsInScope(paperId, categoryIds)
                    } else {
                        emptyMap()
                    }
                    TestSelector.select(
                        effectivePlan, pool, breadth, customPool, medianDwellSeconds
                    )
                } else {
                    null
                }
                val finalPlan = if (focusSelection != null && effectivePlan != null) {
                    effectivePlan.copy(
                        targetTopicIds = focusSelection.targets,
                        compositionJson = focusSelection.composition.toJson()
                    )
                } else {
                    effectivePlan
                }
                sessionPlan = finalPlan
                Logger.i("TESTVM", "Loaded ${order.size} questions, duration=${paper?.durationMinutes}min" +
                    if (mistakesSession) " (mistakes only)" else "")
                val shuffleQ = repository.shuffleQuestions().first()
                val shuffleO = repository.shuffleOptions().first()
                // A bounded plan's seed is its sampling seed (recorded on the
                // attempt); a legacy session keeps the old random seed.
                val seed = effectivePlan?.seed ?: Random.nextLong()
                questionShuffleEnabled = shuffleQ
                sessionSeed = seed
                if (shuffleQ || shuffleO) {
                    Logger.i("TESTVM", "Shuffled attempt: seed=$seed, " +
                        "questions=$shuffleQ, options=$shuffleO")
                }
                // A bounded plan is not a drill: its length comes from the
                // plan's resolution, not from the legacy drill route.
                val isDrill = plan == null && drillCount > 0
                // Block-aware: passage members move as one block, so a
                // comprehension set is never split by the shuffle or the cut.
                // (A seed snapshot replays this, so the algorithm and its
                // Random stream live in SessionOrder, not here.)
                //
                // The shuffle and the drill cut iterate the whole paper and
                // allocate multi-megabyte index arrays, so they run off the
                // main thread; only the resulting order is published to state.
                val (_, drilled) = withContext(Dispatchers.Default) {
                    if (finalPlan != null) {
                        if (focusSelection != null) {
                            // A focus selector already chose and froze the ids;
                            // only the presentation shuffle remains. Re-sampling
                            // here would re-rank or change the recorded sample.
                            val focusOrder = repository.getSessionOrderForIds(focusSelection.ids)
                            if (focusOrder.isEmpty && focusSelection.ids.isNotEmpty()) {
                                throw IllegalStateException("The selected questions no longer exist.")
                            }
                            val presented = focusOrder.shuffled(seed, shuffleQ)
                            drillSampleSeed = 0L
                            Logger.i(
                                "TESTVM",
                                "Focus ${finalPlan.type}: requested=${finalPlan.count}, " +
                                    "selected=${focusSelection.ids.size}, shown=${presented.size}, " +
                                    "targets=${focusSelection.targets.size}, seed=$seed"
                            )
                            order to presented
                        } else {
                            // The plan's requested length is resolved against the
                            // type's pool (the base order), then sampled exactly:
                            // a short scope runs whole, a request above the pool is
                            // clamped, and a passage block may overshoot the target
                            // by at most the section 9.6 limit.
                            val target = when (val resolution = finalPlan.resolve(order.size)) {
                                is TestPlan.Resolution.Refused ->
                                    throw IllegalStateException(resolution.message)
                                is TestPlan.Resolution.Ready -> resolution.count
                            }
                            val sampled = order.sampledBounded(
                                target = target,
                                max = TestPlan.MAX_LENGTH,
                                seed = finalPlan.seed
                            )
                            val selected = when (sampled) {
                                is BoundedSample.Refused ->
                                    throw IllegalStateException(sampled.message)
                                is BoundedSample.Selected -> sampled.order
                            }
                            drillSampleSeed = 0L
                            Logger.i(
                                "TESTVM",
                                "Bounded ${finalPlan.type}: requested=${finalPlan.count}, " +
                                    "target=$target, shown=${selected.size}, seed=${finalPlan.seed}"
                            )
                            order to selected
                        }
                    } else {
                        val shuffledOrder = order.shuffled(seed, shuffleQ)
                        if (isDrill) {
                            val sampleSeed = Random.nextLong()
                            drillSampleSeed = sampleSeed
                            Logger.i("TESTVM", "Drill: sampling $drillCount of ${shuffledOrder.size}, seed=$sampleSeed")
                            // Whole blocks are taken, so the result may overshoot
                            // the requested count when a passage does not fit.
                            shuffledOrder to shuffledOrder.sampled(drillCount, sampleSeed)
                        } else {
                            drillSampleSeed = 0L
                            shuffledOrder to shuffledOrder
                        }
                    }
                }
                optionShuffleSeed = if (shuffleO) seed else 0L
                // Saturating conversion: drillMinutes, the picker override and
                // durationMinutes all originate in files or raw text, and
                // minutes * 60 could overflow.
                val totalSeconds = com.mcqapp.domain.ExamTiming.sessionSeconds(
                    isDrill = isDrill,
                    drillMinutes = drillMinutes,
                    overrideMinutes = durationMinutes,
                    paperMinutes = (paper?.durationMinutes ?: 0)
                ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val practice = repository.practiceMode().first()
                val strict = repository.strictMode().first()
                val advance = repository.autoAdvance().first()
                if (practice && !strict) Logger.i("TESTVM", "Practice mode on: live feedback enabled")
                if (strict) Logger.i("TESTVM", "Strict exam mode on: aids hidden")
                // Ungraded are counted by paper or by category selection — one
                // aggregate query. Only the sampled sessions (a bounded plan,
                // a drill, a mistakes-only run) count by id, where the
                // session's questions are bounded by what the user asked for.
                val ungradedTotal = when {
                    effectivePlan != null || isDrill || mistakesSession ->
                        repository.countUngraded(drilled.idsInOrder())
                    categoryIds.isEmpty() -> repository.countUngradedForPaper(paperId)
                    else -> repository.countUngradedForCategories(scopeIds)
                }
                _state.update {
                    it.copy(
                        loading = false,
                        paper = paper,
                        order = drilled,
                        ungradedTotal = ungradedTotal,
                        totalSeconds = totalSeconds,
                        remainingSeconds = totalSeconds,
                        practiceMode = practice,
                        strictMode = strict,
                        autoAdvance = advance,
                        mistakesOnly = mistakesSession,
                        isDrill = isDrill,
                        drillRequested = if (isDrill) drillCount else 0,
                        testType = effectivePlan?.type?.name,
                        requestedCount = effectivePlan?.count ?: 0
                    )
                }
                // The resume rebuild replays the saved ids, so it reads the
                // full authored base — not the repeat-avoidance filter, which
                // describes the sample this launch would draw if it started
                // fresh.
                checkResumeOffer(baseOrder)
                startElapsedMs = SystemClock.elapsedRealtime()
                lastNavElapsedMs = startElapsedMs
                dwellCommitted.clear()
                _state.update { it.copy(dwellActive = !drilled.isEmpty) }
                resolveCurrent()
                startDwellTicker()
                // Not while an offer waits: the dialog demands an explicit
                // choice, and a countdown running behind it would reach zero
                // and auto-submit an empty attempt — clearing the snapshot
                // the dialog is about to offer the user back. Resume and
                // discard each start the timer when they resolve the offer.
                if (totalSeconds > 0 && _state.value.resumeOffer == null) startTimer()
            } catch (e: Exception) {
                // Without clearing `loading` the screen sat on "Loading…"
                // forever with no retry and no explanation.
                Logger.e("TESTVM", "Failed to load test session", e)
                _state.update { it.copy(loading = false, loadError = "Could not start the test: ${e.message}") }
            }
        }
    }

    /** Re-runs a failed load after the user asks for it. */
    fun retry() {
        _state.update { it.copy(loading = true, loadError = null) }
        load()
    }

    /**
     * The mistaken questions of this paper, restricted to the session's
     * expanded scope. The paper-level query predates scoped Test; a bounded
     * Mistakes plan must not sample a question outside the chosen scope.
     *
     * The scope filter runs in SQL by question category. A [SessionOrder]
     * holds question ids only, so intersecting it with category ids here would
     * drop every mistake. A ticked scope that expands to nothing is an empty
     * scope, not the whole paper.
     */
    private suspend fun mistakenOrderInScope(scopeIds: List<String>): SessionOrder =
        when {
            categoryIds.isEmpty() -> repository.getMistakenQuestionOrder(paperId)
            scopeIds.isEmpty() -> SessionOrder.empty()
            else -> repository.getMistakenQuestionOrder(paperId, scopeIds)
        }

    /**
     * The bounded plan the route describes, or null for a legacy deep link.
     *
     * Only a *missing* type is legacy — it is never inferred as
     * [TestType.RANDOM] (section 16 C5). A present but unknown type, a present
     * type with no length, and a type whose selector is not implemented yet are
     * all refused rather than silently running a different test. `RECENT_3` is
     * accepted only for the types that support a fresh sample; [TestPlan]
     * refuses it for every other type.
     */
    private fun buildPlan(scopeKey: String): TestPlan? {
        if (routeTestType.isBlank()) return null
        val type = TestType.fromStorage(routeTestType)
            ?: throw IllegalStateException("Unknown test type '$routeTestType'.")
        // The support check comes before the length check: `type=BALANCED&count=0`
        // must report the unimplemented selector, not fall back to a legacy run.
        if (!TestSetup.isStartableType(type)) {
            throw IllegalStateException("This test type is not available yet.")
        }
        if (routeRequestedCount <= 0) {
            throw IllegalStateException("A ${type.name.lowercase().replace('_', ' ')} test needs a length.")
        }
        val exclusion = if (routeExclusionMode.isBlank()) {
            ExclusionMode.NONE
        } else {
            runCatching { ExclusionMode.valueOf(routeExclusionMode) }.getOrNull()
                ?: throw IllegalStateException("Unknown exclusion rule '$routeExclusionMode'.")
        }
        // The variant is the custom type's choice (phase 3): a folded tag or
        // a difficulty name. A Tag/Authored route without one cannot start,
        // and Start on the setup sheet already refuses without it.
        val variant = when (type) {
            TestType.TAG, TestType.AUTHORED_DIFFICULTY, TestType.FIXED_PANEL ->
                routeVariant.ifBlank {
                    throw IllegalStateException("This test needs a choice.")
                }
            else -> null
        }
        return TestPlan(
            paperId = paperId,
            categoryIds = categoryIds,
            type = type,
            count = routeRequestedCount,
            minutes = durationMinutes.coerceAtLeast(0),
            seed = Random.nextLong(),
            samplingVersion = TestPlan.SAMPLING_VERSION,
            scopeKey = scopeKey,
            exclusionMode = exclusion,
            targetTopicIds = emptyList(),
            variant = variant
        )
    }

    /**
     * The nine section 12.1 fields for the attempt this session saves.
     *
     * A legacy session writes the legacy defaults (`testType = null`,
     * `requestedCount = 0`, `samplingVersion = 1`) but still records the
     * canonical scope key, so its scope is identifiable even though it is not
     * comparable. A bounded plan writes its own type, request and sampling
     * seed; `totalQuestions` stays the shown count.
     *
     * The seed is [sessionSeed], not the plan's original draw: resuming a
     * bounded session carries the saved snapshot's seed forward (see [resume]),
     * and the attempt must record the seed that produced the presented order.
     */
    private fun attemptMetadata(): AttemptMetadata {
        val plan = sessionPlan
        return AttemptMetadata(
            testType = plan?.type?.name,
            requestedCount = plan?.count ?: 0,
            scopeCategoryIds = categoryIds.joinToString(","),
            scopeKey = sessionScopeKey,
            samplingVersion = plan?.samplingVersion ?: 1,
            seed = if (plan != null) sessionSeed else 0L,
            exclusionMode = plan?.exclusionMode?.name ?: ExclusionMode.NONE.name,
            targetTopicIds = plan?.targetTopicIds?.joinToString(",") ?: "",
            compositionJson = plan?.compositionJson ?: "",
            variant = plan?.variant
        )
    }

    /**
     * The countdown, anchored on the wall clock rather than on tick counts.
     *
     * A `delay(1000)`-per-second decrement drifts: every iteration costs the
     * delay's scheduling slack plus the update itself, so a long paper finished
     * late by tens of seconds. [Countdown.remainingAt] recomputes what the
     * anchor says is left, so ticks that run late cannot steal or gift a
     * second against elapsed real time.
     */
    private fun startTimer() {
        timerJob?.cancel()
        val anchored = _state.value
        timerAnchorRemaining = anchored.remainingSeconds
        timerAnchorElapsed = SystemClock.elapsedRealtime()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val current = _state.value
                if (current.submitted || current.remainingSeconds <= 0) break
                val target = Countdown.remainingAt(
                    timerAnchorRemaining, timerAnchorElapsed, SystemClock.elapsedRealtime()
                )
                // A tick (and its update work) can run past a second; a
                // recomposition of the same value is no progress, so publish
                // only when the anchor says the clock moved.
                if (target >= current.remainingSeconds) continue
                val previous = current.remainingSeconds
                _state.update { it.copy(remainingSeconds = target) }
                val due = TimerWarnings.newlyDue(previous, target)
                    .filter { warnedThresholds.add(it) }
                if (due.isNotEmpty()) {
                    val text = due.joinToString("; ") { TimerWarnings.message(it) }
                    Logger.i("TESTVM", "Time warning: $text")
                    _state.update { it.copy(timeWarning = text) }
                }
                if (target <= 0) {
                    submit()
                    break
                }
            }
        }
    }

    fun dismissTimeWarning() {
        _state.update { it.copy(timeWarning = null) }
    }

    fun dismissSaveError() {
        _state.update { it.copy(saveError = null) }
    }

    /**
     * Ticks the per-question dwell clock.
     *
     * Deliberately independent of [startTimer]: that loop only runs for a
     * timed paper, but dwell is recorded for untimed papers too. Without a
     * clock of its own the readout could only ever change on navigation, so it
     * sat at 0:00 for the whole time the user was looking at a question.
     */
    private fun startDwellTicker() {
        dwellJob?.cancel()
        dwellJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                if (!_state.value.dwellActive) continue
                if (_state.value.submitted) break
                publishDwell(SystemClock.elapsedRealtime())
            }
        }
    }

    /**
     * Publishes banked dwell plus the time since the current question was
     * reached, without moving [lastNavElapsedMs] — the anchor stays where
     * navigation put it so ticks cannot lose a sub-second remainder.
     */
    /**
     * Publishes the live dwell of the question on screen. The tick updates
     * only [liveDwell] — the header's own flow — and never the per-question
     * [TestUiState.dwellSeconds] map, so a second of looking at a question does
     * not copy a map that grows with everything answered so far. The map is
     * written when a question is left ([flushDwell]) and when the session is
     * submitted, which is exactly when its reader needs it.
     */
    private fun publishDwell(now: Long) {
        val current = _state.value
        val question = current.currentQuestion ?: return
        val segment =
            if (current.dwellActive) (now - lastNavElapsedMs).coerceAtLeast(0L) / 1000 else 0L
        val total = (dwellCommitted[question.id] ?: 0L) + segment
        if (_liveDwell.value != total) _liveDwell.value = total
    }

    /**
     * The dwell currently known for [id]: the on-screen question's live total,
     * or its last banked total once it has been left.
     */
    private fun dwellOf(state: TestUiState, id: String): Long =
        if (id == state.currentQuestion?.id) _liveDwell.value else state.dwellSeconds[id] ?: 0L

    /** Banks time on the question being left, so the next visit starts from it. */
    private fun flushDwell() {
        val current = _state.value
        val question = current.currentQuestion ?: return
        val now = SystemClock.elapsedRealtime()
        val segment =
            if (current.dwellActive) (now - lastNavElapsedMs).coerceAtLeast(0L) / 1000 else 0L
        val banked = (dwellCommitted[question.id] ?: 0L) + segment
        dwellCommitted[question.id] = banked
        lastNavElapsedMs = now
        if (_liveDwell.value != banked) _liveDwell.value = banked
        _state.update { it.copy(dwellSeconds = it.dwellSeconds + (question.id to banked)) }
    }

    /**
     * Turns dwell accrual on or off. Pausing banks the active segment, so a
     * resume starts from zero rather than charging the pause duration.
     */
    private fun setDwellActive(active: Boolean) {
        val current = _state.value
        if (active == current.dwellActive) return
        if (active) {
            if (!dwellShouldRun(current)) return
            val now = SystemClock.elapsedRealtime()
            lastNavElapsedMs = now
            _state.update { it.copy(dwellActive = true) }
            publishDwell(now)
        } else {
            if (current.dwellActive) flushDwell()
            _state.update { it.copy(dwellActive = false) }
            publishDwell(SystemClock.elapsedRealtime())
        }
    }

    private fun dwellShouldRun(state: TestUiState): Boolean =
        clockMayRun(
            questionInView = questionInView,
            pausedByUser = state.dwellPausedByUser,
            answerable = !state.submitted && state.currentQuestion != null && !state.loading
        )

    /** Backgrounding pauses dwell but never turns a deliberate pause off. */
    fun onAppBackgrounded() {
        setDwellActive(false)
        // Banking the dwell alone does not reach disk: without this write a
        // kill after the last answer restored the older remainingSeconds and
        // dropped the unbanked dwell (consolidated_bugs.md D6).
        persistProgress()
    }

    /** The gate decides: a covered card or a deliberate pause stays paused. */
    fun onAppForegrounded() {
        setDwellActive(true)
    }

    /** Called when a dialog or question palette covers the question body. */
    fun setQuestionVisible(visible: Boolean) {
        questionInView = visible
        setDwellActive(visible)
    }

    /** The tap-to-pause control on the question clock. */
    fun toggleDwellPause() {
        val paused = !_state.value.dwellPausedByUser
        _state.update { it.copy(dwellPausedByUser = paused) }
        setDwellActive(!paused)
    }

    fun toggleOption(optionId: String) {
        // A submitted (or in-flight-submitted) attempt is scored already: an
        // edit now changes what the screen shows without changing either the
        // scored map or the persisted rows — the progress saver bails on
        // `submitted` — so what the user last saw would silently disagree
        // with the result.
        val currentAttempt = _state.value
        if (currentAttempt.submitted || currentAttempt.saving) return
        val question = currentAttempt.currentQuestion ?: return
        if (question.id in currentAttempt.revealed) return
        Logger.d("TESTVM", "toggleOption question=${question.id} option=$optionId " +
            "multi=${question.isMultiCorrect}")
        _state.update { state ->
            val current = state.selections[question.id] ?: emptySet()
            val updated = if (question.isMultiCorrect) {
                if (current.contains(optionId)) current - optionId else current + optionId
            } else {
                setOf(optionId)
            }
            state.copy(selections = state.selections + (question.id to updated))
        }
        persistProgress()
        val current = _state.value
        AutoAdvance.nextIndex(
            current.autoAdvance,
            question.isMultiCorrect,
            current.currentIndex,
            current.questionCount
        )?.let { goTo(it) }
    }

    fun revealCurrent() {
        val question = _state.value.currentQuestion ?: return
        Logger.d("TESTVM", "revealCurrent question=${question.id}")
        _state.update {
            it.copy(revealed = it.revealed + question.id)
        }
        persistProgress()
    }

    fun toggleFlag() {
        // Same reason as toggleOption: `persistProgress` bails on `submitted`,
        // so a flag flipped after the save started is screen-only and is
        // scored as if never set.
        val currentAttempt = _state.value
        if (currentAttempt.submitted || currentAttempt.saving) return
        val question = currentAttempt.currentQuestion ?: return
        _state.update {
            val flagged = it.flagged.toMutableSet()
            if (!flagged.add(question.id)) flagged.remove(question.id)
            it.copy(flagged = flagged)
        }
        persistProgress()
    }

    fun toggleBookmarkCurrent() {
        val question = _state.value.currentQuestion ?: return
        viewModelScope.launch {
            try {
                repository.toggleBookmark(question.id)
            } catch (e: Exception) {
                Logger.e("TESTVM", "toggleBookmark(${question.id}) failed", e)
            }
        }
    }

    fun goTo(index: Int) {
        // A submitted attempt's persistProgress already bailed, so moving on
        // would not even rewrite the header/dwell it flushes — refuse it: the
        // result view owns the screen once the save started.
        val currentAttempt = _state.value
        if (currentAttempt.submitted || currentAttempt.saving) return
        if (index in 0 until currentAttempt.order.size) {
            // The question being left is the one whose answer changed: its dwell
            // is banked by the flush below and written with the new header.
            val leaving = currentAttempt.order.idAtOrNull(currentAttempt.currentIndex)
            flushDwell()
            _state.update { it.copy(currentIndex = index) }
            // The header reads the live value, so it has to follow the
            // question rather than keep showing the one just left.
            publishDwell(SystemClock.elapsedRealtime())
            resolveCurrent()
            persistProgress(questionId = leaving)
        }
    }

    /**
     * Resolves the question on screen from its order. Sessions hold an order
     * and nothing else — a full paper of 169k questions cannot fit in memory as
     * content — so the body, its options and its passage are read when the
     * index changes.
     */
    private fun resolveCurrent() {
        val state = _state.value
        val currentId = state.order.idAtOrNull(state.currentIndex)
        if (currentId == null) {
            prefetched = null
            _state.update { it.copy(current = null, currentPassage = null) }
            return
        }
        // The read-ahead of the previous card already holds this one: publish
        // it without a round-trip, so tapping Next is a frame, not a query.
        val ready = prefetched?.takeIf { it.first == currentId }?.second
        if (ready != null) {
            prefetched = null
            publishCurrent(ready)
            prefetchNext()
            return
        }
        viewModelScope.launch {
            val loaded = loadCurrent(currentId)
            // A navigation that raced this read wins: only publish what is
            // still on screen.
            if (_state.value.order.idAtOrNull(_state.value.currentIndex) != currentId) return@launch
            publishCurrent(loaded)
            prefetchNext()
        }
    }

    /** Resolves one card's content (and its passage) straight from the DB. */
    private suspend fun loadCurrent(id: String): LoadedCard? {
        val question = repository.getQuestion(id)?.let { applyOptionShuffle(it) } ?: return null
        val passage = question.passageId?.let { pid ->
            repository.getPassagesByIds(listOf(pid)).firstOrNull()
        }
        return LoadedCard(question, passage)
    }

    private fun publishCurrent(loaded: LoadedCard?) {
        _state.update { it.copy(current = loaded?.question, currentPassage = loaded?.passage) }
        publishDwell(SystemClock.elapsedRealtime())
    }

    /**
     * Anki's reviewer read-ahead: while a card is shown, resolve the next one
     * so it is ready the moment Next is tapped. Only ever one card ahead, and
     * dropped if the reader moves elsewhere before it lands.
     */
    private fun prefetchNext() {
        val state = _state.value
        val nextId = state.order.idAtOrNull(state.currentIndex + 1) ?: run {
            prefetched = null
            return
        }
        if (prefetched?.first == nextId) return
        prefetched = null
        viewModelScope.launch {
            val loaded = loadCurrent(nextId)
            val now = _state.value
            if (now.order.idAtOrNull(now.currentIndex + 1) == nextId) {
                prefetched = loaded?.let { nextId to it }
            }
        }
    }

    /** Per-question, seed-stable option order (shared with Study). */
    private fun applyOptionShuffle(question: Question): Question =
        Shuffle.optionsFor(question, optionShuffleSeed)

    fun next() = goTo(_state.value.currentIndex + 1)
    fun previous() = goTo(_state.value.currentIndex - 1)

    /**
     * Opens the pre-submit review sheet. Rows page over the session order:
     * each page resolves just its slice's questions, so a huge session never
     * holds every row.
     */
    fun openAnswerReview() {
        if (_state.value.reviewOpen) return
        val state = _state.value
        reviewPaging.value = Pager(
            PagingConfig(pageSize = REVIEW_PAGE, initialLoadSize = REVIEW_PAGE, enablePlaceholders = false)
        ) {
            SessionReviewPagingSource(
                state.order, state.selections, state.flagged, REVIEW_PAGE
            ) { ids ->
                repository.questionsOf(repository.db().questionDao().getByIdsChunked(ids))
            }
        }.flow.cachedIn(viewModelScope)
        _state.update { it.copy(reviewOpen = true) }
    }

    fun dismissAnswerReview() {
        _state.update { it.copy(reviewOpen = false) }
        reviewPaging.value = flowOf(PagingData.empty())
    }

    fun submit() {
        val snapshot = _state.value
        if (snapshot.submitted || snapshot.saving || snapshot.questionCount == 0) return
        Logger.i("TESTVM", "submit() called: answered=${snapshot.answeredCount}/${snapshot.questionCount}, " +
            "remaining=${snapshot.remainingSeconds}s")
        timerJob?.cancel()
        dwellJob?.cancel()
        setDwellActive(false)
        flushDwell()
        // Read after the flush: the state read before it predates the last
        // question's dwell, which then saved as zero.
        val current = _state.value
        val durationSeconds = if (current.totalSeconds > 0) {
            (current.totalSeconds - current.remainingSeconds).toLong()
        } else {
            ((SystemClock.elapsedRealtime() - startElapsedMs) / 1000)
        }
        _state.update {
            it.copy(
                submitted = true, saving = true, saveError = null,
                saveDone = 0, saveTotal = it.questionCount
            )
        }
        viewModelScope.launch {
            // Save first, clear second: the resume snapshot is the only copy
            // of this session, so dropping it before the attempt is durable
            // would lose the whole exam on a storage error or process death.
            val outcome = com.mcqapp.domain.submitAttempt(
                save = {
                    repository.saveAttemptStreamed(
                        paperId = paperId,
                        paperTitle = current.paper?.title ?: "Test",
                        order = current.order,
                        selections = current.selections,
                        negativeMarking = current.paper?.negativeMarking ?: 0.0,
                        durationSeconds = durationSeconds,
                        finishedAt = System.currentTimeMillis(),
                        dwellSeconds = current.dwellSeconds,
                        metadata = attemptMetadata(),
                        onProgress = { stored, total ->
                            _state.update { it.copy(saveDone = stored, saveTotal = total) }
                        }
                    )
                },
                clearSnapshot = { repository.clearTestProgress() },
                onClearFailure = { Logger.w("TESTVM", "Failed to clear test progress: ${it.message}") }
            )
            when (outcome) {
                is com.mcqapp.domain.SubmissionOutcome.Saved -> {
                    Logger.i("TESTVM", "Attempt saved: attemptId=${outcome.value}")
                    _state.update { it.copy(saving = false, attemptId = outcome.value) }
                }
                // Keep the snapshot and unlock the screen so the user can retry.
                is com.mcqapp.domain.SubmissionOutcome.Failed -> {
                    Logger.e("TESTVM", "Failed to save attempt: ${outcome.message}")
                    _state.update {
                        it.copy(
                            saving = false,
                            submitted = false,
                            saveError = "Could not save your result: ${outcome.message}"
                        )
                    }
                    // submit() cancelled both timers up front; without this the
                    // countdown froze and auto-submit stayed dead while the
                    // user kept answering, and the recorded duration stopped
                    // with it (consolidated_bugs.md F3).
                    startDwellTicker()
                    setDwellActive(true)
                    val after = _state.value
                    if (after.totalSeconds > 0 && after.remainingSeconds > 0) startTimer()
                }
            }
        }
    }

    /**
     * Offers to resume a snapshot left by a killed session of this exact
     * paper + category selection. Only offered when real progress exists
     * and the paper still contains overlapping questions.
     */
    private suspend fun checkResumeOffer(base: SessionOrder) {
        val document = repository.loadTestSnapshot()
        if (document == null || document.paperId != paperId) {
            // No session of this paper is saved — the one slot is another
            // paper's, or empty — so its answers can only be the leftovers of
            // an abandoned one, and merging them into this session would show
            // answers never given here.
            //
            // A document for this paper under *another* scope is left alone:
            // opening a screen must not destroy a saved session, and an answer
            // row is keyed by question, not by scope. See TestProgressStore.
            repository.clearStaleTestAnswers(paperId)
            return
        }
        // A legacy snapshot on a bounded route is offered as a labelled
        // Legacy/Short session (section 7.6): it has no type or canonical key
        // to compare, but the paper, ticked scope and mistakes flag still
        // identify it. Accepting it must not drop its answers — they belong to
        // the session being resumed, not to an abandoned one.
        val legacyMatch = sessionPlan != null && document.isLegacy &&
            document.categoryIds.toSet() == categoryIds.toSet() &&
            document.mistakesOnly == isMistakesSession
        if (!legacyMatch && !document.matches(
                paperId = paperId,
                categoryIds = categoryIds,
                isDrill = sessionPlan == null && drillCount > 0,
                mistakesOnly = isMistakesSession,
                drillRequested = if (sessionPlan == null) drillCount else 0,
                testType = sessionPlan?.type?.name,
                requestedCount = sessionPlan?.count ?: 0,
                exclusionMode = sessionPlan?.exclusionMode?.name ?: ExclusionMode.NONE.name,
                targetTopicIds = sessionPlan?.targetTopicIds.orEmpty(),
                // A legacy session keeps the raw tick-set identity so a
                // snapshot written before scopeKey existed can still resume;
                // a bounded plan compares the canonical expanded key.
                scopeKey = sessionPlan?.scopeKey ?: "",
                variant = sessionPlan?.variant
            )
        ) {
            // The slot holds a different session of this paper. Opening the
            // screen must not destroy it, but a bounded session that starts
            // here takes the slot on its first save; see
            // [dropStaleAnswersOnSave].
            if (sessionPlan != null) dropStaleAnswersOnSave = true
            return
        }
        // A document written before the answers moved to rows still carries
        // them inline; writing them as rows now is what keeps the next answer
        // from dropping everything answered before it. A row-based document has
        // nothing to adopt, so this costs a check.
        repository.adoptTestAnswers(document)
        val saved = repository.resumeOffer(paperId) ?: document
        if (!saved.hasAnswers && saved.currentIndex <= 0) {
            repository.clearTestProgress()
            return
        }
        // A snapshot whose post-submit clear failed still looks resumable, but
        // the attempt it belongs to is already stored: resuming and submitting
        // would write a second attempt and re-apply the per-question stats.
        // An attempt finished after this document was written means the
        // session already ended, so the stale snapshot is cleared instead of
        // offered (consolidated_bugs.md D8).
        if (saved.savedAt > 0) {
            val latestFinishedAt = repository.latestAttemptFinishedAt(paperId)
            if (latestFinishedAt != null && latestFinishedAt >= saved.savedAt) {
                repository.clearTestProgress()
                return
            }
        }
        // Rebuilding a seeded order replays the whole-paper shuffle, so it runs
        // off the main thread like the load-time shuffle does.
        val ordered = withContext(Dispatchers.Default) { TestSnapshot.orderOf(base, saved) }
            ?: run {
                repository.clearTestProgress()
                return
            }
        if (ordered.isEmpty) {
            repository.clearTestProgress()
            return
        }
        resumeOrder = ordered
        Logger.i("TESTVM", "Resume available: ${saved.selections.size} answered, " +
            "index=${saved.currentIndex}, remaining=${saved.remainingSeconds}s, " +
            "legacyMatch=$legacyMatch, legacy=${saved.isLegacy}")
        _state.update {
            it.copy(
                resumeOffer = saved,
                resumeOfferCount = ordered.size,
                // Any pre-bounded snapshot is labelled legacy, whether it was
                // accepted by the legacy route or by [legacyMatch].
                resumeOfferLegacy = saved.isLegacy
            )
        }
    }

    fun resume() {
        // The dialog can outlive the session it offered: once an attempt is
        // submitted, submit() is clearing the very rows this snapshot came
        // from, so resuming here would rebuild the screen mid-save.
        val current = _state.value
        if (current.submitted || current.saving) return
        val saved = current.resumeOffer ?: return
        val ordered = resumeOrder
        if (ordered.isEmpty) {
            discardResume()
            return
        }
        // A legacy snapshot resumes as itself, even when it was offered on a
        // bounded route: stamping the fresh plan's type, count and scope over
        // an order the plan never sampled would label it a bounded test it is
        // not (section 7.6). The next save then writes the legacy metadata.
        if (saved.isLegacy) sessionPlan = null
        // A resumed focus session keeps the targets and composition it was
        // saved with; the route does not carry them.
        if (!saved.isLegacy) {
            sessionPlan = sessionPlan?.copy(
                targetTopicIds = saved.targetTopicIds,
                compositionJson = saved.compositionJson,
                variant = saved.variant
            )
        }
        // Carry the saved seeds forward so later autosaves rewrite the same
        // tiny snapshot instead of falling back to an id list.
        saved.questionSeed?.let { sessionSeed = it }
        if (saved.isDrill) {
            saved.drillSeed?.let { drillSampleSeed = it }
        } else {
            // A non-drill snapshot must not inherit a fresh drill's seed, or
            // the persisted header pairs a seed with a count never applied
            // (consolidated_bugs.md D1).
            drillSampleSeed = 0L
        }
        // A seed-less snapshot has no seed to replay; keep its id list (and a
        // null seed) so the next autosave does not stamp the fresh random seed
        // over the order the user resumed into.
        legacyResumedIds = if (saved.questionSeed == null) saved.questionIds else null
        questionShuffleEnabled = saved.questionShuffle
        optionShuffleSeed = saved.optionShuffleSeed
        // The saved answers may name questions outside the rebuilt order (the
        // library changed since the snapshot was written): counting them makes
        // the header read "5 of 3 answered" and pushes the submit summary
        // negative, so they are dropped here (consolidated_bugs.md D5).
        val orderIds = ordered.idsInOrder().toHashSet()
        val savedSelections = saved.selections.filterKeys { it in orderIds }
        val savedRevealed = saved.revealed.filterTo(LinkedHashSet()) { it in orderIds }
        val savedFlagged = saved.flagged.filterTo(LinkedHashSet()) { it in orderIds }
        val savedDwell = saved.dwellSeconds.filterKeys { it in orderIds }
        Logger.i("TESTVM", "Resuming saved progress: index=${saved.currentIndex}, " +
            "answered=${savedSelections.size}")
        _state.update {
            it.copy(
                order = ordered,
                selections = savedSelections.mapValues { e -> e.value.toSet() },
                revealed = savedRevealed,
                flagged = savedFlagged,
                currentIndex = saved.currentIndex.coerceIn(0, ordered.size - 1),
                remainingSeconds = saved.remainingSeconds,
                totalSeconds = saved.totalSeconds,
                dwellSeconds = savedDwell,
                // The session shape travels with the snapshot: resume() kept the
                // new session's isDrill/mistakesOnly, so the header and the next
                // autosave described a session the saved order was never built
                // for (consolidated_bugs.md D1/D4).
                isDrill = saved.isDrill,
                mistakesOnly = saved.mistakesOnly,
                drillRequested = saved.drillRequested,
                // A legacy snapshot resumed on a bounded route keeps its own
                // (absent) type: the header must not call it a Random test.
                testType = sessionPlan?.type?.name,
                requestedCount = sessionPlan?.count ?: 0,
                timeWarning = null,
                resumeOffer = null
            )
        }
        warnedThresholds.clear()
        // Untimed attempts bank their elapsed seconds on every autosave, so a
        // kill does not shrink the recorded duration (consolidated_bugs.md D7).
        startElapsedMs = SystemClock.elapsedRealtime() - saved.elapsedSeconds * 1000L
        lastNavElapsedMs = SystemClock.elapsedRealtime()
        // The snapshot's map becomes the new baseline, so time already spent
        // on a question is not counted again from this visit onwards.
        dwellCommitted.clear()
        dwellCommitted.putAll(savedDwell)
        // The read-ahead holds the previous session's card, resolved with the
        // pre-resume option shuffle; resolveCurrent would publish it as the
        // first resumed card (consolidated_bugs.md D9).
        prefetched = null
        if (sessionPlan != null || saved.isDrill || saved.mistakesOnly || saved.isLegacy) {
            // ungradedTotal was computed for the freshly loaded order; the
            // resumed order is the saved cut or the frozen bounded sample, so
            // the count follows it.
            viewModelScope.launch {
                try {
                    val ungraded = repository.countUngraded(ordered.idsInOrder())
                    _state.update { it.copy(ungradedTotal = ungraded) }
                } catch (e: Exception) {
                    Logger.w("TESTVM", "ungradedTotal recount failed: ${e.message}")
                }
            }
        }
        startDwellTicker()
        resolveCurrent()
        if (saved.totalSeconds > 0 && saved.remainingSeconds > 0) startTimer()
    }

    fun discardResume() {
        Logger.i("TESTVM", "Discarding saved progress, starting fresh")
        viewModelScope.launch { repository.clearTestProgress() }
        resumeOrder = SessionOrder.empty()
        legacyResumedIds = null
        _state.update { it.copy(resumeOffer = null) }
        // Starting the fresh countdown is the choice's other half: load()
        // withheld it while the offer was up (see the timer's start there),
        // so whichever way the dialog resolves, it must be started here.
        val current = _state.value
        if (!current.submitted && current.totalSeconds > 0 && current.remainingSeconds > 0) startTimer()
    }

    /**
     * Best-effort snapshot after every discrete change (never on timer ticks).
     *
     * The document holds the session's header — scope, seeds, index, timers — and
     * is a few hundred bytes. The answer that changed is one `test_progress` row:
     * [questionId] names it when the change is about a question just left, the
     * one on screen otherwise. Serialising every answer so far, which is what
     * this used to do, made each tap cost the whole sitting.
     */
    private fun persistProgress(questionId: String? = null) {
        val current = _state.value
        if (current.loading || current.questionCount == 0 || current.submitted) return
        val snapshot = TestSnapshot(
            paperId = paperId,
            categoryIds = categoryIds,
            // A resumed legacy snapshot keeps its id list and a null seed until
            // the session ends, so a second resume replays the same order.
            questionIds = legacyResumedIds ?: emptyList(),
            currentIndex = current.currentIndex,
            remainingSeconds = current.remainingSeconds,
            totalSeconds = current.totalSeconds,
            questionSeed = if (legacyResumedIds != null) null else sessionSeed,
            questionShuffle = questionShuffleEnabled,
            optionShuffleSeed = optionShuffleSeed,
            drillSeed = if (drillSampleSeed != 0L) drillSampleSeed else null,
            drillRequested = current.drillRequested,
            isDrill = current.isDrill,
            mistakesOnly = current.mistakesOnly,
            // A bounded plan freezes its sample so a resume replays exactly
            // these ids and never re-ranks; a legacy session keeps the seed
            // replay (section 12.2). The key is written only for a plan, so a
            // legacy snapshot still compares by its raw tick set.
            selectedQuestionIds = if (sessionPlan != null) current.order.idsInOrder() else emptyList(),
            scopeKey = sessionPlan?.scopeKey ?: "",
            testType = sessionPlan?.type?.name,
            requestedCount = sessionPlan?.count ?: 0,
            samplingVersion = sessionPlan?.samplingVersion ?: 1,
            exclusionMode = sessionPlan?.exclusionMode?.name ?: ExclusionMode.NONE.name,
            targetTopicIds = sessionPlan?.targetTopicIds.orEmpty(),
            compositionJson = sessionPlan?.compositionJson.orEmpty(),
            variant = sessionPlan?.variant,
            // Untimed sessions bank their elapsed time so a resume neither
            // restarts the clock nor counts the dead interval (D7).
            elapsedSeconds = if (current.totalSeconds > 0) {
                0
            } else {
                ((SystemClock.elapsedRealtime() - startElapsedMs) / 1000).toInt().coerceAtLeast(0)
            },
            savedAt = System.currentTimeMillis()
        )
        val id = questionId ?: current.order.idAtOrNull(current.currentIndex)
        val takeOver = dropStaleAnswersOnSave
        val changed = if (takeOver) {
            // A takeover deletes every row of this paper, so it must rewrite
            // every question with progress — not just the one that changed.
            // Two saves can overlap (answer, then auto-advance) and both read
            // the flag; a delta-only rewrite would let the second delete the
            // first's row and lose the answer.
            current.order.idsInOrder().mapNotNull { qid ->
                val selection = current.selections[qid].orEmpty()
                val revealed = qid in current.revealed
                val flagged = qid in current.flagged
                if (selection.isEmpty() && !revealed && !flagged &&
                    dwellOf(current, qid) <= 0
                ) {
                    null
                } else {
                    TestAnswer(
                        questionId = qid,
                        selection = selection,
                        revealed = revealed,
                        flagged = flagged,
                        dwellSeconds = dwellOf(current, qid)
                    )
                }
            }
        } else if (id == null) {
            emptyList()
        } else {
            listOf(
                TestAnswer(
                    questionId = id,
                    selection = current.selections[id] ?: emptySet(),
                    revealed = id in current.revealed,
                    flagged = id in current.flagged,
                    dwellSeconds = dwellOf(current, id)
                )
            )
        }
        viewModelScope.launch {
            try {
                repository.saveTestProgress(snapshot, changed, takeOver)
                // Clear only after the save landed: a failed save keeps the
                // takeover pending, so the next autosave still replaces the
                // stale rows instead of appending beside them.
                if (takeOver) dropStaleAnswersOnSave = false
            } catch (e: Exception) {
                // A failed takeover may have deleted the rows before it
                // failed; keep the flag armed so the next save replaces the
                // full set. A takeover always rewrites every answered row, so
                // re-arming is safe.
                if (takeOver) dropStaleAnswersOnSave = true
                Logger.w("TESTVM", "persistProgress failed: ${e.message}")
            }
        }
    }
}
