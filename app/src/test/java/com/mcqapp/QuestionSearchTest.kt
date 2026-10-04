package com.mcqapp

import com.mcqapp.domain.ContentElement
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

    /**
     * Search used to match on textContent, which drops tables and formulas and
     * keeps inline markup — so a question whose only mention of a name lived in
     * a table cell was unfindable, while searching "strong" matched any
     * question containing a <strong> tag.
     */
    @Test
    fun contentInsideATableIsSearchable() {
        val question = Question(
            id = "q1", categoryId = "c",
            elements = listOf(
                ContentElement.TextElement("Use the table to answer."),
                ContentElement.TableElement(listOf(listOf("Ruler", "Year"), listOf("Akbar", "1556")))
            ),
            options = listOf(QuestionOption("a", "Chloroform"), QuestionOption("b", "Sulphur")),
            correctOptionIds = setOf("a")
        )
        assertEquals(1, QuestionSearch.filter(listOf(question), "Akbar").size)
        assertEquals(1, QuestionSearch.filter(listOf(question), "1556").size)
    }

    @Test
    fun aNameOnlyPresentInAnOptionTableIsSearchable() {
        val question = Question(
            id = "q1", categoryId = "c",
            elements = listOf(ContentElement.TextElement("Which table is correct?")),
            options = listOf(
                QuestionOption(
                    "a",
                    listOf(ContentElement.TableElement(listOf(listOf("Dam", "River"), listOf("Hirakud", "Mahanadi"))))
                ),
                QuestionOption("b", "None")
            ),
            correctOptionIds = setOf("b")
        )
        assertEquals("option table content must be searchable", 1,
            QuestionSearch.filter(listOf(question), "Mahanadi").size)
    }

    @Test
    fun aFormulaIsSearchableByItsCharacters() {
        val question = Question(
            id = "q1", categoryId = "c",
            elements = listOf(
                ContentElement.TextElement("Solve for "),
                ContentElement.MathElement("<math><mi>v</mi><mo>=</mo><mi>u</mi><mo>+</mo><mi>a</mi><mi>t</mi></math>")
            ),
            options = listOf(QuestionOption("a", "1"), QuestionOption("b", "2")),
            correctOptionIds = setOf("a")
        )
        // The MathML markup is stripped, leaving the letters a reader sees.
        assertEquals(1, QuestionSearch.filter(listOf(question), "v=u+at").size)
    }

    @Test
    fun inlineMarkupIsNotItselfSearchable() {
        val question = Question(
            id = "q1", categoryId = "c",
            elements = listOf(ContentElement.TextElement("A <strong>bold</strong> claim.")),
            options = listOf(QuestionOption("a", "1"), QuestionOption("b", "2")),
            correctOptionIds = setOf("a")
        )
        assertEquals(1, QuestionSearch.filter(listOf(question), "bold claim").size)
        assertEquals("markup must not be findable", 0,
            QuestionSearch.filter(listOf(question), "strong").size)
    }
}
