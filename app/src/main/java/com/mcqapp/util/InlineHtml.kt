package com.mcqapp.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/**
 * Renders the inline HTML that bank files put inside text runs
 * (`<b>`, `<sub>`, `<br>`, `&amp;`, …) as an [AnnotatedString].
 *
 * Storage keeps the raw string (Anki actually renders basic HTML, so the
 * export path wants it untouched); only the on-screen text is formatted.
 * Anything unrecognized — unknown tags, a bare `&` or `<` as in `5 < 6` —
 * is left visible rather than swallowed.
 */
object InlineHtml {

    private val tagPattern = Regex("<(/?)\\s*([a-zA-Z][a-zA-Z0-9]*)[^>]*>")
    private val entityPattern = Regex("&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

    private val namedEntities = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " "
    )

    private fun styleFor(tag: String): SpanStyle? = when (tag) {
        "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
        "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
        "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "del", "s", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)
        "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)
        "mark" -> SpanStyle(background = Color(0xFFFFEB3B), color = Color(0xFF000000))
        else -> null
    }

    private fun isBlockBreak(tag: String): Boolean = tag == "br" ||
        tag == "p" || tag == "div" || tag == "li" || tag == "tr"

    fun parse(text: String): AnnotatedString {
        if (!text.contains('<') && !text.contains('&')) return AnnotatedString(text)
        return buildAnnotatedString {
            val openTags = ArrayDeque<String>()
            var trailingBreak = false
            fun appendText(s: String) {
                if (s.isEmpty()) return
                append(s)
                trailingBreak = s.endsWith('\n')
            }
            fun appendDecoded(raw: String) {
                if (!raw.contains('&')) {
                    appendText(raw)
                    return
                }
                var pos = 0
                for (match in entityPattern.findAll(raw)) {
                    appendText(raw.substring(pos, match.range.first))
                    appendText(decodeEntity(match.groupValues[1]))
                    pos = match.range.last + 1
                }
                appendText(raw.substring(pos))
            }
            fun appendBreak() {
                if (length > 0 && !trailingBreak) appendText("\n")
            }
            var pos = 0
            for (match in tagPattern.findAll(text)) {
                appendDecoded(text.substring(pos, match.range.first))
                val closing = match.groupValues[1] == "/"
                val tag = match.groupValues[2].lowercase()
                if (isBlockBreak(tag)) {
                    appendBreak()
                } else if (!closing) {
                    val style = styleFor(tag)
                    if (style != null) {
                        pushStyle(style)
                        openTags.addLast(tag)
                    }
                    // Unknown tags are stripped.
                } else if (openTags.lastOrNull() == tag) {
                    openTags.removeLast()
                    pop()
                }
                // A stray closing tag pops nothing, so it cannot eat an
                // unrelated span.
                pos = match.range.last + 1
            }
            appendDecoded(text.substring(pos))
        }
    }

    private fun decodeEntity(body: String): String {
        if (body.startsWith("#")) {
            val code = if (body[1] == 'x' || body[1] == 'X') {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull()
            }
            if (code != null && code in 0..0x10FFFF) {
                return codePointString(code)
            }
            return "&$body;"
        }
        return namedEntities[body] ?: "&$body;"
    }

    private fun codePointString(codePoint: Int): String = if (codePoint <= 0xFFFF) {
        codePoint.toChar().toString()
    } else {
        // Surrogate pair for supplementary-plane code points (e.g. emoji).
        val v = codePoint - 0x10000
        charArrayOf(
            (0xD800 + (v shr 10)).toChar(),
            (0xDC00 + (v and 0x3FF)).toChar()
        ).concatToString()
    }
}
