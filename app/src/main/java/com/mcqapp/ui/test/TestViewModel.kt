package com.mcqapp.ui.test

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.domain.AnswerReview
import com.mcqapp.domain.AutoAdvance
import com.mcqapp.domain.Dwell
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.domain.Shuffle
import com.mcqapp.domain.TestSnapshot
import com.mcqapp.domain.TimerWarnings
import com.mcqapp.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

data class TestUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val paper: Paper? = null,
    /**
     * The session's ordered questions as refs — ids, passage membership and
     * preview text. Content is resolved per question on navigation, so this
     * is a few strings per question rather than the full content.
     */
    val refs: List<QuestionRef> = emptyList(),
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
    val saveError: String? = null,
    val attemptId: Long? = null,
    val practiceMode: Boolean = false,
    val strictMode: Boolean = false,
    val autoAdvance: Boolean = false,
    val mistakesOnly: Boolean = false,
    val isDrill: Boolean = false,
    /** The requested drill count; a passage block may push [questions] past it. */
    val drillRequested: Int = 0,
    val resumeOffer: TestSnapshot? = null,
    val dwellSeconds: Map<String, Long> = emptyMap(),
    /** Whether the per-question clock currently accrues time. */
    val dwellActive: Boolean = false,
    /** Whether the learner paused the per-question clock by tapping it. */
    val dwellPausedByUser: Boolean = false,
    val timeWarning: String? = null,
    val bookmarked: Set<String> = emptySet(),
    /** Rows for the pre-submit review sheet, built per batch on open. */
    val reviewRows: List<AnswerReview.Row> = emptyList(),
    val reviewOpen: Boolean = false
) {
    /** The question on screen. */
    val currentQuestion: Question? get() = current

    val answeredCount: Int get() = selections.count { it.value.isNotEmpty() }
    val isCurrentRevealed: Boolean get() = current?.id in revealed
    val questionCount: Int get() = refs.size
}

class TestViewModel(
    application: Application,
    private val paperId: String,
    private val categoryIds: List<String>,
    private val mistakesOnly: Boolean = false,
    private val drillCount: Int = 0,
    private val drillMinutes: Int = 0
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(TestUiState())
    val state: StateFlow<TestUiState> = _state.asStateFlow()

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
    /** Seed for the per-question option shuffle; 0 when options stay put. */
    private var optionShuffleSeed: Long = 0L
    /** The session's shuffle/drill seeds, so the resume snapshot can
     *  reproduce the presented order from a few bytes. */
    private var questionShuffleEnabled: Boolean = false
    private var sessionSeed: Long = 0L
    private var drillSampleSeed: Long = 0L
    /** The order a pending resume would present; rebuilt while the base refs
     *  are still loaded, so the unshuffled list is not retained. */
    private var resumeOrder: List<QuestionRef> = emptyList()

    private companion object {
        /** Questions resolved per batch while building the review sheet. */
        const val REVIEW_BATCH = 500
    }
    private var startTimestamp: Long = 0
    private var lastNavMillis: Long = 0

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
                Logger.d("TESTVM", "Loaded paper '${paper?.title}', categories=${paper?.categories?.size ?: 0}")
                // Scheduling-and-preview columns only: the session orders ids,
                // never content (see loadQuestionRefs).
                val refs = if (mistakesOnly) {
                    repository.getMistakenQuestionRefs(paperId)
                } else if (categoryIds.isEmpty()) {
                    repository.getQuestionRefsForPaper(paperId)
                } else {
                    repository.getQuestionRefsForCategories(categoryIds)
                }
                Logger.i("TESTVM", "Loaded ${refs.size} questions, duration=${paper?.durationMinutes}min" +
                    if (mistakesOnly) " (mistakes only)" else "")
                val shuffleQ = repository.shuffleQuestions().first()
                val shuffleO = repository.shuffleOptions().first()
                val seed = Random.nextLong()
                questionShuffleEnabled = shuffleQ
                sessionSeed = seed
                if (shuffleQ || shuffleO) {
                    Logger.i("TESTVM", "Shuffled attempt: seed=$seed, " +
                        "questions=$shuffleQ, options=$shuffleO")
                }
                val isDrill = drillCount > 0
                // Block-aware: passage members move as one block, so a
                // comprehension set is never split by the shuffle or the cut.
                val shuffled = com.mcqapp.domain.PassageBlocks
                    .shuffleAttempt(refs, seed, shuffleQ) { it.passageId }
                val drilled = if (isDrill) {
                    val sampleSeed = Random.nextLong()
                    drillSampleSeed = sampleSeed
                    Logger.i("TESTVM", "Drill: sampling $drillCount of ${shuffled.size}, seed=$sampleSeed")
                    // Whole blocks are taken, so the result may overshoot the
                    // requested count when a passage does not fit the cut.
                    com.mcqapp.domain.PassageBlocks
                        .sample(shuffled, drillCount, sampleSeed) { it.passageId }
                } else {
                    drillSampleSeed = 0L
                    shuffled
                }
                optionShuffleSeed = if (shuffleO) seed else 0L
                // Saturating conversion: drillMinutes and durationMinutes both
                // originate in files, and minutes * 60 could overflow.
                val totalSeconds = com.mcqapp.domain.ExamTiming.secondsFrom(
                    if (isDrill && drillMinutes > 0) drillMinutes
                    else (paper?.durationMinutes ?: 0)
                ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val practice = repository.practiceMode().first()
                val strict = repository.strictMode().first()
                val advance = repository.autoAdvance().first()
                if (practice && !strict) Logger.i("TESTVM", "Practice mode on: live feedback enabled")
                if (strict) Logger.i("TESTVM", "Strict exam mode on: aids hidden")
                // The session orders refs; the question on screen is resolved
                // by id when it is reached, so a 169k-question paper never
                // holds every question's content at once.
                val ungradedTotal = repository.countUngraded(drilled.map { it.id })
                _state.update {
                    it.copy(
                        loading = false,
                        paper = paper,
                        refs = drilled,
                        ungradedTotal = ungradedTotal,
                        totalSeconds = totalSeconds,
                        remainingSeconds = totalSeconds,
                        practiceMode = practice,
                        strictMode = strict,
                        autoAdvance = advance,
                        mistakesOnly = mistakesOnly,
                        isDrill = isDrill,
                        drillRequested = if (isDrill) drillCount else 0
                    )
                }
                checkResumeOffer(refs)
                startTimestamp = System.currentTimeMillis()
                lastNavMillis = startTimestamp
                dwellCommitted.clear()
                _state.update { it.copy(dwellActive = drilled.isNotEmpty()) }
                resolveCurrent()
                startDwellTicker()
                if (totalSeconds > 0) startTimer()
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

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val current = _state.value
                if (current.submitted || current.remainingSeconds <= 0) break
                val previous = current.remainingSeconds
                _state.update { it.copy(remainingSeconds = it.remainingSeconds - 1) }
                val now = _state.value.remainingSeconds
                val due = TimerWarnings.newlyDue(previous, now)
                    .filter { warnedThresholds.add(it) }
                if (due.isNotEmpty()) {
                    val text = due.joinToString("; ") { TimerWarnings.message(it) }
                    Logger.i("TESTVM", "Time warning: $text")
                    _state.update { it.copy(timeWarning = text) }
                }
                if (now <= 0) {
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
                publishDwell(System.currentTimeMillis())
            }
        }
    }

    /**
     * Publishes banked dwell plus the time since the current question was
     * reached, without moving [lastNavMillis] — the anchor stays where
     * navigation put it so ticks cannot lose a sub-second remainder.
     */
    private fun publishDwell(now: Long) {
        val current = _state.value
        val question = current.currentQuestion ?: return
        val segment =
            if (current.dwellActive) (now - lastNavMillis).coerceAtLeast(0L) / 1000 else 0L
        val total = (dwellCommitted[question.id] ?: 0L) + segment
        if (_liveDwell.value != total) _liveDwell.value = total
        if (current.dwellSeconds[question.id] == total) return
        _state.update {
            it.copy(dwellSeconds = it.dwellSeconds + (question.id to total))
        }
    }

    /** Banks time on the question being left, so the next visit starts from it. */
    private fun flushDwell() {
        val current = _state.value
        val question = current.currentQuestion ?: return
        val now = System.currentTimeMillis()
        val segment =
            if (current.dwellActive) (now - lastNavMillis).coerceAtLeast(0L) / 1000 else 0L
        val banked = (dwellCommitted[question.id] ?: 0L) + segment
        dwellCommitted[question.id] = banked
        lastNavMillis = now
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
            val now = System.currentTimeMillis()
            lastNavMillis = now
            _state.update { it.copy(dwellActive = true) }
            publishDwell(now)
        } else {
            if (current.dwellActive) flushDwell()
            _state.update { it.copy(dwellActive = false) }
            publishDwell(System.currentTimeMillis())
        }
    }

    private fun dwellShouldRun(state: TestUiState): Boolean =
        !state.submitted && state.currentQuestion != null && !state.loading

    /** Backgrounding pauses dwell but never turns a deliberate pause off. */
    fun onAppBackgrounded() {
        setDwellActive(false)
    }

    fun onAppForegrounded() {
        if (!_state.value.dwellPausedByUser) setDwellActive(true)
    }

    /** Called when a dialog or question palette covers the question body. */
    fun setQuestionVisible(visible: Boolean) {
        if (visible && !_state.value.dwellPausedByUser) {
            setDwellActive(true)
        } else {
            setDwellActive(false)
        }
    }

    /** The tap-to-pause control on the question clock. */
    fun toggleDwellPause() {
        val paused = !_state.value.dwellPausedByUser
        _state.update { it.copy(dwellPausedByUser = paused) }
        setDwellActive(!paused)
    }

    fun toggleOption(optionId: String) {
        val question = _state.value.currentQuestion ?: return
        if (question.id in _state.value.revealed) return
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
        val question = _state.value.currentQuestion ?: return
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
        if (index in _state.value.refs.indices) {
            flushDwell()
            _state.update { it.copy(currentIndex = index) }
            // The header reads the live value, so it has to follow the
            // question rather than keep showing the one just left.
            publishDwell(System.currentTimeMillis())
            resolveCurrent()
            persistProgress()
        }
    }

    /**
     * Resolves the question on screen from its ref. Sessions hold refs only —
     * a full paper of 169k questions cannot fit in memory as content — so the
     * body, its options and its passage are read when the index changes.
     */
    private fun resolveCurrent() {
        val state = _state.value
        val ref = state.refs.getOrNull(state.currentIndex)
        if (ref == null) {
            _state.update { it.copy(current = null, currentPassage = null) }
            return
        }
        viewModelScope.launch {
            val loaded = repository.getQuestion(ref.id)?.let { applyOptionShuffle(it) }
            val passage = loaded?.passageId?.let { pid ->
                repository.getPassagesByIds(listOf(pid)).firstOrNull()
            }
            // A navigation that raced this read wins: only publish what is
            // still on screen.
            if (_state.value.refs.getOrNull(_state.value.currentIndex)?.id != ref.id) return@launch
            _state.update { it.copy(current = loaded, currentPassage = passage) }
            publishDwell(System.currentTimeMillis())
        }
    }

    /** Per-question, seed-stable option order (the old one-stream shuffle). */
    private fun applyOptionShuffle(question: Question): Question {
        val seed = optionShuffleSeed
        if (seed == 0L) return question
        return question.copy(
            options = question.options.shuffled(Random(seed xor question.id.hashCode().toLong()))
        )
    }

    fun next() = goTo(_state.value.currentIndex + 1)
    fun previous() = goTo(_state.value.currentIndex - 1)

    /**
     * Opens the pre-submit review sheet. The rows are built in question
     * batches, never from one full-paper load: each batch's questions are
     * dropped as soon as its rows exist.
     */
    fun openAnswerReview() {
        if (_state.value.reviewOpen) return
        _state.update { it.copy(reviewOpen = true, reviewRows = emptyList()) }
        viewModelScope.launch {
            val state = _state.value
            val rows = mutableListOf<AnswerReview.Row>()
            var index = 0
            for (batch in state.refs.map { it.id }.chunked(REVIEW_BATCH)) {
                val entities = repository.db().questionDao().getByIdsChunked(batch)
                    .associateBy { it.id }
                val ordered = batch.mapNotNull { entities[it] }
                rows += AnswerReview.rows(
                    questions = repository.questionsOf(ordered),
                    selections = state.selections,
                    flagged = state.flagged
                ).map { it.copy(number = index + it.number) }
                index += ordered.size
                _state.update { it.copy(reviewRows = rows.toList()) }
            }
        }
    }

    fun dismissAnswerReview() {
        _state.update { it.copy(reviewOpen = false) }
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
            ((System.currentTimeMillis() - startTimestamp) / 1000)
        }
        _state.update { it.copy(submitted = true, saving = true, saveError = null) }
        viewModelScope.launch {
            // Save first, clear second: the resume snapshot is the only copy
            // of this session, so dropping it before the attempt is durable
            // would lose the whole exam on a storage error or process death.
            val outcome = com.mcqapp.domain.submitAttempt(
                save = {
                    repository.saveAttemptStreamed(
                        paperId = paperId,
                        paperTitle = current.paper?.title ?: "Test",
                        questionIdsInOrder = current.refs.map { it.id },
                        selections = current.selections,
                        negativeMarking = current.paper?.negativeMarking ?: 0.0,
                        durationSeconds = durationSeconds,
                        finishedAt = System.currentTimeMillis(),
                        dwellSeconds = current.dwellSeconds
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
                }
            }
        }
    }

    /**
     * Offers to resume a snapshot left by a killed session of this exact
     * paper + category selection. Only offered when real progress exists
     * and the paper still contains overlapping questions.
     */
    private suspend fun checkResumeOffer(base: List<QuestionRef>) {
        val raw = repository.loadTestProgress() ?: return
        val saved = TestSnapshot.fromJson(raw) ?: run {
            repository.clearTestProgress()
            return
        }
        if (!saved.matches(paperId, categoryIds)) return
        if (saved.selections.isEmpty() && saved.currentIndex <= 0) {
            repository.clearTestProgress()
            return
        }
        val ordered = TestSnapshot.orderRefs(base, _state.value.refs, saved)
        if (ordered.isEmpty()) {
            repository.clearTestProgress()
            return
        }
        resumeOrder = ordered
        Logger.i("TESTVM", "Resume available: ${saved.selections.size} answered, " +
            "index=${saved.currentIndex}, remaining=${saved.remainingSeconds}s")
        _state.update { it.copy(resumeOffer = saved) }
    }

    fun resume() {
        val saved = _state.value.resumeOffer ?: return
        val ordered = resumeOrder
        if (ordered.isEmpty()) {
            discardResume()
            return
        }
        // Carry the saved seeds forward so later autosaves rewrite the same
        // tiny snapshot instead of falling back to an id list.
        saved.questionSeed?.let { sessionSeed = it }
        saved.drillSeed?.let { drillSampleSeed = it }
        questionShuffleEnabled = saved.questionShuffle
        optionShuffleSeed = saved.optionShuffleSeed
        Logger.i("TESTVM", "Resuming saved progress: index=${saved.currentIndex}, " +
            "answered=${saved.selections.size}")
        _state.update {
            it.copy(
                refs = ordered,
                selections = saved.selections.mapValues { e -> e.value.toSet() },
                revealed = saved.revealed,
                flagged = saved.flagged,
                currentIndex = saved.currentIndex.coerceIn(ordered.indices),
                remainingSeconds = saved.remainingSeconds,
                totalSeconds = saved.totalSeconds,
                dwellSeconds = saved.dwellSeconds,
                timeWarning = null,
                resumeOffer = null
            )
        }
        warnedThresholds.clear()
        startTimestamp = System.currentTimeMillis()
        lastNavMillis = startTimestamp
        // The snapshot's map becomes the new baseline, so time already spent
        // on a question is not counted again from this visit onwards.
        dwellCommitted.clear()
        dwellCommitted.putAll(saved.dwellSeconds)
        startDwellTicker()
        resolveCurrent()
        if (saved.totalSeconds > 0 && saved.remainingSeconds > 0) startTimer()
    }

    fun discardResume() {
        Logger.i("TESTVM", "Discarding saved progress, starting fresh")
        viewModelScope.launch { repository.clearTestProgress() }
        resumeOrder = emptyList()
        _state.update { it.copy(resumeOffer = null) }
    }

    /** Best-effort snapshot after every discrete change (never on timer ticks). */
    private fun persistProgress() {
        val current = _state.value
        if (current.loading || current.questionCount == 0 || current.submitted) return
        val snapshot = TestSnapshot(
            paperId = paperId,
            categoryIds = categoryIds,
            questionIds = emptyList(),
            selections = current.selections.mapValues { e -> e.value.toList() },
            revealed = current.revealed,
            flagged = current.flagged,
            currentIndex = current.currentIndex,
            remainingSeconds = current.remainingSeconds,
            totalSeconds = current.totalSeconds,
            dwellSeconds = current.dwellSeconds,
            questionSeed = sessionSeed,
            questionShuffle = questionShuffleEnabled,
            optionShuffleSeed = optionShuffleSeed,
            drillSeed = if (drillSampleSeed != 0L) drillSampleSeed else null,
            drillRequested = current.drillRequested
        )
        viewModelScope.launch {
            try {
                repository.saveTestProgress(snapshot.toJson())
            } catch (e: Exception) {
                Logger.w("TESTVM", "persistProgress failed: ${e.message}")
            }
        }
    }
}
