package com.mcqapp

import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.io.McqFileDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * §7's pure-JVM scale guard for the backup reader a restore uses: a study file
 * with a quarter of a million reviews and fifty thousand schedules, read one
 * value at a time.
 *
 * The fixture is generated straight into the zip — the test never holds the
 * study file as text or as rows, the way [StatsScaleTest] never holds its
 * review log — and the reader's working set is meant to be one value plus the
 * caller's batch. The time bound is a loose tripwire for a reader that has
 * started holding the document, not a benchmark.
 */
class BackupStreamingScaleTest {

    private val cards = 50_000
    private val reviews = 250_000

    @Test
    fun theStreamedReadStaysLinearOverAQuarterOfAMillionRows() = runBlocking {
        val started = System.currentTimeMillis()
        val zip = largeBackup()
        val wrote = System.currentTimeMillis() - started

        val counts = Counts()
        val startedRead = System.currentTimeMillis()
        McqBackupFormat.readStreaming(ByteArrayInputStream(zip), counts)
        val elapsedMs = System.currentTimeMillis() - startedRead

        // Nothing was dropped, and the rows arrived in the order they were
        // written, id by id.
        assertEquals(cards, counts.cardState)
        assertEquals(reviews, counts.reviewLog)
        assertEquals(reviews.toLong(), counts.lastReviewId)
        assertTrue(
            "reading $cards card states and $reviews reviews from a " +
                "${zip.size / 1048576} MB archive took ${elapsedMs}ms " +
                "(writing the archive took ${wrote}ms)",
            elapsedMs < 15_000
        )
    }

    private class Counts : McqBackupFormat.StreamingSink {
        var cardState = 0
        var reviewLog = 0
        var lastReviewId = 0L
        var contentSeen = false

        override suspend fun content(json: InputStream) {
            // Still consumed: a sink has to be finished with the entry.
            json.readBytes()
            contentSeen = true
        }

        override suspend fun cardState(row: McqBackupFormat.CardStateRow) {
            cardState++
        }

        override suspend fun reviewLog(row: McqBackupFormat.ReviewLogRow) {
            reviewLog++
            lastReviewId = row.id
        }
    }

    /** The archive, generated row by row into the zip's entry writers. */
    private fun largeBackup(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out.buffered()).use { zip ->
            fun entry(name: String, body: (Writer) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8), 1 shl 14)
                body(writer)
                writer.flush()
                zip.closeEntry()
            }
            val json = McqBackupFormat.json
            entry(McqBackupFormat.MANIFEST) {
                it.write(
                    json.encodeToString(
                        McqBackupFormat.Manifest.serializer(),
                        McqBackupFormat.Manifest(papers = 1, questions = cards, reviews = reviews)
                    )
                )
            }
            entry(McqBackupFormat.CONTENT) {
                it.write(
                    json.encodeToString(
                        McqFileDto.serializer(),
                        McqFileDto(version = 1)
                    )
                )
            }
            entry(McqBackupFormat.STUDY) { w ->
                w.write("{\"cardState\":[")
                for (index in 0 until cards) {
                    if (index > 0) w.write(",")
                    w.write(
                        """{"paperId":"p1","questionId":"q$index","ease":2.5,""" +
                            """"intervalDays":3,"dueAt":90,"reps":2,"lapses":0,""" +
                            """"leech":false,"lastReviewedAt":50,"contentHash":"h$index",""" +
                            """"difficulty":4.0,"stability":5.0,"cardState":"REVIEW",""" +
                            """"reviewCount":2}"""
                    )
                }
                w.write("],\"reviewLog\":[")
                for (index in 0 until reviews) {
                    if (index > 0) w.write(",")
                    w.write(
                        """{"id":${index + 1},"paperId":"p1","questionId":"q${index % cards}",""" +
                            """"rating":3,"reviewedAt":${1_000 + index},"elapsedSeconds":4,""" +
                            """"stateBefore":"REVIEW","stabilityAfter":5.0,""" +
                            """"difficultyAfter":4.0,"intervalDaysAfter":3}"""
                    )
                }
                w.write("],\"paperCreated\":[],\"inProgressTest\":null}")
            }
            entry(McqBackupFormat.SETTINGS) { it.write("{\"preferences\":{}}") }
        }
        return out.toByteArray()
    }
}
