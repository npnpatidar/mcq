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
    val intervalBeforeDays: Int?,
    /**
     * Whether the learner declared this attempt a guess before answering.
     * Null for log rows written before v19: they carry no flag, and the
     * guessing statistics deliberately exclude them rather than assume.
     */
    val wasGuess: Boolean? = null,
    /** Whether the chosen options matched the key, recorded at answer time. */
    val wasCorrect: Boolean? = null,
    /**
     * The interval Good would have produced from the same pre-answer state.
     * Only computed for guessed-correct rows (the counterfactual the guessing
     * dashboard prices); null everywhere else.
     */
    val wouldBeGoodIntervalDays: Int? = null
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

/**
 * One logged review, for the per-card drill-down history. The raw log row in
 * a domain shape: no replay enrichment, just what was recorded.
 */
data class ReviewHistoryEntry(
    /** 1=Again, 2=Hard, 3=Good, 4=Easy. */
    val rating: Int,
    val reviewedAt: Long,
    val elapsedSeconds: Long?,
    val stateBefore: CardStateKind?,
    val stabilityAfter: Double?,
    val difficultyAfter: Double?,
    val intervalDaysAfter: Int?
)

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

/**
 * How stale the scheduled backlog is: cards past due bucketed by age in
 * whole days. [bins] covers the full overdue set, [overdue] is their total.
 */
data class OverdueAgeData(
    val bins: List<OverdueAgeBin>,
    val overdue: Int,
    val meanAgeDays: Double?,
    val oldestAgeDays: Int?
) {
    data class OverdueAgeBin(
        val label: String,
        val fromDays: Int,
        /** Exclusive upper bound in days; null for the open-ended last bin. */
        val toDays: Int?,
        val count: Int
    )
}

/** Card counts for one paper, for the cross-paper comparison table. */
data class PaperCardCounts(
    val paperId: String,
    val label: String,
    val total: Int,
    val fresh: Int,
    val learning: Int,
    val relearning: Int,
    val young: Int,
    val mature: Int
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

/** The maturity band a review happened in, for the guessing breakdown. */
enum class GuessBand { FRESH, LEARNING, RELEARNING, YOUNG, MATURE }

/**
 * Guess and hit counts for one slice of the log — a trend bucket, a sitting
 * position, a topic. Counts rather than percentages, so slices pool correctly
 * and a two-review bucket cannot outweigh a forty-review one.
 */
data class GuessSlice(
    val label: String,
    val reviews: Int,
    val guessed: Int,
    val guessedCorrect: Int
) {
    val guessRate: Double? get() = if (reviews == 0) null else guessed.toDouble() / reviews
    val hitRate: Double? get() = if (guessed == 0) null else guessedCorrect.toDouble() / guessed
}

/** One maturity band's guess/hit counts; the UI resolves [band] to a label. */
data class GuessBandSlice(
    val band: GuessBand,
    val reviews: Int,
    val guessed: Int,
    val guessedCorrect: Int
) {
    val guessRate: Double? get() = if (reviews == 0) null else guessed.toDouble() / reviews
    val hitRate: Double? get() = if (guessed == 0) null else guessedCorrect.toDouble() / guessed
}

/** Guess counts for one hour of the local day (0..23). */
data class HourlyGuess(
    val hour: Int,
    val reviews: Int,
    val guessed: Int,
    val guessedCorrect: Int
) {
    val guessRate: Double? get() = if (reviews == 0) null else guessed.toDouble() / reviews
}

/** Mean answer seconds on flagged rows: declared guesses versus earned answers. */
data class GuessDwell(
    val guessedReviews: Int,
    val guessedAvgSeconds: Double?,
    val earnedReviews: Int,
    val earnedAvgSeconds: Double?
)

/**
 * What the lucky picks cost the schedule: the interval Good would have given
 * minus the interval the card actually received, summed over guessed-correct
 * reviews that could be replayed.
 */
data class GuessCost(
    val reviews: Int,
    val extraDays: Int,
    val avgActualDays: Double?,
    val avgGoodDays: Double?
)

/** One paper's or category's guess record, for the fragile-topic ranking. */
data class GuessTopic(
    val label: String,
    val reviews: Int,
    val guessed: Int,
    val guessedCorrect: Int
) {
    val guessRate: Double? get() = if (reviews == 0) null else guessed.toDouble() / reviews
    val hitRate: Double? get() = if (guessed == 0) null else guessedCorrect.toDouble() / guessed
}

/** A card that is guessed often, ranked for the shaky list. */
data class ShakyCard(
    val paperId: String,
    val questionId: String,
    val reviews: Int,
    val guessed: Int,
    val guessedCorrect: Int,
    val lastReviewedAt: Long
) {
    val guessRate: Double? get() = if (reviews == 0) null else guessed.toDouble() / reviews
    val hitRate: Double? get() = if (guessed == 0) null else guessedCorrect.toDouble() / guessed
}

/**
 * The guessing dashboard's payload. Only reviews that carry the flag are
 * classified; [allReviews] says how much of the in-range history that leaves
 * out, so the screen can state it rather than silently shrink the sample.
 *
 * The four outcomes are the 2×2 of declared guess × correctness: [earned]
 * (knew it), [lucky] (guessed right), [lapse] (knew it was wrong / didn't know
 * and said so), [didntKnow] (guessed wrong). [earned] is what the scheduler's
 * grade can honestly claim; [lucky] is what raw accuracy adds on top.
 */
data class GuessingData(
    val allReviews: Int,
    val reviews: Int,
    val guessed: Int,
    val earned: Int,
    val lucky: Int,
    val lapse: Int,
    val didntKnow: Int,
    /** Mean chance a uniform random pick would have been right, over guesses. */
    val chanceBaseline: Double?,
    val trend: List<GuessSlice>,
    val maturity: List<GuessBandSlice>,
    val positions: List<GuessSlice>,
    val hourly: List<HourlyGuess>,
    val dwell: GuessDwell,
    val cost: GuessCost,
    val topics: List<GuessTopic>,
    val shaky: List<ShakyCard>
) {
    /** Share of flagged reviews where the learner declared a guess. */
    val guessRate: Double? get() = if (reviews == 0) null else guessed.toDouble() / reviews

    /** Share of guesses that happened to be right. */
    val hitRate: Double? get() = if (guessed == 0) null else lucky.toDouble() / guessed

    /** Every right answer, lucky or earned. */
    val rawAccuracy: Double?
        get() = if (reviews == 0) null else (earned + lucky).toDouble() / reviews

    /** Only the answers the learner actually knew. */
    val gradedPassRate: Double?
        get() = if (reviews == 0) null else earned.toDouble() / reviews

    /** Hit rate above (or below) random picking, in percentage points. */
    val lift: Double? get() = hitRate?.let { hit -> chanceBaseline?.let { hit - it } }

    val hasData: Boolean get() = reviews > 0

    /** False when some in-range history predates the v19 flag columns. */
    val isComplete: Boolean get() = allReviews == reviews
}

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
    val overdueAge: OverdueAgeData,
    /** Per-paper card counts; empty when the view is scoped to one paper. */
    val paperCounts: List<PaperCardCounts>,
    val weakest: List<WeakCard>,
    val leeches: List<WeakCard>,
    val guessing: GuessingData
)

/**
 * One tab's slice of the page, so a tab can be loaded (and re-sliced) on its
 * own instead of dragging the whole log behind it. Each tab of the statistics
 * screen reads exactly one of these; see [StatsSections].
 */
data class OverviewData(
    val today: TodayStats,
    val kpis: OverviewKpis,
    val retentionTable: List<RetentionRow>,
    val heatmap: HeatmapData,
    val fsrsStatus: FsrsStatus,
    val weakest: List<WeakCard>
)

/** Retention tab: true-retention trends and calibration. */
data class RetentionData(
    val retentionTrend: RetentionTrendData,
    val calibration: CalibrationData,
    val groupRetention: List<GroupRetention>,
    val weekdayRetention: List<WeekdayRetention>
)

/** Workload tab: how the review volume and pace are distributed. */
data class WorkloadData(
    val forecast: ForecastData,
    val reviewsPerDay: List<StackedPoint>,
    val timePerDay: List<SeriesPoint>,
    val answerButtons: AnswerButtonsData,
    val hourly: List<HourlyStat>,
    val responseTime: ResponseTimeData
)

/** Memory tab: where the collection sits in the FSRS state space. */
data class MemoryData(
    val kpis: OverviewKpis,
    val stability: Distribution,
    val difficulty: Distribution,
    val retrievability: Distribution,
    val intervals: Distribution,
    val memoryGrowth: MemoryGrowthData
)

/** Cards tab: the collection's shape and the cards that need attention. */
data class CardsData(
    val cardCounts: CardCountsData,
    val overdueAge: OverdueAgeData,
    val paperCounts: List<PaperCardCounts>,
    val leeches: List<WeakCard>
)

/**
 * The whole page, one entry per tab. Kept alongside [StatsBundle] so the
 * existing all-at-once payload stays available while tabs move to loading
 * their own slice (§4.9 item 3).
 */
data class StatsSections(
    val overview: OverviewData,
    val retention: RetentionData,
    val workload: WorkloadData,
    val memory: MemoryData,
    val cards: CardsData,
    val guessing: GuessingData
)

/** Flattens the per-tab slices back into the whole-page payload. */
fun StatsSections.toBundle(): StatsBundle = StatsBundle(
    today = overview.today,
    kpis = overview.kpis,
    retentionTable = overview.retentionTable,
    heatmap = overview.heatmap,
    fsrsStatus = overview.fsrsStatus,
    retentionTrend = retention.retentionTrend,
    calibration = retention.calibration,
    groupRetention = retention.groupRetention,
    weekdayRetention = retention.weekdayRetention,
    forecast = workload.forecast,
    reviewsPerDay = workload.reviewsPerDay,
    timePerDay = workload.timePerDay,
    answerButtons = workload.answerButtons,
    hourly = workload.hourly,
    responseTime = workload.responseTime,
    stability = memory.stability,
    difficulty = memory.difficulty,
    retrievability = memory.retrievability,
    intervals = memory.intervals,
    memoryGrowth = memory.memoryGrowth,
    cardCounts = cards.cardCounts,
    overdueAge = cards.overdueAge,
    paperCounts = cards.paperCounts,
    weakest = overview.weakest,
    leeches = cards.leeches,
    guessing = guessing
)
