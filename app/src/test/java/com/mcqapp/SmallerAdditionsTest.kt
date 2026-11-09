package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.CardHistorySummary
import com.mcqapp.domain.stats.CardSnapshot
import com.mcqapp.domain.stats.ProgressComparison
import com.mcqapp.domain.stats.ReviewHistoryEntry
import com.mcqapp.domain.stats.StatsAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The smaller additions in plan §8: card-sheet summary and memory by paper. */
class SmallerAdditionsTest {

    private val now = 1_000_000_000_000L

    // --- card-sheet summary (plan §8.2) --------------------------------------

    private fun history(
        rating: Int,
        elapsedSeconds: Long? = null
    ) = ReviewHistoryEntry(
        rating = rating,
        reviewedAt = now,
        elapsedSeconds = elapsedSeconds,
        stateBefore = CardStateKind.REVIEW,
        stabilityAfter = 5.0,
        difficultyAfter = 5.0,
        intervalDaysAfter = 3
    )

    @Test
    fun anEmptyHistoryHasNoRates() {
        val summary = CardHistorySummary.of(emptyList())
        assertEquals(0, summary.reviews)
        assertEquals(0, summary.again)
        assertNull(summary.againRate)
        assertNull(summary.meanSeconds)
    }

    @Test
    fun oneReviewReportsItsOwnGradeAndTime() {
        val summary = CardHistorySummary.of(listOf(history(rating = 1, elapsedSeconds = 8L)))
        assertEquals(1, summary.reviews)
        assertEquals(1.0, summary.againRate!!, 1e-9)
        assertEquals(8.0, summary.meanSeconds!!, 1e-9)
    }

    @Test
    fun aMixedHistoryAveragesOnlyTheTimedReviews() {
        val summary = CardHistorySummary.of(
            listOf(
                history(rating = 1, elapsedSeconds = 10L),
                history(rating = 3, elapsedSeconds = 20L),
                // An unmeasured review still counts toward the Again rate.
                history(rating = 3, elapsedSeconds = null)
            )
        )
        assertEquals(3, summary.reviews)
        assertEquals(1, summary.again)
        assertEquals(1.0 / 3.0, summary.againRate!!, 1e-9)
        assertEquals(15.0, summary.meanSeconds!!, 1e-9)
    }

    @Test
    fun aHistoryWithNoTimedReviewHasNoMeanTime() {
        val summary = CardHistorySummary.of(listOf(history(rating = 3)))
        assertNull(summary.meanSeconds)
        assertEquals(0.0, summary.againRate!!, 1e-9)
    }

    // --- progress deltas (plan §8.1) -----------------------------------------

    @Test
    fun theAgainRateDeltaIsTheNegatedPassRateDelta() {
        val comparison = ProgressComparison(
            currentReviews = 40,
            previousReviews = 25,
            currentPassRate = 0.75,
            previousPassRate = 0.60,
            currentAvgSeconds = 9.0,
            previousAvgSeconds = 12.0,
            currentStudyDays = 6,
            previousStudyDays = 5
        )
        assertEquals(15, comparison.reviewsDelta)
        assertEquals(0.15, comparison.passRateDelta!!, 1e-9)
        assertEquals(-0.15, comparison.againRateDelta!!, 1e-9)
        assertEquals(-3.0, comparison.avgSecondsDelta!!, 1e-9)
        assertEquals(1, comparison.studyDaysDelta)
    }

    @Test
    fun aMissingPreviousRateLeavesBothRateDeltasNull() {
        val comparison = ProgressComparison(
            currentReviews = 0,
            previousReviews = 0,
            currentPassRate = null,
            previousPassRate = null,
            currentAvgSeconds = null,
            previousAvgSeconds = null,
            currentStudyDays = 0,
            previousStudyDays = 0
        )
        assertNull(comparison.passRateDelta)
        assertNull(comparison.againRateDelta)
        assertNull(comparison.avgSecondsDelta)
    }

    // --- memory by paper (plan §8.3) -----------------------------------------

    private fun card(
        paperId: String,
        questionId: String,
        stability: Double = 0.0,
        difficulty: Double = 0.0
    ) = CardSnapshot(
        paperId = paperId,
        state = CardState(
            questionId = questionId,
            stability = stability,
            difficulty = difficulty,
            intervalDays = if (stability > 0.0) stability.toInt() else 0,
            dueAt = now + 86_400_000L,
            state = if (stability > 0.0) CardStateKind.REVIEW else CardStateKind.NEW,
            lapses = 0,
            leech = false
        )
    )

    private fun memory(cards: List<CardSnapshot>, scopePaperId: String? = null) =
        StatsAggregator.buildMemory(
            StatsAggregator.Input(
                reviews = emptyList(),
                cards = cards,
                categoryOfQuestion = emptyMap(),
                paperTitleOf = mapOf("p1" to "Paper One", "p2" to "Paper Two"),
                scheduler = FsrsScheduler(config = SchedulerConfig()),
                config = SchedulerConfig(),
                now = now,
                zoneOffsetMillis = 0L
            ),
            scopePaperId = scopePaperId,
            range = StatsAggregator.Range.ALL
        )

    @Test
    fun unscopedMemoryMeansEachPaperSeparately() {
        val data = memory(
            listOf(
                card("p1", "a", stability = 10.0, difficulty = 5.0),
                card("p1", "b", stability = 20.0, difficulty = 7.0),
                // A new card with no memory state: it counts, its means are null.
                card("p2", "c")
            )
        )
        val byPaper = data.byPaper.associateBy { it.paperId }
        assertEquals(setOf("p1", "p2"), byPaper.keys)

        val one = byPaper.getValue("p1")
        assertEquals("Paper One", one.label)
        assertEquals(2, one.cards)
        assertEquals(15.0, one.meanStability!!, 1e-9)
        assertEquals(6.0, one.meanDifficulty!!, 1e-9)
        assertTrue(one.meanRetrievability!! in 0.0..1.0)

        val two = byPaper.getValue("p2")
        assertEquals(1, two.cards)
        assertNull(two.meanStability)
        assertNull(two.meanDifficulty)
        assertNull(two.meanRetrievability)
    }

    @Test
    fun anUnknownPaperFallsBackToItsLabel() {
        val data = memory(listOf(card("p9", "a", stability = 10.0, difficulty = 5.0)))
        assertEquals("Unknown paper", data.byPaper.single().label)
    }

    @Test
    fun aScopedMemoryHasNoPerPaperRows() {
        val data = memory(
            cards = listOf(card("p1", "a", stability = 10.0, difficulty = 5.0)),
            scopePaperId = "p1"
        )
        assertTrue(data.byPaper.isEmpty())
        // The distributions still read the scoped card.
        assertEquals(1, data.stability.bins.sumOf { it.count })
    }

    @Test
    fun perPaperRowsAreSortedByLabel() {
        val data = memory(
            listOf(
                card("p2", "b", stability = 10.0, difficulty = 5.0),
                card("p1", "a", stability = 10.0, difficulty = 5.0)
            )
        )
        assertEquals(listOf("Paper One", "Paper Two"), data.byPaper.map { it.label })
    }
}
