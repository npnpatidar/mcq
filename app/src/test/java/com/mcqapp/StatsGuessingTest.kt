package com.mcqapp

import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.GuessBand
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.StatsAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The guessing dashboard's arithmetic: the 2×2 outcomes, the chance baseline,
 * the counterfactual schedule cost, and the slices that say when guessing
 * happens. Pure JVM — no database, no scheduler config beyond the defaults.
 */
class StatsGuessingTest {

    private val day = DayBoundary.DAY_MS
    private val minute = 60_000L

    /** 15 Jun 2026, 12:00 UTC. */
    private val now = LocalDate.of(2026, 6, 15).toEpochDay() * day + 12 * 3_600_000L

    private fun review(
        questionId: String,
        reviewedAt: Long,
        guess: Boolean? = true,
        correct: Boolean? = true,
        stateBefore: CardStateKind? = CardStateKind.NEW,
        intervalBeforeDays: Int? = null,
        elapsedSeconds: Long? = 10L,
        intervalDaysAfter: Int? = null,
        wouldBeGoodIntervalDays: Int? = null,
        paperId: String = "p1"
    ) = ReplayReview(
        paperId = paperId,
        questionId = questionId,
        rating = if (correct == true) 3 else 1,
        reviewedAt = reviewedAt,
        elapsedSeconds = elapsedSeconds,
        stateBefore = stateBefore,
        stabilityAfter = 5.0,
        difficultyAfter = 5.0,
        intervalDaysAfter = intervalDaysAfter,
        predictedRetention = null,
        intervalBeforeDays = intervalBeforeDays,
        wasGuess = guess,
        wasCorrect = correct,
        wouldBeGoodIntervalDays = wouldBeGoodIntervalDays
    )

    private fun build(
        reviews: List<ReplayReview>,
        chanceOfQuestion: Map<String, Double> = emptyMap(),
        categoryOfQuestion: Map<String, String> = emptyMap(),
        paperTitleOf: Map<String, String> = mapOf("p1" to "Paper One")
    ) = StatsAggregator.buildGuessing(
        input = StatsAggregator.Input(
            reviews = reviews,
            cards = emptyList(),
            categoryOfQuestion = categoryOfQuestion,
            paperTitleOf = paperTitleOf,
            scheduler = FsrsScheduler(config = SchedulerConfig()),
            config = SchedulerConfig(),
            now = now,
            zoneOffsetMillis = 0L,
            chanceOfQuestion = chanceOfQuestion
        ),
        scopePaperId = null,
        range = StatsAggregator.Range.ALL
    )

    @Test
    fun flaggedReviewsSplitIntoTheFourOutcomesWhileUnflaggedOnesAreOnlyCounted() {
        val data = build(
            listOf(
                review("q1", now - 50 * minute, guess = false, correct = true),
                review("q2", now - 40 * minute, guess = false, correct = true),
                review("q3", now - 30 * minute, guess = true, correct = true),
                review("q4", now - 20 * minute, guess = true, correct = false),
                review("q5", now - 10 * minute, guess = false, correct = false),
                // A row written before v19: no flag at all.
                review("q6", now, guess = null, correct = null)
            )
        )

        assertEquals(6, data.allReviews)
        assertEquals(5, data.reviews)
        assertEquals(2, data.earned)
        assertEquals(1, data.lucky)
        assertEquals(1, data.lapse)
        assertEquals(1, data.didntKnow)
        assertEquals(2, data.guessed)
        assertEquals(0.4, data.guessRate!!, 1e-9)
        assertEquals(0.5, data.hitRate!!, 1e-9)
        assertEquals(0.6, data.rawAccuracy!!, 1e-9)
        assertEquals(0.4, data.gradedPassRate!!, 1e-9)
        assertFalse("one row carries no flag", data.isComplete)
        assertTrue(data.hasData)
    }

    @Test
    fun chanceBaselineWeightsEveryGuessByItsOwnQuestion() {
        // q1 is a four-option single-answer question (25%); q2 is true/false
        // (50%). Two guesses on q1 and one on q2 pool to one third, not to the
        // mean of the two questions' rates.
        val data = build(
            listOf(
                review("q1", now - 30 * minute),
                review("q1", now - 20 * minute),
                review("q2", now - 10 * minute)
            ),
            chanceOfQuestion = mapOf("q1" to 0.25, "q2" to 0.5)
        )

        assertEquals(1.0 / 3.0, data.chanceBaseline!!, 1e-9)
        assertEquals(1.0 - 1.0 / 3.0, data.lift!!, 1e-9)
    }

    @Test
    fun liftIsNullWithoutAChanceBaselineAndHitRateIsNullWithoutGuesses() {
        val noChance = build(listOf(review("q1", now)))
        assertNull(noChance.chanceBaseline)
        assertNull(noChance.lift)

        val noGuesses = build(
            listOf(review("q1", now, guess = false, correct = true)),
            chanceOfQuestion = mapOf("q1" to 0.25)
        )
        assertNull(noGuesses.hitRate)
        assertNull(noGuesses.lift)
        assertEquals(0.0, noGuesses.guessRate!!, 1e-9)
    }

    @Test
    fun counterfactualCostSumsWhatGoodWouldHaveGivenMinusWhatTheCardKept() {
        val data = build(
            listOf(
                review(
                    "q1", now - 20 * minute,
                    intervalDaysAfter = 0, wouldBeGoodIntervalDays = 10
                ),
                review(
                    "q2", now - 10 * minute,
                    intervalDaysAfter = 4, wouldBeGoodIntervalDays = 10
                ),
                // Not guessed-correct, so it contributes nothing.
                review(
                    "q3", now,
                    guess = false, correct = true,
                    intervalDaysAfter = 3, wouldBeGoodIntervalDays = null
                )
            )
        )

        assertEquals(2, data.cost.reviews)
        assertEquals(16, data.cost.extraDays)
        assertEquals(2.0, data.cost.avgActualDays!!, 1e-9)
        assertEquals(10.0, data.cost.avgGoodDays!!, 1e-9)
    }

    @Test
    fun sittingPositionRestartsAfterAThirtyMinuteGap() {
        val start = now - 60 * minute
        val data = build(
            listOf(
                review("q1", start),
                review("q2", start + 10 * minute),
                review("q3", start + 20 * minute),
                // 40 minutes after the previous review: a new sitting.
                review("q4", start + 60 * minute)
            )
        )

        assertEquals(2, data.positions.first { it.label == "1" }.reviews)
        assertEquals(1, data.positions.first { it.label == "2" }.reviews)
        assertEquals(1, data.positions.first { it.label == "3" }.reviews)
        assertEquals(0, data.positions.first { it.label == "5+" }.reviews)
    }

    @Test
    fun maturityBandsFollowTheCardStateAndTheIntervalCarriedIn() {
        val data = build(
            listOf(
                review("q1", now - 50 * minute, stateBefore = CardStateKind.NEW),
                review(
                    "q2", now - 40 * minute,
                    stateBefore = CardStateKind.REVIEW, intervalBeforeDays = 30
                ),
                review(
                    "q3", now - 30 * minute,
                    stateBefore = CardStateKind.REVIEW, intervalBeforeDays = 5
                ),
                review("q4", now - 20 * minute, stateBefore = CardStateKind.LEARNING),
                review("q5", now - 10 * minute, stateBefore = CardStateKind.RELEARNING)
            )
        )

        fun reviewsOf(band: GuessBand) =
            data.maturity.first { it.band == band }.reviews

        assertEquals(1, reviewsOf(GuessBand.FRESH))
        assertEquals(1, reviewsOf(GuessBand.MATURE))
        assertEquals(1, reviewsOf(GuessBand.YOUNG))
        assertEquals(1, reviewsOf(GuessBand.LEARNING))
        assertEquals(1, reviewsOf(GuessBand.RELEARNING))
    }

    @Test
    fun dwellComparesGuessedAndEarnedAnswerTime() {
        val data = build(
            listOf(
                review("q1", now - 30 * minute, guess = true, elapsedSeconds = 4),
                review("q2", now - 20 * minute, guess = true, elapsedSeconds = 6),
                review("q3", now - 10 * minute, guess = false, correct = true, elapsedSeconds = 20)
            )
        )

        assertEquals(2, data.dwell.guessedReviews)
        assertEquals(5.0, data.dwell.guessedAvgSeconds!!, 1e-9)
        assertEquals(1, data.dwell.earnedReviews)
        assertEquals(20.0, data.dwell.earnedAvgSeconds!!, 1e-9)
    }

    @Test
    fun shakyCardsNeedTwoGuessesAndAreRankedByHowOftenTheyWereGuessed() {
        val data = build(
            listOf(
                review("q1", now - 50 * minute),
                review("q1", now - 40 * minute, correct = false),
                review("q1", now - 30 * minute),
                review("q2", now - 20 * minute),
                review("q2", now - 10 * minute),
                review("q3", now)
            )
        )

        assertEquals(listOf("q1", "q2"), data.shaky.map { it.questionId })
        assertEquals(3, data.shaky[0].guessed)
        assertEquals(2, data.shaky[0].guessedCorrect)
        assertEquals(2.0 / 3.0, data.shaky[0].hitRate!!, 1e-9)
    }

    @Test
    fun topicsLabelByCategoryWhenScopedAndByPaperOtherwise() {
        val scoped = build(
            listOf(
                review("q1", now - 20 * minute),
                review("q2", now - 10 * minute),
                review("q2", now)
            ),
            categoryOfQuestion = mapOf("q1" to "Algebra", "q2" to "Geometry")
        )
        assertEquals(
            listOf("Geometry", "Algebra"),
            scoped.topics.map { it.label }
        )
        assertEquals(2, scoped.topics.first().guessed)

        val unscoped = build(listOf(review("q1", now)))
        assertEquals(listOf("Paper One"), unscoped.topics.map { it.label })
    }
}
