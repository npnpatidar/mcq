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
        val zoneOffsetMillis: Long,
        /**
         * questionId → chance a uniform random pick is exactly right, for the
         * guessed questions only. The guessing tab's baseline reads it; every
         * other section may pass an empty map.
         */
        val chanceOfQuestion: Map<String, Double> = emptyMap()
    )

    /**
     * One build's shared inputs: the scope-filtered log — only the retention
     * accumulator reads it — and the day arithmetic. Sections take this instead
     * of re-deriving it, which is what lets any one of them be built alone.
     */
    private class Context(
        val input: Input,
        val inScope: List<ReplayReview>,
        val scopePaperId: String?,
        val range: Range
    ) {
        val cfg: SchedulerConfig get() = input.config
        val zone: Long get() = input.zoneOffsetMillis

        companion object {
            fun of(input: Input, scopePaperId: String?, range: Range): Context = Context(
                input = input,
                inScope = input.reviews.filter { scopePaperId == null || it.paperId == scopePaperId },
                scopePaperId = scopePaperId,
                range = range
            )
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
            cards = cardsOf(ctx),
            guessing = guessingOf(ctx)
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

    private fun workloadOf(ctx: Context): WorkloadData {
        val accumulator = WorkloadAccumulator(
            scopePaperId = ctx.scopePaperId,
            range = ctx.range,
            now = ctx.input.now,
            cfg = ctx.cfg,
            zone = ctx.zone
        )
        // The whole log and every card, not the scope's own slices: the
        // accumulator applies the scope itself, so the streamed build — which
        // pages the same rows — and this one cannot drift.
        ctx.input.reviews.forEach(accumulator::add)
        ctx.input.cards.forEach(accumulator::addCard)
        return accumulator.toData()
    }

    private fun memoryOf(ctx: Context, kpis: OverviewKpis): MemoryData {
        val accumulator = MemoryAccumulator(
            scopePaperId = ctx.scopePaperId,
            now = ctx.input.now,
            cfg = ctx.cfg,
            zone = ctx.zone,
            scheduler = ctx.input.scheduler
        )
        ctx.input.reviews.forEach(accumulator::add)
        ctx.input.cards.forEach(accumulator::addCard)
        return accumulator.toData(kpis)
    }

    private fun cardsOf(ctx: Context): CardsData {
        val accumulator = CardsAccumulator(
            scopePaperId = ctx.scopePaperId,
            now = ctx.input.now,
            paperTitleOf = ctx.input.paperTitleOf
        )
        // The cards first: the leech roll-up can only recognise a leech once
        // its card has been seen.
        ctx.input.cards.forEach(accumulator::addCard)
        ctx.input.reviews.forEach(accumulator::add)
        return accumulator.toData()
    }

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

    /** Label for a paper that no longer exists (its reviews still count). */
    private const val UNKNOWN_PAPER = "Unknown paper"

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
                paperTitleOf[key] ?: UNKNOWN_PAPER
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

    // --- per-question roll-ups ----------------------------------------------

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

    /**
     * The numbers behind one distribution. The bins and the mean are counters,
     * but a true median needs the middle value, so the numbers are kept — in a
     * `DoubleArray`, eight bytes each, not one [CardSnapshot] object per card.
     * At a million studied cards the four distributions are ~32 MB between
     * them, against a hundred-plus for the snapshot list they replaced.
     */
    private class Values {
        private var buffer = DoubleArray(16)
        private var size = 0

        fun add(value: Double) {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[size++] = value
        }

        fun forEach(action: (Double) -> Unit) {
            for (i in 0 until size) action(buffer[i])
        }

        fun mean(): Double? {
            if (size == 0) return null
            var sum = 0.0
            for (i in 0 until size) sum += buffer[i]
            return sum / size
        }

        fun median(): Double? {
            if (size == 0) return null
            val sorted = buffer.copyOf(size)
            sorted.sort()
            val mid = size / 2
            return if (size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /** A histogram over [values] against fixed [edges]; the median is the true one. */
    private fun histogram(values: Values, edges: List<Double>, labels: List<String>): Distribution {
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
            mean = values.mean(),
            median = values.median()
        )
    }

    // --- workload accumulator ------------------------------------------------

    /**
     * The workload tab as one bounded pass over the log and one over the cards.
     *
     * Every figure here used to be derived from the whole log and the whole card
     * library — the page's two largest constructs. Each is now a counter, a
     * per-day bucket, or a per-question roll-up: the day maps and the histogram
     * are O(days), the roll-ups are O(questions in range), which is the replay's
     * own working set, and nothing is O(reviews).
     *
     * The in-memory and the streamed build feed this same accumulator, so the
     * two cannot drift apart.
     */
    class WorkloadAccumulator(
        private val scopePaperId: String?,
        private val range: Range,
        private val now: Long,
        private val cfg: SchedulerConfig,
        private val zone: Long
    ) {
        private class DayBucket {
            /** LEARNING, REVIEW, RELEARNING, unknown — the chart's legend order. */
            val kinds = IntArray(4)
            var reviews = 0
            var seconds = 0L
            var timedReviews = 0
            var timedSeconds = 0L

            fun add(review: ReplayReview) {
                reviews++
                when (review.kind) {
                    ReplayReview.ReviewKind.LEARNING -> kinds[0]++
                    ReplayReview.ReviewKind.REVIEW -> kinds[1]++
                    ReplayReview.ReviewKind.RELEARNING -> kinds[2]++
                    null -> kinds[3]++
                }
                seconds += review.elapsedSeconds ?: 0L
                review.elapsedSeconds?.let {
                    timedReviews++
                    timedSeconds += it
                }
            }
        }

        private val todayStart = DayBoundary.startOfDay(now, cfg.dayStartHour, zone)
        private val todayDay = studyEpochDay(todayStart, cfg, zone)
        private val rangeStart = range.days?.let { todayStart - (it - 1) * DayBoundary.DAY_MS }

        /** Study day → its in-range totals. O(days), never O(reviews). */
        private val days = HashMap<Long, DayBucket>()

        /** The oldest in-range review; it fixes the ALL-range window. */
        private var earliest: Long? = null

        private val forecastLearning = IntArray(FORECAST_DAYS)
        private val forecastReview = IntArray(FORECAST_DAYS)
        private var forecastOverdue = 0
        private var forecastLoad = 0.0

        /** Answer-button counts per [AnswerButtonsData.Kind], first-seen order. */
        private val answerKinds = LinkedHashMap<AnswerButtonsData.Kind, IntArray>()

        private val hourReviews = IntArray(24)
        private val hourPasses = IntArray(24)

        private val responseHistogram = IntArray(RESPONSE_LABELS.size)
        private val gradeReviews = IntArray(4)
        private val gradeSeconds = DoubleArray(4)
        private var fastFails = 0

        /** One roll per question in range; the slowest list ranks these. */
        private val rollOf = HashMap<String, QuestionRoll>()
        private val timedOrder = ArrayList<String>()

        private val leechQuestions = HashSet<String>()

        /** Feeds one replayed review, in per-card chronological order. */
        fun add(review: ReplayReview) {
            if (scopePaperId != null && review.paperId != scopePaperId) return
            if (rangeStart != null && review.reviewedAt < rangeStart) return
            val seen = earliest
            if (seen == null || review.reviewedAt < seen) earliest = review.reviewedAt
            days.getOrPut(studyEpochDay(review.reviewedAt, cfg, zone)) { DayBucket() }.add(review)
            countAnswerButtons(review)
            val hour = (Math.floorMod(review.reviewedAt + zone, DayBoundary.DAY_MS) / HOUR_MS).toInt()
            hourReviews[hour]++
            if (review.passed) hourPasses[hour]++
            review.elapsedSeconds?.let { seconds ->
                var bin = RESPONSE_EDGES.indexOfFirst { seconds < it }
                if (bin < 0) bin = RESPONSE_LABELS.size - 1
                responseHistogram[bin]++
                if (review.rating in 1..4) {
                    gradeReviews[review.rating - 1]++
                    gradeSeconds[review.rating - 1] += seconds
                }
                if (!review.passed && seconds < 5) fastFails++
            }
            countRoll(review)
        }

        /** Feeds one scoped card: [CardSnapshot] carries its current schedule. */
        fun addCard(snapshot: CardSnapshot) {
            if (scopePaperId != null && snapshot.paperId != scopePaperId) return
            val state = snapshot.state
            if (state.leech) leechQuestions += state.questionId
            if (state.state == CardStateKind.NEW || state.dueAt == 0L) return
            forecastLoad += if (state.intervalDays <= 0) 1.0 else 1.0 / state.intervalDays
            val dayIndex = Math.floorDiv(state.dueAt - todayStart, DayBoundary.DAY_MS)
            when {
                dayIndex < 0 -> forecastOverdue++
                dayIndex < FORECAST_DAYS ->
                    if (state.state == CardStateKind.LEARNING ||
                        state.state == CardStateKind.RELEARNING
                    ) {
                        forecastLearning[dayIndex.toInt()]++
                    } else {
                        forecastReview[dayIndex.toInt()]++
                    }
            }
        }

        /** The workload tab's slice. */
        fun toData(): WorkloadData = WorkloadData(
            forecast = forecast(),
            reviewsPerDay = reviewsPerDay(),
            timePerDay = timePerDay(),
            answerButtons = answerButtons(),
            hourly = hourly(),
            responseTime = responseTime()
        )

        private fun countAnswerButtons(review: ReplayReview) {
            val kind = when {
                review.kind == null -> return
                review.kind == ReplayReview.ReviewKind.RELEARNING ->
                    AnswerButtonsData.Kind.RELEARNING
                review.kind == ReplayReview.ReviewKind.REVIEW && review.matureAtReview ->
                    AnswerButtonsData.Kind.MATURE
                review.kind == ReplayReview.ReviewKind.REVIEW -> AnswerButtonsData.Kind.YOUNG
                else -> AnswerButtonsData.Kind.LEARNING
            }
            val counts = answerKinds.getOrPut(kind) { IntArray(4) }
            when (review.rating) {
                1 -> counts[0]++
                2 -> counts[1]++
                3 -> counts[2]++
                // Anything else (Easy) — corrupt ratings fold here rather
                // than vanishing from the total (E11).
                else -> counts[3]++
            }
        }

        private fun countRoll(review: ReplayReview) {
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

        private fun forecast(): ForecastData {
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
            return ForecastData(
                days = (0 until FORECAST_DAYS).map { i ->
                    ForecastDay(
                        label = LocalDate.ofEpochDay(todayDay + i).format(fmt),
                        learning = forecastLearning[i],
                        review = forecastReview[i]
                    )
                },
                overdue = forecastOverdue,
                dueToday = forecastLearning[0] + forecastReview[0],
                dailyLoad = forecastLoad
            )
        }

        private fun reviewsPerDay(): List<StackedPoint> {
            val windowStart = rangeStart ?: earliest ?: return emptyList()
            val granularity = granularityFor(windowStart, todayDay, cfg, zone)
            val buckets = bucketList(
                studyEpochDay(windowStart, cfg, zone), todayDay, granularity, cfg, zone
            )
            val kindsByBucket = HashMap<Long, IntArray>()
            val reviewsByBucket = HashMap<Long, Int>()
            days.forEach { (day, bucket) ->
                val key = bucketKeyOf(day, granularity)
                val kinds = kindsByBucket.getOrPut(key) { IntArray(4) }
                for (i in 0 until 4) kinds[i] += bucket.kinds[i]
                reviewsByBucket[key] = (reviewsByBucket[key] ?: 0) + bucket.reviews
            }
            return buckets.map { b ->
                val kinds = kindsByBucket[b.key]
                StackedPoint(
                    label = b.label,
                    segments = (0 until 4).map { (kinds?.get(it) ?: 0).toDouble() },
                    total = reviewsByBucket[b.key] ?: 0
                )
            }
        }

        private fun timePerDay(): List<SeriesPoint> {
            val windowStart = rangeStart ?: earliest ?: return emptyList()
            val granularity = granularityFor(windowStart, todayDay, cfg, zone)
            val buckets = bucketList(
                studyEpochDay(windowStart, cfg, zone), todayDay, granularity, cfg, zone
            )
            val secondsByBucket = HashMap<Long, Long>()
            val countByBucket = HashMap<Long, Int>()
            days.forEach { (day, bucket) ->
                val key = bucketKeyOf(day, granularity)
                secondsByBucket[key] = (secondsByBucket[key] ?: 0L) + bucket.seconds
                countByBucket[key] = (countByBucket[key] ?: 0) + bucket.reviews
            }
            return buckets.map { b ->
                val count = countByBucket[b.key] ?: 0
                SeriesPoint(
                    label = b.label,
                    value = if (count == 0) null else (secondsByBucket[b.key] ?: 0L).toDouble(),
                    count = count
                )
            }
        }

        private fun answerButtons(): AnswerButtonsData = AnswerButtonsData(
            ANSWER_KIND_ORDER.mapNotNull { kind ->
                answerKinds[kind]?.let { c ->
                    AnswerButtonsData.Group(kind, c[0], c[1], c[2], c[3])
                }
            }
        )

        private fun hourly(): List<HourlyStat> = (0 until 24).map { hour ->
            val reviews = hourReviews[hour]
            HourlyStat(
                hour = hour,
                reviews = reviews,
                passRate = if (reviews == 0) null else hourPasses[hour].toDouble() / reviews
            )
        }

        private fun responseTime(): ResponseTimeData {
            val byGrade = ReviewGrade.entries.map { grade ->
                val index = grade.ordinal
                ResponseTimeData.GradeStat(
                    grade = grade,
                    reviews = gradeReviews[index],
                    avgSeconds = if (gradeReviews[index] == 0) {
                        null
                    } else {
                        gradeSeconds[index] / gradeReviews[index]
                    }
                )
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
                    leech = leechQuestions.contains(questionId)
                )
            }
            return ResponseTimeData(
                histogram = RESPONSE_LABELS.mapIndexed { i, label ->
                    SeriesPoint(label, responseHistogram[i].toDouble(), responseHistogram[i])
                },
                avgByGrade = byGrade,
                trend = responseTrend(),
                slowest = perCard.sortedByDescending { it.avgSeconds }.take(10),
                fastFails = fastFails
            )
        }

        private fun responseTrend(): List<SeriesPoint> {
            val windowStart = rangeStart ?: earliest ?: return emptyList()
            val granularity = granularityFor(windowStart, todayDay, cfg, zone)
            val buckets = bucketList(
                studyEpochDay(windowStart, cfg, zone), todayDay, granularity, cfg, zone
            )
            val secondsByBucket = HashMap<Long, Long>()
            val countByBucket = HashMap<Long, Int>()
            days.forEach { (day, bucket) ->
                if (bucket.timedReviews == 0) return@forEach
                val key = bucketKeyOf(day, granularity)
                secondsByBucket[key] = (secondsByBucket[key] ?: 0L) + bucket.timedSeconds
                countByBucket[key] = (countByBucket[key] ?: 0) + bucket.timedReviews
            }
            return buckets.map { b ->
                val count = countByBucket[b.key] ?: 0
                SeriesPoint(
                    label = b.label,
                    value = if (count == 0) {
                        null
                    } else {
                        (secondsByBucket[b.key] ?: 0L).toDouble() / count
                    },
                    count = count
                )
            }
        }

        private companion object {
            const val FORECAST_DAYS = 30
            val RESPONSE_EDGES = listOf(5L, 10L, 20L, 30L, 60L, 120L)
            val RESPONSE_LABELS =
                listOf("0–5s", "5–10s", "10–20s", "20–30s", "30–60s", "1–2m", "2m+")
            val ANSWER_KIND_ORDER = listOf(
                AnswerButtonsData.Kind.LEARNING,
                AnswerButtonsData.Kind.YOUNG,
                AnswerButtonsData.Kind.MATURE,
                AnswerButtonsData.Kind.RELEARNING
            )
        }
    }

    // --- memory accumulator --------------------------------------------------

    /**
     * The memory tab as bounded passes: the log for the memory-growth curves and
     * the cards for the four FSRS-state distributions.
     *
     * The distributions need a true median, which no counter can give, so each
     * keeps its values in a [Values] — eight bytes a value, not one snapshot
     * object a card. The growth curves are counters: one contributing-review
     * index per question, and a sum and count per review number and per month.
     *
     * The in-memory and the streamed build feed this same accumulator, so the
     * two cannot drift apart.
     */
    class MemoryAccumulator(
        private val scopePaperId: String?,
        private val now: Long,
        private val cfg: SchedulerConfig,
        private val zone: Long,
        private val scheduler: FsrsScheduler
    ) {
        private val stability = Values()
        private val difficulty = Values()
        private val retrievability = Values()
        private val intervals = Values()

        /** Contributing reviews seen per question, capped at the last bucket. */
        private val contributing = HashMap<String, Int>()
        private val byNumberSum = HashMap<Int, Double>()
        private val byNumberCount = HashMap<Int, Int>()
        private val monthlySum = HashMap<Long, Double>()
        private val monthlyCount = HashMap<Long, Int>()

        /** Feeds one replayed review: the growth curves, all history not range. */
        fun add(review: ReplayReview) {
            if (scopePaperId != null && review.paperId != scopePaperId) return
            val value = review.stabilityAfter ?: return
            val number = ((contributing[review.questionId] ?: 0) + 1).coerceAtMost(CAPPED_NUMBERS)
            contributing[review.questionId] = number
            byNumberSum[number] = (byNumberSum[number] ?: 0.0) + value
            byNumberCount[number] = (byNumberCount[number] ?: 0) + 1
            val day = LocalDate.ofEpochDay(studyEpochDay(review.reviewedAt, cfg, zone))
            val key = day.year * 12L + day.monthValue - 1
            monthlySum[key] = (monthlySum[key] ?: 0.0) + value
            monthlyCount[key] = (monthlyCount[key] ?: 0) + 1
        }

        /** Feeds one scoped card into the four FSRS-state distributions. */
        fun addCard(snapshot: CardSnapshot) {
            if (scopePaperId != null && snapshot.paperId != scopePaperId) return
            val state = snapshot.state
            if (state.stability > 0.0) {
                stability.add(state.stability)
                retrievability.add(scheduler.retention(state, now))
            }
            if (state.difficulty > 0.0) difficulty.add(state.difficulty)
            if (state.intervalDays > 0) intervals.add(state.intervalDays.toDouble())
        }

        /** The memory tab's slice; [kpis] comes from the overview accumulator. */
        fun toData(kpis: OverviewKpis): MemoryData = MemoryData(
            kpis = kpis,
            stability = histogram(stability, STABILITY_EDGES, STABILITY_LABELS),
            difficulty = difficultyDistribution(difficulty),
            retrievability = retrievabilityDistribution(retrievability),
            intervals = histogram(intervals, INTERVAL_EDGES, INTERVAL_LABELS),
            memoryGrowth = memoryGrowth()
        )

        private fun memoryGrowth(): MemoryGrowthData {
            val points = (1..CAPPED_NUMBERS).map { number ->
                val count = byNumberCount[number] ?: 0
                SeriesPoint(
                    label = if (number == CAPPED_NUMBERS) "$number+" else "$number",
                    value = if (count == 0) null else byNumberSum.getValue(number) / count,
                    count = count
                )
            }
            val monthFmt = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)
            val overTime = monthlySum.keys.sorted().map { key ->
                val date = LocalDate.of((key / 12).toInt(), (key % 12 + 1).toInt(), 1)
                SeriesPoint(
                    label = date.format(monthFmt),
                    value = monthlySum.getValue(key) / monthlyCount.getValue(key),
                    count = monthlyCount.getValue(key)
                )
            }
            return MemoryGrowthData(points, overTime)
        }

        private fun difficultyDistribution(values: Values): Distribution {
            val counts = IntArray(10)
            values.forEach { counts[(floor(it) - 1).toInt().coerceIn(0, 9)]++ }
            val bins = (0 until 10).map { i ->
                Distribution.Bin((i + 1).toString(), i + 1.0, i + 2.0, counts[i])
            }
            return Distribution(bins, values.mean(), values.median())
        }

        private fun retrievabilityDistribution(values: Values): Distribution {
            val counts = IntArray(10)
            values.forEach { counts[(it * 10).toInt().coerceIn(0, 9)]++ }
            val bins = (0 until 10).map { i ->
                Distribution.Bin(
                    "${i * 10}–${(i + 1) * 10}%", i / 10.0, (i + 1) / 10.0, counts[i]
                )
            }
            return Distribution(bins, values.mean(), values.median())
        }

        private companion object {
            const val CAPPED_NUMBERS = 15
            val STABILITY_EDGES = listOf(1.0, 3.0, 7.0, 14.0, 21.0, 45.0, 90.0, 180.0, 365.0)
            val STABILITY_LABELS = listOf(
                "<1d", "1–3d", "3–7d", "1–2w", "2–3w", "3w–1.5m",
                "1.5–3m", "90–180d", "6m–1y", ">1y"
            )
            val INTERVAL_EDGES = listOf(2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 512.0)
            val INTERVAL_LABELS = listOf(
                "1d", "2–3d", "4–7d", "8–15d", "16–31d",
                "1–2m", "2–4m", "4–8m", "8–17m", "17m+"
            )
        }
    }

    // --- cards accumulator ---------------------------------------------------

    /**
     * The cards tab as bounded passes: the cards for the counts, the overdue
     * backlog and the per-paper table, then the log for the leech figures. The
     * leech roll-up keeps one roll per *flagged* card only, so the log pass costs
     * O(leeches) state, and the per-paper table is one bucket per paper (a
     * thousand at most), never one per card.
     *
     * The cards must be fed before the reviews: a leech can only be rolled up
     * once its card has been seen. The streamed build pages the cards first for
     * exactly this reason.
     *
     * The in-memory and the streamed build feed this same accumulator, so the
     * two cannot drift apart.
     */
    class CardsAccumulator(
        private val scopePaperId: String?,
        private val now: Long,
        private val paperTitleOf: Map<String, String>
    ) {
        private var fresh = 0
        private var learning = 0
        private var relearning = 0
        private var young = 0
        private var mature = 0
        private var leechCount = 0

        private val overdueCounts = IntArray(OVERDUE_BINS.size)
        private var overdue = 0
        private var ageSum = 0.0
        private var oldest = 0

        /** paperId → [total, fresh, learning, relearning, young, mature], first-seen order. */
        private val perPaper = LinkedHashMap<String, IntArray>()

        /** The flagged cards' question → paper, in card order. */
        private val leechPaperOf = LinkedHashMap<String, String>()
        private val rollOf = HashMap<String, QuestionRoll>()

        /** Feeds one scoped card: the counts, the per-paper table and the backlog. */
        fun addCard(snapshot: CardSnapshot) {
            if (scopePaperId != null && snapshot.paperId != scopePaperId) return
            val state = snapshot.state
            val bucket = when {
                state.state == CardStateKind.NEW -> 0
                state.state == CardStateKind.LEARNING -> 1
                state.state == CardStateKind.RELEARNING -> 2
                state.intervalDays >= ReplayReview.MATURE_DAYS -> 4
                else -> 3
            }
            when (bucket) {
                0 -> fresh++
                1 -> learning++
                2 -> relearning++
                3 -> young++
                else -> mature++
            }
            if (state.leech) {
                leechCount++
                leechPaperOf[state.questionId] = snapshot.paperId
            }
            if (scopePaperId == null) {
                val counts = perPaper.getOrPut(snapshot.paperId) { IntArray(6) }
                counts[0]++
                counts[bucket + 1]++
            }
            if (state.dueAt != 0L && state.state != CardStateKind.NEW && state.dueAt < now) {
                val age = floor((now - state.dueAt).toDouble() / DayBoundary.DAY_MS).toInt()
                    .coerceAtLeast(0)
                overdue++
                ageSum += age
                oldest = maxOf(oldest, age)
                val index = OVERDUE_BINS.indexOfFirst { (_, from, to) ->
                    age >= from && (to == null || age < to)
                }
                if (index >= 0) overdueCounts[index]++
            }
        }

        /** Feeds one scoped review, rolled up only for the flagged cards. */
        fun add(review: ReplayReview) {
            if (scopePaperId != null && review.paperId != scopePaperId) return
            if (review.questionId !in leechPaperOf) return
            val roll = rollOf.getOrPut(review.questionId) { QuestionRoll() }
            roll.reviews++
            if (!review.passed) roll.lapses++
            if (review.reviewedAt > roll.lastReviewedAt) roll.lastReviewedAt = review.reviewedAt
            review.elapsedSeconds?.let {
                roll.timedCount++
                roll.timedSeconds += it
            }
        }

        /** The cards tab's slice. */
        fun toData(): CardsData = CardsData(
            cardCounts = CardCountsData(fresh, learning, relearning, young, mature, leechCount),
            overdueAge = overdueAge(),
            paperCounts = if (scopePaperId == null) paperCounts() else emptyList(),
            leeches = leeches()
        )

        private fun overdueAge(): OverdueAgeData = OverdueAgeData(
            bins = OVERDUE_BINS.mapIndexed { i, (label, from, to) ->
                OverdueAgeData.OverdueAgeBin(label, from, to, overdueCounts[i])
            },
            overdue = overdue,
            meanAgeDays = if (overdue == 0) null else ageSum / overdue,
            oldestAgeDays = if (overdue == 0) null else oldest
        )

        private fun paperCounts(): List<PaperCardCounts> = perPaper.map { (paperId, c) ->
            PaperCardCounts(
                paperId = paperId,
                label = paperTitleOf[paperId] ?: UNKNOWN_PAPER,
                total = c[0],
                fresh = c[1],
                learning = c[2],
                relearning = c[3],
                young = c[4],
                mature = c[5]
            )
        }.sortedWith(compareByDescending<PaperCardCounts> { it.total }.thenBy { it.label })

        private fun leeches(): List<WeakCard> = leechPaperOf.mapNotNull { (questionId, paperId) ->
            val roll = rollOf[questionId] ?: return@mapNotNull null
            WeakCard(
                paperId = paperId,
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
                leech = true
            )
        }.sortedByDescending { it.lapses }.take(15)

        private companion object {
            val OVERDUE_BINS: List<Triple<String, Int, Int?>> = listOf(
                Triple("<1d", 0, 1),
                Triple("1–2d", 1, 2),
                Triple("2–4d", 2, 4),
                Triple("4–7d", 4, 7),
                Triple("1–2w", 7, 14),
                Triple("2w+", 14, null)
            )
        }
    }

    // --- guessing ------------------------------------------------------------

    /**
     * The guessing tab on its own. Only the log is read: every figure is a
     * count over flagged reviews, and the chance baseline is supplied by the
     * caller (the store resolves option/key counts for guessed questions).
     */
    fun buildGuessing(input: Input, scopePaperId: String?, range: Range): GuessingData {
        val ctx = Context.of(input, scopePaperId, range)
        val accumulator = GuessingAccumulator(
            scopePaperId = scopePaperId,
            range = range,
            now = input.now,
            cfg = ctx.cfg,
            zone = ctx.zone,
            paperTitleOf = input.paperTitleOf,
            categoryOfQuestion = input.categoryOfQuestion
        )
        ctx.inScope.forEach(accumulator::add)
        return accumulator.toData(input.chanceOfQuestion)
    }

    private fun guessingOf(ctx: Context): GuessingData {
        val accumulator = GuessingAccumulator(
            scopePaperId = ctx.scopePaperId,
            range = ctx.range,
            now = ctx.input.now,
            cfg = ctx.cfg,
            zone = ctx.zone,
            paperTitleOf = ctx.input.paperTitleOf,
            categoryOfQuestion = ctx.input.categoryOfQuestion
        )
        ctx.inScope.forEach(accumulator::add)
        return accumulator.toData(ctx.input.chanceOfQuestion)
    }

    /**
     * Counts the guessing dashboard in one pass over the in-scope log.
     *
     * Only rows that carry both flags are classified. Rows written before v19
     * have no flag and are counted in [allReviews] only, so the screen can say
     * how much history the figures leave out instead of pretending it was
     * earned.
     *
     * Sitting position uses a 30-minute gap to start a new sitting. The log is
     * fed in append order (the order the app wrote it), which is also how the
     * rest of the replay treats it; a clock rollback therefore starts a new
     * sitting rather than producing a negative gap.
     */
    class GuessingAccumulator(
        private val scopePaperId: String?,
        private val range: Range,
        now: Long,
        private val cfg: SchedulerConfig,
        private val zone: Long,
        private val paperTitleOf: Map<String, String>,
        private val categoryOfQuestion: Map<String, String>
    ) {
        private val todayStart = DayBoundary.startOfDay(now, cfg.dayStartHour, zone)
        private val todayDay = studyEpochDay(todayStart, cfg, zone)
        private val rangeStart = range.days?.let { todayStart - (it - 1) * DayBoundary.DAY_MS }

        private var allReviews = 0
        private var reviews = 0
        private var guessed = 0
        private var earned = 0
        private var lucky = 0
        private var lapse = 0
        private var didntKnow = 0

        /** Guessed rows per question, so the chance baseline can be weighted. */
        private val guessedByQuestion = HashMap<String, Int>()

        /** day → [reviews, guessed, guessedCorrect]. */
        private val days = HashMap<Long, IntArray>()
        /** [reviews, guessed, guessedCorrect] per band, in [GuessBand] order. */
        private val bands = HashMap<GuessBand, IntArray>()
        /** [reviews, guessed, guessedCorrect] per sitting position 1..5+ (index 0..4). */
        private val positions = Array(5) { IntArray(3) }
        /** [reviews, guessed, guessedCorrect] per hour. */
        private val hourly = Array(24) { IntArray(3) }
        /** label → [reviews, guessed, guessedCorrect]. */
        private val topics = HashMap<String, IntArray>()
        /** questionId → [reviews, guessed, guessedCorrect, paperId, lastReviewedAt]. */
        private val cards = HashMap<String, CardGuess>()

        private var guessedDwellSum = 0.0
        private var guessedDwellCount = 0
        private var earnedDwellSum = 0.0
        private var earnedDwellCount = 0

        private var costReviews = 0
        private var costExtraDays = 0
        private var costActualSum = 0.0
        private var costGoodSum = 0.0

        private var earliest: Long? = null
        private var lastReviewAt = Long.MIN_VALUE
        private var sittingPosition = 0

        fun add(review: ReplayReview) {
            if (scopePaperId != null && review.paperId != scopePaperId) return
            if (rangeStart != null && review.reviewedAt < rangeStart) return
            allReviews++
            earliest = earliest?.let { minOf(it, review.reviewedAt) } ?: review.reviewedAt

            // A gap of 30 minutes or more starts a new sitting; a timestamp
            // that moved backwards does too (a clock rollback, not a pause).
            val gap = review.reviewedAt - lastReviewAt
            sittingPosition = if (lastReviewAt == Long.MIN_VALUE || gap < 0 || gap > SITTING_GAP_MS) {
                1
            } else {
                sittingPosition + 1
            }
            lastReviewAt = review.reviewedAt

            val wasGuess = review.wasGuess
            val wasCorrect = review.wasCorrect
            if (wasGuess == null || wasCorrect == null) return

            reviews++
            val hit = if (wasCorrect) 1 else 0
            val band = bandOf(review)
            val topic = topicOf(review)
            val position = sittingPosition.coerceAtMost(5) - 1
            val hour = (Math.floorMod(review.reviewedAt + zone, DayBoundary.DAY_MS) / HOUR_MS).toInt()
            val day = studyEpochDay(review.reviewedAt, cfg, zone)

            fun IntArray.bump() {
                this[0]++
                if (wasGuess) {
                    this[1]++
                    this[2] += hit
                }
            }
            days.getOrPut(day) { IntArray(3) }.bump()
            bands.getOrPut(band) { IntArray(3) }.bump()
            positions[position].bump()
            hourly[hour].bump()
            topics.getOrPut(topic) { IntArray(3) }.bump()

            val card = cards.getOrPut(review.questionId) {
                CardGuess(review.paperId, review.questionId)
            }
            card.reviews++
            if (wasGuess) {
                card.guessed++
                card.guessedCorrect += hit
            }
            card.lastReviewedAt = maxOf(card.lastReviewedAt, review.reviewedAt)

            review.elapsedSeconds?.let { seconds ->
                if (wasGuess) {
                    guessedDwellSum += seconds
                    guessedDwellCount++
                } else {
                    earnedDwellSum += seconds
                    earnedDwellCount++
                }
            }

            if (wasGuess) {
                guessed++
                guessedByQuestion[review.questionId] =
                    (guessedByQuestion[review.questionId] ?: 0) + 1
                if (wasCorrect) lucky++ else didntKnow++
            } else {
                if (wasCorrect) earned++ else lapse++
            }

            val good = review.wouldBeGoodIntervalDays
            val actual = review.intervalDaysAfter
            if (wasGuess && wasCorrect && good != null && actual != null) {
                costReviews++
                costExtraDays += (good - actual).coerceAtLeast(0)
                costActualSum += actual
                costGoodSum += good
            }
        }

        /** The question ids whose chance baseline the caller must resolve. */
        fun guessedQuestionIds(): Set<String> = guessedByQuestion.keys

        fun toData(chanceOfQuestion: Map<String, Double>): GuessingData {
            var chanceSum = 0.0
            var chanceCount = 0
            guessedByQuestion.forEach { (questionId, count) ->
                chanceOfQuestion[questionId]?.let { chance ->
                    chanceSum += chance * count
                    chanceCount += count
                }
            }
            return GuessingData(
                allReviews = allReviews,
                reviews = reviews,
                guessed = guessed,
                earned = earned,
                lucky = lucky,
                lapse = lapse,
                didntKnow = didntKnow,
                chanceBaseline = if (chanceCount == 0) null else chanceSum / chanceCount,
                trend = trend(),
                maturity = GuessBand.entries.map { band ->
                    val counts = bands[band] ?: IntArray(3)
                    GuessBandSlice(band, counts[0], counts[1], counts[2])
                },
                positions = positions.mapIndexed { index, counts ->
                    GuessSlice(
                        label = if (index == 4) "5+" else (index + 1).toString(),
                        reviews = counts[0],
                        guessed = counts[1],
                        guessedCorrect = counts[2]
                    )
                },
                hourly = (0 until 24).map { hour ->
                    val counts = hourly[hour]
                    HourlyGuess(hour, counts[0], counts[1], counts[2])
                },
                dwell = GuessDwell(
                    guessedReviews = guessedDwellCount,
                    guessedAvgSeconds = if (guessedDwellCount == 0) {
                        null
                    } else {
                        guessedDwellSum / guessedDwellCount
                    },
                    earnedReviews = earnedDwellCount,
                    earnedAvgSeconds = if (earnedDwellCount == 0) {
                        null
                    } else {
                        earnedDwellSum / earnedDwellCount
                    }
                ),
                cost = GuessCost(
                    reviews = costReviews,
                    extraDays = costExtraDays,
                    avgActualDays = if (costReviews == 0) null else costActualSum / costReviews,
                    avgGoodDays = if (costReviews == 0) null else costGoodSum / costReviews
                ),
                topics = topics.map { (label, counts) ->
                    GuessTopic(label, counts[0], counts[1], counts[2])
                }.filter { it.guessed > 0 }
                    .sortedWith(
                        compareByDescending<GuessTopic> { it.guessed }
                            .thenBy { it.hitRate ?: 1.0 }
                            .thenBy { it.label }
                    )
                    .take(8),
                shaky = cards.values.map { card ->
                    ShakyCard(
                        paperId = card.paperId,
                        questionId = card.questionId,
                        reviews = card.reviews,
                        guessed = card.guessed,
                        guessedCorrect = card.guessedCorrect,
                        lastReviewedAt = card.lastReviewedAt
                    )
                }.filter { it.guessed >= SHAKY_MIN_GUESSES }
                    .sortedWith(
                        compareByDescending<ShakyCard> { it.guessed }
                            .thenBy { it.hitRate ?: 1.0 }
                            .thenByDescending { it.lastReviewedAt }
                    )
                    .take(10)
            )
        }

        private fun trend(): List<GuessSlice> {
            val start = rangeStart ?: earliest ?: return emptyList()
            val granularity = granularityFor(start, todayDay, cfg, zone)
            val buckets = bucketList(
                studyEpochDay(start, cfg, zone), todayDay, granularity, cfg, zone
            )
            val byBucket = HashMap<Long, IntArray>()
            days.forEach { (day, counts) ->
                val key = bucketKeyOf(day, granularity)
                val into = byBucket.getOrPut(key) { IntArray(3) }
                for (i in 0 until 3) into[i] += counts[i]
            }
            return buckets.map { bucket ->
                val counts = byBucket[bucket.key] ?: IntArray(3)
                GuessSlice(bucket.label, counts[0], counts[1], counts[2])
            }
        }

        private fun bandOf(review: ReplayReview): GuessBand = when (review.stateBefore) {
            CardStateKind.NEW -> GuessBand.FRESH
            CardStateKind.LEARNING -> GuessBand.LEARNING
            CardStateKind.RELEARNING -> GuessBand.RELEARNING
            CardStateKind.REVIEW ->
                if (review.matureAtReview) GuessBand.MATURE else GuessBand.YOUNG
            // Seeded history: the pre-state was never recorded, so the
            // freshest band is the honest default — its label says "unknown".
            null -> GuessBand.FRESH
        }

        private fun topicOf(review: ReplayReview): String =
            categoryOfQuestion[review.questionId]
                ?: paperTitleOf[review.paperId]
                ?: review.paperId

        private class CardGuess(val paperId: String, val questionId: String) {
            var reviews = 0
            var guessed = 0
            var guessedCorrect = 0
            var lastReviewedAt = Long.MIN_VALUE
        }

        private companion object {
            /** A pause this long between reviews starts a new sitting. */
            const val SITTING_GAP_MS = 30 * 60 * 1000L
            /** Below this many guesses a card's hit rate is noise. */
            const val SHAKY_MIN_GUESSES = 2
        }
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

}
