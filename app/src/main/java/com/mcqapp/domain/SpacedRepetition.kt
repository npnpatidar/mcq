package com.mcqapp.domain

/**
 * Spaced repetition scheduling for a single question, Anki-style.
 *
 * The unit of memory is the question inside one paper, never the paper as a
 * whole, so each card carries its own ease / interval / due date. Grading uses
 * the learner-facing vocabulary (Again / Hard / Good / Easy) rather than exam
 * vocabulary (correct / wrong).
 *
 * The scheduler sits behind [Scheduler] so the forgetting model can be swapped
 * (SM-2 now, FSRS later) without touching storage, the study loop or tests.
 * Every implementation must be pure and deterministic: no clock, no
 * randomness, no Android types.
 */
data class CardState(
    val questionId: String,
    val ease: Double = Sm2Scheduler.DEFAULT_EASE,
    val intervalDays: Int = 0,
    val dueAt: Long = 0L,
    val reps: Int = 0,
    val lapses: Int = 0,
    val leech: Boolean = false,
    val lastReviewedAt: Long = 0L
) {
    /**
     * Never studied: no reviews and no due date. An "Again" answer also leaves
     * [reps] at zero, but it does set [dueAt], which keeps relearning cards out
     * of the new-card limit so they still come back the same day.
     */
    val isNew: Boolean get() = reps == 0 && dueAt == 0L

    /** Failed, waiting on a short relearning interval rather than a new card. */
    val isLearning: Boolean get() = reps == 0 && dueAt != 0L

    /** Has a due date at all (new cards are due by definition, not by date). */
    val isScheduled: Boolean get() = dueAt != 0L
}

enum class ReviewGrade(val label: String) {
    AGAIN("Again"),
    HARD("Hard"),
    GOOD("Good"),
    EASY("Easy")
}

/** Why a question is in today's study queue. */
enum class StudyReason { DUE, NEW, LEECH }

data class StudyCard(
    val questionId: String,
    val reason: StudyReason,
    val state: CardState
)

/** One graded answer, used to rebuild a card's schedule from history. */
data class ReviewSignal(
    val questionId: String,
    val grade: ReviewGrade,
    val at: Long
)

/**
 * A forgetting model: given a card and a grade, what happens next.
 *
 * Implementations must not depend on each other or on Android, so a new model
 * (FSRS) can be added and selected by a setting without touching the database
 * or UI layers.
 */
interface Scheduler {
    /** Grade counts after which a card is flagged as a leech. */
    val leechThreshold: Int

    fun initial(questionId: String): CardState

    /** Applies one grade and returns the next state. */
    fun next(state: CardState, grade: ReviewGrade, now: Long): CardState

    /** Estimated probability of recall, 0..1, for display only. */
    fun retention(state: CardState, now: Long): Double

    /** Returns a card to new, e.g. after its content changed. */
    fun reset(state: CardState): CardState
}

/**
 * SM-2: the classic single-ease-factor model.
 *
 * Intervals are whole days because a question bank is studied over weeks, not
 * seconds, but a failed card still returns later the same day so one sitting
 * can recover it. Ease drops on failures, rises on Easy, and is clamped so a
 * card can never become permanently unlearnable or trivially short. Only a
 * card that already graduated counts a miss as a lapse — a first exposure has
 * no memory to lose.
 *
 * Chosen over FSRS for the first release: it needs no parameter tuning and no
 * optimizer run, so it is correct on day one for banks of any size. FSRS
 * needs ~1k graded reviews per deck before it beats this, and the review log to
 * fit them does not exist yet.
 */
object Sm2Scheduler : Scheduler {

    const val DEFAULT_EASE = 2.5
    const val MIN_EASE = 1.3
    const val MAX_EASE = 3.0
    const val DAY_MS = 24 * 60 * 60 * 1000L
    const val RELEARN_MS = 10 * 60 * 1000L
    const val FIRST_INTERVAL_DAYS = 1
    const val SECOND_INTERVAL_DAYS = 6
    const val EASY_FIRST_INTERVAL_DAYS = 4
    override val leechThreshold: Int = 8

    override fun initial(questionId: String): CardState = CardState(questionId = questionId)

    override fun next(state: CardState, grade: ReviewGrade, now: Long): CardState {
        val hadMemory = state.reps > 0
        val ease = easeAfter(state.ease, grade)
        val lapses = if (grade == ReviewGrade.AGAIN && hadMemory) state.lapses + 1 else state.lapses
        val interval = nextInterval(state, grade, ease)
        val dueAt = when {
            interval <= 0 -> now + RELEARN_MS
            else -> now + interval * DAY_MS
        }
        val reps = if (grade == ReviewGrade.AGAIN) 0 else maxOf(1, state.reps + 1)
        return state.copy(
            ease = ease,
            intervalDays = interval.coerceAtLeast(0),
            dueAt = dueAt,
            reps = reps,
            lapses = lapses,
            leech = lapses >= leechThreshold,
            lastReviewedAt = now
        )
    }

    private fun easeAfter(ease: Double, grade: ReviewGrade): Double = when (grade) {
        ReviewGrade.AGAIN -> ease - 0.20
        ReviewGrade.HARD -> ease - 0.15
        ReviewGrade.GOOD -> ease
        ReviewGrade.EASY -> ease + 0.15
    }.coerceIn(MIN_EASE, MAX_EASE)

    /** New interval in days; 0 means relearning later today. */
    private fun nextInterval(state: CardState, grade: ReviewGrade, ease: Double): Int =
        when (grade) {
            ReviewGrade.AGAIN -> 0
            ReviewGrade.HARD ->
                if (state.intervalDays <= 0) FIRST_INTERVAL_DAYS
                else maxOf(state.intervalDays + 1, (state.intervalDays * 1.2).toLong().toInt())
            ReviewGrade.GOOD -> when {
                state.reps <= 0 -> FIRST_INTERVAL_DAYS
                state.reps == 1 -> SECOND_INTERVAL_DAYS
                else -> maxOf(
                    state.intervalDays + 1,
                    (state.intervalDays * ease).toLong().toInt()
                )
            }
            ReviewGrade.EASY ->
                if (state.reps <= 0) EASY_FIRST_INTERVAL_DAYS
                else maxOf(
                    state.intervalDays + 1,
                    (state.intervalDays * ease * 1.3).toLong().toInt()
                )
        }

    override fun retention(state: CardState, now: Long): Double {
        if (state.reps == 0 || state.intervalDays <= 0 || state.dueAt == 0L) return 0.0
        if (now <= state.dueAt) return 1.0
        val overdueDays = (now - state.dueAt).toDouble() / DAY_MS
        val stability = state.intervalDays.toDouble() / state.ease
        return (1.0 / (1.0 + overdueDays / stability)).coerceIn(0.0, 1.0)
    }

    override fun reset(state: CardState): CardState = initial(state.questionId)
}

/**
 * Scheduler-agnostic study queue and grade inference.
 *
 * These rules are about *what to study*, not *how memory decays*, so they stay
 * shared no matter which [Scheduler] is in use.
 */
object Study {

    const val DAY_MS = Sm2Scheduler.DAY_MS

    /** Correct answers faster than this read as effortless. */
    const val FAST_SECONDS = 8L

    /** Correct answers slower than this read as a struggle. */
    const val SLOW_SECONDS = 30L

    const val DEFAULT_NEW_LIMIT = 20

    /**
     * Infers a grade from an answered attempt. Untracked timing (0s) is a
     * normal Good rather than effortless.
     */
    fun inferGrade(isCorrect: Boolean, dwellSeconds: Long, skipped: Boolean = false): ReviewGrade {
        if (skipped || !isCorrect) return ReviewGrade.AGAIN
        if (dwellSeconds in 1 until FAST_SECONDS) return ReviewGrade.EASY
        if (dwellSeconds >= SLOW_SECONDS) return ReviewGrade.HARD
        return ReviewGrade.GOOD
    }

    /**
     * Replays graded history in order to rebuild a card's schedule, so an
     * existing install starts warm instead of treating everything as new.
     */
    fun rebuild(
        scheduler: Scheduler,
        questionId: String,
        signals: List<ReviewSignal>
    ): CardState {
        val ordered = signals.filter { it.questionId == questionId }.sortedBy { it.at }
        return ordered.fold(scheduler.initial(questionId)) { state, signal ->
            scheduler.next(state, signal.grade, signal.at)
        }
    }

    /**
     * Today's queue for a paper: due cards oldest-due first, then leeches,
     * then new cards up to [newLimit]. Questions absent from [states] are new.
     * Order is stable so a reload mid-session does not reshuffle the deck.
     */
    fun queue(
        scheduler: Scheduler,
        questionIds: List<String>,
        states: Map<String, CardState>,
        now: Long,
        newLimit: Int = DEFAULT_NEW_LIMIT
    ): List<StudyCard> {
        val due = mutableListOf<StudyCard>()
        val leeches = mutableListOf<StudyCard>()
        val fresh = mutableListOf<StudyCard>()
        var newTaken = 0

        questionIds.forEach { id ->
            val state = states[id]
            when {
                state == null || state.isNew -> {
                    if (newTaken < newLimit) {
                        fresh.add(StudyCard(id, StudyReason.NEW, state ?: scheduler.initial(id)))
                        newTaken++
                    }
                }
                // isLearning cards fall through here: they are due in minutes, not
                // new, so the new-card limit must not hide them.
                state.leech && state.dueAt <= now -> leeches.add(StudyCard(id, StudyReason.LEECH, state))
                state.dueAt <= now -> due.add(StudyCard(id, StudyReason.DUE, state))
            }
        }
        return due.sortedBy { it.state.dueAt } +
            leeches.sortedBy { it.state.dueAt } +
            fresh
    }

    fun dueCount(states: Collection<CardState>, now: Long): Int =
        states.count { !it.isNew && it.dueAt <= now }

    fun newCount(questionIds: List<String>, states: Map<String, CardState>): Int =
        questionIds.count { states[it]?.isNew ?: true }

    fun leechCount(states: Collection<CardState>): Int = states.count { it.leech }
}
