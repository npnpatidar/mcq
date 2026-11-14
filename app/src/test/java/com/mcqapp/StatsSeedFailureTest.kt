package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.GradingVersionEntity
import com.mcqapp.data.repository.GradingRulesStore
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.data.repository.StatsStore
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.StatsAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule

/**
 * B1's failure edge: when the grading-version seed cannot run, Statistics must
 * never fall back to version 0 — that is the contaminated report (`epochStart
 * = 0`, pre-v27 rows pooled with the absolute-rule rows) the feature exists to
 * prevent.
 *
 * A positive stored id is still usable (the row repair happens on the next
 * write path), so the tab builds against it. With no id at all the store
 * throws, and `StatsViewModel` already renders that as its `loadError` state.
 *
 * Robolectric + a real SQLite database: CI-only on x86_64, compiles on the
 * ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class StatsSeedFailureTest {

    @get:Rule
    val timeout: TimeoutRule = TimeoutRule.seconds(180)

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore

    @get:Rule
    val settingsReset = SettingsResetRule()

    /** Database/DataStore work must not block Robolectric's paused main thread. */
    private fun <T> dbBlocking(block: suspend () -> T): T = runBlocking {
        withContext(Dispatchers.IO) { withTimeout(30_000) { block() } }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsStore(context, freshInstall = false)
        dbBlocking { db.ensureOpen() }
    }

    @After
    fun teardown() {
        db.close()
    }

    /** A rules store whose seed always fails, as a corrupt DB or migration would. */
    private fun failingRules() = object : GradingRulesStore(db, settings) {
        override suspend fun currentVersion(): Int =
            throw IllegalStateException("grading_versions is unreadable")
    }

    @Test
    fun aFailedSeedStillUsesTheStoredVersionRatherThanZero() {
        dbBlocking {
            // A version id written by an earlier successful pass, with its row.
            db.gradingRulesDao().insertVersion(
                GradingVersionEntity(
                    id = 7,
                    easySeconds = 9L,
                    slowSeconds = 31L,
                    effectiveFrom = 1_000L,
                    reason = "prior",
                    correctnessOnly = false
                )
            )
            settings.writeCuts(9L, 31L, 7, correctnessOnly = false)

            val store = StatsStore(db, settings, failingRules())
            val config = store.loadLibrary().config
            assertEquals(
                "the stored id is the only valid epoch; 0 would pool the old rule",
                7,
                config.gradingVersion
            )
            assertEquals(9L, config.fastSeconds)
        }
    }

    @Test
    fun aFailedSeedWithNoStoredVersionFailsTheTabInsteadOfReportingVersionZero() {
        val store = StatsStore(db, settings, failingRules())
        val failure = assertThrows(IllegalStateException::class.java) {
            dbBlocking { store.loadLibrary() }
        }
        assertEquals(
            "the loadError path needs a message, not a version-0 report",
            "The grading rule could not be loaded.",
            failure.message
        )
    }

    /** A rules store whose first seed fails, then delegates, as a transient DB failure would. */
    private fun failOnceRules(): GradingRulesStore {
        val real = GradingRulesStore(db, settings)
        return object : GradingRulesStore(db, settings) {
            private var failuresLeft = 1
            override suspend fun currentVersion(): Int {
                if (failuresLeft > 0) {
                    failuresLeft--
                    throw IllegalStateException("grading_versions is temporarily unreadable")
                }
                return real.currentVersion()
            }
        }
    }

    /**
     * A cut save or Reset writes the new id and its cuts in one DataStore edit
     * and inserts the `grading_versions` row after it, so a stats read can see
     * the id for an instant before its row exists. That read must heal the
     * window (the retry re-inserts the row) and report against it, not fail
     * the tab and not fall back to an epoch of 0.
     */
    @Test
    fun aVersionRowThatLandsAfterTheReadIsRepairedInsteadOfFailing() {
        dbBlocking {
            // The window: id 42 and its cuts are stored, its row is not yet.
            settings.writeCuts(9L, 31L, 42, correctnessOnly = false)
            val windowedRules = object : GradingRulesStore(db, settings) {
                private val real = GradingRulesStore(db, settings)
                private var calls = 0
                override suspend fun currentVersion(): Int {
                    calls++
                    // The first read sees the id but must not repair it yet.
                    if (calls == 1) return 42
                    return real.currentVersion()
                }
            }

            val store = StatsStore(db, settings, windowedRules)
            val retention = store.loadRetention(
                scopePaperId = null,
                range = StatsAggregator.Range.ALL,
                now = System.currentTimeMillis()
            )

            assertNotNull(
                "the retry must build the report, not fail the tab",
                retention.calibrationReport
            )
            val repaired = db.gradingRulesDao().version(42L)
            assertNotNull("the retry must re-insert the missing row", repaired)
            assertEquals(9L, repaired!!.easySeconds)
            assertEquals(31L, repaired.slowSeconds)
            assertEquals(
                "the report's epoch comes from the repaired row",
                repaired.effectiveFrom,
                retention.calibrationReport!!.epochStart
            )
        }
    }

    /**
     * The failure is neither cached nor persisted, so a later read can still
     * seed: the tab fails once, then reports against the row the retry wrote —
     * never against version 0.
     */
    @Test
    fun recoveryAfterAFailedSeedUsesTheFreshRow() {
        dbBlocking {
            val store = StatsStore(db, settings, failOnceRules())

            val first = try {
                store.loadLibrary()
                null
            } catch (e: IllegalStateException) {
                e
            }
            assertNotNull(
                "the failing read must be the loadError path, not a version-0 report",
                first
            )
            assertEquals(
                "the failing read must be the loadError path, not a version-0 report",
                "The grading rule could not be loaded.",
                first!!.message
            )

            val config = store.loadLibrary().config
            assertEquals("the retry must seed a real version", 1, config.gradingVersion)
            val row = db.gradingRulesDao().version(config.gradingVersion.toLong())!!
            assertEquals(
                "the config must name the pair the fresh row recorded",
                config.fastSeconds,
                row.easySeconds
            )
            assertEquals(config.slowSeconds, row.slowSeconds)
            assertEquals("prior", row.reason)
            assertEquals(SchedulerConfig().fastSeconds, config.fastSeconds)
        }
    }
}
