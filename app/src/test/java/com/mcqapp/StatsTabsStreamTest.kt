package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.stats.StatsAggregator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The workload, memory and cards tabs now stream their rows into an accumulator
 * (`StatsStore.loadWorkload` / `loadMemory` / `loadCards`) instead of reading the
 * whole log and the whole card library as lists. The accumulators are the same
 * objects the in-memory build uses, so this runs both paths over the same rows
 * and asserts each payload agrees — which also pins the one ordering the cards
 * tab depends on (the cards before the log, so a leech is recognised when its
 * history streams past).
 *
 * The clock-dependent figures are only checked for parity (the pure-JVM
 * aggregator test pins them deterministically); the shape of the payload is
 * asserted outright so the test cannot pass on an empty build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsTabsStreamTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    private val day = DayBoundary.DAY_MS
    private val hour = 3_600_000L
    private val now = System.currentTimeMillis()
    private val todayStart = DayBoundary.startOfDay(now)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun log(
        questionId: String,
        rating: Int,
        reviewedAt: Long,
        paperId: String = "p1",
        stateBefore: String = "REVIEW",
        elapsedSeconds: Long? = 7L,
        stabilityAfter: Double? = 8.0,
        intervalDaysAfter: Int = 8
    ) {
        db.reviewLogDao().insert(
            ReviewLogEntity(
                paperId = paperId,
                questionId = questionId,
                rating = rating,
                reviewedAt = reviewedAt,
                elapsedSeconds = elapsedSeconds,
                stateBefore = stateBefore,
                stabilityAfter = stabilityAfter,
                difficultyAfter = 5.0,
                intervalDaysAfter = intervalDaysAfter
            )
        )
    }

    private suspend fun card(
        questionId: String,
        paperId: String = "p1",
        state: CardStateKind = CardStateKind.REVIEW,
        intervalDays: Int = 0,
        dueAt: Long = 0L,
        lapses: Int = 0,
        leech: Boolean = false,
        stability: Double = 0.0,
        difficulty: Double = 0.0
    ) {
        db.cardStateDao().upsert(
            CardStateEntity(
                paperId = paperId,
                questionId = questionId,
                ease = 2.5,
                intervalDays = intervalDays,
                dueAt = dueAt,
                reps = lapses + 1,
                lapses = lapses,
                leech = leech,
                lastReviewedAt = dueAt,
                contentHash = "h-$questionId",
                stability = stability,
                difficulty = difficulty,
                cardState = state
            )
        )
    }

    /**
     * Two papers, seven cards across every state, an overdue backlog on both
     * sides and a history that spans learning, review, relearning and a seeded
     * (unknown-kind) row — enough that every chart has something to disagree on.
     */
    private suspend fun seed() {
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))
        db.paperDao().insertIgnore(PaperEntity(id = "p2", title = "Paper Two"))

        card(
            "q1", intervalDays = 30, dueAt = now + 2 * hour,
            stability = 20.0, difficulty = 6.5
        )
        card(
            "q2", state = CardStateKind.LEARNING, dueAt = todayStart + 3 * hour,
            stability = 2.0, difficulty = 4.2
        )
        card("q3", state = CardStateKind.NEW)
        card(
            "q4", intervalDays = 5, dueAt = todayStart - 3 * day,
            lapses = 3, leech = true, stability = 5.0, difficulty = 3.0
        )
        card(
            "q5", paperId = "p2", intervalDays = 100, dueAt = todayStart - 20 * day,
            stability = 50.0, difficulty = 8.0
        )
        card(
            "q6", paperId = "p2", state = CardStateKind.RELEARNING,
            dueAt = todayStart + hour, stability = 1.0, difficulty = 2.0
        )
        card("q7", paperId = "p2", intervalDays = 0, dueAt = 0L)

        // q1: three timed reviews over a week; the first is untimed so the
        // response-time and volume charts genuinely differ.
        log("q1", rating = 3, reviewedAt = todayStart - 8 * day, elapsedSeconds = null)
        log("q1", rating = 1, reviewedAt = todayStart - 2 * day, elapsedSeconds = 4L)
        log("q1", rating = 3, reviewedAt = now - 2 * hour, elapsedSeconds = 40L)
        // q4: four timed reviews, all failing but the third — the leech roll-up.
        log("q4", rating = 1, reviewedAt = todayStart - 9 * day, elapsedSeconds = 3L)
        log("q4", rating = 1, reviewedAt = todayStart - 6 * day, elapsedSeconds = 6L)
        log("q4", rating = 3, reviewedAt = todayStart - 3 * day, elapsedSeconds = 2L)
        log("q4", rating = 1, reviewedAt = now - hour, elapsedSeconds = 9L)
        // q5: a long interval, one timed review.
        log(
            "q5", rating = 3, reviewedAt = todayStart - 20 * day, elapsedSeconds = 12L,
            paperId = "p2", stabilityAfter = 30.0
        )
        // q2 and q6: learning steps, no whole-day interval to qualify with.
        log(
            "q2", rating = 3, reviewedAt = todayStart - day + hour, elapsedSeconds = 2L,
            stateBefore = "NEW", intervalDaysAfter = 0
        )
        log(
            "q6", rating = 2, reviewedAt = todayStart - 4 * day, elapsedSeconds = 5L,
            paperId = "p2", stateBefore = "RELEARNING", intervalDaysAfter = 0
        )
        // q3: a seeded row whose pre-state is unknown, so its kind is not
        // knowable; it must land in the charts' own "unknown" slice.
        log(
            "q3", rating = 3, reviewedAt = todayStart - 5 * day, elapsedSeconds = 8L,
            stateBefore = "SEEDED", intervalDaysAfter = 3
        )
    }

    /** The same inputs the streamed build pages past, handed over as lists. */
    private suspend fun input(): StatsAggregator.Input {
        val library = repository.statsLibrary()
        return StatsAggregator.Input(
            reviews = repository.statsReviews(),
            cards = library.cards,
            categoryOfQuestion = emptyMap(),
            paperTitleOf = library.paperTitleOf,
            scheduler = FsrsScheduler(
                desiredRetention = library.config.desiredRetention,
                config = library.config
            ),
            config = library.config,
            now = now,
            zoneOffsetMillis = DayBoundary.zoneOffset(now)
        )
    }

    private val cases = listOf(
        null to StatsAggregator.Range.ALL,
        null to StatsAggregator.Range.D7,
        "p1" to StatsAggregator.Range.ALL,
        "p1" to StatsAggregator.Range.D7,
        "p2" to StatsAggregator.Range.D30
    )

    @Test
    fun streamedWorkloadMatchesTheInMemoryBuild() = runBlocking {
        seed()
        val loaded = input()
        var sawSlowest = false
        cases.forEach { (scope, range) ->
            val streamed = repository.statsWorkload(scope, range, now)
            val inMemory = StatsAggregator.buildWorkload(loaded, scope, range)
            assertEquals("scope=$scope range=$range", inMemory, streamed)
            // Guard against both sides agreeing on an empty build.
            assertEquals(30, streamed.forecast.days.size)
            assertTrue("forecast overdue scope=$scope", streamed.forecast.overdue > 0)
            sawSlowest = sawSlowest || streamed.responseTime.slowest.isNotEmpty()
        }
        assertTrue("no case reached the slowest list", sawSlowest)
    }

    @Test
    fun streamedMemoryMatchesTheInMemoryBuild() = runBlocking {
        seed()
        val loaded = input()
        var sawGrowth = false
        cases.forEach { (scope, range) ->
            val streamed = repository.statsMemory(scope, range, now)
            val inMemory = StatsAggregator.buildMemory(loaded, scope, range)
            assertEquals("scope=$scope range=$range", inMemory, streamed)
            sawGrowth = sawGrowth || streamed.memoryGrowth.byReviewNumber.any { it.count > 0 }
        }
        // The unscoped view sees every card with a stability, so its histogram
        // has a real median and mean to compare.
        val everyPaper = repository.statsMemory(null, StatsAggregator.Range.ALL, now)
        assertTrue(everyPaper.stability.bins.sumOf { it.count } > 0)
        assertEquals(true, everyPaper.stability.median != null)
        assertTrue("no case reached the growth curves", sawGrowth)
    }

    @Test
    fun streamedCardsMatchesTheInMemoryBuild() = runBlocking {
        seed()
        val loaded = input()
        cases.forEach { (scope, range) ->
            val streamed = repository.statsCards(scope, range, now)
            val inMemory = StatsAggregator.buildCards(loaded, scope, range)
            assertEquals("scope=$scope range=$range", inMemory, streamed)
        }
        val everyPaper = repository.statsCards(null, StatsAggregator.Range.ALL, now)
        assertEquals(7, everyPaper.cardCounts.total)
        assertEquals(1, everyPaper.cardCounts.leeches)
        assertEquals(listOf("Paper One", "Paper Two"), everyPaper.paperCounts.map { it.label }.sorted())
        // q4 is the one flagged card, and its four reviews are in the log.
        val leech = everyPaper.leeches.single()
        assertEquals("q4", leech.questionId)
        assertEquals(4, leech.reviews)
        assertEquals(3, leech.lapses)
        assertTrue(everyPaper.overdueAge.overdue > 0)
    }
}
