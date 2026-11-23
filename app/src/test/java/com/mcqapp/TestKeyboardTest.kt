package com.mcqapp

import com.mcqapp.ui.study.StudyKey
import com.mcqapp.ui.study.StudyKeyAction
import com.mcqapp.ui.study.StudyKeyboard
import com.mcqapp.ui.test.TestKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the Test keyboard seam. A test has no grades, so the
 * digit keys must stay inert here while Space and the arrows still work — the
 * one-line regression for "digits only where a grade exists".
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

    @Test
    fun theSharedTextFieldGuardStillDetectsTextEntry() {
        // The screen never steals a key while a text field or dialog owns focus;
        // the role check is the shared one Study uses too.
        assertTrue(StudyKeyboard.isTextEntry("TextField"))
        assertTrue(StudyKeyboard.isTextEntry("EditText"))
        assertFalse(StudyKeyboard.isTextEntry("Button"))
    }
}
