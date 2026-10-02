package com.mcqapp

import com.mcqapp.util.ImportTooLargeException
import com.mcqapp.util.MAX_IMPORT_BYTES
import com.mcqapp.util.readBounded
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BoundedReadTest {

    /** A stream that lies about its size, like a provider reporting the whole file. */
    private class EndlessStream : InputStream() {
        private var served = 0L
        override fun read(): Int {
            served++
            return 'x'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            served += len
            return len
        }

        override fun available(): Int = Int.MAX_VALUE
    }

    @Test
    fun readsASmallFileIntact() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        assertArrayEquals(bytes, readBounded(ByteArrayInputStream(bytes)))
    }

    @Test
    fun readsAnEmptyStream() {
        assertEquals(0, readBounded(ByteArrayInputStream(ByteArray(0))).size)
    }

    @Test
    fun readsExactlyTheLimit() {
        val bytes = ByteArray(2048) { 7 }
        assertArrayEquals(bytes, readBounded(ByteArrayInputStream(bytes), limit = 2048))
    }

    @Test
    fun refusesAFileOverTheLimit() {
        val e = runCatching { readBounded(ByteArrayInputStream(ByteArray(4096)), limit = 1024) }
            .exceptionOrNull()
        assertTrue("expected ImportTooLargeException, got $e", e is ImportTooLargeException)
        assertEquals(1024L, (e as ImportTooLargeException).limit)
    }

    @Test
    fun anEndlessStreamIsStoppedByTheLimit() {
        // Regression guard for the real failure: readBytes() sizes itself from
        // available(), so a provider claiming the file is huge made the app
        // allocate until it died.
        val e = runCatching { readBounded(EndlessStream(), limit = 128 * 1024) }.exceptionOrNull()
        assertTrue("expected ImportTooLargeException, got $e", e is ImportTooLargeException)
    }

    @Test
    fun theDefaultLimitIsGenerousButBounded() {
        assertEquals(64L * 1024 * 1024, MAX_IMPORT_BYTES)
    }
}
