package com.mcqapp.util

/**
 * The shared numeric-entry pipeline for every numeric field in the app
 * (library dialogs, the import screen, the scheduler's settings rows).
 *
 * A comma-locale keyboard (pt-BR, de-DE, hi-IN…) inserts ',' for its decimal
 * key, so the per-screen digit filters silently deleted it: typing `0,25`
 * left `025` — worth 25 marks per wrong answer instead of a quarter — and on
 * the import screen the value-clause rewrite made `0.5` un-typeable at all.
 * Fold the comma into '.' FIRST, then apply whichever characters the field
 * accepts; parsing goes through [parseDecimalEntry] so all screens share one
 * fallback (0.0) for unparsable fragments.
 */
fun filterDecimalEntry(
    input: String,
    allowLeadingMinus: Boolean = false
): String {
    // Fold before filtering: `0,25` must survive as `0.25`, and a stray
    // leading minus is kept only when the field's range allows negatives.
    val folded = input.replace(',', '.')
    return folded.filter {
        it.isDigit() ||
            it == '.' ||
            (allowLeadingMinus && it == '-' && folded.indexOf('-') == 0)
    }
}

/** The one parse for every numeric field: comma-folded, 0.0 fallback. */
fun parseDecimalEntry(text: String): Double =
    text.replace(',', '.').toDoubleOrNull() ?: 0.0

/**
 * A blank optional marks field means "keep", but a non-blank one must be a
 * finite, non-negative number. Returning null for `0,25`-style junk lets the
 * caller refuse the whole edit instead of applying the other fields while
 * silently dropping marks.
 */
fun parseNonNegativeDecimal(text: String): Double? =
    text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
