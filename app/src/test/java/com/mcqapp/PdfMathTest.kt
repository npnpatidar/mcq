package com.mcqapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.UnderlineSpan
import com.mcqapp.data.export.MathSpan
import com.mcqapp.data.export.TexNode
import com.mcqapp.data.export.columnEdges
import com.mcqapp.data.export.drawNodes
import com.mcqapp.data.export.mathMlToNodes
import com.mcqapp.data.export.mathToLinear
import com.mcqapp.data.export.measureNodes
import com.mcqapp.data.export.spannedFromInlineHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfMathTest {

    private fun paint(size: Float = 30f) = TextPaint().apply {
        textSize = size
        color = Color.BLACK
        isAntiAlias = true
    }

    @Test
    fun parsesSuperscriptSum() {
        val nodes = mathMlToNodes(
            "<math><mrow><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>2</mn></mrow></math>"
        )
        assertEquals(
            listOf(
                TexNode.Sup(listOf(TexNode.Run("x")), listOf(TexNode.Run("2"))),
                TexNode.Run("+"),
                TexNode.Run("2")
            ),
            nodes
        )
    }

    @Test
    fun parsesNestedSqrt() {
        val nodes = mathMlToNodes(
            "<math><msqrt><mrow><msup><mi>a</mi><mn>2</mn></msup><mo>+</mo>" +
                "<msup><mi>b</mi><mn>2</mn></msup></mrow></msqrt></math>"
        )
        assertNotNull(nodes)
        val sqrt = nodes!!.single() as TexNode.Sqrt
        assertTrue(sqrt.body.any { it is TexNode.Sup })
    }

    @Test
    fun parsesSemanticsSkippingAnnotation() {
        val nodes = mathMlToNodes(
            "<math display=\"inline\" xmlns=\"http://www.w3.org/1998/Math/MathML\">" +
                "<semantics><mrow><mi>x</mi></mrow>" +
                "<annotation encoding=\"application/x-tex\">x</annotation></semantics></math>"
        )
        assertEquals(listOf(TexNode.Run("x")), nodes)
    }

    @Test
    fun rejectsUnknownAndMalformed() {
        assertNull(mathMlToNodes("<math><mtable><mtr><mtd>x</mtd></mtr></mtable></math>"))
        assertNull(mathMlToNodes("<math><mfrac><mi>a</mi></mfrac></math>"))
        assertNull(mathMlToNodes("<math><msup><mi>x</mi></math>"))
        assertNull(mathMlToNodes("<math></math>"))
        assertNull(mathMlToNodes("plain text"))
    }

    @Test
    fun fractionIsWiderThanItsParts() {
        val p = paint()
        val num = measureNodes(listOf(TexNode.Run("12")), p)
        val frac = measureNodes(
            listOf(TexNode.Frac(listOf(TexNode.Run("12")), listOf(TexNode.Run("3456")))),
            p
        )
        assertTrue(frac.width >= num.width)
        assertTrue(frac.ascent + frac.descent > num.ascent + num.descent)
    }

    @Test
    fun scriptAddsWidth() {
        val p = paint()
        val base = measureNodes(listOf(TexNode.Run("x")), p)
        val sup = measureNodes(
            listOf(TexNode.Sup(listOf(TexNode.Run("x")), listOf(TexNode.Run("2")))),
            p
        )
        assertTrue(sup.width > base.width)
        assertTrue(sup.ascent >= base.ascent)
    }

    @Test
    fun drawPutsPixelsOnCanvas() {
        // Canvas rasterization is stubbed under Robolectric, so this only
        // exercises the draw paths without crashing.
        val nodes = mathMlToNodes(
            "<math><mrow><mfrac><mi>a</mi><mi>b</mi></mfrac><mo>+</mo>" +
                "<msqrt><mi>x</mi></msqrt></mrow></math>"
        )!!
        val bitmap = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)
        drawNodes(Canvas(bitmap), nodes, 10f, 100f, paint(40f))
    }

    @Test
    fun drawReturnsMeasuredWidth() {
        val p = paint(40f)
        val cases = listOf(
            listOf(TexNode.Run("hello")),
            listOf(TexNode.Sup(listOf(TexNode.Run("x")), listOf(TexNode.Run("2")))),
            listOf(TexNode.Sub(listOf(TexNode.Run("H")), listOf(TexNode.Run("2")))),
            listOf(TexNode.Frac(listOf(TexNode.Run("12")), listOf(TexNode.Run("3456")))),
            listOf(TexNode.Sqrt(listOf(TexNode.Run("x"))))
        )
        val bitmap = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (nodes in cases) {
            val expected = measureNodes(nodes, p).width
            assertEquals(expected, drawNodes(canvas, nodes, 10f, 100f, p), 0.001f)
        }
    }

    @Test
    fun spanMeasuresPositiveWidth() {
        val nodes = mathMlToNodes("<math><mi>x</mi></math>")!!
        val span = MathSpan(nodes, 30f)
        val p = paint(30f)
        val w = span.getSize(p, "\uFFFC", 0, 1, null)
        assertTrue(w > 0)
    }

    @Test
    fun fractionMeasureContainsChildren() {
        // Regression test for numerators overflowing into the line above:
        // the measured box must contain both children plus the rule gap.
        val p = paint(40f)
        val num = listOf(TexNode.Run("x+y"))
        val den = listOf(TexNode.Run("a-b-c"))
        val m = measureNodes(listOf(TexNode.Frac(num, den)), p)
        val n = measureNodes(num, p)
        val d = measureNodes(den, p)
        val gap = p.textSize * 0.12f
        assertTrue(m.ascent >= n.ascent + n.descent + gap - 0.001f)
        assertTrue(m.descent >= d.ascent + d.descent + gap - 0.001f)
        assertTrue(m.width >= n.width && m.width >= d.width)
    }

    @Test
    fun columnEdgesChainFromOrigin() {
        // Recomputing after a column advance must move the whole grid:
        // stale edges draw new rows on top of old ones.
        val left = columnEdges(40f, floatArrayOf(100f, 150f))
        assertEquals(listOf(40f, 140f, 290f), left.toList())
        val right = columnEdges(309.5f, floatArrayOf(100f, 150f))
        assertEquals(listOf(309.5f, 409.5f, 559.5f), right.toList())
    }

    @Test
    fun linearFallbackKeepsTexAnnotation() {
        assertEquals(
            "x^{2}",
            mathToLinear(
                "<math><semantics><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow>" +
                    "<annotation encoding=\"application/x-tex\">x^{2}</annotation></semantics></math>"
            )
        )
    }

    @Test
    fun spannedBoldAndUnderline() {
        val spanned = spannedFromInlineHtml("a<b>x</b>c<u>y</u>")
        assertEquals("axcy", spanned.toString())
        val bold = spanned.getSpans(0, 4, StyleSpan::class.java)
        assertEquals(1, bold.size)
        assertEquals(Typeface.BOLD, bold[0].style)
        assertEquals(1, spanned.getSpanStart(bold[0]))
        assertEquals(2, spanned.getSpanEnd(bold[0]))
        assertEquals(1, spanned.getSpans(0, 4, UnderlineSpan::class.java).size)
    }

    @Test
    fun spannedChemistrySubscript() {
        val spanned = spannedFromInlineHtml("H<sub>2</sub>O")
        assertEquals("H2O", spanned.toString())
        assertEquals(1, spanned.getSpans(0, 3, SubscriptSpan::class.java).size)
        assertEquals(1, spanned.getSpans(0, 3, RelativeSizeSpan::class.java).size)
    }

    @Test
    fun spannedMarkHighlights() {
        val spanned = spannedFromInlineHtml("<mark>x</mark>")
        assertEquals("x", spanned.toString())
        assertEquals(1, spanned.getSpans(0, 1, BackgroundColorSpan::class.java).size)
    }

    @Test
    fun spannedBreaksAndBareSymbols() {
        val spanned = spannedFromInlineHtml("a<br/>b &amp; 5 < 6")
        assertEquals("a\nb & 5 < 6", spanned.toString())
    }
}
