package com.mcqapp.domain

import kotlin.math.ceil

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
    /**
     * Minimum correct-answer seconds that still infers Easy. Long questions
     * receive reading time on top of this floor.
     */
    val fastSeconds: Long = 8L,
    /** Correct answer slower than this (seconds) infers Hard from history. */
    val slowSeconds: Long = 30L,
    /**
     * The 21 FSRS-6 weights scheduling runs on, in reference order. Defaults
     * to the official unoptimized set; "Optimize FSRS parameters" in Settings
     * replaces them with [FsrsOptimizer]'s fit to this learner's own review
     * history. A malformed list (wrong length, non-finite values) falls back
     * to the defaults in [sanitized], so a corrupt preference can never break
     * scheduling; well-formed lists are clipped to the official bounds.
     */
    val fsrsParameters: List<Double> = FsrsScheduler.DEFAULT_FSRS_PARAMETERS,
    /**
     * The ladder version the cut points belong to (v26). 0 means no version
     * has been written yet — every pre-v26 review was graded without one.
     */
    val ladderVersion: Int = 0,
    /**
     * Which weight set [fsrsParameters] belongs to (v26). 0 is the shipped
     * default set; every refit bumps it, so a review cannot claim a forecast
     * that a different fit produced.
     */
    val fsrsFitVersion: Int = 0
) {
    /** Guards against values that would break scheduling entirely. */
    fun sanitized(): SchedulerConfig = copy(
        maxIntervalDays = maxIntervalDays.coerceIn(1, 36500),
        relearnMs = relearnMs.coerceIn(60_000L, 24 * 60 * 60 * 1000L),
        leechThreshold = leechThreshold.coerceIn(1, 100),
        desiredRetention = desiredRetention.coerceIn(0.75, 0.97),
        dayStartHour = dayStartHour.coerceIn(0, 23),
        fastSeconds = fastSeconds.coerceIn(1, 3600),
        slowSeconds = slowSeconds.coerceIn(fastSeconds.coerceIn(1, 3600), 3600),
        ladderVersion = ladderVersion.coerceAtLeast(0),
        fsrsFitVersion = fsrsFitVersion.coerceAtLeast(0),
        fsrsParameters = fsrsParameters
            .takeIf { list ->
                list.size == FsrsScheduler.PARAMETER_COUNT && list.all { it.isFinite() }
            }
            ?.let { FsrsOptimizer.clip(it) }
            ?: FsrsScheduler.DEFAULT_FSRS_PARAMETERS
    )
}

/**
 * Scheduler-agnostic study loop: what to pull next, and grade inference.
 *
 * These rules are about *what to study*, not *how memory decays*, so they stay
 * shared no matter which [Scheduler] is in use.
 */
/** Where one review's saved grade came from (v26, `review_log.gradeSource`). */
enum class GradeSource(val stored: String) {
    /** The app's proposal was saved unchanged. */
    AUTO("AUTO"),

    /** The learner changed the app's proposal before saving it. */
    EDITED("EDITED"),

    /** Classic mode: the learner chose a grade and the app proposed none. */
    CHOSEN("CHOSEN")
}

/**
 * The provenance rules the calibration depends on, kept pure so they can be
 * tested without a database. They are deliberately small: the ladder measures
 * only AUTO labels, and a row that claims AUTO when it was edited would
 * contaminate every statistic downstream.
 */
object GradeProvenance {

    /**
     * [edited] is sticky. A learner who changes Easy to Good and back saves a
     * grade equal to the proposal, but the row is still EDITED: it is a
     * decision that went through the learner, not the app's output. Deriving
     * the source from the two grades alone cannot see that case, which is why
     * the session tracks the edit rather than recomputing it here.
     */
    fun source(proposed: ReviewGrade?, edited: Boolean): GradeSource = when {
        proposed == null -> GradeSource.CHOSEN
        edited -> GradeSource.EDITED
        else -> GradeSource.AUTO
    }

    /**
     * The forecast a review row may store, from the card state immediately
     * before the answer. Two nulls are deliberate:
     *
     * - a new card has no memory, and `retention` returns 0.0 for it — a
     *   stored 0.0 is indistinguishable from a genuine near-zero prediction;
     * - an imported card with no review timestamp but a positive stability
     *   makes `retention` repeat the schedule's own assumption back, which is
     *   not a forecast.
     */
    fun predictedRecall(
        state: CardState?,
        now: Long,
        retention: (CardState, Long) -> Double
    ): Double? {
        if (state == null) return null
        if (state.state == CardStateKind.NEW) return null
        if (state.lastReviewedAt == 0L && state.stability > 0.0) return null
        return retention(state, now)
    }
}

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
     * [fastSeconds] and [slowSeconds] override one end each of the ladder;
     * callers that extend one for reading time must extend the other by the
     * same amount, or Good becomes unreachable on long questions.
     */
    fun inferGrade(
        isCorrect: Boolean,
        dwellSeconds: Long,
        skipped: Boolean = false,
        config: SchedulerConfig = SchedulerConfig(),
        fastSeconds: Long = config.fastSeconds,
        slowSeconds: Long = config.slowSeconds
    ): ReviewGrade {
        if (skipped || !isCorrect) return ReviewGrade.AGAIN
        if (dwellSeconds in 1 until fastSeconds) return ReviewGrade.EASY
        if (dwellSeconds >= slowSeconds) return ReviewGrade.HARD
        return ReviewGrade.GOOD
    }

    /**
     * The grade for a study card answered in Simplified mode, where nobody
     * picks a grade and the answer itself decides.
     *
     * Correctness is exact match: for a multi-correct question any subset or
     * superset of the key is wrong, which is how an exam with negative marking
     * scores it. An empty selection is a skip. A declared guess that happens
     * to be right is Again: the learner did not know it, so FSRS treats it as
     * a failed recall even though the chosen answer matched.
     *
     * Deliberation time on a random pick carries no information, so a guess
     * bypasses the dwell ladder entirely.
     */
    fun autoGrade(
        correctOptionIds: Set<String>,
        selection: Set<String>,
        dwellSeconds: Long,
        isGuess: Boolean,
        config: SchedulerConfig = SchedulerConfig(),
        fastSeconds: Long = config.fastSeconds,
        slowSeconds: Long = config.slowSeconds
    ): ReviewGrade {
        if (selection.isEmpty()) return ReviewGrade.AGAIN
        if (selection != correctOptionIds) return ReviewGrade.AGAIN
        if (isGuess) return ReviewGrade.AGAIN
        return inferGrade(
            isCorrect = true,
            dwellSeconds = dwellSeconds,
            config = config,
            fastSeconds = fastSeconds,
            slowSeconds = slowSeconds
        )
    }

    /** Assumed comfortable reading speed, in visible characters per second. */
    private const val READ_CHARS_PER_SECOND = 20.0

    /** Reading time added per image in the stem, options, or passage. */
    private const val IMAGE_SECONDS = 3L

    /**
     * The Easy threshold for one card: the configured minimum plus the time
     * needed to read the visible material. Counts the stem, every option, and
     * the passage title/body because the learner must read all of it before
     * answering.
     */
    fun easyThresholdSeconds(
        config: SchedulerConfig,
        question: Question,
        passage: Passage?
    ): Long {
        val readableLength =
            question.elements.searchableText.length +
                question.options.sumOf { it.elements.searchableText.length } +
                (passage?.elements?.searchableText?.length ?: 0) +
                (passage?.title?.length ?: 0)
        val imageCount =
            (if (question.image != null) 1 else 0) +
                question.options.count { it.image != null } +
                (if (passage?.image != null) 1 else 0)
        val readingBonus = ceil(
            (readableLength / READ_CHARS_PER_SECOND) + (imageCount * IMAGE_SECONDS).toDouble()
        ).toLong()
        return config.fastSeconds + readingBonus
    }

    /**
     * The two dwell thresholds a card is graded against: Easy below the first,
     * Hard at or above the second, Good between them. Reading time lifts both
     * ends by the same amount, so Good stays reachable on a long question.
     *
     * The Simplified-mode check grades from this pair and the header clock
     * colours itself from the same pair, so the two can never disagree about
     * where a card sits on the ladder.
     */
    data class DwellLadder(val easySeconds: Long, val hardSeconds: Long)

    fun dwellLadder(
        config: SchedulerConfig,
        question: Question,
        passage: Passage?
    ): DwellLadder {
        val easy = easyThresholdSeconds(config, question, passage)
        return DwellLadder(
            easySeconds = easy,
            hardSeconds = config.slowSeconds + (easy - config.fastSeconds)
        )
    }

    /**
     * The bands of the dwell ladder, for the study clock's colour. [NEUTRAL] is
     * the first second only: no time is banked yet, so the clock makes no
     * colour claim until it has something to claim.
     */
    enum class DwellBand { NEUTRAL, EASY, GOOD, HARD }

    /**
     * Where [dwellSeconds] sits on the ladder described by [easySeconds] and
     * [hardSeconds]. From one second up, the boundaries match [inferGrade]
     * exactly. At 0 the clock is neutral: grade inference treats 0 as untracked
     * timing and calls it Good, while a live clock that has not banked its first
     * second should not yet claim any band at all.
     */
    fun dwellBand(
        dwellSeconds: Long,
        easySeconds: Long,
        hardSeconds: Long
    ): DwellBand = when {
        dwellSeconds <= 0 -> DwellBand.NEUTRAL
        dwellSeconds < easySeconds -> DwellBand.EASY
        dwellSeconds < hardSeconds -> DwellBand.GOOD
        else -> DwellBand.HARD
    }

    /**
     * The whole-second dwell a check freezes and stores: milliseconds are the
     * source of truth, rounded half-up once, at the moment the grade is
     * proposed. The clock display truncates instead, because a clock reading
     * 3 s then 4 s is what a learner expects.
     */
    fun gradedDwellSeconds(elapsedMillis: Long): Long =
        (elapsedMillis.coerceAtLeast(0L) + 500L) / 1000L

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
     * The first card of the pools' review pool, for a caller that could only
     * read one candidate per chunk: the same ranking as that pool (lowest
     * predicted retention, then oldest due date), so the app still has one
     * comparator rather than a second copy of it.
     *
     * [candidates] are expected to be due review cards. A learning card is not
     * returned (pools puts it in the learning pool), so a caller holding a mix
     * must filter to reviews first — as the scoped pull does, one winner per
     * chunk. Candidates arrive in the caller's order and a tie on
     * (retention, dueAt) keeps that order; the SQL tie-break instead falls
     * through to the authored order, so an exact tie across a chunk boundary
     * may serve the other card.
     */
    fun bestReview(
        scheduler: Scheduler,
        candidates: List<StudyCard>,
        now: Long
    ): StudyCard? =
        pools(
            scheduler,
            candidates.map { it.questionId },
            candidates.associate { it.questionId to it.state },
            now
        ).reviews.firstOrNull()

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
        return pickNext(pools.learning, pools.reviews, pools.fresh.firstOrNull(), studyMode, reviewsSinceNew)
    }

    /**
     * [selectNext] over pools whose candidates were already narrowed: the
     * learning and review pools of the stored schedules, and the *first* new
     * card in authored order.
     *
     * A caller that cannot hold a whole-paper model reads those three from the
     * database instead of building every question's card — and the pull order,
     * which is the part worth having in one place, is this one.
     */
    fun pickNext(
        learning: List<StudyCard>,
        reviews: List<StudyCard>,
        freshFirst: StudyCard?,
        studyMode: StudyMode = StudyMode.MEMORY_FIRST,
        reviewsSinceNew: Int = 0
    ): StudyCard? {
        learning.firstOrNull()?.let { return it }
        val exploreNew = studyMode == StudyMode.EXPLORE_NEW &&
            reviewsSinceNew >= EXPLORE_NEW_EVERY
        return when {
            exploreNew && freshFirst != null -> freshFirst
            reviews.isNotEmpty() -> reviews.first()
            freshFirst != null -> freshFirst
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
