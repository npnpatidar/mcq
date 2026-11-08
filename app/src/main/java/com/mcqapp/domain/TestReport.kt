package com.mcqapp.domain

import com.mcqapp.domain.signals.CounterSet
import kotlin.math.sqrt
import kotlin.random.Random

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
    val answered: Boolean = false,
    /** Passage membership, so the bootstrap resamples whole blocks. */
    val passageId: String? = null
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
    val targetTopicIds: List<String> = emptyList(),
    /** Phase-2 evidence; the defaults are what a legacy/absent read yields. */
    val evidence: ReportEvidence = ReportEvidence()
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
    val negativeMarking: Double,
    /** Phase-2 evidence, or null when the attempt has no such data. */
    val memory: MemoryEvidence? = null,
    val coverage: CoverageEvidence? = null,
    val mistakes: MistakeEvidence? = null,
    /** Per-question lifetime accuracy for the review; empty on a legacy read. */
    val lifetime: Map<String, LifetimeAccuracy> = emptyMap(),
    /** The target-first headline for a practice attempt. */
    val practice: PracticeEvidence? = null,
    /** The comparable trend, or null when the family gets none. */
    val trend: TrendEvidence? = null,
    /** The bootstrap score interval, when negative marking makes it meaningful. */
    val interval: ScoreInterval? = null
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
        val evidence = input.evidence
        val memory = memoryOf(input.rows, evidence)
        val coverage = coverageOf(input.rows, evidence, scopeSize)
        val mistakes = mistakesOf(input.rows, evidence)
        val lifetime = lifetimeOf(input.rows, evidence, family)
        val practice = practiceOf(input, type, score)
        val trend = trendOf(attempt, input.comparableAttempts, scopeSize, family, score)

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
            negativeMarking = markingOf(attempt, input.paper),
            memory = memory,
            coverage = coverage,
            mistakes = mistakes,
            lifetime = lifetime,
            practice = practice,
            trend = trend,
            interval = bootstrapOf(attempt, input.rows)
        )
        return report.copy(actions = RecommendationEngine.recommend(report))
    }

    /**
     * The FSRS quadrants (section 11.8). Only a valid card gives a memory
     * estimate; a missing card, a new card or a stale hash is counted as "no
     * Study history" rather than guessed at. Skipped rows are outside both
     * axes: the learner did not give an answer to grade.
     */
    fun memoryOf(rows: List<ReportRow>, evidence: ReportEvidence): MemoryEvidence? {
        if (rows.isEmpty()) return null
        var noStudy = 0
        var valid = 0
        var dueNow = 0
        var retentionSum = 0.0
        var retentionCount = 0
        val cells = mutableMapOf<Pair<Boolean, Boolean>, Int>()
        for (row in rows) {
            val card = evidence.cards[row.questionId]
            if (card == null || !card.hasValidMemory) {
                noStudy++
                continue
            }
            valid++
            if (evidence.now > 0L && card.dueAt <= evidence.now) dueNow++
            card.retention?.let {
                retentionSum += it
                retentionCount++
            }
            if (!row.graded || !row.answered) continue
            val key = (card.difficulty >= HARD_DIFFICULTY) to row.isCorrect
            cells[key] = (cells[key] ?: 0) + 1
        }
        if (valid == 0 && noStudy == 0) return null
        val quadrants = listOf(
            MemoryQuadrant(hard = true, correct = false, count = cells[true to false] ?: 0),
            MemoryQuadrant(hard = true, correct = true, count = cells[true to true] ?: 0),
            MemoryQuadrant(hard = false, correct = false, count = cells[false to false] ?: 0),
            MemoryQuadrant(hard = false, correct = true, count = cells[false to true] ?: 0)
        )
        return MemoryEvidence(
            quadrants = quadrants,
            noStudy = noStudy,
            dueNow = dueNow,
            validCards = valid,
            meanRetrievability = if (retentionCount > 0) retentionSum / retentionCount else null
        )
    }

    /**
     * Coverage counts engaged rows only (section 11.10): an old full-scope
     * attempt's 980 untouched rows must not look like 980 questions covered.
     */
    fun coverageOf(
        rows: List<ReportRow>,
        evidence: ReportEvidence,
        scopeSize: Int
    ): CoverageEvidence? {
        if (rows.isEmpty()) return null
        val engaged = rows.count { it.answered || it.dwellSeconds > 0 }
        val repeats = rows.count { it.questionId in evidence.testedBefore }
        val testedNow = evidence.testedInScope + rows.count { row ->
            row.questionId !in evidence.testedBefore &&
                (row.answered || row.dwellSeconds > 0)
        }
        val dueNow = rows.count { row ->
            val card = evidence.cards[row.questionId]
            card != null && card.hasValidMemory &&
                evidence.now > 0L && card.dueAt <= evidence.now
        }
        return CoverageEvidence(
            engaged = engaged,
            scopeSize = scopeSize,
            unseenRemaining = (scopeSize - testedNow).coerceAtLeast(0),
            repeats = repeats,
            dueNow = dueNow
        )
    }

    /**
     * Mistakes added and cleared use the same standing rule as History: the
     * latest graded answer before this attempt decides what "was a mistake".
     * A first-ever wrong answer is added; a correct answer to a prior mistake
     * clears it; [MistakeEvidence.remaining] reconciles the scope's prior
     * mistake count with this attempt's changes.
     */
    fun mistakesOf(rows: List<ReportRow>, evidence: ReportEvidence): MistakeEvidence? {
        if (rows.isEmpty()) return null
        val added = rows.count {
            it.graded && it.answered && !it.isCorrect &&
                evidence.priorStanding[it.questionId] != false
        }
        val cleared = rows.count {
            it.graded && it.answered && it.isCorrect &&
                evidence.priorStanding[it.questionId] == false
        }
        val remaining = (evidence.scopeMistakesBefore - cleared + added).coerceAtLeast(0)
        return MistakeEvidence(added = added, cleared = cleared, remaining = remaining)
    }

    /**
     * Per-question lifetime accuracy (section 11.13). A mock reads the
     * representative `rep*` counters; a practice attempt reads every attempt,
     * because its questions were chosen from that same evidence.
     */
    fun lifetimeOf(
        rows: List<ReportRow>,
        evidence: ReportEvidence,
        family: ReportFamily
    ): Map<String, LifetimeAccuracy> {
        val representative = family == ReportFamily.MOCK || family == ReportFamily.FRESH_SAMPLE
        val set = if (representative) CounterSet.REPRESENTATIVE else CounterSet.PERSONAL
        val result = mutableMapOf<String, LifetimeAccuracy>()
        for (id in rows.map { it.questionId }.distinct()) {
            val counters = evidence.counters[id] ?: continue
            val correct = if (representative) counters.repCorrect else counters.correct
            val answered = if (representative) counters.repAnswered else counters.answered
            if (answered <= 0) continue
            result[id] = LifetimeAccuracy(correct = correct, answered = answered, counterSet = set)
        }
        return result
    }

    /**
     * The practice headline (section 11.3). A practice attempt is expected to
     * score lower than a random mock, so the recorded targets are the hero and
     * the overall score is secondary. A run with no recorded targets says so
     * instead of inventing a target set.
     */
    fun practiceOf(
        input: TestReportInput,
        type: TestType?,
        score: ScoreSummary
    ): PracticeEvidence? {
        if (type == null || type.isRepresentative) return null
        val targets = input.targetTopicIds
        if (targets.isEmpty()) {
            return PracticeEvidence(
                targets = emptyList(),
                targetedAsked = 0,
                targetedCorrect = 0,
                targetedPool = 0,
                overallAccuracy = score.accuracy,
                overallAsked = score.answered,
                withoutTargets = true
            )
        }
        val evidence = input.evidence
        val byTarget = targets.map { id ->
            val rows = input.rows.filter {
                it.currentCategoryId == id ||
                    (it.currentCategoryId == null && it.categoryTitle == evidence.targetTitles[id])
            }
            PracticeTarget(
                categoryId = id,
                title = evidence.targetTitles[id]
                    ?: rows.firstOrNull()?.categoryTitle
                    ?: id,
                asked = rows.size,
                correct = rows.count { it.graded && it.answered && it.isCorrect },
                poolSize = evidence.targetSizes[id] ?: 0
            )
        }
        return PracticeEvidence(
            targets = byTarget,
            targetedAsked = byTarget.sumOf { it.asked },
            targetedCorrect = byTarget.sumOf { it.correct },
            targetedPool = byTarget.sumOf { it.poolSize },
            overallAccuracy = score.accuracy,
            overallAsked = score.answered,
            withoutTargets = false
        )
    }

    /**
     * The comparable trend (section 11.5). It never mixes a practice attempt
     * with a mock, or a strict attempt with a Fresh sample: the caller only
     * supplies same-key attempts. A single point is a reference, not a line.
     */
    fun trendOf(
        attempt: Attempt,
        comparableAttempts: List<Attempt>,
        scopeSize: Int,
        family: ReportFamily,
        score: ScoreSummary
    ): TrendEvidence? {
        if (family != ReportFamily.MOCK && family != ReportFamily.FRESH_SAMPLE) return null
        if (!isTrendWorthy(attempt, scopeSize, score)) return null
        val current = TrendPoint(
            attemptId = attempt.id,
            percentage = attempt.percentage,
            marginPoints = TestStatistics.marginPoints(
                proportion = score.accuracy,
                sample = score.shown,
                population = scopeSize
            ),
            finishedAt = attempt.finishedAt
        )
        val earlier = comparableAttempts
            .asReversed()
            .filter { isTrendWorthy(it, scopeSize, null) }
            .takeLast(TREND_POINTS - 1)
            .map {
                TrendPoint(
                    attemptId = it.id,
                    percentage = it.percentage,
                    marginPoints = TestStatistics.marginPoints(
                        proportion = it.accuracyOnAnswered,
                        sample = it.totalQuestions,
                        population = scopeSize
                    ),
                    finishedAt = it.finishedAt
                )
            }
        val points = (earlier + current).takeLast(TREND_POINTS)
        val previous = points.getOrNull(points.size - 2) ?: return null
        val delta = current.percentage - previous.percentage
        val noise = (current.marginPoints ?: 0.0) + (previous.marginPoints ?: 0.0)
        return TrendEvidence(
            points = points,
            deltaPoints = delta,
            withinNoise = kotlin.math.abs(delta) <= noise,
            freshSample = attempt.exclusionMode == ExclusionMode.RECENT_3.name
        )
    }

    private fun isTrendWorthy(
        attempt: Attempt,
        scopeSize: Int,
        score: ScoreSummary?
    ): Boolean {
        if (attempt.totalQuestions >= TREND_MIN_SHOWN) return true
        val margin = if (score != null) {
            TestStatistics.marginPoints(score.accuracy, score.shown, scopeSize)
        } else {
            TestStatistics.marginPoints(
                attempt.accuracyOnAnswered,
                attempt.totalQuestions,
                scopeSize
            )
        }
        return margin != null && margin <= TREND_MAX_MARGIN
    }

    /**
     * The score's bootstrap interval (section 11.5), resampling passage blocks
     * with a fixed seed so the same attempt always reports the same interval.
     * It is only produced when negative marking makes the accuracy margin an
     * incomplete description of the score.
     */
    fun bootstrapOf(attempt: Attempt, rows: List<ReportRow>): ScoreInterval? {
        val marking = markingOf(attempt, null)
        if (marking <= 0.0 || rows.size < 2) return null
        val blocks = rows
            .groupBy { it.passageId ?: it.questionId }
            .values
            .map { block ->
                block.map { row ->
                    when {
                        !row.graded -> 0.0
                        !row.answered -> 0.0
                        row.isCorrect -> 1.0
                        else -> -marking
                    }
                }
            }
        if (blocks.size < 2) return null
        val random = Random(attempt.seed xor BOOTSTRAP_SEED)
        val n = rows.size
        val means = DoubleArray(BOOTSTRAP_RESAMPLES)
        repeat(BOOTSTRAP_RESAMPLES) { index ->
            var total = 0.0
            var taken = 0
            while (taken < n) {
                val block = blocks[random.nextInt(blocks.size)]
                for (value in block) {
                    if (taken >= n) break
                    total += value
                    taken++
                }
            }
            means[index] = total / n * 100.0
        }
        means.sort()
        val low = means[(BOOTSTRAP_RESAMPLES * 0.025).toInt().coerceIn(0, means.lastIndex)]
        val high = means[(BOOTSTRAP_RESAMPLES * 0.975).toInt().coerceIn(0, means.lastIndex)]
        return ScoreInterval(
            lowPercent = low,
            highPercent = high,
            resamples = BOOTSTRAP_RESAMPLES,
            passageBlocks = blocks.size
        )
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

    /** FSRS difficulty at or above this is "hard" in the quadrants (section 11.8). */
    const val HARD_DIFFICULTY: Double = 5.5

    /** The trend line shows at most this many points. */
    const val TREND_POINTS: Int = 5

    /** A seeded bootstrap, cheap at the 150-question cap (section 11.5). */
    const val BOOTSTRAP_RESAMPLES: Int = 1_000
    private const val BOOTSTRAP_SEED: Long = 0x5EED_B007L
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
