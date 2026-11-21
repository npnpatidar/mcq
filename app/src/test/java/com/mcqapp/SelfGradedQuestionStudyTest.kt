package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.StudyCard
import com.mcqapp.domain.StudyReason
import com.mcqapp.ui.study.StudyUiState
import com.mcqapp.ui.study.studyOptionStateRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Self-graded" means **no answer key**, not "no options". A multiple-choice
 * question imported ungraded has options but an empty key; comparing a
 * selection to it would call every answer wrong, so it is self-graded exactly
 * like a card. These predicates are pure, so this runs on the ARM64 host.
 */
class SelfGradedQuestionStudyTest {

    private fun keyless(id: String = "nokey") = Question(
        id = id,
        categoryId = "c1",
        elements = listOf(ContentElement.TextElement("Prompt $id")),
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = emptySet(),
        explanationElements = listOf(ContentElement.TextElement("Because $id"))
    )

    private fun keyed(id: String = "keyed") = Question(
        id = id,
        categoryId = "c1",
        elements = listOf(ContentElement.TextElement("Prompt $id")),
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = setOf("$id-a")
    )

    private fun card(id: String = "card") = Question(
        id = id,
        categoryId = "c1",
        elements = listOf(ContentElement.TextElement("Prompt $id")),
        options = emptyList(),
        correctOptionIds = emptySet(),
        explanationElements = listOf(ContentElement.TextElement("Answer $id"))
    )

    private fun state(question: Question?) = StudyUiState(
        loading = false,
        current = question?.let { StudyCard(it.id, StudyReason.NEW, CardState(questionId = it.id)) },
        question = question
    )

    @Test
    fun aKeylessMcqIsSelfGradedButNotACard() {
        val state = state(keyless())
        // No key: the app cannot grade it, so the learner does.
        assertFalse(state.canAutoGrade)
        assertTrue(state.selfGraded)
        // It is still a multiple-choice question, not a card: labels and
        // summary wording keep saying "Explanation".
        assertFalse(state.isFlashcard)
        assertTrue(state.question!!.options.isNotEmpty())
    }

    @Test
    fun aKeyedMcqIsAutoGraded() {
        val state = state(keyed())
        assertTrue(state.canAutoGrade)
        assertFalse(state.selfGraded)
        assertFalse(state.isFlashcard)
    }

    @Test
    fun aCardIsSelfGradedBecauseItHasNoKey() {
        val state = state(card())
        assertFalse(state.canAutoGrade)
        assertTrue(state.selfGraded)
        assertTrue(state.isFlashcard)
    }

    @Test
    fun noQuestionOnScreenIsNeither() {
        val state = StudyUiState()
        assertFalse(state.canAutoGrade)
        assertFalse(state.selfGraded)
        assertFalse(state.isFlashcard)
    }

    @Test
    fun theGradingHintIsHiddenForKeylessQuestions() {
        // The hint explains how an MCQ is graded; a keyless question is graded
        // by the learner's buttons, exactly like a card.
        assertFalse(state(keyless()).showGradingHint)
        assertTrue(state(keyed()).showGradingHint)
    }

    @Test
    fun recallAccuracyIsOmittedAfterAKeylessReview() {
        // reviewed = 2 with one keyless question: a percentage would mix
        // correctness with self-grades.
        assertFalse(StudyUiState(reviewed = 2, selfGradedReviewed = 1).showsRecallAccuracy)
        assertTrue(StudyUiState(reviewed = 2, selfGradedReviewed = 0).showsRecallAccuracy)
    }

    @Test
    fun aKeylessRevealNeverClaimsACorrectOrWrongOption() {
        // The state description is what TalkBack speaks, so "wrong answer" must
        // be unreachable with no key — even if a stale id happened to match.
        assertEquals(
            R.string.r2_study_option_selected,
            studyOptionStateRes(revealed = true, isCorrect = false, isSelected = true, keyKnown = false)
        )
        assertEquals(
            R.string.r2_study_option_not_selected,
            studyOptionStateRes(revealed = true, isCorrect = false, isSelected = false, keyKnown = false)
        )
        assertEquals(
            R.string.r2_study_option_not_selected,
            studyOptionStateRes(revealed = true, isCorrect = true, isSelected = false, keyKnown = false)
        )
        // A keyed reveal keeps its verdict wording.
        assertEquals(
            R.string.r2_study_option_correct,
            studyOptionStateRes(revealed = true, isCorrect = true, isSelected = false)
        )
        assertEquals(
            R.string.r2_study_option_selected_wrong,
            studyOptionStateRes(revealed = true, isCorrect = false, isSelected = true)
        )
    }
}
