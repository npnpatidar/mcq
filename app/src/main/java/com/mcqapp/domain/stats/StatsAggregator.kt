package com.mcqapp.domain.stats

import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsOptimizer
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.SchedulerConfig
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Builds the whole statistics payload from the replayed review log and the
 * current card states. Pure and clock-free: `now` and the zone offset are
 * parameters, so every section is testable at any instant.
 *
 * Two scopes apply:
 *  - [build]'s `scopePaperId` — Anki's Collection-vs-Deck switch. Null means
 *    every paper.
 *  - [Range] — the time window. It applies to history graphs (trend,
 *    reviews/day, answer buttons, calibration, ...); "now"-shaped sections
 *    (today strip, forecast, distributions, card counts), the true-retention
 *    table (whose rows carry their own windows) and the heatmap (fixed at a
 *    year) are deliberately range-independent.
 *
 * All day arithmetic runs on the study day ([DayBoundary], 04:00 by default),
 * never on raw midnight.
 */
object StatsAggregator {

    /** The selectable time window. */
    enum class Range(val days: Int?) { D7(7), D30(30), D90(90), D365(365), ALL(null) }

    private const val HOUR_MS = 3_600_000L

    /** Everything the aggregation reads, in domain shapes. */
    data class Input(
        /**
         * Every logged review across every paper, replayed by [FsrsReplay] and
         * in per-card chronological order. The streamed retention pass reads
         * one card's day's at a time, so a caller that hands reviews over out
         * of order gets the same answer this order would have given only if
         * each card's reviews are oldest first.
         */
        val reviews: List<ReplayReview>,
        /** Every tracked card with its current schedule. */
        val cards: List<CardSnapshot>,
        /**
         * questionId → category title, for the per-category breakdown. Only the
         * retention tab's group breakdown reads it, and a caller that builds
         * any other section may pass an empty map.
         */
        val categoryOfQuestion: Map<String, String>,
        /** paperId → title. */
        val paperTitleOf: Map<String, String>,
        val scheduler: FsrsScheduler,
        val config: SchedulerConfig,
        val now: Long,
        /** The device's UTC offset at [now], in ms. */
        val zoneOffsetMillis: Long
    )

    /**
     * One build's shared inputs: the scope- and range-filtered log views, the
     * scoped cards, and the day arithmetic. Sections take this instead of
     * re-deriving it, which is what lets any one of them be built alone.
     */
    private class Context(
        val input: Input,
        val inScope: List<ReplayReview>,
        val inRange: List<ReplayReview>,
        val cards: List<CardSnapshot>,
        val scopePaperId: String?,
        val range: Range,
        val rangeStart: Long?,
        val todayStart: Long,
        val todayDay: Long
    ) {
        val cfg: SchedulerConfig get() = input.config
        val zone: Long get() = input.zoneOffsetMillis

        companion object {
            fun of(input: Input, scopePaperId: String?, range: Range): Context {
                val cfg = input.config
                val zone = input.zoneOffsetMillis
                val todayStart = DayBoundary.startOfDay(input.now, cfg.dayStartHour, zone)
                val inScope = input.reviews.filter { scopePaperId == null || it.paperId == scopePaperId }
                val rangeStart = range.days?.let { todayStart - (it - 1) * DayBoundary.DAY_MS }
                return Context(
                    input = input,
                    inScope = inScope,
                    inRange = inScope.filter { rangeStart == null || it.reviewedAt >= rangeStart },
                    cards = input.cards.filter { scopePaperId == null || it.paperId == scopePaperId },
                    scopePaperId = scopePaperId,
                    range = range,
                    rangeStart = rangeStart,
                    todayStart = todayStart,
                    todayDay = studyEpochDay(todayStart, cfg, zone)
                )
            }
        }
    }

    fun build(input: Input, scopePaperId: String?, range: Range): StatsBundle =
        buildSections(input, scopePaperId, range).toBundle()

    /**
     * Every tab's slice, each built by its own section function. [build] is
     * this plus [toBundle], so the all-at-once payload and the per-tab one can
     * never disagree; a tab that wants to load on its own calls the matching
     * section builder rather than the whole page.
     */
    private fun buildSections(input: Input, scopePaperId: String?, range: Range): StatsSections {
        val ctx = Context.of(input, scopePaperId, range)
        // The overview accumulator carries the KPIs the memory tab shows too,
        // so one pass serves both rather than one each.
        val overview = overviewAccumulator(ctx)
        return StatsSections(
            overview = overview.toData(),
            retention = retentionOf(ctx),
            workload = workloadOf(ctx),
            memory = memoryOf(ctx, overview.kpis()),
            cards = cardsOf(ctx)
        )
    }

    /** The overview tab on its own, for a tab that loads lazily. */
    fun buildOverview(input: Input, scopePaperId: String?, range: Range): OverviewData =
        overviewAccumulator(Context.of(input, scopePaperId, range)).toData()

    /** The retention tab on its own. */
    fun buildRetention(input: Input, scopePaperId: String?, range: Range): RetentionData =
        retentionOf(Context.of(input, scopePaperId, range))

    /** The workload tab on its own. */
    fun buildWorkload(input: Input, scopePaperId: String?, range: Range): WorkloadData =
        workloadOf(Context.of(input, scopePaperId, range))

    /** The memory tab on its own. */
    fun buildMemory(input: Input, scopePaperId: String?, range: Range): MemoryData {
        val ctx = Context.of(input, scopePaperId, range)
        return memoryOf(ctx, overviewAccumulator(ctx, collectWeakest = false).kpis())
    }

    /** The cards tab on its own. */
    fun buildCards(input: Input, scopePaperId: String?, range: Range): CardsData =
        cardsOf(Context.of(input, scopePaperId, range))

    /**
     * The overview pass: the scope-filtered log and the scope-filtered cards
     * streamed into one accumulator. The in-memory build feeds it from the
     * loaded lists and the streamed build feeds it from the database, so the
     * two cannot drift apart.
     */
    private fun overviewAccumulator(ctx: Context, collectWeakest: Boolean = true): OverviewAccumulator {
        val accumulator = OverviewAccumulator(
            scopePaperId = ctx.scopePaperId,
            range = ctx.range,
            now = ctx.input.now,
            cfg = ctx.cfg,
            zone = ctx.zone,
            scheduler = ctx.input.scheduler,
            collectWeakest = collectWeakest
        )
        // The whole log and every card, not the scope's own slices: the
        // accumulator applies the scope itself, and the FSRS panel's review
        // count is the library's, which is what the streamed build sees as it
        // pages. Feeding the slices instead would give the two builds different
        // inputs and let them drift.
        ctx.input.reviews.forEach(accumulator::add)
        ctx.input.cards.forEach(accumulator::addCard)
        return accumulator
    }

    private fun retentionOf(ctx: Context): RetentionData {
        val accumulator = RetentionAccumulator(
            scopePaperId = ctx.scopePaperId,
            range = ctx.range,
            now = ctx.input.now,
            cfg = ctx.cfg,
            zone = ctx.zone,
            paperTitleOf = ctx.input.paperTitleOf,
            categoryOfQuestion = ctx.input.categoryOfQuestion
        )
        ctx.inScope.forEach(accumulator::add)
        return accumulator.toData()
    }

    private fun workloadOf(ctx: Context): WorkloadData = WorkloadData(
        forecast = forecast(ctx.cards, ctx.todayStart, ctx.todayDay),
        reviewsPerDay = reviewsPerDay(ctx.inRange, ctx.rangeStart, ctx.todayDay, ctx.cfg, ctx.zone),
        timePerDay = timePerDay(ctx.inRange, ctx.rangeStart, ctx.todayDay, ctx.cfg, ctx.zone),
        answerButtons = answerButtons(ctx.inRange),
        hourly = hourly(ctx.inRange, ctx.zone),
        responseTime = responseTime(ctx.inRange, ctx.cards, ctx.rangeStart, ctx.todayDay, ctx.cfg, ctx.zone)
    )

    private fun memoryOf(ctx: Context, kpis: OverviewKpis): MemoryData = MemoryData(
        kpis = kpis,
        stability = stabilityDistribution(ctx.cards),
        difficulty = difficultyDistribution(ctx.cards),
        retrievability = retrievabilityDistribution(ctx.cards, ctx.input.scheduler, ctx.input.now),
        intervals = intervalDistribution(ctx.cards),
        memoryGrowth = memoryGrowth(ctx.inScope, ctx.cfg, ctx.zone)
    )

    private fun cardsOf(ctx: Context): CardsData = CardsData(
        cardCounts = cardCounts(ctx.cards),
        overdueAge = overdueAge(ctx.cards, ctx.input.now),
        paperCounts = if (ctx.scopePaperId == null) paperCounts(ctx.cards, ctx.input) else emptyList(),
        leeches = leeches(ctx.inScope, ctx.cards)
    )

    // --- overview accumulator ----------------------------------------------

    /** True-retention counts for one window, split by maturity. */
    private class RetentionRowCounts {
        private var all = 0
        private var allPasses = 0
        private var young = 0
        private var youngPasses = 0
        private var mature = 0
        private var maturePasses = 0

        fun add(review: ReplayReview) {
            all++
            if (review.passed) {
                allPasses++
                if (review.matureAtReview) maturePasses++ else youngPasses++
            }
            if (review.matureAtReview) mature++ else young++
        }

        fun toRow(label: String) = RetentionRow(
            label = label,
            allReviews = all,
            allPasses = allPasses,
            youngReviews = young,
            youngPasses = youngPasses,
            matureReviews = mature,
            maturePasses = maturePasses
        )
    }

    /**
     * Anki's true-retention rule as a cursor: only a card's first qualifying
     * review of a study day counts. Rows arrive oldest first within each card
     * (the order the log is read), so remembering the last day counted for a
     * card is enough to recognise the next day's first review. That is what
     * keeps this O(cards) instead of the O(card, day) map the lists needed.
     */
    private class FirstOfDayCursor {
        private val lastDay = HashMap<String, Long>()

        /** True when [review] is the card's first qualifying review on [day]. */
        fun isFirstOf(review: ReplayReview, day: Long): Boolean {
            // Learning-step rows and first exposures carry no whole-day interval
            // to qualify with, mirroring Anki's `last_interval >= 1` check.
            if ((review.intervalBeforeDays ?: 0) < 1) return false
            if (lastDay[review.questionId] == day) return false
            lastDay[review.questionId] = day
            return true
        }
    }

    /**
     * The overview tab — and the KPIs the memory tab shares with it — as
     * bounded passes: one over the log, one over the cards.
     *
     * Every figure here used to be derived from the two whole lists, and the
     * review side is where the page's memory went. Each one is now a counter or
     * a cursor: the heatmap needs a year of day buckets, the true-retention
     * rule needs one day index per card, and the weakest list needs one
     * roll-up per question in range. Nothing is O(reviews).
     *
     * The in-memory and the streamed build feed this same accumulator, so the
     * two cannot drift apart.
     */
    class OverviewAccumulator(
        private val scopePaperId: String?,
        private val range: Range,
        private val now: Long,
        private val cfg: SchedulerConfig,
        private val zone: Long,
        private val scheduler: FsrsScheduler,
        /** The memory tab shares this class for its KPIs; it ranks nothing. */
        private val collectWeakest: Boolean = true
    ) {
        private class DayTotals {
            var reviews = 0
            var seconds = 0L

            fun add(review: ReplayReview) {
                reviews++
                seconds += review.elapsedSeconds ?: 0L
            }
        }

        private class Window(val label: String, val fromDay: Long, val toDay: Long) {
            val counts = RetentionRowCounts()
        }

        private val todayStart = DayBoundary.startOfDay(now, cfg.dayStartHour, zone)
        private val tomorrowStart = todayStart + DayBoundary.DAY_MS
        private val todayDay = studyEpochDay(todayStart, cfg, zone)
        private val rangeStart = range.days?.let { todayStart - (it - 1) * DayBoundary.DAY_MS }

        /** The heatmap's year, keyed by study day; older days are never read. */
        private val heatDays = HashMap<Long, DayTotals>()

        private val firstOfDay = FirstOfDayCursor()

        private val windows = listOf(
            Window("today", todayDay, todayDay + 1),
            Window("yesterday", todayDay - 1, todayDay),
            Window("last7", todayDay - 6, todayDay + 1),
            Window("last30", todayDay - 29, todayDay + 1),
            Window("last90", todayDay - 89, todayDay + 1),
            Window("last365", todayDay - 364, todayDay + 1),
            Window("all", Long.MIN_VALUE, todayDay + 1)
        )

        /**
         * Every row fed in — the whole log, all papers — for the FSRS panel's
         * review count. Deliberately not scoped: the panel reports how much
         * history the library has to fit on, which is what the pre-streaming
         * build read straight off its input list.
         */
        private var allReviews = 0

        private var rangeReviews = 0
        private var rangeTimedCount = 0
        private var rangeTimedSeconds = 0L
        private val rangeDays = HashSet<Long>()
        private val rangeRetention = RetentionRowCounts()

        private var todayReviews = 0
        private var todayAgain = 0
        private var todayLearning = 0
        private var todayReview = 0
        private var todayRelearning = 0
        private var todayUnknown = 0
        private var todaySeconds = 0L
        private var todayIntroduced = 0

        private val rollOf = HashMap<String, QuestionRoll>()
        private val rollOrder = ArrayList<String>()

        private var dueNow = 0
        private var backlog = 0
        private var intervalSum = 0L
        private var intervalCount = 0
        private var knowledge = 0.0
        private var leechCount = 0
        private val leechQuestions = HashSet<String>()

        /**
         * Feeds one replayed review, in per-card chronological order. Rows
         * outside the scope are counted for the library total and then dropped.
         */
        fun add(review: ReplayReview) {
            // Counted before the scope guard: this one is the library's
            // history, not the scope's.
            allReviews++
            if (scopePaperId != null && review.paperId != scopePaperId) return
            val day = studyEpochDay(review.reviewedAt, cfg, zone)
            if (day > todayDay - HEATMAP_DAYS) heatDays.getOrPut(day) { DayTotals() }.add(review)
            if (day == todayDay) countToday(review)
            // The cursor sees every scoped row, in range or not, so a day the
            // range excludes cannot hide a later day's first review.
            val inRange = rangeStart == null || review.reviewedAt >= rangeStart
            if (firstOfDay.isFirstOf(review, day) && day <= todayDay) {
                // The retention table's windows are their own, not the
                // selector's: the range only narrows the KPIs and the graphs.
                windows.forEach { if (day >= it.fromDay && day < it.toDay) it.counts.add(review) }
                if (inRange) rangeRetention.add(review)
            }
            if (!inRange) return
            rangeReviews++
            rangeDays += day
            rangeTimedSeconds += review.elapsedSeconds ?: 0L
            if (review.elapsedSeconds != null) rangeTimedCount++
            if (collectWeakest) countRoll(review)
        }

        /** Feeds one scoped card: [CardSnapshot] carries its current schedule. */
        fun addCard(snapshot: CardSnapshot) {
            if (scopePaperId != null && snapshot.paperId != scopePaperId) return
            val state = snapshot.state
            if (state.state != CardStateKind.NEW) {
                // Scheduled for this study day; overdue cards stay in the
                // backlog instead of being double-counted here.
                if (state.dueAt != 0L && state.dueAt >= todayStart && state.dueAt < tomorrowStart) {
                    dueNow++
                }
                if (state.dueAt != 0L && state.dueAt < now) backlog++
            }
            if (state.intervalDays > 0) {
                intervalSum += state.intervalDays
                intervalCount++
            }
            if (state.stability > 0.0) knowledge += scheduler.retention(state, now)
            if (state.leech) {
                leechCount++
                leechQuestions += state.questionId
            }
        }

        /** The overview tab's slice. */
        fun toData(): OverviewData {
            val heatmap = heatmap()
            return OverviewData(
                today = today(),
                kpis = kpis(heatmap, rangeRetention.toRow("range")),
                retentionTable = windows.map { it.counts.toRow(it.label) },
                heatmap = heatmap,
                fsrsStatus = FsrsStatus(
                    fitted = cfg.fsrsParameters != FsrsScheduler.DEFAULT_FSRS_PARAMETERS,
                    reviewCount = allReviews,
                    unlockReviews = FsrsOptimizer.MIN_REVIEWS,
                    desiredRetention = cfg.desiredRetention
                ),
                weakest = weakest()
            )
        }

        /** The KPIs the overview and memory tabs both show. */
        fun kpis(): OverviewKpis = kpis(heatmap(), rangeRetention.toRow("range"))

        private fun kpis(heatmap: HeatmapData, rangeRow: RetentionRow): OverviewKpis = OverviewKpis(
            reviews = rangeReviews,
            days = rangeDays.size,
            timeSeconds = rangeTimedSeconds,
            avgAnswerSeconds = if (rangeTimedCount == 0) {
                null
            } else {
                rangeTimedSeconds.toDouble() / rangeTimedCount
            },
            retention = rangeRow.allRetention,
            matureRetention = rangeRow.matureRetention,
            desiredRetention = cfg.desiredRetention,
            estimatedKnowledge = knowledge.roundToInt(),
            avgIntervalDays = if (intervalCount == 0) null else intervalSum.toDouble() / intervalCount,
            leeches = leechCount,
            currentStreak = heatmap.currentStreak,
            longestStreak = heatmap.longestStreak
        )

        private fun today(): TodayStats = TodayStats(
            reviews = todayReviews,
            againCount = todayAgain,
            correctPct = if (todayReviews == 0) {
                null
            } else {
                (todayReviews - todayAgain).toDouble() / todayReviews
            },
            timeSeconds = todaySeconds,
            learning = todayLearning,
            review = todayReview,
            relearning = todayRelearning,
            unknown = todayUnknown,
            introduced = todayIntroduced,
            dueNow = dueNow,
            backlog = backlog
        )

        private fun countToday(review: ReplayReview) {
            todayReviews++
            if (!review.passed) todayAgain++
            when (review.kind) {
                ReplayReview.ReviewKind.LEARNING -> todayLearning++
                ReplayReview.ReviewKind.REVIEW -> todayReview++
                ReplayReview.ReviewKind.RELEARNING -> todayRelearning++
                null -> todayUnknown++
            }
            todaySeconds += review.elapsedSeconds ?: 0L
            if (review.stateBefore == CardStateKind.NEW) todayIntroduced++
        }

        private fun countRoll(review: ReplayReview) {
            val roll = rollOf.getOrPut(review.questionId) {
                rollOrder += review.questionId
                QuestionRoll()
            }
            roll.reviews++
            if (!review.passed) roll.lapses++
            if (roll.firstPaperId == null) roll.firstPaperId = review.paperId
            if (review.reviewedAt > roll.lastReviewedAt) roll.lastReviewedAt = review.reviewedAt
            review.elapsedSeconds?.let { seconds ->
                roll.timedCount++
                roll.timedSeconds += seconds
            }
        }

        private fun heatmap(): HeatmapData {
            val firstDay = todayStart - (HEATMAP_DAYS - 1) * DayBoundary.DAY_MS
            val days = (0 until HEATMAP_DAYS).map { i ->
                val totals = heatDays[todayDay - (HEATMAP_DAYS - 1 - i)]
                HeatmapDay(
                    dayStart = firstDay + i * DayBoundary.DAY_MS,
                    reviews = totals?.reviews ?: 0,
                    seconds = totals?.seconds ?: 0L
                )
            }
            var longest = 0
            var run = 0
            days.forEach {
                run = if (it.reviews > 0) run + 1 else 0
                longest = maxOf(longest, run)
            }
            // A day with no reviews yet does not break the streak until it is over.
            var current = 0
            for (i in days.indices.reversed()) {
                val day = days[i]
                if (day.reviews > 0) current++
                else if (i == days.lastIndex) continue
                else break
            }
            return HeatmapData(days, current, longest)
        }

        private fun weakest(): List<WeakCard> = rollOrder.mapNotNull { questionId ->
            val roll = rollOf.getValue(questionId)
            if (roll.reviews < 3) return@mapNotNull null
            if (roll.lapses == 0) return@mapNotNull null
            WeakCard(
                paperId = roll.firstPaperId!!,
                questionId = questionId,
                reviews = roll.reviews,
                lapses = roll.lapses,
                againRate = roll.lapses.toDouble() / roll.reviews,
                avgSeconds = if (roll.timedCount == 0) {
                    null
                } else {
                    roll.timedSeconds.toDouble() / roll.timedCount
                },
                lastReviewedAt = roll.lastReviewedAt,
                leech = leechQuestions.contains(questionId)
            )
        }
            .sortedWith(compareBy({ -it.againRate }, { -it.lapses }))
            .take(15)

        private companion object {
            /** The heatmap shows a year, today included. */
            const val HEATMAP_DAYS = 365
        }
    }

    // --- retention accumulator ---------------------------------------------

    /** A group whose label the breakdown could not resolve. */
    private const val UNKNOWN_GROUP = "—"

    /**
     * The retention tab as one bounded pass.
     *
     * Its four charts read the log two different ways: the trend and the
     * weekday split follow Anki's true-retention rule (each card's first
     * qualifying review of a study day), while calibration and the per-group
     * table count every review. Holding the whole log to do that is what made
     * the page grow with the library, so the rule is expressed as a cursor
     * instead: rows arrive in per-card chronological order, one day index per
     * card recognises a day's first qualifying review, and everything else is
     * a counter. Live state is O(cards + days + groups), never O(reviews).
     *
     * The in-memory and the streamed build feed this same accumulator, so the
     * two cannot drift apart.
     */
    class RetentionAccumulator(
        private val scopePaperId: String?,
        private val range: Range,
        private val now: Long,
        private val cfg: SchedulerConfig,
        private val zone: Long,
        private val paperTitleOf: Map<String, String>,
        private val categoryOfQuestion: Map<String, String>
    ) {
        private class Counts {
            var reviews = 0
            var passes = 0

            fun add(passed: Boolean) {
                reviews++
                if (passed) passes++
            }
        }

        private val todayStart = DayBoundary.startOfDay(now, cfg.dayStartHour, zone)
        private val todayDay = studyEpochDay(todayStart, cfg, zone)
        private val rangeStart = range.days?.let { todayStart - (it - 1) * DayBoundary.DAY_MS }

        /** Study day → that day's first qualifying reviews. */
        private val trendDays = HashMap<Long, Counts>()

        private val firstOfDay = FirstOfDayCursor()

        /** 0 = Monday .. 6 = Sunday. */
        private val weekdays = Array(7) { Counts() }

        /** Group key → every in-range review of it, in first-seen order. */
        private val groups = LinkedHashMap<String, Counts>()

        private var earliestInRange: Long? = null

        private val binSums = DoubleArray(10)
        private val binCounts = IntArray(10)
        private val binPasses = IntArray(10)
        private var samples = 0
        private var predictedSum = 0.0
        private var brier = 0.0

        /**
         * Feeds one replayed review, in per-card chronological order. Rows
         * outside the scope or the range are dropped outright: no caller's view
         * contains them, and their replay already happened upstream.
         */
        fun add(review: ReplayReview) {
            if (scopePaperId != null && review.paperId != scopePaperId) return
            val reviewedAt = review.reviewedAt
            if (rangeStart != null && reviewedAt < rangeStart) return
            val earliest = earliestInRange
            if (earliest == null || reviewedAt < earliest) earliestInRange = reviewedAt
            countGroup(review)
            countCalibration(review)
            // The true-retention rule, through the shared cursor: a first
            // exposure carries no whole-day interval to qualify with, and only
            // the day's first qualifying review counts.
            val day = studyEpochDay(reviewedAt, cfg, zone)
            if (!firstOfDay.isFirstOf(review, day)) return
            trendDays.getOrPut(day) { Counts() }.add(review.passed)
            weekdays[weekdayOf(day)].add(review.passed)
        }

        /** The tab's four charts. */
        fun toData(): RetentionData = RetentionData(
            retentionTrend = trend(),
            calibration = calibration(),
            groupRetention = groupRetention(),
            weekdayRetention = weekdayRetention()
        )

        private fun countGroup(review: ReplayReview) {
            val key = if (scopePaperId == null) review.paperId else review.questionId
            groups.getOrPut(key) { Counts() }.add(review.passed)
        }

        private fun countCalibration(review: ReplayReview) {
            val predicted = review.predictedRetention ?: return
            val p = predicted.coerceIn(0.0, 0.999999)
            val bin = floor(p * 10).toInt().coerceIn(0, 9)
            binSums[bin] += p
            binCounts[bin]++
            if (review.passed) binPasses[bin]++
            val outcome = if (review.passed) 1.0 else 0.0
            brier += (p - outcome) * (p - outcome)
            samples++
            predictedSum += predicted
        }

        private fun trend(): RetentionTrendData {
            val windowStart = rangeStart ?: earliestInRange ?: now
            val granularity = granularityFor(windowStart, todayDay, cfg, zone)
            val buckets = bucketList(
                studyEpochDay(windowStart, cfg, zone), todayDay, granularity, cfg, zone
            )
            val reviewsByBucket = HashMap<Long, Int>()
            val passesByBucket = HashMap<Long, Int>()
            trendDays.forEach { (day, counts) ->
                val key = bucketKeyOf(day, granularity)
                reviewsByBucket[key] = (reviewsByBucket[key] ?: 0) + counts.reviews
                passesByBucket[key] = (passesByBucket[key] ?: 0) + counts.passes
            }
            val points = buckets.map { bucket ->
                val reviews = reviewsByBucket[bucket.key] ?: 0
                val passes = passesByBucket[bucket.key] ?: 0
                SeriesPoint(
                    label = bucket.label,
                    value = if (reviews == 0) null else passes.toDouble() / reviews,
                    count = reviews
                )
            }
            // Trailing pooled pass rate: a 1-review 100% day must not outweigh a
            // 40-review 70% day, so pool the counts instead of the percentages.
            val rollingWindow = if (granularity == Granularity.DAILY) 7 else 4
            val rolling = points.indices.map { index ->
                val first = maxOf(0, index - rollingWindow + 1)
                var reviews = 0
                var passes = 0
                for (i in first..index) {
                    val key = buckets[i].key
                    reviews += reviewsByBucket[key] ?: 0
                    passes += passesByBucket[key] ?: 0
                }
                SeriesPoint(
                    label = points[index].label,
                    value = if (reviews == 0) null else passes.toDouble() / reviews,
                    count = reviews
                )
            }
            return RetentionTrendData(
                points = points,
                rolling = rolling,
                desiredRetention = cfg.desiredRetention
            )
        }

        private fun calibration(): CalibrationData {
            if (samples == 0) return CalibrationData(emptyList(), 0, null, null, null)
            val bins = binCounts.indices.mapNotNull { i ->
                if (binCounts[i] == 0) {
                    null
                } else {
                    CalibrationData.CalibrationBin(
                        predictedFrom = i / 10.0,
                        predictedMean = binSums[i] / binCounts[i],
                        actualRate = binPasses[i].toDouble() / binCounts[i],
                        count = binCounts[i]
                    )
                }
            }
            return CalibrationData(
                bins = bins,
                samples = samples,
                predictedMean = predictedSum / samples,
                actualMean = binPasses.sum().toDouble() / samples,
                brier = brier / samples
            )
        }

        private fun groupRetention(): List<GroupRetention> = groups.map { (key, counts) ->
            val label = if (scopePaperId == null) {
                paperTitleOf[key] ?: key
            } else {
                categoryOfQuestion[key] ?: UNKNOWN_GROUP
            }
            GroupRetention(label, counts.reviews, counts.passes)
        }
            .sortedWith(compareBy({ it.retention ?: 1.0 }, { -it.reviews }))
            .take(15)

        private fun weekdayRetention(): List<WeekdayRetention> = (0..6).map { day ->
            WeekdayRetention(day, weekdays[day].reviews, weekdays[day].passes)
        }
    }

    // --- forecast ----------------------------------------------------------

    private fun forecast(
        cards: List<CardSnapshot>,
        todayStart: Long,
        todayDay: Long
    ): ForecastData {
        val horizon = 30
        val learning = IntArray(horizon)
        val review = IntArray(horizon)
        var overdue = 0
        var load = 0.0
        cards.forEach { snapshot ->
            val state = snapshot.state
            if (state.state == CardStateKind.NEW || state.dueAt == 0L) return@forEach
            load += if (state.intervalDays <= 0) 1.0 else 1.0 / state.intervalDays
            val dayIndex = Math.floorDiv(state.dueAt - todayStart, DayBoundary.DAY_MS)
            when {
                dayIndex < 0 -> overdue++
                dayIndex < horizon ->
                    if (state.state == CardStateKind.LEARNING ||
                        state.state == CardStateKind.RELEARNING
                    ) learning[dayIndex.toInt()]++
                    else review[dayIndex.toInt()]++
            }
        }
        val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        return ForecastData(
            days = (0 until horizon).map { i ->
                val day = todayDay + i
                ForecastDay(
                    label = LocalDate.ofEpochDay(day).format(fmt),
                    learning = learning[i],
                    review = review[i]
                )
            },
            overdue = overdue,
            dueToday = learning[0] + review[0],
            dailyLoad = load
        )
    }

    // --- review history graphs ----------------------------------------------

    private fun reviewsPerDay(
        inRange: List<ReplayReview>,
        rangeStart: Long?,
        todayDay: Long,
        cfg: SchedulerConfig,
        zone: Long
    ): List<StackedPoint> {
        val windowStart = rangeStart ?: inRange.minOfOrNull { it.reviewedAt } ?: return emptyList()
        val granularity = granularityFor(windowStart, todayDay, cfg, zone)
        val rangeStartDay = studyEpochDay(windowStart, cfg, zone)
        val bs = bucketList(rangeStartDay, todayDay, granularity, cfg, zone)
        val grouped = inRange.groupBy {
            bucketKeyOf(studyEpochDay(it.reviewedAt, cfg, zone), granularity)
        }
        return bs.map { b ->
            val rows = grouped[b.key].orEmpty()
            StackedPoint(
                label = b.label,
                segments = listOf(
                    rows.count { it.kind == ReplayReview.ReviewKind.LEARNING }.toDouble(),
                    rows.count { it.kind == ReplayReview.ReviewKind.REVIEW }.toDouble(),
                    rows.count { it.kind == ReplayReview.ReviewKind.RELEARNING }.toDouble(),
                    rows.count { it.kind == null }.toDouble()
                ),
                total = rows.size
            )
        }
    }

    private fun timePerDay(
        inRange: List<ReplayReview>,
        rangeStart: Long?,
        todayDay: Long,
        cfg: SchedulerConfig,
        zone: Long
    ): List<SeriesPoint> {
        val windowStart = rangeStart ?: inRange.minOfOrNull { it.reviewedAt } ?: return emptyList()
        val granularity = granularityFor(windowStart, todayDay, cfg, zone)
        val rangeStartDay = studyEpochDay(windowStart, cfg, zone)
        val bs = bucketList(rangeStartDay, todayDay, granularity, cfg, zone)
        val grouped = inRange.groupBy {
            bucketKeyOf(studyEpochDay(it.reviewedAt, cfg, zone), granularity)
        }
        return bs.map { b ->
            val rows = grouped[b.key].orEmpty()
            val seconds = rows.sumOf { it.elapsedSeconds ?: 0L }.toDouble()
            SeriesPoint(
                label = b.label,
                value = if (rows.isEmpty()) null else seconds,
                count = rows.size
            )
        }
    }

    // --- answer behaviour ----------------------------------------------------

    private fun answerButtons(inRange: List<ReplayReview>): AnswerButtonsData {
        data class Counts(var again: Int = 0, var hard: Int = 0, var good: Int = 0, var easy: Int = 0)
        val byKind = mutableMapOf<AnswerButtonsData.Kind, Counts>()
        // Rows whose pre-state is unknown (seeded history) cannot be
        // classified honestly; they are left out of this chart rather than
        // being lumped into "Learning".
        inRange.filter { it.kind != null }.forEach { review ->
            val kind = when {
                review.kind == ReplayReview.ReviewKind.RELEARNING ->
                    AnswerButtonsData.Kind.RELEARNING
                review.kind == ReplayReview.ReviewKind.REVIEW && review.matureAtReview ->
                    AnswerButtonsData.Kind.MATURE
                review.kind == ReplayReview.ReviewKind.REVIEW -> AnswerButtonsData.Kind.YOUNG
                else -> AnswerButtonsData.Kind.LEARNING
            }
            val counts = byKind.getOrPut(kind) { Counts() }
            when (review.rating) {
                1 -> counts.again++
                2 -> counts.hard++
                3 -> counts.good++
                else -> counts.easy++
            }
        }
        return AnswerButtonsData(
            listOf(
                AnswerButtonsData.Kind.LEARNING,
                AnswerButtonsData.Kind.YOUNG,
                AnswerButtonsData.Kind.MATURE,
                AnswerButtonsData.Kind.RELEARNING
            ).mapNotNull { kind ->
                byKind[kind]?.let { c ->
                    AnswerButtonsData.Group(kind, c.again, c.hard, c.good, c.easy)
                }
            }
        )
    }

    private fun hourly(inRange: List<ReplayReview>, zone: Long): List<HourlyStat> {
        val byHour = inRange.groupBy {
            Math.floorMod(it.reviewedAt + zone, DayBoundary.DAY_MS) / HOUR_MS
        }
        return (0 until 24).map { hour ->
            val rows = byHour[hour.toLong()].orEmpty()
            HourlyStat(
                hour = hour,
                reviews = rows.size,
                passRate = if (rows.isEmpty()) null else rows.count { it.passed }.toDouble() / rows.size
            )
        }
    }

    /**
     * Per-question figures gathered in one pass over the log, for the ranked
     * lists. Those lists used to be built by re-filtering the whole log once
     * per question; rolling the figures up in a single pass is what keeps the
     * statistics screen linear in the number of reviews rather than quadratic.
     */
    private class QuestionRoll {
        var reviews = 0
        var lapses = 0
        var lastReviewedAt = 0L
        var timedCount = 0
        var timedSeconds = 0L

        /** Paper of the first review seen, for the row's display paper. */
        var firstPaperId: String? = null

        /** Paper of the first *timed* review; the old grouping ordered by it. */
        var firstTimedPaperId: String? = null
    }

    private fun responseTime(
        inRange: List<ReplayReview>,
        cards: List<CardSnapshot>,
        rangeStart: Long?,
        todayDay: Long,
        cfg: SchedulerConfig,
        zone: Long
    ): ResponseTimeData {
        val timed = inRange.mapNotNull { review ->
            review.elapsedSeconds?.let { review to it }
        }
        val edges = listOf(5L, 10L, 20L, 30L, 60L, 120L)
        val labels = listOf("0–5s", "5–10s", "10–20s", "20–30s", "30–60s", "1–2m", "2m+")
        val histogram = IntArray(labels.size)
        timed.forEach { (_, seconds) ->
            var bin = edges.indexOfFirst { seconds < it }
            if (bin < 0) bin = labels.size - 1
            histogram[bin]++
        }
        val byGrade = ReviewGrade.entries.map { grade ->
            val rows = timed.filter { it.first.rating == grade.ordinal + 1 }
            ResponseTimeData.GradeStat(
                grade = grade,
                reviews = rows.size,
                avgSeconds = if (rows.isEmpty()) null else rows.map { it.second }.average()
            )
        }
        val windowStart = rangeStart ?: inRange.minOfOrNull { it.reviewedAt }
        val trend = if (windowStart == null) {
            emptyList()
        } else {
            val granularity = granularityFor(windowStart, todayDay, cfg, zone)
            val rangeStartDay = studyEpochDay(windowStart, cfg, zone)
            val bs = bucketList(rangeStartDay, todayDay, granularity, cfg, zone)
            val grouped = timed.groupBy {
                bucketKeyOf(studyEpochDay(it.first.reviewedAt, cfg, zone), granularity)
            }
            bs.map { b ->
                val rows = grouped[b.key].orEmpty()
                SeriesPoint(
                    label = b.label,
                    value = if (rows.isEmpty()) null else rows.map { it.second }.average(),
                    count = rows.size
                )
            }
        }
        val leechByQuestion = cards.filter { it.state.leech }.associate { it.state.questionId to true }
        // One pass for the per-question figures. This used to re-filter the
        // whole range once per question that had three or more timed reviews,
        // which is O(reviews × questions): at 1M reviews over 250k questions
        // it is the screen's worst hot spot. `timedOrder` reproduces the key
        // order of the old `timed.groupBy {}` (first *timed* review), which
        // `sortedByDescending` relies on to break equal-average ties.
        val rollOf = HashMap<String, QuestionRoll>()
        val timedOrder = ArrayList<String>()
        inRange.forEach { review ->
            val roll = rollOf.getOrPut(review.questionId) { QuestionRoll() }
            roll.reviews++
            if (!review.passed) roll.lapses++
            if (review.reviewedAt > roll.lastReviewedAt) roll.lastReviewedAt = review.reviewedAt
            review.elapsedSeconds?.let { seconds ->
                if (roll.timedCount == 0) {
                    roll.firstTimedPaperId = review.paperId
                    timedOrder += review.questionId
                }
                roll.timedCount++
                roll.timedSeconds += seconds
            }
        }
        val perCard = timedOrder.mapNotNull { questionId ->
            val roll = rollOf.getValue(questionId)
            if (roll.timedCount < 3) return@mapNotNull null
            WeakCard(
                paperId = roll.firstTimedPaperId!!,
                questionId = questionId,
                reviews = roll.reviews,
                lapses = roll.lapses,
                againRate = roll.lapses.toDouble() / roll.reviews,
                avgSeconds = roll.timedSeconds.toDouble() / roll.timedCount,
                lastReviewedAt = roll.lastReviewedAt,
                leech = leechByQuestion.containsKey(questionId)
            )
        }
        return ResponseTimeData(
            histogram = labels.mapIndexed { i, label ->
                SeriesPoint(label, histogram[i].toDouble(), histogram[i])
            },
            avgByGrade = byGrade,
            trend = trend,
            slowest = perCard.sortedByDescending { it.avgSeconds }.take(10),
            fastFails = timed.count { (review, seconds) -> !review.passed && seconds < 5 }
        )
    }

    // --- card-state distributions -------------------------------------------

    private fun stabilityDistribution(cards: List<CardSnapshot>): Distribution =
        histogram(
            values = cards.mapNotNull { if (it.state.stability > 0.0) it.state.stability else null },
            edges = listOf(1.0, 3.0, 7.0, 14.0, 21.0, 45.0, 90.0, 180.0, 365.0),
            labels = listOf(
                "<1d", "1–3d", "3–7d", "1–2w", "2–3w", "3w–1.5m",
                "1.5–3m", "90–180d", "6m–1y", ">1y"
            )
        )

    private fun difficultyDistribution(cards: List<CardSnapshot>): Distribution {
        val values = cards.mapNotNull { if (it.state.difficulty > 0.0) it.state.difficulty else null }
        val counts = IntArray(10)
        values.forEach { counts[(floor(it) - 1).toInt().coerceIn(0, 9)]++ }
        val bins = (0 until 10).map { i ->
            Distribution.Bin((i + 1).toString(), i + 1.0, i + 2.0, counts[i])
        }
        return Distribution(bins, values.ifEmpty { null }?.average(), median(values))
    }

    private fun retrievabilityDistribution(
        cards: List<CardSnapshot>,
        scheduler: FsrsScheduler,
        now: Long
    ): Distribution {
        val values = cards.mapNotNull {
            if (it.state.stability > 0.0) scheduler.retention(it.state, now) else null
        }
        val counts = IntArray(10)
        values.forEach { counts[(it * 10).toInt().coerceIn(0, 9)]++ }
        val bins = (0 until 10).map { i ->
            Distribution.Bin(
                "${i * 10}–${(i + 1) * 10}%", i / 10.0, (i + 1) / 10.0, counts[i]
            )
        }
        return Distribution(bins, values.ifEmpty { null }?.average(), median(values))
    }

    private fun intervalDistribution(cards: List<CardSnapshot>): Distribution =
        histogram(
            values = cards.mapNotNull { if (it.state.intervalDays > 0) it.state.intervalDays.toDouble() else null },
            edges = listOf(2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 512.0),
            labels = listOf(
                "1d", "2–3d", "4–7d", "8–15d", "16–31d",
                "1–2m", "2–4m", "4–8m", "8–17m", "17m+"
            )
        )

    private fun histogram(values: List<Double>, edges: List<Double>, labels: List<String>): Distribution {
        val counts = IntArray(labels.size)
        values.forEach { value ->
            var bin = edges.indexOfFirst { value < it }
            if (bin < 0) bin = labels.size - 1
            counts[bin]++
        }
        return Distribution(
            bins = labels.mapIndexed { i, label ->
                Distribution.Bin(
                    label,
                    from = if (i == 0) 0.0 else edges[i - 1],
                    to = edges.getOrElse(i) { Double.MAX_VALUE },
                    count = counts[i]
                )
            },
            mean = values.ifEmpty { null }?.average(),
            median = median(values)
        )
    }

    // --- memory growth -------------------------------------------------------

    private fun memoryGrowth(
        inScope: List<ReplayReview>,
        cfg: SchedulerConfig,
        zone: Long
    ): MemoryGrowthData {
        val cappedNumbers = 15
        val byNumber = HashMap<Int, MutableList<Double>>()
        inScope.groupBy { it.questionId }.forEach { (_, rows) ->
            // Number only rows that contribute: a seeded first row without a
            // stability estimate must not shift every later cohort down.
            val contributing = rows.sortedBy { it.reviewedAt }
                .mapNotNull { row -> row.stabilityAfter?.let { row to it } }
            var number = 0
            contributing.forEach { (_, stability) ->
                number += 1
                val n = number.coerceAtMost(cappedNumbers)
                byNumber.getOrPut(n) { mutableListOf() }.add(stability)
            }
        }
        val points = (1..cappedNumbers).map { number ->
            val values = byNumber[number].orEmpty()
            SeriesPoint(
                label = if (number == cappedNumbers) "$number+" else "$number",
                value = if (values.isEmpty()) null else values.average(),
                count = values.size
            )
        }
        val monthly = inScope
            .filter { it.stabilityAfter != null }
            .groupBy {
                val day = LocalDate.ofEpochDay(studyEpochDay(it.reviewedAt, cfg, zone))
                day.year * 12L + day.monthValue - 1
            }
        val overTime = monthly.keys.sorted().map { key ->
            val rows = monthly.getValue(key)
            val first = LocalDate.ofEpochDay(studyEpochDay(rows.first().reviewedAt, cfg, zone))
                .withDayOfMonth(1)
            SeriesPoint(
                label = first.format(DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)),
                value = rows.mapNotNull { it.stabilityAfter }.average(),
                count = rows.size
            )
        }
        return MemoryGrowthData(points, overTime)
    }

    // --- card counts ---------------------------------------------------------

    private fun cardCounts(cards: List<CardSnapshot>): CardCountsData {
        var fresh = 0
        var learning = 0
        var relearning = 0
        var young = 0
        var mature = 0
        var leeches = 0
        cards.forEach {
            val state = it.state
            when {
                state.state == CardStateKind.NEW -> fresh++
                state.state == CardStateKind.LEARNING -> learning++
                state.state == CardStateKind.RELEARNING -> relearning++
                state.intervalDays >= ReplayReview.MATURE_DAYS -> mature++
                else -> young++
            }
            if (state.leech) leeches++
        }
        return CardCountsData(fresh, learning, relearning, young, mature, leeches)
    }

    // --- overdue age ------------------------------------------------------------

    /**
     * How stale the scheduled backlog is: overdue cards (past due, not new)
     * bucketed by whole-day age at [now].
     */
    private fun overdueAge(cards: List<CardSnapshot>, now: Long): OverdueAgeData {
        val bins: List<Triple<String, Int, Int?>> = listOf(
            Triple("<1d", 0, 1),
            Triple("1–2d", 1, 2),
            Triple("2–4d", 2, 4),
            Triple("4–7d", 4, 7),
            Triple("1–2w", 7, 14),
            Triple("2w+", 14, null)
        )
        val counts = IntArray(bins.size)
        val overdue = cards.filter {
            it.state.dueAt != 0L && it.state.state != CardStateKind.NEW && it.state.dueAt < now
        }
        var ageSum = 0.0
        var oldest = 0
        overdue.forEach { card ->
            val age = floor((now - card.state.dueAt).toDouble() / DayBoundary.DAY_MS).toInt()
                .coerceAtLeast(0)
            ageSum += age
            oldest = maxOf(oldest, age)
            val index = bins.indexOfFirst { (_, from, to) ->
                age >= from && (to == null || age < to)
            }
            if (index >= 0) counts[index]++
        }
        return OverdueAgeData(
            bins = bins.mapIndexed { i, (label, from, to) ->
                OverdueAgeData.OverdueAgeBin(label, from, to, counts[i])
            },
            overdue = overdue.size,
            meanAgeDays = if (overdue.isEmpty()) null else ageSum / overdue.size,
            oldestAgeDays = if (overdue.isEmpty()) null else oldest
        )
    }

    // --- per-paper card counts ---------------------------------------------------

    /** Card counts per paper, biggest collection first, for cross-paper comparison. */
    private fun paperCounts(
        cards: List<CardSnapshot>,
        input: Input
    ): List<PaperCardCounts> =
        cards.groupBy { it.paperId }.map { (paperId, rows) ->
            val counts = cardCounts(rows)
            PaperCardCounts(
                paperId = paperId,
                label = input.paperTitleOf[paperId] ?: paperId,
                total = counts.total,
                fresh = counts.fresh,
                learning = counts.learning,
                relearning = counts.relearning,
                young = counts.young,
                mature = counts.mature
            )
        }.sortedWith(compareByDescending<PaperCardCounts> { it.total }.thenBy { it.label })

    /**
     * The flagged leeches with their lifetime figures (all history, not the
     * selected range: a leech is a property of the card, not of the window).
     */
    private fun leeches(
        inScope: List<ReplayReview>,
        cards: List<CardSnapshot>
    ): List<WeakCard> {
        val flagged = cards.filter { it.state.leech }
        if (flagged.isEmpty()) return emptyList()
        // One pass over history, touching only the flagged cards. This used to
        // filter the whole scope once per leech, which is O(reviews × leeches);
        // the walk below is what makes a 1M-row log cheap for the overview.
        val wanted = flagged.mapTo(HashSet()) { it.state.questionId }
        val rollOf = HashMap<String, QuestionRoll>()
        inScope.forEach { review ->
            if (review.questionId !in wanted) return@forEach
            val roll = rollOf.getOrPut(review.questionId) { QuestionRoll() }
            roll.reviews++
            if (!review.passed) roll.lapses++
            if (review.reviewedAt > roll.lastReviewedAt) roll.lastReviewedAt = review.reviewedAt
            review.elapsedSeconds?.let { seconds ->
                roll.timedCount++
                roll.timedSeconds += seconds
            }
        }
        return flagged.mapNotNull { snapshot ->
            val roll = rollOf[snapshot.state.questionId] ?: return@mapNotNull null
            WeakCard(
                paperId = snapshot.paperId,
                questionId = snapshot.state.questionId,
                reviews = roll.reviews,
                lapses = roll.lapses,
                againRate = roll.lapses.toDouble() / roll.reviews,
                avgSeconds = if (roll.timedCount == 0) {
                    null
                } else {
                    roll.timedSeconds.toDouble() / roll.timedCount
                },
                lastReviewedAt = roll.lastReviewedAt,
                leech = true
            )
        }.sortedByDescending { it.lapses }.take(15)
    }

    // --- day and bucket arithmetic -------------------------------------------

    private enum class Granularity { DAILY, WEEKLY, MONTHLY }

    private data class Bucket(val key: Long, val label: String)

    /** Epoch-day index of the study day containing [ms]. */
    private fun studyEpochDay(ms: Long, cfg: SchedulerConfig, zone: Long): Long {
        val shift = cfg.dayStartHour.coerceIn(0, 23) * HOUR_MS
        return Math.floorDiv(ms + zone - shift, DayBoundary.DAY_MS)
    }

    /** 0 = Monday .. 6 = Sunday (epoch day 0 was a Thursday). */
    private fun weekdayOf(epochDay: Long): Int =
        Math.floorMod(epochDay + 3, 7)

    private fun bucketKeyOf(epochDay: Long, granularity: Granularity): Long = when (granularity) {
        Granularity.DAILY -> epochDay
        Granularity.WEEKLY -> Math.floorDiv(epochDay, 7)
        Granularity.MONTHLY -> LocalDate.ofEpochDay(epochDay).let { it.year * 12L + it.monthValue - 1 }
    }

    private fun bucketStartDay(key: Long, granularity: Granularity): Long = when (granularity) {
        Granularity.DAILY -> key
        Granularity.WEEKLY -> key * 7
        Granularity.MONTHLY -> LocalDate.of((key / 12).toInt(), (key % 12 + 1).toInt(), 1).toEpochDay()
    }

    /**
     * Buckets spanning [startDay]..[todayDay], oldest first, empty ones kept
     * so a chart never shows a shifted axis.
     */
    private fun bucketList(
        startDay: Long,
        todayDay: Long,
        granularity: Granularity,
        cfg: SchedulerConfig,
        zone: Long
    ): List<Bucket> {
        val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        val monthFmt = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)
        val firstKey = bucketKeyOf(startDay, granularity)
        val lastKey = bucketKeyOf(todayDay, granularity)
        return (firstKey..lastKey).map { key ->
            val label = when (granularity) {
                Granularity.DAILY, Granularity.WEEKLY ->
                    LocalDate.ofEpochDay(bucketStartDay(key, granularity).coerceAtLeast(startDay))
                        .format(dayFmt)
                Granularity.MONTHLY -> LocalDate.ofEpochDay(bucketStartDay(key, granularity))
                    .atStartOfDay().format(monthFmt)
            }
            Bucket(key, label)
        }
    }

    private fun granularityFor(
        windowStartMs: Long,
        todayDay: Long,
        cfg: SchedulerConfig,
        zone: Long
    ): Granularity {
        val startDay = studyEpochDay(windowStartMs, cfg, zone)
        val spanDays = (todayDay - startDay).coerceAtLeast(0)
        return when {
            spanDays <= 31 -> Granularity.DAILY
            spanDays <= 150 -> Granularity.WEEKLY
            else -> Granularity.MONTHLY
        }
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

}
