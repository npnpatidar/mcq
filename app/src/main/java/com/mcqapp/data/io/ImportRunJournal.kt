package com.mcqapp.data.io

import android.content.Context

/**
 * How far a streaming import got, persisted so an interrupted one is visible
 * after the process that ran it is gone. A re-run is safe because the whole run
 * is one transaction: a failed run rolls every batch back, so no changes were
 * saved and a retry starts fresh. The journal is a record, not a resume pointer.
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
 * before it finished. It is advisory — an interrupted run saved nothing and a
 * retry starts fresh. Severity is now carried explicitly by the warning model,
 * so no wording rule (such as avoiding "skipped") is needed to route it.
 */
fun interruptedImportNotice(
    record: ImportRunRecord?,
    sourceKey: String?,
    totalQuestions: Int
): String? {
    if (record == null || sourceKey == null) return null
    if (record.sourceKey != sourceKey) return null
    // Compare against the total captured when that run began. The file may
    // have been replaced since (same URI, different contents), and mixing the
    // old count with the new total would either suppress the notice or print
    // a progress figure for the wrong file.
    if (totalQuestions > 0 && record.totalQuestions != totalQuestions) return null
    val recordedTotal = record.totalQuestions.takeIf { it > 0 } ?: totalQuestions
    if (recordedTotal <= 0 || record.questionsSeen >= recordedTotal) return null
    return "A previous import of this file stopped after ${record.questionsSeen} of " +
        "$recordedTotal questions. No changes were saved; running again starts fresh."
}
