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

/**
 * What to tell someone who picked a backup for import. It names the button in
 * Settings that can actually restore it, so the message leads somewhere
 * instead of only refusing.
 *
 * Composed here, like the import-size message, because it travels through the
 * shared error dialog as a string: a `stringResource(...)` has to sit in a
 * `Text`-like sink (StringResourceUsageTest enforces it, after a regression
 * that blanked every label), which a message built for a ViewModel is not.
 * The button's name is therefore literal, and [PickedArchiveTest] pins it to
 * `restore_backup` in strings.xml so the two cannot drift apart unnoticed.
 */
internal fun backupNotABankMessage(): String =
    "This is a library backup, not a question bank. " +
        "Restore it from Settings → Restore backup."
