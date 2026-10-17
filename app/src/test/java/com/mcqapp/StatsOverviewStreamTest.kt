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
import com.mcqapp.domain.stats.OverviewData
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
 * The overview tab is the one the page opens on, and it now streams the log and
 * the cards into one accumulator (`StatsStore.loadOverview`) instead of reading
 * either as a list. The list-based path is still exercised by the other tabs, so
 * this runs both over the same rows and asserts the whole payload agrees — the
 * card counters included, which the retention parity test cannot reach.
 *
 * The card schedule is real here: due counts, the interval average, the
 * knowledge estimate and the leech set all come out of the streamed card pass.
 * Their clock-dependent figures are only checked for parity (the pure-JVM
 * aggregator test pins them deterministically); the clock-independent ones are
 * asserted outright so the test cannot pass on an empty payload.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsOverviewStreamTest {

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
        stabilityAfter: Double = 8.0
    ) {
        db.reviewLogDao().insert(
            ReviewLogEntity(
                paperId = paperId,
                questionId = questionId,
                rating = rating,
                reviewedAt = reviewedAt,
                elapsedSeconds = 7L,
                stateBefore = stateBefore,
                stabilityAfter = stabilityAfter,
                difficultyAfter = 5.0,
                intervalDaysAfter = 8
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
        stability: Double = 0.0
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
                cardState = state
            )
        )
    }

    /**
     * Two papers, four cards and two cards' worth of history. q1 is the only
     * card weak enough to rank; q2 is the only leech; two of the four cards
     * carry a whole-day interval, so the average is exactly 20 days.
     */
    private suspend fun seed() {
        db.paperDao().upsert(PaperEntity(id = "p1", title = "Paper One"))
        db.paperDao().upsert(PaperEntity(id = "p2", title = "Paper Two"))

        card("q1", intervalDays = 30, dueAt = now + 2 * hour, stability = 20.0)
        card("q2", intervalDays = 10, dueAt = todayStart - 2 * day, lapses = 9, leech = true, stability = 5.0)
        card("q3", state = CardStateKind.NEW)
        card("q4", paperId = "p2", dueAt = todayStart - 5 * day, lapses = 1)

        log("q1", rating = 3, reviewedAt = todayStart - 10 * day + 2 * hour)
        log("q1", rating = 1, reviewedAt = todayStart - 2 * day + 2 * hour)
        log("q1", rating = 1, reviewedAt = now - 2 * hour)
        log("q2", rating = 3, reviewedAt = todayStart - 3 * day + 2 * hour, stateBefore = "NEW")
        log("q2", rating = 3, reviewedAt = now - hour)
        log("q4", rating = 1, reviewedAt = todayStart - 9 * day + 2 * hour, paperId = "p2", stateBefore = "NEW")
        log("q4", rating = 3, reviewedAt = todayStart - day + 2 * hour, paperId = "p2")
    }

    /** The same payload built the way the list-based tabs still build it. */
    private suspend fun inMemory(scopePaperId: String?, range: StatsAggregator.Range): OverviewData {
        val library = repository.statsLibrary()
        return StatsAggregator.buildOverview(
            StatsAggregator.Input(
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
            ),
            scopePaperId,
            range
        )
    }

    @Test
    fun streamedPayloadMatchesTheInMemoryBuild() = runBlocking {
        seed()
        val cases = listOf(
            null to StatsAggregator.Range.ALL,
            null to StatsAggregator.Range.D7,
            "p1" to StatsAggregator.Range.ALL,
            "p1" to StatsAggregator.Range.D7,
            "p2" to StatsAggregator.Range.D30
        )
        cases.forEach { (scope, range) ->
            val streamed = repository.statsOverview(scope, range, now)
            val loaded = inMemory(scope, range)
            assertEquals("scope=$scope range=$range", loaded, streamed)
            // Guard against both sides agreeing on an empty payload.
            assertTrue(streamed.retentionTable.last().allReviews > 0)
            assertTrue(streamed.heatmap.days.any { it.reviews > 0 })
        }
    }

    @Test
    fun cardCountersComeOutOfTheStreamedCardPass() = runBlocking {
        seed()
        val everyPaper = repository.statsOverview(null, StatsAggregator.Range.ALL, now)
        assertEquals(1, everyPaper.kpis.leeches)
        assertEquals(20.0, everyPaper.kpis.avgIntervalDays!!, 1e-9)
        assertEquals(listOf("q1"), everyPaper.weakest.map { it.questionId })
        assertEquals(2, everyPaper.weakest.single().lapses)

        // Scoped to p2 only q4's card is left, and it has no interval to average.
        val scoped = repository.statsOverview("p2", StatsAggregator.Range.ALL, now)
        assertEquals(0, scoped.kpis.leeches)
        assertEquals(null, scoped.kpis.avgIntervalDays)
        assertTrue(scoped.weakest.isEmpty())
    }
}
