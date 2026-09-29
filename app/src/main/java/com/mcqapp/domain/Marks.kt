package com.mcqapp.domain

/**
 * Marks parsing/formatting shared by the editor, bulk edit and import
 * paths: invalid, non-finite or negative input falls back to 1 mark, and
 * whole marks display without a decimal point.
 */
object Marks {

    fun parse(raw: String): Double =
        raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 1.0

    fun format(marks: Double): String =
        if (marks == kotlin.math.floor(marks) && !marks.isInfinite()) {
            marks.toLong().toString()
        } else {
            marks.toString()
        }
}
