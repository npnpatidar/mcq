package com.mcqapp

import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.QuestionResult
import com.mcqapp.domain.ReviewFilters
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewFiltersTest {

    private fun result(
        id: String,
        isCorrect: Boolean,
        selected: Set<String>,
        correctIds: Set<String> = setOf("a")
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

    private val results = listOf(
        result("correct", isCorrect = true, selected = setOf("a")),
        result("wrong", isCorrect = false, selected = setOf("b")),
        result("skipped", isCorrect = false, selected = emptySet()),
        result("ungraded-answered", isCorrect = false, selected = setOf("a"), correctIds = emptySet()),
        result("ungraded-skipped", isCorrect = false, selected = emptySet(), correctIds = emptySet())
    )

    @Test
    fun allReturnsEverything() {
        assertEquals(5, ReviewFilters.apply(results, ReviewFilters.ALL).size)
        assertEquals(5, ReviewFilters.apply(results, "bogus").size)
    }

    @Test
    fun gradedBranchesExcludeUngraded() {
        assertEquals(listOf("correct"), ReviewFilters.apply(results, ReviewFilters.CORRECT).map { it.questionId })
        assertEquals(listOf("wrong"), ReviewFilters.apply(results, ReviewFilters.WRONG).map { it.questionId })
        assertEquals(listOf("skipped"), ReviewFilters.apply(results, ReviewFilters.SKIPPED).map { it.questionId })
        assertEquals(
            listOf("ungraded-answered", "ungraded-skipped"),
            ReviewFilters.apply(results, ReviewFilters.UNGRADED).map { it.questionId }
        )
    }

    @Test
    fun savedFiltersByBookmarkSet() {
        val filtered = ReviewFilters.apply(results, ReviewFilters.SAVED, setOf("wrong", "skipped"))
        assertEquals(listOf("wrong", "skipped"), filtered.map { it.questionId })
        assertEquals(0, ReviewFilters.apply(results, ReviewFilters.SAVED, emptySet()).size)
    }
}
