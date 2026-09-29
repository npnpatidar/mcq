package com.mcqapp

import com.mcqapp.data.io.AttemptDto
import com.mcqapp.data.io.AttemptResultDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun backupPayloadSurvivesJsonRoundTrip() {
        val file = McqFileDto(
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
                                QuestionDto(id = "q1", text = "Q?")
                            )
                        )
                    )
                )
            ),
            bookmarks = listOf("q1", "q-missing"),
            attempts = listOf(
                AttemptDto(
                    paperId = "p1",
                    title = "Paper",
                    totalQuestions = 1,
                    correctCount = 1,
                    score = 2.0,
                    maxScore = 2.0,
                    finishedAt = 1700000000000,
                    results = listOf(
                        AttemptResultDto(
                            questionId = "q1",
                            text = "Q?",
                            selectedOptionIds = "a",
                            correctOptionIds = "a",
                            isCorrect = true
                        )
                    )
                )
            )
        )
        val restored = LegacyParser.parse(json.encodeToString(McqFileDto.serializer(), file))
        assertEquals(1, restored.papers.size)
        assertEquals(listOf("q1", "q-missing"), restored.bookmarks)
        assertEquals(1, restored.attempts.size)
        val attempt = restored.attempts.single()
        assertEquals("p1", attempt.paperId)
        assertEquals(2.0, attempt.score, 0.0001)
        assertEquals(1700000000000, attempt.finishedAt)
        assertEquals(1, attempt.results.size)
        assertTrue(attempt.results.single().isCorrect)
    }

    @Test
    fun regularBanksParseWithEmptyHistory() {
        val restored = LegacyParser.parse(
            """{"papers": [{"id": "p1", "title": "Paper", "categories": []}]}"""
        )
        assertTrue(restored.bookmarks.isEmpty())
        assertTrue(restored.attempts.isEmpty())
    }

    @Test
    fun corruptAttemptResultsDefaultGracefully() {
        val restored = LegacyParser.parse(
            """{"papers": [], "attempts": [{"paperId": "p1", "results": "oops"}]}"""
        )
        assertEquals(1, restored.attempts.size)
        assertEquals("p1", restored.attempts.single().paperId)
        assertTrue(restored.attempts.single().results.isEmpty())
        assertTrue(restored.papers.isEmpty())
    }
}
