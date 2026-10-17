package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.CardSnapshot
import com.mcqapp.domain.stats.FsrsReplay
import com.mcqapp.domain.stats.StatsAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * §7's pure-JVM scale guard: the statistics screen's streaming overview pass —
 * the FSRS replay and the accumulator, fed one row at a time the way
 * `StatsStore` pages them out of the database — over a heavy library.
 *
 * The rows are generated as they are consumed, so the test never builds the
 * list the streaming path exists to avoid; the only state that accumulates is
 * the per-card one the design promises (one replay state per card, one roll-up
 * per questioned card). The time bound is deliberately loose — it is a
 * tripwire for a pass that has turned quadratic or started holding rows, not a
 * benchmark.
 */
class StatsScaleTest {

    private val day = DayBoundary.DAY_MS
    private val minute = 60_000L
    private val now = LocalDate.of(2026, 6, 15).toEpochDay() * day + 12 * 3_600_000L
    private val todayStart = DayBoundary.startOfDay(now)

    private val cards = 50_000
    private val reviewsPerCard = 4

    @Test
    fun theStreamingOverviewPassStaysLinearOverTwoHundredThousandReviews() {
        val config = SchedulerConfig()
        val scheduler = FsrsScheduler(config = config)
        val stream = FsrsReplay.Stream(scheduler)
        val accumulator = StatsAggregator.OverviewAccumulator(
            scopePaperId = null,
            range = StatsAggregator.Range.ALL,
            now = now,
            cfg = config,
            zone = 0L,
            scheduler = scheduler
        )

        val start = System.nanoTime()
        var rows = 0L
        for (c in 0 until cards) {
            val questionId = "q$c"
            for (i in 0 until reviewsPerCard) {
                rows++
                // Every card's own rows oldest first, all within the last few
                // days: the order the log's ascending ids give the real caller.
                val reviewedAt = todayStart - (reviewsPerCard - i) * day + (c % 1440) * minute
                accumulator.add(
                    stream.accept(
                        FsrsReplay.RawReview(
                            id = rows,
                            paperId = "p1",
                            questionId = questionId,
                            rating = if (i == 1) 1 else 3,
                            reviewedAt = reviewedAt,
                            elapsedSeconds = 7L,
                            stateBefore = if (i == 0) CardStateKind.NEW else CardStateKind.REVIEW,
                            stabilityAfter = 3.0 + i,
                            difficultyAfter = 5.0,
                            intervalDaysAfter = (i + 1) * 2
                        )
                    )
                )
            }
            accumulator.addCard(
                CardSnapshot(
                    paperId = "p1",
                    state = CardState(
                        questionId = questionId,
                        intervalDays = reviewsPerCard * 2,
                        dueAt = todayStart + day,
                        reps = reviewsPerCard,
                        lapses = 1,
                        stability = 6.0,
                        difficulty = 5.0,
                        state = CardStateKind.REVIEW,
                        reviewCount = reviewsPerCard
                    )
                )
            )
        }
        val overview = accumulator.toData()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        // Nothing was dropped on the way through.
        assertEquals(rows, overview.fsrsStatus.reviewCount.toLong())
        assertEquals(rows, overview.kpis.reviews.toLong())
        assertEquals(rows, overview.heatmap.days.sumOf { it.reviews.toLong() })
        assertTrue(overview.weakest.isNotEmpty())
        assertTrue(
            "aggregating $rows reviews over $cards cards took ${elapsedMs}ms",
            elapsedMs < 15_000
        )
    }
}
