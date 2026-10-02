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
