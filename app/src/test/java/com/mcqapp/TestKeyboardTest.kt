package com.mcqapp

import com.mcqapp.domain.ExamMode
import com.mcqapp.ui.study.StudyKey
import com.mcqapp.ui.study.StudyKeyAction
import com.mcqapp.ui.test.TestKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the Test keyboard seam. A test has no grades, so the
 * digit keys must stay inert here while Space and the arrows still work — the
 * one-line regression for "digits only where a grade exists". Space's reveal
 * follows the same exam-mode matrix as the on-screen button.
 */
class TestKeyboardTest {

    @Test
    fun spaceShowsTheAnswerThenAdvances() {
        assertEquals(
            StudyKeyAction.REVEAL,
            TestKeyboard.action(StudyKey.SPACE, revealed = false)
        )
        assertEquals(
            StudyKeyAction.NEXT,
            TestKeyboard.action(StudyKey.SPACE, revealed = true)
        )
    }

    @Test
    fun spaceNeverRevealsInStrictModeEvenWhenPracticeIsOn() {
        // Strict overrides practice, so neither strict combination may reveal.
        assertFalse(ExamMode.canReveal(practiceMode = false, strictMode = true))
        assertFalse(ExamMode.canReveal(practiceMode = true, strictMode = true))
        assertEquals(
            StudyKeyAction.NONE,
            TestKeyboard.action(StudyKey.SPACE, revealed = false, practiceMode = false, strictMode = true)
        )
        assertEquals(
            StudyKeyAction.NONE,
            TestKeyboard.action(StudyKey.SPACE, revealed = false, practiceMode = true, strictMode = true)
        )
    }

    @Test
    fun practiceAlsoHidesRevealButNormalModeAllowsIt() {
        // Practice gives live feedback, so its reveal guard is hidden too —
        // the same ExamMode matrix the on-screen button follows.
        assertFalse(ExamMode.canReveal(practiceMode = true, strictMode = false))
        assertEquals(
            StudyKeyAction.NONE,
            TestKeyboard.action(StudyKey.SPACE, revealed = false, practiceMode = true, strictMode = false)
        )
        assertTrue(ExamMode.canReveal(practiceMode = false, strictMode = false))
        assertEquals(
            StudyKeyAction.REVEAL,
            TestKeyboard.action(StudyKey.SPACE, revealed = false, practiceMode = false, strictMode = false)
        )
    }

    @Test
    fun theStrictGuardOnlyBlocksReveal() {
        // Navigation stays available in a test, and a card that is already shown
        // still advances on Space — Strict mode must not freeze the session.
        assertEquals(
            StudyKeyAction.SELECT_PREVIOUS_OPTION,
            TestKeyboard.action(StudyKey.LEFT, revealed = false, practiceMode = false, strictMode = true)
        )
        assertEquals(
            StudyKeyAction.SELECT_NEXT_OPTION,
            TestKeyboard.action(StudyKey.RIGHT, revealed = false, practiceMode = false, strictMode = true)
        )
        assertEquals(
            StudyKeyAction.NEXT,
            TestKeyboard.action(StudyKey.SPACE, revealed = true, practiceMode = false, strictMode = true)
        )
    }

    @Test
    fun arrowsMoveBetweenQuestionsInEitherRevealState() {
        listOf(false, true).forEach { revealed ->
            assertEquals(
                StudyKeyAction.SELECT_PREVIOUS_OPTION,
                TestKeyboard.action(StudyKey.LEFT, revealed)
            )
            assertEquals(
                StudyKeyAction.SELECT_NEXT_OPTION,
                TestKeyboard.action(StudyKey.RIGHT, revealed)
            )
        }
    }

    @Test
    fun digitsNeverGradeOrNavigateInATest() {
        listOf(StudyKey.ONE, StudyKey.TWO, StudyKey.THREE, StudyKey.FOUR).forEach { key ->
            assertEquals(StudyKeyAction.NONE, TestKeyboard.action(key, revealed = false))
            assertEquals(StudyKeyAction.NONE, TestKeyboard.action(key, revealed = true))
        }
        assertEquals(StudyKeyAction.NONE, TestKeyboard.action(StudyKey.OTHER, revealed = false))
    }
}
