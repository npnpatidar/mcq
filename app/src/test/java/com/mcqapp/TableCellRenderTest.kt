package com.mcqapp

import com.mcqapp.data.sanitizeMathml
import com.mcqapp.domain.CellPart
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.tableCellParts
import com.mcqapp.util.CELL_FORMULA_MARKER
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.ContentRenderPath
import com.mcqapp.util.contentRenderPath
import com.mcqapp.util.sanitizeTableCell
import com.mcqapp.util.stripMathAttributes
import com.mcqapp.util.tableCellHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table cells carry inline markup on purpose (consolidated_bugs.md G1-G3):
 * the text surfaces collapse the two cases no text pipeline can show — a
 * picture (a data-URI <img>) and a formula (raw <math>) — to visible
 * markers, and the HTML surfaces render the rest through the same inline
 * pipeline as a TextElement.
 */
class TableCellRenderTest {

    @Test
    fun `a data-uri picture collapses to the image marker`() {
        val cell = "Before <img src=\"data:image/png;base64,AAAA\"/> after"
        assertEquals("Before $CELL_IMAGE_MARKER after", sanitizeTableCell(cell))
    }

    @Test
    fun `a formula collapses to the formula marker on text surfaces`() {
        val cell = "x <math><mi>y</mi></math> z"
        assertEquals("x $CELL_FORMULA_MARKER z", sanitizeTableCell(cell))
    }

    @Test
    fun `plain inline markup survives the text sanitizer`() {
        val cell = "<b>bold</b> & <sub>2</sub>"
        assertEquals(cell, sanitizeTableCell(cell))
    }

    @Test
    fun `the html cell renders inline markup and keeps math for MathJax`() {
        val html = tableCellHtml("a <b>b</b> <math><mi>m</mi></math> c") { sanitizeMathml(it) }
        assertTrue("inline markup renders: $html", html.contains("<b>b</b>"))
        assertTrue("math survives for the typesetter: $html", html.contains("<math>"))
        assertTrue(html.startsWith("a "))
        assertTrue(html.endsWith(" c"))
    }

    @Test
    fun `newlines become breaks like a text element`() {
        val html = tableCellHtml("one\ntwo") { sanitizeMathml(it) }
        assertEquals("one<br/>two", html)
    }

    @Test
    fun `an unknown tag stays visible as escaped text like the body pipeline`() {
        val html = tableCellHtml("Pick <one> & all") { sanitizeMathml(it) }
        // renderInlineHtml escapes unknown tags: they render as visible text
        // ("<one>") in the page without becoming live markup — the
        // escaped form is what the string holds.
        assertTrue("unknown tag stays visible: $html", html.contains("&lt;one&gt;"))
        assertTrue(html.contains("&amp; all"))  // the escaped ampersand
    }

    // ---- the one shared cell grammar ---------------------------------------

    @Test
    fun `tableCellParts splits prose and math in order`() {
        val parts = tableCellParts("a <b>b</b> <math><mi>m</mi></math> c")
        assertEquals(3, parts.size)
        assertEquals(CellPart.Text("a <b>b</b> "), parts[0])
        assertEquals(CellPart.Math("<math><mi>m</mi></math>"), parts[1])
        assertEquals(CellPart.Text(" c"), parts[2])
    }

    @Test
    fun `tableCellParts keeps more than one formula in one cell`() {
        val parts = tableCellParts("<math><mi>a</mi></math> and <math><mi>b</mi></math>")
        assertEquals(3, parts.size)
        assertEquals(CellPart.Math("<math><mi>a</mi></math>"), parts[0])
        // The prose between the two blocks is its own part, never swallowed.
        assertEquals(CellPart.Text(" and "), parts[1])
        assertEquals(CellPart.Math("<math><mi>b</mi></math>"), parts[2])
    }

    @Test
    fun `an unclosed math tag stays prose`() {
        val parts = tableCellParts("Solve <math><mi>x</mi> for x")
        assertEquals(listOf(CellPart.Text("Solve <math><mi>x</mi> for x")), parts)
        // The plain text surface keeps the markup visible instead of a marker.
        assertEquals("Solve <math><mi>x</mi> for x", sanitizeTableCell("Solve <math><mi>x</mi> for x"))
    }

    @Test
    fun `a picture stays a text part and the view layer replaces it with the marker`() {
        val cell = "Before <img src=\"data:image/png;base64,AAAA\"/> after"
        val parts = tableCellParts(cell)
        assertEquals(1, parts.size)
        assertTrue("a picture is not a formula: ${parts.single()}", parts.single() is CellPart.Text)
        assertTrue((parts.single() as CellPart.Text).html.contains("<img"))
        assertEquals("Before $CELL_IMAGE_MARKER after", sanitizeTableCell(cell))
    }

    @Test
    fun `a formula and a picture in one cell keep their order and both markers`() {
        val cell = "a <math><mi>x</mi></math> <img src=\"data:image/png;base64,AAAA\"/> b"
        // Text / Math / Text, in the stored order: the picture is prose after
        // the formula, never folded into it.
        assertEquals(
            listOf(
                CellPart.Text("a "),
                CellPart.Math("<math><mi>x</mi></math>"),
                CellPart.Text(" <img src=\"data:image/png;base64,AAAA\"/> b")
            ),
            tableCellParts(cell)
        )
        // Text surfaces: formula marker, then image marker — no base64.
        assertEquals("a $CELL_FORMULA_MARKER $CELL_IMAGE_MARKER b", sanitizeTableCell(cell))
        // HTML surfaces: the formula stays live markup, the picture collapses
        // to the marker, and the data URI never prints as text.
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        assertEquals("a <math><mi>x</mi></math> $CELL_IMAGE_MARKER b", html)
        assertFalse("raw base64 must not reach the page: $html", html.contains("base64,"))
    }

    @Test
    fun `uppercase math with attributes is a formula and survives sanitizing`() {
        val cell = "<MATH display=\"block\"><MI>X</MI></MATH>"
        // Classification is case-insensitive, so this table takes the mixed
        // path instead of printing a literal formula marker.
        assertEquals(
            ContentRenderPath.MIXED,
            contentRenderPath(listOf(ContentElement.TableElement(listOf(listOf(cell)))))
        )
        assertEquals(listOf(CellPart.Math(cell)), tableCellParts(cell))
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        assertTrue("tags are canonicalised, not escaped: $html", html.contains("<math><mi>X</mi></math>"))
        assertFalse("never visible tag text: $html", html.contains("&lt;"))
    }

    @Test
    fun `an unclosed uppercase math tag stays prose and keeps the native path`() {
        val cell = "<MATH display=\"block\"><MI>X</MI>"
        assertEquals(listOf(CellPart.Text(cell)), tableCellParts(cell))
        assertEquals(cell, sanitizeTableCell(cell))
        // No complete formula anywhere in the table: nothing needs MathJax.
        assertEquals(
            ContentRenderPath.NATIVE,
            contentRenderPath(listOf(ContentElement.TableElement(listOf(listOf(cell)))))
        )
    }

    @Test
    fun `a picture inside a formula becomes the marker instead of base64`() {
        val cell = "<math><mi>x</mi><img src=\"data:image/png;base64,AAAA\"/></math>"
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        // The MathML sanitiser would escape an unknown <img> as visible text,
        // printing the data URI; the cell marks it first.
        assertTrue("picture is a marker: $html", html.contains(CELL_IMAGE_MARKER))
        assertFalse("never the raw data URI: $html", html.contains("base64,"))
        // The mixed view's own mathHtml strips and tokenizes the formula; the
        // data URI must not survive that pass either.
        val stripped = tableCellHtml(cell) { stripMathAttributes(it) }
        assertTrue("picture is a marker after the strip: $stripped",
            stripped.contains(CELL_IMAGE_MARKER))
        assertFalse("never the raw data URI: $stripped", stripped.contains("base64,"))
        assertFalse("no live image tag: $stripped", stripped.contains("<img"))
    }

    @Test
    fun `a literal marker or escaped math is never treated as a formula`() {
        assertEquals(listOf(CellPart.Text(CELL_FORMULA_MARKER)), tableCellParts(CELL_FORMULA_MARKER))
        assertEquals(CELL_FORMULA_MARKER, sanitizeTableCell(CELL_FORMULA_MARKER))
        val escaped = "&lt;math&gt;x&lt;/math&gt;"
        assertEquals(listOf(CellPart.Text(escaped)), tableCellParts(escaped))
        assertEquals(escaped, sanitizeTableCell(escaped))

        // A cell with only legacy marker/escaped text must not pull the whole
        // question onto the WebView path.
        assertEquals(
            ContentRenderPath.NATIVE,
            contentRenderPath(listOf(ContentElement.TableElement(listOf(
                listOf(CELL_FORMULA_MARKER, escaped)
            ))))
        )
    }
}
