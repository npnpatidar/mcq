package com.mcqapp

import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.QuestionResult
import com.mcqapp.domain.QuestionStats
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionStatsTest {

    private fun result(
        id: String,
        correctIds: Set<String> = setOf("a"),
        selected: Set<String> = setOf("a"),
        isCorrect: Boolean = true
    ) = QuestionResult(
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
    fun aggregatesAcrossAttempts() {
        val rows = listOf(
            result("q1", selected = setOf("a"), isCorrect = true),
            result("q1", selected = setOf("b"), isCorrect = false),
            result("q1", selected = emptySet(), isCorrect = false),
            result("q2", correctIds = emptySet(), selected = setOf("a"), isCorrect = false)
        )
        val stats = QuestionStats.aggregate(rows)
        assertEquals(2, stats.size)
        val q1 = stats.single { it.questionId == "q1" }
        assertEquals(3, q1.attempts)
        assertEquals(1, q1.correct)
        assertEquals(1, q1.wrong)
        assertEquals(1, q1.skipped)
        assertEquals(1.0 / 3.0, q1.wrongRate, 0.0001)
        val q2 = stats.single { it.questionId == "q2" }
        assertEquals(1, q2.ungraded)
        assertEquals(0, q2.gradedAttempts)
        assertEquals(0.0, q2.wrongRate, 0.0001)
    }

    @Test
    fun hardestSortsByWrongRateThenAttempts() {
        val rows = listOf(
            result("easy", selected = setOf("a"), isCorrect = true),
            result("easy", selected = setOf("a"), isCorrect = true),
            result("hard", selected = setOf("b"), isCorrect = false),
            result("hard", selected = setOf("b"), isCorrect = false),
            result("rare", selected = setOf("b"), isCorrect = false)
        )
        val hardest = QuestionStats.hardest(QuestionStats.aggregate(rows), limit = 2)
        assertEquals(listOf("hard", "rare"), hardest.map { it.questionId })
    }

    @Test
    fun ungradedOnlyQuestionsNeverRank() {
        val rows = listOf(result("q", correctIds = emptySet(), selected = setOf("a"), isCorrect = false))
        assertEquals(emptyList<QuestionStats.Stat>(), QuestionStats.hardest(QuestionStats.aggregate(rows)))
    }
}
