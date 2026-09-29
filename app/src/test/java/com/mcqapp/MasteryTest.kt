package com.mcqapp

import com.mcqapp.domain.Attempt
import com.mcqapp.domain.Mastery
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.QuestionResult
import org.junit.Assert.assertEquals
import org.junit.Test

class MasteryTest {

    private fun attempt(id: Long, paperId: String, title: String) = Attempt(
        id = id,
        paperId = paperId,
        title = title,
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
        category: String,
        isCorrect: Boolean,
        selected: Set<String> = setOf("a"),
        correctIds: Set<String> = setOf("a")
    ) = QuestionResult(
        attemptId = attemptId,
        questionId = "$category-$attemptId-${if (isCorrect) "c" else "w"}",
        categoryTitle = category,
        text = "T",
        options = listOf(QuestionOption("a", "A")),
        correctOptionIds = correctIds,
        selectedOptionIds = selected,
        isCorrect = isCorrect,
        explanation = ""
    )

    @Test
    fun ratesGroupByPaperAndCategory() {
        val attempts = listOf(attempt(1, "p1", "Paper One"), attempt(2, "p2", "Paper Two"))
        val results = listOf(
            result(1, "Science", isCorrect = true),
            result(1, "Science", isCorrect = false),
            result(2, "Science", isCorrect = false)
        )
        val mastery = Mastery.perCategory(attempts, results)
        assertEquals(2, mastery.size)
        val p1 = mastery.single { it.paperId == "p1" }
        assertEquals("Paper One", p1.paperTitle)
        assertEquals(0.5, p1.rate, 0.0001)
        val p2 = mastery.single { it.paperId == "p2" }
        assertEquals(0.0, p2.rate, 0.0001)
    }

    @Test
    fun weakestSortsAscendingAndSkipsUngraded() {
        val attempts = listOf(attempt(1, "p1", "Paper"))
        val results = listOf(
            result(1, "Strong", isCorrect = true),
            result(1, "Weak", isCorrect = false),
            result(1, "Weak", isCorrect = false),
            result(1, "Keyless", isCorrect = false, correctIds = emptySet())
        )
        val weakest = Mastery.weakest(Mastery.perCategory(attempts, results))
        assertEquals(listOf("Weak", "Strong"), weakest.map { it.categoryTitle })
    }
}
