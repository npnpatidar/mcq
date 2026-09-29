package com.mcqapp

import com.mcqapp.domain.Scoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {

    private val correct = setOf("a", "c")

    @Test
    fun exactMatchIsCorrect() {
        assertTrue(Scoring.isCorrect(setOf("a", "c"), correct))
        assertTrue(Scoring.isCorrect(setOf("c", "a"), correct))
    }

    @Test
    fun subsetIsWrongAllOrNothing() {
        assertFalse(Scoring.isCorrect(setOf("a"), correct))
        assertFalse(Scoring.isCorrect(setOf("c"), correct))
    }

    @Test
    fun supersetIsWrong() {
        assertFalse(Scoring.isCorrect(setOf("a", "c", "d"), correct))
    }

    @Test
    fun emptySelectionIsNotCorrect() {
        assertFalse(Scoring.isCorrect(emptySet(), correct))
    }

    @Test
    fun correctScoresOne() {
        assertEquals(1.0, Scoring.scoreQuestion(setOf("a", "c"), correct, 0.33), 0.0001)
    }

    @Test
    fun wrongDeductsNegativeMarking() {
        assertEquals(-0.33, Scoring.scoreQuestion(setOf("a"), correct, 0.33), 0.0001)
    }

    @Test
    fun skippedScoresZero() {
        assertEquals(0.0, Scoring.scoreQuestion(emptySet(), correct, 0.33), 0.0001)
    }

    @Test
    fun summarizeCountsCorrectWrongSkipped() {
        val questions = listOf(
            com.mcqapp.domain.Question(
                id = "q1", categoryId = "c", text = "t",
                options = emptyList(), correctOptionIds = setOf("a")
            ),
            com.mcqapp.domain.Question(
                id = "q2", categoryId = "c", text = "t",
                options = emptyList(), correctOptionIds = setOf("b")
            ),
            com.mcqapp.domain.Question(
                id = "q3", categoryId = "c", text = "t",
                options = emptyList(), correctOptionIds = setOf("c")
            )
        )
        val summary = Scoring.summarize(
            selections = mapOf("q1" to setOf("a"), "q2" to setOf("a")),
            questions = questions,
            negativeMarking = 0.25
        )
        assertEquals(1, summary.correctCount)
        assertEquals(1, summary.wrongCount)
        assertEquals(1, summary.skippedCount)
        assertEquals(0.75, summary.score, 0.0001)
        assertEquals(3.0, summary.maxScore, 0.0001)
    }

    @Test
    fun ungradedQuestionsExcludedFromScoring() {
        val questions = listOf(
            com.mcqapp.domain.Question(
                id = "q1", categoryId = "c", text = "t",
                options = emptyList(), correctOptionIds = setOf("a")
            ),
            com.mcqapp.domain.Question(
                id = "q2", categoryId = "c", text = "t",
                options = emptyList(), correctOptionIds = emptySet()
            ),
            com.mcqapp.domain.Question(
                id = "q3", categoryId = "c", text = "t",
                options = emptyList(), correctOptionIds = emptySet()
            )
        )
        val summary = Scoring.summarize(
            selections = mapOf("q1" to setOf("a"), "q2" to setOf("x")),
            questions = questions,
            negativeMarking = 0.25
        )
        assertEquals(1, summary.correctCount)
        assertEquals(0, summary.wrongCount)
        assertEquals(0, summary.skippedCount)
        assertEquals(2, summary.ungradedCount)
        assertEquals(1.0, summary.score, 0.0001)
        assertEquals(1.0, summary.maxScore, 0.0001)
    }

    @Test
    fun ungradedSingleQuestionScoresZeroWithoutPenalty() {
        assertEquals(0.0, Scoring.scoreQuestion(setOf("a"), emptySet(), 0.33), 0.0001)
        assertEquals(0.0, Scoring.scoreQuestion(emptySet(), emptySet(), 0.33), 0.0001)
    }
}
