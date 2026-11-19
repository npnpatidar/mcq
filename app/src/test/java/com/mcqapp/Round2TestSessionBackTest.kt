package com.mcqapp

import com.mcqapp.ui.test.TestBackAction
import com.mcqapp.ui.test.testBackAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1: the top-bar arrow must mirror system Back. System Back closes an open
 * palette first; the arrow used to pop the whole test behind that palette.
 * The decision is a pure seam in the screen, so both halves are JVM-testable.
 */
class Round2TestSessionBackTest {

    @Test
    fun arrowClosesThePaletteBeforeAnythingElse() {
        // Positive: an open palette always wins, even while a question is in
        // view (the palette is an inline Card, so the top bar stays live).
        assertEquals(TestBackAction.CLOSE_PALETTE, testBackAction(true, false, false))
        assertEquals(TestBackAction.CLOSE_PALETTE, testBackAction(true, true, false))
    }

    @Test
    fun arrowConfirmsSubmitForALiveQuestion() {
        // Negative of the palette case: with no palette, the confirm path is
        // kept — the learner is not dropped out of a half-done paper.
        assertEquals(TestBackAction.CONFIRM_SUBMIT, testBackAction(false, true, false))
    }

    @Test
    fun arrowPopsWithoutConfirmAndNeverConfirmsMidSave() {
        // Negative: no palette and nothing to confirm -> the ordinary pop path.
        assertEquals(TestBackAction.POP, testBackAction(false, false, false))
        // H1 guard: even a stale `backConfirmsSubmit` cannot open the dialog
        // once a save is in flight; the arrow resolves to the guarded popBack().
        assertEquals(TestBackAction.POP, testBackAction(false, true, true))
        assertEquals(TestBackAction.POP, testBackAction(false, false, true))
    }
}
