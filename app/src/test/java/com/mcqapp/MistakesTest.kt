package com.mcqapp

import com.mcqapp.domain.Attempt
import com.mcqapp.domain.Mistakes
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.QuestionResult
import org.junit.Assert.assertEquals
import org.junit.Test

class MistakesTest {

    private fun attempt(id: Long, paperId: String) = Attempt(
        id = id,
        paperId = paperId,
        title = "Paper $paperId",
        totalQuestions = 2,
        correctCount = 1,
        wrongCount = 1,
        skippedCount = 0,
        score = 1.0,
        maxScore = 2.0,
        durationSeconds = 60,
        finishedAt = id * 1000
    )

    private fun result(
        attemptId: Long,
        id: String,
        isCorrect: Boolean,
        selected: Set<String> = setOf("a"),
        correctIds: Set<String> = setOf("a")
    ) = QuestionResult(
        attemptId = attemptId,
        questionId = id,
        categoryTitle = "Cat",
        text = "Text $id",
        options = listOf(QuestionOption("a", "A")),
        correctOptionIds = correctIds,
        selectedOptionIds = selected,
        isCorrect = isCorrect,
        explanation = ""
    )

    @Test
    fun collectsWrongGroupedByPaperRecentFirst() {
        val attempts = listOf(attempt(1, "p1"), attempt(2, "p1"), attempt(3, "p2"))
        val results = listOf(
            result(1, "old", isCorrect = false),
            result(1, "ok", isCorrect = true),
            result(2, "new", isCorrect = false),
            result(3, "other", isCorrect = false)
        )
        val byPaper = Mistakes.mistakenIdsByPaper(attempts, results)
        assertEquals(listOf("new", "old"), byPaper["p1"])
        assertEquals(listOf("other"), byPaper["p2"])
    }

    @Test
    fun repeatedMissesAppearOnce() {
        val attempts = listOf(attempt(1, "p1"), attempt(2, "p1"))
        val results = listOf(
            result(1, "q", isCorrect = false),
            result(2, "q", isCorrect = false)
        )
        assertEquals(listOf("q"), Mistakes.mistakenIdsByPaper(attempts, results)["p1"])
    }

    @Test
    fun laterCorrectAnswerClearsTheMistake() {
        val attempts = listOf(attempt(1, "p1"), attempt(2, "p1"))
        val results = listOf(
            result(1, "fixed", isCorrect = false),
            result(1, "still-wrong", isCorrect = false),
            result(2, "fixed", isCorrect = true),
            result(2, "still-wrong", isCorrect = false)
        )
        assertEquals(
            listOf("still-wrong"),
            Mistakes.mistakenIdsByPaper(attempts, results)["p1"]
        )
    }

    @Test
    fun skippedAfterMissKeepsTheMistake() {
        val attempts = listOf(attempt(1, "p1"), attempt(2, "p1"))
        val results = listOf(
            result(1, "q", isCorrect = false),
            result(2, "q", isCorrect = false, selected = emptySet())
        )
        assertEquals(listOf("q"), Mistakes.mistakenIdsByPaper(attempts, results)["p1"])
    }

    @Test
    fun ignoresSkipsUngradedAndOrphans() {
        val attempts = listOf(attempt(1, "p1"))
        val results = listOf(
            result(1, "skipped", isCorrect = false, selected = emptySet()),
            result(1, "ungraded", isCorrect = false, correctIds = emptySet()),
            result(1, "correct-empty", isCorrect = true, selected = emptySet()),
            result(9, "orphan", isCorrect = false)
        )
        assertEquals(emptyMap<String, List<String>>(), Mistakes.mistakenIdsByPaper(attempts, results))
    }
}
