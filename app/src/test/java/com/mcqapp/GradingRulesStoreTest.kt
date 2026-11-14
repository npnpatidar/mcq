package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.GradingVersionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.GradingRulesStore
import com.mcqapp.data.repository.InstallFreshness
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.data.repository.dataStore
import com.mcqapp.domain.SchedulerConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cut points' single writer (v27): every cut change owns a version row, the
 * seed happens once, a crash between the value and the row is repaired, and a
 * pair saved under the old reading-allowance rule starts a new epoch instead of
 * reusing a version id whose `effectiveFrom` predates the rule change.
 *
 * Robolectric + a real SQLite database, so like every DB-backed test it runs on
 * CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GradingRulesStoreTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @get:Rule
    val settingsReset = SettingsResetRule()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    /** The upgrade side of the store, for tests about versioning rather than seeding. */
    private fun store() = SettingsStore(context, freshInstall = false)
    private fun rules() = GradingRulesStore(db, store())

    /**
     * The production wiring of the first-launch seed: freshness comes from the
     * database's own content, exactly as [McqApplication] supplies it.
     */
    private fun productionRepository() = McqRepository(db, context)

    /** An upgrade repository: tests that pin a timed pair must not be seeded. */
    private fun upgradeRepository() = McqRepository(db, context, freshInstall = false)

    @Test
    fun firstReadSeedsOnePriorVersionAndTheSecondDoesNot() = runBlocking {
        val first = rules().currentVersion()
        assertTrue(first > 0)
        val versions = db.gradingRulesDao().allVersions()
        assertEquals(1, versions.size)
        assertEquals("prior", versions.single().reason)
        assertEquals(6L, versions.single().easySeconds)
        assertEquals(60L, versions.single().slowSeconds)
        assertFalse("the shipped rule grades on the clock", versions.single().correctnessOnly)

        assertEquals(first, rules().currentVersion())
        assertEquals(1, db.gradingRulesDao().allVersions().size)
        // The preferences carry the same version the row does.
        assertEquals(first, store().gradingVersionOrNull())
        assertEquals(first, store().schedulerConfigNow().gradingVersion)
    }

    /**
     * A new install starts on correctness-only grading, and the version row
     * that covers its first reviews must say so: a row that claimed the clock
     * graded them would feed the answer-time suggestion with tinted dwells.
     */
    @Test
    fun aFreshInstallGradesOnCorrectnessAndItsVersionRowSaysSo() = runBlocking {
        val freshStore = SettingsStore(context, freshInstall = true)
        val version = GradingRulesStore(db, freshStore).currentVersion()
        val row = db.gradingRulesDao().version(version.toLong())!!
        assertTrue("the first version must record the rule in force", row.correctnessOnly)
        assertTrue(freshStore.schedulerConfigNow().correctnessOnlyGrading)
    }

    /**
     * B3: a preference is not an upgrade. A theme change (or any other setting)
     * writes DataStore long before the first scheduler read, and the seed must
     * still apply because the database has no papers and no reviews.
     */
    @Test
    fun anUnrelatedPreferenceAndAnEmptyDatabaseStillSeedCorrectnessOnly() = runBlocking {
        context.dataStore.edit { it[booleanPreferencesKey("haptics_enabled")] = false }
        val repository = productionRepository()
        assertTrue(
            "a stored preference must not turn a new install into an upgrade",
            repository.schedulerConfigNow().correctnessOnlyGrading
        )
        val row = db.gradingRulesDao().version(repository.gradingVersion().toLong())!!
        assertTrue("the seed's version row must say it graded without the clock", row.correctnessOnly)
    }

    /** B2: one paper is content, so the install is an upgrade and stays timed. */
    @Test
    fun onePaperMakesTheInstallAnUpgrade() = runBlocking {
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))
        val repository = productionRepository()
        assertFalse(repository.schedulerConfigNow().correctnessOnlyGrading)
        assertFalse(db.gradingRulesDao().version(repository.gradingVersion().toLong())!!.correctnessOnly)
    }

    /**
     * B2: a review row is content too. This fixture is review-only on purpose:
     * a paper beside the review would pass on a papers-only probe. Room
     * enforces `review_log.paperId`'s foreign key, so the row is inserted with
     * enforcement off for this statement — the probe's second branch must hold
     * on its own, not as a consequence of the paper branch.
     */
    @Test
    fun aReviewRowAlsoMakesTheInstallAnUpgrade() = runBlocking {
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("PRAGMA foreign_keys=OFF")
        try {
            sqlite.execSQL(
                "INSERT INTO review_log (paperId, questionId, rating, reviewedAt) " +
                    "VALUES ('p1', 'q1', 3, 1)"
            )
        } finally {
            sqlite.execSQL("PRAGMA foreign_keys=ON")
        }
        assertEquals("the fixture must have no paper", 0, countRows("papers"))
        assertEquals("the fixture must have exactly one review", 1, countRows("review_log"))
        val repository = productionRepository()
        assertFalse(repository.schedulerConfigNow().correctnessOnlyGrading)
    }

    /**
     * D: the first-start answer is taken before the first config read and
     * persisted. An ordinary first run — launch, open the library, import a
     * paper — must not turn into an "upgrade" because the probe ran late; and
     * a relaunch in the same install must inherit the same answer.
     */
    @Test
    fun theFreshnessAnswerIsTakenAtFirstStartAndSurvivesARelaunch() = runBlocking {
        val firstStart = InstallFreshness(context) { db.hasNoPapersOrReviews() }
        assertTrue("the install is new at first start", firstStart.isNewInstall())

        // The learner imports before ever opening Statistics or Settings.
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))

        // A new process would build a new gate; the persisted marker answers.
        val relaunched = InstallFreshness(context) { db.hasNoPapersOrReviews() }
        assertTrue(
            "a paper imported during the first run must not rewrite history",
            relaunched.isNewInstall()
        )
        val repository = McqRepository(db, context, relaunched)
        assertTrue(repository.schedulerConfigNow().correctnessOnlyGrading)
        assertTrue(
            db.gradingRulesDao().version(repository.gradingVersion().toLong())!!.correctnessOnly
        )
    }

    /**
     * The failure edge of the first-start answer: a probe that cannot read the
     * database (corrupt file, migration that will not finish) must not throw
     * out of a launch, must not seed correctness-only over history it cannot
     * read, and must not persist an answer. The next read retries, so a
     * repaired install still gets the right rule.
     */
    @Test
    fun aFailedProbeAnswersNotNewWithoutPersistingAndIsRetried() = runBlocking {
        var healthy = false
        val freshness = InstallFreshness(context) {
            check(healthy) { "grading_versions is unreadable" }
            true
        }

        assertFalse(
            "an unreadable database is the conservative answer: not a new install",
            freshness.isNewInstall()
        )
        assertNull(
            "a failed probe must not persist an answer",
            context.dataStore.data.first()[InstallFreshness.MARKER_KEY]
        )
        val store = SettingsStore(context, freshness)
        assertFalse(
            "a failed probe must not seed correctness-only over unreadable history",
            store.schedulerConfigNow().correctnessOnlyGrading
        )

        // The learner repairs the install (or restores a backup): the failure
        // was not cached, so the same gate can now answer and persist.
        healthy = true
        assertTrue(freshness.isNewInstall())
        assertTrue(
            "the retry's answer is what gets persisted",
            context.dataStore.data.first()[InstallFreshness.MARKER_KEY] == true
        )
        assertTrue(store.schedulerConfigNow().correctnessOnlyGrading)
    }

    private fun countRows(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /**
     * B3: an explicitly stored choice always wins, including `false`. The empty
     * database would otherwise seed correctness-only.
     */
    @Test
    fun anExplicitlyStoredFalseIsNeverOverwrittenByTheSeed() = runBlocking {
        context.dataStore.edit { it[booleanPreferencesKey("correctness_only")] = false }
        val repository = productionRepository()
        assertFalse(repository.schedulerConfigNow().correctnessOnlyGrading)
        // Idempotent: the second read cannot flip it either.
        assertFalse(repository.schedulerConfigNow().correctnessOnlyGrading)
        assertEquals(1, db.gradingRulesDao().allVersions().size)
    }

    /** A Settings cut edit must write a version row, not just a preference. */
    @Test
    fun aManualCutEditWritesAVersionRow() = runBlocking {
        val repository = upgradeRepository()
        val before = repository.gradingVersion()
        repository.setSchedulerConfig(SchedulerConfig(fastSeconds = 10L))
        val after = repository.gradingVersion()
        assertEquals(before + 1, after)

        val row = db.gradingRulesDao().version(after.toLong())!!
        assertEquals("manual", row.reason)
        assertEquals(10L, row.easySeconds)
        assertEquals(60L, row.slowSeconds)
        assertEquals(10L, repository.schedulerConfigNow().fastSeconds)
    }

    /** Saving a config whose cuts did not change is not a new version. */
    @Test
    fun aSaveWithoutACutChangeDoesNotVersion() = runBlocking {
        val repository = upgradeRepository()
        val before = repository.gradingVersion()
        repository.setSchedulerConfig(SchedulerConfig(desiredRetention = 0.85))
        assertEquals(before, repository.gradingVersion())
        assertEquals(1, db.gradingRulesDao().allVersions().size)
    }

    @Test
    fun aResetThatChangesThePairWritesOneManualVersion() = runBlocking {
        val repository = upgradeRepository()
        repository.setSchedulerConfig(SchedulerConfig(fastSeconds = 12L, slowSeconds = 40L))
        val before = repository.gradingVersion()
        repository.resetSchedulerConfig()
        val after = repository.gradingVersion()
        assertTrue(after > before)
        assertEquals(6L, repository.schedulerConfigNow().fastSeconds)
        val row = db.gradingRulesDao().version(after.toLong())!!
        assertEquals(6L, row.easySeconds)
        assertEquals(60L, row.slowSeconds)
        // Reset is a user action on the cuts: `manual`, not `prior`.
        assertEquals("manual", row.reason)
        assertTrue(
            "the reset row must carry a real epoch timestamp",
            row.effectiveFrom > 0L
        )
        // The reset rewrites the pair, not the rule: an install next door was
        // grading on the clock, and it still is.
        assertFalse(repository.schedulerConfigNow().correctnessOnlyGrading)
        assertFalse(row.correctnessOnly)
    }

    /**
     * F: a reset whose pair was already 6/60 changes no grading evidence. It
     * must not drop the version or mint a row: that would move `epochStart` to
     * now and discard weeks of timed-grading calibration because a pacing knob
     * moved.
     */
    @Test
    fun aResetWithAnUnchangedPairLeavesTheVersionAndEpochAlone() = runBlocking {
        val repository = upgradeRepository()
        repository.setSchedulerConfig(
            SchedulerConfig(
                fastSeconds = 6L,
                slowSeconds = 60L,
                desiredRetention = 0.85
            )
        )
        val before = repository.gradingVersion()
        val beforeRow = db.gradingRulesDao().version(before.toLong())!!
        val versionsBefore = db.gradingRulesDao().allVersions().size
        repository.resetSchedulerConfig()

        assertEquals("the epoch must not move for a pacing-only reset", before, repository.gradingVersion())
        assertEquals(versionsBefore, db.gradingRulesDao().allVersions().size)
        assertEquals(
            beforeRow.effectiveFrom,
            db.gradingRulesDao().version(before.toLong())!!.effectiveFrom
        )
        assertEquals(0.9, repository.schedulerConfigNow().desiredRetention, 0.0001)
    }

    /**
     * B4: Reset is a pacing action, so a correctness-only learner must still be
     * on correctness-only after it — and the version row it writes has to name
     * that rule, or the reviews it grades would be filed under a tinted clock
     * that never existed.
     */
    @Test
    fun aResetUnderCorrectnessOnlyKeepsTheRuleAndRecordsIt() = runBlocking {
        val repository = upgradeRepository()
        repository.setSchedulerConfig(
            SchedulerConfig(
                correctnessOnlyGrading = true,
                fastSeconds = 10L,
                slowSeconds = 40L
            )
        )
        val before = repository.gradingVersion()
        repository.resetSchedulerConfig()
        val after = repository.gradingVersion()
        assertTrue(after > before)
        assertTrue(
            "Reset must not switch a correctness-only install back to timed grading",
            repository.schedulerConfigNow().correctnessOnlyGrading
        )
        val row = db.gradingRulesDao().version(after.toLong())!!
        assertEquals("manual", row.reason)
        assertEquals(6L, row.easySeconds)
        assertEquals(60L, row.slowSeconds)
        assertTrue("the reset row must record the rule that grades", row.correctnessOnly)
        assertTrue(
            "a reset that changes cuts must open a new epoch",
            row.effectiveFrom > 0L
        )
    }

    /**
     * A crash after the atomic preference write but before the row leaves a
     * version number with no history row; the next read repairs it from the
     * stored cuts rather than moving the number again.
     */
    @Test
    fun aMissingHistoryRowIsRepairedFromThePreferences() = runBlocking {
        val store = store()
        store.writeCuts(9L, 31L, 4, correctnessOnly = false)
        db.openHelper.writableDatabase.execSQL("DELETE FROM grading_versions")

        assertEquals(4, rules().currentVersion())
        val repaired = db.gradingRulesDao().version(4L)
        assertNotNull(repaired)
        assertEquals("prior", repaired!!.reason)
        assertEquals(9L, repaired.easySeconds)
        assertEquals(31L, repaired.slowSeconds)
    }

    /**
     * Switching to correctness-only grading is a rule change, not a tuning
     * change: it owns a version row so reviews graded with the clock are never
     * pooled with reviews graded without it.
     */
    @Test
    fun aRuleSwitchWritesItsOwnVersionAndSwitchingBackDoesToo() = runBlocking {
        val repository = upgradeRepository()
        val before = repository.gradingVersion()

        repository.setSchedulerConfig(SchedulerConfig(correctnessOnlyGrading = true))
        val on = repository.gradingVersion()
        assertEquals(before + 1, on)
        val onRow = db.gradingRulesDao().version(on.toLong())!!
        assertEquals("rule", onRow.reason)
        assertTrue(onRow.correctnessOnly)
        // The cuts are recorded unchanged: they still describe the fallback.
        assertEquals(6L, onRow.easySeconds)
        assertEquals(60L, onRow.slowSeconds)
        assertTrue(repository.schedulerConfigNow().correctnessOnlyGrading)

        repository.setSchedulerConfig(SchedulerConfig(correctnessOnlyGrading = false))
        val off = repository.gradingVersion()
        assertEquals(on + 1, off)
        val offRow = db.gradingRulesDao().version(off.toLong())!!
        assertEquals("rule", offRow.reason)
        assertFalse(offRow.correctnessOnly)
        assertFalse(repository.schedulerConfigNow().correctnessOnlyGrading)
    }

    /** A cut edited while the switch is on is a manual version of that rule. */
    @Test
    fun aCutEditUnderCorrectnessOnlyKeepsTheModeOnItsRow() = runBlocking {
        val repository = upgradeRepository()
        repository.setSchedulerConfig(SchedulerConfig(correctnessOnlyGrading = true))
        repository.setSchedulerConfig(
            SchedulerConfig(correctnessOnlyGrading = true, fastSeconds = 9)
        )
        val row = db.gradingRulesDao().version(repository.gradingVersion().toLong())!!
        assertEquals("manual", row.reason)
        assertTrue(row.correctnessOnly)
        assertEquals(9L, row.easySeconds)
    }

    @Test
    fun restoreSeedsANewPriorVersionContinuingTheNumbering() = runBlocking {
        rules().currentVersion()
        // Pretend the restored backup carried version 5 with its own cuts.
        db.gradingRulesDao().insertVersion(
            GradingVersionEntity(
                id = 5,
                easySeconds = 11L,
                slowSeconds = 33L,
                effectiveFrom = 1L,
                reason = "prior"
            )
        )
        // A restore rewrites the preferences before the seed reads them.
        store().writeCuts(11L, 33L, 5)
        val seeded = rules().seedAfterRestore()
        assertEquals(6, seeded)
        assertEquals("prior", db.gradingRulesDao().version(6L)!!.reason)
        assertEquals(11L, db.gradingRulesDao().version(6L)!!.easySeconds)
    }

    /**
     * The upgrade case: v26 left a version id and a pair tuned for the reading
     * allowance. The id's `effectiveFrom` predates the absolute rule, so reusing
     * it would pool two grading rules in one calibration epoch.
     */
    @Test
    fun aPairSavedUnderAnOlderRuleStartsANewEpoch() = runBlocking {
        db.gradingRulesDao().insertVersion(
            GradingVersionEntity(
                id = 1,
                easySeconds = 8L,
                slowSeconds = 30L,
                effectiveFrom = 1_000L,
                reason = "prior"
            )
        )
        context.dataStore.edit { prefs ->
            prefs[doublePreferencesKey("anki_fast_seconds")] = 8.0
            prefs[doublePreferencesKey("anki_slow_seconds")] = 30.0
            prefs[intPreferencesKey("ladder_version")] = 1
            // No grading_rules_version: this store was last written by v26.
        }

        // Before the first versioned read the stale pair is already ignored.
        assertEquals(6L, store().schedulerConfigNow().fastSeconds)
        assertEquals(0, store().schedulerConfigNow().gradingVersion)

        val fresh = rules().currentVersion()
        assertEquals(2, fresh)
        assertEquals(6L, db.gradingRulesDao().version(2L)!!.easySeconds)
        assertEquals(60L, db.gradingRulesDao().version(2L)!!.slowSeconds)
        // The old row keeps its pair, so every pre-v27 review still resolves it.
        assertEquals(8L, db.gradingRulesDao().version(1L)!!.easySeconds)
        assertEquals(fresh, store().schedulerConfigNow().gradingVersion)
        assertEquals(6L, store().schedulerConfigNow().fastSeconds)
    }

    /**
     * C: a pre-v27 backup has no `correctness_only`, and it is restored into an
     * install that started fresh. The startup answer must not survive the
     * restore — the backup brought history, so the restored install is no
     * longer new, and the seed must not write correctness-only over it.
     */
    @Test
    fun restoringAPreV27BackupOverRestoredHistoryDoesNotSeedCorrectnessOnly() = runBlocking {
        val repository = productionRepository()
        // The install was fresh before the restore: it seeded correctness-only.
        assertTrue(repository.schedulerConfigNow().correctnessOnlyGrading)
        val before = repository.gradingVersion()

        // The backup's database restore lands first (Backup.restore), so the
        // content probe must see the restored history.
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper One"))
        val preV27 = McqBackupFormat.SettingsFile(
            mapOf(
                "theme_mode" to McqBackupFormat.PreferenceEntry(
                    "string",
                    JsonPrimitive("dark")
                ),
                "anki_fast_seconds" to McqBackupFormat.PreferenceEntry(
                    "double",
                    JsonPrimitive(8.0)
                ),
                "anki_slow_seconds" to McqBackupFormat.PreferenceEntry(
                    "double",
                    JsonPrimitive(30.0)
                )
            )
        )
        repository.settingsRestore(preV27)

        assertFalse(
            "restored history must not inherit the old start's correctness-only rule",
            repository.schedulerConfigNow().correctnessOnlyGrading
        )
        val after = repository.gradingVersion()
        assertTrue("the restore still seeds its own version", after > before)
        assertFalse(
            "the restored version's row must name the rule that grades",
            db.gradingRulesDao().version(after.toLong())!!.correctnessOnly
        )
    }

    /**
     * C: a stored rule wins, including a v27 backup's explicit `false`, even
     * on an install whose start was fresh and whose database has no content
     * to contradict it.
     */
    @Test
    fun aRestoredV27RuleWinsOverTheFreshInstallSeed() = runBlocking {
        val repository = productionRepository()
        assertTrue(repository.schedulerConfigNow().correctnessOnlyGrading)

        val v27 = McqBackupFormat.SettingsFile(
            mapOf(
                "correctness_only" to McqBackupFormat.PreferenceEntry(
                    "boolean",
                    JsonPrimitive(false)
                ),
                "grading_rules_version" to McqBackupFormat.PreferenceEntry(
                    "int",
                    JsonPrimitive(SettingsStore.GRADING_RULES_VERSION)
                )
            )
        )
        repository.settingsRestore(v27)

        assertFalse(
            "the restored rule is the learner's own; the seed is only a default",
            repository.schedulerConfigNow().correctnessOnlyGrading
        )
        val latest = db.gradingRulesDao().allVersions().maxByOrNull { it.id }!!
        assertFalse("the restore row must agree with the restored rule", latest.correctnessOnly)
    }

    /**
     * B1: the config a caller grades under must name the row the seed wrote,
     * not an id re-read before the seed. Here the retired v26 pair and its id
     * sit in preferences; the seed writes 6/60 under a new id, and the config
     * must come back with that id and pair together.
     */
    @Test
    fun schedulerConfigNowCarriesTheIdStoredWithTheSeededCuts() = runBlocking {
        db.gradingRulesDao().insertVersion(
            GradingVersionEntity(
                id = 1,
                easySeconds = 8L,
                slowSeconds = 30L,
                effectiveFrom = 1_000L,
                reason = "prior"
            )
        )
        context.dataStore.edit { prefs ->
            prefs[doublePreferencesKey("anki_fast_seconds")] = 8.0
            prefs[doublePreferencesKey("anki_slow_seconds")] = 30.0
            prefs[intPreferencesKey("ladder_version")] = 1
            // No grading_rules_version: this is the v26 state.
        }

        val config = upgradeRepository().schedulerConfigNow()
        assertEquals("the seed continues the retired row's numbering", 2, config.gradingVersion)
        assertEquals(6L, config.fastSeconds)
        assertEquals(60L, config.slowSeconds)
        val row = db.gradingRulesDao().version(2L)!!
        assertEquals(
            "the version must name the pair in the same config",
            config.fastSeconds,
            row.easySeconds
        )
        assertEquals(config.slowSeconds, row.slowSeconds)
    }

    /**
     * Item 6: the first Reset of a retired pre-v27 pair is not a change. The
     * effective pair was already the shipped 6/60, so no `manual` row is
     * minted — but the stale id still has to go, and unlike an unchanged
     * *current* pair the epoch does move: the next read mints a fresh `prior`
     * row at now, because the retired row's `effectiveFrom` names cuts that no
     * longer grade anything.
     */
    @Test
    fun aFirstResetOfARetiredPairMintsNoManualRow() = runBlocking {
        val retired = GradingVersionEntity(
            id = 1,
            easySeconds = 8L,
            slowSeconds = 30L,
            effectiveFrom = 1_000L,
            reason = "prior"
        )
        db.gradingRulesDao().insertVersion(retired)
        context.dataStore.edit { prefs ->
            prefs[doublePreferencesKey("anki_fast_seconds")] = 8.0
            prefs[doublePreferencesKey("anki_slow_seconds")] = 30.0
            prefs[intPreferencesKey("ladder_version")] = 1
        }

        val repository = upgradeRepository()
        repository.resetSchedulerConfig()
        assertEquals(
            "an unchanged effective pair must not mint a version",
            1,
            db.gradingRulesDao().allVersions().size
        )

        // The next read seeds the effective pair as `prior` under a new id,
        // never by reusing the retired row's 8/30.
        val version = repository.gradingVersion()
        assertEquals(2, version)
        val seeded = db.gradingRulesDao().version(2L)!!
        assertEquals(6L, seeded.easySeconds)
        assertEquals(60L, seeded.slowSeconds)
        assertEquals("prior", seeded.reason)
        assertTrue(
            "the first reset of a retired pair must re-epoch at now, not keep " +
                "the retired row's start (retired=${retired.effectiveFrom}, " +
                "seeded=${seeded.effectiveFrom})",
            seeded.effectiveFrom > retired.effectiveFrom
        )
        assertEquals(version, repository.schedulerConfigNow().gradingVersion)
    }
}
