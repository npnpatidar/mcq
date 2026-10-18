package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.TestProgressEntity
import com.mcqapp.domain.TestAnswer
import com.mcqapp.domain.TestSnapshot
import com.mcqapp.util.Logger

/**
 * The resume snapshot and the answers that belong to it.
 *
 * A session's *header* — scope, order seeds, index, timers — is a few hundred
 * bytes and lives in DataStore. Its *answers* are `test_progress` rows, written
 * one small upsert per action, so answering costs what the answer changed rather
 * than a rewrite of everything answered so far. Callers see one thing: [snapshot]
 * is the header, [offer] is the header joined with its rows, [clear] drops both.
 *
 * A snapshot written before the rows existed (or restored from a backup) still
 * carries its answers in the document; [adopt] writes them as rows when it
 * resumes, so they survive the next answer instead of being dropped.
 *
 * Rows are keyed by `(paperId, questionId)`, so they cannot tell two *scopes* of
 * one paper apart — a whole-paper session and a mistakes-only one, say. Only
 * one session is saved at a time (the header slot is global), so the pair is
 * enough to decide when rows are leftovers; [discardStale] is what the caller
 * uses, and it deliberately leaves a same-paper document alone rather than
 * deleting answers an open screen did not create.
 */
internal class TestProgressStore(
    private val db: AppDatabase,
    private val settings: SettingsStore
) {
    /**
     * The saved session header, or null when nothing is in progress. A document
     * that cannot be read is dropped here: it would otherwise be offered — and
     * re-parsed — on every load of the screen.
     */
    suspend fun snapshot(): TestSnapshot? {
        val raw = settings.loadTestProgress() ?: return null
        val parsed = TestSnapshot.fromJson(raw)
        if (parsed == null) {
            Logger.w("REPO", "Discarding an unreadable resume snapshot")
            settings.clearTestProgress()
        }
        return parsed
    }

    /** Writes the header and the questions that just changed. */
    suspend fun save(snapshot: TestSnapshot, changed: List<TestAnswer>) {
        if (changed.isNotEmpty()) writeRows(snapshot.paperId, changed)
        settings.saveTestProgress(snapshot.header().toJson())
    }

    /** The header joined with the answers stored for it — the resume offer. */
    suspend fun offer(paperId: String): TestSnapshot? =
        snapshot()?.withAnswers(rows(paperId))

    /** Writes a legacy snapshot's inline answers as rows; nothing for a row-based one. */
    suspend fun adopt(snapshot: TestSnapshot) {
        val answers = snapshot.answers()
        if (answers.isEmpty()) return
        Logger.i("REPO", "Adopting ${answers.size} inline answers of ${snapshot.paperId}")
        writeRows(snapshot.paperId, answers)
    }

    /**
     * Drops the header and the answers of the paper it names. A paper's own
     * deletion needs no help — the rows cascade with the paper row — so this is
     * for submit, discard and the dead snapshots a load can find.
     */
    suspend fun clear() {
        snapshot()?.paperId?.let { db.testProgressDao().deleteForPaper(it) }
        settings.clearTestProgress()
    }

    /**
     * Drops [paperId]'s answers when no session of it is saved. The slot is
     * global, so a paper whose document has been overwritten by another's can
     * only hold leftovers — merging them into its next session would show
     * answers the reader never gave there.
     */
    suspend fun discardStale(paperId: String) {
        db.testProgressDao().deleteForPaper(paperId)
    }

    /** The backup's copy of the session: one legacy-shaped document. */
    suspend fun forBackup(): String? {
        val saved = snapshot() ?: return null
        return saved.withAnswers(rows(saved.paperId)).toJson()
    }

    /**
     * Puts a backup's document back, verbatim, and drops the rows it supersedes:
     * the document is the newer truth, and its own answers are adopted as rows
     * when the session is next offered. A null document clears the slot.
     */
    suspend fun restore(document: String?) {
        if (document == null) {
            clear()
            return
        }
        TestSnapshot.fromJson(document)?.let { db.testProgressDao().deleteForPaper(it.paperId) }
        settings.saveTestProgress(document)
    }

    private suspend fun rows(paperId: String): List<TestAnswer> =
        db.testProgressDao().getForPaper(paperId).map { it.toAnswer() }

    /**
     * Written in chunks: adopting a 250k-answer session is one pass, but never
     * one 250k-row statement.
     */
    private suspend fun writeRows(paperId: String, answers: List<TestAnswer>) {
        answers.chunked(ANSWER_CHUNK).forEach { chunk ->
            db.testProgressDao().upsertAll(chunk.map { it.toEntity(paperId) })
        }
    }

    private companion object {
        const val ANSWER_CHUNK = 500
    }
}

private fun TestProgressEntity.toAnswer() = TestAnswer(
    questionId = questionId,
    selection = selection.split(",").filter { it.isNotBlank() }.toSet(),
    revealed = revealed,
    flagged = flagged,
    dwellSeconds = dwellSeconds
)

private fun TestAnswer.toEntity(paperId: String) = TestProgressEntity(
    paperId = paperId,
    questionId = questionId,
    // Comma-joined like question_results.selectedOptionIds: one list, one format.
    selection = selection.joinToString(","),
    revealed = revealed,
    flagged = flagged,
    dwellSeconds = dwellSeconds
)
