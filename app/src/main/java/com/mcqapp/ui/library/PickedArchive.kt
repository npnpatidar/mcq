package com.mcqapp.ui.library

import com.mcqapp.data.backup.McqBackupFormat

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
 * Decides what a picked zip is by streaming its entry names, without reading
 * the archive whole. The same decision the byte overload makes, for a file too
 * large to hold: a Word bank is its `word/document.xml` entry, a backup its
 * `manifest.json`, anything else is an Anki package.
 *
 * Both checks share one pass. The docx check looks over the first few entries —
 * a docx names `word/document.xml` early — and a hit wins outright, exactly as
 * `isDocxArchive` runs before `McqBackupFormat.isBackupArchive`. A manifest is
 * only a backup when every entry walked up to it could have been ours, the rule
 * `isBackupArchive` applies so a file manager's re-pack is still recognised and
 * a foreign archive is not.
 */
internal fun classifyPickedArchive(input: java.io.InputStream): PickedArchive {
    var docx = false
    var manifestFormat: String? = null
    var manifestReachable = false
    var foreignSeen = false
    try {
        java.util.zip.ZipInputStream(input.buffered()).use { zip ->
            var checked = 0
            var entry = zip.nextEntry
            while (entry != null && checked < DOCX_SCAN_ENTRIES) {
                if (!entry.isDirectory) {
                    when {
                        // A docx is decided by its document entry; nothing
                        // later changes that, and stopping avoids inflating the
                        // rest — the byte check returns true at the same point.
                        entry.name == "word/document.xml" -> {
                            docx = true
                            break
                        }
                        entry.name == McqBackupFormat.MANIFEST -> {
                            val text = com.mcqapp.util.readBounded(
                                zip,
                                limit = McqBackupFormat.MANIFEST_LIMIT_BYTES
                            ).toString(Charsets.UTF_8)
                            manifestFormat = McqBackupFormat.manifestFormat(text)
                            manifestReachable = !foreignSeen
                        }
                        !McqBackupFormat.isBackupMemberName(entry.name) -> foreignSeen = true
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
                checked++
            }
        }
    } catch (e: Exception) {
        // A truncated or unreadable archive keeps whatever the entries walked
        // so far support — the same way the byte checks swallow the exception
        // and answer from the prefix they managed to read. A backup cut short
        // after its manifest is still a backup.
    }
    return when {
        docx -> PickedArchive.DOCX
        manifestReachable && manifestFormat == McqBackupFormat.FORMAT -> PickedArchive.BACKUP
        else -> PickedArchive.OTHER
    }
}

/** Entries a docx or a backup names its marker within, matching the byte checks. */
private const val DOCX_SCAN_ENTRIES = 12

/**
 * Decides from the archive's own bytes, not from its name or extension (a
 * provider may hand over any of them). Cheap: each test stops as soon as it
 * has seen what it looks for. Delegates to the streamed classifier so there is
 * one set of rules.
 */
internal fun classifyPickedArchive(bytes: ByteArray): PickedArchive =
    classifyPickedArchive(java.io.ByteArrayInputStream(bytes))

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
