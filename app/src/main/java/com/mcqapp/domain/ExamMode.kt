package com.mcqapp.domain

/**
 * Visibility matrix for test aids. Strict exam mode hides reveal, flags
 * and the question palette; it also suppresses practice-mode live
 * feedback (the two modes contradict, strict wins).
 */
object ExamMode {

    fun canReveal(practiceMode: Boolean, strictMode: Boolean): Boolean =
        !practiceMode && !strictMode

    fun canFlag(strictMode: Boolean): Boolean = !strictMode

    fun canOpenPalette(strictMode: Boolean): Boolean = !strictMode

    /**
     * Strict mode hides the *status* a palette shows (answered, flagged,
     * current) but not the ability to move to a numbered question. A learner
     * at Q80 must be able to revisit Q12 without being shown any answer feedback.
     */
    fun paletteShowsFeedback(strictMode: Boolean): Boolean = !strictMode

    /** Both modes get a numbered jump grid; only its decoration differs. */
    fun canJumpToQuestion(strictMode: Boolean): Boolean = true

    fun effectivePractice(practiceMode: Boolean, strictMode: Boolean): Boolean =
        practiceMode && !strictMode
}
