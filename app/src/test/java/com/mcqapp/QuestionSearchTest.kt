package com.mcqapp

import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.QuestionSearch
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionSearchTest {

    private val questions = listOf(
        Question(
            id = "q1", categoryId = "c", text = "Which planet is Blue?",
            options = listOf(QuestionOption("a", "Venus")),
            correctOptionIds = setOf("a"),
            tags = listOf("space")
        ),
        Question(
            id = "q2", categoryId = "c", text = "Capital of France?",
            options = listOf(QuestionOption("a", "Paris")),
            correctOptionIds = setOf("a"),
            tags = listOf("geography")
        )
    )

    @Test
    fun blankQueryReturnsEverything() {
        assertEquals(questions, QuestionSearch.filter(questions, ""))
        assertEquals(questions, QuestionSearch.filter(questions, "   "))
    }

    @Test
    fun matchesTextCaseInsensitively() {
        assertEquals(listOf("q1"), QuestionSearch.filter(questions, "PLANET").map { it.id })
    }

    @Test
    fun matchesTagsAndOptionTexts() {
        assertEquals(listOf("q1"), QuestionSearch.filter(questions, "space").map { it.id })
        assertEquals(listOf("q2"), QuestionSearch.filter(questions, "paris").map { it.id })
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertEquals(emptyList<Question>(), QuestionSearch.filter(questions, "titanic"))
    }

    @Test
    fun uncategorizedMatchesTitleAndBlankIds() {
        val mixed = listOf(
            Question(
                id = "q1", categoryId = "", text = "Orphan",
                options = emptyList(), correctOptionIds = emptySet()
            ),
            Question(
                id = "q2", categoryId = "u1", text = "Top level",
                options = emptyList(), correctOptionIds = emptySet()
            ),
            Question(
                id = "q3", categoryId = "c9", text = "Filed",
                options = emptyList(), correctOptionIds = emptySet()
            )
        )
        val titles = mapOf("u1" to "Uncategorized", "c9" to "Science")
        assertEquals(
            listOf("q1", "q2"),
            QuestionSearch.filterUncategorized(mixed, titles).map { it.id }
        )
        // Blank ids always count, even with no Uncategorized category present.
        assertEquals(
            listOf("q1"),
            QuestionSearch.filterUncategorized(mixed, mapOf("u1" to "Misc", "c9" to "Science")).map { it.id }
        )
    }
}
