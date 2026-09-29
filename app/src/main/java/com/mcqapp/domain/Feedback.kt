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
}
