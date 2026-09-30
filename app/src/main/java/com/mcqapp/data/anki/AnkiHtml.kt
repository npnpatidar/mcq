package com.mcqapp.data.anki

/**
 * Converts Anki notefield HTML into the plain text the app stores, and back.
 *
 * Question and option text is plain text in this app, so an import has to
 * flatten the HTML Anki stores; an export re-escapes it (see
 * [AnkiMediaPool.htmlField]). Round-tripping therefore strips then re-adds
 * markup rather than preserving it.
 */
object AnkiHtml {

    private val TAG = Regex("<[^>]*>")
    private val BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val BLOCK_BREAK = Regex("</(p|div|li|tr|h[1-6])\\s*>", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("[ \\t]+")
    private val BLANK_LINES = Regex("\n{3,}")
    private val NUMERIC_ENTITY = Regex("&#(x?[0-9a-fA-F]+);")

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

    /** `A. text`, or `(1A). text` past the first 26 options. */
    private val OPTION_LINE = Regex("^(\\(?\\d*[A-Z]\\)?|[A-Z])\\. ")

    /**
     * The question text from the front of one of our cards.
     *
     * The exporter writes the question, a blank line, an optional multi-select
     * prompt, then the options as `A. …`. Anything shaped like an option line
     * that does not follow that boundary is part of the question, so a question
     * that happens to start with a letter and a full stop is left alone.
     */
    fun questionTextFromFront(front: String): String {
        val lines = front.lines()
        val cut = lines.withIndex().firstOrNull { (i, line) ->
            OPTION_LINE.containsMatchIn(line.trim()) &&
                i > 0 && (lines[i - 1].isBlank() || lines[i - 1].trim() == MULTI_PROMPT)
        }?.index ?: -1
        if (cut < 0) return front.trim()
        return lines.take(cut)
            .filterNot { it.trim() == MULTI_PROMPT }
            .joinToString("\n")
            .trim()
    }

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
        val text = decodeEntities(field)
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