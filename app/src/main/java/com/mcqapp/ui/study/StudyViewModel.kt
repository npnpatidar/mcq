package com.mcqapp.ui.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.StudyReason
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StudyUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val paperTitle: String = "",
    val queue: List<Question> = emptyList(),
    val reasons: Map<String, StudyReason> = emptyMap(),
    val index: Int = 0,
    val selections: Map<String, Set<String>> = emptyMap(),
    val revealed: Boolean = false,
    val grading: Boolean = false,
    val reviewed: Int = 0,
    val againCount: Int = 0,
    val goodCount: Int = 0,
    val finished: Boolean = false,
    val emptyReason: String = ""
) {
    val currentQuestion: Question? get() = queue.getOrNull(index)
    val remaining: Int get() = (queue.size - index).coerceAtLeast(0)
    val currentSelection: Set<String> get() = currentQuestion?.let { selections[it.id] }.orEmpty()
    val isCurrentRevealed: Boolean get() = revealed
    val progress: Float get() = if (queue.isEmpty()) 0f else index.toFloat() / queue.size
}

class StudyViewModel(
    application: Application,
    private val paperId: String,
    private val leechesOnly: Boolean = false
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(StudyUiState())
    val state: StateFlow<StudyUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val paper = repository.getPaper(paperId)
                val cards = repository.getStudyQueue(paperId, leechesOnly = leechesOnly)
                if (cards.isEmpty()) {
                    _state.update {
                        it.copy(
                            loading = false,
                            finished = true,
                            emptyReason = if (leechesOnly) {
                                "No tricky questions left. Keep reviewing and they will come back."
                            } else {
                                "Nothing due here. Come back later, or study a different paper."
                            }
                        )
                    }
                    return@launch
                }
                val byId = repository.getQuestionsForPaper(paperId).associateBy { it.id }
                val questions = cards.mapNotNull { byId[it.questionId] }
                Logger.i("STUDY", "Study session: paper=$paperId, cards=${cards.size}, loaded=${questions.size}")
                _state.update {
                    it.copy(
                        loading = false,
                        paperTitle = paper?.title ?: "Study",
                        queue = questions,
                        reasons = cards.associate { c -> c.questionId to c.reason }
                    )
                }
            } catch (e: Exception) {
                // `finished = true` with an empty reason rendered the success
                // branch: "Session complete / 0 reviewed". A failure has to
                // look like a failure.
                Logger.e("STUDY", "Failed to load study session", e)
                _state.update { it.copy(loading = false, loadError = "Could not start the session: ${e.message}") }
            }
        }
    }

    /** Re-runs a failed load after the user asks for it. */
    fun retry() {
        _state.update { it.copy(loading = true, loadError = null) }
        load()
    }

    fun toggleOption(optionId: String) {
        val question = _state.value.currentQuestion ?: return
        if (_state.value.revealed) return
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

    fun reveal() {
        if (_state.value.revealed) return
        Logger.d("STUDY", "reveal question=${_state.value.currentQuestion?.id}")
        _state.update { it.copy(revealed = true) }
    }

    /**
     * Applies a grade, persists the new schedule, and advances. The learner
     * sees the answer before grading, so the grade is self-reported — that is
     * the whole point of the four-button vocabulary.
     */
    fun grade(grade: ReviewGrade) {
        val current = _state.value
        val question = current.currentQuestion ?: return
        // Claim the card before launching: the write is async, so a second tap
        // would otherwise persist two reviews for the same question.
        if (current.grading) return
        _state.update { it.copy(grading = true) }
        viewModelScope.launch {
            try {
                val next = repository.recordStudyReview(paperId, question.id, grade)
                Logger.i(
                    "STUDY",
                    "graded ${question.id} -> $grade, next in ${next.intervalDays}d, " +
                        "leech=${next.leech}"
                )
                val reasons = current.reasons - question.id
                _state.update {
                    it.copy(
                        reviewed = it.reviewed + 1,
                        againCount = it.againCount + if (grade == ReviewGrade.AGAIN) 1 else 0,
                        goodCount = it.goodCount + if (grade == ReviewGrade.AGAIN) 0 else 1,
                        selections = it.selections - question.id,
                        revealed = false,
                        grading = false,
                        index = it.index + 1,
                        reasons = reasons,
                        finished = it.index + 1 >= it.queue.size
                    )
                }
            } catch (e: Exception) {
                Logger.e("STUDY", "recordStudyReview(${question.id}) failed", e)
                _state.update { it.copy(grading = false) }
            }
        }
    }

    /** Restarts the same question list so a session can be repeated. */
    fun restart() {
        _state.update {
            it.copy(
                index = 0,
                selections = emptyMap(),
                revealed = false,
                grading = false,
                reviewed = 0,
                againCount = 0,
                goodCount = 0,
                finished = false
            )
        }
        Logger.i("STUDY", "restart study session: ${_state.value.queue.size} cards")
    }
}
