package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.QuestionTagEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.local.getCategoryTitlesByIdsChunked
import com.mcqapp.data.local.tagsForQuestionsChunked
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.stats.RetentionData
import com.mcqapp.domain.stats.StatsAggregator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))
        db.paperDao().insertIgnore(PaperEntity(id = "p2", title = "Paper Two"))
        // Categories and tags so the new §6.2/§6.3 slices have data on both
        // build paths: q1 carries two tags, q2 one, q3 none.
        db.categoryDao().upsert(CategoryEntity("c1", "p1", "Alpha"))
        db.categoryDao().upsert(CategoryEntity("c2", "p2", "Beta"))
        db.questionDao().upsertAll(
            listOf(
                QuestionEntity("q1", "c1", text = "", searchText = "", searchTextFolded = "", tags = "tag-a,tag-b", tagsFolded = "tag-a tag-b"),
                QuestionEntity("q2", "c1", text = "", searchText = "", searchTextFolded = "", tags = "tag-a", tagsFolded = "tag-a"),
                QuestionEntity("q3", "c2", text = "", searchText = "", searchTextFolded = "", tags = "", tagsFolded = "")
            )
        )
        db.questionTagDao().insertIgnore(
            listOf(
                QuestionTagEntity("q1", "tag-a", "tag-a"),
                QuestionTagEntity("q1", "tag-b", "tag-b"),
                QuestionTagEntity("q2", "tag-a", "tag-a")
            )
        )
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
        val reviews = repository.statsReviews()
        // The post-pass lookups the streamed build performs (plan §6.2/§6.3).
        val reviewedIds = reviews.map { it.questionId }.distinct()
        return StatsAggregator.buildRetention(
            StatsAggregator.Input(
                reviews = reviews,
                cards = library.cards,
                categoryOfQuestion = db.questionDao().getCategoryTitlesByIdsChunked(reviewedIds),
                paperTitleOf = library.paperTitleOf,
                scheduler = FsrsScheduler(
                    desiredRetention = library.config.desiredRetention,
                    config = library.config
                ),
                config = library.config,
                now = now,
                zoneOffsetMillis = DayBoundary.zoneOffset(now),
                tagsOfQuestion = db.questionTagDao().tagsForQuestionsChunked(reviewedIds)
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
            val streamed = repository.statsRetention(scope, range, now)
            val loaded = inMemory(scope, range)
            // The streamed build additionally carries the phase-2 report and
            // the cut suggestions; the in-memory oracle builds only the v1
            // payload.
            assertEquals(
                "scope=$scope range=$range",
                loaded,
                streamed.copy(
                    calibrationReport = null,
                    cutSuggestion = null,
                    dwellSuggestion = null
                )
            )
            assertTrue(
                "the retention load must carry the calibration report",
                streamed.calibrationReport != null
            )
            // Guard against both sides agreeing on an empty payload.
            assertTrue(streamed.retentionTrend.points.any { it.count > 0 })
            assertTrue(streamed.weekdayRetention.any { it.reviews > 0 })
            assertTrue(streamed.calibration.samples > 0)
            assertTrue(streamed.groupRetention.isNotEmpty())
            assertTrue(streamed.intervalRetention.sumOf { it.reviews } > 0)
            assertTrue(streamed.tagRetention.isNotEmpty())
            // The category slice only exists when no paper is selected.
            assertEquals(scope == null, streamed.categoryRetention.isNotEmpty())
        }
    }

    @Test
    fun paperScopeKeepsOnlyItsOwnRows() = runBlocking {
        seedLog()
        val everyPaper = repository.statsRetention(null, StatsAggregator.Range.ALL, now)
        val scoped = repository.statsRetention("p1", StatsAggregator.Range.ALL, now)
        // Unscoped the breakdown is one row per paper; scoped to p1 it is one
        // row per category of that paper — its questions are both in Alpha —
        // and p2's card no longer contributes.
        assertEquals(
            setOf("Paper One", "Paper Two"),
            everyPaper.groupRetention.map { it.label }.toSet()
        )
        assertTrue(everyPaper.groupIsPaper)
        assertEquals(listOf("Alpha"), scoped.groupRetention.map { it.label })
        assertEquals(6, scoped.groupRetention.sumOf { it.reviews })
        assertFalse(scoped.groupIsPaper)
    }

    @Test
    fun theScopedCategoryMapPagesOnlyTheSelectedPaper() = runBlocking {
        // The Retention tab's question→category-title map used to be built by
        // reading every question in the bank, even with one paper selected.
        // These two keyset pages are the scoped replacement, so they must never
        // return another paper's rows.
        db.paperDao().insertIgnore(PaperEntity("p1", "Paper One"))
        db.paperDao().insertIgnore(PaperEntity("p2", "Paper Two"))
        db.categoryDao().upsert(CategoryEntity("c1", "p1", "Alpha"))
        db.categoryDao().upsert(CategoryEntity("c2", "p2", "Beta"))
        db.questionDao().upsertAll(
            listOf(
                QuestionEntity("a1", "c1", text = "", searchText = "", searchTextFolded = "", tags = "", tagsFolded = ""),
                QuestionEntity("a2", "c1", text = "", searchText = "", searchTextFolded = "", tags = "", tagsFolded = ""),
                QuestionEntity("b1", "c2", text = "", searchText = "", searchTextFolded = "", tags = "", tagsFolded = "")
            )
        )

        val first = db.questionDao().pageIdCategoriesFirst("p1", 1)
        assertEquals(listOf("a1"), first.map { it.id })
        val rest = db.questionDao().pageIdCategoriesAfter("p1", first.last().id, 10)
        assertEquals(listOf("a2"), rest.map { it.id })
        assertTrue(
            "p2 must not leak into p1's page",
            (first + rest).all { it.categoryId == "c1" }
        )
    }
}
