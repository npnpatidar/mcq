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
        /** Every logged review across every paper, replayed by [FsrsReplay]. */
        val reviews: List<ReplayReview>,
        /** Every tracked card with its current schedule. */
        val cards: List<CardSnapshot>,
        /** questionId → category title, for the per-category breakdown. */
        val categoryOfQuestion: Map<String, String>,
        /** paperId → title. */
        val paperTitleOf: Map<String, String>,
        val scheduler: FsrsScheduler,
        val config: SchedulerConfig,
        val now: Long,
        /** The device's UTC offset at [now], in ms. */
        val zoneOffsetMillis: Long
    )

    fun build(input: Input, scopePaperId: String?, range: Range): StatsBundle {
        val cfg = input.config
        val zone = input.zoneOffsetMillis
        val todayStart = DayBoundary.startOfDay(input.now, cfg.dayStartHour, zone)
        val tomorrowStart = todayStart + DayBoundary.DAY_MS
        val todayDay = studyEpochDay(todayStart, cfg, zone)
        val rangeStart = range.days?.let { todayStart - (it - 1) * DayBoundary.DAY_MS }

        val inScope = input.reviews.filter { scopePaperId == null || it.paperId == scopePaperId }
        val inRange = inScope.filter { rangeStart == null || it.reviewedAt >= rangeStart }
        val cards = input.cards.filter { scopePaperId == null || it.paperId == scopePaperId }

        val heatmap = heatmap(inScope, todayStart, cfg, zone)
        val kpiRetention = trueRetention(inRange, todayStart, tomorrowStart, rangeStart, cfg, zone)

        return StatsBundle(
            today = today(inScope, cards, todayStart, tomorrowStart, todayDay, input.now, cfg, zone),
            kpis = kpis(inRange, cards, kpiRetention, heatmap, input, cfg, zone),
            retentionTable = retentionTable(inScope, todayStart, tomorrowStart, cfg, zone),
            heatmap = heatmap,
            fsrsStatus = FsrsStatus(
                fitted = input.config.fsrsParameters != FsrsScheduler.DEFAULT_FSRS_PARAMETERS,
                reviewCount = input.reviews.size,
                unlockReviews = FsrsOptimizer.MIN_REVIEWS,
                desiredRetention = input.config.desiredRetention
            ),
            retentionTrend = retentionTrend(inRange, rangeStart, todayDay, input, cfg, zone),
            calibration = calibration(inRange),
            groupRetention = groupRetention(inRange, input, scopePaperId),
            weekdayRetention = weekdayRetention(inRange, todayStart, cfg, zone),
            forecast = forecast(cards, todayStart, todayDay),
            reviewsPerDay = reviewsPerDay(inRange, rangeStart, todayDay, cfg, zone),
            timePerDay = timePerDay(inRange, rangeStart, todayDay, cfg, zone),
            answerButtons = answerButtons(inRange),
            hourly = hourly(inRange, zone),
            responseTime = responseTime(inRange, cards, rangeStart, todayDay, cfg, zone),
            stability = stabilityDistribution(cards),
            difficulty = difficultyDistribution(cards),
            retrievability = retrievabilityDistribution(cards, input.scheduler, input.now),
            intervals = intervalDistribution(cards),
            memoryGrowth = memoryGrowth(inScope, cfg, zone),
            cardCounts = cardCounts(cards),
            weakest = weakest(inRange, cards),
            leeches = leeches(inScope, cards)
        )
    }

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
    ): List<ReplayReview> =
        reviews
            .filter { it.reviewedAt >= windowStart && it.reviewedAt < windowEnd }
            .filter { (it.intervalBeforeDays ?: 0) >= 1 }     // Anki: prev interval >= 1 day
            .groupBy { it.questionId to studyEpochDay(it.reviewedAt, cfg, zone) }
            .mapNotNull { (_, rows) -> rows.minByOrNull { it.reviewedAt } }

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
        val day = DayBoundary.DAY_MS
        data class Window(val label: String, val start: Long, val end: Long = tomorrowStart)
        return listOf(
            Window("today", todayStart),
            Window("yesterday", todayStart - day, todayStart),
            Window("last7", todayStart - 6 * day),
            Window("last30", todayStart - 29 * day),
            Window("last90", todayStart - 89 * day),
            Window("last365", todayStart - 364 * day),
            Window("all", Long.MIN_VALUE / 2)
        ).map { w -> retentionRow(w.label, firstOfDayReviews(inScope, w.start, w.end, cfg, zone)) }
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

    // --- retention trend ---------------------------------------------------

    private fun retentionTrend(
        inRange: List<ReplayReview>,
        rangeStart: Long?,
        todayDay: Long,
        input: Input,
        cfg: SchedulerConfig,
        zone: Long
    ): RetentionTrendData {
        val windowStart = rangeStart ?: inRange.minOfOrNull { it.reviewedAt } ?: input.now
        val granularity = granularityFor(windowStart, todayDay, cfg, zone)
        val rangeStartDay = studyEpochDay(windowStart, cfg, zone)
        val buckets = bucketList(rangeStartDay, todayDay, granularity, cfg, zone)
        val firstOfDay = firstOfDayReviews(inRange, Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, cfg, zone)
        val perBucket = firstOfDay.groupBy {
            bucketKeyOf(studyEpochDay(it.reviewedAt, cfg, zone), granularity)
        }
        val rowsByBucket = buckets.map { perBucket[it.key].orEmpty() }
        val points = buckets.mapIndexed { i, b ->
            val rows = rowsByBucket[i]
            SeriesPoint(
                label = b.label,
                value = if (rows.isEmpty()) null else rows.count { it.passed }.toDouble() / rows.size,
                count = rows.size
            )
        }
        // Trailing pooled pass rate: a 1-review 100% day must not outweigh a
        // 40-review 70% day, so pool the counts instead of the percentages.
        val rollingWindow = if (granularity == Granularity.DAILY) 7 else 4
        val rolling = points.indices.map { index ->
            val first = maxOf(0, index - rollingWindow + 1)
            var passes = 0
            var reviews = 0
            for (i in first..index) {
                passes += rowsByBucket[i].count { it.passed }
                reviews += rowsByBucket[i].size
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
            desiredRetention = input.config.desiredRetention
        )
    }

    // --- calibration -------------------------------------------------------

    private fun calibration(inRange: List<ReplayReview>): CalibrationData {
        val usable = inRange.filter { it.predictedRetention != null }
        if (usable.isEmpty()) {
            return CalibrationData(emptyList(), 0, null, null, null)
        }
        val bins = MutableList(10) { i ->
            CalibrationData.CalibrationBin(i / 10.0, 0.0, null, 0)
        }
        val sums = DoubleArray(10)
        val counts = IntArray(10)
        val passes = IntArray(10)
        var brier = 0.0
        usable.forEach { review ->
            val p = review.predictedRetention!!.coerceIn(0.0, 0.999999)
            val i = floor(p * 10).toInt().coerceIn(0, 9)
            sums[i] += p
            counts[i]++
            if (review.passed) passes[i]++
            val outcome = if (review.passed) 1.0 else 0.0
            brier += (p - outcome) * (p - outcome)
        }
        val built = bins.indices.mapNotNull { i ->
            if (counts[i] == 0) null else CalibrationData.CalibrationBin(
                predictedFrom = bins[i].predictedFrom,
                predictedMean = sums[i] / counts[i],
                actualRate = passes[i].toDouble() / counts[i],
                count = counts[i]
            )
        }
        return CalibrationData(
            bins = built,
            samples = usable.size,
            predictedMean = usable.mapNotNull { it.predictedRetention }.average(),
            actualMean = usable.count { it.passed }.toDouble() / usable.size,
            brier = brier / usable.size
        )
    }

    // --- group / weekday retention -----------------------------------------

    /**
     * Per-paper (or per-category when scoped to one paper) pass rate over the
     * range: every review counts, so a card's re-answer same day still moves
     * the number. The true-retention rule is the retention table's job.
     */
    private fun groupRetention(
        inRange: List<ReplayReview>,
        input: Input,
        scopePaperId: String?
    ): List<GroupRetention> {
        val grouped = inRange.groupBy {
            if (scopePaperId == null) it.paperId else it.questionId
        }
        return grouped.map { (_, rows) ->
            val label = if (scopePaperId == null) {
                input.paperTitleOf[rows.first().paperId] ?: rows.first().paperId
            } else {
                input.categoryOfQuestion[rows.first().questionId] ?: UNKNOWN_GROUP
            }
            GroupRetention(label, rows.size, rows.count { it.passed })
        }
            .filter { it.reviews > 0 }
            .sortedWith(compareBy({ it.retention ?: 1.0 }, { -it.reviews }))
            .take(15)
    }

    private const val UNKNOWN_GROUP = "—"

    private fun weekdayRetention(
        inRange: List<ReplayReview>,
        todayStart: Long,
        cfg: SchedulerConfig,
        zone: Long
    ): List<WeekdayRetention> {
        val qualifying = firstOfDayReviews(
            inRange, Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, cfg, zone
        ).groupBy { weekdayOf(studyEpochDay(it.reviewedAt, cfg, zone)) }
        return (0..6).map { day ->
            val rows = qualifying[day].orEmpty()
            WeekdayRetention(day, rows.size, rows.count { it.passed })
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
        val perCard = timed.groupBy { it.first.questionId }.mapNotNull { (questionId, rows) ->
            if (rows.size < 3) return@mapNotNull null
            val any = rows.first().first
            val all = inRange.filter { it.questionId == questionId }
            WeakCard(
                paperId = any.paperId,
                questionId = questionId,
                reviews = all.size,
                lapses = all.count { !it.passed },
                againRate = if (all.isEmpty()) 0.0 else all.count { !it.passed }.toDouble() / all.size,
                avgSeconds = rows.map { it.second }.average(),
                lastReviewedAt = all.maxOf { it.reviewedAt },
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
            val contrib = rows.sortedBy { it.reviewedAt }
                .mapNotNull { row -> row.intervalDaysAfter?.let { row to it } }
            var number = 0
            contrib.forEach { (_, interval) ->
                number += 1
                val n = number.coerceAtMost(cappedNumbers)
                byNumber.getOrPut(n) { mutableListOf() }.add(interval.toDouble())
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
            .filter { it.intervalDaysAfter != null }
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
                value = rows.mapNotNull { it.intervalDaysAfter }.average(),
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

    // --- weakest cards -------------------------------------------------------

    private fun weakest(
        inRange: List<ReplayReview>,
        cards: List<CardSnapshot>
    ): List<WeakCard> {
        val leechByQuestion = cards.filter { it.state.leech }.associate { it.state.questionId to true }
        return inRange.groupBy { it.questionId }.mapNotNull { (questionId, rows) ->
            if (rows.size < 3) return@mapNotNull null
            val lapses = rows.count { !it.passed }
            if (lapses == 0) return@mapNotNull null
            val timed = rows.mapNotNull { it.elapsedSeconds }
            val any = rows.first()
            WeakCard(
                paperId = any.paperId,
                questionId = questionId,
                reviews = rows.size,
                lapses = lapses,
                againRate = lapses.toDouble() / rows.size,
                avgSeconds = if (timed.isEmpty()) null else timed.average(),
                lastReviewedAt = rows.maxOf { it.reviewedAt },
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
    ): List<WeakCard> =
        cards.filter { it.state.leech }.mapNotNull { snapshot ->
            val rows = inScope.filter { it.questionId == snapshot.state.questionId }
            if (rows.isEmpty()) return@mapNotNull null
            val lapses = rows.count { !it.passed }
            val timed = rows.mapNotNull { it.elapsedSeconds }
            WeakCard(
                paperId = snapshot.paperId,
                questionId = snapshot.state.questionId,
                reviews = rows.size,
                lapses = lapses,
                againRate = lapses.toDouble() / rows.size,
                avgSeconds = if (timed.isEmpty()) null else timed.average(),
                lastReviewedAt = rows.maxOf { it.reviewedAt },
                leech = true
            )
        }.sortedByDescending { it.lapses }.take(15)

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
