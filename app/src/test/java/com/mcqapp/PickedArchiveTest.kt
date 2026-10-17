package com.mcqapp

import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.ui.library.PickedArchive
import com.mcqapp.ui.library.backupNotABankMessage
import com.mcqapp.ui.library.classifyPickedArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Import questions" must never treat a library backup as a bank. Restoring is
 * the reverse of exporting — it replaces the library — while the import button
 * only adds papers, so a backup picked there would either drop everything
 * behind paper 1 or wipe the library by accident. The picker used to hand one
 * to the Anki reader, which answered with an Anki-shaped error about a file
 * that was never an Anki package.
 *
 * The decision is made from the bytes, because a provider hands over whatever
 * name and extension it likes, and it is made cheaply: the archive is only
 * scanned while it still looks like ours.
 */
class PickedArchiveTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A backup written by the app itself, via the same writer the screen uses. */
    private fun backup(): ByteArray = McqBackupFormat.write(
        manifest = McqBackupFormat.Manifest(papers = 1, questions = 2),
        content = McqFileDto(version = 1),
        study = McqBackupFormat.StudyFile(),
        settings = McqBackupFormat.SettingsFile()
    )

    private fun manifestJson(manifest: McqBackupFormat.Manifest): ByteArray =
        McqBackupFormat.json.encodeToString(McqBackupFormat.Manifest.serializer(), manifest)
            .toByteArray()

    /** Pulls the parts out of a written backup, so they can be re-packed. */
    private fun partsOf(zip: ByteArray): Map<String, ByteArray> {
        val parts = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zip)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                parts[entry.name] = input.readBytes()
                entry = input.nextEntry
            }
        }
        return parts
    }

    @Test
    fun aLibraryBackupIsNotOfferedToTheImporter() {
        val bytes = backup()
        assertTrue(McqBackupFormat.isBackupArchive(bytes))
        assertEquals(PickedArchive.BACKUP, classifyPickedArchive(bytes))
    }

    @Test
    fun aRepackedBackupIsStillRecognisedWhateverOrderItIsIn() {
        val parts = partsOf(backup())
        val rePacked = zipOf(
            McqBackupFormat.STUDY to parts.getValue(McqBackupFormat.STUDY),
            McqBackupFormat.SETTINGS to parts.getValue(McqBackupFormat.SETTINGS),
            McqBackupFormat.CONTENT to parts.getValue(McqBackupFormat.CONTENT),
            McqBackupFormat.MANIFEST to parts.getValue(McqBackupFormat.MANIFEST)
        )
        assertEquals(PickedArchive.BACKUP, classifyPickedArchive(rePacked))
    }

    /**
     * A file manager's "compress" can prepend its own small notes before the
     * files the user selected.
     */
    @Test
    fun aBackupRepackedByAFileManagerIsStillRecognised() {
        val parts = partsOf(backup())
        val rePacked = zipOf(
            "__MACOSX/._content.json" to ByteArray(120),
            McqBackupFormat.MANIFEST to parts.getValue(McqBackupFormat.MANIFEST),
            McqBackupFormat.CONTENT to parts.getValue(McqBackupFormat.CONTENT),
            McqBackupFormat.STUDY to parts.getValue(McqBackupFormat.STUDY),
            McqBackupFormat.SETTINGS to parts.getValue(McqBackupFormat.SETTINGS)
        )
        assertEquals(PickedArchive.BACKUP, classifyPickedArchive(rePacked))
    }

    /**
     * The scan walks past a bank of any size, because it is ours by name, so a
     * re-pack that put one in front of the manifest is still a backup. Only a
     * *large* member that is not ours stops it — an Anki package stays cheap.
     */
    @Test
    fun aRepackedBackupWithABankInFrontOfTheManifestIsStillRecognised() {
        val parts = partsOf(backup())
        val bank = ByteArray(2 * 1024 * 1024).also { java.util.Random(2).nextBytes(it) }
        val rePacked = zipOf(
            McqBackupFormat.CONTENT to bank,
            McqBackupFormat.STUDY to parts.getValue(McqBackupFormat.STUDY),
            McqBackupFormat.MANIFEST to parts.getValue(McqBackupFormat.MANIFEST)
        )
        assertTrue(McqBackupFormat.isBackupArchive(rePacked))
        assertEquals(PickedArchive.BACKUP, classifyPickedArchive(rePacked))
    }

    /**
     * The other half of that rule, pinned on purpose: the first member that is
     * not ours ends the scan, so an Anki package or a media-heavy Word file is
     * never inflated to be told it is not a backup — and a hybrid that hides
     * our manifest behind somebody else's file reads as somebody else's
     * archive, a shape no re-pack tool produces. (The member's own size field
     * cannot separate the two: a zip written with a data descriptor, which is
     * what Java's ZipOutputStream writes, reports every size as -1.)
     */
    @Test
    fun aForeignMemberEndsTheScanBeforeTheManifest() {
        val manifest = partsOf(backup()).getValue(McqBackupFormat.MANIFEST)
        // Large, like a real collection.
        val large = zipOf(
            "collection.anki2" to ByteArray(2 * 1024 * 1024)
                .also { java.util.Random(3).nextBytes(it) },
            McqBackupFormat.MANIFEST to manifest
        )
        assertFalse(McqBackupFormat.isBackupArchive(large))
        assertEquals(PickedArchive.OTHER, classifyPickedArchive(large))

        // And tiny, which the size field could never have told apart.
        val tiny = zipOf(
            "readme.txt" to "notes the user left beside the backup".toByteArray(),
            McqBackupFormat.MANIFEST to manifest
        )
        assertEquals(PickedArchive.OTHER, classifyPickedArchive(tiny))
    }

    @Test
    fun aWordBankIsStillAWordBank() {
        val docx = zipOf(
            "[Content_Types].xml" to "<Types/>".toByteArray(),
            "word/document.xml" to "<w:document/>".toByteArray()
        )
        assertEquals(PickedArchive.DOCX, classifyPickedArchive(docx))
        assertFalse(McqBackupFormat.isBackupArchive(docx))
    }

    @Test
    fun anAnkiPackageIsLeftToTheAnkiReader() {
        val apkg = zipOf(
            "collection.anki2" to ByteArray(2 * 1024 * 1024),
            "media" to "{}".toByteArray(),
            "0" to byteArrayOf(1, 2, 3)
        )
        assertEquals(PickedArchive.OTHER, classifyPickedArchive(apkg))
        assertFalse(McqBackupFormat.isBackupArchive(apkg))
    }

    @Test
    fun aBankJsonIsNotAnArchiveAtAll() {
        val json = """{"version":1,"papers":[{"id":"p1"}]}""".toByteArray()
        assertEquals(PickedArchive.OTHER, classifyPickedArchive(json))
        assertFalse(McqBackupFormat.isBackupArchive(json))
    }

    /**
     * A zip with a `manifest.json` is not enough: it has to be *our* manifest.
     * An Anki package carries one too, in its own shape.
     */
    @Test
    fun someoneElsesManifestIsNotABackup() {
        val ankiManifest = zipOf(
            "manifest.json" to """{"package":"anki","version":2}""".toByteArray(),
            "collection.anki2" to ByteArray(16)
        )
        assertEquals(PickedArchive.OTHER, classifyPickedArchive(ankiManifest))
        assertFalse(McqBackupFormat.isBackupArchive(ankiManifest))

        val truncated = zipOf("manifest.json" to """{"format": "mcqapp-back""".toByteArray())
        assertEquals(PickedArchive.OTHER, classifyPickedArchive(truncated))
        assertFalse(McqBackupFormat.isBackupArchive(truncated))
    }

    @Test
    fun anEmptyOrUnreadablePickIsNotABackup() {
        assertFalse(McqBackupFormat.isBackupArchive(ByteArray(0)))
        assertFalse(McqBackupFormat.isBackupArchive(byteArrayOf(1, 2, 3, 4, 5)))
        assertFalse(McqBackupFormat.isBackupArchive("not a zip".toByteArray()))
        assertEquals(PickedArchive.OTHER, classifyPickedArchive(ByteArray(0)))
        assertEquals(PickedArchive.OTHER, classifyPickedArchive("not a zip".toByteArray()))
    }

    @Test
    fun aTakenApartBackupWithNoManifestIsNotABackup() {
        val parts = partsOf(backup())
        val noManifest = zipOf(
            McqBackupFormat.CONTENT to parts.getValue(McqBackupFormat.CONTENT),
            McqBackupFormat.STUDY to parts.getValue(McqBackupFormat.STUDY)
        )
        assertEquals(PickedArchive.OTHER, classifyPickedArchive(noManifest))
        assertFalse(McqBackupFormat.isBackupArchive(noManifest))
    }

    @Test
    fun aBackupOfANewerVersionIsStillABackup() {
        // It has to be refused by the restore screen with its "newer app
        // version" message, not sent to the Anki reader.
        val newer = zipOf(
            McqBackupFormat.MANIFEST to manifestJson(
                McqBackupFormat.Manifest(version = McqBackupFormat.VERSION + 1)
            ),
            McqBackupFormat.CONTENT to "{}".toByteArray()
        )
        assertEquals(PickedArchive.BACKUP, classifyPickedArchive(newer))
    }

    /**
     * The screen checks the backup question on the bounded prefix it reads
     * first, because a backup with images in it is often over the size a whole
     * import is allowed to be — and "too large to import" is the wrong thing
     * to tell someone who picked a file that was never meant to be imported.
     * The manifest is the archive's first entry, so the prefix always holds it.
     */
    @Test
    fun anOverLargeBackupIsStillRecognisedFromItsPrefix() {
        val manifest = partsOf(backup()).getValue(McqBackupFormat.MANIFEST)
        val unmovable = ByteArray(5 * 1024 * 1024).also { java.util.Random(1).nextBytes(it) }
        val big = zipOf(
            McqBackupFormat.MANIFEST to manifest,
            "an-image-inside-the-bank.bin" to unmovable
        )
        val prefix = com.mcqapp.util.readPrefix(
            big.inputStream(),
            com.mcqapp.ui.importscreen.JSON_STREAM_THRESHOLD_BYTES.toLong()
        )
        assertTrue("prefix must be cut short for this test to mean anything", prefix.truncated)
        assertTrue(McqBackupFormat.isBackupArchive(prefix.bytes))
        assertEquals(PickedArchive.BACKUP, classifyPickedArchive(prefix.bytes))
    }

    /**
     * The refusal names the Settings button in the words that are on that
     * screen. The message is composed in Kotlin — it travels through the shared
     * error dialog as a string, which StringResourceUsageTest (rightly) does
     * not accept as a rendered sink — so the name is pinned to strings.xml
     * here instead, and drift fails the build rather than misleading a user.
     */
    @Test
    fun theRefusalNamesTheButtonThatCanRestoreIt() {
        val xml = File("src/main/res/values/strings.xml").readText()
        val label = Regex("""<string name="restore_backup">(.*?)</string>""")
            .find(xml)?.groupValues?.get(1)
            ?: error("restore_backup is not defined in strings.xml")
        val message = backupNotABankMessage()
        assertTrue("the refusal must point at Settings: $message", message.contains("Settings"))
        assertTrue("the refusal must name the button '$label': $message", message.contains(label))
    }
}
