package com.mcqapp.util

import com.mcqapp.data.escapeHtmlText
import com.mcqapp.data.export.isImageSrcDescriptor

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

/**
 * Stored binary bytes allowed for one cell image. A cell travels inside the
 * question row, its `CardContent` fingerprint and every backup part, so an
 * unbounded picture costs far more than the same picture at the top level.
 */
internal const val MAX_CELL_IMAGE_BYTES = 512 * 1024

/** Long edge a cell image is scaled to at import: a cell is at most half a phone's width. */
internal const val CELL_IMAGE_MAX_DIMENSION = 800

/**
 * The one diagnostic an import writes when a picture could not be brought under
 * [MAX_CELL_IMAGE_BYTES]: a stable message, so [com.mcqapp.util.Logger.warnOnce]
 * reports it once per session instead of once per cell.
 */
internal const val CELL_IMAGE_TOO_LARGE_MESSAGE =
    "A table cell image stayed above 512 KB after scaling and was replaced with [image]"

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
 * - a value-less attribute name (`hidden`) belongs to a torn tag only when a
 *   `name=value` token follows it, so `alt="x" hidden src=data:…` loses the
 *   payload while `src="x" after` stops before the prose `after`;
 * - when no value can be delimited at all (a quote is still open), the span runs
 *   to the end of [html].
 *
 * A linear scan with no decode and no per-character allocation: a multi-
 * megabyte cell is safe here (this runs during composition).
 */
internal inline fun forEachCellImageSpan(
    html: String,
    onImage: (tag: String?, start: Int, end: Int) -> Unit
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
        // The last `<img` a truncated scan read *outside* a quoted value. Only
        // the last one can still be a complete tag: an earlier nested start was
        // torn by a later `<`. Resuming there, rather than at `i + 4`, is what
        // keeps a cell full of torn `<img` starts linear instead of re-reading
        // the tail for every one of them.
        var lastNestedImgStart = -1
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
                        if (isImgTagStart(html, j)) lastNestedImgStart = j
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
            // A complete tag: hand out its source text so the strict reader
            // (`soleImgSrc`) and the import normaliser can each read it their
            // own way without the scanner deciding what an attribute means.
            val end = tagEnd + 1
            onImage(html.substring(i, end), i, end)
            i = end
        } else if (tagEnd < 0 && quote == '\u0000' && lastValueEnd < 0) {
            // No `>` in the rest of the cell and no quoted value to delimit the
            // span: every remaining `<img` is a torn prefix whose unquoted
            // `name=value` run may hold a payload. Report them in one forward
            // pass, so a cell full of `<img ` starts is read once instead of
            // re-reading its tail from every one.
            var s = i
            while (true) {
                onImage(null, s, unquotedTagPrefixEnd(html, s + IMG_TAG_LENGTH))
                var search = s + 1
                var next = -1
                while (true) {
                    val candidate = html.indexOf(IMG_TAG, search, ignoreCase = true)
                    if (candidate < 0) break
                    if (isImgTagStart(html, candidate)) {
                        next = candidate
                        break
                    }
                    search = candidate + 1
                }
                if (next < 0) {
                    s = length
                    break
                }
                s = next
            }
            i = s
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
            // Resume at the last nested `<img` only when it lies beyond the
            // span already reported; otherwise that start is inside the span
            // and must not be reported twice. The tail before it is part of
            // the same torn tag, so it is reported as one span and removed
            // whole by the normaliser.
            val resumeAtLastNested = tagEnd >= 0 &&
                quote == '\u0000' &&
                lastNestedImgStart > end
            val spanEnd = if (resumeAtLastNested) lastNestedImgStart else end
            onImage(null, i, spanEnd)
            i = when {
                // An open quote cannot be told from a payload: the scan read to
                // the end, and nothing inside it can be a picture either.
                quote != '\u0000' -> length
                // A later `<img` can still be a complete tag ending at the
                // `>` this scan stopped at. Re-read from that one start only;
                // the next pass then consumes the tag and moves on.
                resumeAtLastNested -> lastNestedImgStart
                // No nested picture start beyond the span: leave the rest of
                // the text to the outer loop, so prose after a torn prefix
                // survives.
                else -> spanEnd.coerceAtLeast(i + 1)
            }
        }
    }
}

/**
 * Visit every `<img …>` picture tag in [html] reporting the `src` of a complete
 * tag, or null when the tag is truncated or is not a sole-`src` tag. Rendering
 * uses this strict reader: an extra attribute makes a tag unsafe to draw.
 */
internal inline fun forEachCellImage(
    html: String,
    onImage: (src: String?, start: Int, end: Int) -> Unit
) {
    forEachCellImageSpan(html) { tag, start, end ->
        onImage(tag?.let { soleImgSrc(it) }, start, end)
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
 * truncated the tag). A value-less name is consumed and the scan continues
 * only when a run of bare names ends in `name=value` (so
 * `alt="x" hidden src=data:…` is one tag), and the scan stops at the first
 * token that is not part of such a run, so prose after a torn tag —
 * `src=data:… after` — is never eaten.
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
        if (equals >= length || html[equals] != '=') {
            // A value-less attribute (`hidden`) belongs to the tag only when a
            // run of bare names ends in `name=value`; otherwise this is prose
            // and the run stops before it (`src=data:… after` keeps `after`).
            if (!bareNamesEndInAssignment(html, nameEnd)) break
            end = nameEnd
            pos = nameEnd
            continue
        }
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

/**
 * True when the tokens from [start] are a run of value-less attribute names
 * that ends in a `name=value` token. One bare name alone is prose
 * (`src="x" after`), and a run with no assignment is prose too
 * (`<img hidden and after`), so neither is consumed.
 */
private fun bareNamesEndInAssignment(html: String, start: Int): Boolean {
    val length = html.length
    var pos = start
    while (true) {
        var k = pos
        while (k < length && html[k].isWhitespace()) k++
        if (k < length && html[k] == '/') {
            k++
            while (k < length && html[k].isWhitespace()) k++
        }
        if (k >= length || !isAttributeNameStart(html[k])) return false
        var nameEnd = k + 1
        while (nameEnd < length && isAttributeNameChar(html[nameEnd])) nameEnd++
        var equals = nameEnd
        while (equals < length && html[equals].isWhitespace()) equals++
        if (equals < length && html[equals] == '=') return true
        pos = nameEnd
    }
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
 * The import normaliser: every complete picture tag is rebuilt into the stored
 * form, every torn `<img` fragment is removed, and only a portable inline
 * picture that fits [MAX_CELL_IMAGE_BYTES] survives.
 *
 * [scale] receives each portable `src` (a fake in tests, the real 800 px
 * downscaler at import); [log] receives [CELL_IMAGE_TOO_LARGE_MESSAGE] when the
 * result still exceeds the cap, and that one tag becomes `[image]`. The rest of
 * the cell is untouched.
 *
 * A readable source that is not portable (`file:`, `http:`, SVG) is left
 * exactly as the author wrote it: only a `file:` reference raises the parser's
 * local-image warning, while a remote URL and an SVG data URI are silent by
 * design. A complete tag with no readable source at all fails closed and
 * becomes `[image]`; keeping it verbatim could leave a payload (or a tag the
 * renderer cannot draw) in the cell as prose.
 *
 * Idempotent by construction: the canonical tag it emits is read back by the
 * same lenient `src` reader, so a second import of our own export changes
 * nothing (a re-import must not read as an edit).
 */
internal fun normalizeCellImages(
    cell: String,
    scale: (String?) -> String?,
    log: (String) -> Unit
): String = mapCellImageTags(cell) { tag ->
    if (tag == null) return@mapCellImageTags ""          // torn <img: never leave its payload as prose
    // A complete tag whose source cannot be read is not drawable: fail closed.
    val src = imgTagSrc(tag) ?: return@mapCellImageTags CELL_IMAGE_MARKER
    // `mcqapp:img|…` is the DOCX writer's hidden payload stand-in, not a
    // picture a stored cell may carry: the body cannot draw it, so a later
    // DOCX export would count a picture that is not there. It never reaches a
    // row from an import (the DOCX parser grafts the visible bytes back);
    // a descriptor in a JSON file is blanked here rather than stored.
    if (isImageSrcDescriptor(src)) return@mapCellImageTags CELL_IMAGE_MARKER
    val portable = compactCellImageSrc(src) ?: return@mapCellImageTags tag
    // The real downscaler returns a MIME-encoded (CRLF-wrapped) payload, and a
    // fake may return anything; either way the stored form is unwrapped and
    // must still be a portable picture.
    val scaled = compactCellImageSrc(scale(portable) ?: portable)
    if (scaled == null || cellImageDecodedBytes(scaled) > MAX_CELL_IMAGE_BYTES) {
        log(CELL_IMAGE_TOO_LARGE_MESSAGE)
        CELL_IMAGE_MARKER
    } else {
        cellImageTag(scaled)
    }
}

/** [mapCellImages] for callers that need the complete tag itself, not its `src`. */
private inline fun mapCellImageTags(html: String, replace: (tag: String?) -> String): String {
    var out: StringBuilder? = null
    var pos = 0
    forEachCellImageSpan(html) { tag, start, end ->
        val sb = out ?: StringBuilder(html.length).also { out = it }
        sb.append(html, pos, start)
        sb.append(replace(tag))
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
    val bodyStart = cellImageBodyStart(src) ?: return false
    return validBase64Body(src, bodyStart)
}

/**
 * The index just past `data:image/<type>;base64,` for one of the accepted
 * picture types, or null. Only the header is inspected; the payload is the
 * caller's business.
 */
private fun cellImageBodyStart(src: String): Int? {
    if (!src.regionMatches(0, CELL_IMAGE_PREFIX, 0, CELL_IMAGE_PREFIX.length, ignoreCase = true)) {
        return null
    }
    for (type in CELL_IMAGE_MIME_TYPES) {
        val end = CELL_IMAGE_PREFIX.length + type.length
        if (end < src.length && src[end] == ';' &&
            src.regionMatches(CELL_IMAGE_PREFIX.length, type, 0, type.length, ignoreCase = true)
        ) {
            if (!src.regionMatches(end, CELL_IMAGE_BASE64, 0, CELL_IMAGE_BASE64.length, ignoreCase = true)) {
                return null
            }
            return end + CELL_IMAGE_BASE64.length
        }
    }
    return null
}

/**
 * [src] in the one stored form: whitespace inside the payload removed (a MIME
 * encoder writes CRLF every 76 characters; wrapping made the same picture hash
 * differently from its unwrapped twin). Anything that is not a `data:image`
 * URI for an accepted type, or that is not base64 after the whitespace is
 * removed, returns null and the tag is left alone.
 */
private fun compactCellImageSrc(src: String): String? {
    val bodyStart = cellImageBodyStart(src) ?: return null
    if (validBase64Body(src, bodyStart)) return src
    val compact = StringBuilder(src.length)
    for (i in bodyStart until src.length) {
        val c = src[i]
        if (!c.isWhitespace()) compact.append(c)
    }
    if (compact.length == src.length - bodyStart) return null   // unwrapped: genuinely not base64
    if (!validBase64Body(compact, 0)) return null
    return src.substring(0, bodyStart) + compact
}

/**
 * Decoded payload size, by base64 shape only: no decode, no allocation. The
 * payload has already passed [validBase64Body], so the number of non-padding
 * characters alone decides the byte count.
 */
private fun cellImageDecodedBytes(src: String): Long {
    val bodyStart = cellImageBodyStart(src) ?: return Long.MAX_VALUE
    var end = bodyStart
    while (end < src.length && src[end] != '=') end++
    return (end - bodyStart).toLong() * 3 / 4
}

/**
 * True when the body from [start] is non-empty, uses only the base64
 * alphabet and carries legal padding: `=` may only appear at the end, at most
 * two of them, and only for the remainder a standard encoder produces.
 * Unpadded input is accepted too (a remainder of 1 is still impossible).
 */
private fun validBase64Body(src: CharSequence, start: Int): Boolean {
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
 * The rebuilt image tag: only the source, the canonical sizing style and the
 * canonical [CELL_IMAGE_ALT] are kept. Any attribute the stored tag carried (an
 * `onerror`, a `style`, a width) is dropped — the tag is emitted whole, never
 * copied. The alt is not a parameter: a stored tag must always be drawable by
 * [soleImgSrc], whose only accepted alt is the canonical one.
 */
internal fun cellImageTag(src: String): String =
    "<img src=\"" + escapeHtmlText(src) + "\" style=\"" + CELL_IMAGE_STYLE +
        "\" alt=\"" + escapeHtmlText(CELL_IMAGE_ALT) + "\"/>"

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
 * The `src` of a tag that carries only attributes this app itself writes: one
 * `src`, optionally the canonical [CELL_IMAGE_STYLE] and [CELL_IMAGE_ALT]. A
 * second `src`, a different value for either trusted attribute, an unknown
 * attribute, an unquoted value, a missing `src` or a truncated tag returns
 * null: such a tag is not portable by definition (see
 * [portableCellImageSrc]).
 *
 * The two trusted attributes are exactly what [cellImageTag] emits, so the tag
 * the import normaliser stores reads back as a drawable picture on every
 * surface. Their values are never copied into the rendered tag — the renderer
 * rebuilds it — so accepting them cannot carry styling or markup from a file.
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
    var style: String? = null
    var alt: String? = null
    while (pos < body.length) {
        var k = pos
        while (k < body.length && body[k].isWhitespace()) k++
        if (k >= body.length) break
        val match = IMG_ATTRIBUTE.find(body, pos) ?: return null
        if (match.range.first != pos) return null   // text between attributes
        val name = match.groupValues[1]
        val value = match.groups[2]?.value ?: match.groups[3]?.value ?: return null
        when {
            name.equals("src", ignoreCase = true) -> {
                if (src != null) return null        // more than one source
                src = value
            }
            name.equals("style", ignoreCase = true) -> {
                if (style != null) return null
                style = value
            }
            name.equals("alt", ignoreCase = true) -> {
                if (alt != null) return null
                alt = value
            }
            else -> return null                     // unknown or hostile attribute
        }
        pos = match.range.last + 1
    }
    if (style != null && style != CELL_IMAGE_STYLE) return null
    if (alt != null && alt != CELL_IMAGE_ALT) return null
    return src
}

/** `name="value"` or `name='value'`, with any leading whitespace. */
private val IMG_ATTRIBUTE =
    Regex("\\s+([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

/**
 * The `src` of a complete tag, whatever else the tag carries: attribute order
 * does not matter, extra attributes do not defeat it, a value-less attribute
 * (`hidden`) is accepted, an optional `/` may precede a name, and a quoted or
 * unquoted value is read. This is the import normaliser's reader — it must
 * recognise the canonical tag it wrote (which carries `style` and `alt`) as
 * well as a foreign tag, so the second import of the same bytes normalises to
 * the same string. Rendering stays strict on [soleImgSrc]: this reader never
 * decides what may be drawn.
 *
 * Null means "no readable source": a tag truncated before its `>`, an
 * attribute without a value in an unexpected place, or a `src` with no value.
 * The importer fails closed on null rather than keeping such a tag verbatim.
 */
internal fun imgTagSrc(tag: String): String? {
    if (tag.length < 4 || !tag.regionMatches(0, "<img", 0, 4, ignoreCase = true)) return null
    val body = when {
        tag.endsWith("/>") -> tag.substring(4, tag.length - 2)
        tag.endsWith(">") -> tag.substring(4, tag.length - 1)
        else -> return null
    }
    var pos = 0
    while (pos < body.length) {
        while (pos < body.length && body[pos].isWhitespace()) pos++
        if (pos < body.length && body[pos] == '/') pos++             // `<img/src=…`
        while (pos < body.length && body[pos].isWhitespace()) pos++
        if (pos >= body.length) break
        if (!isAttributeNameStart(body[pos])) return null
        val nameStart = pos
        pos++
        while (pos < body.length && isAttributeNameChar(body[pos])) pos++
        val name = body.substring(nameStart, pos)
        var equals = pos
        while (equals < body.length && body[equals].isWhitespace()) equals++
        if (equals >= body.length || body[equals] != '=') {
            // A value-less attribute (`hidden`) is legal in the grammar; it
            // simply carries no source. Keep scanning for a later `src`.
            pos = equals
            continue
        }
        var valueStart = equals + 1
        while (valueStart < body.length && body[valueStart].isWhitespace()) valueStart++
        val quote = body.getOrNull(valueStart)
        val value: String
        if (quote == '"' || quote == '\'') {
            val close = body.indexOf(quote, valueStart + 1)
            if (close < 0) return null                         // an unclosed quote cannot be read
            value = body.substring(valueStart + 1, close)
            pos = close + 1
        } else {
            var valueEnd = valueStart
            while (valueEnd < body.length && !body[valueEnd].isWhitespace()) valueEnd++
            value = body.substring(valueStart, valueEnd)
            pos = valueEnd
        }
        if (name.equals("src", ignoreCase = true) && value.isNotEmpty()) return value
    }
    return null
}
