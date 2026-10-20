package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.TestProgressEntity
import com.mcqapp.domain.TestAnswer
import com.mcqapp.domain.TestSnapshot
import com.mcqapp.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
/**
 * The resume slot the header document lives in. A seam: the store's saves must
 * be provably serialised, and a fake slot can record (and delay, deterministically,
 * under the test's control) exactly what the real DataStore write hides.
 */
internal interface TestProgressSlot {
    suspend fun saveTestProgress(json: String)
    suspend fun loadTestProgress(): String?
    suspend fun clearTestProgress()
}

internal class TestProgressStore(
    private val db: AppDatabase,
    private val settings: TestProgressSlot
) {
    /**
     * Serialises every write that pairs rows with the header document. Two
     * [save]s a few hundred ms apart used to interleave freely — both suspend
     * between the row write and the DataStore header write — so the saved
     * header could end up one question behind the rows around it; on a crash
     * in that gap the resume offer is a session one step back. A paper's
     * [restore] joins the same lock, since it replaces the same pair, and so
     * does [clear]: a clear landing between another save's row write and its
     * header left a header with no rows, resurrecting a session whose answers
     * the user had already wiped.
     */
    private val writeLock = Mutex()

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

    /** Writes the header and the questions that just changed, serialised. */
    suspend fun save(snapshot: TestSnapshot, changed: List<TestAnswer>) = writeLock.withLock {
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
     *
     * Joins [writeLock]: it replaces exactly the pair [save] writes, and a
     * clear landing between another save's rows and its header left that
     * header standing over no rows. [restore(null)] reaches this locked body
     * from outside any lock it holds, so there is no self-deadlock.
     */
    suspend fun clear() = writeLock.withLock {
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
     * when the session is next offered. A null document clears the slot, via
     * the locked [clear] — this call holds no lock at that point, so the
     * delegation cannot self-deadlock. Joins [writeLock] either way: it
     * replaces the same rows-then-header pair [save] writes, and must not land
     * between another save's rows and its header.
     *
     * A document that cannot parse never reaches the slot: the parse guard used
     * to protect only the ROW delete, while the overwrite ran unconditionally —
     * restoring a backup with a truncated/corrupt inProgressTest destroyed the
     * live session's good header (and abandoned its rows), and the next load
     * then discarded the damaged string. A live session stays live.
     */
    suspend fun restore(document: String?) = if (document == null) {
        clear()
    } else {
        writeLock.withLock {
            val parsed = TestSnapshot.fromJson(document)
            if (parsed == null) {
                Logger.w(
                    "REPO",
                    "Refusing a corrupt inProgressTest document from the backup; " +
                        "the live resume snapshot stays"
                )
            } else {
                db.testProgressDao().deleteForPaper(parsed.paperId)
                settings.saveTestProgress(document)
            }
        }
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
