package com.mcqapp.ui.test

import com.mcqapp.ui.study.StudyKey
import com.mcqapp.ui.study.StudyKeyAction

/**
 * The Test counterpart of [com.mcqapp.ui.study.StudyKeyboard], kept pure so the
 * shortcut rules are provable on the JVM without a Compose host. It reuses the
 * same key/action vocabulary so the two screens cannot drift on which physical
 * key means what.
 *
 * Test semantics differ from Study in two deliberate ways:
 *  - Space shows the answer, then advances once it is already shown. There is
 *    no confirm-grade "staged" state in a test.
 *  - A test has no grades (ungraded questions are never sampled into one), so
 *    the digit keys are inert. They are only wired where a grade exists — the
 *    Study screen handles 1-4 there; here they never grade or navigate.
 *
 * Arrows move between questions. The UI owns the focus/dialog check and the
 * modifier-key guard, and calls the existing ViewModel navigation/reveal
 * methods, so strict-mode and auto-advance behaviour is untouched.
 */
internal object TestKeyboard {
    fun action(key: StudyKey, revealed: Boolean): StudyKeyAction = when (key) {
        StudyKey.SPACE -> if (revealed) StudyKeyAction.NEXT else StudyKeyAction.REVEAL
        StudyKey.LEFT -> StudyKeyAction.SELECT_PREVIOUS_OPTION
        StudyKey.RIGHT -> StudyKeyAction.SELECT_NEXT_OPTION
        StudyKey.ONE, StudyKey.TWO, StudyKey.THREE, StudyKey.FOUR, StudyKey.OTHER ->
            StudyKeyAction.NONE
    }
}
