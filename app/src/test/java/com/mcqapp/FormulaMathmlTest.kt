package com.mcqapp

import com.mcqapp.ui.editor.MAX_FORMULA_MATHML_CHARS
import com.mcqapp.ui.editor.cellFormulaCommit
import com.mcqapp.ui.editor.normalizeFormulaMathml
import com.mcqapp.util.CellEditPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one commit-time normaliser for MathLive output.
 *
 * `mf.getValue('math-ml')` is an atom run (a bare `<msup>…</msup>` or sibling
 * elements), not a document. Committing it verbatim stored raw XML and every
 * surface printed it. [normalizeFormulaMathml] is what `updateMathBody/
 * Explanation/Option` and the cell formula dialog run before state.
 */
class FormulaMathmlTest {

    private val bareMrow =
        "<mrow><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></mrow>"
    private val canonicalMrow =
        "<math><mrow><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></mrow></math>"

    @Test
    fun aBareMrowFragmentGetsTheMathRoot() {
        assertEquals(canonicalMrow, normalizeFormulaMathml(bareMrow))
    }

    @Test
    fun siblingElementsAreWrappedInOneMrow() {
        assertEquals(
            "<math><mrow><mi>x</mi><mo>+</mo><mn>1</mn></mrow></math>",
            normalizeFormulaMathml("<mi>x</mi><mo>+</mo><mn>1</mn>")
        )
        // A lone token element is a valid atom run too.
        assertEquals(
            "<math><mrow><mi>x</mi></mrow></math>",
            normalizeFormulaMathml("<mi>x</mi>")
        )
    }

    @Test
    fun aFullBlockIsCanonicalisedThroughTheExistingStrip() {
        assertEquals(
            "<math><mrow><mi>x</mi></mrow></math>",
            normalizeFormulaMathml("<math display=\"block\" xmlns=\"http://www.w3.org/1998/Math/MathML\"><mi>x</mi></math>")
        )
    }

    @Test
    fun attributesOnABareRunAreDropped() {
        assertEquals(
            "<math><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow></math>",
            normalizeFormulaMathml(
                "<msup class=\"ML__mathstyle\" mathbackground=\"#fff\">" +
                    "<mi mathvariant=\"bold\" onclick=\"x()\">x</mi><mn>2</mn></msup>"
            )
        )
    }

    @Test
    fun aHostileTagNeverSurvivesAsMarkup() {
        val out = normalizeFormulaMathml("<script>alert(1)</script>")
        assertFalse("no live script: $out", out.contains("<script"))
        assertTrue("inert escaped text: $out", out.contains("&lt;script&gt;"))
        val insideBlock = normalizeFormulaMathml(
            "<math><mrow><mi>x</mi><script>alert(1)</script></mrow></math>"
        )
        assertFalse("no live script inside a block: $insideBlock", insideBlock.contains("<script"))
    }

    @Test
    fun emptyAndBlankValuesStayEmpty() {
        assertEquals("", normalizeFormulaMathml(""))
        assertEquals("", normalizeFormulaMathml("   \n "))
    }

    @Test
    fun anOversizedValueIsRejectedRatherThanStored() {
        val huge = "<mi>" + "x".repeat(MAX_FORMULA_MATHML_CHARS) + "</mi>"
        assertEquals("", normalizeFormulaMathml(huge))
    }

    @Test
    fun anEscapedFragmentStaysVisibleText() {
        val escaped = "&lt;mrow&gt;&lt;mi&gt;x&lt;/mi&gt;&lt;/mrow&gt;"
        assertEquals(escaped, normalizeFormulaMathml(escaped))
    }

    @Test
    fun anEscapedComparisonOperatorIsWrappedLikeAnyAtom() {
        // `x < 2` arrives from MathLive with the operator entity-escaped; it
        // must still become a complete block.
        assertEquals(
            "<math><mrow><mi>x</mi><mo>&lt;</mo><mn>2</mn></mrow></math>",
            normalizeFormulaMathml("<mi>x</mi><mo>&lt;</mo><mn>2</mn>")
        )
    }

    @Test
    fun theCellFormulaDialogCommitStoresACompleteBlock() {
        var capturedBlock: String? = null
        var capturedRow = -1
        var capturedCol = -1
        var capturedPart: Int? = null
        var capturedPartValue: CellEditPart? = null
        cellFormulaCommit(
            "table-1", row = 2, col = 1, partIndex = 0,
            mathml = "<msup><mi>x</mi><mn>2</mn></msup>",
            onSetCellPart = { blockId, row, col, partIndex, part ->
                capturedBlock = blockId
                capturedRow = row
                capturedCol = col
                capturedPart = partIndex
                capturedPartValue = part
            }
        )
        assertEquals("table-1", capturedBlock)
        assertEquals(2, capturedRow)
        assertEquals(1, capturedCol)
        assertEquals(0, capturedPart)
        assertEquals(
            CellEditPart.Formula("<math><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow></math>"),
            capturedPartValue
        )
    }

    @Test
    fun aSelfClosingMathSpaceKeepsItsSiblings() {
        assertEquals(
            "<math><mrow><mi>a</mi><mspace/><mi>b</mi></mrow></math>",
            normalizeFormulaMathml("<mi>a</mi><mspace width=\"0.5em\"/><mi>b</mi>")
        )
        // MathLive/MathML also accepts whitespace before the closing `>`.
        assertEquals(
            "<math><mrow><mi>a</mi><mspace/><mi>b</mi></mrow></math>",
            normalizeFormulaMathml("<mi>a</mi><mspace/ ><mi>b</mi>")
        )
        // The same element inside a full block the editor already has.
        assertEquals(
            "<math><mrow><mi>a</mi><mspace/><mi>b</mi></mrow></math>",
            normalizeFormulaMathml(
                "<math><mrow><mi>a</mi><mspace width=\"0.5em\"/><mi>b</mi></mrow></math>"
            )
        )
    }

    @Test
    fun aFallbackValueCannotBecomeLiveMarkup() {
        // Not a complete run, so it is stored as inert text: even an
        // allow-listed tag shape must not survive as markup.
        val out = normalizeFormulaMathml("<mi>x</mi> prose")
        assertEquals("&lt;mi&gt;x&lt;/mi&gt; prose", out)
        assertFalse("no live tag: $out", out.contains("<mi"))
        assertFalse("no live tag: $out", out.contains("</mi"))
    }

    @Test
    fun anEscapedTagInTextStillStaysText() {
        val out = normalizeFormulaMathml("<mtext>&lt;mrow&gt;</mtext>")
        assertFalse("an escaped tag is not a formula: $out", out.contains("<math>"))
        assertTrue("it stays literal: $out", out.contains("&lt;mrow&gt;"))
    }

    @Test
    fun theNormaliserIsIdempotentForEveryAcceptedShape() {
        val inputs = listOf(
            bareMrow,
            "<mi>x</mi><mo>+</mo><mn>1</mn>",
            "<mi>x</mi>",
            "<math display=\"block\"><mi>x</mi></math>",
            "<msup class=\"a\"><mi>x</mi><mn>2</mn></msup>",
            "<mrow>x</mrow>",
            "<mi>a</mi><mspace width=\"0.5em\"/><mi>b</mi>",
            "<mtext>a &lt; b</mtext>",
            "<script>alert(1)</script>",
            "<mi>x</mi> prose",
            "&lt;mrow&gt;x&lt;/mrow&gt;",
            "   ",
            ""
        )
        for (input in inputs) {
            val once = normalizeFormulaMathml(input)
            assertEquals("idempotent for: $input", once, normalizeFormulaMathml(once))
        }
    }
}
