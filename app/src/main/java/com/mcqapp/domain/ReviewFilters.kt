package com.mcqapp.domain

import androidx.annotation.StringRes
import com.mcqapp.R

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

    /** Object form of the [labelRes] extension, for callers with a key value. */
    @StringRes
    fun labelRes(filter: String): Int = filter.labelRes

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

/**
 * Localized label for a review filter key, e.g. `ReviewFilters.CORRECT.labelRes`.
 *
 * The filter is still stored and compared as its stable [String] key — SQL, the
 * paging source and saved state all use those keys — so the resource is an
 * extension rather than a replacement for the constants. Call sites resolve it
 * with `stringResource(labelRes)`; an unknown key falls back to the All label
 * instead of ever returning 0 (which would crash `stringResource`).
 */
@get:StringRes
val String.labelRes: Int
    get() = when (this) {
        ReviewFilters.ALL -> R.string.r2_res_filter_all
        ReviewFilters.CORRECT -> R.string.r2_res_filter_correct
        ReviewFilters.WRONG -> R.string.r2_res_filter_wrong
        ReviewFilters.SKIPPED -> R.string.r2_res_filter_skipped
        ReviewFilters.UNGRADED -> R.string.r2_res_filter_ungraded
        ReviewFilters.SLOW -> R.string.r2_res_filter_slow
        ReviewFilters.SAVED -> R.string.r2_res_filter_saved
        else -> R.string.r2_res_filter_all
    }
