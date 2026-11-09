package com.mcqapp

import com.mcqapp.domain.Attempt
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.ReportFamily
import com.mcqapp.domain.ReportRow
import com.mcqapp.domain.ReviewFilters
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestReportBuilder
import com.mcqapp.domain.TestReportInput
import com.mcqapp.domain.TestStatistics
import com.mcqapp.domain.stats.Reliability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure result report (section 11.14/11.16): score denominators, the
 * margin, the family label, the error mix, topic ordering and the action
 * priority. No Compose, no database.
 */
class TestReportBuilderTest {

    private fun attempt(
        id: Long = 1L,
        testType: String? = "RANDOM",
        samplingVersion: Int = TestPlan.SAMPLING_VERSION,
        total: Int = 20,
        requested: Int = 20,
        correct: Int = 12,
        wrong: Int = 4,
        skipped: Int = 2,
        score: Double = 12.0 - 4.0 / 3.0,
        maxScore: Double = 18.0,
        scopeKey: String = ScopeKey.wholePaper("p1"),
        exclusion: String = ExclusionMode.NONE.name,
        finishedAt: Long = 1_000L,
        targetTopicIds: String = "",
        negativeMarking: Double? = null
    ) = Attempt(
        id = id,
        paperId = "p1",
        title = "Paper",
        totalQuestions = total,
        correctCount = correct,
        wrongCount = wrong,
        skippedCount = skipped,
        score = score,
        maxScore = maxScore,
        durationSeconds = 1_200,
        finishedAt = finishedAt,
        testType = testType,
        requestedCount = requested,
        scopeKey = scopeKey,
        samplingVersion = samplingVersion,
        exclusionMode = exclusion,
        targetTopicIds = targetTopicIds,
        negativeMarking = negativeMarking
    )

    private fun row(
        id: String,
        correct: Boolean,
        dwell: Long,
        graded: Boolean = true,
        answered: Boolean = true,
        title: String = "Cat",
        current: String? = "c1",
        optionCount: Int = 0,
        correctCount: Int = 0
    ) = ReportRow(
        questionId = id,
        categoryTitle = title,
        currentCategoryId = current,
        dwellSeconds = dwell,
        isCorrect = correct,
        graded = graded,
        answered = answered,
        optionCount = optionCount,
        correctCount = correctCount
    )

    /** 12 correct, 4 wrong, 2 skipped, 2 ungraded; one correct is a 2-second guess. */
    private fun standardRows(): List<ReportRow> = buildList {
        repeat(11) { add(row("c$it", correct = true, dwell = 40)) }
        add(row("guess", correct = true, dwell = 2))
        add(row("w1", correct = false, dwell = 60))
        add(row("w2", correct = false, dwell = 5))
        add(row("w3", correct = false, dwell = 30))
        add(row("w4", correct = false, dwell = 50))
        add(row("s1", correct = false, dwell = 0, answered = false))
        add(row("s2", correct = false, dwell = 0, answered = false))
        add(row("u1", correct = false, dwell = 0, graded = false, answered = false))
        add(row("u2", correct = false, dwell = 0, graded = false, answered = false))
    }

    private fun paper() = Paper(
        id = "p1",
        title = "Paper",
        negativeMarking = 1.0 / 3.0,
        categories = listOf(
            CategoryNode("c1", "p1", "Polity", null, questionCount = 60),
            CategoryNode("c2", "p1", "Economy", null, questionCount = 40)
        )
    )

    private fun report(
        attempt: Attempt = attempt(),
        rows: List<ReportRow> = standardRows(),
        paper: Paper? = paper(),
        previous: List<Attempt> = emptyList()
    ) = TestReportBuilder.build(
        TestReportInput(attempt = attempt, rows = rows, paper = paper, comparableAttempts = previous)
    )

    @Test
    fun theScoreAndAccuracyUseDifferentDenominators() {
        val report = report()
        val score = report.score
        assertEquals(20, score.shown)
        assertEquals(16, score.answered)
        assertEquals(12, score.correct)
        assertEquals(4, score.wrong)
        assertEquals(2, score.skipped)
        assertEquals(2, score.ungraded)
        assertEquals("accuracy on answered excludes skips", 0.75, score.accuracy, 0.0001)
        assertEquals("accuracy asked counts a skip as a miss", 0.60, score.accuracyAsked, 0.0001)
        assertEquals(16, score.accuracySample)
    }

    @Test
    fun theMarkingComesFromTheStoredScoreNotThePapersCurrentSetting() {
        // The paper now says 1/2, but the attempt's score implies 1/3. The
        // report must describe what the attempt actually applied.
        val changed = paper().copy(negativeMarking = 0.5)
        val report = report(paper = changed)
        assertEquals(1.0 / 3.0, report.negativeMarking, 0.001)
    }

    @Test
    fun aPaperWithNoWrongAnswersFallsBackToItsMarking() {
        val attempt = attempt(wrong = 0, skipped = 6, correct = 12, score = 12.0, maxScore = 18.0)
        val rows = buildList {
            repeat(12) { add(row("c$it", correct = true, dwell = 40)) }
            repeat(6) { add(row("s$it", correct = false, dwell = 0, answered = false)) }
            repeat(2) { add(row("u$it", correct = false, dwell = 0, graded = false, answered = false)) }
        }
        val report = report(attempt = attempt, rows = rows)
        assertEquals(1.0 / 3.0, report.negativeMarking, 0.001)
    }

    @Test
    fun theGuessingCorrectionUsesEachItemsOwnChanceLevel() {
        // 20 answered, 10 correct. Ten single-correct 4-option items (chance
        // 1/4) and ten "pick 2 of 4" items (chance 1/6) average 0.2083.
        val rows = buildList {
            repeat(10) { i -> add(row("s$i", correct = i < 5, dwell = 30, optionCount = 4, correctCount = 1)) }
            repeat(10) { i -> add(row("m$i", correct = i < 5, dwell = 30, optionCount = 4, correctCount = 2)) }
        }
        val report = report(
            // 10 right, 10 wrong, no penalty: markingOf infers 0 from the score,
            // so only the chance level can suppress this estimate.
            attempt = attempt(correct = 10, wrong = 10, skipped = 0, score = 10.0, maxScore = 20.0),
            rows = rows
        )
        val guessing = report.guessing!!
        assertEquals(20, guessing.scored)
        assertEquals(0.5, guessing.rawAccuracy, 0.0001)
        assertEquals((0.25 + 1.0 / 6.0) / 2.0, guessing.chance, 0.0001)
        assertEquals(
            (0.5 - guessing.chance) / (1.0 - guessing.chance),
            guessing.correctedAccuracy,
            0.0001
        )
    }

    @Test
    fun guessingIsSuppressedForPracticePenaltyAndUnknownKeys() {
        val rows = (0 until 20).map {
            row("q$it", correct = it < 10, dwell = 30, optionCount = 4, correctCount = 1)
        }
        // A practice set is selected on the dependent variable.
        val practice = TestReportBuilder.build(
            TestReportInput(
                attempt = attempt(
                    testType = "MIXED",
                    correct = 10,
                    wrong = 10,
                    skipped = 0,
                    score = 10.0,
                    maxScore = 20.0
                ),
                rows = rows,
                paper = paper().copy(negativeMarking = 0.0)
            )
        )
        assertNull(practice.guessing)
        // Negative marking breaks the "a wrong answer costs nothing" assumption.
        // The score (8 of 10 right, 10 wrong) implies 0.2 lost per wrong answer.
        val penalised = report(
            attempt = attempt(
                correct = 10,
                wrong = 10,
                skipped = 0,
                score = 8.0,
                maxScore = 20.0
            ),
            rows = rows,
            paper = paper()
        )
        assertNull(penalised.guessing)
        // One row without an option key would make it a subset estimate.
        val unknown = rows.toMutableList().also { it[0] = it[0].copy(optionCount = 0) }
        assertNull(
            report(
                attempt = attempt(
                    correct = 10,
                    wrong = 10,
                    skipped = 0,
                    score = 10.0,
                    maxScore = 20.0
                ),
                rows = unknown,
                paper = paper().copy(negativeMarking = 0.0)
            ).guessing
        )
    }

    @Test
    fun guessingBelowTwentyAnsweredIsNotAnEstimate() {
        val rows = (0 until 19).map {
            row("q$it", correct = it < 10, dwell = 30, optionCount = 4, correctCount = 1)
        }
        assertNull(
            report(
                attempt = attempt(
                    correct = 10,
                    wrong = 9,
                    skipped = 0,
                    score = 10.0,
                    maxScore = 19.0
                ),
                rows = rows,
                paper = paper().copy(negativeMarking = 0.0)
            ).guessing
        )
    }

    @Test
    fun discriminationReachesTheReviewRowWhenTheSampleSupportsIt() {
        val rows = standardRows()
        val disc = mapOf(
            "c0" to Reliability.ItemDiscrimination(
                "c0",
                0.42,
                Reliability.Discrimination.Flag.OK
            ),
            "w1" to Reliability.ItemDiscrimination(
                "w1",
                -0.2,
                Reliability.Discrimination.Flag.REVIEW
            )
        )
        val report = TestReportBuilder.build(
            TestReportInput(
                attempt = attempt(),
                rows = rows,
                paper = paper(),
                discrimination = disc
            )
        )
        assertEquals(0.42, report.reviews.getValue("c0").discrimination!!, 0.0001)
        assertEquals(
            Reliability.Discrimination.Flag.REVIEW,
            report.reviews.getValue("w1").discriminationFlag
        )
        assertNull("a question with no estimate says nothing", report.reviews.getValue("c1").discrimination)
    }

    @Test
    fun theMarginShrinksWithTheFinitePopulationCorrection() {
        val uncorrected = TestStatistics.marginPoints(0.75, 20, null)!!
        val corrected = TestStatistics.marginPoints(0.75, 20, 1_000)!!
        assertTrue("a finite population narrows the interval", corrected < uncorrected)
        assertEquals(0.0, TestStatistics.marginPoints(0.75, 20, 20)!!, 0.0001)
        assertNull(TestStatistics.marginPoints(0.75, 0, 1_000))
    }

    @Test
    fun randomIsComparableAndGetsAMargin() {
        val report = report()
        assertEquals(ReportFamily.MOCK, report.family)
        assertTrue(report.comparable)
        assertNotNull(report.marginPoints)
        assertEquals(0.60, report.score.accuracyAsked, 0.0001)
    }

    @Test
    fun theMarginDescribesTheShownDenominator() {
        // The margin is attached to the score percentage, whose denominator is
        // the shown count; it must not pair an answered-based proportion with
        // that sample.
        val report = report()
        assertEquals(
            TestStatistics.marginPoints(
                report.score.accuracyAsked,
                report.score.shown,
                report.scopeSize
            ),
            report.marginPoints
        )
    }

    @Test
    fun theStoredMarkingSurvivesUnequalQuestionMarks() {
        // One 2-mark question right and one 4-mark question wrong at a 0.25
        // penalty stores score = 2 - 1 = 1. Correct/wrong counts alone would
        // reconstruct (1 - 1) / 1 = 0.
        val report = report(
            attempt = attempt(
                correct = 1,
                wrong = 1,
                skipped = 0,
                score = 1.0,
                maxScore = 6.0,
                negativeMarking = 0.25
            ),
            rows = emptyList()
        )
        assertEquals(0.25, report.negativeMarking, 0.0001)
        assertNull("negative marking rules the correction out", report.guessing)
    }

    @Test
    fun practiceIsNeverComparableAndHasNoMargin() {
        val report = report(attempt = attempt(testType = "MISTAKES"))
        assertEquals(ReportFamily.PRACTICE, report.family)
        assertFalse(report.comparable)
        assertNull(report.marginPoints)
        assertNull(report.previousComparable)
    }

    @Test
    fun aShortRunIsLabelledAndHasNoMargin() {
        val rows = standardRows().take(12)
        val report = report(
            attempt = attempt(total = 12, correct = 8, wrong = 4, skipped = 0, score = 8.0, maxScore = 12.0),
            rows = rows
        )
        assertEquals(ReportFamily.SHORT, report.family)
        assertFalse(report.comparable)
        assertNull(report.marginPoints)
    }

    @Test
    fun aLegacyAttemptIsNotComparable() {
        val legacy = attempt(testType = null, samplingVersion = 1, total = 1_000, requested = 0)
        val report = report(attempt = legacy, rows = standardRows())
        assertEquals(ReportFamily.LEGACY, report.family)
        assertFalse(report.comparable)
        assertNull(report.marginPoints)
        assertNull(report.type)
    }

    @Test
    fun aFreshSampleIsItsOwnFamily() {
        val report = report(attempt = attempt(exclusion = ExclusionMode.RECENT_3.name))
        assertEquals(ReportFamily.FRESH_SAMPLE, report.family)
        assertTrue(report.comparable)
    }

    @Test
    fun theErrorMixSplitsSlowWrongFromRushedWrong() {
        val mix = report().errorMix
        assertEquals("60, 30 and 50 are not rushed", 3, mix.knowledgeGap)
        assertEquals("5 seconds is under the rushed threshold", 1, mix.rushed)
        assertEquals(2, mix.skipped)
        assertEquals(2, mix.ungraded)
        assertEquals(1, mix.possibleGuess)
        assertFalse(mix.smallSample)
    }

    @Test
    fun fewerThanTenTimedQuestionsUsesAbsoluteThresholds() {
        val rows = listOf(
            row("c1", correct = true, dwell = 40),
            row("c2", correct = true, dwell = 40),
            row("w1", correct = false, dwell = 4),
            row("w2", correct = false, dwell = 20),
            row("s1", correct = false, dwell = 0, answered = false)
        )
        val report = report(attempt = attempt(total = 5, requested = 5), rows = rows)
        val mix = report.errorMix
        assertTrue(mix.smallSample)
        assertEquals(1, mix.rushed)
        assertEquals(1, mix.knowledgeGap)
    }

    @Test
    fun topicsSortWeakestFirstAndFlagSmallSamples() {
        val rows = listOf(
            row("a1", correct = true, dwell = 10, title = "A"),
            row("a2", correct = false, dwell = 10, title = "A"),
            row("b1", correct = true, dwell = 10, title = "B"),
            row("b2", correct = true, dwell = 10, title = "B"),
            row("b3", correct = true, dwell = 10, title = "B"),
            row("b4", correct = true, dwell = 10, title = "B"),
            row("b5", correct = false, dwell = 10, title = "B"),
            row("c1", correct = true, dwell = 10, title = "C"),
            row("c2", correct = true, dwell = 10, title = "C"),
            row("c3", correct = true, dwell = 10, title = "C")
        )
        val report = report(attempt = attempt(total = 10, requested = 10), rows = rows)
        assertEquals(listOf("A", "B", "C"), report.topics.map { it.title })
        assertTrue("2 asked is small", report.topics[0].smallSample)
        assertFalse("5 asked is not small", report.topics[1].smallSample)
        assertEquals(0.5, report.topics[0].accuracy, 0.0001)
    }

    @Test
    fun topicsInScopeThatWereNotSampledAreListed() {
        val report = report()
        assertEquals(listOf("Economy"), report.notSampledTopics)
        assertEquals("the whole paper is the scope size", 100, report.scopeSize)
    }

    @Test
    fun aTickedScopeUsesItsSubtreeForThePopulation() {
        val attempt = attempt().copy(scopeCategoryIds = "c1")
        val report = report(attempt = attempt)
        assertEquals(60, report.scopeSize)
        assertTrue(
            "a category outside the scope is not 'not sampled'",
            report.notSampledTopics.isEmpty()
        )
    }

    @Test
    fun aSmallMarginOnAFiftyQuestionRunIsTrendWorthy() {
        val rows = buildList {
            repeat(30) { add(row("c$it", correct = true, dwell = 40)) }
            repeat(20) { add(row("w$it", correct = false, dwell = 40)) }
        }
        val report = report(
            attempt = attempt(total = 50, requested = 50, correct = 30, wrong = 20, skipped = 0),
            rows = rows
        )
        assertTrue(report.trendWorthy)
    }

    @Test
    fun aTwentyQuestionPointIsNotATrend() {
        assertFalse(report().trendWorthy)
    }

    @Test
    fun thePreviousComparableIsCarriedWithItsOwnMargin() {
        val previous = attempt(
            id = 7L,
            correct = 11,
            wrong = 5,
            skipped = 4,
            score = 11.0 - 5.0 / 3.0,
            finishedAt = 500L
        )
        val report = report(previous = listOf(previous))
        assertEquals(7L, report.previousComparable?.attemptId)
        assertNotNull(report.previousComparable?.marginPoints)
    }

    @Test
    fun theTimingSummaryReportsMedianPaceAndSinks() {
        val timing = report().timing
        assertEquals(40, timing.medianSeconds)
        assertEquals(60, timing.slowest.first().seconds)
        assertEquals(3, timing.slowest.size)
        assertTrue(timing.hasDwell)
        assertEquals("2× the 40-second median wins over 60", 80, timing.slowThresholdSeconds)
    }

    @Test
    fun aLegacyAttemptGetsReviewAndTopicActionsOnly() {
        val report = report(
            attempt = attempt(testType = null, samplingVersion = 1, total = 1_000, requested = 0)
        )
        assertTrue(report.actions.isNotEmpty())
        assertTrue(
            report.actions.none { it.rule is com.mcqapp.domain.RecommendedAction.Rule.TakeLongerMock }
        )
    }

    @Test
    fun aPracticeReportNeverClaimsMockComparability() {
        val report = report(attempt = attempt(testType = "MISTAKES"))
        assertTrue(
            report.actions.none { it.rule is com.mcqapp.domain.RecommendedAction.Rule.TakeLongerMock }
        )
        assertTrue(
            "no practice action sends the learner into a random mock line",
            report.actions.none {
                it.kind == com.mcqapp.domain.RecommendedAction.Kind.TEST &&
                    it.testType == com.mcqapp.domain.TestType.RANDOM
            }
        )
    }

    @Test
    fun aMockReportStartsWithTheWrongReview() {
        val report = report()
        val first = report.actions.first()
        assertEquals(com.mcqapp.domain.RecommendedAction.Kind.REVIEW, first.kind)
        assertEquals(ReviewFilters.WRONG, first.filter)
        assertEquals(4, (first.rule as com.mcqapp.domain.RecommendedAction.Rule.ReviewWrong).count)
    }

    @Test
    fun aWideMarginAndATwentyQuestionRunRecommendAFiftyQuestionMock() {
        val report = report()
        val longer = report.actions.filterIsInstance<com.mcqapp.domain.RecommendedAction>()
            .firstOrNull { it.rule is com.mcqapp.domain.RecommendedAction.Rule.TakeLongerMock }
        assertNotNull(longer)
        val rule = longer!!.rule as com.mcqapp.domain.RecommendedAction.Rule.TakeLongerMock
        assertEquals(50, rule.targetCount)
        assertTrue(rule.canReachFifty)
        assertEquals(com.mcqapp.domain.TestType.RANDOM, longer.testType)
    }

    @Test
    fun aScopeThatCannotSupplyFiftyCapsTheNextMock() {
        val smallPaper = Paper(
            id = "p1",
            title = "Paper",
            negativeMarking = 1.0 / 3.0,
            categories = listOf(CategoryNode("c1", "p1", "Polity", null, questionCount = 25))
        )
        val rows = standardRows().take(20)
        val report = report(attempt = attempt(), rows = rows, paper = smallPaper)
        val longer = report.actions.firstOrNull {
            it.rule is com.mcqapp.domain.RecommendedAction.Rule.TakeLongerMock
        }
        // maxAllowedFor(25) is 20, the same as the request, so no action.
        assertNull(longer)
    }

    @Test
    fun noReportEverExceedsFiveActions() {
        assertTrue(report().actions.size <= 5)
    }

    @Test
    fun memoryQuadrantsIgnoreStaleAndMissingCards() {
        val rows = listOf(
            row("q1", correct = true, dwell = 30),
            row("q2", correct = false, dwell = 30),
            row("q3", correct = true, dwell = 30),
            row("q4", correct = false, dwell = 30),
            row("q5", correct = false, dwell = 0, answered = false)
        )
        val evidence = com.mcqapp.domain.ReportEvidence(
            cards = mapOf(
                "q1" to reportCard("q1", difficulty = 8.0, valid = true, dueAt = 500),
                "q2" to reportCard("q2", difficulty = 3.0, valid = true, dueAt = 2_000),
                "q3" to reportCard("q3", difficulty = 8.0, valid = false, dueAt = 500),
                "q5" to reportCard("q5", difficulty = 3.0, valid = true, dueAt = 500)
            ),
            now = 1_000
        )
        val memory = TestReportBuilder.memoryOf(rows, evidence)!!
        assertEquals(1, memory.quadrants.first { it.hard && it.correct }.count)
        assertEquals(1, memory.quadrants.first { !it.hard && !it.correct }.count)
        assertEquals(0, memory.quadrants.first { it.hard && !it.correct }.count)
        assertEquals("stale and missing cards are not estimates", 2, memory.noStudy)
        assertEquals(3, memory.validCards)
        assertEquals("only valid due cards count", 2, memory.dueNow)
    }

    @Test
    fun coverageCountsEngagedRowsOnlyAndReconcilesTheScope() {
        val rows = listOf(
            row("a", correct = true, dwell = 0),
            row("b", correct = false, dwell = 0),
            row("c", correct = false, dwell = 0, answered = false)
        )
        val evidence = com.mcqapp.domain.ReportEvidence(
            testedBefore = setOf("a"),
            testedInScope = 10
        )
        val coverage = TestReportBuilder.coverageOf(rows, evidence, scopeSize = 100)!!
        assertEquals(2, coverage.engaged)
        assertEquals(1, coverage.repeats)
        assertEquals("10 tested before + one new engaged", 89, coverage.unseenRemaining)
    }

    @Test
    fun mistakesUseTheStandingBeforeThisAttempt() {
        val rows = listOf(
            row("still-wrong", correct = false, dwell = 10),
            row("now-correct", correct = true, dwell = 10),
            row("first-wrong", correct = false, dwell = 10),
            row("skipped", correct = false, dwell = 0, answered = false)
        )
        val evidence = com.mcqapp.domain.ReportEvidence(
            priorStanding = mapOf("still-wrong" to false, "now-correct" to false),
            scopeMistakesBefore = 5
        )
        val mistakes = TestReportBuilder.mistakesOf(rows, evidence)!!
        assertEquals(1, mistakes.added)
        assertEquals(1, mistakes.cleared)
        assertEquals(5, mistakes.remaining)
    }

    @Test
    fun lifetimeAccuracyReadsTheCounterSetTheFamilyUses() {
        val rows = standardRows()
        val counters = rows.associate {
            it.questionId to com.mcqapp.domain.ReportCounters(
                correct = 4,
                wrong = 1,
                skip = 0,
                repCorrect = 2,
                repWrong = 2,
                repSkip = 0
            )
        }
        val evidence = com.mcqapp.domain.ReportEvidence(counters = counters)
        val mock = TestReportBuilder.lifetimeOf(rows, evidence, ReportFamily.MOCK)
        assertEquals(2, mock["c0"]!!.correct)
        assertEquals(4, mock["c0"]!!.answered)
        assertEquals(
            com.mcqapp.domain.signals.CounterSet.REPRESENTATIVE,
            mock["c0"]!!.counterSet
        )

        val practice = TestReportBuilder.lifetimeOf(rows, evidence, ReportFamily.PRACTICE)
        assertEquals(4, practice["c0"]!!.correct)
        assertEquals(5, practice["c0"]!!.answered)
        assertEquals(com.mcqapp.domain.signals.CounterSet.PERSONAL, practice["c0"]!!.counterSet)
    }

    @Test
    fun aPracticeReportLeadsWithTheRecordedTargets() {
        val attempt = attempt(testType = "WEAKEST_TOPICS")
        val rows = listOf(
            row("q1", correct = true, dwell = 10, title = "Polity", current = "c1"),
            row("q2", correct = false, dwell = 10, title = "Polity", current = "c1"),
            row("q3", correct = false, dwell = 10, title = "Economy", current = "c2")
        )
        val evidence = com.mcqapp.domain.ReportEvidence(
            targetSizes = mapOf("c1" to 60),
            targetTitles = mapOf("c1" to "Polity")
        )
        val report = TestReportBuilder.build(
            TestReportInput(
                attempt = attempt,
                rows = rows,
                paper = paper(),
                targetTopicIds = listOf("c1"),
                evidence = evidence
            )
        )
        val practice = report.practice!!
        assertFalse(practice.withoutTargets)
        assertEquals(1, practice.targets.size)
        assertEquals("Polity", practice.targets.single().title)
        assertEquals(2, practice.targets.single().asked)
        assertEquals(1, practice.targets.single().correct)
        assertEquals(60, practice.targets.single().poolSize)
        assertNull("a practice attempt never gets a mock trend", report.trend)
    }

    @Test
    fun aTrendNeedsTwoComparableTrendWorthyAttempts() {
        val current = attempt(
            id = 3,
            total = 60,
            requested = 60,
            correct = 40,
            wrong = 10,
            skipped = 10,
            score = 40.0,
            maxScore = 60.0
        )
        val previous = attempt(
            id = 2,
            total = 60,
            requested = 60,
            correct = 35,
            wrong = 15,
            skipped = 10,
            score = 35.0,
            maxScore = 60.0,
            finishedAt = 900
        )
        val rows = (1..60).map {
            row("q$it", correct = it <= 40, dwell = 10)
        }
        val report = TestReportBuilder.build(
            TestReportInput(
                attempt = current,
                rows = rows,
                paper = paper(),
                comparableAttempts = listOf(previous)
            )
        )
        val trend = report.trend!!
        assertTrue(trend.isLine)
        assertEquals(2, trend.points.size)
        assertTrue(trend.deltaPoints > 0)
        assertFalse(trend.freshSample)

        val fresh = TestReportBuilder.build(
            TestReportInput(
                attempt = current.copy(exclusionMode = ExclusionMode.RECENT_3.name),
                rows = rows,
                paper = paper(),
                comparableAttempts = listOf(previous)
            )
        )
        assertTrue(fresh.trend!!.freshSample)
    }

    @Test
    fun theBootstrapIsSeededAndResamplesPassages() {
        val rows = (1..20).map {
            ReportRow(
                questionId = "q$it",
                categoryTitle = "Cat",
                currentCategoryId = "c1",
                dwellSeconds = 10,
                isCorrect = it <= 12,
                graded = true,
                answered = true,
                passageId = "p${(it - 1) / 2}"
            )
        }
        val first = TestReportBuilder.bootstrapOf(attempt(), rows)!!
        val second = TestReportBuilder.bootstrapOf(attempt(), rows)!!
        assertEquals(first, second)
        assertEquals(10, first.passageBlocks)
        assertEquals(1_000, first.resamples)
        assertTrue(first.lowPercent <= first.highPercent)
    }

    @Test
    fun reviewsCarryTheWhyTagAndTheStudyBadge() {
        val rows = listOf(
            row("solid", correct = true, dwell = 30),
            row("guess", correct = true, dwell = 2),
            row("rushed", correct = false, dwell = 8),
            row("gap", correct = false, dwell = 40),
            row("skipped", correct = false, dwell = 0, answered = false),
            row("ungraded", correct = false, dwell = 0, graded = false, answered = false)
        )
        val timing = com.mcqapp.domain.TimingSummary(
            totalSeconds = 300,
            timedQuestions = 10,
            medianSeconds = 40,
            averageSeconds = 40,
            correctAverageSeconds = 30,
            wrongAverageSeconds = 40,
            targetSecondsPerQuestion = 60,
            slowest = emptyList(),
            smallSample = false,
            slowThresholdSeconds = 60
        )
        val evidence = com.mcqapp.domain.ReportEvidence(
            cards = mapOf(
                "solid" to reportCard("solid", difficulty = 3.0, valid = true, dueAt = 2_000),
                "guess" to reportCard("guess", difficulty = 8.0, valid = true, dueAt = 500),
                "gap" to reportCard("gap", difficulty = 9.0, valid = true, dueAt = 500),
                "skipped" to reportCard("skipped", difficulty = 3.0, valid = false, dueAt = 500)
            ),
            now = 1_000
        )
        val reviews = TestReportBuilder.reviewsOf(
            rows,
            timing,
            evidence,
            targetTopicIds = emptyList(),
            family = ReportFamily.PRACTICE
        )
        assertEquals(com.mcqapp.domain.ReviewTag.SOLID_CORRECT, reviews["solid"]!!.tag)
        assertEquals(com.mcqapp.domain.ReviewTag.POSSIBLE_GUESS, reviews["guess"]!!.tag)
        assertTrue("the guess card is due now", reviews["guess"]!!.dueNow)
        assertEquals(com.mcqapp.domain.ReviewTag.RUSHED, reviews["rushed"]!!.tag)
        assertEquals(com.mcqapp.domain.ReviewTag.KNOWLEDGE_GAP, reviews["gap"]!!.tag)
        assertEquals(com.mcqapp.domain.ReviewTag.SKIPPED, reviews["skipped"]!!.tag)
        assertTrue("the stale card says so", reviews["skipped"]!!.staleCard)
        assertEquals(com.mcqapp.domain.ReviewTag.UNGRADED, reviews["ungraded"]!!.tag)
        assertFalse("a question with no card claims no Study memory", reviews["rushed"]!!.inStudy)
    }

    @Test
    fun aFragileCorrectIsAHardOrLowRecallCard() {
        val rows = listOf(row("hard", correct = true, dwell = 30))
        val timing = com.mcqapp.domain.TimingSummary(
            totalSeconds = 30,
            timedQuestions = 1,
            medianSeconds = 30,
            averageSeconds = 30,
            correctAverageSeconds = 30,
            wrongAverageSeconds = 0,
            targetSecondsPerQuestion = 60,
            slowest = emptyList(),
            smallSample = true,
            slowThresholdSeconds = 60
        )
        val evidence = com.mcqapp.domain.ReportEvidence(
            cards = mapOf(
                "hard" to reportCard("hard", difficulty = 8.0, valid = true, dueAt = 2_000)
            ),
            now = 1_000
        )
        val reviews = TestReportBuilder.reviewsOf(
            rows,
            timing,
            evidence,
            emptyList(),
            ReportFamily.MOCK
        )
        assertEquals(com.mcqapp.domain.ReviewTag.FRAGILE_CORRECT, reviews["hard"]!!.tag)
        assertEquals(8.0, reviews["hard"]!!.difficulty!!, 0.001)
    }

    @Test
    fun practiceTrendUsesOnlyPriorRunsOnTheSameTargets() {
        val rows = listOf(
            row("t1", correct = true, dwell = 10, title = "Polity", current = "c1"),
            row("t2", correct = false, dwell = 10, title = "Polity", current = "c1"),
            row("t3", correct = false, dwell = 10, title = "Polity", current = "c1"),
            row("t4", correct = false, dwell = 10, title = "Polity", current = "c1")
        )
        val report = TestReportBuilder.build(
            TestReportInput(
                attempt = attempt(testType = "WEAKEST_TOPICS", targetTopicIds = "c1"),
                rows = rows,
                paper = paper(),
                targetTopicIds = listOf("c1"),
                evidence = com.mcqapp.domain.ReportEvidence(
                    targetSizes = mapOf("c1" to 20),
                    targetTitles = mapOf("c1" to "Polity")
                ),
                practiceHistory = listOf(
                    com.mcqapp.domain.PracticeTrendPoint(2, 800, asked = 5, correct = 1),
                    com.mcqapp.domain.PracticeTrendPoint(3, 900, asked = 5, correct = 1)
                )
            )
        )
        val trend = report.practiceTrend!!
        assertEquals(3, trend.points.size)
        assertTrue("the target accuracy improved", trend.deltaPoints!! > 0)
        assertTrue("a practice run still has no mock trend", report.trend == null)
        assertEquals(1, report.practice!!.targetedCorrect)
        assertEquals(4, report.practice!!.targetedAsked)

        val mock = TestReportBuilder.build(
            TestReportInput(
                attempt = attempt(),
                rows = rows,
                paper = paper(),
                practiceHistory = listOf(
                    com.mcqapp.domain.PracticeTrendPoint(2, 800, asked = 5, correct = 1)
                )
            )
        )
        assertNull("a mock never reads the practice line", mock.practiceTrend)
    }

    @Test
    fun practiceActionsFollowThePracticeRules() {
        val rows = listOf(
            row("t1", correct = true, dwell = 10, title = "Polity", current = "c1"),
            row("t2", correct = false, dwell = 10, title = "Polity", current = "c1"),
            row("t3", correct = false, dwell = 10, title = "Polity", current = "c1"),
            row("t4", correct = false, dwell = 10, title = "Polity", current = "c1")
        )
        val report = TestReportBuilder.build(
            TestReportInput(
                attempt = attempt(
                    testType = "WEAKEST_TOPICS",
                    total = 20,
                    requested = 20,
                    correct = 1,
                    wrong = 3,
                    skipped = 0,
                    score = 1.0,
                    maxScore = 20.0,
                    targetTopicIds = "c1"
                ),
                rows = rows,
                paper = paper(),
                targetTopicIds = listOf("c1"),
                evidence = com.mcqapp.domain.ReportEvidence(
                    targetSizes = mapOf("c1" to 20),
                    targetTitles = mapOf("c1" to "Polity")
                ),
                practiceHistory = listOf(
                    com.mcqapp.domain.PracticeTrendPoint(2, 800, asked = 5, correct = 1)
                )
            )
        )
        val rules = report.actions.map { it.rule }
        assertTrue(
            "a weak target set repeats the focus",
            rules.any { it is com.mcqapp.domain.RecommendedAction.Rule.RepeatPractice }
        )
        assertTrue(
            "open mistakes get their own practice",
            rules.any { it is com.mcqapp.domain.RecommendedAction.Rule.PractiseMistakes }
        )
        assertTrue(
            "an improved practice target asks for a random control",
            rules.any { it is com.mcqapp.domain.RecommendedAction.Rule.CheckTransfer }
        )
        assertTrue(
            "half-uncovered targets continue the focus set",
            rules.any { it is com.mcqapp.domain.RecommendedAction.Rule.ContinueFocus }
        )
        assertTrue(report.actions.size <= 5)
    }

    @Test
    fun anAtRiskPracticeRecommendsStudyWhenQuestionsAreStillDue() {
        val rows = listOf(
            row("t1", correct = true, dwell = 10, title = "Polity", current = "c1"),
            row("t2", correct = true, dwell = 10, title = "Polity", current = "c1")
        )
        val report = TestReportBuilder.build(
            TestReportInput(
                attempt = attempt(
                    testType = "AT_RISK",
                    total = 20,
                    requested = 20,
                    correct = 2,
                    wrong = 0,
                    skipped = 0,
                    score = 2.0,
                    maxScore = 20.0,
                    targetTopicIds = "c1"
                ),
                rows = rows,
                paper = paper(),
                targetTopicIds = listOf("c1"),
                evidence = com.mcqapp.domain.ReportEvidence(
                    cards = mapOf(
                        "t1" to reportCard("t1", difficulty = 6.0, valid = true, dueAt = 500),
                        "t2" to reportCard("t2", difficulty = 6.0, valid = true, dueAt = 500)
                    ),
                    targetSizes = mapOf("c1" to 20),
                    targetTitles = mapOf("c1" to "Polity"),
                    now = 1_000
                )
            )
        )
        val due = report.actions.firstOrNull {
            it.rule is com.mcqapp.domain.RecommendedAction.Rule.ReviewDue
        }
        assertNotNull("expected the due-in-Study rule", due)
        assertEquals(
            2,
            (due!!.rule as com.mcqapp.domain.RecommendedAction.Rule.ReviewDue).due
        )
        assertEquals(com.mcqapp.domain.RecommendedAction.Kind.STUDY, due.kind)
    }

    private fun reportCard(
        id: String,
        difficulty: Double,
        valid: Boolean,
        dueAt: Long
    ) = com.mcqapp.domain.ReportCard(
        questionId = id,
        difficulty = difficulty,
        stability = 10.0,
        reviewCount = 5,
        lapses = 0,
        leech = false,
        dueAt = dueAt,
        lastReviewedAt = 1L,
        contentHashMatches = valid,
        cardState = com.mcqapp.domain.CardStateKind.REVIEW,
        retention = 0.8
    )
}
