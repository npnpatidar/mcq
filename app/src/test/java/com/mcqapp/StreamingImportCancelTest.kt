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

    /**
     * The batch the parser hands over at a time. Small on purpose: the
     * read-ahead is the queue's depth times a batch, so a small batch keeps
     * the deductible — what the parser may legitimately have read before the
     * consumer died — far below the bound the test asserts.
     */
    private companion object {
        const val BATCH = 50
    }

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
     * A bank far wider than any bounded read-ahead: 10k questions at ~160
     * bytes each, about 1.6MB. The parser blocks on the bounded queue long
     * before it has seen a fraction of this, so a parse that keeps going
     * after the consumer throws walks the file end to end and the byte
     * count gives it away. (A 47MB bank made the test JVM run out of
     * heap on CI, so the bank is sized to the proof, not to the drama.)
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
        writeBank(file, questionsPerCategory = 10_000)
        assertTrue(file.length() > 1_000_000L)
        val started = System.currentTimeMillis()
        val parserThreadsBefore = importStreamThreads().toSet()
        val counting = CountingInputStream(FileInputStream(file))
        val propagated = try {
            runBlocking {
                StreamingImporter(db, batchSize = BATCH).import(
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
        val deadline = System.currentTimeMillis() + 10_000
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
        // The guarantee the fix makes is that the parse stopped reading, so
        // that is asserted first: a failure about the thread alone must not
        // hide whether the bytes were fine.
        val finalBytes = counting.bytesRead.get()
        assertTrue(
            "the parse must stop reading soon after the consumer throws: " +
                "read $finalBytes of ${file.length()} bytes",
            finalBytes < 500_000L
        )
        // Then the thread itself. If it is still parked, its stack says where
        // it is waiting — in the queue's put, or in a read the close never
        // reached — instead of leaving it to be guessed at.
        val stuck = importStreamThreads().filter { it !in parserThreadsBefore }
        assertTrue(
            "the parser thread must stop after the consumer throws " +
                "(waited ${System.currentTimeMillis() - started}ms)\n" +
                stuck.joinToString("\n") { thread ->
                    thread.stackTrace.take(25).joinToString("\n") { "    $it" }
                },
            stuck.isEmpty()
        )
    }
}
