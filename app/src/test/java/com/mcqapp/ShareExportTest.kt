package com.mcqapp

import com.mcqapp.util.ImportTooLargeException
import com.mcqapp.util.copyBounded
import com.mcqapp.util.filesToSweep
import com.mcqapp.util.nextSharedFile
import com.mcqapp.util.sanitizeFileName
import com.mcqapp.util.sweepSharedDir
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Share files live under `cacheDir/shared` through `FileProvider`, so their
 * names are mangled by [sanitizeFileName] and their lifetime is managed by
 * [filesToSweep]. Both are pure, so they are pinned without Android.
 */
class ShareExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fileAt(dir: File, name: String, modified: Long, bytes: ByteArray = "x".toByteArray()): File {
        val file = File(dir, name)
        java.io.FileOutputStream(file).use { fos -> fos.write(bytes) }
        file.setLastModified(modified)
        return file
    }

    @Test
    fun sanitisesIllegalCharacters() {
        assertEquals("paper_selection_.pdf", sanitizeFileName("paper (selection).pdf"))
        assertEquals("Quiz.pdf", sanitizeFileName("mcq-report/Quiz.pdf"))
        assertEquals("c.txt", sanitizeFileName("a b\\c.txt"))
        assertEquals("physics-bank.json", sanitizeFileName("physics-bank.json"))
    }

    @Test
    fun sanitisesPathsAndBlankNames() {
        assertEquals("c.pdf", sanitizeFileName("a/b/c.pdf"))
        assertEquals("c.pdf", sanitizeFileName("..\\.\\c.pdf"))
        assertEquals("export", sanitizeFileName(""))
        assertEquals("export", sanitizeFileName("..."))
        assertEquals("export", sanitizeFileName("///"))
    }

    @Test
    fun trimsLeadingDotsAndCapsLength() {
        assertEquals("hidden", sanitizeFileName(".hidden"))
        assertEquals("hidden", sanitizeFileName("..hidden.."))
        val long = "a".repeat(250) + ".json"
        assertEquals(100, sanitizeFileName(long).length)
    }

    @Test
    fun sweepKeepsFreshFiles() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val dir = tmp.newFolder("shared")
        fileAt(dir, "old1.json", now - 8 * day)
        fileAt(dir, "old2.json", now - 9 * day)
        fileAt(dir, "new.json", now - 1 * day)
        assertEquals(listOf("old1.json", "old2.json"), filesToSweep(dir, now).map { it.name }.sorted())
    }

    @Test
    fun sweepKeepsTheNewestEvenWhenAllAreOld() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val dir = tmp.newFolder("shared")
        fileAt(dir, "a.json", now - 9 * day)
        fileAt(dir, "b.json", now - 8 * day)
        fileAt(dir, "c.json", now - 30 * day)
        assertEquals(listOf("a.json", "c.json"), filesToSweep(dir, now).map { it.name }.sorted())
    }

    @Test
    fun sweepLeavesASingleFileAlone() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val dir = tmp.newFolder("shared")
        fileAt(dir, "only.json", now - 30 * day)
        assertEquals(emptyList<File>(), filesToSweep(dir, now))
        assertEquals(0, sweepSharedDir(dir, now))
        assertTrue(File(dir, "only.json").exists())
    }

    @Test
    fun sweepDeletesExpiredExceptNewest() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val dir = tmp.newFolder("shared")
        fileAt(dir, "keep.json", now - 6 * day)
        fileAt(dir, "delete.json", now - 20 * day)
        fileAt(dir, "new.json", now)
        assertEquals(1, sweepSharedDir(dir, now))
        assertTrue(File(dir, "keep.json").exists())
        assertTrue(File(dir, "new.json").exists())
        assertTrue(!File(dir, "delete.json").exists())
    }

    @Test
    fun sweepToleratesAMissingDirectory() {
        val missing = File(tmp.root, "no-such-dir")
        assertEquals(emptyList<File>(), filesToSweep(missing, 0L))
        assertEquals(0, sweepSharedDir(missing, 0L))
    }

    @Test
    fun nextSharedFileSweepsFirstAndSanitises() {
        val cacheDir = tmp.newFolder("cache")
        val now = 1_700_000_000_000L
        val shared = File(cacheDir, "shared").apply { mkdirs() }
        fileAt(shared, "ancient.pdf", now - 30 * 24 * 60 * 60 * 1000L)
        fileAt(shared, "recent.pdf", now)
        val file = nextSharedFile(cacheDir, "Physics Bank (selection).pdf", now)
        assertEquals(File(shared, "Physics_Bank_selection_.pdf"), file)
        assertTrue(!File(shared, "ancient.pdf").exists())
        assertTrue(File(shared, "recent.pdf").exists())
        assertTrue(file.parentFile?.name == "shared")
    }

    @Test
    fun copyBoundedCopiesExactly() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val copied = copyBounded(ByteArrayInputStream(bytes), out, limit = 8192)
        assertEquals(bytes.size.toLong(), copied)
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun copyBoundedRefusesPastTheLimit() {
        val out = ByteArrayOutputStream()
        val error = runCatching {
            copyBounded(ByteArrayInputStream(ByteArray(4096)), out, limit = 1024)
        }.exceptionOrNull()
        assertTrue("expected ImportTooLargeException, got $error", error is ImportTooLargeException)
        assertEquals(1024L, (error as ImportTooLargeException).limit)
    }

    @Test
    fun copyBoundedCopiesAnEmptyStream() {
        val out = ByteArrayOutputStream()
        assertEquals(0L, copyBounded(ByteArrayInputStream(ByteArray(0)), out, limit = 1024))
        assertEquals(0, out.size())
    }
}
