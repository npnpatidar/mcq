package com.mcqapp.ui.test

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.domain.Shuffle
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
    val paper: Paper? = null,
    val questions: List<Question> = emptyList(),
    val currentIndex: Int = 0,
    val selections: Map<String, Set<String>> = emptyMap(),
    val revealed: Set<String> = emptySet(),
    val flagged: Set<String> = emptySet(),
    val remainingSeconds: Int = 0,
    val totalSeconds: Int = 0,
    val submitted: Boolean = false,
    val attemptId: Long? = null
) {
    val currentQuestion: Question? get() = questions.getOrNull(currentIndex)
    val answeredCount: Int get() = selections.count { it.value.isNotEmpty() }
    val isCurrentRevealed: Boolean get() = currentQuestion?.id in revealed
}

class TestViewModel(
    application: Application,
    private val paperId: String,
    private val categoryIds: List<String>
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(TestUiState())
    val state: StateFlow<TestUiState> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var startTimestamp: Long = 0

    init {
        Logger.i("TESTVM", "TestViewModel created: paperId=$paperId, categoryIds=${categoryIds.size} categories")
        viewModelScope.launch {
            try {
                Logger.d("TESTVM", "Loading paper $paperId")
                val paper = repository.getPaper(paperId)
                Logger.d("TESTVM", "Loaded paper '${paper?.title}', categories=${paper?.categories?.size ?: 0}")
                val questions = if (categoryIds.isEmpty()) {
                    repository.getQuestionsForPaper(paperId)
                } else {
                    repository.getQuestionsForCategories(categoryIds)
                }
                Logger.i("TESTVM", "Loaded ${questions.size} questions, duration=${paper?.durationMinutes}min")
                val shuffleQ = repository.shuffleQuestions().first()
                val shuffleO = repository.shuffleOptions().first()
                val seed = Random.nextLong()
                val ordered = Shuffle.shuffleAttempt(questions, seed, shuffleQ, shuffleO)
                if (shuffleQ || shuffleO) {
                    Logger.i("TESTVM", "Shuffled attempt: seed=$seed, " +
                        "questions=$shuffleQ, options=$shuffleO")
                }
                val totalSeconds = (paper?.durationMinutes ?: 0) * 60
                _state.update {
                    it.copy(
                        loading = false,
                        paper = paper,
                        questions = ordered,
                        totalSeconds = totalSeconds,
                        remainingSeconds = totalSeconds
                    )
                }
                startTimestamp = System.currentTimeMillis()
                if (totalSeconds > 0) startTimer()
            } catch (e: Exception) {
                Logger.e("TESTVM", "Failed to load test session", e)
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val current = _state.value
                if (current.submitted || current.remainingSeconds <= 0) break
                _state.update { it.copy(remainingSeconds = it.remainingSeconds - 1) }
                if (_state.value.remainingSeconds <= 0) {
                    submit()
                    break
                }
            }
        }
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
    }

    fun revealCurrent() {
        val question = _state.value.currentQuestion ?: return
        Logger.d("TESTVM", "revealCurrent question=${question.id}")
        _state.update {
            it.copy(revealed = it.revealed + question.id)
        }
    }

    fun toggleFlag() {
        val question = _state.value.currentQuestion ?: return
        _state.update {
            val flagged = it.flagged.toMutableSet()
            if (!flagged.add(question.id)) flagged.remove(question.id)
            it.copy(flagged = flagged)
        }
    }

    fun goTo(index: Int) {
        val questions = _state.value.questions
        if (index in questions.indices) {
            _state.update { it.copy(currentIndex = index) }
        }
    }

    fun next() = goTo(_state.value.currentIndex + 1)
    fun previous() = goTo(_state.value.currentIndex - 1)

    fun submit() {
        val current = _state.value
        if (current.submitted || current.questions.isEmpty()) return
        Logger.i("TESTVM", "submit() called: answered=${current.answeredCount}/${current.questions.size}, " +
            "remaining=${current.remainingSeconds}s")
        timerJob?.cancel()
        val durationSeconds = if (current.totalSeconds > 0) {
            (current.totalSeconds - current.remainingSeconds).toLong()
        } else {
            ((System.currentTimeMillis() - startTimestamp) / 1000)
        }
        _state.update { it.copy(submitted = true) }
        viewModelScope.launch {
            val attemptId = repository.saveAttempt(
                paperId = paperId,
                paperTitle = current.paper?.title ?: "Test",
                questions = current.questions,
                selections = current.selections,
                negativeMarking = current.paper?.negativeMarking ?: 0.0,
                durationSeconds = durationSeconds,
                finishedAt = System.currentTimeMillis()
            )
            Logger.i("TESTVM", "Attempt saved: attemptId=$attemptId")
            _state.update { it.copy(attemptId = attemptId) }
        }
    }
}
