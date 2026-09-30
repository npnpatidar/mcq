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
    fun scopesRestrictMatchArea() {
        // "paris" is option-only; "planet" is text-only.
        assertEquals(
            listOf("q2"),
            QuestionSearch.filter(questions, "paris", QuestionSearch.Scope.OPTIONS).map { it.id }
        )
        assertEquals(
            emptyList<Question>(),
            QuestionSearch.filter(questions, "paris", QuestionSearch.Scope.QUESTION)
        )
        assertEquals(
            listOf("q1"),
            QuestionSearch.filter(questions, "planet", QuestionSearch.Scope.QUESTION).map { it.id }
        )
        assertEquals(
            emptyList<Question>(),
            QuestionSearch.filter(questions, "planet", QuestionSearch.Scope.OPTIONS)
        )
        // Tags belong to the question scope.
        assertEquals(
            listOf("q1"),
            QuestionSearch.filter(questions, "space", QuestionSearch.Scope.QUESTION).map { it.id }
        )
        assertEquals(
            emptyList<Question>(),
            QuestionSearch.filter(questions, "space", QuestionSearch.Scope.OPTIONS)
        )
    }

    @Test
    fun attachAddsProvenanceAndDropsOrphans() {
        val hits = QuestionSearch.attach(
            questions,
            mapOf("c" to ("p1" to "Paper One"))
        )
        assertEquals(2, hits.size)
        assertEquals("p1", hits[0].paperId)
        assertEquals("Paper One", hits[0].paperTitle)
        assertEquals("q1", hits[0].question.id)
        assertEquals(
            emptyList<QuestionSearch.Hit>(),
            QuestionSearch.attach(questions, emptyMap())
        )
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

    @Test
    fun escapeLikeLeavesPlainQueriesAlone() {
        assertEquals("titanic", QuestionSearch.escapeLike("titanic"))
        assertEquals("", QuestionSearch.escapeLike(""))
    }

    @Test
    fun escapeLikeNeutralisesWildcards() {
        assertEquals("100\\% sure", QuestionSearch.escapeLike("100% sure"))
        assertEquals("a\\_b", QuestionSearch.escapeLike("a_b"))
        assertEquals("back\\\\slash", QuestionSearch.escapeLike("back\\slash"))
        // Backslash first: an existing escape must not itself become wild.
        assertEquals("\\\\\\%\\_", QuestionSearch.escapeLike("\\%_"))
    }
}
