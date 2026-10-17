package com.mcqapp

import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The streaming reader is the one a restore actually uses, so it has to agree
 * with [McqBackupFormat.read] on every part it hands over — byte for byte on
 * the bank, row for row on the study table — and refuse the same archives with
 * the same words. These are pure-JVM tests: no database, no Robolectric.
 */
class BackupStreamingReadTest {

    /** Collects what a streamed read hands over, so it can be compared. */
    private class Collected : McqBackupFormat.StreamingSink {
        var content: String? = null
        val cardState = mutableListOf<McqBackupFormat.CardStateRow>()
        val reviewLog = mutableListOf<McqBackupFormat.ReviewLogRow>()
        val paperCreated = mutableListOf<McqBackupFormat.PaperCreated>()
        var inProgressTest: String? = null
        var settings: McqBackupFormat.SettingsFile? = null

        override suspend fun content(json: InputStream) {
            content = json.readBytes().toString(Charsets.UTF_8)
        }

        override suspend fun cardState(row: McqBackupFormat.CardStateRow) {
            cardState += row
        }

        override suspend fun reviewLog(row: McqBackupFormat.ReviewLogRow) {
            reviewLog += row
        }

        override suspend fun paperCreated(row: McqBackupFormat.PaperCreated) {
            paperCreated += row
        }

        override suspend fun inProgressTest(json: String?) {
            inProgressTest = json
        }

        override suspend fun settings(settings: McqBackupFormat.SettingsFile) {
            this.settings = settings
        }
    }

    private fun zip(vararg parts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun content() = McqFileDto(
        version = 1,
        papers = listOf(
            PaperDto(
                id = "p1",
                title = "Paper",
                categories = listOf(
                    CategoryDto(
                        id = "c1",
                        title = "Cat",
                        questions = listOf(
                            QuestionDto(id = "q1", text = "Stem", options = emptyList())
                        )
                    )
                )
            )
        )
    )

    /**
     * A study file written by hand rather than by the serializer: a row with
     * only the required fields, a row that carries every field plus an unknown
     * one holding a nested object, escaped quotes and a backslash in a string,
     * an unknown top-level field, and both shapes of the resume snapshot. The
     * streamed read must produce exactly what the serializer would.
     */
    private val handWrittenStudy = """
    {
      "cardState": [
        {"paperId": "p1", "questionId": "q1", "ease": 2.5, "intervalDays": 3,
         "dueAt": 90, "reps": 2, "lapses": 1, "leech": false,
         "lastReviewedAt": 50, "contentHash": "h1"},
        {"paperId": "p2", "questionId": "q2", "ease": 2.0, "intervalDays": 0,
         "dueAt": 0, "reps": 0, "lapses": 0, "leech": true,
         "lastReviewedAt": 0, "contentHash": "a \"quoted\" \\ backslash",
         "difficulty": 4.5, "stability": 7.0, "cardState": "NEW", "reviewCount": 3,
         "aFieldFromALaterVersion": {"nested": [1, 2, {"deep": true}]}}
      ],
      "reviewLog": [
        {"id": 1, "paperId": "p1", "questionId": "q1", "rating": 3, "reviewedAt": 50},
        {"id": 2, "paperId": "p1", "questionId": "q1", "rating": 1, "reviewedAt": 60,
         "elapsedSeconds": 4, "stateBefore": "REVIEW", "stabilityAfter": 5.0,
         "difficultyAfter": 4.0, "intervalDaysAfter": 3}
      ],
      "paperCreated": [{"id": "p1", "createdAt": 7}],
      "inProgressTest": "{\"paperId\": \"p1\"}",
      "anotherFieldFromALaterVersion": [1, 2, 3]
    }
    """.trimIndent()

    private fun parts(
        study: String = handWrittenStudy,
        settings: String = "{\"preferences\": {}}"
    ): Array<Pair<String, String>> = arrayOf(
        McqBackupFormat.MANIFEST to McqBackupFormat.json.encodeToString(
            McqBackupFormat.Manifest.serializer(),
            McqBackupFormat.Manifest(papers = 1, questions = 1)
        ),
        McqBackupFormat.CONTENT to
            McqBackupFormat.json.encodeToString(McqFileDto.serializer(), content()),
        McqBackupFormat.STUDY to study,
        McqBackupFormat.SETTINGS to settings
    )

    @Test
    fun theStreamedReadHandsOverExactlyWhatTheWholeReadDecodes() = runBlocking {
        val bytes = zip(*parts())
        val decoded = McqBackupFormat.read(bytes)

        val collected = Collected()
        val manifest = McqBackupFormat.readStreaming(ByteArrayInputStream(bytes), collected)

        assertEquals(decoded.manifest, manifest)
        assertEquals(decoded.study.cardState, collected.cardState)
        assertEquals(decoded.study.reviewLog, collected.reviewLog)
        assertEquals(decoded.study.paperCreated, collected.paperCreated)
        assertEquals(decoded.study.inProgressTest, collected.inProgressTest)
        assertEquals(decoded.settings, collected.settings)
        // The bank is handed over as the very bytes the zip carries, so
        // whatever the importer makes of them is what a whole read would.
        assertEquals(
            McqBackupFormat.json.encodeToString(McqFileDto.serializer(), decoded.content),
            collected.content
        )
    }

    @Test
    fun aRowWithOnlyTheRequiredFieldsTakesItsDefaults() = runBlocking {
        val collected = Collected()
        McqBackupFormat.readStreaming(ByteArrayInputStream(zip(*parts())), collected)
        val first = collected.cardState.first()
        assertEquals(0.0, first.difficulty, 0.0)
        assertEquals(0.0, first.stability, 0.0)
        assertEquals("NEW", first.cardState)
        assertEquals(0, first.reviewCount)
        // The unknown field on the second row did not stop it decoding.
        assertEquals("a \"quoted\" \\ backslash", collected.cardState[1].contentHash)
        assertEquals(3, collected.cardState[1].reviewCount)
    }

    @Test
    fun everyMissingPartIsRefusedByName() = runBlocking {
        val all = parts()
        for ((missing, _) in all) {
            val stripped = zip(*all.filter { it.first != missing }.toTypedArray())
            assertThrows(McqBackupFormat.Invalid::class.java) {
                McqBackupFormat.read(stripped)
            }
            val streamed = assertThrows(McqBackupFormat.Invalid::class.java) {
                runBlocking {
                    McqBackupFormat.readStreaming(ByteArrayInputStream(stripped), Collected())
                }
            }
            assertTrue(
                "an archive without $missing must name it, got: ${streamed.message}",
                streamed.message!!.contains(missing.substringBefore('.'))
            )
        }
    }

    /**
     * A zip re-packed with the study file before the content. Its rows cannot
     * be offered (they reference the bank), so the archive is refused rather
     * than silently restored without them.
     */
    @Test
    fun studyRowsBeforeTheContentAreRefusedRatherThanDroppedSilently() = runBlocking {
        val ordered = parts()
        val study = ordered.first { it.first == McqBackupFormat.STUDY }
        val content = ordered.first { it.first == McqBackupFormat.CONTENT }
        val manifest = ordered.first { it.first == McqBackupFormat.MANIFEST }
        val settings = ordered.first { it.first == McqBackupFormat.SETTINGS }
        val rePacked = zip(manifest, study, content, settings)

        val collected = Collected()
        val e = assertThrows(McqBackupFormat.Invalid::class.java) {
            runBlocking { McqBackupFormat.readStreaming(ByteArrayInputStream(rePacked), collected) }
        }
        assertTrue(e.message!!.contains("before its content"))
        assertTrue(collected.cardState.isEmpty())
    }

    @Test
    fun aForeignZipAndAFutureVersionAreRefusedTheSameWay() = runBlocking {
        val foreign = zip("unrelated.txt" to "hello")
        val wholeForeign = assertThrows(McqBackupFormat.Invalid::class.java) {
            McqBackupFormat.read(foreign)
        }
        val streamedForeign = assertThrows(McqBackupFormat.Invalid::class.java) {
            runBlocking { McqBackupFormat.readStreaming(ByteArrayInputStream(foreign), Collected()) }
        }
        assertEquals(wholeForeign.message, streamedForeign.message)

        val future = zip(
            *parts().map { (name, text) ->
                if (name != McqBackupFormat.MANIFEST) name to text else name to
                    McqBackupFormat.json.encodeToString(
                        McqBackupFormat.Manifest.serializer(),
                        McqBackupFormat.Manifest(version = McqBackupFormat.VERSION + 1)
                    )
            }.toTypedArray()
        )
        val wholeFuture = assertThrows(McqBackupFormat.Invalid::class.java) {
            McqBackupFormat.read(future)
        }
        val streamedFuture = assertThrows(McqBackupFormat.Invalid::class.java) {
            runBlocking { McqBackupFormat.readStreaming(ByteArrayInputStream(future), Collected()) }
        }
        assertEquals(wholeFuture.message, streamedFuture.message)
        assertTrue(streamedFuture.message!!.contains("newer app version"))
    }

    /**
     * The reader must not hold the archive: with the entries written one at a
     * time and a stream that only ever moves forward, a read of a large study
     * file still works. This pins the shape (a stream that refuses to rewind)
     * rather than the size.
     */
    @Test
    fun theReadOnlyEverMovesForwardOverTheInput() = runBlocking {
        val rows = (1..500).joinToString(",") { index ->
            """{"id":$index,"paperId":"p1","questionId":"q1","rating":3,"reviewedAt":$index}"""
        }
        val bytes = zip(
            *parts(
                study = """{"cardState":[],"reviewLog":[$rows],"paperCreated":[],
                    "inProgressTest":null}"""
            )
        )
        val collected = Collected()
        McqBackupFormat.readStreaming(ForwardOnly(ByteArrayInputStream(bytes)), collected)
        assertEquals(500, collected.reviewLog.size)
    }

    /** A stream with no [java.io.InputStream.mark] support, like SAF's. */
    private class ForwardOnly(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = 0
        override fun markSupported(): Boolean = false
        override fun close() = delegate.close()
    }
}
