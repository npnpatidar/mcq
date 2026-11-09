package com.mcqapp

import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.ui.library.PickedArchive
import com.mcqapp.ui.library.classifyPickedArchive
import com.mcqapp.ui.library.isZipMagic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A file shared to the app is routed by its content, not its MIME type or
 * extension (a provider hands over whatever it likes) and not its `SEND`
 * type. The `PK` sniff decides zip versus JSON; the archive's own entries
 * decide docx versus backup versus Anki, exactly as the picker's sniff does.
 */
class SendClassificationTest {

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

    @Test
    fun jsonStartsAreNotZips() {
        assertFalse(isZipMagic("{\"version\":1}".toByteArray()))
        assertFalse(isZipMagic("[{\"id\":1}]".toByteArray()))
        assertFalse(isZipMagic(ByteArray(0)))
        assertFalse(isZipMagic(byteArrayOf('P'.code.toByte())))
        assertFalse(isZipMagic("PK".toByteArray().copyOf(1)))
    }

    @Test
    fun pkSignaturesAreZips() {
        assertTrue(isZipMagic(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0, 0)))
        assertTrue(isZipMagic(zipOf("collection.anki2" to byteArrayOf(1))))
    }

    @Test
    fun aSharedWordBankIsAWordBank() {
        val docx = zipOf(
            "word/document.xml" to "<w:document/>".toByteArray(),
            "docProps/core.xml" to "<core/>".toByteArray()
        )
        assertTrue(isZipMagic(docx))
        assertEquals(PickedArchive.DOCX, classifyPickedArchive(docx))
    }

    @Test
    fun aSharedLibraryBackupIsRefusedAsAnImport() {
        val backup = McqBackupFormat.write(
            manifest = McqBackupFormat.Manifest(papers = 1, questions = 2),
            content = McqFileDto(version = 1),
            study = McqBackupFormat.StudyFile(),
            settings = McqBackupFormat.SettingsFile()
        )
        assertTrue(isZipMagic(backup))
        assertEquals(PickedArchive.BACKUP, classifyPickedArchive(backup))
    }

    @Test
    fun aSharedAnkiDeckIsAnotherArchive() {
        val apkg = zipOf(
            "collection.anki2" to ByteArray(64).also { it[0] = 1 },
            "media" to "[]".toByteArray()
        )
        assertTrue(isZipMagic(apkg))
        assertEquals(PickedArchive.OTHER, classifyPickedArchive(apkg))
    }

    @Test
    fun plainTextIsNeverAnArchive() {
        val text = "hello world".toByteArray()
        assertFalse(isZipMagic(text))
        assertEquals(PickedArchive.OTHER, classifyPickedArchive(text))
    }
}
