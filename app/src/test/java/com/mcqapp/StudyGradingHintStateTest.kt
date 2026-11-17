package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.StudyCard
import com.mcqapp.domain.StudyReason
import com.mcqapp.ui.study.StudyUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-time grading disclosure is gated by [StudyUiState.showGradingHint].
 * These are pure state checks (no Robolectric, no database) so they run on the
 * ARM64 host too. They pin the "only while a card is actually being studied"
 * rule and the dismissal rule before any persistence is involved.
 */
class StudyGradingHintStateTest {

    private val question = Question(
        id = "q1",
        categoryId = "c1",
        text = "Q?",
        options = listOf(QuestionOption("a", "A")),
        correctOptionIds = setOf("a")
    )
    private val card = StudyCard("q1", StudyReason.NEW, CardState("q1"))

    private fun onCard(dismissed: Boolean = false) = StudyUiState(
        loading = false,
        current = card,
        question = question,
        gradingHintDismissed = dismissed
    )

    @Test
    fun hintShowsWhileFlagFalseAndCardOnScreen() {
        assertTrue(onCard().showGradingHint)
    }

    @Test
    fun hintHiddenOnceDismissed() {
        assertFalse(onCard(dismissed = true).showGradingHint)
    }

    @Test
    fun hintHiddenWhileLoading() {
        assertFalse(
            StudyUiState(loading = true, current = card, question = question).showGradingHint
        )
    }

    @Test
    fun hintHiddenOnLoadError() {
        assertFalse(
            StudyUiState(
                loading = false,
                loadError = "boom",
                current = card,
                question = question
            ).showGradingHint
        )
    }

    @Test
    fun hintHiddenOnSummary() {
        assertFalse(
            StudyUiState(
                loading = false,
                finished = true,
                current = card,
                question = question
            ).showGradingHint
        )
    }

    @Test
    fun hintHiddenWithoutACard() {
        assertFalse(StudyUiState(loading = false).showGradingHint)
    }
}
