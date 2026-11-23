package com.mcqapp

import com.mcqapp.ui.study.StudyKey
import com.mcqapp.ui.study.StudyKeyAction
import com.mcqapp.ui.study.StudyKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM coverage for the keyboard-shortcut decision seam. */
class StudyKeyboardTest {

    @Test
    fun spaceRevealsThenAdvancesOnlyWhenStaged() {
        assertEquals(
            StudyKeyAction.REVEAL,
            StudyKeyboard.action(StudyKey.SPACE, revealed = false, staged = false, canGrade = true)
        )
        assertEquals(
            StudyKeyAction.NONE,
            StudyKeyboard.action(StudyKey.SPACE, revealed = true, staged = false, canGrade = true)
        )
        assertEquals(
            StudyKeyAction.NEXT,
            StudyKeyboard.action(StudyKey.SPACE, revealed = true, staged = true, canGrade = true)
        )
    }

    @Test
    fun gradesNeverFireBeforeRevealOrWhileGrading() {
        listOf(StudyKey.ONE, StudyKey.TWO, StudyKey.THREE, StudyKey.FOUR).forEach { key ->
            assertEquals(
                StudyKeyAction.NONE,
                StudyKeyboard.action(key, revealed = false, staged = false, canGrade = true)
            )
            assertEquals(
                StudyKeyAction.NONE,
                StudyKeyboard.action(key, revealed = true, staged = false, canGrade = false)
            )
        }
    }

    @Test
    fun fourGradesMapInOrder() {
        val expected = listOf(
            StudyKeyAction.GRADE_AGAIN, StudyKeyAction.GRADE_HARD,
            StudyKeyAction.GRADE_GOOD, StudyKeyAction.GRADE_EASY
        )
        listOf(StudyKey.ONE, StudyKey.TWO, StudyKey.THREE, StudyKey.FOUR)
            .zip(expected).forEach { (key, action) ->
                assertEquals(
                    action,
                    StudyKeyboard.action(key, revealed = true, staged = false, canGrade = true)
                )
            }
    }

    @Test
    fun arrowsNavigateAndTextEntryIsDetected() {
        assertEquals(
            StudyKeyAction.SELECT_PREVIOUS_OPTION,
            StudyKeyboard.action(StudyKey.LEFT, false, false, true)
        )
        assertEquals(
            StudyKeyAction.SELECT_NEXT_OPTION,
            StudyKeyboard.action(StudyKey.RIGHT, false, false, true)
        )
        assertTrue(StudyKeyboard.isTextEntry("TextField"))
        assertFalse(StudyKeyboard.isTextEntry("Button"))
    }
}
