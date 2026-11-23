package com.mcqapp.data.backup

import com.mcqapp.util.Logger
import java.io.OutputStream

/**
 * Writes a backup into a document the picker just created and drops that
 * document if anything fails.
 *
 * `backupAll` used to delete the picked file only for the "grading settings
 * moved" refusal. A write that failed halfway (a size cap, a full disk, a
 * provider that closed the stream) left a truncated zip at the URI that the
 * learner could later pick as a backup and find unrestorable. Every failure
 * after [open] succeeds now deletes it — the document is ours, so a retry can
 * produce a fresh one.
 *
 * Pure apart from [Logger], so the guarantee is testable without Android: the
 * caller supplies the open and delete actions.
 *
 * @return false when [open] returned null (no document was written, so there is
 *   nothing to drop); true when the write and [verify] both succeeded.
 */
internal suspend fun writeBackupOrDrop(
    open: () -> OutputStream?,
    delete: () -> Unit,
    write: suspend (OutputStream) -> Unit,
    verify: suspend () -> Unit = {}
): Boolean {
    val output = open() ?: return false
    try {
        output.use { out -> write(out) }
        verify()
        return true
    } catch (e: Throwable) {
        runCatching { delete() }.onFailure {
            Logger.w("SETTINGS", "Could not drop a failed backup", it)
        }
        throw e
    }
}
