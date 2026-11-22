package com.mcqapp

import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.domain.CardStateKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedWriter
import java.io.InputStream
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Wire-format defaults for the v30 fields. A backup written before v30 has no
 * `suspended`/`buriedUntil` keys, so both DTOs must still decode it as an
 * ordinary card; a backup written now must carry them through both the
 * whole-file and the streaming readers. Pure JVM.
 */
class SuspendDtoDefaultsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun anOldScheduleEntryDecodesAsAnOrdinaryCard() {
        val old = json.decodeFromString<CardScheduleDto>(
            """{"ease":2.5,"intervalDays":4,"dueAt":100,"reps":3,"reviewCount":3}"""
        )
        assertFalse(old.suspended)
        assertNull(old.buriedUntil)
    }

    @Test
    fun aNewScheduleEntryRoundTripsItsFlags() {
        val current = CardScheduleDto(
            ease = 2.5,
            suspended = true,
            buriedUntil = 1_800_000_000_000L
        )
        val decoded = json.decodeFromString<CardScheduleDto>(json.encodeToString(current))
        assertTrue(decoded.suspended)
        assertEquals(1_800_000_000_000L, decoded.buriedUntil)
    }

    private class Sink : McqBackupFormat.StreamingSink {
        val cards = mutableListOf<McqBackupFormat.CardStateRow>()
        override suspend fun content(json: InputStream) = Unit
        override suspend fun cardState(row: McqBackupFormat.CardStateRow) {
            cards += row
        }
        override suspend fun reviewLog(row: McqBackupFormat.ReviewLogRow) = Unit
        override suspend fun paperCreated(row: McqBackupFormat.PaperCreated) = Unit
        override suspend fun panel(row: McqBackupFormat.PanelRow) = Unit
        override suspend fun inProgressTest(json: String?) = Unit
        override suspend fun settings(settings: McqBackupFormat.SettingsFile) = Unit
    }

    private fun zip(study: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun entry(name: String, text: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            entry(
                McqBackupFormat.MANIFEST,
                McqBackupFormat.json.encodeToString(
                    McqBackupFormat.Manifest.serializer(),
                    McqBackupFormat.Manifest(papers = 1, questions = 1)
                )
            )
            entry(
                McqBackupFormat.CONTENT,
                McqBackupFormat.json.encodeToString(McqFileDto.serializer(), McqFileDto())
            )
            entry(McqBackupFormat.STUDY, study)
            entry(McqBackupFormat.SETTINGS, "{\"preferences\": {}}")
        }
        return out.toByteArray()
    }

    @Test
    fun theStreamingStudyReaderKeepsBothFlags() = runBlocking {
        val study = """
            {"cardState": [
              {"paperId":"p1","questionId":"q1","ease":2.5,"intervalDays":0,"dueAt":0,
               "reps":0,"lapses":0,"leech":false,"lastReviewedAt":0,"contentHash":"h",
               "suspended":true,"buriedUntil":1800000000000},
              {"paperId":"p1","questionId":"q2","ease":2.5,"intervalDays":0,"dueAt":0,
               "reps":0,"lapses":0,"leech":false,"lastReviewedAt":0,"contentHash":"h"}
            ],"reviewLog":[]}
        """.trimIndent()
        val sink = Sink()
        McqBackupFormat.readStreaming(ByteArrayInputStream(zip(study)), sink)

        assertEquals(2, sink.cards.size)
        assertTrue(sink.cards[0].suspended)
        assertEquals(1_800_000_000_000L, sink.cards[0].buriedUntil)
        assertFalse(sink.cards[1].suspended)
        assertNull(sink.cards[1].buriedUntil)
    }

    @Test
    fun theStreamingStudyWriterEmitsBothFlags() = runBlocking {
        fun row(id: String, suspended: Boolean, buriedUntil: Long?) = CardStateEntity(
            paperId = "p1",
            questionId = id,
            ease = 2.5,
            intervalDays = 0,
            dueAt = 0L,
            reps = 0,
            lapses = 0,
            leech = false,
            lastReviewedAt = 0L,
            contentHash = "h",
            cardState = CardStateKind.NEW,
            suspended = suspended,
            buriedUntil = buriedUntil
        )
        val writer = StringWriter()
        val w = BufferedWriter(writer)
        McqBackupFormat.writeStudyRows(
            w = w,
            cardState = { emit ->
                emit(row("q1", suspended = true, buriedUntil = null))
                emit(row("q2", suspended = false, buriedUntil = 1_800_000_000_000L))
            },
            reviewLog = { },
            paperCreated = emptyList(),
            inProgressTest = null
        )
        w.flush()

        val sink = Sink()
        McqBackupFormat.readStreaming(ByteArrayInputStream(zip(writer.toString())), sink)
        assertEquals(2, sink.cards.size)
        assertTrue(sink.cards.first { it.questionId == "q1" }.suspended)
        assertEquals(
            1_800_000_000_000L,
            sink.cards.first { it.questionId == "q2" }.buriedUntil
        )
    }
}
