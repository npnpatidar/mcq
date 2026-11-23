package com.mcqapp

import com.mcqapp.ui.study.StudyDigitGrade
import com.mcqapp.ui.study.StudyKey
import com.mcqapp.ui.study.StudyKeyAction
import com.mcqapp.ui.study.StudyKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pure-JVM coverage for the keyboard-shortcut decision seam. */
class StudyKeyboardTest {

    private val screen by lazy {
        File("src/main/java/com/mcqapp/ui/study/StudyScreen.kt").readText()
    }
    private val viewModel by lazy {
        File("src/main/java/com/mcqapp/ui/study/StudyViewModel.kt").readText()
    }

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
    fun spaceDoesNotRevealWhenTheModeSaysRevealIsNotAllowed() {
        // Simplified's keyed card: Check is the only pre-answer action and it
        // stages the grade. Space revealing alone left pendingGrade null, so
        // Next and Change grade were dead and the card could never be graded.
        assertEquals(
            StudyKeyAction.NONE,
            StudyKeyboard.action(
                StudyKey.SPACE, revealed = false, staged = false, canGrade = true,
                revealAllowed = false
            )
        )
        // A Simplified self-graded card still uses Space as Show answer.
        assertEquals(
            StudyKeyAction.REVEAL,
            StudyKeyboard.action(
                StudyKey.SPACE, revealed = false, staged = false, canGrade = true,
                revealAllowed = true
            )
        )
    }

    @Test
    fun screenWiresRevealAllowedAndTheStuckGuard() {
        assertTrue(
            "Study must pass revealAllowed = !simplified || selfGraded",
            screen.contains("revealAllowed = !state.simplified || state.selfGraded")
        )
        assertTrue(
            "a stuck card must not look staged to the keyboard",
            screen.contains("staged = state.pendingGrade != null && !state.gradedAndStuck")
        )
        assertTrue(
            "a stuck card must not accept a digit grade",
            screen.contains("canGrade = !state.grading && !state.onBreak && !state.gradedAndStuck")
        )
        assertTrue(
            "next() must return before it claims a graded-and-stuck card",
            functionOf(viewModel, "fun next() {").contains("if (current.gradedAndStuck) return")
        )
        assertTrue(
            "grade() must return before it claims a graded-and-stuck card",
            functionOf(viewModel, "fun grade(grade: ReviewGrade) {")
                .contains("if (current.gradedAndStuck) return")
        )
    }

    @Test
    fun simplifiedDigitsChangeTheStagedProposalInsteadOfWritingChosen() {
        // Simplified keyed card: the digit must edit Check's proposal so Next
        // keeps proposedGrade, the guess flag and EDITED provenance.
        assertEquals(
            StudyDigitGrade.CHANGE,
            StudyKeyboard.digitGradeAction(
                simplified = true, confirmGradeBeforeNext = false, selfGraded = false
            )
        )
        assertEquals(
            StudyDigitGrade.CHANGE,
            StudyKeyboard.digitGradeAction(
                simplified = true, confirmGradeBeforeNext = true, selfGraded = false
            )
        )
        // A Simplified self-graded card has no Check and no proposal: one tap.
        assertEquals(
            StudyDigitGrade.GRADE,
            StudyKeyboard.digitGradeAction(
                simplified = true, confirmGradeBeforeNext = true, selfGraded = true
            )
        )
        assertEquals(
            StudyDigitGrade.STAGE,
            StudyKeyboard.digitGradeAction(
                simplified = false, confirmGradeBeforeNext = true, selfGraded = false
            )
        )
        assertEquals(
            StudyDigitGrade.GRADE,
            StudyKeyboard.digitGradeAction(
                simplified = false, confirmGradeBeforeNext = false, selfGraded = false
            )
        )
        assertTrue(
            "gradeByKey must switch on the pure decision",
            screen.contains("StudyKeyboard.digitGradeAction(") &&
                screen.contains("StudyDigitGrade.CHANGE -> viewModel.changeGrade(grade)")
        )
    }

    /** One function body, from its signature to its closing brace. */
    private fun functionOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is missing", start >= 0)
        val end = source.indexOf("\n    }\n", start)
        assertTrue("$signature has no closing brace", end > start)
        return source.substring(start, end)
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
