package com.mcqapp.ui.test

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
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
    val questions: List<Question> = emptyList(),
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
    val resumeOffer: TestSnapshot? = null,
    val dwellSeconds: Map<String, Long> = emptyMap(),
    val timeWarning: String? = null,
    val bookmarked: Set<String> = emptySet()
) {
    val currentQuestion: Question? get() = questions.getOrNull(currentIndex)
    val answeredCount: Int get() = selections.count { it.value.isNotEmpty() }
    val isCurrentRevealed: Boolean get() = currentQuestion?.id in revealed
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

    private var timerJob: Job? = null
    private var startTimestamp: Long = 0
    private var lastNavMillis: Long = 0
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
                val questions = if (mistakesOnly) {
                    repository.getMistakenQuestions(paperId)
                } else if (categoryIds.isEmpty()) {
                    repository.getQuestionsForPaper(paperId)
                } else {
                    repository.getQuestionsForCategories(categoryIds)
                }
                Logger.i("TESTVM", "Loaded ${questions.size} questions, duration=${paper?.durationMinutes}min" +
                    if (mistakesOnly) " (mistakes only)" else "")
                val shuffleQ = repository.shuffleQuestions().first()
                val shuffleO = repository.shuffleOptions().first()
                val seed = Random.nextLong()
                val ordered = Shuffle.shuffleAttempt(questions, seed, shuffleQ, shuffleO)
                if (shuffleQ || shuffleO) {
                    Logger.i("TESTVM", "Shuffled attempt: seed=$seed, " +
                        "questions=$shuffleQ, options=$shuffleO")
                }
                val isDrill = drillCount > 0
                val drilled = if (isDrill) {
                    val seed = Random.nextLong()
                    Logger.i("TESTVM", "Drill: sampling $drillCount of ${ordered.size}, seed=$seed")
                    com.mcqapp.domain.Drill.sample(ordered, drillCount, seed)
                } else {
                    ordered
                }
                val totalSeconds = if (isDrill && drillMinutes > 0) {
                    drillMinutes * 60
                } else {
                    (paper?.durationMinutes ?: 0) * 60
                }
                val practice = repository.practiceMode().first()
                val strict = repository.strictMode().first()
                val advance = repository.autoAdvance().first()
                if (practice && !strict) Logger.i("TESTVM", "Practice mode on: live feedback enabled")
                if (strict) Logger.i("TESTVM", "Strict exam mode on: aids hidden")
                _state.update {
                    it.copy(
                        loading = false,
                        paper = paper,
                        questions = drilled,
                        totalSeconds = totalSeconds,
                        remainingSeconds = totalSeconds,
                        practiceMode = practice,
                        strictMode = strict,
                        autoAdvance = advance,
                        mistakesOnly = mistakesOnly,
                        isDrill = isDrill
                    )
                }
                checkResumeOffer()
                startTimestamp = System.currentTimeMillis()
                lastNavMillis = startTimestamp
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

    /** Credits elapsed time since the last navigation to the question left. */
    private fun flushDwell() {
        val current = _state.value
        val question = current.currentQuestion ?: return
        val elapsed = (System.currentTimeMillis() - lastNavMillis) / 1000
        if (elapsed > 0) {
            _state.update { it.copy(dwellSeconds = Dwell.add(it.dwellSeconds, question.id, elapsed)) }
        }
        lastNavMillis = System.currentTimeMillis()
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
            current.questions.size
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
        val questions = _state.value.questions
        if (index in questions.indices) {
            flushDwell()
            _state.update { it.copy(currentIndex = index) }
            persistProgress()
        }
    }

    fun next() = goTo(_state.value.currentIndex + 1)
    fun previous() = goTo(_state.value.currentIndex - 1)

    fun submit() {
        val current = _state.value
        if (current.submitted || current.saving || current.questions.isEmpty()) return
        Logger.i("TESTVM", "submit() called: answered=${current.answeredCount}/${current.questions.size}, " +
            "remaining=${current.remainingSeconds}s")
        timerJob?.cancel()
        flushDwell()
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
                    repository.saveAttempt(
                        paperId = paperId,
                        paperTitle = current.paper?.title ?: "Test",
                        questions = current.questions,
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
    private suspend fun checkResumeOffer() {
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
        val ordered = TestSnapshot.reorder(_state.value.questions, saved.questionIds)
        if (ordered.isEmpty()) {
            repository.clearTestProgress()
            return
        }
        Logger.i("TESTVM", "Resume available: ${saved.selections.size} answered, " +
            "index=${saved.currentIndex}, remaining=${saved.remainingSeconds}s")
        _state.update { it.copy(resumeOffer = saved) }
    }

    fun resume() {
        val saved = _state.value.resumeOffer ?: return
        val ordered = TestSnapshot.reorder(_state.value.questions, saved.questionIds)
        if (ordered.isEmpty()) {
            discardResume()
            return
        }
        Logger.i("TESTVM", "Resuming saved progress: index=${saved.currentIndex}, " +
            "answered=${saved.selections.size}")
        _state.update {
            it.copy(
                questions = ordered,
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
        if (saved.totalSeconds > 0 && saved.remainingSeconds > 0) startTimer()
    }

    fun discardResume() {
        Logger.i("TESTVM", "Discarding saved progress, starting fresh")
        viewModelScope.launch { repository.clearTestProgress() }
        _state.update { it.copy(resumeOffer = null) }
    }

    /** Best-effort snapshot after every discrete change (never on timer ticks). */
    private fun persistProgress() {
        val current = _state.value
        if (current.loading || current.questions.isEmpty() || current.submitted) return
        val snapshot = TestSnapshot(
            paperId = paperId,
            categoryIds = categoryIds,
            questionIds = current.questions.map { it.id },
            selections = current.selections.mapValues { e -> e.value.toList() },
            revealed = current.revealed,
            flagged = current.flagged,
            currentIndex = current.currentIndex,
            remainingSeconds = current.remainingSeconds,
            totalSeconds = current.totalSeconds,
            dwellSeconds = current.dwellSeconds
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
