package com.mcqapp.domain

/**
 * Review-list filtering, extracted from the results screen so every branch
 * (including ungraded exclusion and the bookmarked subset) is unit-tested.
 */
object ReviewFilters {

    const val ALL = "All"
    const val CORRECT = "Correct"
    const val WRONG = "Wrong"
    const val SKIPPED = "Skipped"
    const val UNGRADED = "Ungraded"
    const val SAVED = "Saved"

    fun apply(
        results: List<QuestionResult>,
        filter: String,
        bookmarked: Set<String> = emptySet()
    ): List<QuestionResult> = when (filter) {
        CORRECT -> results.filter { it.isCorrect }
        WRONG -> results.filter {
            !it.isCorrect && it.selectedOptionIds.isNotEmpty() && it.correctOptionIds.isNotEmpty()
        }
        SKIPPED -> results.filter {
            it.selectedOptionIds.isEmpty() && it.correctOptionIds.isNotEmpty()
        }
        UNGRADED -> results.filter { it.correctOptionIds.isEmpty() }
        SAVED -> results.filter { it.questionId in bookmarked }
        else -> results
    }
}
