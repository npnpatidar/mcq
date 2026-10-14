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
internal class SettingsStore(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme_mode")

    fun themeMode(): Flow<String> =
        context.dataStore.data.map { it[themeKey] ?: "system" }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[themeKey] = mode }
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

    private val pdfTwoColumnKey = booleanPreferencesKey("pdf_two_column")

    fun pdfTwoColumn(): Flow<Boolean> =
        context.dataStore.data.map { it[pdfTwoColumnKey] ?: false }

    suspend fun setPdfTwoColumn(enabled: Boolean) {
        context.dataStore.edit { it[pdfTwoColumnKey] = enabled }
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
                slowSeconds = (prefs[schedKey("slow_seconds")] ?: d.slowSeconds.toDouble()).toLong()
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
    }

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

    suspend fun saveTestProgress(json: String) {
        context.dataStore.edit { it[progressKey] = json }
    }

    suspend fun loadTestProgress(): String? =
        context.dataStore.data.map { it[progressKey] }.first()

    suspend fun clearTestProgress() {
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
        context.dataStore.edit { prefs ->
            prefs.clear()
            for ((name, entry) in settings.preferences) {
                val value = entry.value
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
    }
}
