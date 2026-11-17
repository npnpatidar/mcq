package com.mcqapp.domain

/**
 * Marks parsing/formatting shared by the editor, bulk edit and import
 * paths: invalid, non-finite or negative input falls back to 1 mark, and
 * whole marks display without a decimal point.
 *
 * A comma is folded into a dot first, exactly like the bulk-edit field
 * ([com.mcqapp.util.NumericInput.parseNonNegativeDecimal]): a comma-locale
 * keyboard types `1,5` for one and a half marks, which must save.
 */
object Marks {

    fun parse(raw: String): Double =
        normalize(raw)?.takeIf { it.isFinite() && it >= 0.0 } ?: 1.0

    /**
     * Whether the raw field can be saved as typed. Blank is valid (the editor
     * documents it as 1 mark); anything else must be a finite, non-negative
     * number, so an invalid entry is reported instead of being silently
     * stored as 1 (question_editor_audit.md M-08). A comma is a valid decimal
     * separator (owner decision), but negatives, NaN and Infinity stay invalid.
     */
    fun isValid(raw: String): Boolean {
        if (raw.trim().isEmpty()) return true
        val value = normalize(raw) ?: return false
        return value.isFinite() && value >= 0.0
    }

    /** Trim, fold the comma-locale decimal separator, then parse. */
    private fun normalize(raw: String): Double? {
        val trimmed = raw.trim().replace(',', '.')
        if (trimmed.isEmpty()) return null
        return trimmed.toDoubleOrNull()
    }

    fun format(marks: Double): String =
        if (marks == kotlin.math.floor(marks) && !marks.isInfinite()) {
            marks.toLong().toString()
        } else {
            marks.toString()
        }
}
