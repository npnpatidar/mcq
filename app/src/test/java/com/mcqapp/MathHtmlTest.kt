package com.mcqapp

import androidx.compose.ui.graphics.Color
import com.mcqapp.data.normalizeMathText
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CONTENT_HEIGHT_BRIDGE
import com.mcqapp.util.ContentHeightCache
import com.mcqapp.util.MATHJAX_ASSET_URL
import com.mcqapp.util.MATH_FONT_ASSET_URL
import com.mcqapp.util.isMathFontRequest
import com.mcqapp.util.mixedContentHtml
import com.mcqapp.util.stripMathAttributes
import com.mcqapp.util.toCssRgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MathHtmlTest {

    private fun sampleElements() = listOf(
        ContentElement.TextElement("हल करें: "),
        ContentElement.MathElement("<math><mi>x</mi></math>"),
        ContentElement.TextElement(" जारी"),
        ContentElement.TableElement(listOf(listOf("a<b", "c&d"))),
        ContentElement.ImageElement("https://example.com/f.png")
    )

    @Test
    fun mixedPageFlowsTextAndFormulaInline() {
        val html = mixedContentHtml(sampleElements(), 48f, "rgba(0,0,0,1)")
        // One body, no per-element block wrappers: the browser lays text
        // and the formula out in a single sentence.
        assertTrue(html.contains("हल करें: <math><mrow><mi>x</mi></mrow></math> जारी"))
        assertTrue(html.contains(MATHJAX_ASSET_URL))
        assertTrue(html.contains("font-size:48.0px"))
        assertTrue(html.contains("color:rgba(0,0,0,1)"))
        assertTrue(html.contains("background:transparent"))
    }

    @Test
    fun mixedPageSerializesTablesAndImages() {
        val html = mixedContentHtml(sampleElements(), 48f, "rgba(0,0,0,1)")
        assertTrue(html.contains("<td>a&lt;b</td>"))
        assertTrue(html.contains("<td>c&amp;d</td>"))
        assertTrue(html.contains("<img src=\"https://example.com/f.png\""))
    }

    /**
     * Current WebView lays MathML out natively. That is what makes a
     * maths-heavy list scroll: no 2 MB script to parse per block, no typeset
     * pass, and a height that is final on the first layout. The bundle is
     * still shipped and loaded by WebViews that predate MathML Core.
     */
    @Test
    fun formulasUseNativeMathmlUnlessTheWebViewIsOlder() {
        val html = mixedContentHtml(sampleElements(), 48f, "rgba(0,0,0,1)")
        assertTrue(html.contains("typeof MathMLElement === 'undefined'"))
        assertTrue(html.contains("document.write("))
        assertTrue(html.contains(MATHJAX_ASSET_URL))
    }

    /**
     * Native MathML needs a font with a MATH table for stretchy brackets,
     * radicals and italic variables. Android ships none, so the page must
     * load the bundled one and must not report a height measured before it
     * arrives.
     */
    @Test
    fun nativeMathUsesTheBundledMathFont() {
        val html = mixedContentHtml(sampleElements(), 48f, "rgba(0,0,0,1)")
        assertTrue(html.contains(MATH_FONT_ASSET_URL))
        assertTrue(html.contains("@font-face"))
        assertTrue(html.contains("math{font-family:'MCQMath'}"))
        // Served from the asset origin, not file: — a WebView refuses
        // CORS-enabled subresources such as fonts from file://.
        assertTrue(html.contains("font-src https://appassets.androidplatform.net"))
        assertTrue(html.contains("document.fonts.ready"))
    }

    @Test
    fun onlyTheBundledMathFontIsServedFromTheAssetOrigin() {
        assertTrue(isMathFontRequest(MATH_FONT_ASSET_URL))
        // Anything else must fall through to the WebView's own handling,
        // or the interceptor would be a general asset reader.
        assertTrue(!isMathFontRequest("https://appassets.androidplatform.net/assets/mathjax/tex-mml-svg.js"))
        assertTrue(!isMathFontRequest("https://appassets.androidplatform.net/assets/fonts/other.otf"))
        assertTrue(!isMathFontRequest("file:///data/data/com.mcqapp/files/secret"))
    }

    @Test
    fun bundledMathFontIsPresentAndHasAMathTable() {
        val asset = File("src/main/assets/fonts/latinmodern-math.otf")
        assertTrue("missing: ${asset.absolutePath}", asset.isFile)
        val bytes = asset.readBytes()
        assertTrue("unexpectedly small: ${bytes.size}", bytes.size > 500_000)
        // sfnt header: numTables at 4..6, then one 16-byte directory entry
        // per table. Without a MATH table the browser cannot lay maths out.
        val tables = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        val tags = (0 until tables).map { String(bytes, 12 + it * 16, 4, Charsets.US_ASCII) }
        assertTrue("tables: $tags", "MATH" in tags)
    }

    @Test
    fun mixedPageNeedsNoNetwork() {
        val html = mixedContentHtml(sampleElements(), 48f, "rgba(0,0,0,1)")
        // Any remote script would reintroduce the offline failure.
        assertTrue(html.lines().none { it.contains("<script src=\"http") })
    }

    /**
     * The page reports the height it settles at, not the height it has while
     * MathJax is still replacing the MathML with SVG. A wrap-content WebView
     * otherwise measures twice when a question appears, which is the "table
     * and formula being built on screen" jump.
     */
    @Test
    fun mixedPageReportsItsSettledHeight() {
        val html = mixedContentHtml(sampleElements(), 48f, "rgba(0,0,0,1)")
        assertTrue(html.contains(CONTENT_HEIGHT_BRIDGE))
        // Waiting on MathJax's own promise is what makes the report final:
        // the raw MathML and the typeset SVG do not have the same height.
        assertTrue(html.contains("startup.promise"))
        assertTrue(html.contains("typesetPromise"))
        // The body's border box, not scrollHeight: scrollHeight is at least
        // the viewport height, and the viewport is the pinned height, so a
        // block pinned too tall could never shrink and would leave blank
        // space below the question.
        assertTrue(html.contains("body.getBoundingClientRect().height"))
        assertTrue(!html.contains("body.scrollHeight"))
        // Nothing may be reported before the typeset pass settles.
        assertTrue(html.contains("if (!settled) return"))
        // A width change (rotation, font scale) reflows the page.
        assertTrue(html.contains("addEventListener('resize'"))
    }

    @Test
    fun settledHeightsAreCachedPerPage() {
        val html = mixedContentHtml(sampleElements(), 48f, "rgba(0,0,0,1)")
        ContentHeightCache.put(html, 321)
        assertEquals(321, ContentHeightCache.get(html))
        // Bounded: a long browsing session must not retain one HTML document
        // per question.
        for (i in 1..100) ContentHeightCache.put("page-$i", i)
        assertNull(ContentHeightCache.get(html))
        assertEquals(100, ContentHeightCache.get("page-100"))
    }

    @Test
    fun cssColorFormatsRgba() {
        assertEquals("rgba(0,0,0,1)", Color(0f, 0f, 0f, 1f).toCssRgba())
        assertEquals("rgba(255,255,255,1)", Color.White.toCssRgba())
    }

    @Test
    fun mathAttributesAreStrippedForMathJax() {
        val mathml = "<math display=\"inline\" xmlns=\"http://www.w3.org/1998/Math/MathML\"><mi>x</mi></math>"
        assertEquals("<math><mrow><mi>x</mi></mrow></math>", stripMathAttributes(mathml))
    }

    @Test
    fun mathWithSurroundingWhitespaceDoesNotNest() {
        val mathml = "\n<math display=\"inline\"><mrow><mi>x</mi></mrow></math>\n"
        assertEquals("<math><mrow><mi>x</mi></mrow></math>", stripMathAttributes(mathml))
    }

    @Test
    fun semanticsAndAnnotationAreStripped() {
        val mathml = "<math display=\"inline\" xmlns=\"http://www.w3.org/1998/Math/MathML\">" +
            "<semantics><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow>" +
            "<annotation encoding=\"application/x-tex\">x^{2}</annotation></semantics></math>"
        assertEquals("<math><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow></math>", stripMathAttributes(mathml))
    }

    @Test
    fun mathWithMrowIsNotDoubleWrapped() {
        val mathml = "<math><mrow><mi>x</mi></mrow></math>"
        assertEquals("<math><mrow><mi>x</mi></mrow></math>", stripMathAttributes(mathml))
    }

    @Test
    fun bareTextInMathRunBecomesTokens() {
        // Compact MathML from the DOCX converter: MathJax rejects
        // bare text nodes with "Unexpected text node" (shown as
        // "Math input error").
        val stored = "<math><msup><mrow>x</mrow><mrow>2</mrow></msup>+2x+1=0</math>"
        assertEquals(
            "<math><msup><mrow><mi>x</mi></mrow><mrow><mn>2</mn></mrow></msup>" +
                "<mo>+</mo><mn>2</mn><mi>x</mi><mo>+</mo><mn>1</mn><mo>=</mo><mn>0</mn></math>",
            normalizeMathText(stored)
        )
    }

    @Test
    fun strippedMathIsTokenizedForPreview() {
        val mathml = "\n<math display=\"inline\"><msup><mrow>x</mrow><mrow>2</mrow></msup>+2x+1=0</math>\n"
        assertEquals(
            "<math><mrow><msup><mrow><mi>x</mi></mrow><mrow><mn>2</mn></mrow></msup>" +
                "<mo>+</mo><mn>2</mn><mi>x</mi><mo>+</mo><mn>1</mn><mo>=</mo><mn>0</mn></mrow></math>",
            stripMathAttributes(mathml)
        )
    }

    /**
     * F4: MathML from an imported bank must not carry event handlers into a
     * JS-enabled WebView. Inner tags are reduced to bare, attribute-free,
     * allow-listed tags; unknown tags are escaped as text.
     */
    @Test
    fun mathmlEventHandlersAreStripped() {
        val hostile = "<math><mi onclick=\"alert(1)\">x</mi><mo onerror=\"alert(2)\">+</mo></math>"
        assertEquals(
            "<math><mrow><mi>x</mi><mo>+</mo></mrow></math>",
            stripMathAttributes(hostile)
        )
    }

    @Test
    fun mathmlUnknownTagsAreEscaped() {
        val hostile = "<math><script>alert(1)</script><mi>x</mi></math>"
        // Escaped attacker text is kept inert, without re-tokenization; the
        // original <math> root survives since it is allow-listed.
        assertEquals(
            "<math>&lt;script&gt;alert(1)&lt;/script&gt;<mi>x</mi></math>",
            stripMathAttributes(hostile)
        )
    }

    @Test
    fun mathmlAttributesAreStrippedFromEveryTag() {
        val hostile = "<math display=\"inline\" xmlns=\"http://www.w3.org/1998/Math/MathML\">" +
            "<mrow style=\"color:red\"><mi id=\"evil\">x</mi></mrow></math>"
        assertEquals(
            "<math><mrow><mi>x</mi></mrow></math>",
            stripMathAttributes(hostile)
        )
    }

    @Test
    fun mathmlTextBetweenTagsIsEscaped() {
        val hostile = "<math><mi>x</mi><mtext><b>bold</b></mtext></math>"
        assertEquals(
            "<math><mi>x</mi><mtext>&lt;b&gt;bold&lt;/b&gt;</mtext></math>",
            stripMathAttributes(hostile)
        )
    }

    @Test
    fun mathmlWithoutTagsIsEscaped() {
        // No <math> block: the raw input is emitted escaped as visible text,
        // never wrapped as live markup — the fallback path used to return it
        // unescaped.
        assertEquals("&lt;script&gt;", stripMathAttributes("<script>"))
    }

    @Test
    fun mathmlEntitiesSurviveSanitizing() {
        // Character references are operator content in exported MathML; they
        // must not be double-escaped into literal "&lt;" on screen.
        assertEquals(
            "<math><mrow><mi>a</mi><mo>&lt;</mo><mi>b</mi></mrow></math>",
            stripMathAttributes("<math><mi>a</mi><mo>&lt;</mo><mi>b</mi></math>")
        )
    }

    @Test
    fun normalizeMathTextIsIdempotent() {
        val strict = "<math><mrow><mfrac><mi>a</mi><mi>b</mi></mfrac>" +
            "<mo>+</mo><msqrt><mi>x</mi></msqrt></mrow></math>"
        assertEquals(strict, normalizeMathText(strict))
        assertEquals(strict, normalizeMathText(normalizeMathText(strict)))
    }

    @Test
    fun normalizeMathTextKeepsAnnotationsAndEscapesMarkup() {
        val mathml = "<math><semantics><mrow><mi>a</mi><mo>&lt;</mo><mi>b</mi></mrow>" +
            "<annotation encoding=\"application/x-tex\">a &lt; b</annotation></semantics></math>"
        // No stray bare text anywhere: annotation bodies stay verbatim.
        assertEquals(mathml, normalizeMathText(mathml))
        val escaped = normalizeMathText("<math><mrow>a<b</mrow></math>")
        assertEquals("<math><mrow><mi>a</mi><mo>&lt;</mo><mi>b</mi></mrow></math>", escaped)
    }

    @Test
    fun normalizeMathTextHandlesPlainTextAndComments() {
        assertEquals("<mi>x</mi><mo>=</mo><mn>1</mn>", normalizeMathText("x=1"))
        // Blank stays blank: there is nothing to render.
        assertTrue(normalizeMathText("   ").isBlank())
        // Comment text must never become operators.
        assertEquals(
            "<math><mrow><mi>x</mi></mrow></math>",
            normalizeMathText("<math><!-- 2x + 1 --><mrow><mi>x</mi></mrow></math>")
        )
    }

    @Test
    fun bundledMathJaxAssetIsPresent() {
        // Unit tests run with the app module as working directory.
        val asset = File("src/main/assets/mathjax/tex-mml-svg.js")
        assertTrue("missing: ${asset.absolutePath}", asset.isFile)
        val content = asset.readText()
        assertTrue(content.length > 1_000_000)
        assertTrue(content.contains("output/svg"))
        assertTrue(content.contains("input/mml"))
    }
}
