package com.mcqapp

import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.browse.applyCategoryFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseCategoryFilterTest {

    private val questions = listOf(
        Question(
            id = "q1", categoryId = "chem", text = "Reaction of copper?",
            options = listOf(QuestionOption("a", "CuO")),
            correctOptionIds = setOf("a")
        ),
        Question(
            id = "q2", categoryId = "phys", text = "Velocity is?",
            options = listOf(QuestionOption("a", "m/s")),
            correctOptionIds = setOf("a")
        ),
        Question(
            id = "q3", categoryId = "chem", text = "Base of copper?",
            options = listOf(QuestionOption("a", "CuO2")),
            correctOptionIds = setOf("a")
        )
    )

    @Test
    fun nullCategoryKeepsTheWholeList() {
        assertEquals(questions, applyCategoryFilter(questions, null))
    }

    @Test
    fun oneCategoryKeepsOnlyItsQuestions() {
        assertEquals(
            listOf("q1", "q3"),
            applyCategoryFilter(questions, "chem").map { it.id }
        )
        assertEquals(
            listOf("q2"),
            applyCategoryFilter(questions, "phys").map { it.id }
        )
    }

    @Test
    fun unknownCategoryYieldsNothing() {
        assertEquals(
            emptyList<String>(),
            applyCategoryFilter(questions, "missing").map { it.id }
        )
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals(emptyList<Question>(), applyCategoryFilter(emptyList(), "chem"))
    }
}
