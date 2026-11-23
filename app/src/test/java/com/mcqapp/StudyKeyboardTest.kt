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
    fun spaceNeverRevealsOrAdvancesWhileOnBreak() {
        // A break owns the screen: the old guard omitted onBreak, so SPACE
        // revealed the answer (or advanced a staged grade) behind the overlay.
        listOf(false, true).forEach { revealed ->
            listOf(false, true).forEach { staged ->
                assertEquals(
                    StudyKeyAction.NONE,
                    StudyKeyboard.action(
                        StudyKey.SPACE, revealed = revealed, staged = staged,
                        canGrade = true, onBreak = true
                    )
                )
            }
        }
        // Every other key is silenced too, not just Space.
        (StudyKey.entries - StudyKey.OTHER).forEach { key ->
            assertEquals(
                StudyKeyAction.NONE,
                StudyKeyboard.action(key, revealed = true, staged = false,
                    canGrade = true, onBreak = true)
            )
        }
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
    fun arrowsNavigateTheOptionSelection() {
        assertEquals(
            StudyKeyAction.SELECT_PREVIOUS_OPTION,
            StudyKeyboard.action(StudyKey.LEFT, false, false, true)
        )
        assertEquals(
            StudyKeyAction.SELECT_NEXT_OPTION,
            StudyKeyboard.action(StudyKey.RIGHT, false, false, true)
        )
    }

    @Test
    fun digitGradeStagesOnlyInClassicWithTheOptIn() {
        // Simplified ignores the opt-in: Check already staged the auto grade and
        // Change grade owns edits. Restaging from a digit would null proposedGrade
        // and flip the row's AUTO/CHOSEN provenance (and fabricate a correctness
        // figure for self-graded cards), so it must always take the one-tap path.
        assertFalse(
            "Simplified + opt-in must NOT restage the auto grade",
            StudyKeyboard.gradeKeyStages(simplified = true, confirmGradeBeforeNext = true)
        )
        assertFalse(
            StudyKeyboard.gradeKeyStages(simplified = true, confirmGradeBeforeNext = false)
        )
        assertFalse(
            "Classic without the opt-in stays one tap",
            StudyKeyboard.gradeKeyStages(simplified = false, confirmGradeBeforeNext = false)
        )
        assertTrue(
            "Classic + opt-in is the only staging case",
            StudyKeyboard.gradeKeyStages(simplified = false, confirmGradeBeforeNext = true)
        )
    }
}
