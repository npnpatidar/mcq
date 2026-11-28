package com.mcqapp.util

import com.mcqapp.data.decodeHtmlEntities
import com.mcqapp.data.escapeHtmlText
import com.mcqapp.data.export.chemElementText
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.forEachCellMathBlock

/**
 * The one storage grammar for a chemical structure inside a table cell:
 *
 *     <span class="mcq-chem-cell" data-smiles="CCO" data-formula="C2H5OH"
 *           data-caption="ethanol" data-spoken="ethanol" data-png="iVBORw0…">
 *       ethanol C2H5OH CCO
 *     </span>
 *
 * The body is the same readable handles text a standalone structure prints
 * ([chemElementText]), so every surface that does not know the marker — the
 * native cell, search, Anki text, Word text, an old build — still shows
 * "ethanol C2H5OH CCO" and never a raw tag or a base64 payload. The optional
 * `data-png` is the bare base64 PNG payload (no `data:` prefix): exports use
 * it as a raster cache, and it is validated exactly like a standalone diagram.
 *
 * Reading is strict on purpose. Only the class token and the five `data-*`
 * names are accepted, every value must be quoted, and a duplicate or unknown
 * attribute makes the whole span prose. The marker is therefore never copied:
 * a caller that re-emits one rebuilds it from the parsed [ContentElement.ChemElement],
 * so an imported bank cannot smuggle `onclick`, `style` or a stray attribute
 * into a JS-enabled WebView. A malformed, unclosed or nested span stays text.
 *
 * Pure JVM: no Compose, no Android. The grammar is shared by every surface:
 * the mixed in-app page rebuilds a marker into a live drawing
 * ([ContentElements]), while imports, exports and the native/text surfaces
 * degrade it to its handles text.
 */
internal const val CELL_CHEM_CLASS = "mcq-chem-cell"

/** The required SMILES attribute; its presence is what makes a span a marker. */
internal const val CELL_CHEM_ATTR = "data-smiles"

/**
 * A marker's PNG payload is capped before any base64 decode: a 2 MB
 * `CursorWindow` row is the storage ceiling, and a hostile multi-megabyte
 * attribute must not allocate a decoded buffer just to be rejected.
 */
private const val MAX_CELL_PNG_PAYLOAD_CHARS = 4 * 1024 * 1024

/**
 * Longest one human field (SMILES, formula, caption, spoken) in a marker built
 * from an imported chem object. A cell travels inside the question row (the
 * 2 MB `CursorWindow` ceiling), its `CardContent` hash and every backup part,
 * so a hostile field must not be copied verbatim into storage. 2 KiB is far
 * above any real name, formula or spoken label.
 */
internal const val MAX_CELL_CHEM_FIELD_CHARS = 2 * 1024

/**
 * Longest emitted marker. The field cap alone is not enough: escaping can grow
 * `&` fivefold and `"` sixfold, so four individually legal fields plus the
 * duplicated handles body could still build a ~84 KiB cell. 16 KiB leaves room
 * for a generous label while keeping one table cell far below the row ceiling.
 */
internal const val MAX_CELL_CHEM_MARKER_CHARS = 16 * 1024

private const val PNG_DATA_URI_PREFIX = "data:image/png;base64,"

/**
 * The attribute region excludes `<` as well as `>`: with only `[^>]*`, a cell
 * full of `<span` fragments and no `>` made every occurrence scan to the end
 * of the cell — quadratic, ~30 s on a 300 KB hostile cell on the UI thread.
 * A valid marker escapes `<` in its attribute values ([escapeHtmlText]), so
 * nothing a writer emits is lost.
 */
private val CELL_CHEM_SPAN_PATTERN = Regex(
    "<span\\b([^<>]*)>([^<]*)</span\\s*>",
    setOf(RegexOption.IGNORE_CASE)
)

private val CELL_CHEM_ATTRIBUTES = setOf(
    "class", "data-smiles", "data-formula", "data-caption", "data-spoken", "data-png"
)

/** One piece of a cell: prose/other markup, or a parsed chemical structure. */
internal sealed interface CellSegment {
    /** Prose (or any inline markup that is not a chem marker), verbatim. */
    data class Text(val html: String) : CellSegment

    /** One validated chem marker, rebuilt from its own fields only. */
    data class Chem(val element: ContentElement.ChemElement) : CellSegment
}

/**
 * The marker HTML for [element]. Only the element's own fields are emitted:
 * class, the five `data-*` values and the escaped handles body. Empty optional
 * fields are omitted, and the PNG travels as a bare payload so the data URI is
 * never printed by a surface that strips tags.
 *
 * [includePng] is for surfaces that draw live (the in-app page and the editor
 * preview): they must not carry a captured raster that is only an export cache.
 * The default keeps it for storage round trips.
 */
internal fun chemCellHtml(
    element: ContentElement.ChemElement,
    includePng: Boolean = true
): String {
    val sb = StringBuilder(128)
    sb.append("<span class=\"").append(CELL_CHEM_CLASS).append('"')
    sb.append(' ').append(CELL_CHEM_ATTR).append("=\"")
        .append(escapeHtmlText(element.smiles)).append('"')
    attribute(sb, "data-formula", element.formula)
    attribute(sb, "data-caption", element.caption)
    attribute(sb, "data-spoken", element.spoken)
    if (includePng) {
        cellPngPayload(element.diagram)?.let { payload ->
            sb.append(" data-png=\"").append(escapeHtmlText(payload)).append('"')
        }
    }
    sb.append('>')
        .append(escapeHtmlText(chemElementText(element)))
        .append("</span>")
    return sb.toString()
}

private fun attribute(sb: StringBuilder, name: String, value: String) {
    if (value.isBlank()) return
    sb.append(' ').append(name).append("=\"")
        .append(escapeHtmlText(value)).append('"')
}

/** One valid top-level chem marker and the span it occupies in a cell. */
internal data class CellChemSpan(
    val start: Int,
    val end: Int,
    val element: ContentElement.ChemElement
)

/**
 * Every valid top-level chem marker in [cell] with its exact offsets, in
 * storage order. A marker inside a [protectedRanges] region is skipped: the
 * writer degrades it to handles in place and never draws it, so it must not
 * count as a picture slot. Pure JVM; the DOCX graft uses this to align picture
 * positions without substring search (a degraded marker's rebuilt text is not
 * a substring of the payload).
 */
internal fun cellChemSpans(cell: String, withPng: Boolean = true): List<CellChemSpan> {
    if (!cell.contains(CELL_CHEM_CLASS)) return emptyList()
    val protected = protectedRanges(cell)
    val spans = mutableListOf<CellChemSpan>()
    var protectedIndex = 0
    for (match in CELL_CHEM_SPAN_PATTERN.findAll(cell)) {
        val element = parseChemSpan(match.groupValues[1], withPng) ?: continue
        val start = match.range.first
        while (protectedIndex < protected.size && protected[protectedIndex].last < start) {
            protectedIndex++
        }
        val insideProtected = protectedIndex < protected.size &&
            start > protected[protectedIndex].first &&
            start <= protected[protectedIndex].last
        if (insideProtected) continue
        spans += CellChemSpan(start, match.range.last + 1, element)
    }
    return spans
}

/**
 * Every chem marker in [cell], in storage order, with the text between them.
 * Empty markers are ignored; the cell is returned as one [CellSegment.Text]
 * when it carries none.
 */
internal fun cellSegments(cell: String): List<CellSegment> = cellSegments(cell, withPng = true)

/**
 * [withPng] false skips the base64 validation for callers that only need the
 * handles text: decoding a multi-kilobyte payload to print "ethanol CCO" would
 * run on every composition of a text-only surface, and the in-app page always
 * draws live (a stored PNG is an export cache, never the screen).
 *
 * A marker is promoted to a [CellSegment.Chem] only when it stands on its own.
 * One that starts inside a [protectedRanges] region — MathML content or a
 * complete tag such as `<img alt='…'>` — is replaced IN PLACE by its escaped
 * handles text, so the surrounding markup stays whole. Splitting the cell at
 * such a marker would cut an attribute value or a `<math>` block in half and
 * hand the fragments to two different HTML pipelines.
 */
internal fun cellSegments(cell: String, withPng: Boolean): List<CellSegment> {
    if (cell.isEmpty()) return emptyList()
    if (!cell.contains(CELL_CHEM_CLASS)) return listOf(CellSegment.Text(cell))
    val protected = protectedRanges(cell)
    val parts = mutableListOf<CellSegment>()
    val text = StringBuilder()
    var pos = 0
    var protectedIndex = 0
    for (match in CELL_CHEM_SPAN_PATTERN.findAll(cell)) {
        val element = parseChemSpan(match.groupValues[1], withPng)
        if (element == null) {
            // A span shaped like a marker (chem class + a data-smiles
            // attribute) that fails the strict reader must never fall through
            // as raw markup: the mixed page's inline pipeline would print
            // every attribute, `data-png` payload included. Degrade it in
            // place to the escaped handles body, exactly as a text-only
            // surface does.
            if (isMarkerShapedSpan(match.groupValues[1])) {
                text.append(cell, pos, match.range.first)
                    .append(escapeHtmlText(match.groupValues[2]))
                pos = match.range.last + 1
            }
            continue
        }
        val start = match.range.first
        while (protectedIndex < protected.size && protected[protectedIndex].last < start) {
            protectedIndex++
        }
        val insideProtected = protectedIndex < protected.size &&
            start > protected[protectedIndex].first &&
            start <= protected[protectedIndex].last
        if (insideProtected) {
            text.append(cell, pos, start)
                .append(escapeHtmlText(chemElementText(element)))
            pos = match.range.last + 1
            continue
        }
        if (start > pos) text.append(cell, pos, start)
        if (text.isNotEmpty()) {
            parts += CellSegment.Text(text.toString())
            text.clear()
        }
        parts += CellSegment.Chem(element)
        pos = match.range.last + 1
    }
    if (pos < cell.length) text.append(cell, pos, cell.length)
    if (text.isNotEmpty()) parts += CellSegment.Text(text.toString())
    if (parts.isEmpty()) return listOf(CellSegment.Text(cell))
    return parts
}

/**
 * Regions a marker scan must not split: every complete `<math>…</math>` block
 * and every complete tag, `<`…`>` with quote awareness. A marker whose start
 * lies strictly inside one sits in MathML content or in an attribute value, so
 * promoting it to a drawing would tear the surrounding markup apart. A
 * marker's own opening `<span …>` starts exactly at its region's first
 * character and is therefore not "inside" it.
 *
 * Ranges merge only when they overlap or contain each other. Adjacent tags
 * are separate regions: `<b>` followed immediately by a marker is two regions,
 * not one, so the marker start stays outside the leading tag. Merging on
 * `next.first == current.last + 1` made every marker after a tag look
 * protected, which degraded a valid marker to text and left a table whose only
 * structure followed a tag on the native path.
 *
 * Only a `<` that begins something tag-shaped (`<tag`, `</`, `<!`, `<?`) starts
 * a region: a stray comparison sign in prose (`2 < 3`) must not swallow the
 * marker that follows it.
 */
private fun protectedRanges(cell: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    forEachCellMathBlock(cell) { start, end -> ranges += start until end }
    var pos = 0
    while (pos < cell.length) {
        val open = cell.indexOf('<', pos)
        if (open < 0) break
        pos = open + 1
        if (pos >= cell.length || !isTagOpeningChar(cell[pos])) continue
        var i = pos
        var quote = '\u0000'
        var close = -1
        while (i < cell.length) {
            val c = cell[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                close = i
                break
            }
            i++
        }
        if (close < 0) break
        ranges += open..close
        pos = close + 1
    }
    if (ranges.size < 2) return ranges
    ranges.sortBy { it.first }
    val merged = ArrayList<IntRange>(ranges.size)
    var current = ranges[0]
    for (index in 1 until ranges.size) {
        val next = ranges[index]
        current = if (next.first <= current.last) {
            current.first..maxOf(current.last, next.last)
        } else {
            merged += current
            next
        }
    }
    merged += current
    return merged
}

/** A `<` only opens a tag when the next character can begin one. */
private fun isTagOpeningChar(c: Char): Boolean =
    c.isLetter() || c == '/' || c == '!' || c == '?'

/**
 * True when [cell] carries at least one top-level valid marker — one that may
 * become a drawing. A marker protected inside MathML or a tag attribute is
 * text, so a table holding only that stays on the native path.
 */
internal fun hasChemCell(cell: String): Boolean {
    if (!cell.contains(CELL_CHEM_CLASS)) return false
    return cellSegments(cell, withPng = false).any { it is CellSegment.Chem }
}

/**
 * [cell] with every valid marker replaced by its escaped handles text — the
 * degradation every text-only surface uses. A hostile or malformed span is not
 * a marker and stays exactly as it was (prose, handled by the caller's inline
 * pipeline); a protected marker is degraded by [cellSegments] in place and
 * keeps its surrounding markup intact.
 */
internal fun sanitizeCellChem(cell: String): String =
    cellSegments(cell, withPng = false).joinToString("") { segment ->
        when (segment) {
            is CellSegment.Text -> segment.html
            is CellSegment.Chem -> escapeHtmlText(chemElementText(segment.element))
        }
    }

/**
 * Parses one `<span …>` attribute region into an element, or null when the
 * span is not a valid marker. Every attribute must be quoted and one of the
 * allow-listed names; a duplicate is rejected. `data-smiles` is required and
 * may not be blank after entity decoding. An invalid `data-png` is dropped
 * rather than rejecting the whole structure: the text handles still describe
 * it, and no writer may embed a non-PNG payload.
 */
private fun parseChemSpan(
    attributes: String,
    withPng: Boolean
): ContentElement.ChemElement? {
    val attrs = parseCellChemAttributes(attributes) ?: return null
    if (!hasCellChemClass(attrs["class"])) return null
    val smiles = decodeHtmlEntities(attrs[CELL_CHEM_ATTR] ?: return null)
    if (smiles.isBlank()) return null
    val diagram = if (withPng) {
        attrs["data-png"]?.let { cellPngDiagram(it) }.orEmpty()
    } else {
        ""
    }
    return ContentElement.ChemElement(
        smiles = smiles,
        formula = decodeHtmlEntities(attrs["data-formula"].orEmpty()),
        caption = decodeHtmlEntities(attrs["data-caption"].orEmpty()),
        spoken = decodeHtmlEntities(attrs["data-spoken"].orEmpty()),
        diagram = diagram
    )
}

/**
 * The value of [name] in a span's attribute region, without validating it:
 * the value when the name occurs, an empty string when it occurs without a
 * value, or null when it is absent. Used only to decide whether a *failed*
 * span is shaped like a chem marker and therefore must not print its
 * attributes; the strict reader above still decides what a valid marker is.
 */
private fun lenientAttributeValue(attributes: String, name: String): String? {
    var pos = 0
    while (pos < attributes.length) {
        while (pos < attributes.length && attributes[pos].isWhitespace()) pos++
        val nameStart = pos
        while (pos < attributes.length && isAttributeNameChar(attributes[pos])) pos++
        if (pos == nameStart) {
            pos++
            continue
        }
        val matches = pos - nameStart == name.length &&
            attributes.regionMatches(nameStart, name, 0, name.length, ignoreCase = true)
        var k = pos
        while (k < attributes.length && attributes[k].isWhitespace()) k++
        if (k < attributes.length && attributes[k] == '=') {
            k++
            while (k < attributes.length && attributes[k].isWhitespace()) k++
            if (k < attributes.length) {
                val quote = attributes[k]
                if (quote == '"' || quote == '\'') {
                    val valueStart = k + 1
                    var valueEnd = valueStart
                    while (valueEnd < attributes.length && attributes[valueEnd] != quote) valueEnd++
                    if (matches) return attributes.substring(valueStart, valueEnd)
                    pos = if (valueEnd < attributes.length) valueEnd + 1 else attributes.length
                    continue
                }
                val valueStart = k
                var valueEnd = valueStart
                while (valueEnd < attributes.length && !attributes[valueEnd].isWhitespace()) valueEnd++
                if (matches) return attributes.substring(valueStart, valueEnd)
                pos = valueEnd
                continue
            }
        }
        if (matches) return ""
        pos = maxOf(pos, k)
    }
    return null
}

/** True when a span carries the chem class and the required `data-smiles` name. */
private fun isMarkerShapedSpan(attributes: String): Boolean =
    hasCellChemClass(lenientAttributeValue(attributes, "class")) &&
        lenientAttributeValue(attributes, CELL_CHEM_ATTR) != null

/** The class attribute lists [CELL_CHEM_CLASS] as one of its whitespace-separated tokens. */
private fun hasCellChemClass(value: String?): Boolean {
    if (value == null) return false
    var pos = 0
    while (pos < value.length) {
        while (pos < value.length && value[pos].isWhitespace()) pos++
        val start = pos
        while (pos < value.length && !value[pos].isWhitespace()) pos++
        if (pos > start && value.substring(start, pos) == CELL_CHEM_CLASS) return true
    }
    return false
}

/**
 * The span's attribute region as name → raw value, or null when anything is
 * unfamiliar: an unknown name, a duplicate, a missing `=`, an unquoted value or
 * trailing junk. Strictness is the point — a marker is rebuilt, never copied.
 */
private fun parseCellChemAttributes(text: String): Map<String, String>? {
    if (text.isBlank()) return null
    val attrs = LinkedHashMap<String, String>()
    var pos = 0
    while (pos < text.length) {
        while (pos < text.length && text[pos].isWhitespace()) pos++
        if (pos >= text.length) break
        val nameStart = pos
        while (pos < text.length && isAttributeNameChar(text[pos])) pos++
        if (pos == nameStart) return null
        val name = text.substring(nameStart, pos).lowercase()
        if (name !in CELL_CHEM_ATTRIBUTES) return null
        while (pos < text.length && text[pos].isWhitespace()) pos++
        if (pos >= text.length || text[pos] != '=') return null
        pos++
        while (pos < text.length && text[pos].isWhitespace()) pos++
        if (pos >= text.length) return null
        val quote = text[pos]
        if (quote != '"' && quote != '\'') return null
        val valueStart = pos + 1
        var valueEnd = valueStart
        while (valueEnd < text.length && text[valueEnd] != quote) valueEnd++
        if (valueEnd >= text.length) return null
        val value = text.substring(valueStart, valueEnd)
        if (attrs.put(name, value) != null) return null
        pos = valueEnd + 1
        if (pos < text.length && !text[pos].isWhitespace()) return null
    }
    return attrs.takeIf { it.isNotEmpty() }
}

private fun isAttributeNameChar(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == ':'

/**
 * The marker's `data-png` value as a full PNG data URI, or empty. The length is
 * checked before `decodeHtmlEntities` and again before the bounded validator.
 */
private fun cellPngDiagram(raw: String): String {
    if (raw.isEmpty() || raw.length > MAX_CELL_PNG_PAYLOAD_CHARS) return ""
    val payload = decodeHtmlEntities(raw)
    if (payload.length > MAX_CELL_PNG_PAYLOAD_CHARS) return ""
    return if (isBoundedPngPayload(payload)) {
        PNG_DATA_URI_PREFIX + payload
    } else {
        ""
    }
}

/**
 * The bare payload to store in `data-png`, or null. The element's [diagram] is
 * normally a full data URI; a bare payload is accepted too so the emitter and
 * the reader agree on one validator. A stored PNG that fails the bounded check
 * is simply not written — the handles text is the fallback.
 */
private fun cellPngPayload(diagram: String): String? {
    if (diagram.isEmpty()) return null
    val payload = if (diagram.startsWith(PNG_DATA_URI_PREFIX)) {
        diagram.substring(PNG_DATA_URI_PREFIX.length)
    } else {
        diagram
    }
    if (payload.isEmpty() || payload.length > MAX_CELL_PNG_PAYLOAD_CHARS) return null
    return payload.takeIf { isBoundedPngPayload(it) }
}

/**
 * Cheap validation for a renderer-produced diagram on its way into the draft.
 *
 * A capture arrives once per structure per preview composition, so this must
 * not decode a multi-megabyte payload on the UI thread. It checks the whole
 * base64 shape and decodes only the PNG signature — the same bounded rule the
 * cell marker reader uses. Storage/export boundaries still run the strict
 * [com.mcqapp.data.export.isPngDataUri] before embedding a payload.
 */
internal fun isRendererPngDataUri(value: String): Boolean {
    if (!value.startsWith(PNG_DATA_URI_PREFIX)) return false
    val payload = value.substring(PNG_DATA_URI_PREFIX.length)
    return payload.isNotEmpty() && isBoundedPngPayload(payload)
}

/**
 * Bounded PNG validation for the cell raster cache.
 *
 * The editor's cell field re-reads the stored cell on every keystroke, so this
 * runs per keystroke for a structure cell. A full
 * [com.mcqapp.data.export.isPngDataUri] decode of a 100 KB–4 MB export-only
 * payload would allocate the whole raster just to look at its 8-byte
 * signature. Instead the whole payload is scanned for base64/padding shape
 * (no allocation) and only its first 12 data characters are decoded (enough
 * for the signature), so validation allocates at most one 9-byte array.
 * Exporters still run the full validator before they embed the payload.
 */
private fun isBoundedPngPayload(payload: String): Boolean {
    if (payload.isEmpty() || payload.length > MAX_CELL_PNG_PAYLOAD_CHARS) return false
    // Validate the WHOLE payload's shape, not just the characters after the
    // 12-character head: padding inside the head (e.g. the 11th character is
    // '=') must be rejected exactly as the strict exporter validator rejects
    // it. Data is [0, dataEnd); [dataEnd, length) is at most two '='.
    val firstPad = payload.indexOf('=')
    val dataEnd = if (firstPad < 0) payload.length else firstPad
    if (dataEnd == 0) return false
    for (i in 0 until dataEnd) {
        if (!isPngBase64Char(payload[i])) return false
    }
    var padding = 0
    for (i in dataEnd until payload.length) {
        if (payload[i] != '=') return false
        padding++
        if (padding > 2) return false
    }
    // A decoder rejects a 1-mod-4 unpadded payload, and padding only completes
    // a 4-character unit. Payloads shorter than the head were fully decoded.
    val validLength = if (padding == 0) payload.length % 4 != 1 else payload.length % 4 == 0
    if (!validLength) return false
    // Only data characters are decoded, so the head can never contain '='.
    val headLength = minOf(dataEnd, PNG_SIGNATURE_BASE64_CHARS)
    val head = try {
        java.util.Base64.getDecoder().decode(payload.substring(0, headLength))
    } catch (e: IllegalArgumentException) {
        return false
    }
    if (head.size < PNG_SIGNATURE.size) return false
    return PNG_SIGNATURE.indices.all { head[it] == PNG_SIGNATURE[it] }
}

/** 12 base64 characters decode the 8-byte PNG signature (9 bytes when unpadded). */
private const val PNG_SIGNATURE_BASE64_CHARS = 12

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
)

private fun isPngBase64Char(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/'
