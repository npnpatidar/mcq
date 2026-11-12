package com.mcqapp

import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The v2 study file's three new tables and seven review columns, through the
 * streaming writer/reader a real backup and restore use. Pure JVM: no database.
 */
class LadderBackupStreamingTest {

    private class Collected : McqBackupFormat.StreamingSink {
        val reviews = mutableListOf<McqBackupFormat.ReviewLogRow>()
        val versions = mutableListOf<McqBackupFormat.LadderVersionRow>()
        val blocks = mutableListOf<McqBackupFormat.LadderBlockRow>()
        val sessions = mutableListOf<McqBackupFormat.StudySessionRow>()

        override suspend fun reviewLog(row: McqBackupFormat.ReviewLogRow) {
            reviews += row
        }

        override suspend fun ladderVersion(row: McqBackupFormat.LadderVersionRow) {
            versions += row
        }

        override suspend fun ladderBlock(row: McqBackupFormat.LadderBlockRow) {
            blocks += row
        }

        override suspend fun studySession(row: McqBackupFormat.StudySessionRow) {
            sessions += row
        }
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

    @Test
    fun `the format version is two`() {
        assertEquals(2, McqBackupFormat.VERSION)
    }

    @Test
    fun `the streaming writer and reader carry the ladder and sessions`() = runBlocking {
        val study = StringWriter()
        val buffered = java.io.BufferedWriter(study)
        McqBackupFormat.writeStudyRows(
            w = buffered,
            cardState = {},
            reviewLog = { emit ->
                emit(
                    com.mcqapp.data.local.ReviewLogEntity(
                        id = 7,
                        paperId = "p1",
                        questionId = "q1",
                        rating = 4,
                        reviewedAt = 900L,
                        proposedRating = 3,
                        gradeSource = "EDITED",
                        ladderVersion = 4,
                        predictedRecall = 0.81,
                        fsrsFitVersion = 2,
                        readingAllowanceSeconds = 17,
                        wasSkipped = false
                    )
                )
            },
            paperCreated = emptyList(),
            inProgressTest = null,
            ladderVersions = listOf(
                McqBackupFormat.LadderVersionRow(
                    id = 4,
                    easySeconds = 9,
                    slowSeconds = 31,
                    effectiveFrom = 500L,
                    reason = "proposal",
                    previousId = 3,
                    blockId = 11,
                    policyVersion = 1
                )
            ),
            ladderBlocks = listOf(
                McqBackupFormat.LadderBlockRow(
                    id = 11,
                    startAt = 10L,
                    endAt = 20L,
                    ladderVersion = 3,
                    fsrsFitVersion = 2,
                    policyVersion = 1,
                    easyPairs = 60,
                    easyCards = 40,
                    goodPairs = 20,
                    goodCards = 18,
                    delta1 = -0.06,
                    delta1Low = -0.11,
                    delta1High = -0.01,
                    followUpEasy = 0.8,
                    followUpGood = 0.75,
                    outcome = "lower-c1",
                    decidedAt = 21L
                )
            ),
            studySessions = listOf(
                McqBackupFormat.StudySessionRow(1, "p1", 100L, 700L, 600L)
            )
        )
        buffered.flush()

        val archive = zip(
            McqBackupFormat.MANIFEST to McqBackupFormat.json.encodeToString(
                McqBackupFormat.Manifest.serializer(),
                McqBackupFormat.Manifest(version = 2, papers = 1, questions = 1)
            ),
            McqBackupFormat.CONTENT to McqBackupFormat.json.encodeToString(
                McqFileDto.serializer(), content()
            ),
            McqBackupFormat.STUDY to study.toString(),
            McqBackupFormat.SETTINGS to "{\"preferences\":{}}"
        )
        val collected = Collected()
        McqBackupFormat.readStreaming(ByteArrayInputStream(archive), collected)

        val review = collected.reviews.single()
        assertEquals(3, review.proposedRating)
        assertEquals("EDITED", review.gradeSource)
        assertEquals(4, review.ladderVersion)
        assertEquals(0.81, review.predictedRecall!!, 0.0001)
        assertEquals(2, review.fsrsFitVersion)
        assertEquals(17, review.readingAllowanceSeconds)
        assertEquals(false, review.wasSkipped)

        assertEquals(4, collected.versions.single().id)
        assertEquals(-0.06, collected.blocks.single().delta1!!, 0.0001)
        assertEquals(40, collected.blocks.single().easyCards)
        assertEquals(600L, collected.sessions.single().activeSeconds)
    }

    /**
     * A v1 study.json has none of the new keys and none of the new review
     * fields. It must still read: every new row list is empty and every new
     * field takes its documented default, so a backup from before v26 restores
     * instead of being refused.
     */
    @Test
    fun `a v1 study file decodes with empty new tables and defaulted columns`() = runBlocking {
        val archive = zip(
            McqBackupFormat.MANIFEST to McqBackupFormat.json.encodeToString(
                McqBackupFormat.Manifest.serializer(),
                McqBackupFormat.Manifest(version = 1, papers = 1, questions = 1)
            ),
            McqBackupFormat.CONTENT to McqBackupFormat.json.encodeToString(
                McqFileDto.serializer(), content()
            ),
            McqBackupFormat.STUDY to
                "{\"cardState\":[],\"reviewLog\":[{\"id\":1,\"paperId\":\"p1\"," +
                "\"questionId\":\"q1\",\"rating\":3,\"reviewedAt\":50}]," +
                "\"paperCreated\":[],\"inProgressTest\":null}",
            McqBackupFormat.SETTINGS to "{\"preferences\":{}}"
        )
        val collected = Collected()
        McqBackupFormat.readStreaming(ByteArrayInputStream(archive), collected)
        assertTrue(collected.versions.isEmpty())
        assertTrue(collected.blocks.isEmpty())
        assertTrue(collected.sessions.isEmpty())
        val review = collected.reviews.single()
        assertEquals(0, review.ladderVersion)
        assertEquals(null, review.gradeSource)
        assertEquals(null, review.predictedRecall)
    }
}
