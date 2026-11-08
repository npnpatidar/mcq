package com.mcqapp.domain

import kotlin.math.sqrt

/**
 * How a result may be read (section 11.3, 11.5).
 *
 * [MOCK] and [FRESH_SAMPLE] are the only families that get a margin or a
 * comparable reference; [PRACTICE] is selected on the dependent variable, so
 * its score is biased by design; [SHORT] and [LEGACY] never get either.
 */
enum class ReportFamily {
    MOCK,
    FRESH_SAMPLE,
    PRACTICE,
    SHORT,
    LEGACY
}

/**
 * One row of the report's per-question evidence, read from `question_results`
 * without the heavy element/option JSON. [currentCategoryId] is the category
 * the question still belongs to, or null when the question was deleted or
 * moved out of the paper, so a Study link never points at a dead id.
 */
data class ReportRow(
    val questionId: String,
    val categoryTitle: String,
    val currentCategoryId: String? = null,
    val dwellSeconds: Long = 0,
    val isCorrect: Boolean = false,
    /** The question has an answer key; an ungraded row is excluded from scoring. */
    val graded: Boolean = true,
    /** An option was selected; a graded row without one is a skip. */
    val answered: Boolean = false
)

/** A category in the attempt's scope, with the questions it currently holds. */
data class ScopeTopic(
    val id: String,
    val title: String,
    val questionCount: Int
)

/** Everything the pure [TestReportBuilder] needs, so it can run without a database. */
data class TestReportInput(
    val attempt: Attempt,
    val rows: List<ReportRow>,
    val paper: Paper? = null,
    /** Same-key attempts older than this one, newest first (section 11.5). */
    val comparableAttempts: List<Attempt> = emptyList(),
    val targetTopicIds: List<String> = emptyList()
)

/** The headline numbers, with both denominators the report shows (section 11.4). */
data class ScoreSummary(
    val shown: Int,
    val answered: Int,
    val correct: Int,
    val wrong: Int,
    val skipped: Int,
    val ungraded: Int,
    val score: Double,
    val maxScore: Double,
    val percentage: Double,
    /** `correct / (correct + wrong)` — how well the learner did on what they attempted. */
    val accuracy: Double,
    /** `correct / shown` — a skip or ungraded row counts as a miss here. */
    val accuracyAsked: Double,
    /** The sample behind [accuracy]; 0 means it has no denominator. */
    val accuracySample: Int
) {
    val graded: Int get() = correct + wrong + skipped
    val hasAccuracy: Boolean get() = accuracySample > 0
}

/** One topic's tally; [accuracy] is `correct / asked` by design (section 11.7). */
data class TopicResult(
    val title: String,
    val asked: Int,
    val correct: Int,
    val wrong: Int,
    val skipped: Int,
    val ungraded: Int,
    val accuracy: Double,
    /** Fewer than five asked: do not turn 2/2 into a mastery claim. */
    val smallSample: Boolean,
    /** The current category id for the Study link, or null when unresolved. */
    val currentCategoryId: String? = null
)

/**
 * Where the marks went (section 11.6), phase-1 subset. Every wrong row lands in
 * [knowledgeGap] or [rushed]; [possibleGuess] is a correct answer under three
 * seconds, a heuristic labelled "possible", not a guess flag.
 */
data class ErrorMix(
    val knowledgeGap: Int,
    val rushed: Int,
    val skipped: Int,
    val ungraded: Int,
    val possibleGuess: Int,
    /** The relative dwell thresholds are suppressed below ten timed questions. */
    val smallSample: Boolean,
    val slowWrongQuestionIds: List<String> = emptyList()
) {
    val hasAnything: Boolean
        get() = knowledgeGap + rushed + skipped + ungraded + possibleGuess > 0
}

/** One slow question, for the time-sink list. [index] is its 1-based place in the attempt. */
data class SlowQuestion(val questionId: String, val seconds: Long, val index: Int)

/** Time as evidence about *how* the learner answered (section 11.9). */
data class TimingSummary(
    val totalSeconds: Long,
    val timedQuestions: Int,
    val medianSeconds: Long,
    val averageSeconds: Long,
    val correctAverageSeconds: Long,
    val wrongAverageSeconds: Long,
    val targetSecondsPerQuestion: Int,
    val slowest: List<SlowQuestion>,
    val smallSample: Boolean,
    /** 2× the median or 60 s, whichever is larger; the review's Slow filter uses it. */
    val slowThresholdSeconds: Long = 60
) {
    val hasDwell: Boolean get() = timedQuestions > 0
}

/** A prior attempt under the same comparability key, with its own margin. */
data class ComparablePoint(
    val attemptId: Long,
    val percentage: Double,
    val marginPoints: Double?
)

/**
 * A next action the report recommends (section 11.11).
 *
 * The rule carries the evidence as values, not prose, so the UI renders it
 * with string resources and the tests assert numbers. [kind] picks the route:
 * Study (category ids), Test (type + count + scope) or Review (a filter).
 */
data class RecommendedAction(
    val rule: Rule,
    val kind: Kind,
    val categoryIds: List<String> = emptyList(),
    val filter: String? = null,
    val testType: TestType? = null,
    val testCount: Int = 0
) {
    enum class Kind { STUDY, TEST, REVIEW }

    sealed interface Rule {
        data class ReviewWrong(val count: Int, val slowestSeconds: Long?) : Rule
        data class StudyTopic(
            val topicTitle: String,
            val correct: Int,
            val asked: Int,
            val scopeAccuracyAsked: Double
        ) : Rule

        data class AttemptEveryQuestion(val skipped: Int) : Rule
        data class GuessWhenPossible(val skipped: Int) : Rule
        data class TakeLongerMock(
            val currentMargin: Double,
            val targetCount: Int,
            val targetMargin: Double,
            /** False when the scope cannot supply 50 questions. */
            val canReachFifty: Boolean
        ) : Rule

        data object KeepGoing : Rule
    }
}

/**
 * The pure result report (section 11.14). [attempt] is the source of truth for
 * the header; everything else is derived once here so the screen never
 * recomputes a tally in Compose.
 */
data class TestReport(
    val attempt: Attempt,
    val family: ReportFamily,
    /** Null only for a legacy attempt with no recorded type. */
    val type: TestType?,
    val requestedCount: Int,
    val shownCount: Int,
    val scopeSize: Int,
    val score: ScoreSummary,
    val comparable: Boolean,
    /** Null when the family gets no margin (short, legacy, or no denominator). */
    val marginPoints: Double?,
    val trendWorthy: Boolean,
    val previousComparable: ComparablePoint?,
    val errorMix: ErrorMix,
    val topics: List<TopicResult>,
    val notSampledTopics: List<String>,
    /** Rows whose question no longer resolves to a category (deleted or moved out). */
    val unresolvedResults: Int,
    val timing: TimingSummary,
    val actions: List<RecommendedAction>,
    /** The marking applied to wrong answers; 0 when the paper had none. */
    val negativeMarking: Double
)

/**
 * Builds a [TestReport] from synthetic or loaded rows (section 11.14).
 *
 * Every rule is a pure function of its input, so the JVM tests can pin the
 * score denominators, the margin, the error-mix thresholds, topic ordering,
 * the family label and the action priority without Compose or a database.
 */
object TestReportBuilder {

    /** The family a legacy row, a short run or a typed attempt belongs to. */
    fun familyOf(attempt: Attempt): ReportFamily {
        val type = TestType.fromStorage(attempt.testType)
        if (type == null || attempt.samplingVersion < TestPlan.SAMPLING_VERSION) {
            return ReportFamily.LEGACY
        }
        if (attempt.totalQuestions < TestPlan.MIN_LENGTH) return ReportFamily.SHORT
        if (attempt.exclusionMode == ExclusionMode.RECENT_3.name) {
            return ReportFamily.FRESH_SAMPLE
        }
        return if (type.isRepresentative) ReportFamily.MOCK else ReportFamily.PRACTICE
    }

    fun build(input: TestReportInput): TestReport {
        val attempt = input.attempt
        val family = familyOf(attempt)
        val type = TestType.fromStorage(attempt.testType)
        val score = scoreOf(attempt, input.rows)
        val scopeTopics = scopeTopicsOf(input)
        val scopeSize = if (scopeTopics.isNotEmpty()) {
            scopeTopics.sumOf { it.questionCount }
        } else {
            score.shown
        }
        val comparable = family == ReportFamily.MOCK || family == ReportFamily.FRESH_SAMPLE
        val margin = if (comparable) {
            TestStatistics.marginPoints(
                proportion = score.accuracy,
                sample = score.shown,
                population = scopeSize
            )
        } else {
            null
        }
        val trendWorthy = comparable &&
            (score.shown >= TREND_MIN_SHOWN || (margin != null && margin <= TREND_MAX_MARGIN))
        val previous = if (comparable) input.comparableAttempts.firstOrNull() else null
        val topics = topicsOf(input.rows)
        val timing = timingOf(attempt, input.rows)
        val errorMix = errorMixOf(input.rows, timing)

        val report = TestReport(
            attempt = attempt,
            family = family,
            type = type,
            requestedCount = attempt.requestedCount,
            shownCount = score.shown,
            scopeSize = scopeSize,
            score = score,
            comparable = comparable,
            marginPoints = margin,
            trendWorthy = trendWorthy,
            previousComparable = previous?.let { prior ->
                ComparablePoint(
                    attemptId = prior.id,
                    percentage = prior.percentage,
                    marginPoints = TestStatistics.marginPoints(
                        proportion = prior.accuracyOnAnswered,
                        sample = prior.totalQuestions,
                        population = scopeSize
                    )
                )
            },
            errorMix = errorMix,
            topics = topics,
            notSampledTopics = notSampledTopics(scopeTopics, input.rows),
            unresolvedResults = input.rows.count { it.currentCategoryId == null },
            timing = timing,
            actions = emptyList(),
            negativeMarking = markingOf(attempt, input.paper)
        )
        return report.copy(actions = RecommendationEngine.recommend(report))
    }

    /** Both denominators and the counts; ungraded rows are excluded from scoring. */
    fun scoreOf(attempt: Attempt, rows: List<ReportRow>): ScoreSummary {
        val correct = rows.count { it.graded && it.answered && it.isCorrect }
        val wrong = rows.count { it.graded && it.answered && !it.isCorrect }
        val skipped = rows.count { it.graded && !it.answered }
        val ungraded = rows.count { !it.graded }
        val shown = if (rows.isNotEmpty()) rows.size else attempt.totalQuestions
        val sample = correct + wrong
        return ScoreSummary(
            shown = shown,
            answered = correct + wrong,
            correct = correct,
            wrong = wrong,
            skipped = skipped,
            ungraded = ungraded,
            score = attempt.score,
            maxScore = attempt.maxScore,
            percentage = attempt.percentage,
            accuracy = if (sample > 0) correct.toDouble() / sample else 0.0,
            accuracyAsked = if (shown > 0) correct.toDouble() / shown else 0.0,
            accuracySample = sample
        )
    }

    /**
     * The marking actually applied, derived from the stored score rather than
     * the paper's current setting: a later edit to the paper must not rewrite
     * what an old attempt meant. Falls back to the paper when there are no
     * wrong answers to derive it from.
     */
    fun markingOf(attempt: Attempt, paper: Paper?): Double {
        if (attempt.wrongCount > 0 && attempt.maxScore > 0) {
            val lost = attempt.correctCount.toDouble() - attempt.score
            if (lost >= 0) return (lost / attempt.wrongCount).coerceIn(0.0, 1.0)
        }
        return paper?.negativeMarking ?: 0.0
    }

    private fun topicsOf(rows: List<ReportRow>): List<TopicResult> =
        rows.groupBy { it.categoryTitle }
            .map { (title, group) ->
                val correct = group.count { it.graded && it.answered && it.isCorrect }
                val wrong = group.count { it.graded && it.answered && !it.isCorrect }
                val skipped = group.count { it.graded && !it.answered }
                val ungraded = group.count { !it.graded }
                val asked = group.size
                TopicResult(
                    title = title,
                    asked = asked,
                    correct = correct,
                    wrong = wrong,
                    skipped = skipped,
                    ungraded = ungraded,
                    accuracy = if (asked > 0) correct.toDouble() / asked else 0.0,
                    smallSample = asked < SMALL_TOPIC,
                    currentCategoryId = group.firstNotNullOfOrNull { it.currentCategoryId }
                )
            }
            .sortedWith(compareBy({ it.accuracy }, { -it.asked }, { it.title }))

    private fun scopeTopicsOf(input: TestReportInput): List<ScopeTopic> {
        val paper = input.paper ?: return emptyList()
        val ticked = input.attempt.scopeCategoryIds
            .split(",")
            .filter { it.isNotBlank() }
        val expanded = if (ticked.isEmpty()) {
            CategorySelection.flatten(paper.categories).map { it.first }
        } else {
            val ids = CategorySelection.orderedIds(paper.categories, ticked)
            val byId = CategorySelection.flatten(paper.categories)
                .associate { it.first.id to it.first }
            ids.mapNotNull { byId[it] }
        }
        return expanded.map { ScopeTopic(it.id, it.title, it.questionCount) }
    }

    private fun notSampledTopics(scope: List<ScopeTopic>, rows: List<ReportRow>): List<String> {
        if (scope.isEmpty()) return emptyList()
        val sampledIds = rows.mapNotNull { it.currentCategoryId }.toHashSet()
        val sampledTitles = rows.map { it.categoryTitle }.toHashSet()
        return scope
            .filter { it.id !in sampledIds && it.title !in sampledTitles }
            .map { it.title }
            .distinct()
    }

    private fun timingOf(attempt: Attempt, rows: List<ReportRow>): TimingSummary {
        val timed = rows.filter { it.dwellSeconds > 0 }
        val sorted = timed.map { it.dwellSeconds }.sorted()
        val median = if (sorted.isEmpty()) 0L else sorted[sorted.size / 2]
        val totalDwell = timed.sumOf { it.dwellSeconds }
        val correct = timed.filter { it.graded && it.answered && it.isCorrect }
        val wrong = timed.filter { it.graded && it.answered && !it.isCorrect }
        return TimingSummary(
            totalSeconds = attempt.durationSeconds,
            timedQuestions = timed.size,
            medianSeconds = median,
            averageSeconds = if (timed.isEmpty()) 0L else totalDwell / timed.size,
            correctAverageSeconds = if (correct.isEmpty()) 0L else correct.sumOf { it.dwellSeconds } / correct.size,
            wrongAverageSeconds = if (wrong.isEmpty()) 0L else wrong.sumOf { it.dwellSeconds } / wrong.size,
            targetSecondsPerQuestion = ExamTiming.DEFAULT_SECONDS_PER_QUESTION,
            slowest = timed.sortedByDescending { it.dwellSeconds }
                .take(SLOWEST_COUNT)
                .map { slow -> SlowQuestion(slow.questionId, slow.dwellSeconds, rows.indexOf(slow) + 1) },
            smallSample = timed.size < SMALL_TIMED,
            slowThresholdSeconds = maxOf(60L, median * 2)
        )
    }

    private fun errorMixOf(rows: List<ReportRow>, timing: TimingSummary): ErrorMix {
        val small = timing.smallSample
        val median = timing.medianSeconds.toDouble()
        // Section 11.6: below ten timed questions hide the relative thresholds
        // and keep only the absolute rules, labelled "small sample".
        val rushedBelow = if (small) 5.0 else maxOf(5.0, median * 0.25)
        val gapFrom = if (small) 15.0 else maxOf(15.0, median)
        val wrong = rows.filter { it.graded && it.answered && !it.isCorrect }
        val rushed = wrong.count { it.dwellSeconds > 0 && it.dwellSeconds < rushedBelow }
        val knowledgeGap = wrong.size - rushed
        return ErrorMix(
            knowledgeGap = knowledgeGap,
            rushed = rushed,
            skipped = rows.count { it.graded && !it.answered },
            ungraded = rows.count { !it.graded },
            possibleGuess = rows.count {
                it.graded && it.answered && it.isCorrect &&
                    it.dwellSeconds in 1L until POSSIBLE_GUESS_BELOW
            },
            smallSample = small,
            slowWrongQuestionIds = wrong
                .filter { it.dwellSeconds >= gapFrom }
                .sortedByDescending { it.dwellSeconds }
                .map { it.questionId }
        )
    }

    /** Below ten timed questions the report says so rather than hiding the split. */
    const val SMALL_TIMED: Int = 10

    /** Fewer than five asked in a topic is "small sample". */
    const val SMALL_TOPIC: Int = 5

    /** A correct answer under three seconds is a *possible* guess. */
    const val POSSIBLE_GUESS_BELOW: Long = 3

    private const val SLOWEST_COUNT = 3

    /** A single 20-question point is not a trend; 50 shown is the bar (section 11.5). */
    const val TREND_MIN_SHOWN: Int = 50
    const val TREND_MAX_MARGIN: Double = 10.0
}

/** The phase-1 recommendation rules (section 11.11), capped at five actions. */
object RecommendationEngine {

    const val MAX_ACTIONS: Int = 5

    fun recommend(report: TestReport): List<RecommendedAction> {
        val actions = when (report.family) {
            ReportFamily.LEGACY, ReportFamily.SHORT -> reviewOnly(report)
            ReportFamily.PRACTICE -> practiceActions(report)
            ReportFamily.MOCK, ReportFamily.FRESH_SAMPLE -> mockActions(report)
        }
        return actions.take(MAX_ACTIONS)
    }

    private fun reviewOnly(report: TestReport): List<RecommendedAction> = buildList {
        reviewWrong(report)?.let { add(it) }
        studyWeakest(report)?.let { add(it) }
    }

    private fun mockActions(report: TestReport): List<RecommendedAction> = buildList {
        if (report.score.wrong >= 3) reviewWrong(report)?.let { add(it) }
        studyWeakest(report)?.let { add(it) }
        if (report.score.skipped >= 3) {
            add(
                if (report.negativeMarking > 0) {
                    RecommendedAction(
                        rule = RecommendedAction.Rule.GuessWhenPossible(report.score.skipped),
                        kind = RecommendedAction.Kind.REVIEW,
                        filter = ReviewFilters.SKIPPED
                    )
                } else {
                    RecommendedAction(
                        rule = RecommendedAction.Rule.AttemptEveryQuestion(report.score.skipped),
                        kind = RecommendedAction.Kind.REVIEW,
                        filter = ReviewFilters.SKIPPED
                    )
                }
            )
        }
        longerMock(report)?.let { add(it) }
        if (isEmpty()) {
            add(
                RecommendedAction(
                    rule = RecommendedAction.Rule.KeepGoing,
                    kind = RecommendedAction.Kind.TEST,
                    testType = TestType.RANDOM,
                    testCount = TestPlan.DEFAULT_LENGTH,
                    categoryIds = report.attempt.scopeCategoryIds.split(",").filter { it.isNotBlank() }
                )
            )
        }
    }

    private fun practiceActions(report: TestReport): List<RecommendedAction> = buildList {
        if (report.score.wrong >= 3) reviewWrong(report)?.let { add(it) }
        studyWeakest(report)?.let { add(it) }
        if (isEmpty() && report.score.wrong > 0) {
            reviewWrong(report)?.let { add(it) }
        }
    }

    private fun reviewWrong(report: TestReport): RecommendedAction? {
        if (report.score.wrong <= 0) return null
        val slowest = report.timing.slowest
            .firstOrNull { it.questionId in report.errorMix.slowWrongQuestionIds }
            ?.seconds
        return RecommendedAction(
            rule = RecommendedAction.Rule.ReviewWrong(report.score.wrong, slowest),
            kind = RecommendedAction.Kind.REVIEW,
            filter = ReviewFilters.WRONG
        )
    }

    private fun studyWeakest(report: TestReport): RecommendedAction? {
        val scope = report.score.accuracyAsked
        val weakest = report.topics.firstOrNull {
            it.asked >= TestReportBuilder.SMALL_TOPIC &&
                it.accuracy <= scope - WEAK_TOPIC_GAP
        } ?: return null
        return RecommendedAction(
            rule = RecommendedAction.Rule.StudyTopic(
                topicTitle = weakest.title,
                correct = weakest.correct,
                asked = weakest.asked,
                scopeAccuracyAsked = scope
            ),
            kind = RecommendedAction.Kind.STUDY,
            categoryIds = listOfNotNull(weakest.currentCategoryId)
        )
    }

    private fun longerMock(report: TestReport): RecommendedAction? {
        if (report.requestedCount != TestPlan.DEFAULT_LENGTH) return null
        val margin = report.marginPoints ?: return null
        if (margin < LONGER_MOCK_MARGIN) return null
        // Section 11.11: the largest useful step is 50 questions; a bigger
        // scope does not make a 150-question mock the right next action.
        val target = minOf(LONGER_MOCK_TARGET, TestPlan.maxAllowedFor(report.scopeSize))
        if (target <= report.requestedCount) return null
        val targetMargin = TestStatistics.marginPoints(
            proportion = report.score.accuracy,
            sample = target,
            population = report.scopeSize
        ) ?: return null
        return RecommendedAction(
            rule = RecommendedAction.Rule.TakeLongerMock(
                currentMargin = margin,
                targetCount = target,
                targetMargin = targetMargin,
                canReachFifty = target >= 50
            ),
            kind = RecommendedAction.Kind.TEST,
            testType = TestType.RANDOM,
            testCount = target,
            categoryIds = report.attempt.scopeCategoryIds.split(",").filter { it.isNotBlank() }
        )
    }

    /** Ten points below the scope is the evidence bar for a topic action. */
    private const val WEAK_TOPIC_GAP: Double = 0.10

    /** A margin at or above fifteen points is too wide to act on. */
    private const val LONGER_MOCK_MARGIN: Double = 15.0

    /** The next mock is at most 50 questions (section 11.11). */
    private const val LONGER_MOCK_TARGET: Int = 50
}

/** The approximate margin (section 11.5), pure so it is JVM-testable. */
object TestStatistics {

    /** 95% two-sided. */
    private const val Z: Double = 1.96

    /**
     * The Wilson interval half-width in percentage points, with the
     * finite-population correction when the scope size is known.
     *
     * [proportion] is 0..1, [sample] the shown count and [population] the
     * scope size. Returns null when there is no sample; a census (sample >=
     * population) returns 0 because there is no sampling error left.
     */
    fun marginPoints(proportion: Double, sample: Int, population: Int?): Double? {
        if (sample <= 0) return null
        val p = proportion.coerceIn(0.0, 1.0)
        val n = sample.toDouble()
        val denominator = 1.0 + Z * Z / n
        val half = Z * sqrt(p * (1.0 - p) / n + Z * Z / (4.0 * n * n)) / denominator
        var margin = half * 100.0
        if (population != null && population > 1) {
            margin *= if (sample >= population) {
                0.0
            } else {
                sqrt((population - sample).toDouble() / (population - 1))
            }
        }
        return margin
    }
}
