package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.CHEM_DIAGRAM_PREFIX
import com.mcqapp.ui.editor.EditorUiState
import com.mcqapp.ui.editor.applyKeyedChemDiagram
import com.mcqapp.util.chemCellHtml
import com.mcqapp.util.cellChemSpans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BEHAVIOUR: a renderer capture for a structure inside one table cell lands in
 * exactly that cell's marker, addressed by stable block id plus row/column.
 *
 * These are the ViewModel's pure write rules ([applyKeyedChemDiagram] over
 * [EditorUiState]); they run as plain JVM tests on every host. The same rules
 * back the drawing preview's `McqChemDiagram` callback.
 */
class EditorTableCellCaptureTest {

    private val firstPng = CHEM_DIAGRAM_PREFIX + "iVBORw0KGgo="
    private val secondPng = CHEM_DIAGRAM_PREFIX + "iVBORw0KGgoAAA=="
    private val firstPayload = firstPng.removePrefix(CHEM_DIAGRAM_PREFIX)

    private val ethanol = ContentElement.ChemElement(
        smiles = "CCO",
        formula = "C2H5OH",
        caption = "ethanol",
        spoken = "ethanol"
    )
    private val benzene = ContentElement.ChemElement(
        smiles = "c1ccccc1",
        formula = "C6H6",
        caption = "benzene",
        spoken = "benzene"
    )

    private fun state(vararg cells: String, isDirty: Boolean = false): EditorUiState {
        val table = ContentElement.TableElement(listOf(cells.toList(), listOf("other")))
        return EditorUiState(
            loading = false,
            elements = listOf(table),
            blockIds = listOf("b1"),
            isDirty = isDirty
        )
    }

    private fun tableCell(state: EditorUiState, row: Int = 0, col: Int = 0): String =
        (state.elements.single() as ContentElement.TableElement).rows[row][col]

    @Test
    fun aCaptureForTheRightCellSetsThatMarkersPngAndPreservesEverythingElse() {
        // Two structures in one cell, plus prose on both sides: only the one
        // named by the key may change.
        val cell = "before " +
            chemCellHtml(ethanol, includePng = false) + " mid " +
            chemCellHtml(benzene, includePng = false) + " after"
        val before = state(cell)

        val after = applyKeyedChemDiagram(before, "b1#0:0@CCO", firstPng)

        assertNotSame(before, after)
        val updated = tableCell(after)
        assertTrue("surrounding prose lost: $updated", updated.startsWith("before "))
        assertTrue("surrounding prose lost: $updated", updated.endsWith(" after"))
        assertTrue("middle prose lost: $updated", " mid " in updated)
        assertTrue("stored payload missing: $updated", "data-png=\"$firstPayload\"" in updated)
        assertEquals("the other cell must not change", "other", tableCell(after, 1, 0))

        val spans = cellChemSpans(updated)
        assertEquals(2, spans.size)
        val captured = spans.single { it.element.smiles == "CCO" }.element
        val untouched = spans.single { it.element.smiles == "c1ccccc1" }.element
        assertEquals(firstPng, captured.diagram)
        // Every other marker field survives the canonical rebuild.
        assertEquals("C2H5OH", captured.formula)
        assertEquals("ethanol", captured.caption)
        assertEquals("ethanol", captured.spoken)
        assertEquals("", untouched.diagram)
        assertEquals("C6H6", untouched.formula)
        assertEquals("benzene", untouched.caption)

        // The cell's readable handles body is still the stored text, never base64.
        assertTrue(updated.contains("ethanol C2H5OH CCO"))
        assertTrue(updated.contains("benzene C6H6 c1ccccc1"))
    }

    @Test
    fun theSecondIdenticalCaptureIsANoOp() {
        val before = state(chemCellHtml(ethanol, includePng = false))
        val once = applyKeyedChemDiagram(before, "b1#0:0@CCO", firstPng)
        assertNotSame(before, once)
        // The same raster arriving again must leave the state instance alone:
        // that is what stops a capture callback from feeding itself.
        assertSame(once, applyKeyedChemDiagram(once, "b1#0:0@CCO", firstPng))
        // A different PNG is a real change.
        assertNotSame(once, applyKeyedChemDiagram(once, "b1#0:0@CCO", secondPng))
    }

    @Test
    fun aStaleKeyForAnotherBlockRowColumnOrSmilesWritesNothing() {
        val before = state(chemCellHtml(ethanol, includePng = false))
        listOf(
            "b2#0:0@CCO",   // block no longer in the draft
            "b1#1:0@CCO",   // row exists but holds no structure
            "b1#0:1@CCO",   // column does not exist on this row
            "b1#0:0@CCC",   // the cell's structure is not this SMILES
            "b1#0@CCO",     // malformed: no row/column
            "b1#x:0@CCO",   // malformed row
            "b1#0:y@CCO"    // malformed column
        ).forEach { key ->
            assertSame("stale key $key wrote a capture", before, applyKeyedChemDiagram(before, key, firstPng))
        }
    }

    @Test
    fun aHostileOrIncompletePngIsRejected() {
        val before = state(chemCellHtml(ethanol, includePng = false))
        listOf(
            "data:image/png;base64,AAAA",              // valid base64, not a PNG
            "data:image/svg+xml;base64,PHN2Zz4=",      // wrong media type
            "data:image/png;base64,<script>alert(1)",  // not base64 at all
            CHEM_DIAGRAM_PREFIX                          // no payload
        ).forEach { png ->
            assertSame("hostile payload accepted: $png", before, applyKeyedChemDiagram(before, "b1#0:0@CCO", png))
        }
        assertFalse("no payload may be stored", tableCell(before).contains("data-png"))
    }

    @Test
    fun aCaptureNeverChangesTheDirtyFlag() {
        val clean = state(chemCellHtml(ethanol, includePng = false), isDirty = false)
        val cleanCaptured = applyKeyedChemDiagram(clean, "b1#0:0@CCO", firstPng)
        assertFalse("a capture is not a user edit", cleanCaptured.isDirty)

        val dirty = clean.copy(isDirty = true)
        val dirtyCaptured = applyKeyedChemDiagram(dirty, "b1#0:0@CCO", firstPng)
        assertTrue("a capture must not clear a real edit", dirtyCaptured.isDirty)
    }

    @Test
    fun aSmilesThatContainsAHashStillParsesItsCellKey() {
        // `C#N` is a real SMILES (a nitrile), so the '#' inside the smiles must
        // not be mistaken for the row/column separator. The parser searches the
        // part before '@', never the smiles.
        val nitrile = ContentElement.ChemElement(
            smiles = "C#N",
            formula = "HCN",
            caption = "hydrogen cyanide",
            spoken = "hydrogen cyanide"
        )
        val before = state(chemCellHtml(nitrile, includePng = false))
        val after = applyKeyedChemDiagram(before, "b1#0:0@C#N", firstPng)
        assertNotSame(before, after)
        val captured = cellChemSpans(tableCell(after)).single().element
        assertEquals("C#N", captured.smiles)
        assertEquals(firstPng, captured.diagram)
    }

    @Test
    fun twoCellsWithTheSameSmilesKeepDistinctKeysAndPngs() {
        val table = ContentElement.TableElement(
            listOf(listOf(chemCellHtml(ethanol, includePng = false), chemCellHtml(ethanol, includePng = false)))
        )
        val before = EditorUiState(
            loading = false,
            elements = listOf(table),
            blockIds = listOf("b7")
        )

        val left = applyKeyedChemDiagram(before, "b7#0:0@CCO", firstPng)
        val right = applyKeyedChemDiagram(left, "b7#0:1@CCO", secondPng)

        val cells = (right.elements.single() as ContentElement.TableElement).rows.single()
        assertEquals(firstPng, cellChemSpans(cells[0]).single().element.diagram)
        assertEquals(secondPng, cellChemSpans(cells[1]).single().element.diagram)
        assertNotSame("the second cell must be a real write", left, right)
    }

    @Test
    fun anExplanationCellIsAddressedByItsBlockIdToo() {
        val cell = chemCellHtml(ethanol, includePng = false)
        val before = EditorUiState(
            loading = false,
            explanationElements = listOf(ContentElement.TableElement(listOf(listOf(cell)))),
            explanationBlockIds = listOf("e1")
        )
        val after = applyKeyedChemDiagram(before, "e1#0:0@CCO", firstPng)
        assertNotSame(before, after)
        val updated = (after.explanationElements.single() as ContentElement.TableElement).rows[0][0]
        assertEquals(firstPng, cellChemSpans(updated).single().element.diagram)
    }
}
