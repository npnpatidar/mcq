package com.mcqapp

import com.mcqapp.data.SafeZip
import com.mcqapp.data.ZipLimitException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The `.docx` and `.apkg` readers extract archives supplied by the user, so
 * extraction is bounded: a few kilobytes of highly compressible data must not
 * be allowed to expand until the process dies.
 */
class SafeZipTest {

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
    fun extractsAnOrdinaryArchive() {
        val zip = zipOf("word/document.xml" to "<p/>".toByteArray(), "media/1.png" to byteArrayOf(1, 2, 3))
        val out = SafeZip.unzip(zip)
        assertEquals(setOf("word/document.xml", "media/1.png"), out.keys)
        assertEquals("<p/>", String(out.getValue("word/document.xml")))
        assertEquals(3, out.getValue("media/1.png").size)
    }

    @Test
    fun anEmptyArchiveYieldsNothing() {
        assertEquals(0, SafeZip.unzip(ByteArray(0)).size)
    }

    @Test
    fun skipsDirectoryEntries() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("media/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("media/1.png"))
            zip.write(byteArrayOf(7))
            zip.closeEntry()
        }
        assertEquals(setOf("media/1.png"), SafeZip.unzip(out.toByteArray()).keys)
    }

    @Test
    fun refusesTooManyEntries() {
        val entries = (0 until SafeZip.MAX_ENTRIES + 5).map { "f$it.txt" to ByteArray(1) }.toTypedArray()
        val e = runCatching { SafeZip.unzip(zipOf(*entries)) }.exceptionOrNull()
        assertTrue("expected ZipLimitException, got $e", e is ZipLimitException)
        assertTrue(e!!.message!!.contains("more than ${SafeZip.MAX_ENTRIES}"))
    }

    @Test
    fun acceptsExactlyTheEntryLimit() {
        val entries = (0 until SafeZip.MAX_ENTRIES).map { "f$it.txt" to ByteArray(1) }.toTypedArray()
        assertEquals(SafeZip.MAX_ENTRIES, SafeZip.unzip(zipOf(*entries)).size)
    }

    @Test
    fun refusesAnEntryThatExpandsPastTheCap() {
        // 200 MB of zeroes compresses to a few hundred kilobytes: the classic
        // zip bomb shape, and the reason limits count decompressed bytes.
        val bomb = ByteArray(200 * 1024 * 1024)
        val zip = zipOf("payload.bin" to bomb)
        assertTrue("the archive itself is small: ${zip.size}", zip.size < 1024 * 1024)
        val e = runCatching { SafeZip.unzip(zip) }.exceptionOrNull()
        assertTrue("expected ZipLimitException, got $e", e is ZipLimitException)
        assertTrue(e!!.message!!.contains("expands to more than"))
    }

    @Test
    fun limitsAreSane() {
        assertEquals(4096, SafeZip.MAX_ENTRIES)
        assertEquals(64L * 1024 * 1024, SafeZip.MAX_ENTRY_BYTES)
        assertEquals(256L * 1024 * 1024, SafeZip.MAX_TOTAL_BYTES)
    }
}
