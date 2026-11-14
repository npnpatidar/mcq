package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.GradingVersionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.QuestionTagEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.local.getCategoryTitlesByIdsChunked
import com.mcqapp.data.local.tagsForQuestionsChunked
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.dataStore
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.stats.Calibration
import com.mcqapp.domain.stats.RetentionData
import com.mcqapp.domain.stats.StatsAggregator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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

    @get:Rule
    val settingsReset = SettingsResetRule()

    private val day = DayBoundary.DAY_MS
    private val hour = 3_600_000L
    private val now = System.currentTimeMillis()
    private val todayStart = DayBoundary.startOfDay(now)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The DataStore is process-wide: start every test from a blank slate so
        // a grading pair or rule written by a neighbour cannot steer this one.
        runBlocking { context.dataStore.edit { it.clear() } }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
    }

    @After
    fun tearDown() {
        db.close()
        // This class writes real preference keys (the v26 upgrade fixture), so
        // it must restore the process-wide store like GradingRulesStoreTest.
        runBlocking {
            ApplicationProvider.getApplicationContext<Context>()
                .dataStore.edit { it.clear() }
        }
    }

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

    /**
     * B1 regression: Statistics can be the first screen a v27 install reads.
     *
     * This models the real v26 → v27 migration, not an empty history: the
     * migration copied `ladder_versions` into `grading_versions`, so the old
     * version row (id 1) is already there, `ladder_version` still names it,
     * and the stored pair was tuned for the retired reading allowance. The
     * seed must mint id 2 with a NEW `effectiveFrom`, not reuse id 1's old
     * epoch. Reviews carry version 1 and sit after that old epoch but before
     * the seed, so a regression that stamps the new row with the old time
     * would count them (`pairs > 0`).
     */
    @Test
    fun openingStatisticsOnAMigratedV27InstallSeedsANewEpoch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oldEffectiveFrom = todayStart - 30 * day
        db.gradingRulesDao().insertVersion(
            GradingVersionEntity(
                id = 1,
                easySeconds = 8L,
                slowSeconds = 30L,
                effectiveFrom = oldEffectiveFrom,
                reason = "prior",
                correctnessOnly = false
            )
        )
        // The v26 state a v27 install upgrades from: the old pair and its
        // version id in DataStore, the retired rule's gate absent.
        context.dataStore.edit { prefs ->
            prefs[doublePreferencesKey("anki_fast_seconds")] = 8.0
            prefs[doublePreferencesKey("anki_slow_seconds")] = 30.0
            prefs[intPreferencesKey("ladder_version")] = 1
            // No grading_rules_version: this store was last written by v26.
        }
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))
        // One usable label→outcome pair, both rows graded under the v26 rule.
        val labelAt = todayStart - 10 * day + 2 * hour
        val lastReviewAt = labelAt + hour
        db.reviewLogDao().insertAll(
            listOf(
                ReviewLogEntity(
                    paperId = "p1",
                    questionId = "q1",
                    rating = 3,
                    reviewedAt = labelAt,
                    stateBefore = "REVIEW",
                    intervalDaysAfter = 8,
                    gradeSource = "AUTO",
                    gradingVersion = 1,
                    predictedRecall = 0.9,
                    fsrsFitVersion = 0,
                    wasGuess = false,
                    wasCorrect = true,
                    wasSkipped = false
                ),
                ReviewLogEntity(
                    paperId = "p1",
                    questionId = "q1",
                    rating = 3,
                    reviewedAt = lastReviewAt,
                    stateBefore = "REVIEW",
                    intervalDaysAfter = 8,
                    gradeSource = "AUTO",
                    gradingVersion = 1,
                    predictedRecall = 0.9,
                    fsrsFitVersion = 0,
                    wasGuess = false,
                    wasCorrect = true,
                    wasSkipped = false
                )
            )
        )
        assertEquals("the fixture must be a usable pair", 1, calibrationPairs())

        val streamed = repository.statsRetention(null, StatsAggregator.Range.ALL, now)
        val report = streamed.calibrationReport!!
        val versions = db.gradingRulesDao().allVersions()
        assertEquals(
            "the migrated row and the seed are the only versions",
            2,
            versions.size
        )
        val seeded = versions.single { it.id != 1L }
        assertEquals("the seed continues the migrated numbering", 2L, seeded.id)
        assertEquals("prior", seeded.reason)
        assertEquals(6L, seeded.easySeconds)
        assertEquals(60L, seeded.slowSeconds)
        assertTrue(
            "the seed must open a new epoch, after every v26 review " +
                "(seed=${seeded.effectiveFrom}, lastReview=$lastReviewAt)",
            seeded.effectiveFrom > lastReviewAt
        )
        assertEquals(
            "the report's epoch is the seeded row's timestamp",
            seeded.effectiveFrom,
            report.epochStart
        )
        assertEquals(
            "the old pair's reviews must be excluded by the epoch, not a row filter",
            0,
            report.pairs
        )
        assertEquals(
            "the old row keeps the pair that graded its reviews",
            8L,
            versions.single { it.id == 1L }.easySeconds
        )
        assertEquals(
            "the config carries the seeded version, not a re-read 0",
            2,
            repository.schedulerConfigNow().gradingVersion
        )
        assertEquals(
            "StatsStore reads the same seeded version",
            2,
            repository.statsLibrary().config.gradingVersion
        )
    }

    /** The same pair through [Calibration.report] with no epoch: proves it is usable. */
    private suspend fun calibrationPairs(): Int = Calibration.report(
        reviews = db.reviewLogDao().getAll().map { row ->
            Calibration.Row(
                id = row.id,
                paperId = row.paperId,
                questionId = row.questionId,
                rating = row.rating,
                reviewedAt = row.reviewedAt,
                gradeSource = row.gradeSource,
                proposedRating = row.proposedRating,
                gradingVersion = row.gradingVersion,
                fsrsFitVersion = row.fsrsFitVersion,
                predictedRecall = row.predictedRecall,
                stateBefore = row.stateBefore?.let { CardStateKind.valueOf(it) },
                wasGuess = row.wasGuess,
                wasCorrect = row.wasCorrect,
                wasSkipped = row.wasSkipped,
                intervalDaysAfter = row.intervalDaysAfter
            )
        },
        now = now,
        epochStart = 0L
    ).pairs

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
