package com.mcqapp.domain.stats

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.ReviewGrade

/**
 * One graded study review enriched with what an FSRS replay can derive about
 * the moment just before it was answered. The raw columns mirror
 * `review_log`; [predictedRetention] and [intervalBeforeDays] are computed by
 * [FsrsReplay] and are what makes calibration and Anki's true-retention
 * maturity rule possible without storing anything new.
 */
data class ReplayReview(
    val paperId: String,
    val questionId: String,
    /** 1=Again, 2=Hard, 3=Good, 4=Easy. */
    val rating: Int,
    /** Epoch ms of the answer. */
    val reviewedAt: Long,
    /** Time spent on the card; null when unmeasured. */
    val elapsedSeconds: Long?,
    /** The card's kind before this answer; null when history was seeded. */
    val stateBefore: CardStateKind?,
    val stabilityAfter: Double?,
    val difficultyAfter: Double?,
    val intervalDaysAfter: Int?,
    /**
     * Predicted recall probability just before the answer, 0..1. Null for a
     * card's first review under this scheduler: there was no memory state to
     * predict from, exactly as the optimizer's training set excludes it.
     */
    val predictedRetention: Double?,
    /**
     * The whole-day interval the card carried into this review; null for a
     * first review. Drives the young/mature split (mature = 21 days or more,
     * Anki's rule).
     */
    val intervalBeforeDays: Int?
) {
    /** Again is the only failing grade; Hard, Good and Easy are passes. */
    val passed: Boolean get() = rating != ReviewGrade.AGAIN.ordinal + 1

    /** The card had survived at least one 21-day interval before this review. */
    val matureAtReview: Boolean get() = (intervalBeforeDays ?: 0) >= MATURE_DAYS

    /**
     * Where the card sat in the pipeline when it was answered. Null when the
     * log row carries no pre-state (seeded history): the kind is then simply
     * not knowable, and honest charts show it as its own slice.
     */
    val kind: ReviewKind?
        get() = when (stateBefore) {
            CardStateKind.REVIEW -> ReviewKind.REVIEW
            CardStateKind.RELEARNING -> ReviewKind.RELEARNING
            CardStateKind.LEARNING, CardStateKind.NEW -> ReviewKind.LEARNING
            null -> null
        }

    enum class ReviewKind { LEARNING, REVIEW, RELEARNING }

    companion object {
        /** Anki's maturity boundary: an interval of 21 days or more. */
        const val MATURE_DAYS = 21
    }
}

/** A card's current schedule, tagged with its paper so stats can scope it. */
data class CardSnapshot(val paperId: String, val state: CardState)

/** Chartable single-value point; [value] null draws a gap, [count] backs tooltips. */
data class SeriesPoint(val label: String, val value: Double?, val count: Int = 0)

/** Chartable stacked-bar point; [segments] aligns with the chart's legend. */
data class StackedPoint(val label: String, val segments: List<Double>, val total: Int)

/** True-retention figures for one time window, split by maturity. */
data class RetentionRow(
    val label: String,
    val allReviews: Int,
    val allPasses: Int,
    val youngReviews: Int,
    val youngPasses: Int,
    val matureReviews: Int,
    val maturePasses: Int
) {
    val allRetention: Double?
        get() = if (allReviews == 0) null else allPasses.toDouble() / allReviews
    val youngRetention: Double?
        get() = if (youngReviews == 0) null else youngPasses.toDouble() / youngReviews
    val matureRetention: Double?
        get() = if (matureReviews == 0) null else maturePasses.toDouble() / matureReviews
}

/**
 * Predicted-vs-actual recall calibration. Each bin holds reviews whose
 * predicted retention fell in a 0.1-wide band; the observed pass rate inside
 * the band is what the calibration chart plots against the band's centre.
 */
data class CalibrationData(
    val bins: List<CalibrationBin>,
    val samples: Int,
    val predictedMean: Double?,
    val actualMean: Double?,
    /** Mean squared error of prediction against the binary outcome. */
    val brier: Double?
) {
    data class CalibrationBin(
        val predictedFrom: Double,
        val predictedMean: Double,
        val actualRate: Double?,
        val count: Int
    )
}

data class ForecastDay(val label: String, val learning: Int, val review: Int)

/**
 * The next 30 days of scheduled work, Anki's Future Due: per-day bars plus
 * the cumulative count if nothing is studied from now on, and the steady-state
 * daily load the intervals imply.
 */
data class ForecastData(
    val days: List<ForecastDay>,
    /** Reviews already past due (the backlog), not counted in the bars. */
    val overdue: Int,
    /** Cards due today, backlog excluded. */
    val dueToday: Int,
    /** Sum of 1/interval over scheduled cards: the long-run reviews per day. */
    val dailyLoad: Double
) {
    /** Cumulative due count per day if the backlog is never touched. */
    val cumulativeIfSkipped: List<Int>
        get() {
            var running = overdue
            return days.map { running += it.learning + it.review; running }
        }
}

data class HeatmapDay(val dayStart: Long, val reviews: Int, val seconds: Long)

/** A GitHub-style calendar of reviews per study day, oldest first, plus streaks. */
data class HeatmapData(
    val days: List<HeatmapDay>,
    val currentStreak: Int,
    val longestStreak: Int
)

/** Where the learner's answers landed, per card maturity, Anki's Answer Buttons. */
data class AnswerButtonsData(
    val groups: List<Group>
) {
    enum class Kind { LEARNING, YOUNG, MATURE, RELEARNING }

    data class Group(
        val kind: Kind,
        val again: Int,
        val hard: Int,
        val good: Int,
        val easy: Int
    ) {
        val total: Int get() = again + hard + good + easy
        val passRate: Double?
            get() = if (total == 0) null else (total - again).toDouble() / total
    }
}

/** Reviews and pass rate for one hour of the wall clock, Anki's Hourly Breakdown. */
data class HourlyStat(val hour: Int, val reviews: Int, val passRate: Double?)

/** Answer speed analysis: distributions, per-grade averages, outliers. */
data class ResponseTimeData(
    /** Count of reviews per fixed seconds bucket. */
    val histogram: List<SeriesPoint>,
    /** Mean answer seconds per grade; null when the grade has no timed reviews. */
    val avgByGrade: List<GradeStat>,
    /** Mean answer seconds per time bucket over the selected range. */
    val trend: List<SeriesPoint>,
    /** Cards with the slowest mean answer time (minimum three timed reviews). */
    val slowest: List<WeakCard>,
    /** Again grades answered in under five seconds: impulsive failures. */
    val fastFails: Int
) {
    data class GradeStat(val grade: ReviewGrade, val reviews: Int, val avgSeconds: Double?)
}

/** A histogram over one continuous quantity, with summary statistics. */
data class Distribution(
    val bins: List<Bin>,
    val mean: Double?,
    val median: Double?
) {
    data class Bin(val label: String, val from: Double, val to: Double, val count: Int)
}

/** How memory deepened: mean stability by review number and by calendar month. */
data class MemoryGrowthData(
    val byReviewNumber: List<SeriesPoint>,
    val overTime: List<SeriesPoint>
)

/** The current shape of the collection, Anki's Card Counts. */
data class CardCountsData(
    val fresh: Int,
    val learning: Int,
    val relearning: Int,
    val young: Int,
    val mature: Int,
    val leeches: Int
) {
    val total: Int get() = fresh + learning + relearning + young + mature
}

/** Retention for one group (paper or category), for a ranked bar list. */
data class GroupRetention(val label: String, val reviews: Int, val passes: Int) {
    val retention: Double?
        get() = if (reviews == 0) null else passes.toDouble() / reviews
}

/** Retention by weekday; [weekday] is 0=Monday .. 6=Sunday. */
data class WeekdayRetention(val weekday: Int, val reviews: Int, val passes: Int) {
    val retention: Double?
        get() = if (reviews == 0) null else passes.toDouble() / reviews
}

/**
 * A card ranked by how often it is forgotten. [lapses] counts Again grades in
 * the log; [againRate] is lapses over reviews and is the ranking key.
 */
data class WeakCard(
    val paperId: String,
    val questionId: String,
    val reviews: Int,
    val lapses: Int,
    val againRate: Double,
    val avgSeconds: Double?,
    val lastReviewedAt: Long,
    val leech: Boolean
)

/** The "Today" strip, Anki-style: one answering of a card is one review. */
data class TodayStats(
    val reviews: Int,
    val againCount: Int,
    /** Share of today's reviews not answered Again; null with no reviews. */
    val correctPct: Double?,
    val timeSeconds: Long,
    val learning: Int,
    val review: Int,
    val relearning: Int,
    val unknown: Int,
    /** Cards introduced for the first time today. */
    val introduced: Int,
    /** Scheduled cards due before the day ends, new cards excluded. */
    val dueNow: Int,
    /** Scheduled cards already past due: the backlog. */
    val backlog: Int
)

/** Headline numbers for the overview grid. */
data class OverviewKpis(
    /** Reviews inside the selected range (all-time when the range is all). */
    val reviews: Int,
    /** Distinct study days with at least one review in the range. */
    val days: Int,
    val timeSeconds: Long,
    val avgAnswerSeconds: Double?,
    /** True retention over the range, all maturities. */
    val retention: Double?,
    /** True retention over the range, mature cards only. */
    val matureRetention: Double?,
    val desiredRetention: Double,
    /** Sum of current retrievability over reviewed cards: likely-remembered count. */
    val estimatedKnowledge: Int,
    val avgIntervalDays: Double?,
    val leeches: Int,
    val currentStreak: Int,
    val longestStreak: Int
)

data class RetentionTrendData(
    val points: List<SeriesPoint>,
    /** Trailing-window average smoothing out the daily noise. */
    val rolling: List<SeriesPoint>,
    val desiredRetention: Double
)

/** Whether the scheduler runs on fitted weights and how close to a refit it is. */
data class FsrsStatus(
    val fitted: Boolean,
    /** Reviews across every paper: the optimizer's unlock gauge. */
    val reviewCount: Int,
    val unlockReviews: Int,
    val desiredRetention: Double
)

/**
 * The full statistics page payload. Every section is derived independently so
 * a missing slice of history blanks one card, never the page.
 */
data class StatsBundle(
    val today: TodayStats,
    val kpis: OverviewKpis,
    val retentionTable: List<RetentionRow>,
    val heatmap: HeatmapData,
    val fsrsStatus: FsrsStatus,
    val retentionTrend: RetentionTrendData,
    val calibration: CalibrationData,
    val groupRetention: List<GroupRetention>,
    val weekdayRetention: List<WeekdayRetention>,
    val forecast: ForecastData,
    val reviewsPerDay: List<StackedPoint>,
    val timePerDay: List<SeriesPoint>,
    val answerButtons: AnswerButtonsData,
    val hourly: List<HourlyStat>,
    val responseTime: ResponseTimeData,
    val stability: Distribution,
    val difficulty: Distribution,
    val retrievability: Distribution,
    val intervals: Distribution,
    val memoryGrowth: MemoryGrowthData,
    val cardCounts: CardCountsData,
    val weakest: List<WeakCard>
)
