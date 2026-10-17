package com.mcqapp

import com.mcqapp.util.ImportTooLargeException
import com.mcqapp.util.MAX_IMPORT_BYTES
import com.mcqapp.util.boundedStream
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
    fun boundedStreamHandsTheBytesThroughWhileCountingThem() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val read = boundedStream(ByteArrayInputStream(bytes), limit = 8192).readBytes()
        assertArrayEquals(bytes, read)
    }

    @Test
    fun boundedStreamRefusesTheBytePastTheLimit() {
        // The same cap readBounded applies, applied as the bytes go past
        // instead of after they have all been collected: a restore reads a
        // stream it never keeps.
        val e = runCatching {
            boundedStream(ByteArrayInputStream(ByteArray(4096)), limit = 1024).readBytes()
        }.exceptionOrNull()
        assertTrue("expected ImportTooLargeException, got $e", e is ImportTooLargeException)
        assertEquals(1024L, (e as ImportTooLargeException).limit)
    }

    @Test
    fun boundedStreamStopsAnEndlessStream() {
        val e = runCatching {
            boundedStream(EndlessStream(), limit = 2000).readBytes()
        }.exceptionOrNull()
        assertTrue("expected ImportTooLargeException, got $e", e is ImportTooLargeException)
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

    /**
     * The cap is unavoidable without streaming the parser, but the message can
     * at least say how far over the file is and why pictures are often the
     * cause, rather than repeating the limit.
     */
    @Test
    fun theTooLargeMessageSaysHowBigAndWhy() {
        val limit = com.mcqapp.util.MAX_IMPORT_BYTES
        val message = com.mcqapp.util.tooLargeMessage(atLeastBytes = 97L * 1024 * 1024, limit = limit)
        assertTrue("should name the limit: $message", message.contains("64 MB"))
        assertTrue("should size the file: $message", message.contains("97 MB"))
        assertTrue("should mention pictures: $message", message.contains("pictures"))
    }

    @Test
    fun anUnknownSizeStillReadsAsAtLeastOneMegabyte() {
        val message = com.mcqapp.util.tooLargeMessage(atLeastBytes = 1024L, limit = 64L * 1024 * 1024)
        assertTrue("should not say 0 MB: $message", message.contains("at least 1 MB"))
    }

    @Test
    fun prefixReadsASmallStreamWhole() {
        val bytes = ByteArray(3000) { (it % 251).toByte() }
        val prefix = com.mcqapp.util.readPrefix(ByteArrayInputStream(bytes), limit = 4096)
        assertArrayEquals(bytes, prefix.bytes)
        assertTrue("nothing followed", !prefix.truncated)
    }

    @Test
    fun prefixReadsExactlyTheLimitWhole() {
        val bytes = ByteArray(4096) { 7 }
        val prefix = com.mcqapp.util.readPrefix(ByteArrayInputStream(bytes), limit = 4096)
        assertArrayEquals(bytes, prefix.bytes)
        assertTrue("exactly the limit is not truncated", !prefix.truncated)
    }

    @Test
    fun prefixStopsAtTheLimitAndSaysMoreFollowed() {
        val bytes = ByteArray(9000) { (it % 251).toByte() }
        val prefix = com.mcqapp.util.readPrefix(ByteArrayInputStream(bytes), limit = 4096)
        assertEquals(4096, prefix.bytes.size)
        assertTrue("more followed", prefix.truncated)
        assertArrayEquals(bytes.copyOf(4096), prefix.bytes)
    }

    @Test
    fun prefixDoesNotAllocateAnEndlessStream() {
        val prefix = com.mcqapp.util.readPrefix(EndlessStream(), limit = 128 * 1024)
        assertEquals(128 * 1024, prefix.bytes.size)
        assertTrue(prefix.truncated)
    }

    @Test
    fun theExceptionCarriesHowMuchWasRead() {
        val e = try {
            readBounded(java.io.ByteArrayInputStream(ByteArray(4096)), limit = 1024)
            throw AssertionError("expected the cap to be enforced")
        } catch (thrown: com.mcqapp.util.ImportTooLargeException) {
            thrown
        }
        assertTrue("should report at least the limit", e.atLeastBytes > 1024)
    }
}
