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
/**
 * Where a card sits in the FSRS learning pipeline.
 *
 * [NEW] has no memory yet; [LEARNING] is a first-exposure card recovering
 * inside the same sitting; [REVIEW] is a graduated card on a multi-day FSRS
 * interval; [RELEARNING] is a graduated card that was forgotten and is
 * recovering. The kind drives state transitions and the candidate pools, not
 * the math: stability and difficulty carry the memory.
 */
enum class CardStateKind { NEW, LEARNING, REVIEW, RELEARNING }

/** How the study loop picks the next card. */
enum class StudyMode {
    /** Serve what is due, most forgettable first; new cards only after. */
    MEMORY_FIRST,

    /** Surface new cards ahead of the due backlog, interleaved. */
    EXPLORE_NEW
}

data class CardState(
    val questionId: String,
    /** Ease on the export scale (1.3..3.0): mirrored from difficulty by FSRS. */
    val ease: Double = 2.5,
    val intervalDays: Int = 0,
    val dueAt: Long = 0L,
    val reps: Int = 0,
    val lapses: Int = 0,
    val leech: Boolean = false,
    val lastReviewedAt: Long = 0L,
    /**
     * FSRS memory state. [difficulty] is the 1..10 ease-of-recall estimate and
     * [stability] the days until recall probability drops to the benchmark
     * retention; both are zero for a card never studied.
     */
    val difficulty: Double = 0.0,
    val stability: Double = 0.0,
    val state: CardStateKind = CardStateKind.NEW,
    /** Total grades ever applied, including failures. Mirrored into [reps]. */
    val reviewCount: Int = 0
) {
    /**
     * Never studied: no reviews and no due date. Any grade — Again included —
     * leaves this false: FSRS gives the card a shorter interval and schedules
     * it back into the same sitting, but it is no longer unread material.
     */
    val isNew: Boolean get() = reps == 0 && dueAt == 0L

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
/**
 * Why the session pulled a card now. Drives the chip on the card and nothing
 * else — the pull order itself is [Study.selectNext]'s business.
 */
enum class StudyReason {
    /** A learning/relearning step is due: short-term repetition first. */
    LEARNING,

    /** A graduated card whose recall probability has dropped well past due. */
    LOW_RECALL_RISK,

    /** A graduated card that is due. */
    DUE,

    /** A card that has never been studied. */
    NEW,

    /** Set only on the tricky-drill surface; the main pull never labels it. */
    LEECH
}

data class StudyCard(
    val questionId: String,
    val reason: StudyReason,
    val state: CardState
)

/** What a pull-based session would serve right now, per pool. */
data class StudyEligibility(
    /** Learning/relearning steps due now, leeches excluded. */
    val learning: Int = 0,
    /** Graduated cards due now, leeches excluded. */
    val due: Int = 0,
    /**
     * Due leeches wherever they sit in the pull order — the tricky drill's
     * promise. A leech that lapsed back into learning is drilled, not
     * hidden behind the memory-first ordering.
     */
    val leeches: Int = 0,
    /** Never-studied cards available. */
    val fresh: Int = 0
) {
    /** Everything the main session serves: the badge's contract. */
    val total: Int get() = learning + due + leeches + fresh
}

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
 * Every tunable number the FSRS scheduler uses, in one place.
 *
 * The memory model itself is fixed (the FSRS-6 formulas with the official
 * unoptimized weights); what remains configurable is the pacing around it —
 * how hard intervals target recall, when the study day rolls over, how fast a
 * failed card returns, and where the grade-inference thresholds sit. They are
 * deliberately not `const val`: a user can change them, so the scheduler is
 * built per-use from a config rather than read from statics.
 */
data class SchedulerConfig(
    /** Ceiling on any interval in days, ~100 years. Anki: 36500 */
    val maxIntervalDays: Int = 36500,
    /** Delay before a failed card returns, in ms. Anki relearn step: 10 min */
    val relearnMs: Long = 10 * 60 * 1000L,
    /** Lapses before a card is flagged leech. Anki: 8 */
    val leechThreshold: Int = 8,
    /**
     * Recall probability future intervals target (FSRS), clamped 0.75..0.97.
     * 0.9 is the FSRS default: at it, a card's interval equals its stability.
     */
    val desiredRetention: Double = 0.9,
    /** How the next card is picked: memory first, or new cards explored. */
    val studyMode: StudyMode = StudyMode.MEMORY_FIRST,
    /** Whether new cards are ever introduced. Off = review-only sessions. */
    val introduceNewCards: Boolean = true,
    /**
     * Hour of the day at which a new study day begins, on the device's clock.
     * Anki: 4. A card answered at 23:00 is then due at the next 04:00 rather
     * than 24 hours later.
     */
    val dayStartHour: Int = DayBoundary.DEFAULT_DAY_START_HOUR,
    /** Correct answer faster than this (seconds) infers Easy from history. */
    val fastSeconds: Long = 8L,
    /** Correct answer slower than this (seconds) infers Hard from history. */
    val slowSeconds: Long = 30L
) {
    /** Guards against values that would break scheduling entirely. */
    fun sanitized(): SchedulerConfig = copy(
        maxIntervalDays = maxIntervalDays.coerceIn(1, 36500),
        relearnMs = relearnMs.coerceIn(60_000L, 24 * 60 * 60 * 1000L),
        leechThreshold = leechThreshold.coerceIn(1, 100),
        desiredRetention = desiredRetention.coerceIn(0.75, 0.97),
        dayStartHour = dayStartHour.coerceIn(0, 23),
        fastSeconds = fastSeconds.coerceIn(1, 3600),
        slowSeconds = slowSeconds.coerceIn(fastSeconds.coerceIn(1, 3600), 3600)
    )
}

/**
 * Scheduler-agnostic study loop: what to pull next, and grade inference.
 *
 * These rules are about *what to study*, not *how memory decays*, so they stay
 * shared no matter which [Scheduler] is in use.
 */
object Study {

    const val DAY_MS = DayBoundary.DAY_MS

    /** Correct answers faster than this read as effortless. */
    const val FAST_SECONDS = 8L

    /** Correct answers slower than this read as a struggle. */
    const val SLOW_SECONDS = 30L

    /**
     * A due review whose predicted recall has fallen below this is pulled with
     * [StudyReason.LOW_RECALL_RISK] — "likely to be forgotten soon". Absolute,
     * not relative to the retention target, so the chip means the same thing at
     * every setting; one obvious place to tune (spec §7: no universal optimal
     * score).
     */
    const val LOW_RECALL_RISK_RETENTION = 0.75

    /**
     * Explore mode surfaces a new card after this many reviews since the last
     * one, so a backlog cannot starve new material (spec §7). A pacing hint
     * only: new cards are still served whenever nothing is due.
     */
    const val EXPLORE_NEW_EVERY = 3

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
     * The grade for a study card answered in Simplified mode, where nobody
     * picks a grade and the answer itself decides.
     *
     * Correctness is exact match: for a multi-correct question any subset or
     * superset of the key is wrong, which is how an exam with negative marking
     * scores it. An empty selection is a skip. A declared guess that happens
     * to be right is HARD rather than GOOD: the learner did not know it, so
     * the interval must stay short — but reps still advance and no lapse is
     * counted, because producing the right answer is not a failure.
     *
     * Deliberation time on a random pick carries no information, so a guess
     * bypasses the dwell ladder entirely.
     */
    fun autoGrade(
        correctOptionIds: Set<String>,
        selection: Set<String>,
        dwellSeconds: Long,
        isGuess: Boolean,
        config: SchedulerConfig = SchedulerConfig()
    ): ReviewGrade {
        if (selection.isEmpty()) return ReviewGrade.AGAIN
        if (selection != correctOptionIds) return ReviewGrade.AGAIN
        if (isGuess) return ReviewGrade.HARD
        return inferGrade(isCorrect = true, dwellSeconds = dwellSeconds, config = config)
    }

    /** Every card eligible right now, split into the pull order's pools. */
    data class CandidatePools(
        /** Learning/relearning steps due now: short-term repetition first. */
        val learning: List<StudyCard>,
        /** Graduated cards due now, most forgettable first (lowest R wins). */
        val reviews: List<StudyCard>,
        /** Never-studied cards, in the caller's stable order. */
        val fresh: List<StudyCard>
    )

    /**
     * All eligible cards for a paper, in the three pull-based pools. A question
     * absent from [states] is new. A due leech is an ordinary review here — the
     * tricky drill ([onlyLeeches]) is a filter over this, not a separate stream.
     *
     * Order is deterministic: reviews rank by predicted recall ascending (the
     * card most likely to be forgotten first), ties by oldest due date, then by
     * the caller's order, so a reload mid-session does not reshuffle the deck.
     *
     * [introduceNewCards] off empties the fresh pool: review-only sessions.
     */
    fun pools(
        scheduler: Scheduler,
        questionIds: List<String>,
        states: Map<String, CardState>,
        now: Long,
        introduceNewCards: Boolean = true
    ): CandidatePools {
        val learning = mutableListOf<StudyCard>()
        val dueReviews = mutableListOf<Pair<StudyCard, Double>>()
        val fresh = mutableListOf<StudyCard>()

        questionIds.forEach { id ->
            val state = states[id]
            when {
                state == null || state.isNew ->
                    if (introduceNewCards) {
                        fresh += StudyCard(id, StudyReason.NEW, state ?: scheduler.initial(id))
                    }
                (state.state == CardStateKind.LEARNING ||
                    state.state == CardStateKind.RELEARNING) && state.dueAt <= now ->
                    learning += StudyCard(id, StudyReason.LEARNING, state)
                state.dueAt <= now -> {
                    val recall = scheduler.retention(state, now)
                    val reason = if (recall < LOW_RECALL_RISK_RETENTION) {
                        StudyReason.LOW_RECALL_RISK
                    } else {
                        StudyReason.DUE
                    }
                    dueReviews += StudyCard(id, reason, state) to recall
                }
            }
        }

        val reviews = dueReviews
            .sortedWith(compareBy({ it.second }, { it.first.state.dueAt }))
            .map { it.first }
        return CandidatePools(learning, reviews, fresh)
    }

    /**
     * The card a pull-based session should serve next, or null when nothing is
     * eligible: the badge, the empty state and the session itself all read the
     * same eligibility, so the UI can never promise a card the pull refuses.
     *
     * Pull order (spec §7):
     *  1. learning steps — a due repetition beats everything;
     *  2. [StudyMode.MEMORY_FIRST]: due reviews by risk, new cards only once
     *     nothing is due. [StudyMode.EXPLORE_NEW]: a new card surfaces every
     *     [EXPLORE_NEW_EVERY] reviews ([reviewsSinceNew], tracked by the
     *     session) ahead of the backlog, so exploring cannot starve memory
     *     work and memory work cannot starve exploration.
     *
     * No limits, no caps: what the badge counts is what this serves.
     */
    fun selectNext(
        scheduler: Scheduler,
        questionIds: List<String>,
        states: Map<String, CardState>,
        now: Long,
        studyMode: StudyMode = StudyMode.MEMORY_FIRST,
        introduceNewCards: Boolean = true,
        reviewsSinceNew: Int = 0
    ): StudyCard? {
        val pools = pools(scheduler, questionIds, states, now, introduceNewCards)
        pools.learning.firstOrNull()?.let { return it }
        val exploreNew = studyMode == StudyMode.EXPLORE_NEW &&
            reviewsSinceNew >= EXPLORE_NEW_EVERY
        return when {
            exploreNew && pools.fresh.isNotEmpty() -> pools.fresh.first()
            pools.reviews.isNotEmpty() -> pools.reviews.first()
            pools.fresh.isNotEmpty() -> pools.fresh.first()
            else -> null
        }
    }

    /**
     * Eligibility counts, read from the same [pools] the pull uses — the badge
     * can never disagree with what a session serves.
     */
    fun eligibility(
        scheduler: Scheduler,
        questionIds: List<String>,
        states: Map<String, CardState>,
        now: Long,
        introduceNewCards: Boolean = true
    ): StudyEligibility {
        val p = pools(scheduler, questionIds, states, now, introduceNewCards)
        val leeches = (p.learning + p.reviews).count { it.state.leech }
        return StudyEligibility(
            learning = p.learning.count { !it.state.leech },
            due = p.reviews.count { !it.state.leech },
            leeches = leeches,
            fresh = p.fresh.size
        )
    }

    /**
     * The delay each grade would actually produce for [state], keyed by grade.
     *
     * Read off [Scheduler.next] rather than recomputed, so a button can never
     * promise an interval the scheduler would not schedule. Nothing is
     * persisted: this is the same pure call the grading path makes.
     */
    fun previewDelays(
        scheduler: Scheduler,
        state: CardState,
        now: Long
    ): Map<ReviewGrade, Long> = ReviewGrade.entries.associateWith { grade ->
        scheduler.next(state, grade, now).dueAt - now
    }

    /**
     * Only the cards flagged as leeches — the tricky drill. A drill is the
     * learner explicitly asking for their problem questions, so it filters the
     * full eligibility ([pools]) rather than serving a capped stream of its own.
     */
    fun onlyLeeches(cards: List<StudyCard>): List<StudyCard> =
        cards.filter { it.reason == StudyReason.LEECH || it.state.leech }
}
