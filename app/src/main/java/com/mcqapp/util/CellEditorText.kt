package com.mcqapp.util

import com.mcqapp.domain.CellPart
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.tableCellParts

/**
 * What a table cell's text field can show without exposing storage markup.
 *
 * A cell is stored as one opaque HTML/MathML string, so a formula (`<math>`),
 * a picture (`<img src="data:…">`) and a chemical structure (`<span
 * class="mcq-chem-cell">`) would otherwise appear in the editor as raw markup
 * and base64. [cellEditParts] splits that string into ordered parts once, and
 * [cellEditorText] renders each rich part as ONE opaque token while
 * [withCellEditorText] writes an edited view back. Nothing is ever located
 * with `indexOf("[image]")`: a token carries its part's identity, so deleting
 * one of two identical-looking parts removes exactly that part, and user text
 * that merely looks like a marker cannot be mistaken for one.
 *
 * A nested `<table>` inside a cell is not a rich part: it stays verbatim text,
 * because the editor does not author nested tables.
 *
 * Pure JVM: no Compose, no Android. The editor UI only supplies the token
 * labels it displays (a [androidx.compose.ui.text.input.VisualTransformation]
 * maps the token to a readable label).
 */
sealed interface CellEditPart {
    /** Prose (or inline markup that is not a formula/picture/structure). */
    data class Text(val html: String) : CellEditPart

    /** One complete `<math>…</math>` block, raw, exactly as stored. */
    data class Formula(val mathml: String) : CellEditPart

    /** One complete `<img …>` tag, verbatim (the stored picture contract). */
    data class Image(val tag: String) : CellEditPart

    /** One validated chem marker, rebuilt from its own fields when written. */
    data class Structure(val element: ContentElement.ChemElement) : CellEditPart
}
internal enum class CellEditPartKind { TEXT, FORMULA, IMAGE, STRUCTURE }

internal fun CellEditPart.kind(): CellEditPartKind = when (this) {
    is CellEditPart.Text -> CellEditPartKind.TEXT
    is CellEditPart.Formula -> CellEditPartKind.FORMULA
    is CellEditPart.Image -> CellEditPartKind.IMAGE
    is CellEditPart.Structure -> CellEditPartKind.STRUCTURE
}

/**
 * Tokens live in the Unicode Private Use Area: a keyboard cannot type one and
 * a normal paste does not contain one, so a token cannot be produced by
 * accident. A token is
 *
 *     lead + salt1 + salt2 + fingerprint + index
 *
 * (five characters). The salt is derived from the cell's stable key, so a
 * token copied from another cell can never rebind to a part here; the
 * fingerprint is derived from the part's own stored markup, so an undo that
 * restores an old token after a part was deleted cannot resurrect that part
 * either. The salt isolates the cell; the fingerprint keeps a stale token from
 * rebinding to a surviving neighbour.
 *
 * Every Private-Use character in user text is written as [CELL_EDITOR_ESCAPE]
 * + the character and restored on write, so literal text round-trips
 * byte-identically instead of doubling on each read. Any Private-Use
 * character that is neither an escape pair nor a valid token is dropped on
 * write: unrecognised token garbage must never reach storage.
 */
private const val TOKEN_LEAD = '\uE000'
internal const val CELL_EDITOR_TOKEN_LEAD = TOKEN_LEAD
internal const val CELL_EDITOR_ESCAPE = '\uF8FF'

/** One token is lead + two salt characters + fingerprint + index. */
internal const val CELL_EDITOR_TOKEN_LENGTH = 5

/** Code alphabet shared by the index, salt and fingerprint characters. */
private const val TOKEN_CODE_FIRST = '\uE001'
private const val TOKEN_CODE_CAPACITY = 6398

private fun tokenChar(index: Int): Char = (TOKEN_CODE_FIRST.code + index).toChar()

/** The part index this character stands for, or null when it is not a code. */
internal fun cellEditorTokenIndex(c: Char): Int? =
    if (c in TOKEN_CODE_FIRST..'\uF8FE') c.code - TOKEN_CODE_FIRST.code else null

internal fun isCellEditorPrivateUse(c: Char): Boolean = c in TOKEN_LEAD..CELL_EDITOR_ESCAPE

/**
 * Stable identity of one editable cell. The salt in every token is derived
 * from this, so a token pasted from another cell (or another editor) is
 * unrecognised rather than bound to whatever part now sits at its index.
 */
internal fun cellTokenKey(blockId: String, row: Int, col: Int): String =
    "$blockId#$row:$col"

/**
 * The token grammar for one cell: its salt, its parts and their fingerprints.
 * Built once per read/write so a cell with a large cached structure PNG does
 * not re-serialise it for every token.
 */
internal class CellTokenScheme(
    private val parts: List<CellEditPart>,
    tokenKey: String
) {
    private val salt: Pair<Char, Char> = saltChars(tokenKey)
    private val fingerprints: List<Char> = parts.map(::partFingerprint)

    fun appendToken(sb: StringBuilder, index: Int) {
        sb.append(TOKEN_LEAD).append(salt.first).append(salt.second)
            .append(fingerprints[index]).append(tokenChar(index))
    }

    /**
     * The part index the token at [at] resolves to, or null. A token with a
     * foreign salt is never accepted. A token whose index no longer names the
     * same part (an undo after a delete renumbered the survivors) resolves by
     * content fingerprint instead, and a token for the deleted part resolves
     * to nothing.
     */
    fun tokenAt(text: String, at: Int, seen: BooleanArray? = null): Int? {
        if (at + CELL_EDITOR_TOKEN_LENGTH > text.length) return null
        if (text[at] != TOKEN_LEAD) return null
        if (text[at + 1] != salt.first || text[at + 2] != salt.second) return null
        val fingerprint = text[at + 3]
        val index = cellEditorTokenIndex(text[at + 4]) ?: return null
        if (index < parts.size && (seen == null || !seen[index]) &&
            fingerprints[index] == fingerprint
        ) {
            return index
        }
        // Content-only fallback for a stale index (an undo after a part was
        // deleted or replaced renumbers the survivors). The fingerprint is
        // 13 bits, so two different parts collide with probability about
        // 1/6398 per pair; a stale token could then bind to a same-fingerprint
        // neighbour of any kind. The salt still fences other cells, and this
        // fallback only applies to the cell's own edit history, so the
        // residual is accepted deliberately rather than widening the token.
        for (i in parts.indices) {
            if ((seen == null || !seen[i]) && fingerprints[i] == fingerprint) return i
        }
        return null
    }
}

private fun saltHash(value: String): Int {
    var h = value.hashCode()
    h = h xor (h ushr 16)
    h *= 0x7feb352d
    h = h xor (h ushr 15)
    return h and 0x7FFFFFFF
}

private fun saltChars(tokenKey: String): Pair<Char, Char> {
    val a = TOKEN_CODE_FIRST + saltHash("$tokenKey\u0000salt-a") % TOKEN_CODE_CAPACITY
    val b = TOKEN_CODE_FIRST + saltHash("$tokenKey\u0000salt-b") % TOKEN_CODE_CAPACITY
    return a to b
}

/**
 * A stable fingerprint of the part's stored content and kind. Hashes the
 * fields directly instead of re-serialising the part: a structure may carry a
 * large export-only PNG, and this runs for every part on every keystroke.
 * [String.hashCode] is specified by the JVM, so the value is stable within the
 * editor session and across processes for the same stored cell.
 */
private fun partFingerprint(part: CellEditPart): Char {
    var h = when (part) {
        is CellEditPart.Text -> 31 + part.html.hashCode()
        is CellEditPart.Formula -> 62 + part.mathml.hashCode()
        is CellEditPart.Image -> 93 + part.tag.hashCode()
        is CellEditPart.Structure -> {
            var s = 124
            s = 31 * s + part.element.smiles.hashCode()
            s = 31 * s + part.element.formula.hashCode()
            s = 31 * s + part.element.caption.hashCode()
            s = 31 * s + part.element.spoken.hashCode()
            s = 31 * s + part.element.diagram.hashCode()
            s
        }
    }
    h = h xor (h ushr 16)
    h *= 0x7feb352d
    h = h xor (h ushr 15)
    return TOKEN_CODE_FIRST + ((h and 0x7FFFFFFF) % TOKEN_CODE_CAPACITY)
}

/**
 * Splits a stored cell into ordered text/formula/picture/structure parts.
 * Empty cells have no parts. A truncated `<img` and a `<math` without its
 * closing tag stay text: neither is a part the editor could safely rebuild.
 */
internal fun cellEditParts(cell: String): List<CellEditPart> {
    if (cell.isEmpty()) return emptyList()
    val parts = mutableListOf<CellEditPart>()
    for (segment in cellSegments(cell)) {
        when (segment) {
            is CellSegment.Chem -> parts += CellEditPart.Structure(segment.element)
            is CellSegment.Text -> appendCellTextParts(segment.html, parts)
        }
    }
    return parts
}

private fun appendCellTextParts(html: String, out: MutableList<CellEditPart>) {
    for (part in tableCellParts(html)) {
        when (part) {
            is CellPart.Math -> out += CellEditPart.Formula(part.mathml)
            is CellPart.Text -> appendCellImageParts(part.html, out)
        }
    }
}

private fun appendCellImageParts(html: String, out: MutableList<CellEditPart>) {
    var pos = 0
    forEachCellImageSpan(html) { tag, start, end ->
        if (start > pos) out += CellEditPart.Text(html.substring(pos, start))
        // A truncated tag is kept as text: it has no readable source, and the
        // import normaliser is the surface that decides what to do with it.
        if (tag != null) out += CellEditPart.Image(tag)
        else out += CellEditPart.Text(html.substring(start, end))
        pos = end
    }
    if (pos < html.length) out += CellEditPart.Text(html.substring(pos))
}

/** The stored form of [part], rebuilt for a structure so no marker is copied. */
internal fun cellEditPartHtml(part: CellEditPart): String = when (part) {
    is CellEditPart.Text -> part.html
    is CellEditPart.Formula -> part.mathml
    is CellEditPart.Image -> part.tag
    is CellEditPart.Structure -> chemCellHtml(part.element)
}

/**
 * The cell as one editable line: prose verbatim, with one opaque token per
 * rich part. The token order is the storage order, so the text around each
 * part stays where the author put it.
 */
internal fun cellEditorText(cell: String, tokenKey: String = ""): String {
    val parts = cellEditParts(cell)
    if (parts.isEmpty()) return ""
    val tokenCount = minOf(parts.size, TOKEN_CODE_CAPACITY)
    val scheme = CellTokenScheme(parts, tokenKey)
    val sb = StringBuilder(cell.length)
    parts.forEachIndexed { index, part ->
        if (part is CellEditPart.Text || index >= tokenCount) {
            appendEscapedText(sb, cellEditPartHtml(part))
        } else {
            scheme.appendToken(sb, index)
        }
    }
    return sb.toString()
}

/**
 * Writes one text part. Every Private-Use character is escaped, because any
 * one of them could be a token lead or a token-code character; the escaped
 * form is restored by [withCellEditorText], so plain text round-trips
 * byte-identically instead of growing on every read.
 */
private fun appendEscapedText(sb: StringBuilder, text: String) {
    for (c in text) {
        if (isCellEditorPrivateUse(c)) sb.append(CELL_EDITOR_ESCAPE)
        sb.append(c)
    }
}

/**
 * Writes the edited [editorText] back over [cell].
 *
 * Each token is paired with the part it was created for. A token the user
 * deleted drops exactly that part; a token pasted in twice keeps only its
 * first occurrence. A token from another cell is rejected by its salt, and an
 * old token for a deleted part is rejected by its fingerprint, so neither can
 * be rebound to a neighbour. Escape pairs are restored, and any other
 * Private-Use character is dropped: unrecognised token garbage is never
 * written to storage. User text is never scanned for `[image]`-style
 * placeholders, so a cell that contains that text is not corrupted.
 */
internal fun withCellEditorText(
    cell: String,
    editorText: String,
    tokenKey: String = ""
): String {
    val parts = cellEditParts(cell)
    if (parts.isEmpty()) {
        // No part can be addressed yet; a stray token character is foreign
        // garbage, not text the author typed.
        return editorText.filterNot(::isCellEditorPrivateUse)
    }
    val scheme = CellTokenScheme(parts, tokenKey)
    val out = StringBuilder(cell.length)
    val seen = BooleanArray(parts.size)
    val text = StringBuilder()
    var i = 0
    while (i < editorText.length) {
        val c = editorText[i]
        if (c == CELL_EDITOR_ESCAPE) {
            // Deleting the literal of an escape pair can leave ESC immediately
            // before a real token. Treating that lead as a literal would store
            // a raw U+E000 and lose the part, so a lead that starts a valid
            // token makes the escape a dangling one that is dropped.
            if (i + 1 < editorText.length &&
                editorText[i + 1] == CELL_EDITOR_TOKEN_LEAD &&
                scheme.tokenAt(editorText, i + 1, null) != null
            ) {
                i++
                continue
            }
            // Otherwise the next character is literal. A dangling escape is
            // token garbage and is dropped.
            if (i + 1 < editorText.length) {
                text.append(editorText[i + 1])
                i += 2
            } else {
                i++
            }
            continue
        }
        if (c == CELL_EDITOR_TOKEN_LEAD) {
            val index = scheme.tokenAt(editorText, i, seen)
            if (index != null) {
                if (text.isNotEmpty()) {
                    out.append(text)
                    text.clear()
                }
                out.append(cellEditPartHtml(parts[index]))
                seen[index] = true
            }
            // Consume the token-shaped run, never the prose or the next token:
            // a partially deleted token must not eat a neighbouring part.
            var end = i + 1
            while (end < editorText.length && end - i < CELL_EDITOR_TOKEN_LENGTH) {
                if (editorText[end] == CELL_EDITOR_TOKEN_LEAD) break
                if (!isCellEditorPrivateUse(editorText[end])) break
                end++
            }
            i = end
            continue
        }
        if (isCellEditorPrivateUse(c)) {
            // A bare token code: never persist an invisible marker.
            i++
            continue
        }
        text.append(c)
        i++
    }
    if (text.isNotEmpty()) out.append(text)
    return out.toString()
}

/**
 * [cell] with one rich part appended (a separating space keeps prose from
 * running into the new part). A new picture/formula/structure is always
 * appended; its token then lets the user place text around it.
 */
internal fun cellWithPartInserted(cell: String, part: CellEditPart): String {
    val html = cellEditPartHtml(part)
    if (cell.isEmpty()) return html
    // Preserve the author's whitespace verbatim; only add a separator when the
    // cell does not already end with one.
    val separator = if (cell.last().isWhitespace()) "" else " "
    return cell + separator + html
}

/**
 * [cell] with the part at [index] replaced, or [cell] unchanged when that index
 * no longer exists or names a different kind of part. A stale gallery callback
 * must never rewrite a neighbouring part (or destroy prose that moved into the
 * slot), so a mismatch is a no-op rather than an append or a blind replace.
 * The other parts and all text are preserved.
 */
internal fun cellWithPartReplaced(
    cell: String,
    index: Int,
    part: CellEditPart
): String {
    val parts = cellEditParts(cell)
    val current = parts.getOrNull(index) ?: return cell
    if (current.kind() != part.kind()) return cell
    return parts.mapIndexed { i, existing ->
        if (i == index) part else existing
    }.joinToString("") { cellEditPartHtml(it) }
}

/** [cell] without the part at [index]; an out-of-range index is a no-op. */
internal fun cellWithPartRemoved(cell: String, index: Int): String {
    val parts = cellEditParts(cell)
    if (index !in parts.indices) return cell
    return parts.filterIndexed { i, _ -> i != index }
        .joinToString("") { cellEditPartHtml(it) }
}

/**
 * Every cell part whose kind is [kind], as part index → part, in order. Tests
 * and the editor's part-aware actions use it to name one formula or structure
 * by position without rescanning the cell in a second grammar.
 */
internal fun cellPartsOfKind(cell: String, kind: CellEditPartKind): List<Pair<Int, CellEditPart>> =
    cellEditParts(cell).withIndex()
        .filter { it.value.kind() == kind }
        .map { it.index to it.value }

/**
 * Size of a base64 data URI's decoded payload, by shape only (no decode).
 * Whitespace is ignored because a MIME-encoded payload carries CRLF every 76
 * characters; the importer canonicalises, the editor needs a bound.
 */
internal fun cellImagePayloadBytes(src: String): Long {
    val comma = src.indexOf(',')
    if (comma < 0) return Long.MAX_VALUE
    var core = 0L
    for (i in comma + 1 until src.length) {
        val c = src[i]
        if (c.isWhitespace()) continue
        if (c == '=') break
        core++
    }
    return core * 3 / 4
}

/**
 * [src] in the one stored cell-picture form, or null when it is not a
 * portable picture. A payload wrapped by a MIME encoder (the import and pick
 * scalers emit CRLF every 76 characters) is unwrapped here, so the same
 * picture hashes and round-trips identically whether it came from a file or
 * from the gallery picker.
 */
internal fun canonicalCellImageSrc(src: String): String? {
    if (portableCellImageSrc(src)) return src
    val comma = src.indexOf(',')
    if (comma < 0) return null
    val compact = StringBuilder(src.length)
    compact.append(src, 0, comma + 1)
    for (i in comma + 1 until src.length) {
        val c = src[i]
        if (!c.isWhitespace()) compact.append(c)
    }
    return compact.toString().takeIf { portableCellImageSrc(it) }
}

/**
 * True when [src] may be stored in a cell: a portable inline picture (the
 * shared [portableCellImageSrc] contract, after unwrapping a MIME-encoded
 * payload) inside the shared [MAX_CELL_IMAGE_BYTES] cap. The editor rejects
 * anything else with a message instead of writing an oversized or foreign
 * source into a cell.
 */
internal fun cellImageAllowed(src: String): Boolean {
    val canonical = canonicalCellImageSrc(src) ?: return false
    return cellImagePayloadBytes(canonical) <= MAX_CELL_IMAGE_BYTES
}

/**
 * Characters a cell SMILES may carry beside the renderer's allowlist: the
 * spaces and commas a block field or an imported value can contain. The
 * renderer drops them from the JS literal exactly as it does for a block, and
 * the marker's visible body is the readable handles text, so neither can
 * break the literal or the drawing. Quotes, angle brackets and semicolons
 * stay rejected: they are the characters that could escape either context.
 */
private val CELL_SMILES_SEPARATORS = setOf(' ', '\t', ',')

/**
 * True when [smiles] is non-blank and every character can reach the drawing
 * engine unchanged. Deliberately a per-character rule, not a full SMILES
 * parser: [SMILES_ALLOWED_CHARS] is the same allowlist the renderer applies,
 * so validation and drawing cannot drift. Surrounding whitespace is trimmed
 * and the harmless separators above are accepted, because a structure copied
 * from a block or an import may carry them; the cell dialog must not disable
 * Insert for a value the block proceeds to draw.
 */
internal fun isValidCellSmiles(smiles: String): Boolean {
    val trimmed = smiles.trim()
    return trimmed.any { it in SMILES_ALLOWED_CHARS } &&
        trimmed.all { it in SMILES_ALLOWED_CHARS || it in CELL_SMILES_SEPARATORS }
}
