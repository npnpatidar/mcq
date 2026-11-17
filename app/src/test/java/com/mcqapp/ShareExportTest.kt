package com.mcqapp

import com.mcqapp.util.ImportTooLargeException
import com.mcqapp.util.copyBounded
import com.mcqapp.util.filesToSweep
import com.mcqapp.util.nextSharedFile
import com.mcqapp.util.renderToTempThenCopy
import com.mcqapp.util.sanitizeFileName
import com.mcqapp.util.sweepSharedDir
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

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

    /**
     * An OutputStream that accepts [limit] bytes then fails, used to simulate
     * both a render that dies part-way and a SAF copy that dies part-way.
     */
    private class FailingAfter(val limit: Int) : OutputStream() {
        val received = ByteArrayOutputStream()
        var written = 0
            private set

        override fun write(b: Int) {
            if (written >= limit) fail()
            received.write(b)
            written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val room = limit - written
            if (room <= 0) fail()
            val take = minOf(room, len)
            received.write(b, off, take)
            written += take
            if (take < len) fail()
        }

        private fun fail(): Nothing =
            throw IOException("simulated write failure after $written bytes")
    }

    @Test
    fun renderFailureLeavesNoPartialDocumentAtUri_andDeletesTemp() = runBlocking {
        val cacheDir = tmp.newFolder("cache-render-fail")
        val partial = ByteArray(64) { 0x41 }
        var uriOpened = false
        val uriOut = ByteArrayOutputStream()
        val boom = IOException("simulated mid-render failure")

        val error = runCatching {
            renderToTempThenCopy<String>(
                cacheDir = cacheDir,
                openUriOutput = { _: String -> uriOpened = true; uriOut },
                render = { sink ->
                    sink.write(partial)
                    sink.flush()
                    throw boom
                }
            )
        }.exceptionOrNull()

        assertTrue("original error must be reported", error === boom)
        assertFalse("URI must never be opened when the render fails", uriOpened)
        assertEquals("no partial content may reach the SAF URI", 0, uriOut.size())
        assertTrue("temp file must be deleted", cacheDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun successCopiesExactBytes_andDeletesTemp() = runBlocking {
        val cacheDir = tmp.newFolder("cache-success")
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val uriOut = ByteArrayOutputStream()
        var opens = 0

        val result = renderToTempThenCopy(
            cacheDir = cacheDir,
            openUriOutput = { _: String -> opens++; uriOut },
            render = { sink ->
                sink.write(bytes)
                "paper.pdf"
            }
        )

        assertEquals("paper.pdf", result)
        assertArrayEquals("success path must be byte-identical", bytes, uriOut.toByteArray())
        assertEquals(1, opens)
        assertTrue("temp file must be deleted", cacheDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun uriCopyFailureReopensWithTruncate_andDeletesTemp() = runBlocking {
        val cacheDir = tmp.newFolder("cache-copy-fail")
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val failing = FailingAfter(3)
        val modes = mutableListOf<String>()
        var opens = 0

        val error = runCatching {
            renderToTempThenCopy(
                cacheDir = cacheDir,
                openUriOutput = { mode ->
                    modes += mode
                    opens++
                    if (opens == 1) failing else ByteArrayOutputStream()
                },
                render = { sink -> sink.write(bytes); "paper.zip" }
            )
        }.exceptionOrNull()

        assertTrue("copy error must be reported", error is IOException)
        assertEquals(3, failing.written)
        // Truncation is provider-dependent, so assert the request rather than
        // the emptiness of a fresh fake stream: both the initial open and the
        // best-effort cleanup reopen must ask for "wt".
        assertEquals(listOf("wt", "wt"), modes)
        assertEquals(2, opens)
        assertTrue("temp file must be deleted", cacheDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun concurrentExportsUseDistinctTempFiles() = runBlocking {
        val cacheDir = tmp.newFolder("cache-concurrent")
        val aBytes = ByteArray(512) { 0x41 }
        val bBytes = ByteArray(512) { 0x42 }
        val outA = ByteArrayOutputStream()
        val outB = ByteArrayOutputStream()

        awaitAll(
            async {
                renderToTempThenCopy(cacheDir, { _: String -> outA }, render = { sink ->
                    for (b in aBytes) { sink.write(b.toInt()); yield() }
                    "a"
                })
            },
            async {
                renderToTempThenCopy(cacheDir, { _: String -> outB }, render = { sink ->
                    for (b in bBytes) { sink.write(b.toInt()); yield() }
                    "b"
                })
            }
        )

        assertArrayEquals(aBytes, outA.toByteArray())
        assertArrayEquals(bBytes, outB.toByteArray())
        assertTrue("all temp files must be deleted", cacheDir.listFiles().isNullOrEmpty())
    }
}
