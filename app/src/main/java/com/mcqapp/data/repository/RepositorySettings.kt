package com.mcqapp.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

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

    // --- Scheduler (Anki-parity) settings ---
    // Stored as doubles keyed by field name so a newly added option defaults
    // cleanly on an old install instead of reading back as 0.

    private fun schedKey(name: String) = doublePreferencesKey("anki_$name")

    private fun schedBoolKey(name: String) = booleanPreferencesKey("anki_$name")

    fun schedulerConfig(): Flow<com.mcqapp.domain.SchedulerConfig> =
        context.dataStore.data.map { prefs ->
            val d = com.mcqapp.domain.SchedulerConfig()
            com.mcqapp.domain.SchedulerConfig(
                defaultEase = prefs[schedKey("default_ease")] ?: d.defaultEase,
                minEase = prefs[schedKey("min_ease")] ?: d.minEase,
                maxEase = prefs[schedKey("max_ease")] ?: d.maxEase,
                againEaseFactor = prefs[schedKey("again_ease")] ?: d.againEaseFactor,
                hardEaseFactor = prefs[schedKey("hard_ease")] ?: d.hardEaseFactor,
                easyEaseFactor = prefs[schedKey("easy_ease")] ?: d.easyEaseFactor,
                firstIntervalDays = (prefs[schedKey("first_interval")] ?: d.firstIntervalDays.toDouble()).toInt(),
                secondIntervalDays = (prefs[schedKey("second_interval")] ?: d.secondIntervalDays.toDouble()).toInt(),
                easyFirstIntervalDays = (prefs[schedKey("easy_first_interval")] ?: d.easyFirstIntervalDays.toDouble()).toInt(),
                hardIntervalMultiplier = prefs[schedKey("hard_multiplier")] ?: d.hardIntervalMultiplier,
                easyBonus = prefs[schedKey("easy_bonus")] ?: d.easyBonus,
                minimumIntervalDays = (prefs[schedKey("min_interval")] ?: d.minimumIntervalDays.toDouble()).toInt(),
                maxIntervalDays = (prefs[schedKey("max_interval")] ?: d.maxIntervalDays.toDouble()).toInt(),
                relearnMs = (prefs[schedKey("relearn_ms")] ?: d.relearnMs.toDouble()).toLong(),
                leechThreshold = (prefs[schedKey("leech_threshold")] ?: d.leechThreshold.toDouble()).toInt(),
                newLimit = (prefs[schedKey("new_limit")] ?: d.newLimit.toDouble()).toInt(),
                reviewLimit = (prefs[schedKey("review_limit")] ?: d.reviewLimit.toDouble()).toInt(),
                newCardsIgnoreReviewLimit = prefs[schedBoolKey("new_ignore_review_limit")] ?: d.newCardsIgnoreReviewLimit,
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
            prefs[schedKey("default_ease")] = c.defaultEase
            prefs[schedKey("min_ease")] = c.minEase
            prefs[schedKey("max_ease")] = c.maxEase
            prefs[schedKey("again_ease")] = c.againEaseFactor
            prefs[schedKey("hard_ease")] = c.hardEaseFactor
            prefs[schedKey("easy_ease")] = c.easyEaseFactor
            prefs[schedKey("first_interval")] = c.firstIntervalDays.toDouble()
            prefs[schedKey("second_interval")] = c.secondIntervalDays.toDouble()
            prefs[schedKey("easy_first_interval")] = c.easyFirstIntervalDays.toDouble()
            prefs[schedKey("hard_multiplier")] = c.hardIntervalMultiplier
            prefs[schedKey("easy_bonus")] = c.easyBonus
            prefs[schedKey("min_interval")] = c.minimumIntervalDays.toDouble()
            prefs[schedKey("max_interval")] = c.maxIntervalDays.toDouble()
            prefs[schedKey("relearn_ms")] = c.relearnMs.toDouble()
            prefs[schedKey("leech_threshold")] = c.leechThreshold.toDouble()
            prefs[schedKey("new_limit")] = c.newLimit.toDouble()
            prefs[schedKey("review_limit")] = c.reviewLimit.toDouble()
            prefs[schedBoolKey("new_ignore_review_limit")] = c.newCardsIgnoreReviewLimit
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
                        "default_ease" to c.defaultEase,
                        "min_ease" to c.minEase,
                        "max_ease" to c.maxEase,
                        "again_ease" to c.againEaseFactor,
                        "hard_ease" to c.hardEaseFactor,
                        "easy_ease" to c.easyEaseFactor,
                        "first_interval" to c.firstIntervalDays.toDouble(),
                        "second_interval" to c.secondIntervalDays.toDouble(),
                        "easy_first_interval" to c.easyFirstIntervalDays.toDouble(),
                        "hard_multiplier" to c.hardIntervalMultiplier,
                        "easy_bonus" to c.easyBonus,
                        "min_interval" to c.minimumIntervalDays.toDouble(),
                        "max_interval" to c.maxIntervalDays.toDouble(),
                        "relearn_ms" to c.relearnMs.toDouble(),
                        "leech_threshold" to c.leechThreshold.toDouble(),
                        "new_limit" to c.newLimit.toDouble(),
                        "review_limit" to c.reviewLimit.toDouble(),
                        "day_start_hour" to c.dayStartHour.toDouble(),
                        "fast_seconds" to c.fastSeconds.toDouble(),
                        "slow_seconds" to c.slowSeconds.toDouble()
                    ).forEach { (name, value) -> prefs[schedKey(name)] = value }
                    // Boolean, so it cannot ride along in the numeric list above.
                    prefs[schedBoolKey("new_ignore_review_limit")] = c.newCardsIgnoreReviewLimit
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
}
