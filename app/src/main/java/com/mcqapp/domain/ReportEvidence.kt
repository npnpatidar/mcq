package com.mcqapp.domain

import com.mcqapp.domain.signals.CounterSet

/**
 * One question's read-only Study evidence for the result page (stats.md §11.8).
 * A card whose `contentHash` no longer matches the question is [stale]: it is
 * reported as "no Study history", not as a memory estimate.
 */
data class ReportCard(
    val questionId: String,
    /** FSRS difficulty, 1..10. */
    val difficulty: Double,
    val stability: Double,
    val reviewCount: Int,
    val lapses: Int,
    val leech: Boolean,
    val dueAt: Long,
    val lastReviewedAt: Long,
    val contentHashMatches: Boolean,
    val cardState: CardStateKind,
    /** Predicted recall probability when the card has valid memory. */
    val retention: Double? = null
) {
    val hasValidMemory: Boolean
        get() = stability > 0.0 && reviewCount > 0 &&
            cardState != CardStateKind.NEW && contentHashMatches
}

/** One question's lifetime counters, both counter sets (stats.md §9.1). */
data class ReportCounters(
    val correct: Int,
    val wrong: Int,
    val skip: Int,
    val repCorrect: Int,
    val repWrong: Int,
    val repSkip: Int
) {
    val answered: Int get() = correct + wrong
    val graded: Int get() = answered + skip
    val repAnswered: Int get() = repCorrect + repWrong
    val repGraded: Int get() = repAnswered + repSkip
}

/**
 * The extra evidence the phase-2 report reads. It is optional so a legacy
 * caller (and a test that only exercises the phase-1 fields) can omit it.
 */
data class ReportEvidence(
    val cards: Map<String, ReportCard> = emptyMap(),
    val counters: Map<String, ReportCounters> = emptyMap(),
    /** Question ids this attempt asked that an earlier attempt already asked. */
    val testedBefore: Set<String> = emptySet(),
    /** Latest graded, answered standing before this attempt: true = correct. */
    val priorStanding: Map<String, Boolean> = emptyMap(),
    /** Distinct questions in the scope tested before this attempt. */
    val testedInScope: Int = 0,
    /** Mistakes in the scope before this attempt. */
    val scopeMistakesBefore: Int = 0,
    /** Current size of each recorded target category, for practice coverage. */
    val targetSizes: Map<String, Int> = emptyMap(),
    /** Current titles of the recorded targets, so a missed target still names. */
    val targetTitles: Map<String, String> = emptyMap(),
    val now: Long = 0L
)

/** One cell of the Study-estimate vs Test-accuracy quadrant (stats.md §11.8). */
data class MemoryQuadrant(
    /** FSRS difficulty at or above 5.5. */
    val hard: Boolean,
    val correct: Boolean,
    val count: Int
)

/** The FSRS memory-vs-performance evidence for one attempt. */
data class MemoryEvidence(
    val quadrants: List<MemoryQuadrant>,
    /** Questions with no card, a new card or a stale hash. */
    val noStudy: Int,
    /** Of the attempt's questions, how many are due in Study now. */
    val dueNow: Int,
    val validCards: Int,
    /** Mean predicted recall probability over valid cards, or null. */
    val meanRetrievability: Double?
)

/** Coverage and repeat evidence for one attempt (stats.md §11.10). */
data class CoverageEvidence(
    /** Questions with a selection or nonzero dwell. */
    val engaged: Int,
    val scopeSize: Int,
    val unseenRemaining: Int,
    val repeats: Int,
    val dueNow: Int
)

/** Mistakes added, cleared and remaining after this attempt (stats.md §11.10). */
data class MistakeEvidence(
    val added: Int,
    val cleared: Int,
    val remaining: Int
)

/**
 * One question's lifetime accuracy in this report's context. [counterSet]
 * says whether the number came from the representative `rep*` counters or
 * from every attempt, because the same question can legitimately show two
 * different values in a mock and a practice report.
 */
data class LifetimeAccuracy(
    val correct: Int,
    val answered: Int,
    val counterSet: CounterSet
) {
    val percentage: Double
        get() = if (answered > 0) correct.toDouble() / answered * 100.0 else 0.0
}

/** One targeted category's accuracy in a practice attempt (stats.md §11.3). */
data class PracticeTarget(
    val categoryId: String,
    val title: String,
    val asked: Int,
    val correct: Int,
    /** Current size of the category's pool; 0 when the target no longer exists. */
    val poolSize: Int
) {
    val accuracy: Double get() = if (asked > 0) correct.toDouble() / asked else 0.0
}

/**
 * The practice headline (stats.md §11.3): the target is the hero, the overall
 * score is secondary. A practice run is selected on the dependent variable, so
 * it never claims mock comparability.
 */
data class PracticeEvidence(
    val targets: List<PracticeTarget>,
    /** Questions asked from the recorded targets. */
    val targetedAsked: Int,
    val targetedCorrect: Int,
    /** Current total size of the recorded targets. */
    val targetedPool: Int,
    /** The attempt's whole-scope accuracy, kept as the secondary number. */
    val overallAccuracy: Double,
    val overallAsked: Int,
    /** `true` when the attempt recorded no targets (a legacy practice run). */
    val withoutTargets: Boolean
) {
    val targetedAccuracy: Double
        get() = if (targetedAsked > 0) targetedCorrect.toDouble() / targetedAsked else 0.0

    /** Targeted questions covered out of the current target pool. */
    val coverage: Double
        get() = if (targetedPool > 0) targetedAsked.toDouble() / targetedPool else 0.0
}

/**
 * One practice run's targeted accuracy, on a fixed recorded target set
 * (stats.md §11.5). Practice runs are never compared with a mock; this line
 * only answers "is this target set improving?".
 */
data class PracticeTrendPoint(
    val attemptId: Long,
    val finishedAt: Long,
    val asked: Int,
    val correct: Int
) {
    val accuracy: Double get() = if (asked > 0) correct.toDouble() / asked else 0.0
}

/** The practice target trend: prior same-target runs plus this one. */
data class PracticeTrend(
    val points: List<PracticeTrendPoint>
) {
    val deltaPoints: Double?
        get() {
            if (points.size < 2) return null
            return (points.last().accuracy - points[points.size - 2].accuracy) * 100.0
        }
}

/**
 * Why a reviewed question matters (stats.md §11.12): the tag names the failure
 * mode, and the Study fields reuse the card the memory card already read.
 */
enum class ReviewTag {
    KNOWLEDGE_GAP,
    RUSHED,
    SKIPPED,
    POSSIBLE_GUESS,
    FRAGILE_CORRECT,
    SOLID_CORRECT,
    UNGRADED
}

/** One question's review evidence: the "why this matters" tag and Study state. */
data class QuestionReview(
    val tag: ReviewTag,
    /** A valid FSRS card exists (not new, not stale). */
    val inStudy: Boolean = false,
    val dueNow: Boolean = false,
    val leech: Boolean = false,
    /** FSRS difficulty, only when a valid card exists. */
    val difficulty: Double? = null,
    val retention: Double? = null,
    /** The question belongs to a recorded practice target. */
    val targeted: Boolean = false,
    /** The question changed since its Study card was written. */
    val staleCard: Boolean = false,
    /** A card exists but has never been reviewed (a new card). */
    val newCard: Boolean = false,
    /**
     * Point-biserial discrimination across same-family attempts, when at least
     * [com.mcqapp.domain.stats.Reliability.MIN_DISCRIMINATION_ATTEMPTS] asked
     * the question. Null means "no estimate", never "zero".
     */
    val discrimination: Double? = null,
    val discriminationFlag: com.mcqapp.domain.stats.Reliability.Discrimination.Flag? = null
)

/** One point on the comparable trend line (stats.md §11.5). */
data class TrendPoint(
    val attemptId: Long,
    val percentage: Double,
    val marginPoints: Double?,
    val finishedAt: Long
)

/**
 * A trend is drawn only from comparable, trend-worthy attempts. A Fresh
 * sample gets its own line and the approximate note; a practice attempt never
 * enters a mock line.
 */
data class TrendEvidence(
    val points: List<TrendPoint>,
    val deltaPoints: Double,
    /** The change is inside the margin, so it may be noise. */
    val withinNoise: Boolean,
    val freshSample: Boolean
) {
    /** One point is a reference, not a line (section 11.5). */
    val isLine: Boolean get() = points.size >= 2
}

/**
 * A seeded bootstrap interval for the score (stats.md §11.5), resampling
 * passages rather than questions so a comprehension set is never split.
 */
data class ScoreInterval(
    val lowPercent: Double,
    val highPercent: Double,
    val resamples: Int,
    val passageBlocks: Int
) {
    val widthPoints: Double get() = highPercent - lowPercent
}
