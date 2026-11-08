package com.mcqapp.domain

/**
 * The results-review chips (section 11.12).
 *
 * [SLOW] is a dwell threshold rather than a verdict, so the caller supplies
 * it: the report's [TimingSummary.slowThresholdSeconds] is 2× the median or
 * 60 s, whichever is larger.
 */
object ReviewFilters {

    const val ALL = "All"
    const val CORRECT = "Correct"
    const val WRONG = "Wrong"
    const val SKIPPED = "Skipped"
    const val UNGRADED = "Ungraded"
    const val SLOW = "Slow"
    const val SAVED = "Saved"

    /** The default Slow threshold when the caller has no median yet. */
    const val DEFAULT_SLOW_SECONDS: Long = 60

    fun apply(
        results: List<QuestionResult>,
        filter: String,
        bookmarked: Set<String> = emptySet(),
        slowSeconds: Long = DEFAULT_SLOW_SECONDS
    ): List<QuestionResult> = when (filter) {
        CORRECT -> results.filter { it.isCorrect }
        WRONG -> results.filter {
            !it.isCorrect && it.selectedOptionIds.isNotEmpty() && it.correctOptionIds.isNotEmpty()
        }
        SKIPPED -> results.filter {
            it.selectedOptionIds.isEmpty() && it.correctOptionIds.isNotEmpty()
        }
        UNGRADED -> results.filter { it.correctOptionIds.isEmpty() }
        SLOW -> results.filter { it.dwellSeconds >= slowSeconds }
        SAVED -> results.filter { it.questionId in bookmarked }
        else -> results
    }
}
