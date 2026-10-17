package com.mcqapp.data.io

import android.content.Context

/**
 * How far a streaming import got, persisted so an interrupted one is visible
 * after the process that ran it is gone. A re-run is safe because it continues
 * over what is already written (the importer skips questions that already
 * exist), so the journal is a record, not a resume pointer.
 */
data class ImportRunRecord(
    val sourceKey: String,
    val questionsSeen: Int,
    val totalQuestions: Int,
    val startedAt: Long
)

interface ImportRunJournal {
    fun begin(sourceKey: String, totalQuestions: Int, startedAt: Long)
    fun progress(questionsSeen: Int)
    fun clear()
    fun current(): ImportRunRecord?
}

/**
 * A single in-progress import, kept in its own preferences file. Writes are
 * `apply()`: the value is a hint for the user, and losing the very last batch's
 * count to process death is exactly the case this is meant to survive.
 */
class SharedPrefsImportRunJournal(context: Context) : ImportRunJournal {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun begin(sourceKey: String, totalQuestions: Int, startedAt: Long) {
        prefs.edit()
            .putString(KEY_SOURCE, sourceKey)
            .putInt(KEY_SEEN, 0)
            .putInt(KEY_TOTAL, totalQuestions)
            .putLong(KEY_STARTED, startedAt)
            .apply()
    }

    override fun progress(questionsSeen: Int) {
        prefs.edit().putInt(KEY_SEEN, questionsSeen).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    override fun current(): ImportRunRecord? {
        val source = prefs.getString(KEY_SOURCE, null) ?: return null
        return ImportRunRecord(
            sourceKey = source,
            questionsSeen = prefs.getInt(KEY_SEEN, 0),
            totalQuestions = prefs.getInt(KEY_TOTAL, 0),
            startedAt = prefs.getLong(KEY_STARTED, 0L)
        )
    }

    private companion object {
        const val PREFS = "import_run"
        const val KEY_SOURCE = "source_key"
        const val KEY_SEEN = "questions_seen"
        const val KEY_TOTAL = "total"
        const val KEY_STARTED = "started_at"
    }
}

/**
 * The notice shown when a previous streaming import of the same file stopped
 * before it finished. It deliberately avoids the word "skipped": that word
 * routes a warning to the "content was dropped" panel.
 */
fun interruptedImportNotice(
    record: ImportRunRecord?,
    sourceKey: String?,
    totalQuestions: Int
): String? {
    if (record == null || sourceKey == null) return null
    if (record.sourceKey != sourceKey) return null
    if (record.questionsSeen >= totalQuestions) return null
    return "A previous import of this file stopped after ${record.questionsSeen} of " +
        "$totalQuestions questions. Running it again is safe: it continues over " +
        "whatever was already written."
}
