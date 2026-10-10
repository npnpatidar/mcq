package com.mcqapp.ui.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CardState
import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.StudyReason
import com.mcqapp.util.IntervalFormat
import com.mcqapp.util.Logger
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

data class StudyUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val paperTitle: String = "",
    val queue: List<Question> = emptyList(),
    val reasons: Map<String, StudyReason> = emptyMap(),
    /**
     * The schedule each card had when the session was built. Kept because the
     * answer buttons show the delay each grade would produce, and that is a
     * function of where this card currently sits.
     */
    val states: Map<String, CardState> = emptyMap(),
    /** Formatted delay per grade for the card on screen, shown on the buttons. */
    val previews: Map<ReviewGrade, String> = emptyMap(),
    val index: Int = 0,
    val selections: Map<String, Set<String>> = emptyMap(),
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
    /** When the on-screen question was first shown, for the dwell ladder. */
    val questionStartedAt: Long = 0L,
    /** The grade Check proposed; Next persists it. Null until checked. */
    val pendingGrade: ReviewGrade? = null,
    /** What the last check decided, for the result card. */
    val lastResult: StudyResult? = null,
    val reviewed: Int = 0,
    val againCount: Int = 0,
    val goodCount: Int = 0,
    val finished: Boolean = false,
    val emptyReason: String = "",
    /** Passages for the queue's members, keyed by id, for the context card. */
    val passages: Map<String, com.mcqapp.domain.Passage> = emptyMap()
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

    /** Read once per session, alongside the queue, for the button previews. */
    private var config: SchedulerConfig? = null

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
                config = repository.schedulerConfigNow()
                val simplified = repository.simplifiedStudy().first()
                // Shared context only (parked decision): each member keeps its
                // own SM-2 schedule; its card just carries the passage above it.
                val passages = repository.getPassagesByIds(
                    questions.mapNotNull { it.passageId }.distinct()
                ).associateBy { it.id }
                _state.update {
                    it.copy(
                        loading = false,
                        paperTitle = paper?.title ?: "Study",
                        queue = questions,
                        reasons = cards.associate { c -> c.questionId to c.reason },
                        states = cards.associate { c -> c.questionId to c.state },
                        passages = passages,
                        simplified = simplified,
                        questionStartedAt = System.currentTimeMillis()
                    )
                }
                refreshPreviews()
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
        val question = current.currentQuestion ?: return
        if (!current.simplified || current.revealed || current.grading) return
        val now = System.currentTimeMillis()
        val dwellSeconds = ((now - current.questionStartedAt).coerceAtLeast(0L)) / 1000L
        val selection = current.selections[question.id].orEmpty()
        val grade = com.mcqapp.domain.Study.autoGrade(
            correctOptionIds = question.correctOptionIds,
            selection = selection,
            dwellSeconds = dwellSeconds,
            isGuess = current.isGuess,
            config = config ?: com.mcqapp.domain.SchedulerConfig()
        )
        _state.update {
            it.copy(
                revealed = true,
                pendingGrade = grade,
                lastResult = buildResult(
                    question, selection, grade, current.isGuess, dwellSeconds, now
                )
            )
        }
        Logger.d("STUDY", "checked ${question.id} -> $grade (guess=${current.isGuess}, dwell=${dwellSeconds}s)")
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
        val card = _state.value.states[question.id] ?: return null
        val scheduler = com.mcqapp.domain.Sm2Scheduler(schedulerConfig)
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
        val question = current.currentQuestion ?: return
        if (!current.simplified || !current.revealed || current.pendingGrade == null) return
        val now = System.currentTimeMillis()
        val dwellSeconds = ((now - current.questionStartedAt).coerceAtLeast(0L)) / 1000L
        _state.update {
            it.copy(
                pendingGrade = grade,
                lastResult = buildResult(
                    question,
                    it.selections[question.id].orEmpty(),
                    grade,
                    it.isGuess,
                    dwellSeconds,
                    now
                )
            )
        }
    }

    /**
     * Persists the proposed grade and advances. Guarded like [grade]: the write
     * is async, so a second tap would persist two reviews for one question.
     */
    fun next() {
        val current = _state.value
        val question = current.currentQuestion ?: return
        val proposed = current.pendingGrade ?: return
        if (!current.simplified || !current.revealed) return
        if (current.grading) return
        _state.update { it.copy(grading = true) }
        viewModelScope.launch {
            try {
                val next = repository.recordStudyReview(paperId, question.id, proposed)
                Logger.i(
                    "STUDY",
                    "graded ${question.id} -> $proposed (auto), next in ${next.intervalDays}d, " +
                        "leech=${next.leech}"
                )
                val reasons = current.reasons - question.id
                _state.update {
                    it.copy(
                        reviewed = it.reviewed + 1,
                        againCount = it.againCount + if (proposed == ReviewGrade.AGAIN) 1 else 0,
                        goodCount = it.goodCount + if (proposed == ReviewGrade.AGAIN) 0 else 1,
                        selections = it.selections - question.id,
                        revealed = false,
                        grading = false,
                        index = it.index + 1,
                        reasons = reasons,
                        isGuess = false,
                        questionStartedAt = System.currentTimeMillis(),
                        pendingGrade = null,
                        lastResult = null,
                        finished = it.index + 1 >= it.queue.size
                    )
                }
            } catch (e: Exception) {
                Logger.e("STUDY", "recordStudyReview(${question.id}) failed", e)
                _state.update { it.copy(grading = false) }
            }
            refreshPreviews()
        }
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
            // The next card has its own schedule, so its labels have to be
            // recomputed rather than left showing the graded card's delays.
            refreshPreviews()
        }
    }

    /**
     * The four button labels for whichever card is on screen.
     *
     * Read off the same pure [com.mcqapp.domain.Scheduler.next] the grading path
     * uses, so a button cannot promise an interval the scheduler would not
     * schedule. Nothing is persisted, and a card whose schedule is unknown gets
     * no labels rather than a guess.
     */
    private fun refreshPreviews() {
        val current = config ?: return
        val scheduler = com.mcqapp.domain.Sm2Scheduler(current)
        _state.update { state ->
            val card = state.currentQuestion?.id?.let { state.states[it] }
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
                finished = false,
                isGuess = false,
                questionStartedAt = System.currentTimeMillis(),
                pendingGrade = null,
                lastResult = null
            )
        }
        refreshPreviews()
        Logger.i("STUDY", "restart study session: ${_state.value.queue.size} cards")
    }
}
