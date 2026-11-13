package com.mcqapp.data.repository

import android.content.Context
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
 */
internal class SettingsStore(private val context: Context) : TestProgressSlot {

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

    private val automaticLadderAdaptationKey =
        booleanPreferencesKey("automatic_ladder_adaptation")

    /**
     * Plan phase 5: apply the ladder policy by itself at the end of a sitting.
     * Off by default. The policy only ever proposes when its readiness and
     * follow-up gates pass, but a switch that changes grades without asking
     * starts off regardless.
     */
    fun automaticLadderAdaptation(): Flow<Boolean> =
        context.dataStore.data.map { it[automaticLadderAdaptationKey] ?: false }

    suspend fun automaticLadderAdaptationNow(): Boolean =
        context.dataStore.data.map { it[automaticLadderAdaptationKey] ?: false }.first()

    suspend fun setAutomaticLadderAdaptation(enabled: Boolean) {
        context.dataStore.edit { it[automaticLadderAdaptationKey] = enabled }
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

    // CSV of the 34 FSRS-7 weights rather than 34 numeric keys: the set is
    // written and read as one value, and a malformed list (wrong length, junk
    // tokens) falls back to the defaults in SchedulerConfig.sanitized.
    private val fsrsParametersKey = stringPreferencesKey("fsrs_parameters")

    /** Which weight set `fsrs_parameters` belongs to (v26). 0 = shipped defaults. */
    private val fsrsFitVersionKey = intPreferencesKey("fsrs_fit_version")

    /** The FSRS model version the stored weights belong to (v27). 0 = FSRS-6. */
    private val fsrsModelVersionKey = intPreferencesKey("fsrs_model_version")

    /** One-shot "your fitted FSRS-6 weights were retired" flag (v27). */
    private val fsrs7NoticeKey = booleanPreferencesKey("fsrs7_upgrade_notice")

    /** The last `review_log.rowid` the FSRS-7 rebuild finished (v27). */
    private val fsrsRebuildCursorKey = longPreferencesKey("fsrs_rebuild_cursor")

    /** Whether the FSRS-7 card-state rebuild finished (v27). */
    private val fsrsRebuildDoneKey = booleanPreferencesKey("fsrs_rebuild_done")

    /** The ladder version the cut points belong to (v26). */
    private val ladderVersionKey = intPreferencesKey("ladder_version")

    fun schedulerConfig(): Flow<com.mcqapp.domain.SchedulerConfig> =
        context.dataStore.data.map { prefs ->
            val d = com.mcqapp.domain.SchedulerConfig()
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
                fastSeconds = (prefs[schedKey("fast_seconds")] ?: d.fastSeconds.toDouble()).toLong(),
                slowSeconds = (prefs[schedKey("slow_seconds")] ?: d.slowSeconds.toDouble()).toLong(),
                fsrsParameters = prefs[fsrsParametersKey]?.let { stored ->
                    stored.split(',')
                        .mapNotNull { token -> token.toDoubleOrNull() }
                        .takeIf { it.size == com.mcqapp.domain.FsrsScheduler.PARAMETER_COUNT }
                } ?: d.fsrsParameters,
                ladderVersion = prefs[ladderVersionKey] ?: 0,
                fsrsFitVersion = prefs[fsrsFitVersionKey] ?: 0
            ).sanitized()
        }

    suspend fun schedulerConfigNow(): com.mcqapp.domain.SchedulerConfig =
        schedulerConfig().first()

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
            prefs[schedKey("fast_seconds")] = c.fastSeconds.toDouble()
            prefs[schedKey("slow_seconds")] = c.slowSeconds.toDouble()
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
            // Any set written from this build is FSRS-7 shaped, including the
            // defaults written by a restore or a reset.
            prefs[fsrsModelVersionKey] = FSRS_MODEL_VERSION
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

    // --- FSRS-7 model migration (v27) ---

    /**
     * One-time model migration, run before the first scheduler read on an
     * upgraded install.
     *
     * FSRS-6's 21 weights cannot be converted: the parameterisation changed,
     * not just the count (the two stability traces and the mixture curve reuse
     * the slots differently). The only honest outcomes are the FSRS-7 defaults
     * or a refit, so this stores the defaults, resets `fsrs_fit_version` to 0
     * so no review claims a fit under a model that was never fitted, and raises
     * the one-shot notice **only** when the learner actually had a fit to lose.
     * A set that is already 34 values long (a restored v27 backup) is kept as
     * is and only stamped with the model version.
     */
    suspend fun ensureFsrs7() {
        context.dataStore.edit { prefs ->
            if ((prefs[fsrsModelVersionKey] ?: 0) >= FSRS_MODEL_VERSION) return@edit
            val parsed = prefs[fsrsParametersKey]
                ?.split(',')
                ?.mapNotNull { it.toDoubleOrNull() }
            if (parsed != null &&
                parsed.size == com.mcqapp.domain.FsrsScheduler.PARAMETER_COUNT
            ) {
                prefs[fsrsModelVersionKey] = FSRS_MODEL_VERSION
                return@edit
            }
            // An absent or malformed set was already running defaults; only a
            // well-formed, non-default 21-value set was a fit worth telling the
            // learner about.
            val hadFit = parsed != null && parsed.isNotEmpty() &&
                parsed != FSRS6_DEFAULT_PARAMETERS
            prefs[fsrsParametersKey] =
                com.mcqapp.domain.FsrsScheduler.DEFAULT_FSRS_PARAMETERS.joinToString(",")
            prefs[fsrsFitVersionKey] = 0
            if (hadFit) prefs[fsrs7NoticeKey] = true
            prefs[fsrsModelVersionKey] = FSRS_MODEL_VERSION
        }
    }

    /** The stored FSRS model version; 0 means the pre-FSRS-7 build. */
    suspend fun fsrsModelVersion(): Int =
        context.dataStore.data.map { it[fsrsModelVersionKey] ?: 0 }.first()

    /**
     * The last review-log id the FSRS-7 card rebuild finished, or 0 when it has
     * not started. Persisted so a process kill resumes instead of restarting a
     * million-row replay.
     */
    suspend fun fsrsRebuildCursor(): Long =
        context.dataStore.data.map { it[fsrsRebuildCursorKey] ?: 0L }.first()

    internal suspend fun setFsrsRebuildCursor(cursor: Long) {
        context.dataStore.edit { it[fsrsRebuildCursorKey] = cursor }
    }

    /** Stamps the rebuild complete and clears its cursor in one edit. */
    internal suspend fun completeFsrs7Rebuild() {
        context.dataStore.edit { prefs ->
            prefs[fsrsRebuildDoneKey] = true
            prefs[fsrsRebuildCursorKey] = 0L
            prefs[fsrsModelVersionKey] = FSRS_MODEL_VERSION
        }
    }

    /** Whether the card-state rebuild has already run to completion. */
    internal suspend fun fsrs7RebuildDone(): Boolean =
        context.dataStore.data.map { it[fsrsRebuildDoneKey] ?: false }.first()

    /** Whether the one-shot FSRS-7 migration notice is still waiting. */
    fun fsrs7Notice(): Flow<Boolean> =
        context.dataStore.data.map { it[fsrs7NoticeKey] ?: false }

    suspend fun dismissFsrs7Notice() {
        context.dataStore.edit { it.remove(fsrs7NoticeKey) }
    }

    /**
     * The atomic cut-point write LadderStore owns: the two cuts and the
     * version number land in one DataStore edit, so a crash cannot leave a
     * value without its version. The history row is written afterwards and
     * repaired from these preferences on the next read if it is missing.
     */
    internal suspend fun writeCuts(
        easySeconds: Long,
        slowSeconds: Long,
        ladderVersion: Int
    ) {
        context.dataStore.edit { prefs ->
            prefs[schedKey("fast_seconds")] = easySeconds.toDouble()
            prefs[schedKey("slow_seconds")] = slowSeconds.toDouble()
            prefs[ladderVersionKey] = ladderVersion
        }
    }

    /** The stored ladder version, or null when none has been written yet. */
    internal suspend fun ladderVersionOrNull(): Int? =
        context.dataStore.data.map { it[ladderVersionKey] }.first()

    /**
     * Resets the pacing knobs only. The fitted FSRS weights are deliberately
     * left alone: they are the learner's own history, and Settings offers a
     * separate "Restore defaults" for dropping them.
     */
    suspend fun resetSchedulerConfig() {
        context.dataStore.edit { prefs ->
            com.mcqapp.domain.SchedulerConfig()
                .sanitized()
                .let { c ->
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
                }
        }
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
            if (key.name == progressKey.name) continue
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
     */
    suspend fun restore(settings: McqBackupFormat.SettingsFile) {
        // The weights ride a separate edit through [writeWeights], because the
        // fit version has to move with them; every other preference is a
        // straight replace.
        var restoredWeights: List<Double>? = null
        var restoredFitVersion: Int? = null
        var restoredRetiredFit = false
        context.dataStore.edit { prefs ->
            // The resume snapshot is restored separately, and only after this
            // call. Clearing it here would destroy a live snapshot on a
            // settings-only restore, so preserve it across the replace.
            val progress = prefs[progressKey]
            prefs.clear()
            if (progress != null) prefs[progressKey] = progress
            for ((name, entry) in settings.preferences) {
                if (name == progressKey.name) continue
                val value = entry.value
                if (name == fsrsParametersKey.name) {
                    val text = (value as? JsonPrimitive)?.content ?: continue
                    val parsed = text.split(',').mapNotNull { it.toDoubleOrNull() }
                    if (parsed.size == com.mcqapp.domain.FsrsScheduler.PARAMETER_COUNT) {
                        restoredWeights = parsed
                    } else if (parsed.isNotEmpty() && parsed != FSRS6_DEFAULT_PARAMETERS) {
                        // A fitted FSRS-6 set in the backup cannot be converted
                        // either; the notice says so after the restore.
                        restoredRetiredFit = true
                    }
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
        if (restoredRetiredFit) {
            context.dataStore.edit { it[fsrs7NoticeKey] = true }
        }
    }

    private companion object {
        /** FSRS-7 is model version 7; anything lower predates the two traces. */
        const val FSRS_MODEL_VERSION = 7

        /**
         * The FSRS-6 weights this build shipped. Kept only to tell a learner's
         * fit apart from a set that was never touched, so the migration can
         * stay silent when there was nothing to lose.
         */
        val FSRS6_DEFAULT_PARAMETERS = listOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722,
            0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729,
            0.5425, 0.0912, 0.0658, 0.1542
        )
    }
}
