package com.mcqapp.data.repository

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.domain.TestPlan
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Internal, not private: RepositorySettingsTest seeds the same file directly to
// prove legacy keys are inert and that corrupt values fall back to defaults.
internal val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Every DataStore-backed preference in one place: appearance, behaviour
 * toggles, the Anki-parity scheduler options and the resume snapshot.
 * Split out of [McqRepository] so the repository's Room half can stay about
 * data and this file about user preferences — the two never interleave except
 * in [discardProgressFor], which paper deletion calls by hand.
 *
 * [installFreshness] decides the one default that is not a preference: a new
 * install starts on correctness-only grading, so the dwell it records is
 * unbiased evidence for the answer-time cuts. Whether an install is new is
 * decided once at first start from database content (see [InstallFreshness]),
 * not here; this store only writes the default when that answer is yes.
 */
internal class SettingsStore(
    private val context: Context,
    private val installFreshness: InstallFreshness
) : TestProgressSlot {

    /**
     * A store with no database behind it, for tests that pin the seed's write
     * behaviour. Production always gets an [InstallFreshness] backed by the
     * database; `freshInstall` stands in for its answer.
     */
    @VisibleForTesting
    internal constructor(context: Context, freshInstall: Boolean) :
        this(context, InstallFreshness.fixed(context, freshInstall))

    private val themeKey = stringPreferencesKey("theme_mode")

    fun themeMode(): Flow<String> =
        context.dataStore.data.map { it[themeKey] ?: "system" }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[themeKey] = mode }
    }

    private val themePaletteKey = stringPreferencesKey("theme_palette")

    fun themePalette(): Flow<String> =
        context.dataStore.data.map { it[themePaletteKey] ?: "material" }

    suspend fun setThemePalette(key: String) {
        context.dataStore.edit { it[themePaletteKey] = key }
    }

    private val customThemesKey = stringPreferencesKey("custom_themes")

    /**
     * Imported themes, as the JSON blob the UI encodes. The store owns the
     * preference (and its backup round-trip); the theme package owns the
     * format, so this layer never needs to know a colour from a name.
     */
    fun customThemesJson(): Flow<String?> =
        context.dataStore.data.map { it[customThemesKey] }

    /**
     * Read-modify-write of the imported themes in one DataStore edit. [update]
     * runs on DataStore's IO dispatcher and returns the new blob; throwing
     * from it aborts the write, which is how "too many themes" and a bad file
     * leave the stored list untouched.
     */
    suspend fun updateCustomThemesJson(update: (String?) -> String) {
        context.dataStore.edit { prefs ->
            prefs[customThemesKey] = update(prefs[customThemesKey])
        }
    }

    private val shuffleQuestionsKey = booleanPreferencesKey("shuffle_questions")
    private val shuffleOptionsKey = booleanPreferencesKey("shuffle_options")
    private val simplifiedStudyKey = booleanPreferencesKey("simplified_study")

    fun shuffleQuestions(): Flow<Boolean> =
        context.dataStore.data.map { it[shuffleQuestionsKey] ?: false }

    suspend fun setShuffleQuestions(enabled: Boolean) {
        context.dataStore.edit { it[shuffleQuestionsKey] = enabled }
    }

    /**
     * Simplified study: cards are graded automatically from the answer instead
     * of asking for Again/Hard/Good/Easy. On by default; it only changes where
     * a grade comes from, never the scheduler underneath it.
     */
    fun simplifiedStudy(): Flow<Boolean> =
        context.dataStore.data.map { it[simplifiedStudyKey] ?: true }

    suspend fun setSimplifiedStudy(enabled: Boolean) {
        context.dataStore.edit { it[simplifiedStudyKey] = enabled }
    }

    fun shuffleOptions(): Flow<Boolean> =
        context.dataStore.data.map { it[shuffleOptionsKey] ?: false }
    suspend fun setShuffleOptions(enabled: Boolean) {
        context.dataStore.edit { it[shuffleOptionsKey] = enabled }
    }

    private val hapticsEnabledKey = booleanPreferencesKey("haptics_enabled")

    /**
     * The app's own haptic feedback. On by default: one short tick is standard
     * UI confirmation, and the system's touch-feedback switch still gates it
     * underneath. The Settings switch is the app-level opt-out.
     */
    fun hapticsEnabled(): Flow<Boolean> =
        context.dataStore.data.map { it[hapticsEnabledKey] ?: true }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[hapticsEnabledKey] = enabled }
    }

    private val pdfTwoColumnKey = booleanPreferencesKey("pdf_two_column")

    fun pdfTwoColumn(): Flow<Boolean> =
        context.dataStore.data.map { it[pdfTwoColumnKey] ?: false }

    suspend fun setPdfTwoColumn(enabled: Boolean) {
        context.dataStore.edit { it[pdfTwoColumnKey] = enabled }
    }

    private val lastTestLengthKey = intPreferencesKey("last_test_length")

    /**
     * The length the learner last started a test with (plan §10). It is only
     * a starting point: the setup sheet snaps it to a selectable length and
     * clamps it to the current pool, so a smaller scope never inherits an
     * impossible request.
     */
    fun lastTestLength(): Flow<Int> =
        context.dataStore.data.map {
            it[lastTestLengthKey] ?: TestPlan.DEFAULT_LENGTH
        }

    suspend fun setLastTestLength(length: Int) {
        context.dataStore.edit { it[lastTestLengthKey] = length }
    }

    private val practiceModeKey = booleanPreferencesKey("practice_mode")

    fun practiceMode(): Flow<Boolean> =
        context.dataStore.data.map { it[practiceModeKey] ?: false }

    suspend fun setPracticeMode(enabled: Boolean) {
        context.dataStore.edit { it[practiceModeKey] = enabled }
    }

    private val strictModeKey = booleanPreferencesKey("strict_mode")

    fun strictMode(): Flow<Boolean> =
        context.dataStore.data.map { it[strictModeKey] ?: false }

    suspend fun setStrictMode(enabled: Boolean) {
        context.dataStore.edit { it[strictModeKey] = enabled }
    }

    /**
     * When on, re-importing a file whose questions have the same text and
     * options but a corrected answer key refreshes the stored answers instead
     * of reporting them as duplicates. Off by default, which preserves the
     * long-standing behaviour.
     */
    private val updateAnswersKey = booleanPreferencesKey("update_answers_on_duplicate")

    fun updateAnswersOnDuplicate(): Flow<Boolean> =
        context.dataStore.data.map { it[updateAnswersKey] ?: false }

    suspend fun setUpdateAnswersOnDuplicate(enabled: Boolean) {
        context.dataStore.edit { it[updateAnswersKey] = enabled }
    }

    private val autoAdvanceKey = booleanPreferencesKey("auto_advance")

    fun autoAdvance(): Flow<Boolean> =
        context.dataStore.data.map { it[autoAdvanceKey] ?: false }

    suspend fun setAutoAdvance(enabled: Boolean) {
        context.dataStore.edit { it[autoAdvanceKey] = enabled }
    }

    /**
     * Whether a representative test avoids questions asked in the last three
     * comparable attempts (section 9.8). On by default: a repeat-free sample
     * is the useful default, and turning it off is what produces a strict
     * Random attempt. The attempt records the rule it actually used.
     */
    private val avoidRecentQuestionsKey = booleanPreferencesKey("avoid_recent_questions")

    fun avoidRecentQuestions(): Flow<Boolean> =
        context.dataStore.data.map { it[avoidRecentQuestionsKey] ?: true }

    suspend fun setAvoidRecentQuestions(enabled: Boolean) {
        context.dataStore.edit { it[avoidRecentQuestionsKey] = enabled }
    }

    /**
     * Whether http(s) question images may be fetched over the network. Off by
     * default: a fetch hands whoever authored the bank the learner's IP and
     * the moment they opened the question. Data-URI images live inside the
     * bank and are always shown.
     */
    private val loadRemoteImagesKey = booleanPreferencesKey("load_remote_images")

    fun loadRemoteImages(): Flow<Boolean> =
        context.dataStore.data.map { it[loadRemoteImagesKey] ?: false }

    suspend fun setLoadRemoteImages(enabled: Boolean) {
        context.dataStore.edit { it[loadRemoteImagesKey] = enabled }
    }

    private val fontScaleKey = floatPreferencesKey("font_scale")

    fun fontScale(): Flow<Float> =
        context.dataStore.data.map {
            com.mcqapp.util.FontScale.coerce(it[fontScaleKey] ?: com.mcqapp.util.FontScale.DEFAULT)
        }

    suspend fun setFontScale(scale: Float) {
        context.dataStore.edit { it[fontScaleKey] = scale }
    }

    // --- Scheduler settings ---
    // Stored as doubles keyed by field name so a newly added option defaults
    // cleanly on an old install instead of reading back as 0. The old
    // anki_new_limit / anki_review_limit / anki_new_ignore_review_limit keys
    // are inert: daily caps are gone, so nothing reads them back. The SM-2
    // ease and graduating-interval keys met the same end when FSRS replaced
    // that engine.

    private fun schedKey(name: String) = doublePreferencesKey("anki_$name")

    // FSRS-era keys, unprefixed: they belong to this app's scheduler, not to
    // the Anki-parity set above.
    private val desiredRetentionKey = doublePreferencesKey("fsrs_desired_retention")
    private val studyModeKey = stringPreferencesKey("study_mode")
    private val introduceNewCardsKey = booleanPreferencesKey("introduce_new_cards")

    /**
     * Grade on correctness alone, ignoring the clock (v27). Unprefixed like the
     * other FSRS-era keys: it is this app's grading rule, not an Anki knob.
     */
    private val correctnessOnlyKey = booleanPreferencesKey("correctness_only")

    // CSV of the 21 weights rather than 21 numeric keys: the set is written
    // and read as one value, and a malformed list (wrong length, junk
    // tokens) falls back to the defaults in SchedulerConfig.sanitized.
    private val fsrsParametersKey = stringPreferencesKey("fsrs_parameters")

    /** Which weight set `fsrs_parameters` belongs to (v26). 0 = shipped defaults. */
    private val fsrsFitVersionKey = intPreferencesKey("fsrs_fit_version")

    /**
     * The grading version the cut points belong to (v26; key kept for upgrade
     * compatibility). Points at a `grading_versions` row.
     */
    private val ladderVersionKey = intPreferencesKey("ladder_version")

    /**
     * Which build of the cut rule the stored pair was calibrated for (v27).
     * Bumped whenever the rule itself changes, so the pair does not outlive the
     * model it was tuned against.
     */
    private val gradingRulesVersionKey = intPreferencesKey("grading_rules_version")

    /**
     * The scheduler config, as stored. This flow deliberately does not seed:
     * the fresh-install rule is a write, and a UI collector must never trigger
     * the database probe behind it. Seeding happens in [schedulerConfigNow]
     * (every non-UI read) and in the startup job; the first emission here is
     * therefore the stored value, seeded by whichever of those ran first.
     */
    fun schedulerConfig(): Flow<com.mcqapp.domain.SchedulerConfig> = flow {
        emitAll(
            context.dataStore.data.map { prefs ->
                val d = com.mcqapp.domain.SchedulerConfig()
                // v27 fixed the cuts at absolute 6 s / 60 s and deleted the reading
                // allowance, so a pair stored by an older build is measured against
                // a rule that no longer exists: keep it is to grade every answer
                // under a ladder the code no longer implements. Until the learner
                // saves a cut under this rule, the shipped pair wins.
                val cutsCurrent = (prefs[gradingRulesVersionKey] ?: 0) >= GRADING_RULES_VERSION
                com.mcqapp.domain.SchedulerConfig(
                    maxIntervalDays = (prefs[schedKey("max_interval")] ?: d.maxIntervalDays.toDouble()).toInt(),
                    relearnMs = (prefs[schedKey("relearn_ms")] ?: d.relearnMs.toDouble()).toLong(),
                    leechThreshold = (prefs[schedKey("leech_threshold")] ?: d.leechThreshold.toDouble()).toInt(),
                    desiredRetention = prefs[desiredRetentionKey] ?: d.desiredRetention,
                    studyMode = prefs[studyModeKey]?.let { stored ->
                        runCatching { com.mcqapp.domain.StudyMode.valueOf(stored) }.getOrNull()
                    } ?: d.studyMode,
                    introduceNewCards = prefs[introduceNewCardsKey] ?: d.introduceNewCards,
                    dayStartHour = (prefs[schedKey("day_start_hour")] ?: d.dayStartHour.toDouble()).toInt(),
                    correctnessOnlyGrading = prefs[correctnessOnlyKey] ?: d.correctnessOnlyGrading,
                    fastSeconds = if (cutsCurrent) {
                        (prefs[schedKey("fast_seconds")] ?: d.fastSeconds.toDouble()).toLong()
                    } else {
                        d.fastSeconds
                    },
                    slowSeconds = if (cutsCurrent) {
                        (prefs[schedKey("slow_seconds")] ?: d.slowSeconds.toDouble()).toLong()
                    } else {
                        d.slowSeconds
                    },
                    fsrsParameters = prefs[fsrsParametersKey]?.let { stored ->
                        stored.split(',')
                            .mapNotNull { token -> token.toDoubleOrNull() }
                            .takeIf { it.size == com.mcqapp.domain.FsrsScheduler.PARAMETER_COUNT }
                    } ?: d.fsrsParameters,
                    gradingVersion = if (cutsCurrent) prefs[ladderVersionKey] ?: 0 else 0,
                    fsrsFitVersion = prefs[fsrsFitVersionKey] ?: 0
                ).sanitized()
            }
        )
    }

    /** Serializes the seed so two first reads cannot both probe the database. */
    private val seedMutex = Mutex()

    /**
     * A new install starts on correctness-only grading.
     *
     * The rule is the measurement condition for the answer-time suggestion:
     * the dwell of a tinted clock is not free evidence, so a learner who
     * starts with timing on cannot produce that suggestion later without
     * first spending two weeks on correctness-only. Starting here makes the
     * unbiased sample the default path, and the learner turns the rule off
     * once Statistics offers a pair.
     *
     * Freshness is decided once, at first start, from database content by
     * [InstallFreshness]: an install whose database was empty when the app
     * started is new even if the learner then imports a paper in that same
     * run; an install that already held a paper or a review is an upgrade or a
     * restore and keeps its rule. A stored choice always wins, including an
     * explicit `false`: the seed is a default, never an override.
     *
     * Because it writes `correctness_only`, the presence check short-circuits
     * every later read. The edit re-checks the key inside the lambda as well,
     * so a Settings save that lands between the snapshot and the write wins
     * instead of being overwritten by the seed.
     */
    private suspend fun seedFreshInstallRule() = seedMutex.withLock {
        val snapshot = context.dataStore.data.first()
        if (snapshot[correctnessOnlyKey] != null) return@withLock
        if (!installFreshness.isNewInstall()) return@withLock
        context.dataStore.edit { prefs ->
            if (prefs[correctnessOnlyKey] == null) prefs[correctnessOnlyKey] = true
        }
    }

    /** The startup job's one-shot seed; every config read repeats it cheaply. */
    internal suspend fun ensureInstallSeed() = seedFreshInstallRule()

    /**
     * The stored config, with the first-start rule seeded before the first
     * read.
     *
     * This is the non-UI read path: a study pull, a stats load, a Settings
     * one-shot all land here before anything renders. [schedulerConfig] is the
     * collection path and stays pure, so a composable collector can never open
     * the database.
     */
    suspend fun schedulerConfigNow(): com.mcqapp.domain.SchedulerConfig {
        seedFreshInstallRule()
        return schedulerConfig().first()
    }

    suspend fun setSchedulerConfig(config: com.mcqapp.domain.SchedulerConfig) {
        val c = config.sanitized()
        context.dataStore.edit { prefs ->
            prefs[schedKey("max_interval")] = c.maxIntervalDays.toDouble()
            prefs[schedKey("relearn_ms")] = c.relearnMs.toDouble()
            prefs[schedKey("leech_threshold")] = c.leechThreshold.toDouble()
            prefs[desiredRetentionKey] = c.desiredRetention
            prefs[studyModeKey] = c.studyMode.name
            prefs[introduceNewCardsKey] = c.introduceNewCards
            prefs[schedKey("day_start_hour")] = c.dayStartHour.toDouble()
            prefs[correctnessOnlyKey] = c.correctnessOnlyGrading
            prefs[schedKey("fast_seconds")] = c.fastSeconds.toDouble()
            prefs[schedKey("slow_seconds")] = c.slowSeconds.toDouble()
            prefs[gradingRulesVersionKey] = GRADING_RULES_VERSION
        }
        // The weights are written by [writeWeights], the single place that can
        // also bump their fit version in the same edit. A no-op when the
        // caller preserved the stored set (the Settings path does).
        writeWeights(c.fsrsParameters)
    }

    /**
     * The only writer of `fsrs_parameters` (v26). Writes the set and, when it
     * differs from the stored one, bumps `fsrs_fit_version` in the same
     * DataStore edit, so a review can never record a forecast whose weights
     * were never actually in force.
     *
     * [forceVersion] is for a restore (which knows the backup's version) and
     * for resetting to the shipped defaults, which writes version 0 — a reset
     * is not a fit and must not look like one.
     */
    suspend fun writeWeights(parameters: List<Double>, forceVersion: Int? = null) {
        val weights = com.mcqapp.domain.SchedulerConfig(fsrsParameters = parameters)
            .sanitized()
            .fsrsParameters
        val serialized = weights.joinToString(",")
        context.dataStore.edit { prefs ->
            if (prefs[fsrsParametersKey] != serialized) {
                prefs[fsrsParametersKey] = serialized
                val current = prefs[fsrsFitVersionKey] ?: 0
                prefs[fsrsFitVersionKey] = forceVersion ?: (current + 1)
            } else if (forceVersion != null) {
                prefs[fsrsFitVersionKey] = forceVersion
            }
        }
    }

    /** A single-key write of the fitted weights; bumps the fit version. */
    suspend fun setFsrsParameters(parameters: List<Double>) = writeWeights(parameters)

    /**
     * The atomic cut-point write GradingRulesStore owns: the two cuts and the
     * version number land in one DataStore edit, so a crash cannot leave a
     * value without its version. The history row is written afterwards and
     * repaired from these preferences on the next read if it is missing.
     */
    internal suspend fun writeCuts(
        easySeconds: Long,
        slowSeconds: Long,
        gradingVersion: Int,
        correctnessOnly: Boolean = false
    ) {
        context.dataStore.edit { prefs ->
            prefs[schedKey("fast_seconds")] = easySeconds.toDouble()
            prefs[schedKey("slow_seconds")] = slowSeconds.toDouble()
            prefs[ladderVersionKey] = gradingVersion
            prefs[gradingRulesVersionKey] = GRADING_RULES_VERSION
            // The mode travels with the cuts: a version row that says
            // "correctness only" must never coexist with preferences that
            // still grade on the clock, even for the instant before the rest
            // of the config lands.
            prefs[correctnessOnlyKey] = correctnessOnly
        }
    }

    /** The stored grading version, or null when none has been written yet. */
    internal suspend fun gradingVersionOrNull(): Int? =
        context.dataStore.data.map { it[ladderVersionKey] }.first()

    /** Which build of the cut rule the stored pair was saved under (v27). */
    internal suspend fun gradingRulesVersionNow(): Int =
        context.dataStore.data.map { it[gradingRulesVersionKey] ?: 0 }.first()

    /**
     * Resets the pacing knobs only. The fitted FSRS weights are deliberately
     * left alone: they are the learner's own history, and Settings offers a
     * separate "Restore defaults" for dropping them.
     *
     * The grading rule is left alone for the same reason: it is not a pacing
     * knob, and the confirm dialog names only "schedule knobs, target
     * retention, ranking mode and new-card handling". Flipping the rule here
     * would silently switch a correctness-only learner back to a tinted clock,
     * start a new grading epoch and mix the two kinds of dwell in the
     * evidence.
     *
     * @return whether the effective cut pair changed. An unchanged *current*
     *   pair returns `false`: no row is written, the version id stays, and the
     *   calibration epoch does not move — moving a pacing knob cannot end the
     *   sample.
     *
     *   The first reset of a *retired* pre-v27 pair is the exception. That pair
     *   was never in force ([schedulerConfig] had already replaced it with the
     *   shipped 6/60), so it also returns `false` and writes no `manual` row,
     *   but its stale version id is dropped. The next version read therefore
     *   mints a fresh `prior` row at "now", and the epoch **does** move: the
     *   old row's `effectiveFrom` names a pair that no longer grades anything,
     *   so the earliest honest start for the new rule is this moment.
     */
    suspend fun resetSchedulerConfig(): Boolean {
        var pairChanged = false
        context.dataStore.edit { prefs ->
            val c = com.mcqapp.domain.SchedulerConfig().sanitized()
            val cutsCurrent =
                (prefs[gradingRulesVersionKey] ?: 0) >= GRADING_RULES_VERSION
            // The effective pair before the reset, read the same way
            // [schedulerConfig] reads it: a pair saved under the retired
            // reading-allowance rule was never in force, so the shipped 6/60
            // is what the learner was actually grading under.
            val wasFast = if (cutsCurrent) {
                (prefs[schedKey("fast_seconds")] ?: c.fastSeconds.toDouble()).toLong()
            } else {
                c.fastSeconds
            }
            val wasSlow = if (cutsCurrent) {
                (prefs[schedKey("slow_seconds")] ?: c.slowSeconds.toDouble()).toLong()
            } else {
                c.slowSeconds
            }
            // Decided inside this one edit, from the same snapshot that is
            // about to be overwritten: a concurrent save cannot make two
            // separate reads disagree about whether the pair moved.
            pairChanged = cutsCurrent &&
                (wasFast != c.fastSeconds || wasSlow != c.slowSeconds)
            listOf(
                "max_interval" to c.maxIntervalDays.toDouble(),
                "relearn_ms" to c.relearnMs.toDouble(),
                "leech_threshold" to c.leechThreshold.toDouble(),
                "day_start_hour" to c.dayStartHour.toDouble(),
                "fast_seconds" to c.fastSeconds.toDouble(),
                "slow_seconds" to c.slowSeconds.toDouble()
            ).forEach { (name, value) -> prefs[schedKey(name)] = value }
            prefs[desiredRetentionKey] = c.desiredRetention
            prefs[studyModeKey] = c.studyMode.name
            prefs[introduceNewCardsKey] = c.introduceNewCards
            // `correctness_only` is deliberately not written here.
            prefs[gradingRulesVersionKey] = GRADING_RULES_VERSION
            if (!cutsCurrent || pairChanged) {
                // Either the reset changed the pair (the old id names cuts
                // that no longer grade, and the repository records the
                // defaults under a fresh `manual` version) or the id names a
                // retired pre-v27 pair. In both cases the next version read
                // must not resurrect it; an unchanged current pair keeps its
                // id, and with it the epoch.
                prefs.remove(ladderVersionKey)
            }
        }
        return pairChanged
    }

    companion object {
        /**
         * The build of the cut rule the stored pair belongs to. 1 = absolute
         * 6 s / 60 s with no reading allowance; a stored pair from before it
         * is ignored once (see [schedulerConfig]).
         */
        const val GRADING_RULES_VERSION = 1
    }

    private val progressKey = stringPreferencesKey("in_progress_test")

    /** The resume slot: the store's saves are provable against this seam. */
    override suspend fun saveTestProgress(json: String) {
        context.dataStore.edit { it[progressKey] = json }
    }

    override suspend fun loadTestProgress(): String? =
        context.dataStore.data.map { it[progressKey] }.first()

    override suspend fun clearTestProgress() {
        context.dataStore.edit { it.remove(progressKey) }
    }

    /** Clears the resume snapshot if it is for [paperId]. */
    suspend fun discardProgressFor(paperId: String) {
        val raw = loadTestProgress() ?: return
        val snapshot = com.mcqapp.domain.TestSnapshot.fromJson(raw)
        // Unparseable snapshots are dead weight either way.
        if (snapshot == null || snapshot.paperId == paperId) {
            Logger.i("REPO", "Discarding in-progress snapshot for $paperId")
            clearTestProgress()
        }
    }

    // --- Backup: every preference, generically ---

    /**
     * Every stored preference with its type, for the backup's settings.json.
     * Keyed by name rather than typed keys so a newly added preference
     * rides along without this file changing; unknown value types are left
     * out rather than half-captured.
     */
    suspend fun snapshot(): McqBackupFormat.SettingsFile {
        val prefs = context.dataStore.data.first().asMap()
        val out = LinkedHashMap<String, McqBackupFormat.PreferenceEntry>()
        for ((key, value) in prefs) {
            // The resume slot is session state, not a setting. Exporting it
            // would let a settings restore overwrite a newer live snapshot
            // with whatever was in progress when the backup was taken.
            // The install marker describes this installation, not the learner,
            // so it must not travel to another one either.
            if (key.name == progressKey.name ||
                key.name == InstallFreshness.MARKER_KEY.name
            ) {
                continue
            }
            val entry = when (value) {
                is Boolean -> McqBackupFormat.PreferenceEntry("boolean", JsonPrimitive(value))
                is Double -> McqBackupFormat.PreferenceEntry("double", JsonPrimitive(value))
                is Float -> McqBackupFormat.PreferenceEntry("float", JsonPrimitive(value))
                is Int -> McqBackupFormat.PreferenceEntry("int", JsonPrimitive(value))
                is Long -> McqBackupFormat.PreferenceEntry("long", JsonPrimitive(value))
                is String -> McqBackupFormat.PreferenceEntry("string", JsonPrimitive(value))
                else -> continue
            }
            out[key.name] = entry
        }
        return McqBackupFormat.SettingsFile(out)
    }

    /**
     * Replaces every preference with the backup's values: the restore is a
     * full replace, so a setting changed since the backup does not survive
     * it. Values that fail to decode keep their current (about to be
     * cleared) state out — a malformed entry is skipped, never fatal.
     *
     * The first-start answer is invalidated before the clearing edit, not
     * after it: the database for a full backup has already been replaced by
     * the caller, so a config read in the window between `clear()` and the
     * restored keys must re-ask the restored database, not reuse the
     * pre-restore decision and seed correctness-only over restored history.
     * A throw later in the restore leaves the answer invalidated too, which
     * is the safe direction (the next read re-probes).
     */
    suspend fun restore(settings: McqBackupFormat.SettingsFile) {
        // The weights ride a separate edit through [writeWeights], because the
        // fit version has to move with them; every other preference is a
        // straight replace.
        installFreshness.invalidate()
        var restoredWeights: List<Double>? = null
        var restoredFitVersion: Int? = null
        context.dataStore.edit { prefs ->
            // The resume snapshot is restored separately, and only after this
            // call. Clearing it here would destroy a live snapshot on a
            // settings-only restore, so preserve it across the replace.
            val progress = prefs[progressKey]
            prefs.clear()
            if (progress != null) prefs[progressKey] = progress
            for ((name, entry) in settings.preferences) {
                if (name == progressKey.name ||
                    name == InstallFreshness.MARKER_KEY.name
                ) {
                    continue
                }
                val value = entry.value
                if (name == fsrsParametersKey.name) {
                    val text = (value as? JsonPrimitive)?.content ?: continue
                    restoredWeights = text.split(',').mapNotNull { it.toDoubleOrNull() }
                        .takeIf { it.size == com.mcqapp.domain.FsrsScheduler.PARAMETER_COUNT }
                    continue
                }
                if (name == fsrsFitVersionKey.name) {
                    restoredFitVersion = (value as? JsonPrimitive)?.intOrNull
                    continue
                }
                when (entry.type) {
                    "boolean" -> (value as? JsonPrimitive)?.booleanOrNull?.let {
                        prefs[booleanPreferencesKey(name)] = it
                    }
                    "double" -> (value as? JsonPrimitive)?.doubleOrNull?.let {
                        prefs[doublePreferencesKey(name)] = it
                    }
                    "float" -> (value as? JsonPrimitive)?.floatOrNull?.let {
                        prefs[floatPreferencesKey(name)] = it
                    }
                    "int" -> (value as? JsonPrimitive)?.intOrNull?.let {
                        prefs[intPreferencesKey(name)] = it
                    }
                    "long" -> (value as? JsonPrimitive)?.longOrNull?.let {
                        prefs[longPreferencesKey(name)] = it
                    }
                    "string" -> (value as? JsonPrimitive)?.let {
                        prefs[stringPreferencesKey(name)] = it.content
                    }
                }
            }
        }
        // A pre-v26 backup carries weights but no fit version. Version 0 is
        // the honest label there: the backup cannot say which fit produced
        // them, and claiming a bump would invent provenance.
        writeWeights(
            restoredWeights ?: com.mcqapp.domain.FsrsScheduler.DEFAULT_FSRS_PARAMETERS,
            forceVersion = restoredFitVersion ?: 0
        )
        // The restore replaced both the preferences and (for a full backup)
        // the database. [installFreshness] was invalidated before the clear
        // above; [McqRepository.settingsRestore] invalidates it again in a
        // `finally` so a throw after this point cannot leave the pre-restore
        // answer cached either.
    }
}
