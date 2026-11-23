package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.CardSnapshot
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.StatsAggregator
import com.mcqapp.ui.stats.charts.SMALL_BUCKET_REVIEWS
import com.mcqapp.ui.stats.charts.isSmallBucket
import com.mcqapp.ui.stats.charts.linePointText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Pure-JVM regressions for the wave-1 statistics honesty findings. Every test
 * fails on the old behaviour: self-graded rows in calibration, a second "true"
 * headline, a card in both due buckets, learning stumbles as lapses, aggregates
 * with no card to open, and thin buckets spoken at full weight.
 *
 * INCIDENT: each of those findings shipped once — a second "true" headline and
 * a card in both due buckets among them — so the source assertions below pin
 * the wiring that makes the honest figures the ones the learner actually sees.
 */
class StatsHonestyGuardTest {

    private val day = DayBoundary.DAY_MS
    private val hour = 3_600_000L
    private val now = LocalDate.of(2026, 6, 15).toEpochDay() * day + 12 * hour
    private val todayStart = DayBoundary.startOfDay(now)

    private fun review(
        questionId: String,
        rating: Int = 3,
        reviewedAt: Long = now - hour,
        paperId: String = "p1",
        elapsedSeconds: Long? = 10L,
        stateBefore: CardStateKind? = CardStateKind.REVIEW,
        intervalBeforeDays: Int? = 30,
        predictedRetention: Double? = null,
        wasGuess: Boolean? = null,
        wasCorrect: Boolean? = null,
        wasSkipped: Boolean? = null,
        correctnessOnly: Boolean = true
    ) = ReplayReview(
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = elapsedSeconds,
        stateBefore = stateBefore,
        stabilityAfter = 5.0,
        difficultyAfter = 5.0,
        intervalDaysAfter = 30,
        predictedRetention = predictedRetention,
        intervalBeforeDays = intervalBeforeDays,
        wasGuess = wasGuess,
        wasCorrect = wasCorrect,
        wasSkipped = wasSkipped,
        correctnessOnly = correctnessOnly
    )

    private fun card(
        questionId: String,
        paperId: String = "p1",
        state: CardStateKind = CardStateKind.REVIEW,
        dueAt: Long = 0L,
        intervalDays: Int = 30
    ) = CardSnapshot(
        paperId = paperId,
        state = CardState(
            questionId = questionId,
            stability = 10.0,
            difficulty = 5.0,
            intervalDays = intervalDays,
            dueAt = dueAt,
            state = state
        )
    )

    private fun input(
        reviews: List<ReplayReview> = emptyList(),
        cards: List<CardSnapshot> = emptyList(),
        config: SchedulerConfig = SchedulerConfig(),
        categoryOfQuestion: Map<String, String> = emptyMap(),
        tagsOfQuestion: Map<String, List<String>> = emptyMap()
    ) = StatsAggregator.Input(
        reviews = reviews,
        cards = cards,
        categoryOfQuestion = categoryOfQuestion,
        paperTitleOf = mapOf("p1" to "Paper One", "p2" to "Paper Two"),
        scheduler = FsrsScheduler(config = config),
        config = config,
        now = now,
        zone = DayBoundary.utc(),
        tagsOfQuestion = tagsOfQuestion
    )

    private fun build(
        reviews: List<ReplayReview> = emptyList(),
        cards: List<CardSnapshot> = emptyList(),
        config: SchedulerConfig = SchedulerConfig(),
        categoryOfQuestion: Map<String, String> = emptyMap(),
        tagsOfQuestion: Map<String, List<String>> = emptyMap()
    ) = StatsAggregator.build(
        input(reviews, cards, config, categoryOfQuestion, tagsOfQuestion),
        scopePaperId = null,
        range = StatsAggregator.Range.ALL
    )

    // --- 1. calibration / Brier evidence gate -------------------------------

    @Test
    fun calibrationUsesOnlyObjectiveAnswersRightOrWrong() {
        val bundle = build(
            listOf(
                // An objective pass qualifies.
                review("objective", predictedRetention = 0.8, wasCorrect = true),
                // A self-graded pass: no objective outcome at all.
                review("self", predictedRetention = 0.8, wasCorrect = null),
                // A correct guess and a skip are not honest recall evidence.
                review("guess", predictedRetention = 0.8, wasCorrect = true, wasGuess = true),
                review("skip", predictedRetention = 0.8, wasCorrect = true, wasSkipped = true),
                // A row from the clock-tinted grading rule is not a correctness grade.
                review("timed", predictedRetention = 0.8, wasCorrect = true, correctnessOnly = false),
                // An objectively wrong answer is honest evidence too: keeping
                // only correct rows pins actualRate at 1.0 and Brier one-sided.
                review("wrong", rating = 1, predictedRetention = 0.8, wasCorrect = false)
            )
        )
        assertEquals(2, bundle.calibration.samples)
        // (0.2^2 + 0.8^2) / 2 = 0.34
        assertEquals(0.34, bundle.calibration.brier!!, 1e-9)
        assertEquals(2, bundle.calibration.bins.single().count)
        assertEquals(0.5, bundle.calibration.bins.single().actualRate!!, 1e-9)
        assertTrue(bundle.calibration.bins.single().actualRate!! < 1.0)
        assertEquals(0.5, bundle.calibration.actualMean!!, 1e-9)
    }

    @Test
    fun calibrationStaysEmptyWhenEveryPredictionBelongsToASelfGradedPass() {
        val bundle = build(
            listOf(
                review("card1", predictedRetention = 0.9, wasCorrect = null),
                review("card2", predictedRetention = 0.7, wasCorrect = null)
            )
        )
        assertEquals(0, bundle.calibration.samples)
        assertNull(bundle.calibration.brier)
        assertTrue(bundle.calibration.bins.isEmpty())
    }

    // --- 3. one honest headline ---------------------------------------------

    @Test
    fun honestHeadlineIsMatureObjectiveRetentionWithASignedGapAndCount() {
        val config = SchedulerConfig(desiredRetention = 0.9)
        val bundle = build(
            listOf(
                review("a", wasCorrect = true),
                review("b", wasCorrect = true),
                review("c", rating = 1, wasCorrect = false),
                // Excluded from the honest sample: no outcome, a guess, and a
                // young card respectively.
                review("self", wasCorrect = null),
                review("lucky", wasCorrect = true, wasGuess = true),
                review("young", intervalBeforeDays = 5, wasCorrect = true)
            ),
            config = config
        )
        val kpis = bundle.kpis
        assertEquals(3, kpis.objectiveMatureReviews)
        assertEquals(2.0 / 3.0, kpis.objectiveMatureRetention!!, 1e-9)
        assertEquals(2.0 / 3.0 - 0.9, kpis.objectiveRetentionGap!!, 1e-9)
        // The grade-based mature figure still exists, and is different: the
        // headline deliberately does not reuse it.
        assertNotEquals(kpis.matureRetention!!, kpis.objectiveMatureRetention!!)
        val last30 = bundle.retentionTable.first { it.label == "all" }
        assertEquals(3, last30.objectiveMatureReviews)
        assertEquals(2, last30.objectiveMaturePasses)
    }

    @Test
    fun honestHeadlineHasNoNumberWhenNoObjectiveMatureEvidenceExists() {
        val bundle = build(
            listOf(
                review("self", wasCorrect = null),
                review("young", intervalBeforeDays = 3, wasCorrect = true)
            )
        )
        assertNull(bundle.kpis.objectiveMatureRetention)
        assertNull(bundle.kpis.objectiveRetentionGap)
        assertEquals(0, bundle.kpis.objectiveMatureReviews)
    }

    // --- 4. one due definition ----------------------------------------------

    @Test
    fun aCardDueEarlierTodayIsNotAlsoBacklogAndForecastUsesTheSameEdges() {
        val bundle = build(
            cards = listOf(
                card("earlier-today", dueAt = now - hour),
                card("overdue", dueAt = todayStart - day),
                card("tomorrow", dueAt = todayStart + day)
            )
        )
        // Earlier today is due now, not overdue. Only yesterday's card is
        // backlog, matching the forecast's overdue bar and its bar 0.
        assertEquals(1, bundle.today.dueNow)
        assertEquals(1, bundle.today.backlog)
        assertEquals(1, bundle.forecast.overdue)
        assertEquals(1, bundle.forecast.dueToday)
        assertEquals(1, bundle.forecast.days[0].review)
        assertEquals(0, bundle.forecast.days[0].learning)
        assertEquals(bundle.today.backlog, bundle.forecast.overdue)
        assertEquals(bundle.today.dueNow, bundle.forecast.dueToday)
    }

    // --- 6. lapses, not first-day stumbles ----------------------------------

    @Test
    fun learningStepFailuresDoNotMakeACardMostForgotten() {
        val stumbles = listOf(
            review("stumbler", rating = 1, stateBefore = CardStateKind.NEW, intervalBeforeDays = null, wasCorrect = false),
            review("stumbler", rating = 1, stateBefore = CardStateKind.LEARNING, intervalBeforeDays = null, wasCorrect = false),
            review("stumbler", rating = 1, stateBefore = CardStateKind.LEARNING, intervalBeforeDays = null, wasCorrect = false)
        )
        val lapses = listOf(
            review("forgetter", rating = 1, stateBefore = CardStateKind.REVIEW, intervalBeforeDays = 40, wasCorrect = false),
            review("forgetter", rating = 1, stateBefore = CardStateKind.REVIEW, intervalBeforeDays = 40, wasCorrect = false),
            review("forgetter", rating = 3, stateBefore = CardStateKind.REVIEW, intervalBeforeDays = 40, wasCorrect = true)
        )
        val weakest = build(stumbles + lapses).weakest
        assertEquals(1, weakest.size)
        assertEquals("forgetter", weakest.single().questionId)
        assertEquals(2, weakest.single().lapses)
        assertFalse(weakest.any { it.questionId == "stumbler" })
    }

    // --- 7. group bars have a card to open ----------------------------------

    @Test
    fun retentionGroupIntervalTagAndCategoryBarsCarryTheirWeakestCard() {
        val reviews = listOf(
            review("weak", rating = 1, paperId = "p1", wasCorrect = false),
            review("weak", rating = 3, paperId = "p1", wasCorrect = true, reviewedAt = now - 2 * hour),
            review("strong", rating = 3, paperId = "p1", wasCorrect = true),
            review("other", rating = 3, paperId = "p2", wasCorrect = true)
        )
        val data = StatsAggregator.buildRetention(
            input(
                reviews,
                categoryOfQuestion = mapOf("weak" to "Algebra", "strong" to "Algebra", "other" to "Geometry"),
                tagsOfQuestion = mapOf("weak" to listOf("chapter-1"), "strong" to listOf("chapter-1"))
            ),
            scopePaperId = null,
            range = StatsAggregator.Range.ALL
        )
        val byPaper = data.groupRetention.associateBy { it.label }
        assertEquals("weak", byPaper.getValue("Paper One").drill!!.questionId)
        assertEquals("p1", byPaper.getValue("Paper One").drill!!.paperId)
        assertEquals("other", byPaper.getValue("Paper Two").drill!!.questionId)

        val band = data.intervalRetention.first { it.reviews > 0 }
        assertEquals("weak", band.drill!!.questionId)
        assertEquals("weak", data.tagRetention.single().drill!!.questionId)
        assertEquals(
            "weak",
            data.categoryRetention.first { it.label == "Algebra" }.drill!!.questionId
        )
    }

    @Test
    fun aSinglePaperGroupStillOpensACard() {
        val bundle = StatsAggregator.buildRetention(
            input(listOf(review("solo", wasCorrect = true))),
            scopePaperId = "p1",
            range = StatsAggregator.Range.ALL
        )
        assertEquals("solo", bundle.groupRetention.single().drill!!.questionId)
    }

    @Test
    fun fragileGuessingTopicOpensItsWeakestCard() {
        val bundle = build(
            listOf(
                review("unlucky", rating = 3, wasGuess = true, wasCorrect = false),
                review("unlucky", rating = 3, wasGuess = true, wasCorrect = false, reviewedAt = now - 2 * hour),
                review("lucky", rating = 3, wasGuess = true, wasCorrect = true)
            ),
            categoryOfQuestion = mapOf("unlucky" to "Algebra", "lucky" to "Algebra")
        )
        val topic = bundle.guessing.topics.single()
        assertNotNull("a fragile topic must point at a card", topic.drill)
        assertEquals("unlucky", topic.drill!!.questionId)
    }

    // --- 5. thin buckets -----------------------------------------------------

    @Test
    fun linePointsSpeakTheirSampleSizeAndSmallBucketsAreDeEmphasised() {
        assertEquals("90% · 1 review", linePointText("90%", 1, "review", "reviews"))
        assertEquals("70% · 40 reviews", linePointText("70%", 40, "review", "reviews"))
        assertEquals("—", linePointText("—", 0, "review", "reviews"))
        assertTrue(isSmallBucket(1))
        assertTrue(isSmallBucket(SMALL_BUCKET_REVIEWS - 1))
        assertFalse(isSmallBucket(SMALL_BUCKET_REVIEWS))
        assertFalse(isSmallBucket(0)) // no reviews draws a gap, not a faded point
    }

    @Test
    fun forecastCopyNamesTheFirstBarAsTodayNotOverdue() {
        val workload = File("src/main/java/com/mcqapp/ui/stats/sections/WorkloadSection.kt").readText()
        assertTrue(workload.contains("R.string.stats_forecast_help_aligned"))
        val copy = File("src/main/res/values/strings_stats_honesty.xml").readText()
        assertTrue(copy, copy.contains("The first bar is today"))
        assertFalse(
            "the old copy called bar 0 the overdue bucket",
            copy.contains("the first bar is everything due before today")
        )
    }

    @Test
    fun retentionAndProgressLinesPassCountsAndDeEmphasiseThinBuckets() {
        val retention = File("src/main/java/com/mcqapp/ui/stats/sections/RetentionSection.kt").readText()
        val progress = File("src/main/java/com/mcqapp/ui/stats/sections/ProgressSection.kt").readText()
        assertTrue(retention.contains("counts = trend.points.map { it.count }"))
        assertTrue(retention.contains("deEmphasiseBelow = SMALL_BUCKET_REVIEWS"))
        assertTrue(progress.contains("counts = data.trend.map { it.reviews }"))
        assertTrue(progress.contains("deEmphasiseBelow = SMALL_BUCKET_REVIEWS"))
    }

    // --- copy: new files, aligned across locales ----------------------------

    @Test
    fun honestyCopyDeclaresTheSameNamesAndPlaceholdersInBothLocales() {
        fun resources(path: String): Map<String, String> {
            val xml = File(path).readText()
            return Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        }
        val english = resources("src/main/res/values/strings_stats_honesty.xml")
        val hindi = resources("src/main/res/values-hi/strings_stats_honesty_hi.xml")
        assertEquals(english.keys, hindi.keys)
        assertTrue(english.keys.containsAll(
            listOf(
                "stats_honest_headline_value", "stats_honest_headline_gap",
                "stats_honest_headline_reviews", "stats_calibration_honest_note",
                "stats_forecast_help_aligned", "stats_chart_small_buckets",
                "stats_guess_empty_self_graded", "stats_tests_family_row_unweighted",
                "stats_exam_kpi_average_pooled"
            )
        ))
        (english.keys).forEach { name ->
            val format = { value: String -> Regex("""%\d+\$[a-zA-Z]""").findAll(value).map { it.value }.toSet() }
            assertEquals("format specifiers differ for $name", format(english.getValue(name)), format(hindi.getValue(name)))
        }
        // The base strings file is left alone; all new copy lives in the new file.
        assertFalse(
            File("src/main/res/values/strings.xml").readText()
                .contains("name=\"stats_honest_headline_title\"")
        )
    }
}
