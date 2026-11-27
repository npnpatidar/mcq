package com.mcqapp.data.anki

import com.mcqapp.data.escapeHtmlText
import com.mcqapp.data.renderInlineHtml
import com.mcqapp.domain.CELL_MATH_PATTERN
import com.mcqapp.util.forEachCellImageSpan
import com.mcqapp.util.imgTagSrc
import com.mcqapp.util.portableCellImageSrc

/**
 * Converts Anki notefield HTML into app text, and back.
 *
 * Inline formatting (`<b>`, `<sub>`, …) survives the trip: import keeps
 * it in text runs ([toRichText]) and export re-emits it, while layout
 * wrappers (`<center>`, aligned `<div>`) are dropped — cards render
 * left-aligned. Answer detection and blank checks still use [toPlainText].
 */
object AnkiHtml {

    private val TAG = Regex("<[^>]*>")
    private val BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val BLOCK_BREAK = Regex("</(p|div|li|tr|h[1-6])\\s*>", RegexOption.IGNORE_CASE)
    /** Layout wrappers a cell does not store; their content stays inline. */
    private val BLOCK_OPEN = Regex("<(p|div|li|tr|h[1-6])\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val RICH_TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*>")
    private val WHITESPACE = Regex("[ \\t]+")
    private val BLANK_LINES = Regex("\n{3,}")
    /**
     * One character reference: decimal or hex (`[xX]`, because exporters
     * write both), or a named one. The same shape as the decoders in
     * `data/docx/MathMlToOmml.kt` and `data/export/PdfMath.kt`.
     */
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

    /** The named references that resolve; any other name stays as written. */
    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "amp" to "&"
    )

    /** Inline formatting tags kept verbatim by [toRichText]. */
    private val RICH_KEEP = setOf(
        "b", "strong", "i", "em", "u", "del", "s", "strike",
        "sub", "sup", "mark"
    )

    /** The class token of the canonical cell chem marker (`util/ChemCell.kt`). */
    private const val CHEM_CELL_CLASS = "mcq-chem-cell"

    /**
     * One chem marker span: quoted class attribute carrying the token, then a
     * body with no child markup (an emitted marker's body is escaped text).
     * A malformed or nested span does not match and is escaped as prose by
     * the normal cell pipeline.
     */
    private val CHEM_CELL_SPAN = Regex(
        "<span\\b[^<>]*\\bclass\\s*=\\s*([\"'])[^\"']*\\bmcq-chem-cell\\b[^\"']*\\1[^<>]*>([^<]*)</span\\s*>",
        RegexOption.IGNORE_CASE
    )

    fun toPlainText(html: String?): String {
        if (html == null) return ""
        var text = BLOCK_BREAK.replace(html, "\n")
        text = BREAK.replace(text, "\n")
        text = TAG.replace(text, "")
        text = decodeEntities(text)
        text = WHITESPACE.replace(text, " ")
        text = text.lines().joinToString("\n") { it.trim() }
        return BLANK_LINES.replace(text, "\n\n").trim()
    }

    /**
     * Like [toPlainText], but inline formatting tags survive: `<center>`
     * and other layout wrappers vanish with their content kept inline,
     * block closings still break lines. Used for element text; answer
     * detection and blank checks stay on [toPlainText].
     */
    fun toRichText(html: String?): String {
        if (html == null) return ""
        var text = BLOCK_BREAK.replace(html, "\n")
        text = BREAK.replace(text, "\n")
        val sb = StringBuilder()
        var pos = 0
        for (match in RICH_TAG.findAll(text)) {
            sb.append(text.substring(pos, match.range.first))
            if (match.groupValues[2].lowercase() in RICH_KEEP) sb.append(match.value)
            pos = match.range.last + 1
        }
        sb.append(text.substring(pos))
        text = decodeEntities(sb.toString())
        text = WHITESPACE.replace(text, " ")
        text = text.lines().joinToString("\n") { it.trim() }
        return BLANK_LINES.replace(text, "\n\n").trim()
    }

    /**
     * The cell variant of [toRichText], used by the table parser instead of
     * dropping everything but the formatting tags.
     *
     * A cell is stored as an HTML/MathML string, so the parts a table can
     * carry have to survive: inline formatting (as in [toRichText]), a
     * complete `<img>` re-emitted with only its `src` (the reader has already
     * rewritten `src` to the inline data URI, so the picture lands in the cell
     * in the canonical form), and a `<math>…</math>` block kept verbatim
     * exactly as a top-level formula is. Pictures are found with the shared
     * quote-aware cell scanner, so a `>` inside a quoted attribute cannot
     * truncate the tag and leak its remainder as prose. Layout wrappers are
     * dropped with their content kept inline, any unknown tag is escaped as
     * visible text rather than silently vanishing, a torn `<img` — or one
     * whose source is not something a cell can store (`javascript:`, `file:`,
     * SVG, an unknown `data:` payload) — is removed whole, and a `<math` with
     * no closing tag is prose.
     *
     * A chemical-structure marker is not carried in a readable field: our own
     * export writes the handles text plus the diagram media, and the full
     * element travels in the payload. A foreign cell that still carries a
     * marker therefore degrades to its readable handles body — never to raw
     * marker markup or its base64 `data-png`.
     */
    fun toCellRichText(html: String?): String {
        if (html == null) return ""
        var text = degradeChemSpans(html)
        text = BLOCK_OPEN.replace(text, "")
        text = BLOCK_BREAK.replace(text, "\n")
        text = BREAK.replace(text, "\n")
        val sb = StringBuilder(text.length)
        var pos = 0
        for (span in cellMarkupSpans(text)) {
            if (span.start < pos) continue
            if (span.start > pos) sb.append(renderInlineHtml(text.substring(pos, span.start)))
            when (span) {
                is CellMarkupSpan.Math -> sb.append(span.raw)
                is CellMarkupSpan.Picture ->
                    span.src?.takeIf(::storableCellImageSrc)?.let { src ->
                        sb.append("<img src=\"").append(escapeHtmlText(src)).append("\">")
                    }
            }
            pos = span.end
        }
        if (pos < text.length) sb.append(renderInlineHtml(text.substring(pos)))
        return finishCellText(sb.toString())
    }

    /** One raw math block or one `<img` region of a cell, in document order. */
    private sealed interface CellMarkupSpan {
        val start: Int
        val end: Int

        data class Math(val raw: String, override val start: Int, override val end: Int) : CellMarkupSpan

        /** [src] is null for a torn tag or one whose source cannot be read. */
        data class Picture(val src: String?, override val start: Int, override val end: Int) : CellMarkupSpan
    }

    /**
     * Finds the cell's math blocks and picture regions together. The picture
     * regions come from the shared quote-aware scanner, so a `>` inside a
     * quoted attribute cannot truncate a tag and leave its remainder as
     * prose; overlapping tokens are resolved by the earlier start, so a
     * `<math>` inside a picture's attribute stays part of the picture and an
     * `<img>` inside MathML stays part of the raw formula.
     */
    private fun cellMarkupSpans(text: String): List<CellMarkupSpan> {
        val spans = ArrayList<CellMarkupSpan>()
        for (math in CELL_MATH_PATTERN.findAll(text)) {
            spans += CellMarkupSpan.Math(math.value, math.range.first, math.range.last + 1)
        }
        forEachCellImageSpan(text) { tag, start, end ->
            spans += CellMarkupSpan.Picture(tag?.let(::imgTagSrc), start, end)
        }
        spans.sortBy { it.start }
        return spans
    }

    /**
     * Whether a readable picture source is something the stored cell grammar
     * can carry: a portable inline raster (the same validator every rendering
     * surface uses), a package-local/relative reference, or an http(s) URL.
     * A hostile scheme (`javascript:`, `file:`), an SVG data URI or an unknown
     * `data:` payload is dropped whole rather than stored.
     */
    private fun storableCellImageSrc(src: String): Boolean {
        if (src.startsWith("data:", ignoreCase = true)) return portableCellImageSrc(src)
        val colon = src.indexOf(':')
        if (colon < 0) return true
        return when (src.substring(0, colon).lowercase()) {
            "http", "https" -> true
            else -> false
        }
    }

    /**
     * A chem marker's body is its readable handles; the span itself (with its
     * `data-png` payload and any hostile attribute) is not something a foreign
     * readable field can be trusted to carry, so the body is all that
     * survives.
     */
    private fun degradeChemSpans(html: String): String {
        if (!html.contains(CHEM_CELL_CLASS, ignoreCase = true)) return html
        return CHEM_CELL_SPAN.replace(html) { it.groupValues[2] }
    }

    private fun finishCellText(text: String): String {
        val collapsed = WHITESPACE.replace(text, " ")
        val lines = collapsed.lines().joinToString("\n") { it.trim() }
        return BLANK_LINES.replace(lines, "\n\n").trim()
    }

    /**
     * Decodes HTML entities, including numeric ones. `&#10003;` and `&#10007;`
     * are how check and cross marks are usually written in Anki fields, and
     * decks from other exporters rely on that.
     *
     * One pass over the source, as the app's other two decoders do
     * (`data/docx/MathMlToOmml.kt`, `data/export/PdfMath.kt`), so a reference
     * that decodes to text which looks like another reference is not decoded
     * again: `&#38;amp;` is the text `&amp;`, not a second decode to `&`.
     */
    fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#") -> {
                val code = if (body[1] == 'x' || body[1] == 'X') {
                    body.substring(2).toIntOrNull(16)
                } else {
                    body.substring(1).toIntOrNull()
                }
                // A code point we cannot represent is left as written rather
                // than replaced by a replacement character.
                code?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: match.value
            }
            else -> NAMED_ENTITIES[body] ?: match.value
        }
    }

    private const val CORRECT_MARK = "✓"
    private const val WRONG_MARK = "✗"
    private const val MULTI_PROMPT = "Select all that apply."
    private const val EXPLANATION_PREFIX = "Explanation:"

    /** `A. text`, or multi-letter (`AA. text`) past the first 26 options. */
    private val OPTION_LINE = Regex("^\\(?\\d*[A-Z]+\\)?\\. ")

    /**
     * The front of one of our cards, split into the question and the options.
     * Both halves keep their HTML, so an image can be told apart by which half
     * it is in rather than by being the first one on the field.
     *
     * The exporter writes the question, a blank line, an optional multi-select
     * prompt, then the options as `A. …`. Anything shaped like an option line
     * that does not follow that boundary is part of the question, so a question
     * that happens to start with a letter and a full stop is left alone.
     */
    fun splitFront(front: String): Pair<String, String> {
        // Line breaks arrive as <br> from a field written by the exporter.
        val lines = BREAK.replace(front, "\n").lines()
        val cut = lines.withIndex().firstOrNull { (i, line) ->
            isOptionLine(line) && i > 0 && (lines[i - 1].isBlank() || isMultiPrompt(lines[i - 1]))
        }?.index ?: -1
        if (cut < 0) return front.trim() to ""
        val question = lines.take(cut)
            .filterNot { isMultiPrompt(it) }
            .joinToString("\n")
            .trim()
        return question to lines.drop(cut).joinToString("\n").trim()
    }

    /**
     * The question text from the front of one of our cards; see [splitFront]
     * for how the options are told apart.
     */
    fun questionTextFromFront(front: String): String = toPlainText(splitFront(front).first)

    /**
     * An option line as the exporter writes it, which is `<b>A.</b> text`. The
     * match is made on the line with its tags removed, so the bold marker on the
     * letter cannot hide it.
     */
    private fun isOptionLine(line: String): Boolean =
        OPTION_LINE.containsMatchIn(TAG.replace(line, "").trim())

    private fun isMultiPrompt(line: String): Boolean = TAG.replace(line, "").trim() == MULTI_PROMPT

    /** One option line recovered from a readable back field. */
    data class BackOption(val text: String, val correct: Boolean)

    /** The result of reading a back field written by [AnkiPackageWriter]. */
    data class BackField(
        val options: List<BackOption>,
        val explanation: String,
        val hasMarkers: Boolean
    )

    /**
     * Recovers options, correctness and the explanation from a readable back
     * field. Fields that carry no markers (a foreign deck) report
     * [hasMarkers] = false so the caller can decide how to represent them.
     */
    fun parseBackField(field: String?): BackField {
        if (field == null) return BackField(emptyList(), "", false)
        // A back field is HTML in Anki, so line breaks arrive as <br> rather
        // than newlines. Flattening it here means the caller can pass either.
        val text = decodeEntities(TAG.replace(BREAK.replace(field, "\n"), ""))
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val options = mutableListOf<BackOption>()
        var explanation = ""
        var seenExplanation = false
        var sawMarker = false

        lines.forEach { line ->
            when {
                line.startsWith(CORRECT_MARK) -> {
                    sawMarker = true
                    options += BackOption(line.removePrefix(CORRECT_MARK).trim(), true)
                }
                line.startsWith(WRONG_MARK) -> {
                    sawMarker = true
                    options += BackOption(line.removePrefix(WRONG_MARK).trim(), false)
                }
                line == MULTI_PROMPT -> Unit
                seenExplanation -> {
                    explanation = if (explanation.isEmpty()) line else "$explanation\n$line"
                }
                line.startsWith(EXPLANATION_PREFIX) -> {
                    seenExplanation = true
                    explanation = line.removePrefix(EXPLANATION_PREFIX).trim()
                }
                // A non-marker line before any option is a stray heading.
                options.isEmpty() && !sawMarker -> Unit
                else -> {
                    // Continuation of the previous option (e.g. a wrapped line).
                    val last = options.lastOrNull()
                    if (last == null) Unit else {
                        options[options.lastIndex] = last.copy(text = last.text + "\n" + line)
                    }
                }
            }
        }
        return BackField(options, explanation.trim(), sawMarker)
    }
}