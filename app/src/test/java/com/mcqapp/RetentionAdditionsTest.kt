package com.mcqapp

import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.IntervalBand
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.RetentionData
import com.mcqapp.domain.stats.StatsAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The retention slices added in plan §6: interval, tag and all-papers category. */
class RetentionAdditionsTest {

    private val day = DayBoundary.DAY_MS
    private val hour = 3_600_000L

    /** 15 Jun 2026, 12:00 UTC — the study day started at 04:00 that morning. */
    private val now = LocalDate.of(2026, 6, 15).toEpochDay() * day + 12 * hour
    private val todayStart = DayBoundary.startOfDay(now)

    private fun review(
        questionId: String,
        rating: Int,
        reviewedAt: Long = todayStart + hour,
        paperId: String = "p1",
        intervalBeforeDays: Int? = null
    ) = ReplayReview(
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = 10L,
        stateBefore = CardStateKind.REVIEW,
        stabilityAfter = 5.0,
        difficultyAfter = 5.0,
        intervalDaysAfter = 3,
        predictedRetention = null,
        intervalBeforeDays = intervalBeforeDays
    )

    private fun retention(
        reviews: List<ReplayReview>,
        scopePaperId: String? = null,
        range: StatsAggregator.Range = StatsAggregator.Range.ALL,
        tags: Map<String, List<String>> = emptyMap(),
        categories: Map<String, String> = emptyMap()
    ): RetentionData {
        val config = SchedulerConfig()
        return StatsAggregator.buildRetention(
            StatsAggregator.Input(
                reviews = reviews,
                cards = emptyList(),
                categoryOfQuestion = categories,
                paperTitleOf = mapOf("p1" to "Paper One", "p2" to "Paper Two"),
                scheduler = FsrsScheduler(config = config),
                config = config,
                now = now,
                zoneOffsetMillis = 0L,
                tagsOfQuestion = tags
            ),
            scopePaperId,
            range
        )
    }

    @Test
    fun emptyInputStillReportsEveryIntervalBand() {
        val data = retention(emptyList())
        assertEquals(
            IntervalBand.entries,
            data.intervalRetention.map { it.band }
        )
        assertTrue(data.intervalRetention.all { it.reviews == 0 })
        assertTrue(data.intervalRetention.all { it.retention == null })
        assertTrue(data.tagRetention.isEmpty())
        assertTrue(data.categoryRetention.isEmpty())
    }

    @Test
    fun intervalBandsSplitOnTheCarriedWholeDayInterval() {
        // One review per boundary, plus a same-day step (0) that joins 1–7d.
        val intervals = listOf<Int?>(null, 0, 1, 7, 8, 21, 22, 60, 61, 180, 181)
        val reviews = intervals.mapIndexed { index, days ->
            review("q$index", rating = 3, intervalBeforeDays = days)
        }
        val data = retention(reviews)

        val byBand = data.intervalRetention.associateBy { it.band }
        assertEquals(1, byBand.getValue(IntervalBand.FIRST).reviews)
        assertEquals(3, byBand.getValue(IntervalBand.D1_7).reviews)
        assertEquals(2, byBand.getValue(IntervalBand.D8_21).reviews)
        assertEquals(2, byBand.getValue(IntervalBand.D22_60).reviews)
        assertEquals(2, byBand.getValue(IntervalBand.D61_180).reviews)
        assertEquals(1, byBand.getValue(IntervalBand.D181_PLUS).reviews)
        // All were passes, so every non-empty band reads 100%.
        data.intervalRetention.filter { it.reviews > 0 }.forEach {
            assertEquals(1.0, it.retention!!, 1e-9)
        }
    }

    @Test
    fun intervalBandsCountPassesAndFailures() {
        val reviews = listOf(
            review("q1", rating = 3, intervalBeforeDays = null),
            review("q2", rating = 1, intervalBeforeDays = 5),
            review("q3", rating = 3, intervalBeforeDays = 5)
        )
        val byBand = retention(reviews).intervalRetention.associateBy { it.band }
        assertEquals(1, byBand.getValue(IntervalBand.FIRST).passes)
        assertEquals(2, byBand.getValue(IntervalBand.D1_7).reviews)
        assertEquals(1, byBand.getValue(IntervalBand.D1_7).passes)
        assertEquals(0.5, byBand.getValue(IntervalBand.D1_7).retention!!, 1e-9)
    }

    @Test
    fun aMultiTagQuestionCountsInEveryTag() {
        val reviews = listOf(
            review("q1", rating = 3),
            review("q1", rating = 1),
            review("q2", rating = 3),
            review("q3", rating = 3)
        )
        val tags = mapOf(
            "q1" to listOf("tag-a", "tag-b"),
            "q2" to listOf("tag-a")
            // q3 carries no tag, so it must not invent one.
        )
        val byTag = retention(reviews, tags = tags).tagRetention.associateBy { it.label }

        assertEquals(setOf("tag-a", "tag-b"), byTag.keys)
        // tag-a: q1's two reviews + q2's one.
        assertEquals(3, byTag.getValue("tag-a").reviews)
        assertEquals(2, byTag.getValue("tag-a").passes)
        // tag-b: q1's two reviews only.
        assertEquals(2, byTag.getValue("tag-b").reviews)
        assertEquals(1, byTag.getValue("tag-b").passes)
        // Weakest first.
        assertEquals(listOf("tag-b", "tag-a"), byTag.keys.toList())
    }

    @Test
    fun unscopedCategorySliceUsesTheReviewedQuestions() {
        val reviews = listOf(
            review("q1", rating = 3),
            review("q2", rating = 1),
            review("q3", rating = 3)
        )
        val categories = mapOf("q1" to "Alpha", "q2" to "Beta", "q3" to "Alpha")
        val data = retention(reviews, categories = categories)

        // The paper breakdown stays as it was; the category slice is new.
        assertEquals(listOf("Paper One"), data.groupRetention.map { it.label })
        val byCategory = data.categoryRetention.associateBy { it.label }
        assertEquals(setOf("Alpha", "Beta"), byCategory.keys)
        assertEquals(2, byCategory.getValue("Alpha").reviews)
        assertEquals(2, byCategory.getValue("Alpha").passes)
        assertEquals(1, byCategory.getValue("Beta").reviews)
        assertEquals(0, byCategory.getValue("Beta").passes)
        assertEquals(listOf("Beta", "Alpha"), byCategory.keys.toList())

        // Scoped, the existing breakdown is already per category.
        val scoped = retention(reviews, scopePaperId = "p1", categories = categories)
        assertTrue(scoped.categoryRetention.isEmpty())
        assertEquals(setOf("Alpha", "Beta"), scoped.groupRetention.map { it.label }.toSet())
    }

    @Test
    fun emptyMapsLeaveTheNewSlicesEmptyButKeepTheOldOnes() {
        val data = retention(listOf(review("q1", rating = 3)))
        assertNull(data.tagRetention.firstOrNull())
        assertNull(data.categoryRetention.firstOrNull())
        assertTrue(data.groupRetention.isNotEmpty())
    }
}
