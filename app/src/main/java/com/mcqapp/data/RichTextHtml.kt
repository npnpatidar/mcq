package com.mcqapp.data

/**
 * Renders inline HTML tags (`<sub>`, `<sup>`, `<strong>`, `<em>`, …)
 * inside exported text as-is, escaping anything else — including unknown
 * tag shapes like `<one>`, which are literal text in legacy questions
 * and must stay visible rather than vanish. Shared by the HTML and Anki
 * exporters so formatted text looks the same everywhere.
 */
internal fun renderInlineHtml(text: String): String {
    if (!text.contains('<')) return escapeHtmlText(text)
    val sb = StringBuilder()
    var pos = 0
    val tagPattern = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*>")
    for (match in tagPattern.findAll(text)) {
        sb.append(escapeHtmlText(text.substring(pos, match.range.first)))
        val tag = match.groupValues[2].lowercase()
        if (tag in setOf(
                "b", "strong", "i", "em", "u", "del", "s", "strike",
                "sub", "sup", "mark", "br", "p", "div", "li", "tr"
            )
        ) {
            sb.append(match.value)
        } else {
            // Unknown tag shape: literal text in legacy questions,
            // escape it so it stays visible.
            sb.append(escapeHtmlText(match.value))
        }
        pos = match.range.last + 1
    }
    sb.append(escapeHtmlText(text.substring(pos)))
    return sb.toString()
}

internal fun escapeHtmlText(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

// Matches a well-formed tag only; a stray "<" (unescaped markup in an
// otherwise-text chunk) fails the match and is escaped as text instead of
// silently swallowing the rest of the input.
private val mathTokenPattern =
    Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)(\\s[^<>]*)?/?>")
private val mathTokenElements = setOf(
    "mi", "mn", "mo", "mtext", "ms", "mspace", "text",
    "annotation", "annotation-xml"
)

/**
 * Splits a bare math text run into strict token elements: ASCII
 * letter runs become `<mi>`, digit runs `<mn>`, anything else goes
 * operator-by-operator into `<mo>`. Bare text directly inside
 * `<mrow>`/`<math>` is invalid MathML — MathJax throws `Unexpected
 * text node` (shown as "Math input error") instead of rendering it.
 */
internal fun tokenizeMathRun(text: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isAsciiLetter() -> {
                var j = i + 1
                while (j < text.length && text[j].isAsciiLetter()) j++
                sb.append("<mi>").append(escapeMathText(text.substring(i, j))).append("</mi>")
                i = j
            }
            c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                var j = i + 1
                while (j < text.length && (text[j].isDigit() || text[j] == '.')) j++
                sb.append("<mn>").append(escapeMathText(text.substring(i, j))).append("</mn>")
                i = j
            }
            c.isWhitespace() -> i++
            else -> {
                sb.append("<mo>").append(escapeMathChar(c)).append("</mo>")
                i++
            }
        }
    }
    return sb.toString()
}

private fun escapeMathText(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun Char.isAsciiLetter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in 'α'..'ω' || this in 'Α'..'Ω'

private fun escapeMathChar(c: Char): String = when (c) {
    '&' -> "&amp;"
    '<' -> "&lt;"
    '>' -> "&gt;"
    else -> c.toString()
}

/**
 * Wraps bare text inside MathML container elements into token
 * elements, leaving already-tokenized content untouched. Idempotent:
 * strict MathML passes through unchanged.
 */
internal fun normalizeMathText(mathml: String): String {
    if (!mathml.contains('<')) {
        return if (mathml.isBlank()) mathml else tokenizeMathRun(mathml)
    }
    // Comments and PIs carry no rendering semantics; dropping them keeps
    // the scanner from ever tokenizing comment text into operators.
    var input = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(mathml, "")
    input = Regex("<\\?.*?\\?>", RegexOption.DOT_MATCHES_ALL).replace(input, "")
    val sb = StringBuilder()
    val stack = ArrayDeque<String>()
    var pos = 0
    while (pos < input.length) {
        val match = mathTokenPattern.find(input, pos) ?: break
        val chunk = input.substring(pos, match.range.first)
        if (chunk.isNotBlank() && stack.lastOrNull() !in mathTokenElements) {
            sb.append(tokenizeMathRun(chunk))
        } else {
            sb.append(chunk)
        }
        val closing = match.groupValues[1] == "/"
        val tag = match.groupValues[2].lowercase()
        sb.append(match.value)
        if (!closing && !match.value.endsWith("/>")) {
            stack.addLast(tag)
        } else if (closing) {
            val idx = stack.indexOfLast { it == tag }
            if (idx >= 0) while (stack.size > idx) stack.removeLast()
        }
        pos = match.range.last + 1
    }
    val tail = input.substring(pos)
    if (tail.isNotBlank() && stack.lastOrNull() !in mathTokenElements) {
        sb.append(tokenizeMathRun(tail))
    } else {
        sb.append(tail)
    }
    return sb.toString()
}
