package com.mcqapp.domain

/**
 * Display rules for answer feedback during a test.
 *
 * Exam mode reveals correctness only after an explicit "Show answer" tap
 * (which also locks the question). Practice mode colors options live as the
 * user taps and always shows the explanation — without touching selection
 * or scoring logic. Unkeyed (ungraded) questions have no key to check
 * against, so they never get correctness colors in either mode.
 */
object Feedback {

    fun liveReveal(practiceMode: Boolean, manuallyRevealed: Boolean, ungraded: Boolean): Boolean =
        !ungraded && (practiceMode || manuallyRevealed)

    fun showExplanation(practiceMode: Boolean, manuallyRevealed: Boolean): Boolean =
        practiceMode || manuallyRevealed

    /**
     * What to draw beside a revealed option: a tick for the correct answer,
     * a cross for the answer the user wrongly picked, and nothing at all for
     * an option that is neither. Deciding this in one place keeps a wrong
     * option from being marked with a tick.
     */
    enum class RevealMarker { CORRECT, WRONG, NONE }

    fun revealMarker(isCorrectOption: Boolean, selected: Boolean): RevealMarker = when {
        isCorrectOption -> RevealMarker.CORRECT
        selected -> RevealMarker.WRONG
        else -> RevealMarker.NONE
    }
}
