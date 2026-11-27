package com.mcqapp.util

import com.mcqapp.data.escapeHtmlText

/** What a rendered cell picture is announced as, in HTML and to TalkBack. */
internal const val CELL_IMAGE_ALT = "Image in table"

/**
 * The canonical style every rendered cell image carries. Kept in one place so
 * the in-app WebView, the HTML export and any future writer agree. The height
 * cap keeps a tall picture from producing a multi-thousand-pixel row; the
 * aspect ratio is preserved by the width/height pair.
 */
internal const val CELL_IMAGE_STYLE =
    "max-width:100%;height:auto;max-height:240px;vertical-align:middle"

private const val IMG_TAG = "<img"
private const val IMG_TAG_LENGTH = 4

private const val CELL_IMAGE_PREFIX = "data:image/"
private const val CELL_IMAGE_BASE64 = ";base64,"
private val CELL_IMAGE_MIME_TYPES = arrayOf("png", "jpeg", "jpg", "gif", "webp")

/**
 * Visit every `<img …>` picture tag in [html], in order, reporting the span it
 * occupies and the `src` of a complete tag (null when the tag is truncated).
 *
 * One quote-aware scanner, shared by every cell surface, because a regex broke
 * in both directions:
 * - `<img` with no `>` used to match to the end of the cell, so the text after
 *   it (`before <img src="…"/ after`) was swallowed on every surface;
 * - a `>` inside a quoted attribute (`alt="x>y"`) ended the match early and
 *   the rest of the tag — base64 included — was escaped as visible prose.
 *
 * The rules:
 * - the tag starts at `<img` followed by whitespace, `/` or `>` (case-insensitive),
 *   or at the very end of [html] — a bare `<img` is a truncated tag;
 * - a `"`/`'` may contain `>`; the first `>` outside quotes ends a complete tag;
 * - a `<` before that `>` means the tag was never closed: it is truncated, and
 *   its span covers only the tag-like prefix — through the last complete quoted
 *   attribute value plus an optional trailing `/`, then on through any
 *   unquoted `name=value` tokens that follow it (the later of the two ends), or,
 *   when no value was ever quoted, through the last `name=value` token — so a
 *   quoted attribute followed by an unquoted `src` still leaks nothing, and the
 *   prose after it survives;
 * - when no value can be delimited at all (a quote is still open), the span runs
 *   to the end of [html].
 *
 * Known limitation: on a truncated `<img …` with no `>`, a value-less or
 * malformed token after a quoted attribute (`alt="x" hidden src=data:…`) stops
 * the unquoted run at that token, so a later unquoted `src` can be left in the
 * cell; real imports always write a quoted `src` and a complete tag takes the
 * strict [soleImgSrc] path, so only torn hand-authored markup is affected.
 *
 * A linear scan with no decode and no per-character allocation: a multi-
 * megabyte cell is safe here (this runs during composition).
 */
internal inline fun forEachCellImage(
    html: String,
    onImage: (src: String?, start: Int, end: Int) -> Unit
) {
    val length = html.length
    var i = 0
    while (i < length) {
        if (!isImgTagStart(html, i)) {
            i++
            continue
        }
        var j = i + IMG_TAG_LENGTH
        var quote = '\u0000'
        var quoteStart = -1
        var lastValueEnd = -1
        var truncated = false
        var tagEnd = -1
        while (j < length) {
            val c = html[j]
            if (quote != '\u0000') {
                if (c == quote) {
                    quote = '\u0000'
                    if (followsEquals(html, quoteStart)) lastValueEnd = j + 1
                }
                j++
            } else {
                when (c) {
                    '>' -> {
                        tagEnd = j
                        break
                    }
                    '<' -> {
                        truncated = true
                        j++
                    }
                    '"', '\'' -> {
                        quote = c
                        quoteStart = j
                        j++
                    }
                    else -> j++
                }
            }
        }
        if (tagEnd >= 0 && !truncated) {
            // A complete tag: `soleImgSrc` is the one strict reader of its
            // attributes, so the scanner and the renderer cannot disagree.
            val end = tagEnd + 1
            onImage(soleImgSrc(html.substring(i, end)), i, end)
            i = end
        } else {
            val end = when {
                // The payload cannot be delimited: never leave part of it behind.
                quote != '\u0000' -> length
                // A complete quoted value delimits the tag-like prefix: the tag
                // runs on from there through any unquoted `name=value` tokens
                // (`alt="x" src=data:…`) and ends at the later of the two, plus
                // an optional trailing `/`. Stopping at the quote alone used to
                // leave such a `src` in the cell as visible prose.
                lastValueEnd >= 0 -> {
                    val quotedEnd = if (lastValueEnd < length && html[lastValueEnd] == '/') {
                        lastValueEnd + 1
                    } else {
                        lastValueEnd
                    }
                    maxOf(quotedEnd, unquotedTagPrefixEnd(html, quotedEnd))
                }
                // Nothing was quoted, so only the `name=value` tokens after
                // `<img` can belong to the tag. The prose that follows them —
                // and never a payload — stays in the cell.
                else -> unquotedTagPrefixEnd(html, i + IMG_TAG_LENGTH)
            }
            onImage(null, i, end)
            i = end.coerceAtLeast(i + 1)
        }
    }
}

/** True at the `<` of an `<img` tag that a picture tag can actually start. */
private fun isImgTagStart(html: String, index: Int): Boolean {
    if (html[index] != '<') return false
    if (index + IMG_TAG_LENGTH > html.length) return false
    if (!html.regionMatches(index, IMG_TAG, 0, IMG_TAG_LENGTH, ignoreCase = true)) return false
    // A bare `<img` at the end of the cell is a tag cut short, not prose.
    if (index + IMG_TAG_LENGTH == html.length) return true
    val next = html[index + IMG_TAG_LENGTH]
    return next.isWhitespace() || next == '/' || next == '>'
}

/**
 * The end of the tag-like prefix that starts right after `<img` at [start]:
 * each `name=value` token whose value is unquoted, separated by whitespace.
 * One iteration mirrors the attribute grammar the complete-tag reader uses:
 * skip whitespace; skip an optional `/` and the whitespace after it (so
 * `<img/ src=…` and `<img height=100 /src=…` are the same tag); read a name
 * `[A-Za-z_:][-A-Za-z0-9_:.]*`; skip whitespace around the required `=`; then
 * an unquoted value runs to the next whitespace (or to a `<`, which already
 * truncated the tag). The scan stops at the first token that is not
 * `name=`, so prose after a torn tag — `src=data:… after` — is never eaten.
 */
private fun unquotedTagPrefixEnd(html: String, start: Int): Int {
    val length = html.length
    var end = start
    var pos = start
    while (pos < length) {
        var k = pos
        while (k < length && html[k].isWhitespace()) k++
        if (k < length && html[k] == '/') {
            k++
            while (k < length && html[k].isWhitespace()) k++
        }
        if (k >= length || !isAttributeNameStart(html[k])) break
        var nameEnd = k + 1
        while (nameEnd < length && isAttributeNameChar(html[nameEnd])) nameEnd++
        var equals = nameEnd
        while (equals < length && html[equals].isWhitespace()) equals++
        if (equals >= length || html[equals] != '=') break
        var valueStart = equals + 1
        while (valueStart < length && html[valueStart].isWhitespace()) valueStart++
        var valueEnd = valueStart
        while (valueEnd < length && !html[valueEnd].isWhitespace() && html[valueEnd] != '<') {
            valueEnd++
        }
        // `name=` with no value is malformed: keep the token, end the run.
        if (valueEnd == valueStart) {
            end = equals + 1
            break
        }
        end = valueEnd
        pos = valueEnd
    }
    return end
}

/** The first character of an HTML attribute name: `[A-Za-z_:]`. */
private fun isAttributeNameStart(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c == '_' || c == ':'

/** The rest of an HTML attribute name: `[-A-Za-z0-9_:.]`. */
private fun isAttributeNameChar(c: Char): Boolean =
    isAttributeNameStart(c) || c in '0'..'9' || c == '-' || c == '.'

/** True when the quote opening at [quoteIndex] is an attribute value (`name = "…"`). */
private fun followsEquals(html: String, quoteIndex: Int): Boolean {
    var k = quoteIndex - 1
    while (k >= 0 && html[k].isWhitespace()) k--
    return k >= 0 && html[k] == '='
}

/**
 * Rebuild [html] with [replace] called once per picture tag; every other
 * character is copied verbatim. The callback returns the canonical tag, the
 * `[image]` marker, or `<mtext>[image]</mtext>` inside a formula.
 */
internal fun mapCellImages(html: String, replace: (src: String?) -> String): String {
    var out: StringBuilder? = null
    var pos = 0
    forEachCellImage(html) { src, start, end ->
        val sb = out ?: StringBuilder(html.length).also { out = it }
        sb.append(html, pos, start)
        sb.append(replace(src))
        pos = end
    }
    val sb = out ?: return html
    sb.append(html, pos, html.length)
    return sb.toString()
}

/**
 * A portable cell image: an inline base64 data URI for one of the picture
 * types Android can decode. Everything else — a remote URL, `file:`,
 * `javascript:`, SVG, an empty source, a MIME-encoded (wrapped) payload or a
 * non-base64 `data:` payload — is not something a cell may draw.
 *
 * The payload is checked by SHAPE only: a linear scan over the alphabet and
 * the padding rules, with no decode and no buffer. A multi-megabyte cell must
 * not allocate a second copy of itself here (this runs during composition),
 * and a malformed payload must never throw.
 */
internal fun portableCellImageSrc(src: String): Boolean {
    if (!src.regionMatches(0, CELL_IMAGE_PREFIX, 0, CELL_IMAGE_PREFIX.length, ignoreCase = true)) {
        return false
    }
    var mimeEnd = -1
    for (type in CELL_IMAGE_MIME_TYPES) {
        val end = CELL_IMAGE_PREFIX.length + type.length
        if (end < src.length && src[end] == ';' &&
            src.regionMatches(CELL_IMAGE_PREFIX.length, type, 0, type.length, ignoreCase = true)
        ) {
            mimeEnd = end
            break
        }
    }
    if (mimeEnd < 0) return false
    if (!src.regionMatches(mimeEnd, CELL_IMAGE_BASE64, 0, CELL_IMAGE_BASE64.length, ignoreCase = true)) {
        return false
    }
    return validBase64Body(src, mimeEnd + CELL_IMAGE_BASE64.length)
}

/**
 * True when the body from [start] is non-empty, uses only the base64
 * alphabet and carries legal padding: `=` may only appear at the end, at most
 * two of them, and only for the remainder a standard encoder produces.
 * Unpadded input is accepted too (a remainder of 1 is still impossible).
 */
private fun validBase64Body(src: String, start: Int): Boolean {
    var i = start
    var core = 0
    while (i < src.length) {
        val c = src[i]
        if (c == '=') break
        if (!isBase64Char(c)) return false
        core++
        i++
    }
    if (core == 0) return false
    var padding = 0
    while (i < src.length && src[i] == '=') {
        padding++
        if (padding > 2) return false
        i++
    }
    if (i != src.length) return false
    return when (padding) {
        0 -> core % 4 != 1
        1 -> core % 4 == 3
        else -> core % 4 == 2
    }
}

private fun isBase64Char(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/'

/**
 * The rebuilt image tag: only the source, the canonical sizing style and an
 * escaped [alt] are kept. Any attribute the stored tag carried (an `onerror`,
 * a `style`, a width) is dropped — the tag is emitted whole, never copied.
 */
internal fun cellImageTag(src: String, alt: String = CELL_IMAGE_ALT): String =
    "<img src=\"" + escapeHtmlText(src) + "\" style=\"" + CELL_IMAGE_STYLE +
        "\" alt=\"" + escapeHtmlText(alt) + "\"/>"

/**
 * True when any cell carries a portable picture; such a table needs the WebView.
 * The same scanner decides what a tag is here and in the renderer, so the path
 * choice can never disagree with whether the page can draw it.
 */
internal fun cellHasPortableImage(cell: String): Boolean {
    var found = false
    forEachCellImage(cell) { src, _, _ ->
        if (!found && src != null && portableCellImageSrc(src)) found = true
    }
    return found
}

/**
 * The `src` of a tag that carries exactly one attribute, `src`, and nothing
 * else. A second attribute, an unquoted value, a missing `src` or a truncated
 * tag returns null: such a tag is not portable by definition (see
 * [portableCellImageSrc]).
 */
internal fun soleImgSrc(tag: String): String? {
    if (tag.length < 4 || !tag.regionMatches(0, "<img", 0, 4, ignoreCase = true)) return null
    val body = when {
        tag.endsWith("/>") -> tag.substring(4, tag.length - 2)
        tag.endsWith(">") -> tag.substring(4, tag.length - 1)
        else -> return null
    }
    var pos = 0
    var src: String? = null
    while (pos < body.length) {
        if (body.substring(pos).isBlank()) break
        val match = IMG_ATTRIBUTE.find(body, pos) ?: return null
        if (match.range.first != pos) return null   // text between attributes
        if (src != null) return null                // more than one attribute
        if (!match.groupValues[1].equals("src", ignoreCase = true)) return null
        src = match.groups[2]?.value ?: match.groups[3]?.value ?: return null
        pos = match.range.last + 1
    }
    return src
}

/** `name="value"` or `name='value'`, with any leading whitespace. */
private val IMG_ATTRIBUTE =
    Regex("\\s+([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
