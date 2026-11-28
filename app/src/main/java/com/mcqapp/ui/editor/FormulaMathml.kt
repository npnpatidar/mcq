package com.mcqapp.ui.editor

import com.mcqapp.data.normalizeMathText
import com.mcqapp.data.sanitizeMathml
import com.mcqapp.data.sanitizeMathmlReporting
import com.mcqapp.domain.BareMathScan
import com.mcqapp.domain.CELL_MATH_PATTERN
import com.mcqapp.domain.scanBareMath
import com.mcqapp.domain.wrapBareMathRun
import com.mcqapp.util.CellEditPart
import com.mcqapp.util.stripMathAttributes

/**
 * Longest formula the MathLive bridge may store. A real formula is a few
 * hundred characters; the cap only rejects a runaway/hostile serialisation
 * instead of letting it into the question row, its content hash and backup.
 */
internal const val MAX_FORMULA_MATHML_CHARS = 32 * 1024

/**
 * The one canonical form of a formula arriving from MathLive.
 *
 * `mf.getValue('math-ml')` is **not** a document: it serialises the field's
 * atoms, so `x²` arrives as a bare `<msup>…</msup>`, or a run of siblings such
 * as `<mi>x</mi><mo>+</mo><mn>1</mn>`. It used to be committed verbatim, and
 * every surface then printed the raw XML: the complete `<math>` block is what
 * [CELL_MATH_PATTERN] and every renderer recognise.
 *
 * - a value containing a `<math …>…</math>` block goes through the existing
 *   [stripMathAttributes] pipeline (attributes dropped, inner markup
 *   allow-listed, `<mrow>` ensured);
 * - a bare run of allow-listed MathML elements is sanitised and wrapped as
 *   `<math><mrow>…</mrow></math>` (or `<math>…</math>` when the run is one
 *   `mrow`);
 * - anything else is emitted as inert escaped text — never as live markup.
 *
 * Idempotent and cheap: it runs for every keystroke, and its own output is a
 * complete block that the first branch canonicalises unchanged.
 */
internal fun normalizeFormulaMathml(value: String): String {
    if (value.isBlank()) return ""
    if (value.length > MAX_FORMULA_MATHML_CHARS) return ""
    val trimmed = value.trim()
    // One formula, one block. Keeping the block alone (rather than escaped
    // prose around it) is what makes a second pass byte-identical: escaped
    // prose would be escaped again. MathLive never reports prose here.
    CELL_MATH_PATTERN.find(trimmed)?.let { return stripMathAttributes(it.value) }
    val scan = scanBareMath(trimmed, 0)
    if (scan !is BareMathScan.Found || scan.end != trimmed.length) {
        // Not a complete MathML run (prose, HTML, an escaped fragment, or a
        // torn tag). Emit inert visible text: even an allow-listed tag shape
        // is escaped here, because this value was not accepted as MathML and
        // must not reach a JavaScript-enabled page as live markup.
        return inertFormulaText(trimmed)
    }
    val (sanitized, escapedUnknownTag) = sanitizeMathmlReporting(scan.run)
    // The scanner only admits allow-listed tags, so this cannot happen; keep
    // the inert form if it ever does rather than wrapping attacker text.
    if (escapedUnknownTag) return inertFormulaText(sanitized)
    // Tokenize bare text in containers the way stripMathAttributes does, so
    // this output is a fixed point (a second pass through the <math> branch
    // would tokenize it and otherwise change the value).
    val normalized = normalizeMathText(sanitized)
    return wrapBareMathRun(normalized, scan.soleMrow)
}

/**
 * [value] as text that cannot be markup: the sanitizer first escapes unknown
 * tags, then every remaining angle bracket is escaped too. Character
 * references (`&lt;`, `&amp;`, …) are already text and stay readable.
 */
private fun inertFormulaText(value: String): String =
    sanitizeMathml(value)
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/**
 * The cell formula dialog's commit, from confirm button to stored part.
 *
 * The dialog is composable but the contract is not: this is the single place
 * its [mathml] (MathLive's atom run) becomes the complete block every surface
 * reads, so a pure test can prove the commit and a future edit cannot quietly
 * store the raw fragment again.
 */
internal fun cellFormulaCommit(
    blockId: String,
    row: Int,
    col: Int,
    partIndex: Int?,
    mathml: String,
    onSetCellPart: ((String, Int, Int, Int?, CellEditPart) -> Unit)?
) {
    onSetCellPart?.invoke(
        blockId, row, col, partIndex,
        CellEditPart.Formula(normalizeFormulaMathml(mathml))
    )
}
