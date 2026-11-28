package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.EditorUiState
import com.mcqapp.ui.editor.ImageTarget
import com.mcqapp.ui.editor.OptionEditorState
import com.mcqapp.ui.editor.applyImageTarget
import com.mcqapp.ui.editor.editorSnapshot
import com.mcqapp.ui.editor.updateTableBlockById
import com.mcqapp.ui.editor.withCell
import com.mcqapp.ui.editor.withCellEditorText
import com.mcqapp.ui.editor.withCellPart
import com.mcqapp.ui.editor.withCellPartRemoved
import com.mcqapp.util.CellEditPart
import com.mcqapp.util.CellEditPartKind
import com.mcqapp.util.cellEditParts
import com.mcqapp.util.cellEditorText
import com.mcqapp.util.cellImageTag
import com.mcqapp.util.cellPartsOfKind
import com.mcqapp.util.kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * BEHAVIOUR: a table cell can be authored from the editor. The grid writes
 * text back without touching the markers around it, the per-cell menu inserts
 * and removes a formula/picture/structure by stable block id, and an image
 * picked for a cell lands in exactly that cell. Pure JVM: [applyImageTarget]
 * and the table transforms are the same functions the Compose grid calls.
 */
class EditorTableCellAuthoringTest {

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
    private val otherPng: String = "data:image/png;base64," +
        Base64.getEncoder().encodeToString(pngBytes + byteArrayOf(0))

    private val formula = "<math><mi>x</mi></math>"

    private fun table(vararg cells: String) = ContentElement.TableElement(listOf(cells.toList()))

    private fun state(
        table: ContentElement.TableElement,
        blockId: String = "t1"
    ) = EditorUiState(
        loading = false,
        elements = listOf(table),
        blockIds = listOf(blockId)
    )

    // ---- the grid's text field -----------------------------------------

    @Test
    fun `typing around a formula leaves it in place and marks the draft dirty`() {
        val cell = "a $formula b"
        val s = state(table(cell))
        val clean = editorSnapshot(s)
        val view = cellEditorText(cell)
        val after = s.copy(elements = listOf(table(cell).withCellEditorText(0, 0, view.replace("b", "B!"))))
        assertEquals("a $formula B!", (after.elements.single() as ContentElement.TableElement).rows[0][0])
        assertTrue("a real text edit changes the snapshot", editorSnapshot(after) != clean)
    }

    @Test
    fun `writing the unchanged view back is a no-op instance`() {
        val t = table("a $formula b")
        assertSame(t, t.withCellEditorText(0, 0, cellEditorText("a $formula b")))
    }

    // ---- insert / replace / remove by block id -------------------------

    @Test
    fun `a formula inserted into a cell keeps the prose`() {
        val s = state(table("caption"))
        val after = updateTableBlockById(s, "t1") {
            it.withCellPart(0, 0, null, CellEditPart.Formula(formula))
        }
        assertEquals("caption $formula", (after.elements.single() as ContentElement.TableElement).rows[0][0])
    }

    @Test
    fun `replacing a part keeps the surrounding text and other parts`() {
        val structure = CellEditPart.Structure(
            ContentElement.ChemElement(smiles = "CCO", caption = "ethanol")
        )
        val cell = "a $formula b " + com.mcqapp.util.cellEditPartHtml(structure) + " c"
        val s = state(table(cell))
        val index = cellPartsOfKind(cell, CellEditPartKind.FORMULA).single().first
        val replacement = "<math><mi>z</mi></math>"
        val after = updateTableBlockById(s, "t1") {
            it.withCellPart(0, 0, index, CellEditPart.Formula(replacement))
        }
        val written = (after.elements.single() as ContentElement.TableElement).rows[0][0]
        assertTrue(written.contains("a $replacement b "))
        assertTrue(written.contains("ethanol CCO"))
        assertTrue(written.endsWith(" c"))
    }

    @Test
    fun `removing a structure leaves the cell text`() {
        val cell = "a " + com.mcqapp.util.cellEditPartHtml(
            CellEditPart.Structure(ContentElement.ChemElement(smiles = "CCO"))
        ) + " b"
        val s = state(table(cell))
        val index = cellPartsOfKind(cell, CellEditPartKind.STRUCTURE).single().first
        val after = updateTableBlockById(s, "t1") { it.withCellPartRemoved(0, 0, index) }
        assertEquals("a  b", (after.elements.single() as ContentElement.TableElement).rows[0][0])
    }

    @Test
    fun `a stale block id writes nothing`() {
        val s = state(table("x"))
        assertSame(s, updateTableBlockById(s, "gone") { it.withCell(0, 0, "y") })
    }

    @Test
    fun `an out-of-range cell writes nothing`() {
        val s = state(table("x"))
        assertSame(s, updateTableBlockById(s, "t1") {
            it.withCellPart(4, 0, null, CellEditPart.Formula(formula))
        })
    }

    // ---- the picked picture --------------------------------------------

    @Test
    fun `a picked cell image lands in that cell after the text`() {
        val s = state(table("caption"))
        val after = applyImageTarget(
            s,
            ImageTarget.TableCell("t1", 0, 0, replacePart = null),
            png
        )
        val cell = (after.elements.single() as ContentElement.TableElement).rows[0][0]
        assertEquals("caption " + cellImageTag(png), cell)
        assertEquals(1, cellEditParts(cell).count { it.kind() == CellEditPartKind.IMAGE })
    }

    @Test
    fun `replacing a cell image keeps one picture and the text`() {
        val s = state(table("caption"))
        val withImage = applyImageTarget(s, ImageTarget.TableCell("t1", 0, 0), png)
        val cell = (withImage.elements.single() as ContentElement.TableElement).rows[0][0]
        val imageIndex = cellPartsOfKind(cell, CellEditPartKind.IMAGE).single().first
        val replaced = applyImageTarget(
            withImage,
            ImageTarget.TableCell("t1", 0, 0, replacePart = imageIndex),
            otherPng
        )
        val after = (replaced.elements.single() as ContentElement.TableElement).rows[0][0]
        assertTrue(after.startsWith("caption "))
        assertTrue(after.contains(cellImageTag(otherPng)))
        assertFalse(after.contains(cellImageTag(png)))
        assertEquals(1, cellEditParts(after).count { it.kind() == CellEditPartKind.IMAGE })
    }

    @Test
    fun `a delayed replace after the part was deleted writes nothing`() {
        val s = state(table("caption"))
        val withImage = applyImageTarget(s, ImageTarget.TableCell("t1", 0, 0), png)
        val cell = (withImage.elements.single() as ContentElement.TableElement).rows[0][0]
        val imageIndex = cellPartsOfKind(cell, CellEditPartKind.IMAGE).single().first
        // The user removes the image while the gallery picker is still open.
        val deleted = updateTableBlockById(withImage, "t1") {
            it.withCellPartRemoved(0, 0, imageIndex)
        }
        assertEquals("caption ", (deleted.elements.single() as ContentElement.TableElement).rows[0][0])
        // The delayed pick must not append to the now-shorter cell.
        val after = applyImageTarget(
            deleted,
            ImageTarget.TableCell("t1", 0, 0, replacePart = imageIndex),
            otherPng
        )
        assertSame("a stale replace is a no-op", deleted, after)
        assertEquals("caption ", (after.elements.single() as ContentElement.TableElement).rows[0][0])
    }

    @Test
    fun `a replace aimed at a prose part writes nothing`() {
        val s = state(table("caption"))
        val withImage = applyImageTarget(s, ImageTarget.TableCell("t1", 0, 0), png)
        val cell = (withImage.elements.single() as ContentElement.TableElement).rows[0][0]
        val textIndex = cellPartsOfKind(cell, CellEditPartKind.TEXT).first().first
        val after = applyImageTarget(
            withImage,
            ImageTarget.TableCell("t1", 0, 0, replacePart = textIndex),
            otherPng
        )
        assertSame("a kind mismatch must not destroy the prose", withImage, after)
        assertEquals(cell, (after.elements.single() as ContentElement.TableElement).rows[0][0])
    }

    @Test
    fun `a mime-wrapped picked image is stored unwrapped`() {
        val wrapped = png.replace(",", ",\r\n")
        val after = applyImageTarget(state(table("x")), ImageTarget.TableCell("t1", 0, 0), wrapped)
        val cell = (after.elements.single() as ContentElement.TableElement).rows[0][0]
        assertFalse("no CRLF may reach the stored cell", cell.contains("\r") || cell.contains("\n"))
        assertTrue(cell.contains(cellImageTag(png)))
    }

    @Test
    fun `a cell image target resolves an option table by stable id`() {
        val optionTable = table("opt")
        val s = EditorUiState(
            loading = false,
            options = listOf(
                OptionEditorState(
                    id = "a",
                    elements = listOf(optionTable),
                    blockIds = listOf("opt-table")
                )
            )
        )
        val after = applyImageTarget(s, ImageTarget.TableCell("opt-table", 0, 0), png)
        val cell = (after.options.single().elements.single() as ContentElement.TableElement).rows[0][0]
        assertTrue(cell.startsWith("opt "))
        assertTrue(cell.contains("<img"))
    }

    @Test
    fun `a cell image target resolves an explanation table by stable id`() {
        val s = EditorUiState(
            loading = false,
            explanationElements = listOf(table("why")),
            explanationBlockIds = listOf("exp-table")
        )
        val after = applyImageTarget(s, ImageTarget.TableCell("exp-table", 0, 0), png)
        val cell = (after.explanationElements.single() as ContentElement.TableElement).rows[0][0]
        assertTrue(cell.startsWith("why "))
    }

    @Test
    fun `a stale or out-of-range cell image target writes nothing`() {
        val s = state(table("x"))
        assertSame(s, applyImageTarget(s, ImageTarget.TableCell("missing", 0, 0), png))
        assertSame(s, applyImageTarget(s, ImageTarget.TableCell("t1", 3, 3), png))
        assertSame(s, applyImageTarget(s, ImageTarget.TableCell("t1", 0, 0), ""))
    }

    @Test
    fun `an oversized cell image is rejected at the target boundary`() {
        val s = state(table("x"))
        val oversized = "data:image/png;base64," +
            Base64.getEncoder().encodeToString(ByteArray(600 * 1024))
        assertSame(
            "a source over the cell cap must never reach the cell",
            s,
            applyImageTarget(s, ImageTarget.TableCell("t1", 0, 0), oversized)
        )
    }

    @Test
    fun `inserting each rich kind keeps it readable by the cell grammar`() {
        val s = state(table(""))
        val after = updateTableBlockById(s, "t1") { t ->
            t.withCellPart(0, 0, null, CellEditPart.Formula(formula))
                .withCellPart(0, 0, null, CellEditPart.Image(cellImageTag(png)))
                .withCellPart(
                    0, 0, null,
                    CellEditPart.Structure(ContentElement.ChemElement(smiles = "CCO", caption = "ethanol"))
                )
        }
        val cell = (after.elements.single() as ContentElement.TableElement).rows[0][0]
        val kinds = cellEditParts(cell).map { it.kind() }
        assertTrue(CellEditPartKind.FORMULA in kinds)
        assertTrue(CellEditPartKind.IMAGE in kinds)
        assertTrue(CellEditPartKind.STRUCTURE in kinds)
        assertEquals(3, kinds.count { it != CellEditPartKind.TEXT })
    }
}
