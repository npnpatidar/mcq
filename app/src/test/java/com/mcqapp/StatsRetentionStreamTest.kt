package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.stats.RetentionData
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
 * The retention tab aggregates while the log streams past it
 * (`StatsStore.loadRetention`), while every other tab builds from the loaded
 * list. The two are fed by one accumulator, but only the streamed path pages
 * and replays on its own, so this runs both over the same rows and asserts the
 * whole payload agrees — scope and range included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsRetentionStreamTest {

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

    /** Two papers, three cards, reviews on nine days, one day reviewed twice. */
    private suspend fun seedLog() {
        db.paperDao().upsert(PaperEntity(id = "p1", title = "Paper One"))
        db.paperDao().upsert(PaperEntity(id = "p2", title = "Paper Two"))
        val tenDaysAgo = todayStart - 10 * day + 2 * hour
        log("q1", rating = 3, reviewedAt = tenDaysAgo)
        log("q1", rating = 1, reviewedAt = tenDaysAgo + hour)
        log("q1", rating = 3, reviewedAt = todayStart - 2 * day + 2 * hour)
        log("q1", rating = 1, reviewedAt = now - 2 * hour)
        // q2's history starts at its first exposure, so its opening row carries
        // no prediction to calibrate against.
        log("q2", rating = 3, reviewedAt = todayStart - 3 * day + 2 * hour, stateBefore = "NEW")
        log("q2", rating = 3, reviewedAt = now - hour)
        log("q3", rating = 1, reviewedAt = todayStart - 9 * day + 2 * hour, paperId = "p2")
        log("q3", rating = 3, reviewedAt = todayStart - day + 2 * hour, paperId = "p2")
    }

    /** The same payload built the way every list-based tab builds it. */
    private suspend fun inMemory(scopePaperId: String?, range: StatsAggregator.Range): RetentionData {
        val library = repository.statsLibrary()
        return StatsAggregator.buildRetention(
            StatsAggregator.Input(
                reviews = repository.statsReviews(),
                cards = library.cards,
                // The fixture has no categories; the scoped labels fall back to
                // the same "unknown group" the streamed build produces.
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
        seedLog()
        val cases = listOf(
            null to StatsAggregator.Range.ALL,
            null to StatsAggregator.Range.D7,
            "p1" to StatsAggregator.Range.ALL,
            "p1" to StatsAggregator.Range.D7
        )
        cases.forEach { (scope, range) ->
            val streamed = repository.statsRetention(scope, range)
            val loaded = inMemory(scope, range)
            assertEquals("scope=$scope range=$range", loaded, streamed)
            // Guard against both sides agreeing on an empty payload.
            assertTrue(streamed.retentionTrend.points.any { it.count > 0 })
            assertTrue(streamed.weekdayRetention.any { it.reviews > 0 })
            assertTrue(streamed.calibration.samples > 0)
            assertTrue(streamed.groupRetention.isNotEmpty())
        }
    }

    @Test
    fun paperScopeKeepsOnlyItsOwnRows() = runBlocking {
        seedLog()
        val everyPaper = repository.statsRetention(null, StatsAggregator.Range.ALL)
        val scoped = repository.statsRetention("p1", StatsAggregator.Range.ALL)
        // Unscoped the breakdown is one row per paper; scoped to p1 it is one
        // per question of that paper — both labelled "—", since the fixture has
        // no categories — and p2's card no longer contributes.
        assertEquals(
            setOf("Paper One", "Paper Two"),
            everyPaper.groupRetention.map { it.label }.toSet()
        )
        assertEquals(2, scoped.groupRetention.size)
        assertEquals(6, scoped.groupRetention.sumOf { it.reviews })
    }
}
