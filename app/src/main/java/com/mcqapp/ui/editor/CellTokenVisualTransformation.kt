package com.mcqapp.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.mcqapp.util.CELL_EDITOR_ESCAPE
import com.mcqapp.util.CELL_EDITOR_TOKEN_LENGTH
import com.mcqapp.util.CELL_EDITOR_TOKEN_LEAD
import com.mcqapp.util.CellEditPart
import com.mcqapp.util.CellTokenScheme
import com.mcqapp.util.isCellEditorPrivateUse

/**
 * Shows each opaque cell token as a readable label without changing the value
 * the editor actually edits.
 *
 * `CellEditorText` keeps one five-character token per formula, picture or
 * structure, so `onValueChange` receives a string whose rich parts are
 * characters the user cannot type. Displaying those characters directly would
 * leave gaps in the cell field, so this transformation swaps a token for
 * [labels]`[partIndex]` (`[Formula]`, `[Image]`, `[Structure]`). The underlying
 * value, the caret mapping and deletion all stay one token per part: deleting
 * a label deletes exactly that part.
 *
 * The same [CellTokenScheme] the writer uses validates the token, so a token
 * with a foreign salt (pasted from another cell) or a stale fingerprint (an
 * undo after the part was deleted) is not displayed as a neighbouring part; it
 * is hidden and dropped on write. An escaped literal character is shown once,
 * as text, never as a label. No caret position exists inside a token: every
 * offset within one maps to the token's start, so editing at a label boundary
 * can only touch the token itself and never the surrounding prose.
 */
internal class CellTokenVisualTransformation(
    private val labels: List<String>,
    private val parts: List<CellEditPart> = emptyList(),
    tokenKey: String = ""
) : VisualTransformation {

    private val scheme = CellTokenScheme(parts, tokenKey)

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = StringBuilder(raw.length)
        val originalToTransformed = IntArray(raw.length + 1)
        // One entry per transformed boundary, mapping it back to the token's
        // original offset. A caret anywhere inside a label lands on the token.
        val transformedToOriginal = ArrayList<Int>(raw.length + 1)
        val seen = BooleanArray(parts.size)
        var o = 0
        while (o < raw.length) {
            originalToTransformed[o] = out.length
            val c = raw[o]
            if (c == CELL_EDITOR_ESCAPE) {
                // Deleting the literal of an escape pair can leave ESC before
                // a real token. Do not show the token lead as a literal: drop
                // the dangling escape and let the next iteration render the
                // token's label.
                if (o + 1 < raw.length &&
                    raw[o + 1] == CELL_EDITOR_TOKEN_LEAD &&
                    scheme.tokenAt(raw, o + 1, null) != null
                ) {
                    o++
                    continue
                }
                // Otherwise escape + character is one literal character of
                // user text. Show it once, never as a label, so an escaped
                // Private-Use character cannot look like a formula.
                if (o + 1 < raw.length) {
                    transformedToOriginal += o
                    out.append(raw[o + 1])
                    originalToTransformed[o + 1] = out.length
                    o += 2
                } else {
                    o++      // dangling escape: hidden and dropped on write
                }
                continue
            }
            if (c == CELL_EDITOR_TOKEN_LEAD) {
                val index = scheme.tokenAt(raw, o, seen)
                // Consume the token-shaped Private-Use run only. A truncated
                // token must keep the prose after it, and must not swallow the
                // lead of an adjacent token.
                var end = o + 1
                while (end < raw.length && end - o < CELL_EDITOR_TOKEN_LENGTH) {
                    if (raw[end] == CELL_EDITOR_TOKEN_LEAD) break
                    if (!isCellEditorPrivateUse(raw[end])) break
                    end++
                }
                if (index != null && index in labels.indices && labels[index].isNotEmpty()) {
                    val start = out.length
                    val label = labels[index]
                    repeat(label.length) { transformedToOriginal += o }
                    out.append(label)
                    seen[index] = true
                    // Every raw offset inside the token collapses to its start.
                    for (k in o + 1 until end) originalToTransformed[k] = start
                } else {
                    // A foreign or stale token is hidden; it is dropped on
                    // write rather than rebound to a neighbour.
                    for (k in o + 1 until end) originalToTransformed[k] = out.length
                }
                o = end
                continue
            }
            if (isCellEditorPrivateUse(c)) {
                // A bare token code: hidden, and never persisted.
                o++
                continue
            }
            transformedToOriginal += o
            out.append(c)
            o++
        }
        originalToTransformed[raw.length] = out.length
        transformedToOriginal += raw.length

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                originalToTransformed[offset.coerceIn(0, raw.length)]

            override fun transformedToOriginal(offset: Int): Int =
                transformedToOriginal[offset.coerceIn(0, transformedToOriginal.lastIndex)]
        }
        return TransformedText(AnnotatedString(out.toString()), mapping)
    }
}
