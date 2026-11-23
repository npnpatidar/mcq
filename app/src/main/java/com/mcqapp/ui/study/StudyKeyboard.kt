package com.mcqapp.ui.study

/**
 * Pure keyboard-shortcut decisions for Study (and reusable by Test).
 *
 * Keeping the key -> action mapping free of Compose and Android types makes the
 * mode rules — reveal, staged grades and the break lock — testable on the JVM.
 * The UI layer owns focus and decides whether a dialog is open; neither Study
 * nor Test has a text-entry surface on its keyed card/session area, so a
 * shortcut never competes with typing. This object only decides what a key
 * means for a given screen state.
 */
internal enum class StudyKey {
    SPACE, ONE, TWO, THREE, FOUR, LEFT, RIGHT, OTHER
}

internal enum class StudyKeyAction {
    NONE,
    REVEAL,
    NEXT,
    GRADE_AGAIN,
    GRADE_HARD,
    GRADE_GOOD,
    GRADE_EASY,
    SELECT_PREVIOUS_OPTION,
    SELECT_NEXT_OPTION
}

internal object StudyKeyboard {

    /**
     * @param revealed whether the answer is on screen; grades never fire before
     *   reveal, so a learner cannot grade what they have not seen.
     * @param staged whether a confirmation-style grade is pending; Space then
     *   advances instead of revealing again.
     * @param onBreak a running break owns the screen: no key may reveal,
     *   advance, grade or move the selection until the learner resumes.
     */
    fun action(
        key: StudyKey,
        revealed: Boolean,
        staged: Boolean,
        canGrade: Boolean,
        onBreak: Boolean = false
    ): StudyKeyAction = if (onBreak) {
        StudyKeyAction.NONE
    } else {
        when (key) {
            StudyKey.SPACE -> when {
                !revealed -> StudyKeyAction.REVEAL
                staged -> StudyKeyAction.NEXT
                else -> StudyKeyAction.NONE
            }
            StudyKey.ONE, StudyKey.TWO, StudyKey.THREE, StudyKey.FOUR ->
                if (!revealed || !canGrade) StudyKeyAction.NONE else when (key) {
                    StudyKey.ONE -> StudyKeyAction.GRADE_AGAIN
                    StudyKey.TWO -> StudyKeyAction.GRADE_HARD
                    StudyKey.THREE -> StudyKeyAction.GRADE_GOOD
                    else -> StudyKeyAction.GRADE_EASY
                }
            StudyKey.LEFT -> StudyKeyAction.SELECT_PREVIOUS_OPTION
            StudyKey.RIGHT -> StudyKeyAction.SELECT_NEXT_OPTION
            StudyKey.OTHER -> StudyKeyAction.NONE
        }
    }
}
