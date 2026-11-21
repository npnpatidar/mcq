package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.StudyCard
import com.mcqapp.domain.StudyReason
import com.mcqapp.ui.study.StudyUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flashcard predicates [StudyUiState] derives. A card is a question with no
 * options, and everything downstream (the footer, the hint, the summary) reads
 * these flags rather than re-deriving "no options" in the composable. Pure JVM,
 * so this runs on the ARM64 host while the DB-backed session tests are CI-only.
 */
class FlashcardStudyStateTest {

    private fun flashcard(id: String = "card") = Question(
        id = id,
        categoryId = "c1",
        elements = listOf(ContentElement.TextElement("Prompt")),
        options = emptyList(),
        correctOptionIds = emptySet(),
        explanationElements = listOf(ContentElement.TextElement("Answer"))
    )

    private fun mcq(id: String = "mcq") = Question(
        id = id,
        categoryId = "c1",
        elements = listOf(ContentElement.TextElement("Prompt")),
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    private fun card(id: String) =
        StudyCard(id, StudyReason.NEW, CardState(questionId = id))

    private fun state(question: Question?) = StudyUiState(
        loading = false,
        current = question?.let { card(it.id) },
        question = question
    )

    @Test
    fun aZeroOptionQuestionIsAFlashcard() {
        assertTrue(state(flashcard()).isFlashcard)
    }

    @Test
    fun anMcqWithOptionsIsNotAFlashcard() {
        assertFalse(state(mcq()).isFlashcard)
    }

    @Test
    fun noCardOnScreenIsNotAFlashcard() {
        // Loading, an error and the summary must not claim card behaviour.
        assertFalse(state(null).isFlashcard)
    }

    @Test
    fun theMcqGradingHintIsHiddenForACard() {
        // The hint explains how an MCQ is graded; a card is graded by the
        // learner's four buttons, so it must never appear over one.
        assertFalse(state(flashcard()).showGradingHint)
        assertTrue(state(mcq()).showGradingHint)
    }

    @Test
    fun recallAccuracyIsOmittedOnceASittingContainsACard() {
        // A card has no right or wrong answer, so a percentage built from
        // self-grades is not a recall figure.
        assertTrue(StudyUiState(reviewed = 3, flashcardsReviewed = 0).showsRecallAccuracy)
        assertFalse(StudyUiState(reviewed = 3, flashcardsReviewed = 1).showsRecallAccuracy)
        assertFalse(StudyUiState(reviewed = 1, flashcardsReviewed = 1).showsRecallAccuracy)
        // A sitting with no reviews at all has no line either.
        assertFalse(StudyUiState().showsRecallAccuracy)
        assertEquals(0, StudyUiState().flashcardsReviewed)
    }

    @Test
    fun aMixedSittingStillOmitsRecallAccuracy() {
        // Deliberate: one MCQ + one card is still a sitting whose percentage
        // would be half correctness and half self-grade, so the line is
        // dropped rather than silently mixing the two.
        val mixed = StudyUiState(reviewed = 2, flashcardsReviewed = 1, goodCount = 1)
        assertFalse(mixed.showsRecallAccuracy)
        // The surrounding tally is untouched: only the percentage goes.
        assertEquals(2, mixed.reviewed)
        assertTrue(StudyUiState(reviewed = 1, flashcardsReviewed = 0).showsRecallAccuracy)
    }
}
