package com.mcqapp.ui.library

import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.docx.isDocxArchive

/**
 * What the file a user picked for "Import questions" turned out to be.
 *
 * Backups are in here because that button used to hand one to the Anki reader,
 * which answered with an Anki-shaped error about a file that was never an Anki
 * package. A backup replaces the whole library and is restored from Settings;
 * the import button only adds papers to what is already there.
 */
internal enum class PickedArchive {
    /** A Word bank. */
    DOCX,

    /** One of this app's library backups: restored from Settings, not imported. */
    BACKUP,

    /** Anything else: an Anki package. */
    OTHER
}

/**
 * Decides from the archive's own bytes, not from its name or extension (a
 * provider may hand over any of them). Cheap: each test stops as soon as it
 * has seen what it looks for.
 */
internal fun classifyPickedArchive(bytes: ByteArray): PickedArchive = when {
    isDocxArchive(bytes) -> PickedArchive.DOCX
    McqBackupFormat.isBackupArchive(bytes) -> PickedArchive.BACKUP
    else -> PickedArchive.OTHER
}
