package com.mcqapp

import androidx.compose.ui.graphics.Color
import com.mcqapp.data.normalizeMathText
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CELL_FORMULA_MARKER
import com.mcqapp.util.CELL_IMAGE_STYLE
import com.mcqapp.util.CONTENT_HEIGHT_BRIDGE
import com.mcqapp.util.ContentHeightCache
import com.mcqapp.util.ContentRenderPath
import com.mcqapp.util.MATHJAX_ASSET_URL
import com.mcqapp.util.chemCellHtml
import com.mcqapp.util.contentRenderPath
import com.mcqapp.util.mixedContentHtml
import com.mcqapp.util.repairStoredMathml
import com.mcqapp.util.stripMathAttributes
import com.mcqapp.util.toCssRgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun tableCellsWithMathReachMathJaxAndTheTableBoxIsCapped() {
        val elements = listOf(
            ContentElement.TableElement(
                listOf(listOf("case", "<math><mi>y</mi></math> + <math><mn>2</mn></math>"))
            )
        )
        val html = mixedContentHtml(elements, 48f, "rgba(0,0,0,1)")
        assertTrue("prose cell: $html", html.contains("<td>case</td>"))
        // Both formulas in the one cell survive the strip, wrapped for MathJax.
        assertTrue("first formula: $html",
            html.contains("<td><math><mrow><mi>y</mi></mrow></math> + <math><mrow><mn>2</mn></mrow></math></td>"))
        assertEquals("two formulas typeset, not one", 2, Regex("<math>").findAll(html).count())
        assertTrue(html.contains(MATHJAX_ASSET_URL))
        // These rules cap the TABLE box only. The width a single very wide
        // formula can take is handled by the rich-table class (fixed layout
        // plus the SVG cap) covered below and by the 320 dp UI probe.
        assertTrue(html.contains("table{border-collapse:collapse;margin:4px 0;max-width:100%}"))
        assertTrue(html.contains("mjx-container{max-width:100%}"))
    }

    @Test
    fun tablePicturesShareTheCanonicalCellStyleAndAreCappedByCss() {
        val elements = listOf(
            ContentElement.TableElement(
                listOf(listOf("a <img src=\"data:image/png;base64,AAAA\"/> b"))
            )
        )
        val html = mixedContentHtml(elements, 48f, "rgba(0,0,0,1)")
        // The rebuilt tag carries the same style the app and the exports use.
        assertTrue(
            "picture in the cell: $html",
            html.contains("<td>a <img src=\"data:image/png;base64,AAAA\"")
        )
        assertTrue("canonical style: $html", html.contains("style=\"$CELL_IMAGE_STYLE\""))
        // The page's own rule caps the image, so a tall picture cannot stretch
        // the row to several thousand pixels. Pinned as the exact CSS rule.
        assertTrue(
            "td img cap: $html",
            html.contains("td img{max-width:100%;height:auto;max-height:240px;vertical-align:middle}")
        )
        assertTrue("cap matches the canonical style", html.contains("td img{$CELL_IMAGE_STYLE}"))
    }

    /**
     * A rich table's columns are fixed, so one unbreakable word wider than its
     * column used to paint straight over the next one (owner report: a
     * "Hexatriacontane" label at 16 px in a ~99 px three-column cell). Only a
     * rich table's cells may break inside a word; a plain table keeps the
     * browser's automatic layout and its ordinary wrapping.
     */
    @Test
    fun chemTableCellsBreakLongWordsAndPlainTablesKeepTheirWrap() {
        val chem = mixedContentHtml(
            listOf(
                ContentElement.TableElement(
                    listOf(
                        listOf("Name", "Formula", "Structure"),
                        listOf(
                            "Hexatriacontane",
                            "C36H74",
                            chemCellHtml(ContentElement.ChemElement("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"))
                        )
                    )
                )
            ),
            16f,
            "rgba(0,0,0,1)"
        )
        assertTrue("chem table class missing: $chem",
            chem.contains("<table class=\"mcq-rich-table\""))
        // The exact rule, scoped to a rich table's cells.
        assertTrue("cell word break rule missing: $chem",
            chem.contains("table.mcq-rich-table td{overflow-wrap:anywhere;word-break:break-word}"))
        // And nowhere else: an unscoped td rule would change every table.
        assertEquals("word break must be rich-scoped only", 1,
            chem.split("overflow-wrap").size - 1)
        assertTrue("generic cell rule changed: $chem", chem.contains("td{vertical-align:middle}"))

        val plain = mixedContentHtml(
            listOf(ContentElement.TableElement(listOf(listOf("Hexatriacontane", "C36H74")))),
            16f,
            "rgba(0,0,0,1)"
        )
        // No chem class anywhere on a plain page, so the scoped rule cannot
        // reach its cells; the markup and the fixed-layout rule stay off.
        assertTrue("plain table markup: $plain",
            plain.contains("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">"))
        assertEquals("plain table cell: $plain", 1,
            plain.split("<td>Hexatriacontane</td>").size - 1)
        assertFalse("plain table gained the rich class: $plain",
            plain.contains("mcq-rich-table\""))
    }

    /**
     * A formula or a portable picture is as much a rich cell as a structure:
     * the fixed-column class and the break/svg caps must reach those tables
     * too, while a plain text table keeps the automatic layout byte for byte.
     */
    @Test
    fun formulaAndPictureTablesTakeTheFixedRichLayoutAndAPlainTableDoesNot() {
        val formulaTable = ContentElement.TableElement(
            listOf(listOf("speed", "<math><mi>v</mi></math> m/s"))
        )
        val html = mixedContentHtml(listOf(formulaTable), 16f, "rgba(0,0,0,1)")
        assertTrue("formula table is rich: $html",
            html.contains("<table class=\"mcq-rich-table\""))
        assertTrue("fixed layout: $html",
            html.contains("table.mcq-rich-table{table-layout:fixed;width:100%}"))
        assertTrue("cell wrapping: $html",
            html.contains("table.mcq-rich-table td{overflow-wrap:anywhere;word-break:break-word}"))
        assertTrue("formula svg is capped to its cell: $html",
            html.contains("table.mcq-rich-table td svg{max-width:100%;height:auto}"))

        val pictureTable = ContentElement.TableElement(
            listOf(listOf("before <img src=\"data:image/png;base64,AAAA\"/> after"))
        )
        val pictureHtml = mixedContentHtml(listOf(pictureTable), 16f, "#000")
        assertTrue("picture table is rich: $pictureHtml",
            pictureHtml.contains("<table class=\"mcq-rich-table\""))

        val plain = mixedContentHtml(
            listOf(ContentElement.TableElement(listOf(listOf("a", "b")))),
            16f,
            "rgba(0,0,0,1)"
        )
        assertTrue("plain table markup: $plain",
            plain.contains("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">"))
        assertFalse("plain table keeps the auto layout: $plain",
            plain.contains("class=\"mcq-rich-table\""))
    }

    /**
     * The owner-reported bug: MathLive stores a bare `<mrow>…</mrow>` (or a
     * sibling run), so every cell surface printed the raw XML and the column
     * ballooned. The cell grammar repairs the stored fragment on read, and the
     * mixed page then typesets it like any other formula.
     */
    @Test
    fun aBareMathLiveFragmentInACellIsRepairedAndTypeset() {
        val cell = "value <mrow><msup><mi>x</mi><mn>2</mn></msup></mrow> units"
        val elements = listOf(ContentElement.TableElement(listOf(listOf(cell))))
        assertEquals(ContentRenderPath.MIXED, contentRenderPath(elements))
        val html = mixedContentHtml(elements, 16f, "rgba(0,0,0,1)")
        assertTrue("formula is a complete block: $html",
            html.contains("<td>value <math><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow></math> units</td>"))
        assertFalse("never visible tag text: $html", html.contains("&lt;mrow"))
    }

    @Test
    fun repairStoredMathmlIsConservativeAndStable() {
        val block = "<math><mi>x</mi></math>"
        assertEquals("a complete block is untouched", block, repairStoredMathml(block))
        assertEquals("prose is untouched", "plain x", repairStoredMathml("plain x"))
        val repaired = repairStoredMathml("<msup><mi>x</mi><mn>2</mn></msup>")
        assertEquals(
            "<math><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow></math>",
            repaired
        )
        // Second call sees a complete block: no double `<math>`/`mrow`.
        assertEquals(repaired, repairStoredMathml(repaired))
        // Attributes never survive the repair.
        assertEquals(
            "<math><mrow><mi>x</mi></mrow></math>",
            repairStoredMathml("<mi onclick=\"x()\" mathvariant=\"bold\">x</mi>")
        )
    }

    /**
     * A bare fragment already stored in a body/option/explanation
     * `MathElement` (not a cell) has the same bug: renderers look for
     * `<math>` and printed the raw run. The read-time repair wraps it.
     */
    @Test
    fun mixedPageRepairsALegacyBareMathElement() {
        val elements = listOf(
            ContentElement.TextElement("Solve: "),
            ContentElement.MathElement("<msup><mi>x</mi><mn>2</mn></msup>")
        )
        val html = mixedContentHtml(elements, 16f, "rgba(0,0,0,1)")
        assertTrue(
            "the stored run is a complete block: $html",
            html.contains("<math><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow></math>")
        )
        assertFalse("never raw tag text: $html", html.contains("&lt;msup"))
    }

    /**
     * The regression this PR fixes: a question whose only content is a table
     * with a formula cell used to take the native path, which can only print
     * the literal [formula] marker. The path must be chosen by cell content.
     */
    @Test
    fun aTableOnlyFormulaQuestionRendersThroughTheMixedPath() {
        val elements = listOf(
            ContentElement.TableElement(listOf(listOf("speed", "<math><mi>v</mi></math> m/s")))
        )
        assertEquals(ContentRenderPath.MIXED, contentRenderPath(elements))
        val html = mixedContentHtml(elements, 48f, "rgba(0,0,0,1)")
        assertTrue("cell math is live markup: $html", html.contains("<td><math>"))
        assertFalse("never the native marker: $html", html.contains(CELL_FORMULA_MARKER))
        assertTrue("MATHJAX", html.contains(MATHJAX_ASSET_URL))
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
        // The content bottom is the union of every rendered box, not just the
        // body's border box: on Android WebView an inline MathJax SVG leaves
        // its descent outside the text line box, and the body rect alone is a
        // couple of px short — the pinned WebView then clipped subscript
        // bottoms (e.g. C₇H₁₄O₃ in an option label).
        assertTrue(html.contains("function contentHeight()"))
        assertTrue(html.contains("getElementsByTagName('*')"))
        assertTrue(html.contains("r.bottom - b.top"))
        assertTrue(html.contains("var h = contentHeight()"))
        // Out-of-flow and invisible boxes are not part of the painted extent.
        // MathJax's clipped <mjx-assistive-mml> is absolute and can be much
        // taller than the visible SVG for a matrix, so counting it (or its
        // static descendants) pinned blank space below the formula.
        assertTrue(html.contains("MJX-ASSISTIVE-MML"))
        assertTrue(html.contains("position === 'absolute'"))
        assertTrue(html.contains("position === 'fixed'"))
        assertTrue(html.contains("visibility === 'hidden'"))
        assertTrue(html.contains("display === 'none'"))
        // The tag/style test must run only for a box that would raise the max:
        // a style read per descendant is the cost this guard removes.
        assertTrue(html.contains("if (d <= bottom) continue"))
        assertTrue(
            html.indexOf("if (d <= bottom) continue") <
                html.indexOf("if (outOfFlow(el, body)) continue")
        )
        // Still not scrollHeight: scrollHeight is at least the viewport height,
        // and the viewport is the pinned height, so a block pinned too tall
        // could never shrink and would leave blank space below the question.
        assertTrue(!html.contains("document.body.getBoundingClientRect().height"))
        assertTrue(!html.contains("body.scrollHeight"))
        // The body keeps a descender allowance below its last baseline;
        // getBoundingClientRect includes padding, so the reported height grows.
        assertTrue(html.contains("body{padding:0 0 0.25em}"))
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
