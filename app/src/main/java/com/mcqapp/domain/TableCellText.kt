package com.mcqapp.domain

/**
 * One table cell's mixed grammar: prose with the bank's inline markup, and
 * formulas, in the order they appear. A cell is stored as a plain HTML/MathML
 * string (no per-cell type), so every surface that needs to tell a formula
 * apart from prose goes through [tableCellParts].
 *
 * Besides a complete `<math>…</math>` block, the grammar repairs a bare run of
 * allow-listed MathML elements (the shape MathLive's `getValue('math-ml')`
 * returned to the buggy editor build) into a complete block on read. That
 * repair applies everywhere the shared grammar is used, so an already-stored
 * fragment renders and exports as a formula without a data migration.
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

    /**
     * One complete `<math>…</math>` block. A stored block is kept raw; a bare
     * fragment repaired by [tableCellParts] is wrapped, and its inner elements
     * are still exactly as stored (each renderer sanitises attributes).
     */
    data class Math(val mathml: String) : CellPart
}

/**
 * MathML elements allowed inside a `<math>` block, shared by the renderer's
 * sanitizer and the bare-fragment repair below. `math` is the block root and
 * is deliberately absent: a complete block is handled before a bare run.
 */
internal val CELL_MATH_ELEMENTS: Set<String> = setOf(
    "mrow", "mi", "mn", "mo", "mtext", "ms", "mspace",
    "mfrac", "msqrt", "mroot", "msup", "msub", "msubsup",
    "munder", "mover", "munderover", "mtable", "mtr", "mtd",
    "mstyle", "mpadded", "mphantom", "mfenced", "menclose",
    "semantics", "annotation"
)

/**
 * Longest bare run that may be repaired into a formula. MathLive values are
 * short; the cap keeps a hostile megabyte-long cell from being rescanned in
 * pieces and bounds the work one cell can cost. A longer candidate stays
 * prose.
 */
internal const val MAX_BARE_MATH_RUN_CHARS = 16 * 1024

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

/** One well-formed tag token; the text around it is scanned separately. */
private val CELL_MATH_TAG_PATTERN = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^<>]*)?/?>")

/** `/>`, with optional whitespace before `>` (`<mspace/ >`). */
private val SELF_CLOSING_SUFFIX = Regex("/\\s*>$")

private fun isSelfClosingTag(token: String): Boolean =
    SELF_CLOSING_SUFFIX.containsMatchIn(token)

/** The one closing tag a formula block can end at, case-insensitively. */
private val CLOSING_MATH_PATTERN = Regex("</math\\s*>", RegexOption.IGNORE_CASE)

/** Splits [cell] into ordered prose/math parts; an empty cell has no parts. */
internal fun tableCellParts(cell: String): List<CellPart> {
    if (cell.isEmpty()) return emptyList()
    val parts = mutableListOf<CellPart>()
    var textStart = 0
    var pos = 0
    while (pos < cell.length) {
        val lt = cell.indexOf('<', pos)
        if (lt < 0) break
        if (isMathBlockStart(cell, lt)) {
            val end = mathBlockEnd(cell, lt)
            // An unclosed <math> is prose, and everything after it stays prose:
            // this is the long-standing contract for a torn/legacy cell, and
            // it also keeps the scan linear on a cell full of `<math>` starts.
            if (end < 0) break
            if (lt > textStart) parts += CellPart.Text(cell.substring(textStart, lt))
            parts += CellPart.Math(cell.substring(lt, end))
            textStart = end
            pos = end
            continue
        }
        when (val scan = scanBareMath(cell, lt)) {
            is BareMathScan.Found -> {
                if (lt > textStart) parts += CellPart.Text(cell.substring(textStart, lt))
                parts += CellPart.Math(
                    wrapBareMathRun(bareRunWithoutAttributes(scan.run), scan.soleMrow)
                )
                textStart = scan.end
                pos = scan.end
            }
            // Nothing was repaired from this `<`: skip the part the scan had
            // to read. A skipped region can hold no complete top-level MathML
            // element (a complete one would have been returned as Found), so
            // no formula is lost, and the overall scan stays linear on hostile
            // input instead of retrying every nested tag.
            is BareMathScan.Missing -> pos = maxOf(lt + 1, scan.resumeAt)
        }
    }
    if (textStart < cell.length) parts += CellPart.Text(cell.substring(textStart))
    return parts
}

/** True when [at] begins a `<math …>` / `<math>` opener (not `<mathx>`). */
private fun isMathBlockStart(cell: String, at: Int): Boolean {
    if (at + 5 > cell.length) return false
    if (!cell.regionMatches(at, "<math", 0, 5, ignoreCase = true)) return false
    if (at + 5 >= cell.length) return false
    val next = cell[at + 5]
    return next == '>' || next.isWhitespace()
}

/** End (exclusive) of the block starting at [at], or -1 when unclosed. */
private fun mathBlockEnd(cell: String, at: Int): Int =
    CLOSING_MATH_PATTERN.find(cell, at + 5)?.range?.last?.plus(1) ?: -1

/**
 * Visit every complete `<math>…</math>` block in [cell], in order, handing out
 * its half-open span. One forward scan: from each opener, [mathBlockEnd] finds
 * the one closing tag and the scan resumes after the whole block. A cell full
 * of unclosed `<math` starts is read once — the first failed lookup proves no
 * later opener can close either, so the scan stops instead of lazily
 * re-reading the tail from every start (the old `findAll` was quadratic there).
 *
 * The block grammar is the same one [tableCellParts] and [mathBlockEnd] use,
 * so a surface can never disagree with the cell splitter about what a formula
 * is. Blocks inside a block (nested `<math`) stay part of the outer block,
 * exactly as the lazy pattern matched them.
 */
internal inline fun forEachCellMathBlock(
    cell: String,
    onMath: (start: Int, end: Int) -> Unit
) {
    var pos = 0
    while (pos < cell.length) {
        val lt = cell.indexOf("<math", pos, ignoreCase = true)
        if (lt < 0) return
        if (!isMathBlockStart(cell, lt)) {
            pos = lt + 1
            continue
        }
        val end = mathBlockEnd(cell, lt)
        // No closing tag at or after this opener means none exists for any
        // later opener either: one failed lookup ends the scan.
        if (end < 0) return
        onMath(lt, end)
        pos = end
    }
}

/**
 * What one bare-MathML scan found.
 *
 * A stored cell written by the buggy editor build carries MathLive's
 * `getValue('math-ml')` verbatim: a run of allow-listed MathML elements with
 * no `<math>` root (`<mrow><msup>…</msup></mrow>`, or sibling elements such as
 * `<mi>x</mi><mo>+</mo><mn>1</mn>`). [Found] reports such a run so the cell
 * grammar can promote it to a real formula without a data migration; the run
 * still holds no `<math>` wrapper (see [wrapBareMathRun]).
 */
internal sealed interface BareMathScan {
    /** A balanced run ending at [end], with [run] exactly `cell[start until end]`. */
    data class Found(
        val end: Int,
        val run: String,
        /** The run is a single top-level `mrow`, already the wrapper MathJax needs. */
        val soleMrow: Boolean
    ) : BareMathScan

    /**
     * No balanced run starts at this `<`. [resumeAt] is the first position the
     * caller may resume scanning from: everything before it was read by this
     * attempt and contains no complete top-level element.
     */
    data class Missing(val resumeAt: Int) : BareMathScan
}

/** The result of one escaped-tag scan and how many characters it examined. */
internal data class EscapedTagScan(val found: Boolean, val visits: Int)

/**
 * True when [text] carries an escaped allow-listed tag (`&lt;mi&gt;`), which
 * is literal markup a producer wanted to show, not MathML content. An escaped
 * operator or any other character reference does not qualify.
 *
 * One linear pass: from each `&lt;`, look for the next `&gt;` and inspect that
 * one token, then continue after it. [visits] counts the characters examined,
 * so a test can prove the pass is linear — the old pattern rescanned to the
 * chunk end from every `&lt;` start and was quadratic on a hostile cell.
 */
internal fun scanEscapedMathTag(text: String): EscapedTagScan {
    var visits = 0
    var i = 0
    while (i < text.length) {
        visits++
        if (text[i] == '&' && text.startsWith("&lt;", i, ignoreCase = true)) {
            var gt = i + 4
            while (gt < text.length) {
                visits++
                if (text[gt] == '&' && text.startsWith("&gt;", gt, ignoreCase = true)) break
                gt++
            }
            if (gt >= text.length) return EscapedTagScan(false, visits)
            var nameStart = i + 4
            if (nameStart < gt && text[nameStart] == '/') nameStart++
            var nameEnd = nameStart
            while (nameEnd < gt) {
                visits++
                if (!text[nameEnd].isLetterOrDigit()) break
                nameEnd++
            }
            val name = text.substring(nameStart, nameEnd).lowercase()
            if (name == "math" || name in CELL_MATH_ELEMENTS) return EscapedTagScan(true, visits)
            i = gt + 4
            continue
        }
        i++
    }
    return EscapedTagScan(false, visits)
}

private fun isEscapedMathTag(text: String): Boolean = scanEscapedMathTag(text).found

/**
 * Linear scan of a bare MathML candidate starting at [start].
 *
 * Only a balanced sequence of [CELL_MATH_ELEMENTS] is accepted. Text is
 * allowed between tags only while it sits inside one of those elements; at the
 * top level only whitespace may separate sibling elements, so ordinary prose
 * ends the run instead of being swallowed. A run containing an HTML tag
 * (`<b>`, `<span>`, `<img>`), an escaped tag (`&lt;mrow&gt;`) or a malformed
 * tag is not repaired and stays prose — the conservative rule that keeps a
 * legacy cell's visible text untouched. An escaped comparison operator
 * (`<mo>&lt;</mo>`) is math content, not escaped markup, and does not
 * disqualify the run. The scan is bounded by
 * [MAX_BARE_MATH_RUN_CHARS] and never reads a character twice in one attempt.
 */
internal fun scanBareMath(cell: String, start: Int): BareMathScan {
    if (start < 0 || start >= cell.length || cell[start] != '<') {
        return BareMathScan.Missing(minOf(cell.length, maxOf(0, start + 1)))
    }
    val limit = minOf(cell.length, start + MAX_BARE_MATH_RUN_CHARS)
    /** True when more characters follow the cap: the candidate was cut short. */
    val capExceeded = limit < cell.length
    val stack = ArrayDeque<String>()
    var pos = start
    var lastCompleteEnd = -1
    var topLevelCount = 0
    var soleMrow = false

    fun found(): BareMathScan? {
        if (lastCompleteEnd <= start) return null
        return BareMathScan.Found(
            end = lastCompleteEnd,
            run = cell.substring(start, lastCompleteEnd),
            soleMrow = soleMrow
        )
    }

    fun failed(resumeAt: Int): BareMathScan =
        found() ?: BareMathScan.Missing(maxOf(start + 1, minOf(cell.length, resumeAt)))

    fun completed(name: String, end: Int) {
        lastCompleteEnd = end
        topLevelCount++
        soleMrow = topLevelCount == 1 && name == "mrow"
    }

    while (pos < limit) {
        // Scan to the next '<' so a malformed or non-tag '<' is text, not a
        // reason to rescan the rest of the cell with a regex search.
        val lt = cell.indexOf('<', pos)
        val chunkEnd = if (lt < 0 || lt >= limit) limit else lt
        if (chunkEnd > pos) {
            val chunk = cell.substring(pos, chunkEnd)
            if (stack.isEmpty()) {
                // Sibling elements may only be separated by whitespace: any
                // other text is prose, and the run ends before it.
                if (chunk.isNotBlank()) return failed(pos)
            } else if (isEscapedMathTag(chunk)) {
                // An escaped tag inside the candidate means this is not a
                // fragment MathLive produced; keep it visible. An escaped
                // comparison operator (`&lt;`) or other token text is math
                // content, not escaped markup, and does not disqualify the
                // run — MathLive writes `\text{a < b}` exactly that way.
                return failed(pos)
            }
            pos = chunkEnd
            if (pos >= limit) break
        }
        // Anchored at the '<': no search means each character is visited once,
        // however many malformed tags a hostile cell carries.
        val match = CELL_MATH_TAG_PATTERN.matchAt(cell, pos) ?: return failed(pos)
        val closing = match.groupValues[1] == "/"
        val name = match.groupValues[2].lowercase()
        // An HTML/unknown tag is never part of a math run. Decide this BEFORE
        // the length cap: a >16 KiB <img> before a formula used to consume the
        // scan budget here and end the whole cell as prose, hiding the formula
        // that followed it.
        if (name !in CELL_MATH_ELEMENTS) return failed(pos)
        val tokenEnd = match.range.last + 1
        if (tokenEnd > limit) {
            // An over-cap allow-listed tag only skips the rest of the cell
            // when a math run is actually in progress (or already completed):
            // then the tag is part of one over-long run, and rescanning from
            // inside it would promote a suffix to a formula. A lone over-cap
            // tag starts no run, so resume past it instead.
            val runInProgress = stack.isNotEmpty() || lastCompleteEnd > start
            return if (capExceeded && runInProgress) {
                BareMathScan.Missing(cell.length)
            } else {
                failed(pos)
            }
        }
        if (closing) {
            if (stack.isEmpty() || stack.last() != name) return failed(pos)
            stack.removeLast()
            pos = tokenEnd
            if (stack.isEmpty()) completed(name, pos)
        } else {
            pos = tokenEnd
            if (isSelfClosingTag(match.value)) {
                if (stack.isEmpty()) completed(name, pos)
            } else {
                stack.addLast(name)
            }
        }
    }
    if (capExceeded) {
        // Reaching the cap without finding a top-level prose chunk means the
        // run was still continuing (another sibling, or a tag cut in half).
        // Repairing the prefix would turn one over-long run into several
        // formulas, so the whole cell stays prose. A SHORT run followed by
        // long prose exits earlier through failed(pos) and is still promoted.
        return BareMathScan.Missing(cell.length)
    }
    if (stack.isNotEmpty()) return failed(limit)
    return found() ?: BareMathScan.Missing(minOf(cell.length, limit))
}

/**
 * [run] with every tag reduced to its bare allow-listed name. The scanner has
 * already admitted only known elements, so this only removes attributes — a
 * hostile stored fragment must not carry `onclick` into an export (Anki
 * re-emits formula markup as-is). The self-closing form is preserved: a
 * `<mspace/>` is one empty element, not a container.
 */
private fun bareRunWithoutAttributes(run: String): String =
    CELL_MATH_TAG_PATTERN.replace(run) { match ->
        val closing = match.groupValues[1] == "/"
        val name = match.groupValues[2].lowercase()
        when {
            closing -> "</$name>"
            isSelfClosingTag(match.value) -> "<$name/>"
            else -> "<$name>"
        }
    }

/**
 * [run] as a complete MathML block. A single top-level `mrow` is already the
 * wrapper MathJax wants; anything else (siblings, a lone `mi`, a self-closing
 * element) needs one.
 */
internal fun wrapBareMathRun(run: String, soleMrow: Boolean): String {
    val inner = run.trim()
    return if (soleMrow) "<math>$inner</math>" else "<math><mrow>$inner</mrow></math>"
}
