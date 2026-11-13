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
    /**
     * The card's kind before this answer. Null only when the row carries no
     * usable recorded pre-state: a first review logged before the writer
     * recorded New explicitly, or a legacy value the current build cannot
     * read. First reviews still count as introductions through
     * [intervalBeforeDays]'s null.
     */
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

/**
 * The per-card sheet's headline figures (plan §8.2), computed from the history
 * the sheet already loaded — no extra query. [meanSeconds] uses the timed
 * reviews only.
 */
data class CardHistorySummary(
    val reviews: Int = 0,
    val again: Int = 0,
    val meanSeconds: Double? = null
) {
    val againRate: Double? get() = if (reviews == 0) null else again.toDouble() / reviews

    companion object {
        fun of(history: List<ReviewHistoryEntry>): CardHistorySummary {
            if (history.isEmpty()) return CardHistorySummary()
            var again = 0
            var timedSeconds = 0L
            var timedCount = 0
            history.forEach { entry ->
                if (entry.rating == ReviewGrade.AGAIN.ordinal + 1) again++
                entry.elapsedSeconds?.let {
                    timedSeconds += it
                    timedCount++
                }
            }
            return CardHistorySummary(
                reviews = history.size,
                again = again,
                meanSeconds = if (timedCount == 0) null else timedSeconds.toDouble() / timedCount
            )
        }
    }
}

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
    val dailyLoad: Double,
    /** Scheduled cards due in the next study day (plan §7). */
    val dueTomorrow: Int = 0,
    /** Scheduled cards due in the next seven days, backlog excluded. */
    val dueThisWeek: Int = 0
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
    val learning: Int,
    val relearning: Int,
    val young: Int,
    val mature: Int
)

/**
 * The current shape of the collection, Anki's Card Counts.
 *
 * A card with no schedule is not a card this tab can describe: it has never
 * been studied, so it has no state, interval or maturity to count. Those rows
 * are excluded rather than shown as "New"; [total] is the four states that
 * actually exist.
 */
data class CardCountsData(
    val learning: Int,
    val relearning: Int,
    val young: Int,
    val mature: Int,
    val leeches: Int
) {
    val total: Int get() = learning + relearning + young + mature
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
 * The whole-day interval a card carried into a review (plan §6.1). [FIRST] is
 * a card's first logged review, which has no interval to qualify with.
 */
enum class IntervalBand { FIRST, D1_7, D8_21, D22_60, D61_180, D181_PLUS }

/** Retention counts for one [IntervalBand]; every band is always reported. */
data class IntervalBandRetention(
    val band: IntervalBand,
    val reviews: Int,
    val passes: Int
) {
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
    val guessing: GuessingData,
    val progress: ProgressData,
    /** Session-level charts, and the history entries when a caller asked. */
    val sessions: SessionsData = SessionsData()
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
    val weekdayRetention: List<WeekdayRetention>,
    /** Plan §6.1: every band, weakest first is left to the UI's fixed order. */
    val intervalRetention: List<IntervalBandRetention> = emptyList(),
    /** Plan §6.2: per tag, a multi-tag review counting in each. */
    val tagRetention: List<GroupRetention> = emptyList(),
    /** Plan §6.3: per category across every paper; empty when scoped. */
    val categoryRetention: List<GroupRetention> = emptyList(),
    /**
     * True when [groupRetention] lists papers (no paper selected); false when
     * a paper is selected and the rows are its categories.
     */
    val groupIsPaper: Boolean = true,
    /**
     * Grade-level calibration (phase 2): the adjacent contrasts, the per-grade
     * table and the readiness gate. Null only when the surface did not ask for
     * it; empty reports are still rendered, because "not enough evidence" is
     * the expected answer for months and must be visible.
     */
    val calibrationReport: Calibration.Report? = null,
    /**
     * The read-only pair the calibration report's direction implies (v27).
     * Null when the surface did not ask for a report; present but
     * `NEEDS_EVIDENCE` while the gates are closed, because "not yet" is an
     * answer the learner should see.
     */
    val cutSuggestion: Calibration.CutSuggestion? = null,
    /**
     * The pair the learner's own answer times imply, shown while correctness-only
     * grading is on. Null under timed grading, where the contrast suggestion
     * above is the one that applies.
     */
    val dwellSuggestion: Calibration.CutSuggestion? = null,
    /**
     * Whether the grade came from the answer clock (v27). False under
     * correctness-only grading: the calibration card measures the two cuts, so
     * it has nothing to show and is hidden instead of staying empty.
     */
    val timeBasedGrading: Boolean = true
)

/** Grade mix in one bucket (plan §7); the four counts always add to [total]. */
data class GradeMixPoint(
    val label: String,
    val again: Int,
    val hard: Int,
    val good: Int,
    val easy: Int
) {
    val total: Int get() = again + hard + good + easy
}

/**
 * Sittings split by a 30-minute gap (plan §7). Lengths are wall-clock minutes
 * from a sitting's first review to its last; [meanSecondsPerReview] uses the
 * timed reviews only. The histogram buckets the reviews in each sitting.
 */
data class SessionSummary(
    val count: Int = 0,
    val meanReviews: Double? = null,
    val meanMinutes: Double? = null,
    val longestMinutes: Double? = null,
    val meanSecondsPerReview: Double? = null,
    val histogram: List<SessionBin> = emptyList()
) {
    data class SessionBin(val label: String, val count: Int)
}

/** One paper or topic's share of a sitting, for the history list. */
data class SessionSlice(val label: String, val reviews: Int)

/**
 * One inferred study sitting: consecutive graded reviews no more than 30
 * minutes apart, the same rule the Workload tab's summary uses. [startedAt]
 * and [endedAt] are the first and last review's wall-clock times, so
 * [durationSeconds] includes the pauses inside the sitting but never the time
 * after it ended.
 *
 * [questions] is the distinct cards reviewed; [reviews] counts every answer,
 * because a lapsed card can be served twice inside one sitting.
 */
data class StudySession(
    val startedAt: Long,
    val endedAt: Long,
    /** Study day (the configured 04:00 boundary) the sitting started on. */
    val day: Long,
    val reviews: Int,
    val questions: Int,
    val again: Int,
    val hard: Int,
    val good: Int,
    val easy: Int,
    /** Per-review dwell time; unmeasured reviews contribute nothing. */
    val timedSeconds: Long,
    val timedReviews: Int,
    val papers: List<SessionSlice>,
    /** Topic titles, newest map read; empty when the caller did not ask. */
    val categories: List<SessionSlice> = emptyList()
) {
    val durationSeconds: Long
        get() = ((endedAt - startedAt) / 1000L).coerceAtLeast(0L)

    /** Reviews not answered Again: the same pass rule as the review stats. */
    val passed: Int get() = reviews - again

    val accuracy: Double?
        get() = if (reviews == 0) null else passed.toDouble() / reviews

    val meanSecondsPerReview: Double?
        get() = if (timedReviews == 0) null else timedSeconds.toDouble() / timedReviews
}

/** One hour of the day and how many sittings started in it. */
data class SessionHour(val hour: Int, val sessions: Int)

/** 0 = Monday .. 6 = Sunday, and how many sittings started that weekday. */
data class SessionWeekday(val weekday: Int, val sessions: Int)

/**
 * The Sessions tab's charts and, when asked for, the sittings themselves.
 * [sessions] is empty for the statistics tab, which only charts the aggregate;
 * the sidebar history asks for the list and pays for it.
 */
data class SessionsData(
    val totalSessions: Int = 0,
    val totalReviews: Int = 0,
    /** Summed wall-clock length of every sitting, in seconds. */
    val totalSeconds: Long = 0L,
    val sessionsPerDay: List<StackedPoint> = emptyList(),
    /** How long sittings ran: `0–4m`, `5–14m`, ... */
    val durationHistogram: List<SeriesPoint> = emptyList(),
    val startHours: List<SessionHour> = emptyList(),
    val weekdays: List<SessionWeekday> = emptyList(),
    /** Mean sitting accuracy per time bucket; each sitting weighs the same. */
    val accuracyTrend: List<SeriesPoint> = emptyList(),
    /** Study epoch day of the build, for the history list's Today header. */
    val todayDay: Long = 0L,
    /** Newest first; empty unless the build collected entries. */
    val sessions: List<StudySession> = emptyList()
)

/** Workload tab: how the review volume and pace are distributed. */
data class WorkloadData(
    val forecast: ForecastData,
    val reviewsPerDay: List<StackedPoint>,
    val timePerDay: List<SeriesPoint>,
    val answerButtons: AnswerButtonsData,
    val hourly: List<HourlyStat>,
    val responseTime: ResponseTimeData,
    /** Plan §7: Again/Hard/Good/Easy per bucket, same granularity rule. */
    val ratingTrend: List<GradeMixPoint> = emptyList(),
    /** Plan §7: sitting summary, separate from the guessing tab's buckets. */
    val sessions: SessionSummary = SessionSummary()
)

/** Memory tab: where the collection sits in the FSRS state space. */
data class MemoryData(
    val kpis: OverviewKpis,
    val stability: Distribution,
    val difficulty: Distribution,
    val retrievability: Distribution,
    val intervals: Distribution,
    val memoryGrowth: MemoryGrowthData,
    /** Plan §8.3: per-paper means, unscoped only. */
    val byPaper: List<PaperMemory> = emptyList()
)

/**
 * One paper's mean memory state (plan §8.3). Each mean is over the cards that
 * carry that value, so a paper with no graduated card still reports [cards]
 * and a null mean rather than a fabricated zero.
 */
data class PaperMemory(
    val paperId: String,
    val label: String,
    val cards: Int,
    val meanStability: Double?,
    val meanDifficulty: Double?,
    val meanRetrievability: Double?
)

/** Cards tab: the collection's shape and the cards that need attention. */
data class CardsData(
    val cardCounts: CardCountsData,
    val overdueAge: OverdueAgeData,
    val paperCounts: List<PaperCardCounts>,
    val leeches: List<WeakCard>,
    /** Row-based studied/unseen/bookmarked coverage (plan §4). */
    val coverage: CoverageStats = CoverageStats()
)

/**
 * One progress bucket (plan §5.1): counts, not rates, so buckets pool
 * correctly. [introduced] is the card's first-ever logged review landing in
 * the bucket; [graduated] is a pass from NEW/LEARNING/RELEARNING; [relapsed]
 * is an Again on a REVIEW card.
 */
data class ProgressSlice(
    val label: String,
    val reviews: Int = 0,
    val introduced: Int = 0,
    val graduated: Int = 0,
    val relapsed: Int = 0,
    val again: Int = 0
) {
    val againRate: Double? get() = if (reviews == 0) null else again.toDouble() / reviews
    val lapseRate: Double? get() = if (reviews == 0) null else relapsed.toDouble() / reviews
}

/**
 * The selected window against the equal-length window immediately before it
 * (plan §5.1). Null for [StatsAggregator.Range.ALL], which has no previous
 * window. Pass rate treats Again as a fail.
 */
data class ProgressComparison(
    val currentReviews: Int,
    val previousReviews: Int,
    val currentPassRate: Double?,
    val previousPassRate: Double?,
    val currentAvgSeconds: Double?,
    val previousAvgSeconds: Double?,
    val currentStudyDays: Int,
    val previousStudyDays: Int
) {
    val reviewsDelta: Int get() = currentReviews - previousReviews
    val passRateDelta: Double?
        get() = pair(currentPassRate, previousPassRate)?.let { it.first - it.second }
    val avgSecondsDelta: Double?
        get() = pair(currentAvgSeconds, previousAvgSeconds)?.let { it.first - it.second }
    val studyDaysDelta: Int get() = currentStudyDays - previousStudyDays

    /**
     * Again rate is the inverse of the pass rate, so its signed change is the
     * pass-rate delta negated (plan §8.1).
     */
    val againRateDelta: Double?
        get() = passRateDelta?.let { -it }

    private fun pair(a: Double?, b: Double?): Pair<Double, Double>? =
        if (a != null && b != null) a to b else null
}

/** Progress tab: introductions, graduations, relapses and the Again rate. */
data class ProgressData(
    val totals: ProgressSlice,
    val comparison: ProgressComparison?,
    val trend: List<ProgressSlice>
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
    val guessing: GuessingData,
    val progress: ProgressData,
    val sessions: SessionsData = SessionsData()
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
    guessing = guessing,
    progress = progress,
    sessions = sessions
)
