package com.mcqapp.domain.stats

import com.mcqapp.domain.Attempt
import com.mcqapp.domain.ReportFamily
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestReportBuilder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sqrt

/**
 * The three groups the Exam view can compare (plan §3.1). Short and legacy
 * attempts are deliberately absent: they are shown in the recent list with
 * their own family badge, but never enter a trend, best/average figure or a
 * comparison.
 *
 * [MOCK_STRICT] is the ordinary Random/Balanced attempt; [MOCK_FRESH] is the
 * same measurement with the recent-3 exclusion. [PRACTICE] is selected on the
 * dependent variable, so its score is read on its own and never against a
 * mock.
 */
enum class ExamGroup(val family: ReportFamily) {
    MOCK_STRICT(ReportFamily.MOCK),
    MOCK_FRESH(ReportFamily.FRESH_SAMPLE),
    PRACTICE(ReportFamily.PRACTICE);

    companion object {
        /** The group a stored family belongs to, or null for Short/Legacy. */
        fun of(family: ReportFamily): ExamGroup? = when (family) {
            ReportFamily.MOCK -> MOCK_STRICT
            ReportFamily.FRESH_SAMPLE -> MOCK_FRESH
            ReportFamily.PRACTICE -> PRACTICE
            ReportFamily.SHORT, ReportFamily.LEGACY -> null
        }
    }
}

/**
 * The full comparability key (plan §3.1). A trend is drawn only inside one
 * key; every leading field has to agree, so strict and Fresh, Random and
 * Balanced, and two lengths never share a line.
 */
data class ExamComparisonKey(
    val paperId: String,
    val testType: String?,
    val scopeKey: String,
    val requestedCount: Int,
    val samplingVersion: Int,
    val exclusionMode: String
) {
    /** The scope is the whole paper, not a category subset. */
    val isWholePaper: Boolean get() = scopeKey == ScopeKey.wholePaper(paperId)

    companion object {
        fun of(attempt: Attempt): ExamComparisonKey = ExamComparisonKey(
            paperId = attempt.paperId,
            testType = attempt.testType,
            scopeKey = attempt.scopeKey,
            requestedCount = attempt.requestedCount,
            samplingVersion = attempt.samplingVersion,
            exclusionMode = attempt.exclusionMode
        )
    }
}

/** One period's mean score; a null [percent] is an empty bucket, kept. */
data class ExamTrendPoint(
    val label: String,
    val percent: Double?,
    val attempts: Int
)

/** One paper's comparable summary inside the selected family. */
data class ExamPaperRow(
    val paperId: String,
    val title: String,
    val family: ReportFamily,
    val attempts: Int,
    val latestPercent: Double?,
    val bestPercent: Double?,
    val deltaPoints: Double?
)

/**
 * One as-of-attempt category tally. [title] is the title stored on the result
 * row, so a later rename or move does not rewrite history. [unresolved] counts
 * rows whose question no longer exists, which is why the rows need not add up
 * to the current library.
 */
data class ExamCategoryRow(
    val title: String,
    val asked: Int,
    val correct: Int,
    val currentCategoryId: String? = null,
    val unresolved: Int = 0
) {
    val accuracy: Double get() = if (asked > 0) correct.toDouble() / asked else 0.0
}

/** One recent attempt for the tap-through list. */
data class ExamRecentAttempt(
    val attemptId: Long,
    val title: String,
    val testType: String?,
    val percent: Double,
    val correct: Int,
    val shown: Int,
    val finishedAt: Long,
    val family: ReportFamily
)

/** A category aggregate read from `question_results`; the builder sorts it. */
data class ExamCategoryTotal(
    val title: String,
    val asked: Int,
    val correct: Int,
    val currentCategoryId: String? = null,
    val unresolved: Int = 0
)

/** `SUM(dwellSeconds)` and the number of timed rows behind it. */
data class ExamDwellTotals(
    val totalSeconds: Long = 0,
    val timedRows: Int = 0
)

/**
 * Lifetime counters for the "counter source" line. A mock reads the
 * representative (`rep*`) subset; practice reads every attempt, because its
 * questions were chosen from that same evidence.
 */
data class ExamCounterTotals(
    val correct: Int = 0,
    val wrong: Int = 0,
    val skip: Int = 0
) {
    val answered: Int get() = correct + wrong
    val graded: Int get() = correct + wrong + skip
    val accuracy: Double get() = if (answered > 0) correct.toDouble() / answered else 0.0
    val hasData: Boolean get() = graded > 0
}

/**
 * The Exam slice (plan §3.1). Every figure is inside one [comparisonKey]; a
 * different length, type, scope or exclusion mode is a different measurement
 * and is summarised separately in [papers], never averaged in.
 */
data class ExamStats(
    val group: ExamGroup,
    val comparisonKey: ExamComparisonKey? = null,
    /** The paper behind [comparisonKey], as the attempt recorded it. */
    val paperTitle: String? = null,
    /** Attempts in the comparable key behind the headline figures. */
    val attempts: Int = 0,
    /** All attempts in the selected family/scope, including other keys. */
    val familyAttempts: Int = 0,
    val latestPercent: Double? = null,
    val bestPercent: Double? = null,
    val averagePercent: Double? = null,
    /** Latest minus first, in points; null with one attempt. */
    val deltaPoints: Double? = null,
    val totalMinutes: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val skipped: Int = 0,
    val shown: Int = 0,
    val requested: Int = 0,
    val averageSecondsPerQuestion: Double? = null,
    val skipRate: Double? = null,
    /** Approximate 95% margin in points; absent for practice/short/legacy. */
    val marginPoints: Double? = null,
    /** Questions in the latest comparable attempt: the margin's sample size. */
    val latestShown: Int = 0,
    /**
     * The combined 95% band of the latest-vs-first change, in points. Two
     * independent attempts cannot be compared on one attempt's margin.
     */
    val deltaMarginPoints: Double? = null,
    /** Questions in the paper, for the finite-population correction. */
    val population: Int = 0,
    val trend: List<ExamTrendPoint> = emptyList(),
    val papers: List<ExamPaperRow> = emptyList(),
    val categories: List<ExamCategoryRow> = emptyList(),
    val unresolvedCategories: Int = 0,
    val recent: List<ExamRecentAttempt> = emptyList(),
    val lifetime: ExamCounterTotals = ExamCounterTotals(),
    val lifetimeIsRepresentative: Boolean = true
) {
    val isEmpty: Boolean get() = familyAttempts == 0 && recent.isEmpty()

    /** True when the latest movement is inside the combined margin. */
    val deltaIsNoise: Boolean
        get() {
            val delta = deltaPoints ?: return false
            val margin = deltaMarginPoints ?: return false
            return kotlin.math.abs(delta) <= margin
        }
}

/**
 * Pure Exam aggregation (plan §3.1). It takes the attempt headers plus the
 * aggregate category/dwell reads, so it never materialises result rows and
 * every rule is JVM-testable.
 */
object ExamStatsBuilder {

    private const val DAY_MS = 86_400_000L
    private const val Z95 = 1.96

    private enum class Granularity { DAILY, WEEKLY, MONTHLY }

    /**
     * The attempts behind the headline figures: the selected family, then the
     * most recent attempt's full comparability key. A group with no attempt
     * returns an empty list.
     */
    fun comparableAttempts(attempts: List<Attempt>, group: ExamGroup): List<Attempt> {
        val family = attempts
            .filter { TestReportBuilder.familyOf(it) == group.family }
            .sortedWith(compareBy({ it.finishedAt }, { it.id }))
        val latest = family.lastOrNull() ?: return emptyList()
        val key = ExamComparisonKey.of(latest)
        return family.filter { ExamComparisonKey.of(it) == key }
    }

    fun build(
        attempts: List<Attempt>,
        group: ExamGroup,
        categoryTotals: List<ExamCategoryTotal> = emptyList(),
        dwell: ExamDwellTotals = ExamDwellTotals(),
        lifetime: ExamCounterTotals = ExamCounterTotals(),
        population: Int = 0,
        categoryLimit: Int = 15,
        recentLimit: Int = 5
    ): ExamStats {
        val familyAttempts = attempts
            .filter { TestReportBuilder.familyOf(it) == group.family }
            .sortedWith(compareBy({ it.finishedAt }, { it.id }))
        val comparable = comparableAttempts(attempts, group)
        val latest = comparable.lastOrNull()

        val percentages = comparable.map { it.percentage }
        val totalMax = comparable.sumOf { it.maxScore }
        val average = when {
            comparable.isEmpty() -> null
            totalMax > 0 -> comparable.sumOf { it.score } / totalMax * 100.0
            else -> percentages.average()
        }
        val shown = comparable.sumOf { it.totalQuestions }
        val correct = comparable.sumOf { it.correctCount }
        val wrong = comparable.sumOf { it.wrongCount }
        val skipped = comparable.sumOf { it.skippedCount }
        val graded = correct + wrong + skipped
        val latestPercent = latest?.percentage
        val representative = group != ExamGroup.PRACTICE
        // The margin belongs to the latest attempt's percentage, so its sample
        // is that attempt's question count — not the pooled history.
        val latestMargin = if (representative && latest != null) {
            marginPoints(latest.percentage, latest.totalQuestions, population)
        } else {
            null
        }
        val firstMargin = if (representative && comparable.size >= 2) {
            marginPoints(
                comparable.first().percentage,
                comparable.first().totalQuestions,
                population
            )
        } else {
            null
        }

        return ExamStats(
            group = group,
            comparisonKey = latest?.let { ExamComparisonKey.of(it) },
            paperTitle = latest?.title,
            attempts = comparable.size,
            familyAttempts = familyAttempts.size,
            latestPercent = latestPercent,
            bestPercent = percentages.maxOrNull(),
            averagePercent = average,
            deltaPoints = if (percentages.size >= 2) {
                percentages.last() - percentages.first()
            } else {
                null
            },
            totalMinutes = (comparable.sumOf { it.durationSeconds } / 60L).toInt(),
            correct = correct,
            wrong = wrong,
            skipped = skipped,
            shown = shown,
            requested = comparable.sumOf { it.requestedCount },
            averageSecondsPerQuestion = if (dwell.timedRows > 0) {
                dwell.totalSeconds.toDouble() / dwell.timedRows
            } else {
                null
            },
            skipRate = if (graded > 0) skipped.toDouble() / graded else null,
            marginPoints = latestMargin,
            latestShown = latest?.totalQuestions ?: 0,
            deltaMarginPoints = if (latestMargin != null && firstMargin != null) {
                latestMargin + firstMargin
            } else {
                null
            },
            population = population,
            trend = trendOf(comparable),
            papers = papersOf(familyAttempts),
            categories = categoryTotals
                .map {
                    ExamCategoryRow(
                        title = it.title,
                        asked = it.asked,
                        correct = it.correct,
                        currentCategoryId = it.currentCategoryId,
                        unresolved = it.unresolved
                    )
                }
                .sortedWith(compareBy({ it.accuracy }, { -it.asked }, { it.title }))
                .take(categoryLimit),
            unresolvedCategories = categoryTotals.sumOf { it.unresolved },
            recent = attempts
                .sortedWith(compareByDescending<Attempt> { it.finishedAt }.thenByDescending { it.id })
                .take(recentLimit)
                .map {
                    ExamRecentAttempt(
                        attemptId = it.id,
                        title = it.title,
                        testType = it.testType,
                        percent = it.percentage,
                        correct = it.correctCount,
                        shown = it.totalQuestions,
                        finishedAt = it.finishedAt,
                        family = TestReportBuilder.familyOf(it)
                    )
                },
            lifetime = lifetime,
            lifetimeIsRepresentative = representative
        )
    }

    /**
     * The approximate 95% margin of a percentage, in points, from the shown
     * count and the finite-population correction. It is a sampling statement,
     * not a guarantee about the next attempt.
     */
    fun marginPoints(percent: Double, shown: Int, population: Int): Double? {
        if (shown <= 0 || population <= 1) return null
        val p = (percent / 100.0).coerceIn(0.0, 1.0)
        val fpc = if (population > shown) {
            ((population - shown).toDouble() / (population - 1)).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return Z95 * sqrt(p * (1.0 - p) / shown) * sqrt(fpc) * 100.0
    }

    private fun papersOf(familyAttempts: List<Attempt>): List<ExamPaperRow> {
        if (familyAttempts.map { it.paperId }.distinct().size <= 1) return emptyList()
        return familyAttempts.groupBy { it.paperId }
            .map { (paperId, runs) ->
                val ordered = runs.sortedWith(compareBy({ it.finishedAt }, { it.id }))
                val latest = ordered.last()
                val key = ExamComparisonKey.of(latest)
                val comparable = ordered.filter { ExamComparisonKey.of(it) == key }
                val percentages = comparable.map { it.percentage }
                ExamPaperRow(
                    paperId = paperId,
                    title = latest.title,
                    family = TestReportBuilder.familyOf(latest),
                    attempts = comparable.size,
                    latestPercent = percentages.lastOrNull(),
                    bestPercent = percentages.maxOrNull(),
                    deltaPoints = if (percentages.size >= 2) {
                        percentages.last() - percentages.first()
                    } else {
                        null
                    }
                )
            }
            .sortedByDescending { it.title.lowercase(Locale.ROOT) }
    }

    private fun trendOf(attempts: List<Attempt>): List<ExamTrendPoint> {
        if (attempts.isEmpty()) return emptyList()
        val days = attempts.map { Math.floorDiv(it.finishedAt, DAY_MS) }
        val start = days.minOrNull() ?: return emptyList()
        val end = days.maxOrNull() ?: return emptyList()
        val granularity = when {
            end - start <= 31 -> Granularity.DAILY
            end - start <= 150 -> Granularity.WEEKLY
            else -> Granularity.MONTHLY
        }
        val sums = HashMap<Long, Double>()
        val counts = HashMap<Long, Int>()
        for (attempt in attempts) {
            val key = bucketKey(Math.floorDiv(attempt.finishedAt, DAY_MS), granularity)
            sums[key] = (sums[key] ?: 0.0) + attempt.percentage
            counts[key] = (counts[key] ?: 0) + 1
        }
        val firstKey = bucketKey(start, granularity)
        val lastKey = bucketKey(end, granularity)
        return (firstKey..lastKey).map { key ->
            val count = counts[key] ?: 0
            ExamTrendPoint(
                label = labelOf(key, granularity, start),
                percent = if (count > 0) sums.getValue(key) / count else null,
                attempts = count
            )
        }
    }

    private fun bucketKey(epochDay: Long, granularity: Granularity): Long = when (granularity) {
        Granularity.DAILY -> epochDay
        Granularity.WEEKLY -> Math.floorDiv(epochDay, 7)
        Granularity.MONTHLY -> LocalDate.ofEpochDay(epochDay).let { it.year * 12L + it.monthValue - 1 }
    }

    private fun bucketStartDay(key: Long, granularity: Granularity): Long = when (granularity) {
        Granularity.DAILY -> key
        Granularity.WEEKLY -> key * 7
        Granularity.MONTHLY -> LocalDate.of((key / 12).toInt(), (key % 12 + 1).toInt(), 1).toEpochDay()
    }

    private fun labelOf(key: Long, granularity: Granularity, startDay: Long): String {
        val day = bucketStartDay(key, granularity).coerceAtLeast(startDay)
        return when (granularity) {
            Granularity.DAILY, Granularity.WEEKLY ->
                LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
            Granularity.MONTHLY ->
                LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH))
        }
    }
}
