package com.mcqapp.ui

import androidx.annotation.StringRes
import com.mcqapp.R

/**
 * The four outcomes a result row can have, shared by the results card, the CSV
 * export and the report.
 *
 * Colour is never the only signal: every verdict carries a localized label and
 * a non-colour glyph. The container/on-container pairs are resolved from the
 * active Material 3 roles in `ui/theme/VerdictColors.kt`, so custom light themes
 * are respected and the text stays legible on the card it sits on.
 */
enum class Verdict(
    @StringRes val labelRes: Int,
    val glyph: String
) {
    CORRECT(R.string.correct, "✓"),
    WRONG(R.string.wrong, "✗"),
    SKIPPED(R.string.skipped, "–"),
    UNGRADED(R.string.report_tag_ungraded, "?");

    /** Stable lower-case value written to the CSV `result` column. */
    val csvValue: String get() = name.lowercase()

    companion object {
        /**
         * One predicate for the screen, the export and the report. An absent
         * answer key is ungraded even when the learner picked something; only
         * a graded question with no selection is skipped.
         */
        fun of(
            correctOptionIds: Collection<String>,
            selectedOptionIds: Collection<String>,
            isCorrect: Boolean
        ): Verdict = when {
            correctOptionIds.isEmpty() -> UNGRADED
            selectedOptionIds.isEmpty() -> SKIPPED
            isCorrect -> CORRECT
            else -> WRONG
        }

        /** Same predicate for the stored, comma-joined id strings used by CSV. */
        fun of(
            correctOptionIds: String?,
            selectedOptionIds: String?,
            isCorrect: Boolean
        ): Verdict = of(
            splitIds(correctOptionIds),
            splitIds(selectedOptionIds),
            isCorrect
        )

        private fun splitIds(value: String?): Set<String> =
            if (value.isNullOrBlank()) emptySet()
            else value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
