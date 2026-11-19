package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.GradingVersionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.PassageEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.QuestionTagEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.local.deleteByQuestionsChunked
import com.mcqapp.data.local.getCategoryTitlesByIdsChunked
import com.mcqapp.data.local.tagsForQuestionsChunked
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.data.repository.dataStore
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.Passage
import com.mcqapp.domain.stats.Calibration
import com.mcqapp.domain.stats.RetentionData
import com.mcqapp.domain.stats.StatsAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule

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

    private val watchdog = HangWatchdogRule(seconds = 120)
    private val timeout = TimeoutRule.seconds(180)
    private val settingsReset = SettingsResetRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(watchdog)
        .around(timeout)
        .around(settingsReset)

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    private val day = DayBoundary.DAY_MS
    private val hour = 3_600_000L
    private val now = System.currentTimeMillis()
    private val todayStart = DayBoundary.startOfDay(now)

    /** Database/DataStore work must not block Robolectric's paused main thread. */
    private fun <T> dbBlocking(block: suspend () -> T): T = runOffMainWhilePumping(
        condition = "dbBlocking in StatsRetentionStreamTest"
    ) {
        withContext(Dispatchers.IO) { block() }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The DataStore is process-wide: start every test from a blank slate so
        // a grading pair or rule written by a neighbour cannot steer this one.
        // No withTimeout around the edit: cancelling a DataStore write mid-flight
        // poisons the process-wide store (see SettingsResetRule). The pump's own
        // deadline is the bound.
        runOffMainWhilePumping(
            condition = "StatsRetentionStreamTest.setUp DataStore clear"
        ) {
            withContext(Dispatchers.IO) { context.dataStore.edit { it.clear() } }
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
        dbBlocking { db.ensureOpen() }
    }

    @After
    fun tearDown() {
        db.close()
        // This class writes real preference keys (the old-rule upgrade fixture),
        // so it must restore the process-wide store like GradingRulesStoreTest.
        // No withTimeout around the edit: cancelling a DataStore write mid-flight
        // poisons the process-wide store (see SettingsResetRule).
        runOffMainWhilePumping(
            condition = "StatsRetentionStreamTest.tearDown DataStore clear"
        ) {
            withContext(Dispatchers.IO) {
                ApplicationProvider.getApplicationContext<Context>()
                    .dataStore.edit { it.clear() }
            }
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
                zone = DayBoundary.defaultZone(),
                tagsOfQuestion = db.questionTagDao().tagsForQuestionsChunked(reviewedIds)
            ),
            scopePaperId,
            range
        )
    }

    @Test
    fun streamedPayloadMatchesTheInMemoryBuild() = dbBlocking {
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
            // The suggestions are exactly the fields the oracle above has to
            // drop, so assert them here instead of trusting the copy: one is
            // populated, and it is the one the rule in force can produce.
            if (streamed.timeBasedGrading) {
                assertNotNull(
                    "timed grading must offer the contrast-based pair",
                    streamed.cutSuggestion
                )
                // The answer-time pair may also be present here, but only as
                // the "you never applied this" hint, so it must be a real
                // move away from the pair in force.
                streamed.dwellSuggestion?.let {
                    assertTrue(
                        "a timed dwell hint must name a different pair",
                        it.changes
                    )
                }
            } else {
                assertNotNull(
                    "correctness-only grading must offer the answer-time pair",
                    streamed.dwellSuggestion
                )
                assertNull(
                    "no Easy/Hard labels exist, so the contrast has nothing to say",
                    streamed.cutSuggestion
                )
            }
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
     * F3: the correctness-only branch of the assertion above has to execute in
     * CI. The fixture there is always timed — the papers are logged before the
     * first config read, so the install is an upgrade and stays on the clock —
     * which left its `else` half unreachable. This switches the rule the way
     * the Settings screen does and asserts the payload that rule produces,
     * plus the ordinary aggregation that must survive the switch.
     */
    @Test
    fun correctnessOnlyPayloadCarriesTheAnswerTimePair() = dbBlocking {
        seedLog()
        val written = repository.updateSchedulerConfig {
            it.copy(correctnessOnlyGrading = true)
        }
        assertTrue(
            "the fixture must actually be on correctness-only grading",
            written.correctnessOnlyGrading
        )

        val streamed = repository.statsRetention(null, StatsAggregator.Range.ALL, now)
        assertFalse("the rule in force must be reported", streamed.timeBasedGrading)
        assertNotNull(
            "correctness-only grading must offer the answer-time pair",
            streamed.dwellSuggestion
        )
        assertNull(
            "no Easy/Hard labels exist, so the contrast has nothing to say",
            streamed.cutSuggestion
        )
        // The same payload shape as the timed fixture: the rule changes what a
        // grade means, not what the tab aggregates.
        assertTrue(streamed.retentionTrend.points.any { it.count > 0 })
        assertTrue(streamed.weekdayRetention.any { it.reviews > 0 })
        assertTrue(streamed.calibration.samples > 0)
        assertNotNull("the calibration report is still built", streamed.calibrationReport)
    }

    /**
     * F1: a full restore must not restart the calibration epoch. The restored
     * `grading_versions` row carries the `effectiveFrom` the backup was taken
     * under, and the reviews it graded sit after it, so keeping that id keeps
     * them countable. Before the fix the restore minted a fresh row stamped
     * "now", which dropped every restored label from the report.
     */
    @Test
    fun aRestoreKeepsTheCalibrationEvidenceItBrought() = dbBlocking {
        val restoredStart = todayStart - 20 * day
        db.gradingRulesDao().insertVersion(
            GradingVersionEntity(
                id = 5,
                easySeconds = 6L,
                slowSeconds = 60L,
                effectiveFrom = restoredStart,
                reason = "manual",
                correctnessOnly = false
            )
        )
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))
        // One usable label → outcome pair, both graded under version 5 and both
        // inside the epoch the backup's row names.
        val labelAt = todayStart - 10 * day + 2 * hour
        val graded = ReviewLogEntity(
            paperId = "p1",
            questionId = "q1",
            rating = 3,
            reviewedAt = labelAt,
            stateBefore = "REVIEW",
            intervalDaysAfter = 8,
            gradeSource = "AUTO",
            gradingVersion = 5,
            predictedRecall = 0.9,
            fsrsFitVersion = 0,
            wasGuess = false,
            wasCorrect = true,
            wasSkipped = false
        )
        db.reviewLogDao().insertAll(
            listOf(graded, graded.copy(reviewedAt = labelAt + hour))
        )
        assertEquals("the fixture must be a usable pair", 1, calibrationPairs())

        repository.settingsRestore(v27Settings(version = 5))

        val streamed = repository.statsRetention(null, StatsAggregator.Range.ALL, now)
        val report = streamed.calibrationReport!!
        assertEquals(
            "the restored id and its epoch win over a freshly minted row",
            5,
            repository.schedulerConfigNow().gradingVersion
        )
        assertEquals("the epoch must be the restored row's", restoredStart, report.epochStart)
        assertTrue(
            "reviews restored inside the epoch must stay in the evidence " +
                "(pairs=${report.pairs})",
            report.pairs > 0
        )
        assertEquals(
            "a restore that resolves writes no version row",
            1,
            db.gradingRulesDao().allVersions().size
        )
    }

    /**
     * F1, the settings-only case: the restored `ladder_version` names a local
     * row that graded a *different* pair (4/20) while the backup's settings say
     * 6/60. `Calibration.report` filters on `reviewedAt >= epochStart` alone, so
     * reusing that row's start would count its 4/20 reviews as evidence for the
     * restored cuts. The epoch must restart after them instead.
     */
    @Test
    fun aRestoreDoesNotCountReviewsGradedUnderAnotherPair() = dbBlocking {
        val otherPairStart = todayStart - 30 * day
        db.gradingRulesDao().insertVersion(
            GradingVersionEntity(
                id = 5,
                easySeconds = 4L,
                slowSeconds = 20L,
                effectiveFrom = otherPairStart,
                reason = "manual"
            )
        )
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))
        val labelAt = todayStart - 10 * day + 2 * hour
        val graded = ReviewLogEntity(
            paperId = "p1",
            questionId = "q1",
            rating = 3,
            reviewedAt = labelAt,
            stateBefore = "REVIEW",
            intervalDaysAfter = 8,
            gradeSource = "AUTO",
            gradingVersion = 5,
            predictedRecall = 0.9,
            fsrsFitVersion = 0,
            wasGuess = false,
            wasCorrect = true,
            wasSkipped = false
        )
        db.reviewLogDao().insertAll(
            listOf(graded, graded.copy(reviewedAt = labelAt + hour))
        )
        assertEquals("the fixture must be a usable pair", 1, calibrationPairs())

        repository.settingsRestore(v27Settings(version = 5))

        val report = repository
            .statsRetention(null, StatsAggregator.Range.ALL, now)
            .calibrationReport!!
        assertEquals(
            "the 4/20 row cannot open an epoch for 6/60",
            6,
            repository.schedulerConfigNow().gradingVersion
        )
        assertTrue(
            "the epoch must start after the other pair's reviews " +
                "(epoch=${report.epochStart}, label=$labelAt)",
            report.epochStart > labelAt
        )
        assertEquals(
            "reviews graded under the other pair are not evidence for these cuts",
            0,
            report.pairs
        )
    }

    /**
     * F1: the calibration cache is keyed on (scope, version, fit version, block
     * end, max reviewedAt), none of which a restore has to move any more — it
     * keeps the restored id and epoch. A full restore replaces the study log
     * before the settings land, so a report built from the pre-restore rows must
     * not be served for the rows it brought. `max(reviewedAt)` is deliberately
     * left identical here: it is the freshness guard the key relies on, and it
     * cannot notice rows that were replaced rather than appended.
     */
    @Test
    fun aRestoreDoesNotServeTheReportBuiltFromPreRestoreRows() = dbBlocking {
        val restoredStart = todayStart - 20 * day
        db.gradingRulesDao().insertVersion(
            GradingVersionEntity(
                id = 5,
                easySeconds = 6L,
                slowSeconds = 60L,
                effectiveFrom = restoredStart,
                reason = "manual",
                correctnessOnly = false
            )
        )
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))
        val labelAt = todayStart - 10 * day + 2 * hour
        val graded = ReviewLogEntity(
            paperId = "p1",
            questionId = "q1",
            rating = 3,
            reviewedAt = labelAt,
            stateBefore = "REVIEW",
            intervalDaysAfter = 8,
            gradeSource = "AUTO",
            gradingVersion = 5,
            predictedRecall = 0.9,
            fsrsFitVersion = 0,
            wasGuess = false,
            wasCorrect = true,
            wasSkipped = false
        )
        db.reviewLogDao().insertAll(
            listOf(graded, graded.copy(reviewedAt = labelAt + hour))
        )
        repository.settingsRestore(v27Settings(version = 5))

        val before = repository.statsRetention(null, StatsAggregator.Range.ALL, now)
        assertEquals(
            "the fixture must be a usable pair",
            1,
            before.calibrationReport!!.pairs
        )

        // The restore's first half replaced the log; the later row — the cache
        // key's freshness guard — is still the newest row in the log.
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM review_log WHERE id = (SELECT MIN(id) FROM review_log)"
        )
        repository.settingsRestore(v27Settings(version = 5))

        val after = repository.statsRetention(null, StatsAggregator.Range.ALL, now)
        assertEquals(
            "a restore must not serve the report built from pre-restore rows",
            0,
            after.calibrationReport!!.pairs
        )
    }

    /**
     * A settings file with the absolute-rule gate, the version id and the pair
     * in force.
     */
    private fun v27Settings(version: Int) = McqBackupFormat.SettingsFile(
        mapOf(
            "grading_rules_version" to McqBackupFormat.PreferenceEntry(
                "int",
                JsonPrimitive(SettingsStore.GRADING_RULES_VERSION)
            ),
            "ladder_version" to McqBackupFormat.PreferenceEntry(
                "int",
                JsonPrimitive(version)
            ),
            "correctness_only" to McqBackupFormat.PreferenceEntry(
                "boolean",
                JsonPrimitive(false)
            ),
            "anki_fast_seconds" to McqBackupFormat.PreferenceEntry(
                "double",
                JsonPrimitive(6.0)
            ),
            "anki_slow_seconds" to McqBackupFormat.PreferenceEntry(
                "double",
                JsonPrimitive(60.0)
            )
        )
    )

    /**
     * B1 regression: Statistics can be the first screen a v28 install reads.
     *
     * This models the real v26 → v27 → v28 migration chain, not an empty
     * history: the 27→28 step copied `ladder_versions` into `grading_versions`,
     * so the old version row (id 1) is already there, `ladder_version` still
     * names it, and the stored pair was tuned for the retired reading
     * allowance. The seed must mint id 2 with a NEW `effectiveFrom`, not reuse
     * id 1's old epoch. Reviews carry version 1 and sit after that old epoch
     * but before the seed, so a regression that stamps the new row with the
     * old time would count them (`pairs > 0`).
     */
    @Test
    fun openingStatisticsOnAMigratedV27InstallSeedsANewEpoch() = dbBlocking {
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
        // The old-rule state a v28 install upgrades from: the old pair and its
        // version id in DataStore, the retired rule's gate absent.
        context.dataStore.edit { prefs ->
            prefs[doublePreferencesKey("anki_fast_seconds")] = 8.0
            prefs[doublePreferencesKey("anki_slow_seconds")] = 30.0
            prefs[intPreferencesKey("ladder_version")] = 1
            // No grading_rules_version: this store was last written under the
            // retired rule.
        }
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))
        // One usable label→outcome pair, both rows graded under the retired rule.
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

    /**
     * A cached report must not outlive the reviews it counted.
     *
     * `StatsStore.calibrationCache` is keyed on `max(reviewedAt)`, and a
     * review-row removal only moves that when the newest row was among those
     * removed. The fixture below keeps the newest row on another card, so the
     * key is byte-identical before and after every removal: only the
     * repository's invalidation stops the second read from serving the first
     * report (which still counts a pair that no longer exists).
     */
    @Test
    fun aDeletionDoesNotServeTheReportBuiltFromDeletedReviews() = dbBlocking {
        seedDeletablePair()
        assertReviewRemovalStopsTheCachedReport("a question delete") {
            repository.deleteQuestion("q1")
        }
    }

    /** The same funnel, one paper up: the paper's own rows go with it. */
    @Test
    fun aPaperDeletionDoesNotServeTheReportBuiltFromDeletedReviews() = dbBlocking {
        seedDeletablePair()
        assertReviewRemovalStopsTheCachedReport("a paper delete") {
            repository.deletePaper("p1")
        }
    }

    /** The same funnel, one category up. */
    @Test
    fun aCategoryDeletionDoesNotServeTheReportBuiltFromDeletedReviews() = dbBlocking {
        seedDeletablePair()
        assertReviewRemovalStopsTheCachedReport("a category delete") {
            repository.deleteCategory("c1")
        }
    }

    /** The same funnel, in bulk. */
    @Test
    fun aBulkDeletionDoesNotServeTheReportBuiltFromDeletedReviews() = dbBlocking {
        seedDeletablePair()
        assertReviewRemovalStopsTheCachedReport("a bulk question delete") {
            repository.deleteQuestions(listOf("q1"))
        }
    }

    /**
     * A cross-paper move keeps history but re-keys it to the destination paper,
     * so the all-papers report is unchanged. The destination paper's cached
     * report must still be refreshed: the re-key moves no cache-key input.
     */
    @Test
    fun aCrossPaperMoveRefreshesTheDestinationReport() = dbBlocking {
        seedDeletablePair()
        assertCrossPaperMoveRefreshesDestinationReport("a cross-paper move") {
            repository.moveQuestionsToCategory(listOf("q1"), "c2")
        }
    }

    /**
     * The same funnel when a passage moves: its member crosses a paper boundary
     * and its history is re-keyed with the schedule.
     */
    @Test
    fun aPassageMoveRefreshesTheDestinationReport() = dbBlocking {
        seedDeletablePair()
        // q1 becomes the member of a passage that lives in paper p1.
        db.questionDao().upsertAll(
            listOf(
                QuestionEntity(
                    "q1", "c1", passageId = "passage-1",
                    text = "", searchText = "", searchTextFolded = "",
                    tags = "", tagsFolded = ""
                )
            )
        )
        db.passageDao().upsert(
            PassageEntity(
                id = "passage-1",
                categoryId = "c1",
                title = "Passage One",
                text = "[]",
                searchText = "Passage One",
                searchTextFolded = "passage one"
            )
        )

        assertCrossPaperMoveRefreshesDestinationReport("a passage move") {
            // The passage moves to paper p2, so its member crosses a paper
            // boundary and its review rows are re-keyed with the schedule.
            repository.savePassage(
                Passage(id = "passage-1", categoryId = "c2", title = "Passage One")
            )
        }
    }

    /**
     * Assigning a question to a passage in another paper enforces the passage's
     * category: the question crosses a paper boundary and its history is
     * re-keyed with its old schedule.
     */
    @Test
    fun aQuestionSaveIntoAnotherPaperPassageRefreshesTheDestinationReport() = dbBlocking {
        seedDeletablePair()
        db.passageDao().upsert(
            PassageEntity(
                id = "passage-in-p2",
                categoryId = "c2",
                title = "Passage In Paper Two",
                text = "[]",
                searchText = "Passage In Paper Two",
                searchTextFolded = "passage in paper two"
            )
        )
        val q1 = repository.getQuestion("q1")!!

        assertCrossPaperMoveRefreshesDestinationReport("a question save into another paper's passage") {
            repository.saveQuestion(q1.copy(passageId = "passage-in-p2"))
        }
    }

    /**
     * The companion of the delete cases: without a read that is served from
     * the cache, they cannot tell "invalidated" from "never cached". Here the
     * row is deleted straight through the DAO, so no repository wrapper runs
     * and nothing invalidates the cache — the second read must still serve the
     * stale report, which is what proves the first read was cached at all.
     */
    @Test
    fun theCalibrationReportIsServedFromTheCache() = dbBlocking {
        seedDeletablePair()

        val before = repository.statsRetention(null, StatsAggregator.Range.ALL, now)
        assertEquals(
            "the fixture must be a usable pair",
            1,
            before.calibrationReport!!.pairs
        )

        // Straight through the DAO: no wrapper, no invalidation.
        db.reviewLogDao().deleteByQuestionsChunked(listOf("q1"))

        val after = repository.statsRetention(null, StatsAggregator.Range.ALL, now)
        assertEquals(
            "the cache must still serve the report built from the deleted row",
            1,
            after.calibrationReport!!.pairs
        )
    }

    /**
     * The fixture every review-removal funnel needs: one usable pair on q1
     * (paper p1) and one newer row on q2 (paper p2), so removing q1's card
     * cannot move the cache key — `max(reviewedAt)` is its only row-derived
     * input.
     */
    private suspend fun seedDeletablePair() {
        db.gradingRulesDao().insertVersion(
            GradingVersionEntity(
                id = 5,
                easySeconds = 6L,
                slowSeconds = 60L,
                effectiveFrom = todayStart - 20 * day,
                reason = "manual",
                correctnessOnly = false
            )
        )
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))
        db.paperDao().insertIgnore(PaperEntity(id = "p2", title = "Paper Two"))
        db.categoryDao().upsert(CategoryEntity("c1", "p1", "Alpha"))
        db.categoryDao().upsert(CategoryEntity("c2", "p2", "Beta"))
        db.questionDao().upsertAll(
            listOf(
                QuestionEntity("q1", "c1", text = "", searchText = "", searchTextFolded = "", tags = "", tagsFolded = ""),
                QuestionEntity("q2", "c2", text = "", searchText = "", searchTextFolded = "", tags = "", tagsFolded = "")
            )
        )
        val labelAt = todayStart - 10 * day + 2 * hour
        db.reviewLogDao().insertAll(
            listOf(
                gradedRow("q1", labelAt),
                gradedRow("q1", labelAt + hour),
                // The newest row belongs to the other paper's card, so every
                // delete below leaves the cache key's freshness guard alone.
                gradedRow("q2", now - hour, paperId = "p2")
            )
        )
        repository.settingsRestore(v27Settings(version = 5))
    }

    /**
     * The shared assertion of the review-removal funnels: the cached report
     * must not outlive the reviews it counted.
     */
    private suspend fun assertReviewRemovalStopsTheCachedReport(
        what: String,
        removeReviews: suspend () -> Unit
    ) {
        val before = repository.statsRetention(null, StatsAggregator.Range.ALL, now)
        assertEquals(
            "the fixture must be a usable pair",
            1,
            before.calibrationReport!!.pairs
        )
        val newestBefore = newestReviewAt()
        assertEquals(now - hour, newestBefore)

        removeReviews()

        assertEquals(
            "the review-row removal must not move the cache key",
            newestBefore,
            newestReviewAt()
        )
        val after = repository.statsRetention(null, StatsAggregator.Range.ALL, now)
        assertEquals(
            "$what must not serve the report built from deleted reviews",
            0,
            after.calibrationReport!!.pairs
        )
    }

    /**
     * The cross-paper counterpart of [assertReviewRemovalStopsTheCachedReport]:
     * here nothing is deleted, so the all-papers report is unchanged. The rows
     * are re-keyed to the destination paper, which must make its own cached
     * report include them. The newest row belongs to the other paper's card, so
     * `max(reviewedAt)` — the key's only row-derived input — does not move; only
     * invalidation can turn the cached 0 pairs into the moved card's 1 pair.
     *
     * Both reads deliberately go through the same [repository] and its one
     * calibration cache. This is the only test that proves a cross-paper move
     * invalidates that cache: the pre-move read pins a 0-pair p2 report under a
     * key the re-key does not change, so the post-move read can only see the
     * moved card's pair if the move actually dropped the cached report.
     */
    private suspend fun assertCrossPaperMoveRefreshesDestinationReport(
        what: String,
        move: suspend () -> Unit
    ) {
        val p2Before = repository.statsRetention("p2", StatsAggregator.Range.ALL, now)
        assertEquals("the fixture must have no pairs in p2 yet", 0, p2Before.calibrationReport!!.pairs)
        val newestBefore = newestReviewAt()
        assertEquals(now - hour, newestBefore)

        move()

        assertEquals("the move must not move the cache key", newestBefore, newestReviewAt())
        val p2After = repository.statsRetention("p2", StatsAggregator.Range.ALL, now)
        assertEquals(
            "$what must refresh the destination paper's cached report",
            1,
            p2After.calibrationReport!!.pairs
        )
    }

    /** One AUTO row from a graded rule (v5 in this file), ready to pair. */
    private fun gradedRow(
        questionId: String,
        at: Long,
        paperId: String = "p1"
    ) = ReviewLogEntity(
        paperId = paperId,
        questionId = questionId,
        rating = 3,
        reviewedAt = at,
        stateBefore = "REVIEW",
        intervalDaysAfter = 8,
        gradeSource = "AUTO",
        gradingVersion = 5,
        predictedRecall = 0.9,
        fsrsFitVersion = 0,
        wasGuess = false,
        wasCorrect = true,
        wasSkipped = false
    )

    /** `max(reviewedAt)` — the calibration cache key's only row-derived input. */
    private fun newestReviewAt(): Long = db.openHelper.readableDatabase
        .query("SELECT MAX(reviewedAt) FROM review_log")
        .use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    @Test
    fun paperScopeKeepsOnlyItsOwnRows() = dbBlocking {
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
    fun theScopedCategoryMapPagesOnlyTheSelectedPaper() = dbBlocking {
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
