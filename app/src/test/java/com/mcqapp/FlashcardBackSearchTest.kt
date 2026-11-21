package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.QuestionSearch
import com.mcqapp.domain.searchableText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A card's answer is searchable. The back — the explanation elements, which for
 * a flashcard *is* the answer — is part of what the learner reads, so scopes
 * ALL and QUESTIONS match it while OPTIONS stays options-only.
 *
 * Pure JVM: the in-memory authority is what decides a hit. Its SQL prefilter
 * twin (`questions.backTextFolded`) is pinned separately under Robolectric,
 * because the trap is a prefilter that vetoes a hit the filter accepts — the
 * filter must accept every candidate the SQL passes on.
 */
class FlashcardBackSearchTest {

    /** A card: no options, so the back is its answer. */
    private fun card(
        id: String = "card1",
        front: String = "What does photosynthesis release?",
        back: String = "Oxygen, split from water in the light reactions.",
        tags: List<String> = emptyList()
    ) = Question(
        id = id,
        categoryId = "c",
        text = front,
        options = emptyList(),
        correctOptionIds = emptySet(),
        explanation = back,
        tags = tags
    )

    /** A multiple-choice question: its back is the explanation. */
    private fun mcq(
        id: String = "q1",
        front: String = "Which gas do plants release?",
        option: String = "Oxygen",
        back: String = "Chlorophyll absorbs red and blue light."
    ) = Question(
        id = id,
        categoryId = "c",
        text = front,
        options = listOf(QuestionOption("a", option), QuestionOption("b", "Nitrogen")),
        correctOptionIds = setOf("a"),
        explanation = back
    )

    @Test
    fun aCardIsFoundByItsAnswerInAllAndQuestions() {
        val questions = listOf(card())
        val answer = "light reactions"
        assertEquals(
            "ALL must search the back",
            listOf("card1"),
            QuestionSearch.filter(questions, answer, QuestionSearch.Scope.ALL).map { it.id }
        )
        assertEquals(
            "QUESTIONS must search the back",
            listOf("card1"),
            QuestionSearch.filter(questions, answer, QuestionSearch.Scope.QUESTION).map { it.id }
        )
    }

    @Test
    fun theOptionsScopeStaysOptionsOnly() {
        val backOnly = card(back = "Released as a gas.")
        val optionHit = mcq(option = "Oxygen")
        assertEquals(
            "an answer is not an option",
            emptyList<Question>(),
            QuestionSearch.filter(listOf(backOnly), "Released", QuestionSearch.Scope.OPTIONS)
        )
        assertEquals(
            "an option still matches",
            listOf("q1"),
            QuestionSearch.filter(listOf(optionHit), "Oxygen", QuestionSearch.Scope.OPTIONS)
                .map { it.id }
        )
    }

    @Test
    fun anExplanationIsSearchableForAMultipleChoiceQuestionToo() {
        val questions = listOf(mcq(back = "Chlorophyll absorbs red and blue light."))
        assertEquals(
            listOf("q1"),
            QuestionSearch.filter(questions, "chlorophyll", QuestionSearch.Scope.ALL).map { it.id }
        )
        assertEquals(
            listOf("q1"),
            QuestionSearch.filter(questions, "chlorophyll", QuestionSearch.Scope.QUESTION).map { it.id }
        )
    }

    /**
     * The documented trap: the SQL prefilter reads the stored
     * `backTextFolded`, so it hands back a row whose stem, tags and options do
     * *not* contain the query. If [QuestionSearch.filter] did not also read the
     * back, it would silently drop that candidate and the hit would vanish —
     * the prefilter would look like the authority and the answer would be
     * unsearchable.
     */
    @Test
    fun theFilterNeverDropsASqlPrefilterHit() {
        val hit = card()
        val query = "light reactions"
        // What the SQL prefilter matched on: the stored back column, and only it.
        assertFalse("the seed is not a stem hit", hit.elements.searchableText.contains(query))
        assertTrue(
            "the prefilter's candidate must survive the filter",
            QuestionSearch.filter(listOf(hit), query).isNotEmpty()
        )
    }

    @Test
    fun theBackFoldsEveryLetter() {
        val folded = card(back = "CAFÉ culture medium")
        assertEquals(
            listOf("card1"),
            QuestionSearch.filter(listOf(folded), "café").map { it.id }
        )
        assertEquals(
            listOf("card1"),
            QuestionSearch.filter(listOf(folded), "CAFÉ").map { it.id }
        )
    }

    @Test
    fun anExplanationOnlyHitCarriesSnippetTextToo() {
        // The label differs (Explanation, not Answer), but the row still needs
        // the matched text around the match to explain why it surfaced.
        val explanationOnly = mcq(back = "Chlorophyll absorbs red and blue light.")
        assertNotNull(
            "an explanation-only hit must carry a snippet",
            QuestionSearch.backSnippet(explanationOnly, "chlorophyll")
        )
    }

    @Test
    fun backOnlyHitsCarryAShortAnswerSnippet() {
        val longBack = "Oxygen is released, and the light reactions also split water " +
            "into protons, electrons and oxygen gas inside the thylakoid membrane."
        val question = card(back = longBack)
        val snippet = QuestionSearch.backSnippet(question, "light reactions")
        assertTrue("a back-only hit explains itself: $snippet", snippet!!.contains("light reactions"))
        assertTrue(
            "only a window is shown, never the whole back: $snippet",
            snippet.length < longBack.length
        )
    }

    @Test
    fun theSnippetIsAbsentWhenTheRowAlreadyShowsTheMatch() {
        val question = mcq(front = "Chlorophyll question", back = "Chlorophyll detail.")
        assertNull(
            "a stem hit needs no answer snippet",
            QuestionSearch.backSnippet(question, "chlorophyll")
        )
        assertNull(
            "an option hit needs no answer snippet",
            QuestionSearch.backSnippet(question, "Nitrogen")
        )
        assertNull("a tag hit needs no answer snippet", QuestionSearch.backSnippet(
            card(front = "Front", back = "Back with biology", tags = listOf("biology")),
            "biology"
        ))
        assertNull("a blank query has no snippet", QuestionSearch.backSnippet(card(), "  "))
        assertNull(
            "a back that does not match has no snippet",
            QuestionSearch.backSnippet(card(), "photosynthesis product")
        )
    }

    @Test
    fun aMultiLineBackSnippetStaysOnOneLine() {
        val question = Question(
            id = "card1",
            categoryId = "c",
            elements = listOf(ContentElement.TextElement("Front")),
            options = emptyList(),
            correctOptionIds = emptySet(),
            explanationElements = listOf(
                ContentElement.TextElement("First line of the answer."),
                ContentElement.TableElement(listOf(listOf("Ruler", "Year"), listOf("Akbar", "1556")))
            )
        )
        val snippet = QuestionSearch.backSnippet(question, "Akbar")
        assertTrue("the table cell is searchable: $snippet", snippet!!.contains("Akbar"))
        assertFalse("a row snippet has no newlines: $snippet", snippet.contains("\n"))
    }
}
