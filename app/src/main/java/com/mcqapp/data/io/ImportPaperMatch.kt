package com.mcqapp.data.io

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.PaperEntity

/**
 * How an incoming paper attached to a stored row.
 *
 * @property paper the stored row the import writes into.
 * @property byTitle the incoming id was unknown, so the title decided it.
 * @property keepStoredTiming the stored paper holds no questions, so it is a
 * container the user made (the import preview's title field suggests existing
 * titles for exactly this) or a shell a failed import left behind: the import
 * fills it but keeps the duration and negative marking typed by hand.
 */
internal data class ImportPaperMatch(
    val paper: PaperEntity,
    val byTitle: Boolean,
    val keepStoredTiming: Boolean
)

/**
 * Paper identity for an import: the id first, then the title.
 *
 * The title is the destination the user picks in the import preview — typing or
 * selecting a name there means "put these questions in that paper", and a name
 * the library has never seen becomes a new paper. So a same-title paper is
 * adopted even when the incoming id is stable and different: that is the
 * hand-made container being filled, not a duplicate deck. When two papers share
 * the title, the one that already holds questions wins
 * ([com.mcqapp.data.local.PaperDao.getByTitleForImport]).
 *
 * A blank title never matches: an anonymous paper cannot be claimed by name.
 */
internal suspend fun matchImportedPaper(
    db: AppDatabase,
    incomingId: String,
    title: String
): ImportPaperMatch? {
    db.paperDao().getById(incomingId)?.let {
        // Same row as a previous import: the file owns its metadata.
        return ImportPaperMatch(it, byTitle = false, keepStoredTiming = false)
    }
    if (title.isBlank()) return null
    val stored = db.paperDao().getByTitleForImport(title) ?: return null
    val empty = db.questionDao().countByPaper(stored.id) == 0
    return ImportPaperMatch(stored, byTitle = true, keepStoredTiming = empty)
}
