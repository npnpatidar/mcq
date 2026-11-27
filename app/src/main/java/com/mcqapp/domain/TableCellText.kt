package com.mcqapp.domain

/**
 * One table cell's mixed grammar: prose with the bank's inline markup, and
 * complete inline MathML blocks, in the order they appear. A cell is stored
 * as a plain HTML/MathML string (no per-cell type), so every surface that
 * needs to tell a formula apart from prose goes through [tableCellParts].
 *
 * Pure JVM: no Compose, no Android. Markers (`[image]`, `[formula]`) are a
 * view concern and stay in `com.mcqapp.util`, which replaces pictures inside
 * [Text] parts and inside a [Math] part (as `<mtext>[image]</mtext>`, so the
 * marker is one MathML text run) — this file keeps both verbatim. A data URI
 * left inside either part would render as visible base64, or be spoken by
 * TalkBack.
 */
internal sealed interface CellPart {
    /** Prose (or any inline markup) between formulas, verbatim. */
    data class Text(val html: String) : CellPart

    /** One complete `<math …>…</math>` block, raw, exactly as stored. */
    data class Math(val mathml: String) : CellPart
}

/**
 * A real cell formula: `<math` (case-insensitive) followed by `>` or
 * whitespace, up to the first `</math>`. A `<math` with no closing tag is
 * prose and stays visible; literal `[formula]` text or escaped
 * `&lt;math&gt;` is never a formula.
 */
internal val CELL_MATH_PATTERN = Regex(
    "<math[\\s>].*?</math\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** Splits [cell] into ordered prose/math parts; an empty cell has no parts. */
internal fun tableCellParts(cell: String): List<CellPart> {
    val parts = mutableListOf<CellPart>()
    var start = 0
    for (match in CELL_MATH_PATTERN.findAll(cell)) {
        if (match.range.first > start) parts += CellPart.Text(cell.substring(start, match.range.first))
        parts += CellPart.Math(match.value)
        start = match.range.last + 1
    }
    if (start < cell.length) parts += CellPart.Text(cell.substring(start))
    return parts
}
