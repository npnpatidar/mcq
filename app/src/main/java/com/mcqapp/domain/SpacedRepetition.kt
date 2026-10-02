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
    val ease: Double = SchedulerConfig().defaultEase,
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
/**
 * Every tunable number the scheduler uses, in one place.
 *
 * Defaults match the values this app has always used, which are close to Anki's
 * own defaults. They are deliberately not `const val`: a user can change them, so
 * the scheduler is built per-use from a config rather than read from statics.
 * Field names follow Anki's review-options wording so a value read out of Anki
 * lands in the obvious place.
 */
data class SchedulerConfig(
    /** Ease a new card starts at. Anki: 2.5 */
    val defaultEase: Double = 2.5,
    /** Floor for ease, so a card can never become unlearnable. Anki: 1.3 */
    val minEase: Double = 1.3,
    /** Ceiling for ease. Anki: 3.0 */
    val maxEase: Double = 3.0,
    /** Ease penalty per Again. Anki: 0.20 */
    val againEaseFactor: Double = 0.20,
    /** Ease penalty per Hard. Anki: 0.15 */
    val hardEaseFactor: Double = 0.15,
    /** Ease bonus per Easy. Anki: 0.15 */
    val easyEaseFactor: Double = 0.15,
    /** Interval given to a graduating (first-pass) card on Good. Anki: 1 */
    val firstIntervalDays: Int = 1,
    /** Interval for the second review on Good. Anki: 6 */
    val secondIntervalDays: Int = 6,
    /** Interval given to a graduating card on Easy. Anki: 4 */
    val easyFirstIntervalDays: Int = 4,
    /** Hard multiplies the previous interval by this. Anki: 1.2 */
    val hardIntervalMultiplier: Double = 1.2,
    /** Extra factor applied to Easy on top of ease. Anki: 1.3 */
    val easyBonus: Double = 1.3,
    /** Floor on any review interval in days. Anki: 1 */
    val minimumIntervalDays: Int = 1,
    /** Ceiling on any interval in days, ~100 years. Anki: 36500 */
    val maxIntervalDays: Int = 36500,
    /** Delay before a failed card returns, in ms. Anki relearn step: 10 min */
    val relearnMs: Long = 10 * 60 * 1000L,
    /** Lapses before a card is flagged leech. Anki: 8 */
    val leechThreshold: Int = 8,
    /** New cards offered per day. Anki: 20 */
    val newLimit: Int = 20,
    /** Reviews offered per day. Anki: 200 */
    val reviewLimit: Int = 200,
    /** Correct answer faster than this (seconds) infers Easy from history. */
    val fastSeconds: Long = 8L,
    /** Correct answer slower than this (seconds) infers Hard from history. */
    val slowSeconds: Long = 30L
) {
    /** Guards against values that would break scheduling entirely. */
    fun sanitized(): SchedulerConfig = copy(
        defaultEase = defaultEase.coerceIn(1.3, 5.0),
        minEase = minEase.coerceIn(1.0, 3.0),
        maxEase = maxEase.coerceIn(minEase.coerceIn(1.0, 3.0), 5.0),
        againEaseFactor = againEaseFactor.coerceIn(0.0, 1.0),
        hardEaseFactor = hardEaseFactor.coerceIn(0.0, 1.0),
        easyEaseFactor = easyEaseFactor.coerceIn(0.0, 1.0),
        firstIntervalDays = firstIntervalDays.coerceIn(1, 3650),
        secondIntervalDays = secondIntervalDays.coerceIn(1, 3650),
        easyFirstIntervalDays = easyFirstIntervalDays.coerceIn(1, 3650),
        hardIntervalMultiplier = hardIntervalMultiplier.coerceIn(1.0, 5.0),
        easyBonus = easyBonus.coerceIn(1.0, 5.0),
        minimumIntervalDays = minimumIntervalDays.coerceIn(1, 3650),
        maxIntervalDays = maxIntervalDays.coerceIn(minimumIntervalDays, 36500),
        relearnMs = relearnMs.coerceIn(60_000L, 24 * 60 * 60 * 1000L),
        leechThreshold = leechThreshold.coerceIn(1, 100),
        newLimit = newLimit.coerceIn(0, 9999),
        reviewLimit = reviewLimit.coerceIn(0, 9999),
        fastSeconds = fastSeconds.coerceIn(1, 3600),
        slowSeconds = slowSeconds.coerceIn(fastSeconds.coerceIn(1, 3600), 3600)
    )
}

class Sm2Scheduler(private val config: SchedulerConfig = SchedulerConfig()) : Scheduler {

    override val leechThreshold: Int get() = config.leechThreshold

    override fun initial(questionId: String): CardState =
        CardState(questionId = questionId, ease = config.defaultEase)

    override fun next(state: CardState, grade: ReviewGrade, now: Long): CardState {
        val hadMemory = state.reps > 0
        val ease = easeAfter(state.ease, grade)
        val lapses = if (grade == ReviewGrade.AGAIN && hadMemory) state.lapses + 1 else state.lapses
        val interval = nextInterval(state, grade, ease)
        val dueAt = when {
            interval <= 0 -> now + config.relearnMs
            else -> now + interval * DAY_MS
        }
        val reps = if (grade == ReviewGrade.AGAIN) 0 else maxOf(1, state.reps + 1)
        return state.copy(
            ease = ease,
            intervalDays = interval.coerceAtLeast(0),
            dueAt = dueAt,
            reps = reps,
            lapses = lapses,
            leech = lapses >= config.leechThreshold,
            lastReviewedAt = now
        )
    }

    private fun easeAfter(ease: Double, grade: ReviewGrade): Double = when (grade) {
        ReviewGrade.AGAIN -> ease - config.againEaseFactor
        ReviewGrade.HARD -> ease - config.hardEaseFactor
        ReviewGrade.GOOD -> ease
        ReviewGrade.EASY -> ease + config.easyEaseFactor
    }.coerceIn(config.minEase, config.maxEase)

    /**
     * New interval in days; 0 means relearning later today. Every growth path
     * takes the larger of "+1 day" and "multiply", then clamps to the
     * configured floor and ceiling, so a card can neither stall at 1 day nor
     * jump to an absurd interval from a single Easy.
     */
    private fun nextInterval(state: CardState, grade: ReviewGrade, ease: Double): Int =
        when (grade) {
            ReviewGrade.AGAIN -> 0
            ReviewGrade.HARD ->
                if (state.intervalDays <= 0) config.firstIntervalDays
                else grow(state.intervalDays, config.hardIntervalMultiplier)
            ReviewGrade.GOOD -> when {
                state.reps <= 0 -> config.firstIntervalDays
                state.reps == 1 -> config.secondIntervalDays
                else -> grow(state.intervalDays, ease)
            }
            ReviewGrade.EASY ->
                if (state.reps <= 0) config.easyFirstIntervalDays
                else grow(state.intervalDays, ease * config.easyBonus)
        }

    private fun grow(intervalDays: Int, factor: Double): Int =
        maxOf(intervalDays + 1, (intervalDays * factor).toLong().toInt())
            .coerceIn(config.minimumIntervalDays, config.maxIntervalDays)

    override fun retention(state: CardState, now: Long): Double {
        if (state.reps == 0 || state.intervalDays <= 0 || state.dueAt == 0L) return 0.0
        if (now <= state.dueAt) return 1.0
        val overdueDays = (now - state.dueAt).toDouble() / DAY_MS
        val stability = state.intervalDays.toDouble() / state.ease
        return (1.0 / (1.0 + overdueDays / stability)).coerceIn(0.0, 1.0)
    }

    override fun reset(state: CardState): CardState = initial(state.questionId)

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
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
     * normal Good rather than effortless. Takes the timing thresholds from
     * [config] so a user who retunes them also changes history reconstruction.
     */
    fun inferGrade(
        isCorrect: Boolean,
        dwellSeconds: Long,
        skipped: Boolean = false,
        config: SchedulerConfig = SchedulerConfig()
    ): ReviewGrade {
        if (skipped || !isCorrect) return ReviewGrade.AGAIN
        if (dwellSeconds in 1 until config.fastSeconds) return ReviewGrade.EASY
        if (dwellSeconds >= config.slowSeconds) return ReviewGrade.HARD
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

    /**
     * Only the cards flagged as leeches. The library's "N tricky" button
     * promised the user's problem questions but ran the ordinary due+new queue,
     * so the label did not match the behaviour.
     */
    fun onlyLeeches(cards: List<StudyCard>): List<StudyCard> =
        cards.filter { it.reason == StudyReason.LEECH || it.state.leech }
}
