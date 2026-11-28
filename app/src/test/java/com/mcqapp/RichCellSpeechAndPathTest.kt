package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CELL_CHEM_CLASS
import com.mcqapp.util.CELL_FORMULA_MARKER
import com.mcqapp.util.CELL_IMAGE_ALT
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.CellSegment
import com.mcqapp.util.ContentRenderPath
import com.mcqapp.util.accessibleContentText
import com.mcqapp.util.accessibleMathText
import com.mcqapp.util.cellSegments
import com.mcqapp.util.contentRenderPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Room-free half of the rich-cell surface matrix: the render-path price
 * and TalkBack. Both run on every host, including `:app:testPureJvm` on ARM64;
 * the backup/JSON/sample half is [RichCellSurfaceMatrixTest] and is CI-only.
 *
 * The fixture is the same one [RichCellSurfaceMatrixTest] uses
 * ([RichCellFixtures]): one row carrying prose, an inline `<math>` formula, a
 * canonical portable `<img>` and a `mcq-chem-cell` structure, placed in the
 * stem, an option and the explanation.
 *
 * Per-cell grammar and payload handling are already pinned by
 * [TableCellRenderTest], [FormulaAccessibilityTest], [ChemCellTest] and
 * [ChemistryRenderTest]; this file checks that the combined row takes the
 * mixed path and that the combined spoken text stays ordered and payload-free
 * on all three element lists.
 */
class RichCellSpeechAndPathTest {

    // ---- 4. price of the WebView path -------------------------------------

    @Test
    fun aRichCellTableTakesTheMixedPathAndAPlainTableStaysNative() {
        val question = RichCellFixtures.question()
        assertEquals(
            "stem table",
            ContentRenderPath.MIXED,
            contentRenderPath(question.elements)
        )
        assertEquals(
            "option table",
            ContentRenderPath.MIXED,
            contentRenderPath(question.options.single { it.id == "b" }.elements)
        )
        assertEquals(
            "explanation table",
            ContentRenderPath.MIXED,
            contentRenderPath(question.explanationElements)
        )

        // A table with the same four columns but no drawable payload keeps the
        // native fast path: the mixed view is for a payload, not for tables.
        val plain = listOf(
            ContentElement.TableElement(
                listOf(RichCellFixtures.headerRow, listOf("Water", "H2O", "O", "water"))
            )
        )
        assertEquals(
            "a plain table must not pay for the WebView",
            ContentRenderPath.NATIVE,
            contentRenderPath(plain)
        )

        // A non-portable picture is not drawable in either path, so it must not
        // drag the table onto the WebView either.
        val remote = listOf(
            ContentElement.TableElement(
                listOf(
                    RichCellFixtures.headerRow,
                    listOf("Water", "H2O", "O", "<img src=\"https://example.com/water.png\"/>")
                )
            )
        )
        assertEquals(
            "an undrawable picture must not force the WebView",
            ContentRenderPath.NATIVE,
            contentRenderPath(remote)
        )
    }

    // ---- 5. accessibility -------------------------------------------------

    @Test
    fun accessibilitySpeaksEveryRichTableInRowOrderAndNeverTheMarkup() {
        assertSpokenRichTable(RichCellFixtures.stem(), "Water is the liquid in the stem row.")
        assertSpokenRichTable(RichCellFixtures.optionTable(), RichCellFixtures.OPTION_PROSE)
        assertSpokenRichTable(
            RichCellFixtures.explanation(),
            "Water is the liquid in the explanation row."
        )
    }

    /**
     * One rich table's spoken surface: the structure's readable handles and the
     * formula's spoken form, in row order, and never its marker attributes or
     * payload.
     *
     * A portable picture cell does carry the canonical
     * `alt="Image in table"` ([CELL_IMAGE_ALT]), but that generic alt is
     * deliberately discarded for speech: it says nothing about the picture, so
     * the cell is announced as [CELL_IMAGE_MARKER] instead. The structure cell
     * is a different case — it has readable handles, so it is never announced
     * as an image, even though its stored marker carries a captured PNG.
     */
    private fun assertSpokenRichTable(elements: List<ContentElement>, prose: String) {
        val spoken = accessibleContentText(elements)
        val formulaSpoken = accessibleMathText(RichCellFixtures.formulaCell)
        val structureSpoken = "one oxygen atom bonded to two hydrogen atoms"

        assertTrue("the row prose: $spoken", spoken.contains(prose))
        assertTrue("the formula text: $spoken", spoken.contains(formulaSpoken))
        assertTrue("the structure handles: $spoken", spoken.contains(structureSpoken))
        assertTrue("the structure's formula: $spoken", spoken.contains("H₂O"))

        assertFalse("no data URI may be spoken: $spoken", spoken.contains("data:"))
        assertFalse("no base64 may be spoken: $spoken", spoken.contains("base64"))
        assertFalse("no captured PNG may be spoken: $spoken", spoken.contains("iVBOR"))
        assertFalse("no marker class may be spoken: $spoken", spoken.contains(CELL_CHEM_CLASS))
        assertFalse("no marker attribute may be spoken: $spoken", spoken.contains("data-smiles"))
        assertFalse("no marker tag may be spoken: $spoken", spoken.contains("<span"))
        assertFalse("no raw MathML may be spoken: $spoken", spoken.contains("<math"))
        assertFalse("no formula placeholder may be spoken: $spoken", spoken.contains(CELL_FORMULA_MARKER))
        assertFalse(
            "the generic image alt is deliberately not spoken: $spoken",
            spoken.contains(CELL_IMAGE_ALT)
        )

        val proseAt = spoken.indexOf(prose)
        val formulaAt = spoken.indexOf(formulaSpoken)
        val structureAt = spoken.indexOf(structureSpoken)
        val pictureAt = spoken.indexOf(CELL_IMAGE_MARKER)
        assertTrue("prose before formula: $spoken", proseAt in 0 until formulaAt)
        assertTrue("formula before structure: $spoken", formulaAt < structureAt)
        assertTrue("structure before picture: $spoken", structureAt < pictureAt)

        // The marker belongs to the plain picture, not to the structure: one
        // [image] per rich row, and the structure is spoken from its own fields
        // even though its stored form carries a captured PNG.
        assertEquals(
            "only the picture cell is an [image]: $spoken",
            1,
            Regex(Regex.escape(CELL_IMAGE_MARKER)).findAll(spoken).count()
        )
        assertEquals(
            "the structure cell's payload must parse, not leak",
            RichCellFixtures.structure.copy(diagram = ""),
            cellSegments(RichCellFixtures.structureCell, withPng = false)
                .filterIsInstance<CellSegment.Chem>().single().element
        )
    }
}
