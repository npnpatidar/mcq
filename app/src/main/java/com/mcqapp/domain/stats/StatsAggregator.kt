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
        val tomorrowStart: Long,
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
                    tomorrowStart = todayStart + DayBoundary.DAY_MS,
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
        // Heatmap and true retention are inputs to the overview KPIs, so they
        // are computed once here and handed to both consumers rather than
        // recomputed per tab.
        val heatmap = heatmap(ctx.inScope, ctx.todayStart, ctx.cfg, ctx.zone)
        val kpis = kpisOf(ctx, heatmap)
        return StatsSections(
            overview = overviewOf(ctx, kpis, heatmap),
            retention = retentionOf(ctx),
            workload = workloadOf(ctx),
            memory = memoryOf(ctx, kpis),
            cards = cardsOf(ctx)
        )
    }

    /** The overview tab on its own, for a tab that loads lazily. */
    fun buildOverview(input: Input, scopePaperId: String?, range: Range): OverviewData {
        val ctx = Context.of(input, scopePaperId, range)
        val heatmap = heatmap(ctx.inScope, ctx.todayStart, ctx.cfg, ctx.zone)
        return overviewOf(ctx, kpisOf(ctx, heatmap), heatmap)
    }

    /** The retention tab on its own. */
    fun buildRetention(input: Input, scopePaperId: String?, range: Range): RetentionData =
        retentionOf(Context.of(input, scopePaperId, range))

    /** The workload tab on its own. */
    fun buildWorkload(input: Input, scopePaperId: String?, range: Range): WorkloadData =
        workloadOf(Context.of(input, scopePaperId, range))

    /** The memory tab on its own. */
    fun buildMemory(input: Input, scopePaperId: String?, range: Range): MemoryData {
        val ctx = Context.of(input, scopePaperId, range)
        val heatmap = heatmap(ctx.inScope, ctx.todayStart, ctx.cfg, ctx.zone)
        return memoryOf(ctx, kpisOf(ctx, heatmap))
    }

    /** The cards tab on its own. */
    fun buildCards(input: Input, scopePaperId: String?, range: Range): CardsData =
        cardsOf(Context.of(input, scopePaperId, range))

    private fun kpisOf(ctx: Context, heatmap: HeatmapData): OverviewKpis = kpis(
        inRange = ctx.inRange,
        cards = ctx.cards,
        rangeRetention = trueRetention(
            ctx.inRange, ctx.todayStart, ctx.tomorrowStart, ctx.rangeStart, ctx.cfg, ctx.zone
        ),
        heatmap = heatmap,
        input = ctx.input,
        cfg = ctx.cfg,
        zone = ctx.zone
    )

    private fun overviewOf(ctx: Context, kpis: OverviewKpis, heatmap: HeatmapData): OverviewData =
        OverviewData(
            today = today(
                ctx.inScope, ctx.cards, ctx.todayStart, ctx.tomorrowStart, ctx.todayDay,
                ctx.input.now, ctx.cfg, ctx.zone
            ),
            kpis = kpis,
            retentionTable = retentionTable(ctx.inScope, ctx.todayStart, ctx.tomorrowStart, ctx.cfg, ctx.zone),
            heatmap = heatmap,
            fsrsStatus = FsrsStatus(
                fitted = ctx.cfg.fsrsParameters != FsrsScheduler.DEFAULT_FSRS_PARAMETERS,
                reviewCount = ctx.input.reviews.size,
                unlockReviews = FsrsOptimizer.MIN_REVIEWS,
                desiredRetention = ctx.cfg.desiredRetention
            ),
            weakest = weakest(ctx.inRange, ctx.cards)
        )

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

    // --- today strip -------------------------------------------------------

    private fun today(
        inScope: List<ReplayReview>,
        cards: List<CardSnapshot>,
        todayStart: Long,
        tomorrowStart: Long,
        todayDay: Long,
        now: Long,
        cfg: SchedulerConfig,
        zone: Long
    ): TodayStats {
        val todays = inScope.filter { studyEpochDay(it.reviewedAt, cfg, zone) == todayDay }
        val again = todays.count { !it.passed }
        var learning = 0
        var review = 0
        var relearning = 0
        var unknown = 0
        todays.forEach {
            when (it.kind) {
                ReplayReview.ReviewKind.LEARNING -> learning++
                ReplayReview.ReviewKind.REVIEW -> review++
                ReplayReview.ReviewKind.RELEARNING -> relearning++
                null -> unknown++
            }
        }
        return TodayStats(
            reviews = todays.size,
            againCount = again,
            correctPct = if (todays.isEmpty()) null else (todays.size - again).toDouble() / todays.size,
            timeSeconds = todays.sumOf { it.elapsedSeconds ?: 0L },
            learning = learning,
            review = review,
            relearning = relearning,
            unknown = unknown,
            introduced = todays.count { it.stateBefore == CardStateKind.NEW },
            dueNow = cards.count {
                // Scheduled for this study day; overdue cards live in the
                // backlog instead of being double-counted here.
                it.state.dueAt != 0L && it.state.state != CardStateKind.NEW &&
                    it.state.dueAt >= todayStart && it.state.dueAt < tomorrowStart
            },
            backlog = cards.count {
                it.state.dueAt != 0L && it.state.state != CardStateKind.NEW &&
                    it.state.dueAt < now
            }
        )
    }

    // --- true retention ----------------------------------------------------

    /**
     * Anki's true-retention rule: only the first review of each card on each
     * study day counts, Again is a fail, and maturity is the interval the card
     * carried into the review. Learning-step rows and first exposures (which
     * carried no whole-day interval) are excluded, mirroring Anki's
     * `last_interval >= 1` / `last_interval <= -86400` check. Returns the
     * pass/review counts of the qualifying reviews inside the window.
     */
    private fun firstOfDayReviews(
        reviews: List<ReplayReview>,
        windowStart: Long,
        windowEnd: Long,
        cfg: SchedulerConfig,
        zone: Long
    ): List<ReplayReview> {
        // Single pass: keep the earliest qualifying review per (card, study
        // day). The old `groupBy {}` + `minByOrNull` built a list per card-day
        // purely to read one element back out of it, and no caller depends on
        // the order of the result, only on the set.
        val earliest = HashMap<Pair<String, Long>, ReplayReview>()
        reviews.forEach { review ->
            if (review.reviewedAt < windowStart || review.reviewedAt >= windowEnd) return@forEach
            if ((review.intervalBeforeDays ?: 0) < 1) return@forEach
            val key = review.questionId to studyEpochDay(review.reviewedAt, cfg, zone)
            val current = earliest[key]
            if (current == null || review.reviewedAt < current.reviewedAt) earliest[key] = review
        }
        return earliest.values.toList()
    }

    private fun retentionRow(
        label: String,
        qualifying: List<ReplayReview>
    ): RetentionRow {
        val young = qualifying.filter { !it.matureAtReview }
        val mature = qualifying.filter { it.matureAtReview }
        return RetentionRow(
            label = label,
            allReviews = qualifying.size,
            allPasses = qualifying.count { it.passed },
            youngReviews = young.size,
            youngPasses = young.count { it.passed },
            matureReviews = mature.size,
            maturePasses = mature.count { it.passed }
        )
    }

    private fun retentionTable(
        inScope: List<ReplayReview>,
        todayStart: Long,
        tomorrowStart: Long,
        cfg: SchedulerConfig,
        zone: Long
    ): List<RetentionRow> {
        val todayDay = studyEpochDay(todayStart, cfg, zone)
        data class Window(val label: String, val fromDay: Long, val toDay: Long)

        // One pass for all seven rows. Each row used to re-scan and re-group the
        // whole scope for its own window - seven full passes over a 1M-row log.
        // The windows are study-day ranges, and a (card, day) contributes its
        // earliest qualifying review to whichever window asks, so the pass
        // builds that map once and each row slices it. Rows are day ranges
        // because the window starts are day boundaries: yesterday ends where
        // today begins, so day membership decides membership exactly.
        val earliest = HashMap<Pair<String, Long>, ReplayReview>()
        inScope.forEach { review ->
            if (review.reviewedAt >= tomorrowStart) return@forEach
            if ((review.intervalBeforeDays ?: 0) < 1) return@forEach
            val key = review.questionId to studyEpochDay(review.reviewedAt, cfg, zone)
            val current = earliest[key]
            if (current == null || review.reviewedAt < current.reviewedAt) earliest[key] = review
        }
        val windows = listOf(
            Window("today", todayDay, todayDay + 1),
            Window("yesterday", todayDay - 1, todayDay),
            Window("last7", todayDay - 6, todayDay + 1),
            Window("last30", todayDay - 29, todayDay + 1),
            Window("last90", todayDay - 89, todayDay + 1),
            Window("last365", todayDay - 364, todayDay + 1),
            Window("all", Long.MIN_VALUE, todayDay + 1)
        )
        val all = IntArray(windows.size)
        val allPasses = IntArray(windows.size)
        val young = IntArray(windows.size)
        val youngPasses = IntArray(windows.size)
        val mature = IntArray(windows.size)
        val maturePasses = IntArray(windows.size)
        earliest.forEach { (key, review) ->
            val reviewedDay = key.second
            windows.forEachIndexed { i, window ->
                if (reviewedDay < window.fromDay || reviewedDay >= window.toDay) return@forEachIndexed
                all[i]++
                if (review.passed) {
                    allPasses[i]++
                    if (review.matureAtReview) maturePasses[i]++ else youngPasses[i]++
                }
                if (review.matureAtReview) mature[i]++ else young[i]++
            }
        }
        return windows.mapIndexed { i, window ->
            RetentionRow(
                label = window.label,
                allReviews = all[i],
                allPasses = allPasses[i],
                youngReviews = young[i],
                youngPasses = youngPasses[i],
                matureReviews = mature[i],
                maturePasses = maturePasses[i]
            )
        }
    }

    // --- overview kpis -----------------------------------------------------

    private fun kpis(
        inRange: List<ReplayReview>,
        cards: List<CardSnapshot>,
        rangeRetention: Pair<RetentionRow, RetentionRow>,
        heatmap: HeatmapData,
        input: Input,
        cfg: SchedulerConfig,
        zone: Long
    ): OverviewKpis {
        val timed = inRange.mapNotNull { it.elapsedSeconds }
        val days = inRange.mapTo(HashSet()) { studyEpochDay(it.reviewedAt, cfg, zone) }
        val intervals = cards.mapNotNull {
            if (it.state.intervalDays > 0) it.state.intervalDays.toDouble() else null
        }
        val knowledge = cards.sumOf {
            if (it.state.stability > 0.0) input.scheduler.retention(it.state, input.now) else 0.0
        }
        return OverviewKpis(
            reviews = inRange.size,
            days = days.size,
            timeSeconds = timed.sum(),
            avgAnswerSeconds = if (timed.isEmpty()) null else timed.average(),
            retention = rangeRetention.first.allRetention,
            matureRetention = rangeRetention.second.matureRetention,
            desiredRetention = input.config.desiredRetention,
            estimatedKnowledge = knowledge.roundToInt(),
            avgIntervalDays = if (intervals.isEmpty()) null else intervals.average(),
            leeches = cards.count { it.state.leech },
            currentStreak = heatmap.currentStreak,
            longestStreak = heatmap.longestStreak
        )
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

        /** Study day last counted for a card, to spot a day's first review. */
        private val lastCountedDay = HashMap<String, Long>()

        /** Study day → that day's first qualifying reviews. */
        private val trendDays = HashMap<Long, Counts>()

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
            // The true-retention rule: only a card's first qualifying review of
            // a study day counts, and a first exposure carries no whole-day
            // interval to qualify with. Days only move forward per card, so
            // remembering the last one counted is enough to spot the first.
            if ((review.intervalBeforeDays ?: 0) < 1) return
            val day = studyEpochDay(reviewedAt, cfg, zone)
            if (lastCountedDay[review.questionId] == day) return
            lastCountedDay[review.questionId] = day
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

    // --- heatmap -----------------------------------------------------------

    private fun heatmap(
        inScope: List<ReplayReview>,
        todayStart: Long,
        cfg: SchedulerConfig,
        zone: Long
    ): HeatmapData {
        val year = 365
        val firstDay = todayStart - (year - 1) * DayBoundary.DAY_MS
        val byDay = inScope.groupBy { studyEpochDay(it.reviewedAt, cfg, zone) }
        val todayDay = studyEpochDay(todayStart, cfg, zone)
        val days = (0 until year).map { i ->
            val day = todayDay - (year - 1 - i)
            val rows = byDay[day].orEmpty()
            HeatmapDay(
                dayStart = firstDay + i * DayBoundary.DAY_MS,
                reviews = rows.size,
                seconds = rows.sumOf { it.elapsedSeconds ?: 0L }
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

    // --- weakest cards -------------------------------------------------------

    private fun weakest(
        inRange: List<ReplayReview>,
        cards: List<CardSnapshot>
    ): List<WeakCard> {
        val leechByQuestion = cards.filter { it.state.leech }.associate { it.state.questionId to true }
        // One pass, in place of `groupBy {}`: the old shape held a list per
        // question (up to 250k lists over a 1M-row log) purely to read four
        // aggregates off it. `order` reproduces groupBy's first-seen order,
        // which the tie-break in the sort below depends on.
        val rollOf = HashMap<String, QuestionRoll>()
        val order = ArrayList<String>()
        inRange.forEach { review ->
            val roll = rollOf.getOrPut(review.questionId) {
                order += review.questionId
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
        return order.mapNotNull { questionId ->
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
                leech = leechByQuestion.containsKey(questionId)
            )
        }
            .sortedWith(compareBy({ -it.againRate }, { -it.lapses }))
            .take(15)
    }

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

    private fun trueRetention(
        inRange: List<ReplayReview>,
        todayStart: Long,
        tomorrowStart: Long,
        rangeStart: Long?,
        cfg: SchedulerConfig,
        zone: Long
    ): Pair<RetentionRow, RetentionRow> {
        val start = rangeStart ?: Long.MIN_VALUE / 2
        val qualifying = firstOfDayReviews(inRange, start, tomorrowStart, cfg, zone)
        return retentionRow("range", qualifying) to
            retentionRow("range-mature", qualifying.filter { it.matureAtReview })
    }
}
