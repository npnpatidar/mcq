package com.mcqapp

import androidx.compose.ui.text.AnnotatedString
import com.mcqapp.ui.editor.CellTokenVisualTransformation
import com.mcqapp.util.CELL_EDITOR_TOKEN_LENGTH
import com.mcqapp.util.CELL_EDITOR_TOKEN_LEAD
import com.mcqapp.util.cellEditParts
import com.mcqapp.util.cellEditorText
import com.mcqapp.util.cellEditorTokenIndex
import com.mcqapp.util.cellTokenKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BEHAVIOUR: the cell field's labels are purely a display concern. The offset
 * mapping must let the caret sit around a label but never inside it, so an edit
 * at a label boundary can only affect the token's raw characters and never the
 * prose or a fragment of the label. A token with a foreign salt, or one whose
 * part was deleted, is hidden rather than shown as a neighbour. Pure JVM: the
 * transformation works on plain strings and needs no Android view.
 */
class CellTokenVisualTransformationTest {

    private val formula = "<math><mi>x</mi></math>"
    private val image = "<img src=\"data:image/png;base64,AAAA\"/>"
    private val key = cellTokenKey("t1", 0, 0)

    /** A cell with prose around two rich parts: `a <F> b <I> c`. */
    private fun rawCell(): String = "a $formula b $image c"

    private val parts = cellEditParts(rawCell())
    private val labels = listOf("", "[Formula]", "", "[Image]", "")

    private fun tokenStarts(raw: String): List<Int> = raw.indices.filter { i ->
        raw[i] == CELL_EDITOR_TOKEN_LEAD &&
            (i == 0 || raw[i - 1] != com.mcqapp.util.CELL_EDITOR_ESCAPE)
    }

    private fun render(raw: String, labels: List<String> = this.labels, parts: List<com.mcqapp.util.CellEditPart> = this.parts) =
        CellTokenVisualTransformation(labels, parts, key).filter(AnnotatedString(raw))

    @Test
    fun `each label replaces exactly its whole token`() {
        val raw = cellEditorText(rawCell(), key)
        val tokenPositions = tokenStarts(raw)
        assertEquals(2, tokenPositions.size)

        val out = render(raw)
        assertEquals("a [Formula] b [Image] c", out.text.text)
        assertTrue(raw.length < out.text.length)
    }

    @Test
    fun `caret positions map before and after a label`() {
        val raw = cellEditorText(rawCell(), key)
        val positions = tokenStarts(raw)
        val first = positions[0]
        val second = positions[1]
        val out = render(raw)
        val mapping = out.offsetMapping

        // Before the label.
        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(0, mapping.transformedToOriginal(0))
        // The token's start is the label's start; the offset after the whole
        // token is the label's end. No raw offset maps inside the label.
        assertEquals(first, mapping.originalToTransformed(first))
        assertEquals(
            first + "[Formula]".length,
            mapping.originalToTransformed(first + CELL_EDITOR_TOKEN_LENGTH)
        )
        assertEquals(first, mapping.transformedToOriginal(first))
        assertEquals(first, mapping.transformedToOriginal(first + 4))
        assertEquals(
            first + CELL_EDITOR_TOKEN_LENGTH,
            mapping.transformedToOriginal(first + "[Formula]".length)
        )

        // The second label, further along after prose.
        val secondDisplayStart = second + ("[Formula]".length - CELL_EDITOR_TOKEN_LENGTH)
        assertEquals(secondDisplayStart, mapping.originalToTransformed(second))
        assertEquals(second, mapping.transformedToOriginal(secondDisplayStart))
        assertEquals(
            second + CELL_EDITOR_TOKEN_LENGTH,
            mapping.transformedToOriginal(secondDisplayStart + "[Image]".length)
        )
    }

    @Test
    fun `no caret position exists inside a label`() {
        val raw = cellEditorText(rawCell(), key)
        val first = tokenStarts(raw)[0]
        val out = render(raw)
        val mapping = out.offsetMapping
        // Every display offset inside the label collapses to the token's raw
        // start, so a forward-delete range taken inside the label is empty and
        // a partial-label edit is impossible.
        for (offset in first until first + "[Formula]".length) {
            assertEquals("offset $offset", first, mapping.transformedToOriginal(offset))
        }
    }

    @Test
    fun `forward delete at a label start is a no-op`() {
        val raw = cellEditorText(rawCell(), key)
        val first = tokenStarts(raw)[0]
        val mapping = render(raw).offsetMapping
        // Compose maps the forward-delete range [T(start), T(start+1)) through
        // the offset mapping. Both ends collapse to the token start, so the
        // range is empty: Delete does nothing. (Backspace is the way to remove
        // the part; see the next test.)
        val rawStart = mapping.transformedToOriginal(first)
        val rawEnd = mapping.transformedToOriginal(first + 1)
        assertEquals(first, rawStart)
        assertEquals(rawStart, rawEnd)
        assertEquals(raw, raw.removeRange(rawStart, rawEnd))
    }

    @Test
    fun `backspace after a label removes only that token`() {
        val raw = cellEditorText(rawCell(), key)
        val first = tokenStarts(raw)[0]
        val mapping = render(raw).offsetMapping
        val rawCaret = mapping.transformedToOriginal(first + "[Formula]".length)
        assertEquals(first + CELL_EDITOR_TOKEN_LENGTH, rawCaret)
        // Backspace deletes the token's last raw character; the truncated
        // token is hidden and the surviving image still resolves.
        val edited = raw.removeRange(rawCaret - 1, rawCaret)
        val again = render(edited)
        assertFalse(again.text.text.contains("[Formula]"))
        assertEquals("a  b [Image] c", again.text.text)
    }

    @Test
    fun `a truncated token does not hide the prose after it`() {
        val raw = cellEditorText(rawCell(), key)
        val first = tokenStarts(raw)[0]
        // Backspace at the label's end removes only the token's last char.
        val truncated = raw.removeRange(
            first + CELL_EDITOR_TOKEN_LENGTH - 1,
            first + CELL_EDITOR_TOKEN_LENGTH
        )
        val out = render(truncated)
        assertEquals("a  b [Image] c", out.text.text)
    }

    @Test
    fun `a truncated token before an adjacent part does not hide it`() {
        val cell = formula + image
        val cellParts = cellEditParts(cell)
        val raw = cellEditorText(cell, key)
        val starts = tokenStarts(raw)
        assertEquals(2, starts.size)
        val truncated = raw.removeRange(
            starts[0] + CELL_EDITOR_TOKEN_LENGTH - 1,
            starts[0] + CELL_EDITOR_TOKEN_LENGTH
        )
        val out = CellTokenVisualTransformation(
            listOf("[Formula]", "[Image]"),
            cellParts,
            key
        ).filter(AnnotatedString(truncated))
        assertEquals("[Image]", out.text.text)
    }

    @Test
    fun `a dangling escape before a token still shows the label`() {
        val cell = "$CELL_EDITOR_TOKEN_LEAD$formula"
        val cellParts = cellEditParts(cell)
        val raw = cellEditorText(cell, key)
        val edited = raw.removeRange(1, 2)   // ESC + TOKEN, no literal left
        val out = CellTokenVisualTransformation(listOf("", "[Formula]"), cellParts, key)
            .filter(AnnotatedString(edited))
        assertEquals("[Formula]", out.text.text)
    }

    @Test
    fun `an unedited escaped literal before a part still shows both`() {
        val cell = "$CELL_EDITOR_TOKEN_LEAD$formula"
        val cellParts = cellEditParts(cell)
        val raw = cellEditorText(cell, key)
        val out = CellTokenVisualTransformation(listOf("", "[Formula]"), cellParts, key)
            .filter(AnnotatedString(raw))
        assertEquals("$CELL_EDITOR_TOKEN_LEAD[Formula]", out.text.text)
    }

    @Test
    fun `an escaped literal character is shown as text not as a label`() {
        // A literal Private-Use character that collides with a token code is
        // escaped by CellEditorText; the field must show it, not a label.
        val cell = "a \uE001 b $formula"
        val cellParts = cellEditParts(cell)
        val raw = cellEditorText(cell, key)
        val out = render(raw, listOf("", "[Formula]"), cellParts)
        assertEquals("a \uE001 b [Formula]", out.text.text)
        assertEquals(1, out.text.text.count { it == '\uE001' })
        assertFalse("the escaped literal must not become a label", out.text.text.startsWith("[Formula]"))
    }

    @Test
    fun `a foreign or stale token is hidden`() {
        val target = "x $image y"
        val targetParts = cellEditParts(target)
        val targetLabels = listOf("", "[Image]", "")
        val targetKey = cellTokenKey("t1", 0, 1)
        val view = cellEditorText(target, targetKey)

        // A token from another cell (foreign salt).
        val foreign = cellEditorText("a $formula b", cellTokenKey("t1", 0, 0))
            .substring(2, 2 + CELL_EDITOR_TOKEN_LENGTH)
        val out = CellTokenVisualTransformation(targetLabels, targetParts, targetKey)
            .filter(AnnotatedString(view + foreign))
        assertEquals("x [Image] y", out.text.text)
        assertFalse("a pasted token never renders as a label", out.text.text.contains("[Formula]"))

        // A stale token: the part exists but the fingerprint does not match
        // (an undo after a replacement). It must not bind to the image either.
        val token = view.substring(2, 2 + CELL_EDITOR_TOKEN_LENGTH)
        val otherFingerprint = if (token[3] == '\uE002') '\uE003' else '\uE002'
        val stale = token.replaceRange(3, 4, otherFingerprint.toString())
        val staleOut = CellTokenVisualTransformation(targetLabels, targetParts, targetKey)
            .filter(AnnotatedString(view + stale))
        assertEquals("x [Image] y", staleOut.text.text)
    }

    @Test
    fun `plain text passes through unchanged`() {
        val out = render("just prose", emptyList(), emptyList())
        assertEquals("just prose", out.text.text)
        assertEquals(0, out.offsetMapping.originalToTransformed(0))
        assertEquals(4, out.offsetMapping.transformedToOriginal(4))
        assertNull(cellEditorTokenIndex('x'))
    }
}
