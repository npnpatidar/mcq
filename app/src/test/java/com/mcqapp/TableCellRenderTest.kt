package com.mcqapp

import com.mcqapp.data.sanitizeMathml
import com.mcqapp.util.CELL_FORMULA_MARKER
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.sanitizeTableCell
import com.mcqapp.util.tableCellHtml
import org.junit.Assert.assertEquals
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
}
