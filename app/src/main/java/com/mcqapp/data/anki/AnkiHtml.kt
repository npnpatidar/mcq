package com.mcqapp.data.anki

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
    private val RICH_TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*>")
    private val WHITESPACE = Regex("[ \\t]+")
    private val BLANK_LINES = Regex("\n{3,}")
    private val NUMERIC_ENTITY = Regex("&#(x?[0-9a-fA-F]+);")

    /** Inline formatting tags kept verbatim by [toRichText]. */
    private val RICH_KEEP = setOf(
        "b", "strong", "i", "em", "u", "del", "s", "strike",
        "sub", "sup", "mark"
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
     * Decodes HTML entities, including numeric ones. `&#10003;` and `&#10007;`
     * are how check and cross marks are usually written in Anki fields, and
     * decks from other exporters rely on that.
     */
    fun decodeEntities(text: String): String = NUMERIC_ENTITY.replace(
        text.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
    ) { match ->
        val digits = match.groupValues[1].dropLastWhile { it == ';' }
        val code = if (digits.startsWith("x", ignoreCase = true)) {
            digits.drop(1).toIntOrNull(16)
        } else {
            digits.toIntOrNull()
        }
        // A code point we cannot represent is left as written rather than
        // replaced by a replacement character.
        code?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: match.value
    }.replace("&amp;", "&")

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