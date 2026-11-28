package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CELL_EDITOR_ESCAPE
import com.mcqapp.util.CELL_EDITOR_TOKEN_LENGTH
import com.mcqapp.util.CELL_EDITOR_TOKEN_LEAD
import com.mcqapp.util.CellEditPart
import com.mcqapp.util.CellEditPartKind
import com.mcqapp.util.SMILES_ALLOWED_CHARS
import com.mcqapp.util.canonicalCellImageSrc
import com.mcqapp.util.cellEditParts
import com.mcqapp.util.cellEditorText
import com.mcqapp.util.cellEditPartHtml
import com.mcqapp.util.cellImageAllowed
import com.mcqapp.util.cellImagePayloadBytes
import com.mcqapp.util.cellPartsOfKind
import com.mcqapp.util.cellWithPartInserted
import com.mcqapp.util.cellWithPartRemoved
import com.mcqapp.util.cellWithPartReplaced
import com.mcqapp.util.chemCellHtml
import com.mcqapp.util.isValidCellSmiles
import com.mcqapp.util.kind
import com.mcqapp.util.withCellEditorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * BEHAVIOUR: the editor's cell text view is a lossless, collision-free view
 * of the stored cell. A rich part is one opaque token character, so editing
 * prose around it cannot lose, duplicate or corrupt the formula, picture or
 * structure behind it. Pure JVM: no Compose, no Android.
 */
class CellEditorTextTest {

    private val pngBytes = ByteArray(24).also { b ->
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b, 0)
        'I'.code.toByte().let { b[8] = it }
        'H'.code.toByte().let { b[9] = it }
        'D'.code.toByte().let { b[10] = it }
        'R'.code.toByte().let { b[11] = it }
        b[19] = 2
        b[23] = 2
    }
    private val png: String = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)

    private val formula = "<math><mi>x</mi></math>"
    private val otherFormula = "<math><mi>y</mi></math>"
    private val imageTag = "<img src=\"$png\"/>"
    private val structure = ContentElement.ChemElement(
        smiles = "CCO",
        formula = "C2H5OH",
        caption = "ethanol",
        spoken = "ethanol"
    )

    private fun richCell(): String =
        "before $formula middle $imageTag and " + chemCellHtml(structure) + " after"

    /** Every token start in a view, skipping an escaped literal lead. */
    private fun tokenStarts(view: String): List<Int> = view.indices.filter { i ->
        view[i] == CELL_EDITOR_TOKEN_LEAD &&
            (i == 0 || view[i - 1] != CELL_EDITOR_ESCAPE)
    }

    private fun isPrivateUse(c: Char): Boolean = c in '\uE000'..'\uF8FF'

    // ---- view / write round trip ---------------------------------------

    @Test
    fun `an empty cell has an empty editor view`() {
        assertEquals("", cellEditorText(""))
        assertEquals("", withCellEditorText("", ""))
        assertTrue(cellEditParts("").isEmpty())
    }

    @Test
    fun `plain text is its own editor view`() {
        val text = "just some prose"
        assertEquals(text, cellEditorText(text))
        assertEquals(text, withCellEditorText(text, text))
        assertEquals(listOf<CellEditPart>(CellEditPart.Text(text)), cellEditParts(text))
    }

    @Test
    fun `every rich part becomes one distinct opaque token`() {
        val cell = richCell()
        val view = cellEditorText(cell)
        // The prose is untouched; the three rich parts are five-character
        // tokens, not the raw `<math>`, `<img>` or chem marker.
        assertTrue(view.startsWith("before "))
        assertTrue(view.endsWith(" after"))
        assertFalse(view.contains("<math"))
        assertFalse(view.contains("<img"))
        assertFalse(view.contains("mcq-chem-cell"))
        assertEquals(3, cellEditParts(cell).count { it.kind() != CellEditPartKind.TEXT })
        val starts = tokenStarts(view)
        assertEquals(3, starts.size)
        // Distinct tokens: identity survives a deletion.
        assertEquals(3, starts.map { view.substring(it, it + CELL_EDITOR_TOKEN_LENGTH) }.toSet().size)
    }

    @Test
    fun `editing text around rich parts preserves them in place`() {
        val cell = richCell()
        val edited = cellEditorText(cell)
            .replaceFirst("before ", "BEFORE! ")
            .replace("middle", "between")
        val written = withCellEditorText(cell, edited)
        assertTrue(written.startsWith("BEFORE! $formula between "))
        assertTrue(written.contains(imageTag))
        assertTrue(written.contains(chemCellHtml(structure)))
        assertTrue(written.endsWith(" after"))
        // Reading the written cell back gives exactly the edited view.
        assertEquals(edited, cellEditorText(written))
    }

    @Test
    fun `round trip of the unchanged view is byte identical`() {
        val cell = richCell()
        assertEquals(cell, withCellEditorText(cell, cellEditorText(cell)))
    }

    @Test
    fun `deleting one of two identical-looking markers removes exactly that one`() {
        val cell = "a $formula b $otherFormula c"
        val formulaParts = cellPartsOfKind(cell, CellEditPartKind.FORMULA)
        assertEquals(2, formulaParts.size)

        val view = cellEditorText(cell)
        val starts = tokenStarts(view)
        assertEquals(2, starts.size)
        // Remove the SECOND token only.
        val withoutSecond = view.removeRange(starts[1], starts[1] + CELL_EDITOR_TOKEN_LENGTH)
        val written = withCellEditorText(cell, withoutSecond)
        assertEquals("a $formula b  c", written)
        assertEquals(1, cellEditParts(written).count { it.kind() == CellEditPartKind.FORMULA })
        assertEquals(3, cellEditParts(written).size)
    }

    @Test
    fun `deleting every token leaves the prose and no markers`() {
        val cell = "a $formula b"
        val view = cellEditorText(cell)
        val start = tokenStarts(view).single()
        assertEquals(
            "a  b",
            withCellEditorText(cell, view.removeRange(start, start + CELL_EDITOR_TOKEN_LENGTH))
        )
    }

    @Test
    fun `deleting the last character of a token keeps the prose after it`() {
        val cell = "a $formula z"
        val view = cellEditorText(cell)
        val start = tokenStarts(view).single()
        // Backspace at the label's end removes only the token's final char.
        val truncated = view.removeRange(
            start + CELL_EDITOR_TOKEN_LENGTH - 1,
            start + CELL_EDITOR_TOKEN_LENGTH
        )
        val written = withCellEditorText(cell, truncated)
        assertEquals("a  z", written)
        assertFalse("no token garbage may be stored", written.any(::isPrivateUse))
    }

    @Test
    fun `a truncated token before an adjacent part keeps the second part`() {
        val cell = formula + imageTag
        val view = cellEditorText(cell)
        val starts = tokenStarts(view)
        assertEquals(2, starts.size)
        val truncated = view.removeRange(
            starts[0] + CELL_EDITOR_TOKEN_LENGTH - 1,
            starts[0] + CELL_EDITOR_TOKEN_LENGTH
        )
        val written = withCellEditorText(cell, truncated)
        assertEquals("the adjacent image must survive", imageTag, written)
        assertFalse("no token garbage may be stored", written.any(::isPrivateUse))
    }

    @Test
    fun `a truncated token with no separator keeps the prose after it`() {
        val cell = "a${formula}z"
        val view = cellEditorText(cell)
        val start = tokenStarts(view).single()
        val truncated = view.removeRange(
            start + CELL_EDITOR_TOKEN_LENGTH - 1,
            start + CELL_EDITOR_TOKEN_LENGTH
        )
        assertEquals("az", withCellEditorText(cell, truncated))
    }

    @Test
    fun `deleting the literal before an escaped token keeps the part`() {
        // Prose is a literal token lead directly before a formula. In the view
        // it is ESC + lead + TOKEN; backspacing the literal leaves ESC + TOKEN.
        val cell = "$CELL_EDITOR_TOKEN_LEAD$formula"
        val view = cellEditorText(cell)
        val escapeAt = view.indexOf(CELL_EDITOR_ESCAPE)
        assertTrue(escapeAt >= 0)
        val edited = view.removeRange(escapeAt + 1, escapeAt + 2)
        assertTrue(
            "precondition: a dangling escape before the token",
            edited.startsWith("$CELL_EDITOR_ESCAPE$CELL_EDITOR_TOKEN_LEAD")
        )
        val written = withCellEditorText(cell, edited)
        assertEquals("the formula must survive", formula, written)
        assertFalse("no raw Private-Use character may be stored", written.any(::isPrivateUse))
    }

    @Test
    fun `a literal lead immediately before a part round-trips unchanged`() {
        // ESC literal-lead TOKEN: the dangling-escape guard must not treat the
        // escaped literal as the start of the token that follows it.
        val cell = "$CELL_EDITOR_TOKEN_LEAD$formula"
        val view = cellEditorText(cell)
        assertTrue(
            "precondition: escaped literal then token lead",
            view.startsWith(
                "$CELL_EDITOR_ESCAPE$CELL_EDITOR_TOKEN_LEAD$CELL_EDITOR_TOKEN_LEAD"
            )
        )
        assertEquals(cell, withCellEditorText(cell, view))
    }

    @Test
    fun `a literal private-use character round-trips and is not a marker`() {
        val literal = "a \uE000 b"
        val cell = "$literal $formula"
        val view = cellEditorText(cell)
        // Escaped in the view so it cannot be read back as the formula token.
        // Escaped as the escape character + the literal, not as a bare lead
        // (a bare lead would be indistinguishable from a token start).
        assertTrue(view.contains("$CELL_EDITOR_ESCAPE$CELL_EDITOR_TOKEN_LEAD"))
        assertEquals(cell, withCellEditorText(cell, view))
        assertEquals(1, cellEditParts(cell).count { it.kind() == CellEditPartKind.FORMULA })
    }

    @Test
    fun `literal private use text round-trips without compounding`() {
        // \uE000 and \uE001 are token codes for a cell with parts; a code above
        // the part count must still survive a plain-text read unchanged, and
        // reading twice must not double the character again.
        listOf('\uE000', '\uE001', '\uE7FF', '\uF8FF').forEach { c ->
            val cell = "a${c}b"
            val view = cellEditorText(cell)
            assertEquals("first read of \\u${c.code.toString(16)}", cell, withCellEditorText(cell, view))
            val second = cellEditorText(withCellEditorText(cell, view))
            assertEquals("second read of \\u${c.code.toString(16)}", view, second)
            assertEquals(cell, withCellEditorText(cell, second))
        }
    }

    @Test
    fun `private use text beside rich parts still round-trips`() {
        val cell = "a \uE000 b \uE001 c $formula \uF8FF d"
        val view = cellEditorText(cell)
        assertEquals(cell, withCellEditorText(cell, view))
        assertEquals(view, cellEditorText(withCellEditorText(cell, view)))
        assertEquals(1, cellEditParts(cell).count { it.kind() == CellEditPartKind.FORMULA })
    }

    @Test
    fun `user text that looks like a marker is not treated as one`() {
        // There is a REAL image part here, so the write path is exercised: a
        // `[image]`-scanning implementation would corrupt the literal text.
        val cell = "[image] before $imageTag between [formula] after"
        val view = cellEditorText(cell)
        assertTrue("the literal text is still visible", view.contains("[image]"))
        assertEquals(cell, withCellEditorText(cell, view))
        assertEquals(1, cellEditParts(cell).count { it.kind() == CellEditPartKind.IMAGE })
    }

    @Test
    fun `a pasted duplicate token cannot duplicate a rich part`() {
        val cell = "a $formula b"
        val view = cellEditorText(cell)
        val start = tokenStarts(view).single()
        val token = view.substring(start, start + CELL_EDITOR_TOKEN_LENGTH)
        val pasted = view + token
        val written = withCellEditorText(cell, pasted)
        assertEquals(1, Regex("<math").findAll(written).count())
        assertFalse("no private-use garbage may be stored", written.any(::isPrivateUse))
    }

    @Test
    fun `undo after deleting a part leaves no token garbage and no ghost part`() {
        val cell = "a $formula b $imageTag c"
        val key = "t1#0:0"
        val view = cellEditorText(cell, key)
        val starts = tokenStarts(view)
        assertEquals(2, starts.size)

        // The user deletes the formula token.
        val afterDelete = withCellEditorText(
            cell,
            view.removeRange(starts[0], starts[0] + CELL_EDITOR_TOKEN_LENGTH),
            key
        )
        assertFalse(afterDelete.contains("<math"))
        assertTrue(afterDelete.contains("<img"))

        // Undo restores the OLD value (same cell key, stale part indexes). The
        // deleted formula's token must not come back, and the surviving image
        // must not be duplicated.
        val undone = withCellEditorText(afterDelete, view, key)
        assertFalse("no private-use garbage: $undone", undone.any(::isPrivateUse))
        assertFalse("the deleted formula must not return", undone.contains("<math"))
        assertEquals(1, Regex("<img").findAll(undone).count())
        assertTrue(undone.startsWith("a "))
    }

    @Test
    fun `a token pasted from another cell is dropped instead of rebound`() {
        val formulaCell = "a $formula b"
        val imageCell = "x $imageTag y"
        val keyA = "t1#0:0"
        val keyB = "t1#0:1"
        val viewA = cellEditorText(formulaCell, keyA)
        val start = tokenStarts(viewA).single()
        val foreignToken = viewA.substring(start, start + CELL_EDITOR_TOKEN_LENGTH)
        val viewB = cellEditorText(imageCell, keyB)

        val written = withCellEditorText(imageCell, viewB + foreignToken, keyB)
        assertFalse("no private-use garbage may be stored", written.any(::isPrivateUse))
        assertFalse("the foreign formula must not appear", written.contains("<math"))
        assertEquals("the target image is kept exactly once", 1, Regex("<img").findAll(written).count())
        assertEquals("the text is unchanged", imageCell, written)
    }

    @Test
    fun `hostile marker markup that is not a valid marker stays text`() {
        val cell = "<span class=\"mcq-chem-cell\" onclick=\"x\">bad</span>"
        assertEquals(cell, cellEditorText(cell))
        assertEquals(cell, withCellEditorText(cell, cellEditorText(cell)))
        assertEquals(0, cellEditParts(cell).count { it.kind() == CellEditPartKind.STRUCTURE })
    }

    // ---- insert / replace / remove -------------------------------------

    @Test
    fun `inserting into an empty cell writes only the part`() {
        assertEquals(formula, cellWithPartInserted("", CellEditPart.Formula(formula)))
        assertEquals(chemCellHtml(structure), cellWithPartInserted("", CellEditPart.Structure(structure)))
    }

    @Test
    fun `inserting after prose separates it with a space`() {
        assertEquals("a $formula", cellWithPartInserted("a", CellEditPart.Formula(formula)))
        assertEquals("a $formula", cellWithPartInserted("a ", CellEditPart.Formula(formula)))
    }

    @Test
    fun `inserting into a whitespace-only cell keeps the whitespace`() {
        assertEquals("   $formula", cellWithPartInserted("   ", CellEditPart.Formula(formula)))
        assertEquals(" $formula", cellWithPartInserted(" ", CellEditPart.Formula(formula)))
        assertEquals(" \t $formula", cellWithPartInserted(" \t ", CellEditPart.Formula(formula)))
    }

    @Test
    fun `a cell whose only content is a part is one token`() {
        val view = cellEditorText(imageTag)
        assertEquals(CELL_EDITOR_TOKEN_LENGTH, view.length)
        assertEquals(imageTag, withCellEditorText(imageTag, view))
    }

    @Test
    fun `adjacent parts become adjacent distinct tokens`() {
        val cell = formula + imageTag
        val view = cellEditorText(cell)
        assertEquals(2 * CELL_EDITOR_TOKEN_LENGTH, view.length)
        assertEquals(2, tokenStarts(view).size)
        assertNotEquals(
            "tokens carry identity",
            view.substring(0, CELL_EDITOR_TOKEN_LENGTH),
            view.substring(CELL_EDITOR_TOKEN_LENGTH)
        )
        assertEquals(cell, withCellEditorText(cell, view))
    }

    @Test
    fun `removing the only part leaves an empty cell`() {
        assertEquals("", cellWithPartRemoved(imageTag, 0))
        assertEquals("", cellWithPartRemoved(formula, 0))
        assertEquals("a ", cellWithPartRemoved("a $formula", 1))
    }

    @Test
    fun `replacing one part preserves the other parts and the text`() {
        val cell = "a $formula b $imageTag c"
        val formulaIndex = cellPartsOfKind(cell, CellEditPartKind.FORMULA).single().first
        val replaced = cellWithPartReplaced(cell, formulaIndex, CellEditPart.Formula(otherFormula))
        assertEquals("a $otherFormula b $imageTag c", replaced)
    }

    @Test
    fun `removing one part keeps everything else`() {
        val cell = "a $formula b $imageTag c"
        val imageIndex = cellPartsOfKind(cell, CellEditPartKind.IMAGE).single().first
        assertEquals("a $formula b  c", cellWithPartRemoved(cell, imageIndex))
        assertEquals(cell, cellWithPartRemoved(cell, 99))
    }

    @Test
    fun `a stale or kind-mismatched replace is a no-op`() {
        val cell = "a $formula"
        // Out of range.
        assertEquals(cell, cellWithPartReplaced(cell, 99, CellEditPart.Formula(otherFormula)))
        // The slot is prose now; replacing it would destroy the text.
        assertEquals(cell, cellWithPartReplaced(cell, 0, CellEditPart.Formula(otherFormula)))
        // The intended slot still works.
        assertEquals(
            "a $otherFormula",
            cellWithPartReplaced(cell, 1, CellEditPart.Formula(otherFormula))
        )
    }

    @Test
    fun `a structure is rebuilt from its fields rather than copied`() {
        val html = cellEditPartHtml(CellEditPart.Structure(structure))
        assertEquals(chemCellHtml(structure), html)
        assertTrue(html.startsWith("<span class=\"mcq-chem-cell\""))
    }

    // ---- the cell image cap --------------------------------------------

    @Test
    fun `a small portable picture is allowed and its size is counted`() {
        assertTrue(cellImageAllowed(png))
        assertTrue(cellImagePayloadBytes(png) > 0)
        // A MIME-encoded (CRLF-wrapped) payload is counted the same way and is
        // accepted after it is unwrapped to the one stored form.
        val wrapped = png.replace(",", ",\r\n")
        assertEquals(cellImagePayloadBytes(png), cellImagePayloadBytes(wrapped))
        assertTrue(cellImageAllowed(wrapped))
        assertEquals(png, canonicalCellImageSrc(wrapped))
        assertEquals(png, canonicalCellImageSrc(png))
        // Any whitespace inside the payload is MIME wrapping, not an error.
        assertEquals(png, canonicalCellImageSrc(png.replace(",", ",\n ")))
    }

    @Test
    fun `an oversized picture is rejected`() {
        val big = ByteArray(600 * 1024)
        val src = "data:image/png;base64," + Base64.getEncoder().encodeToString(big)
        assertTrue("precondition", cellImagePayloadBytes(src) > 512 * 1024)
        assertFalse(cellImageAllowed(src))
    }

    @Test
    fun `non-portable or non-picture sources are rejected`() {
        listOf(
            "file:///tmp/x.png",
            "http://example.com/x.png",
            "data:image/svg+xml;base64,AAAA",
            "data:text/html;base64,AAAA",
            "data:image/png;base64,AA*A",
            ""
        ).forEach { src ->
            assertFalse("$src must not be storable in a cell", cellImageAllowed(src))
        }
    }

    @Test
    fun `a jpeg data uri is a portable cell picture`() {
        val jpeg = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(pngBytes)
        assertTrue(cellImageAllowed(jpeg))
    }

    // ---- SMILES validation ---------------------------------------------

    @Test
    fun `smiles validation uses the renderer's shared allowlist`() {
        assertTrue(isValidCellSmiles("c1ccccc1"))
        assertTrue(isValidCellSmiles("C[C@H](N)C(=O)O"))
        assertTrue(isValidCellSmiles("CCO>>CC=O"))
        assertFalse(isValidCellSmiles(""))
        assertFalse(isValidCellSmiles("   "))
        assertFalse(isValidCellSmiles("CCO\"</script>"))
        // The set is the renderer's own internal allowlist: no second copy.
        assertTrue('>' in SMILES_ALLOWED_CHARS)
        assertTrue('@' in SMILES_ALLOWED_CHARS)
        assertTrue('%' in SMILES_ALLOWED_CHARS)
        assertFalse('"' in SMILES_ALLOWED_CHARS)
        assertFalse(' ' in SMILES_ALLOWED_CHARS)
        listOf('C', 'c', '>', '@', '%', '"', '<', ';').forEach { c ->
            assertEquals(
                "$c must follow the shared allowlist",
                c in SMILES_ALLOWED_CHARS,
                isValidCellSmiles(c.toString())
            )
        }
    }

    /**
     * A structure lifted from a block field or an import can carry spaces
     * around and between its atoms and a comma-separated second component.
     * The block proceeds to draw it (the renderer filters the separators from
     * the JS literal), so the cell dialog must not disable Insert for it.
     */
    @Test
    fun `accepts the spaces and commas a block or imported smiles may carry`() {
        assertTrue(isValidCellSmiles("  CCO  "))
        assertTrue(isValidCellSmiles("C C O"))
        assertTrue(isValidCellSmiles("CCO, CCN"))
        assertTrue(isValidCellSmiles("CCO\t>>\tCC=O"))
        // Only the separators are added; characters that could break the JS
        // literal or the HTML fallback stay rejected, even inside a separator
        // run.
        assertFalse(isValidCellSmiles("CCO, \"quoted\""))
        assertFalse(isValidCellSmiles("CCO; CCN"))
        assertFalse(isValidCellSmiles("CCO < CCN"))
        assertFalse(isValidCellSmiles(" , "))
    }

    @Test
    fun `editing prose in a structure cell preserves a large cached png`() {
        // The marker's data-png is an export cache; the editor must carry it
        // through a prose edit without a full base64 decode. Bounded
        // validation only decodes the 12-character signature prefix.
        val payload = "iVBORw0KGgo" + "A".repeat(200_000)
        val withPng = structure.copy(diagram = "data:image/png;base64,$payload")
        val cell = "before " + chemCellHtml(withPng) + " after"
        val view = cellEditorText(cell)
        val written = withCellEditorText(cell, view.replaceFirst("before", "BEFORE"))
        assertEquals("BEFORE " + chemCellHtml(withPng) + " after", written)
        assertTrue(written.contains("data-png=\"$payload\""))
    }
}
