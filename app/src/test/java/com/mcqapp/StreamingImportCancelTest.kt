package com.mcqapp

import android.content.Context
import androidx.room.Room
import com.mcqapp.data.io.StreamingImporter
import com.mcqapp.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * A consumer that throws mid-import must stop the parser, not leave it
 * offering into a queue nobody reads while it burns through the rest of the
 * file. The consumer dies, but its parser keeps its own lifecycle: the
 * interrupt the finally issues re-arms on every dispatch, and a read blocked
 * inside the parser is not interruptible at all — closing the source stream
 * is what stops both the drain and the blocked read.
 *
 * Robolectric + the real database: the streaming session writes batches to
 * Room, so the consumer loop only runs where the database exists. This suite
 * is part of the CI-only set on Linux aarch64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingImportCancelTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    /** Counts every byte the parser pulls, without owning its lifecycle. */
    private class CountingInputStream(private val inner: FileInputStream) : InputStream() {
        val bytesRead = AtomicLong(0)

        override fun read(): Int {
            val r = inner.read()
            if (r >= 0) bytesRead.incrementAndGet()
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = inner.read(b, off, len)
            if (r > 0) bytesRead.addAndGet(r.toLong())
            return r
        }

        override fun close() = inner.close()
    }

    /**
     * A bank far wider than any bounded read-ahead: ~300k questions at ~160
     * bytes each. The parser blocks on the bounded queue long before it has
     * seen a fraction of this, so a parse that keeps going after the consumer
     * throws walks the file end to end and the byte count gives it away.
     */
    private fun writeBank(file: File, questionsPerCategory: Int) {
        file.outputStream().bufferedWriter(Charsets.US_ASCII).use { w ->
            w.write(
                "{\"version\":2,\"papers\":[{\"id\":\"p1\",\"title\":\"Big\"," +
                    "\"categories\":[{\"id\":\"c1\",\"title\":\"C\",\"questions\":["
            )
            for (i in 0 until questionsPerCategory) {
                if (i > 0) w.write(",")
                w.write(
                    "{\"id\":\"q$i\",\"text\":\"Filler question $i asking for its " +
                        "correct choice.\",\"options\":[\"Alpha choice\",\"Beta choice\"]," +
                        "\"correctOptionIds\":[\"Alpha choice\"]}"
                )
            }
            w.write("]}]}]}")
        }
    }

    private fun importStreamThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == "import-stream" }

    @Test
    fun aConsumerThrowStopsTheParse() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "cancel-bank.json")
        writeBank(file, questionsPerCategory = 300_000)
        assertTrue(file.length() > 45_000_000L)
        val started = System.currentTimeMillis()
        val parserThreadsBefore = importStreamThreads().toSet()
        val counting = CountingInputStream(FileInputStream(file))
        val propagated = try {
            runBlocking {
                StreamingImporter(db).import(
                    source = { counting },
                    onProgress = { throw IllegalStateException("consumer gone") }
                )
            }
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue("the consumer's failure must propagate", propagated)

        // No sleeps for correctness: poll the thread count and the byte
        // count with a deadline. The parse must both finish (this test's
        // parser threads leave) and stop reading (the byte count settles
        // near what the bounded read-ahead had already pulled).
        val deadline = System.currentTimeMillis() + 20_000
        var lastBytes = -1L
        var settled = false
        var threadsLeft = Int.MAX_VALUE
        while (System.currentTimeMillis() < deadline && (threadsLeft > 0 || !settled)) {
            threadsLeft = importStreamThreads().filter { it !in parserThreadsBefore }.size
            val now = counting.bytesRead.get()
            settled = now == lastBytes
            lastBytes = now
            if (threadsLeft > 0 || !settled) Thread.sleep(50)
        }
        assertTrue(
            "the parser thread must stop after the consumer throws " +
                "(waited ${System.currentTimeMillis() - started}ms)",
            importStreamThreads().filter { it !in parserThreadsBefore }.isEmpty()
        )
        // The bounded read-ahead (a 16-event queue of 2000-question batches
        // plus the reader's own buffers) keeps what it already had; anything
        // near the file's size means the parse kept going. 12MB is far under
        // this bank and far over the deductible.
        val finalBytes = counting.bytesRead.get()
        assertTrue(
            "the parse must stop reading soon after the consumer throws: " +
                "read $finalBytes of ${file.length()} bytes",
            finalBytes < 12_000_000L
        )
    }
}
