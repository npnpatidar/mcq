package com.mcqapp

import com.mcqapp.data.sanitizeMathml
import com.mcqapp.domain.CellPart
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.MAX_BARE_MATH_RUN_CHARS
import com.mcqapp.domain.scanEscapedMathTag
import com.mcqapp.domain.searchableText
import com.mcqapp.domain.tableCellParts
import com.mcqapp.util.CELL_FORMULA_MARKER
import com.mcqapp.util.CELL_IMAGE_ALT
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.CELL_IMAGE_STYLE
import com.mcqapp.util.ContentRenderPath
import com.mcqapp.util.accessibleContentText
import com.mcqapp.util.cellHasPortableImage
import com.mcqapp.util.cellImageTag
import com.mcqapp.util.chemCellHtml
import com.mcqapp.util.contentRenderPath
import com.mcqapp.util.mapCellImages
import com.mcqapp.util.portableCellImageSrc
import com.mcqapp.util.sanitizeTableCell
import com.mcqapp.util.soleImgSrc
import com.mcqapp.util.stripMathAttributes
import com.mcqapp.util.tableCellHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table cells carry inline markup on purpose (consolidated_bugs.md G1-G3):
 * the text-only surfaces (PDF, DOCX, accessibility) collapse a picture and a
 * formula to visible markers, while the screen's HTML surfaces draw a
 * portable picture and keep the formula for MathJax.
 */
class TableCellRenderTest {

    @Test
    fun `a data-uri picture is the marker on a text-only surface`() {
        val cell = "Before <img src=\"data:image/png;base64,AAAA\"/> after"
        assertEquals("Before $CELL_IMAGE_MARKER after", sanitizeTableCell(cell))
    }

    @Test
    fun `a portable picture renders as a rebuilt image tag in html`() {
        val cell = "a <img src=\"data:image/png;base64,AAAA\"/> b"
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        // The rebuilt tag: the stored src, the canonical sizing style (with
        // the height cap) and a non-empty alt; the surrounding prose in order.
        assertTrue("real image: $html", html.contains("<img src=\"data:image/png;base64,AAAA\""))
        assertTrue("canonical style: $html", html.contains("style=\"$CELL_IMAGE_STYLE\""))
        assertTrue("height bounded: $html", CELL_IMAGE_STYLE.contains("max-height:240px"))
        assertTrue("alt label: $html", html.contains("alt=\"$CELL_IMAGE_ALT\""))
        assertTrue("text before keeps its place: $html", html.startsWith("a <img "))
        assertTrue("text after keeps its place: $html", html.endsWith("/> b"))
    }

    @Test
    fun `accepted tag forms all render one canonical image`() {
        val cells = listOf(
            "a <img src='data:image/png;base64,AAAA'/> b",
            "a <img src = \"data:image/png;base64,AAAA\"> b",
            "a <IMG SRC=\"data:image/png;base64,AAAA\"> b",
            "a <img SRC='DATA:IMAGE/PNG;BASE64,AAAA'> b",
            "a <img src=\"data:image/png;base64,AA==\"/> b",
            "a <img src=\"data:image/png;base64,AAA=\"/> b",
            "a <img src=\"data:image/png;base64,AAA\"/> b"
        )
        cells.forEach { cell ->
            val html = tableCellHtml(cell) { sanitizeMathml(it) }
            assertTrue("real image for $cell: $html", html.contains("<img src=\""))
            assertTrue("canonical style for $cell: $html", html.contains("style=\"$CELL_IMAGE_STYLE\""))
            assertTrue("alt label for $cell: $html", html.contains("alt=\"$CELL_IMAGE_ALT\""))
            assertFalse("no marker for $cell: $html", html.contains(CELL_IMAGE_MARKER))
            assertTrue("text before for $cell: $html", html.startsWith("a <img "))
            assertTrue("text after for $cell: $html", html.endsWith("/> b"))
        }
    }

    @Test
    fun `the stored canonical tag still renders as a picture`() {
        // Imports store the same tag the renderer writes (src + the fixed
        // style and alt). It must stay portable, or every imported cell image
        // would show the marker after the import normaliser had run.
        val cell = "before ${cellImageTag("data:image/png;base64,AAAA")} after"
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        assertTrue("a stored picture draws: $html", html.contains("<img src=\""))
        assertFalse("no marker for a stored picture: $html", html.contains(CELL_IMAGE_MARKER))
        assertTrue("the mixed path is chosen", cellHasPortableImage(cell))
    }

    @Test
    fun `the strict reader accepts the canonical style and alt in either order`() {
        val src = "data:image/png;base64,AAAA"
        val tags = listOf(
            """<img src="$src" style="$CELL_IMAGE_STYLE" alt="$CELL_IMAGE_ALT"/>""",
            """<img style="$CELL_IMAGE_STYLE" alt="$CELL_IMAGE_ALT" src="$src"/>""",
            """<img alt="$CELL_IMAGE_ALT" src="$src" style="$CELL_IMAGE_STYLE"/>"""
        )
        tags.forEach { tag ->
            assertEquals("the stored tag stays drawable: $tag", src, soleImgSrc(tag))
        }
        // cellImageTag is that same pair, in the renderer's one order.
        assertEquals(src, soleImgSrc(cellImageTag(src)))
    }

    @Test
    fun `the strict reader rejects a custom or duplicate style and alt`() {
        val src = "data:image/png;base64,AAAA"
        val tags = listOf(
            """<img src="$src" style="width:100%"/>""",
            """<img src="$src" alt="custom"/>""",
            """<img src="$src" style="$CELL_IMAGE_STYLE" style="$CELL_IMAGE_STYLE"/>""",
            """<img src="$src" alt="$CELL_IMAGE_ALT" alt="$CELL_IMAGE_ALT"/>""",
            // A value-less attribute is not something the strict reader draws.
            """<img hidden src="$src"/>"""
        )
        tags.forEach { tag ->
            assertNull("not drawable: $tag", soleImgSrc(tag))
        }
    }

    @Test
    fun `only a portable base64 image data uri is allowed`() {
        val portable = listOf(
            "data:image/png;base64,AAAA",
            "data:image/jpeg;base64,AAAA",
            "data:image/jpg;base64,AAAA",
            "data:image/gif;base64,AAAA",
            "data:image/webp;base64,AAAA",
            // Scheme and the base64 parameter are case-insensitive.
            "DATA:IMAGE/PNG;BASE64,AAAA",
            // Unpadded and legally padded bodies.
            "data:image/png;base64,AA",
            "data:image/png;base64,AAA",
            "data:image/png;base64,AA==",
            "data:image/png;base64,AAA="
        )
        portable.forEach { assertTrue(it, portableCellImageSrc(it)) }
        val rejected = listOf(
            "",
            "data:text/html;base64,AAAA",
            "data:image/svg+xml;base64,AAAA",
            "javascript:alert(1)",
            "file:///x.png",
            "http://example.com/x.png",
            "https://example.com/x.png",
            "data:image/png;base64,A",
            "data:image/png;base64,AA AA",
            "data:image/png;base64,",
            "data:image/png;base64,AAAA=",
            "data:image/png;base64,AAAA==",
            "data:image/png;base64,AA=A",
            "data:image/png;base64,AAA===",
            "data:image/png;base64,A="
        )
        rejected.forEach { assertFalse(it, portableCellImageSrc(it)) }
    }

    @Test
    fun `a huge payload is shape-checked without decoding`() {
        // Several megabytes of valid base64: a decode here would allocate a
        // second buffer during composition. The shape scan is linear.
        val huge = "data:image/png;base64," + "A".repeat(1_000_000)
        assertTrue(portableCellImageSrc(huge))
    }

    @Test
    fun `a non-portable picture never becomes an image tag in html`() {
        val tags = listOf(
            "<img src=\"data:text/html;base64,AAAA\"/>",
            "<img src=\"javascript:alert(1)\"/>",
            "<img src=\"file:///x.png\"/>",
            "<img src=\"http://example.com/x.png\"/>",
            "<img src=\"data:image/svg+xml;base64,AAAA\"/>",
            "<img alt=\"no source\"/>",
            "<img src=\"data:image/png;base64,AAAA\" onerror=\"alert(1)\"/>",
            "<img src=\"data:image/png;base64,AAAA\" src=\"data:image/png;base64,AAAA\"/>",
            "<img src=data:image/png;base64,AAAA>",
            "<img src=\"data:image/png;base64,AAAA\" <img src=\"data:image/png;base64,AAAA\">",
            "<img src=\"data:image/png;base64,AA AA\"/>",
            "<img src=\"data:image/png;base64,AAAA\"/"
        )
        tags.forEach { tag ->
            val html = tableCellHtml("before $tag after") { sanitizeMathml(it) }
            assertTrue("marker for $tag: $html", html.contains(CELL_IMAGE_MARKER))
            assertFalse("no live image for $tag: $html", html.contains("<img"))
            assertFalse("no payload for $tag: $html", html.contains("base64,"))
            assertEquals(
                "native path stays native for $tag",
                ContentRenderPath.NATIVE,
                contentRenderPath(listOf(ContentElement.TableElement(listOf(listOf(tag)))))
            )
        }
    }
    @Test
    fun `a truncated image tag becomes the marker not prose`() {
        val html = tableCellHtml("before <img src=\"data:image/png;base64,AAAA") { sanitizeMathml(it) }
        assertTrue("marker replaces the tag: $html", html.endsWith(CELL_IMAGE_MARKER))
        assertFalse("the payload is gone: $html", html.contains("base64,"))
        assertFalse("no live image: $html", html.contains("<img"))
        assertTrue("the prose before it survives: $html", html.startsWith("before "))
    }

    @Test
    fun `a truncated tag without a closing bracket keeps the prose after it`() {
        val cell = "before <img src=\"data:image/png;base64,AAAA\"/ after"
        // The old scanner matched to the end of the cell, so "after" vanished
        // on every surface. The scan stops at the last complete attribute
        // value instead.
        assertEquals(
            "before $CELL_IMAGE_MARKER after",
            tableCellHtml(cell) { sanitizeMathml(it) }
        )
        assertEquals("before $CELL_IMAGE_MARKER after", sanitizeTableCell(cell))
        assertEquals(
            "before $CELL_IMAGE_MARKER after",
            accessibleContentText(listOf(ContentElement.TableElement(listOf(listOf(cell)))))
        )
        assertFalse("no portable picture was found", cellHasPortableImage(cell))
    }

    @Test
    fun `an unquoted truncated tag keeps the prose after it`() {
        // Unquoted and unclosed: no quote delimits the tag, so only the
        // tag-like `name=value` run goes — "after" is prose, not payload.
        val cells = listOf(
            "before <img src=data:image/png;base64,AAAA after",
            "before <img src=data:image/png;base64,AAAA alt=x after",
            "before <IMG SRC=data:image/png;base64,AAAA after"
        )
        cells.forEach { cell ->
            val html = tableCellHtml(cell) { sanitizeMathml(it) }
            assertEquals("marker keeps the prose for $cell: $html",
                "before $CELL_IMAGE_MARKER after", html)
            assertEquals("text surface for $cell",
                "before $CELL_IMAGE_MARKER after", sanitizeTableCell(cell))
            assertEquals("spoken surface for $cell",
                "before $CELL_IMAGE_MARKER after",
                accessibleContentText(listOf(ContentElement.TableElement(listOf(listOf(cell))))))
            assertFalse("never the payload for $cell: $html", html.contains("base64,"))
            assertFalse("no portable picture for $cell", cellHasPortableImage(cell))
            assertEquals("native path stays native for $cell",
                ContentRenderPath.NATIVE,
                contentRenderPath(listOf(ContentElement.TableElement(listOf(listOf(cell))))))
        }
    }

    @Test
    fun `a truncated unquoted tag reads the same attribute grammar as a complete one`() {
        // A `/` before an attribute name and whitespace around `=` are the
        // attribute grammar the complete-tag reader accepts. The old scan
        // stopped at the `/` or the space, so the whole `src=…base64…` run
        // printed as prose on HTML, text and spoken surfaces.
        val cells = listOf(
            "before <img/src=data:image/png;base64,AAAA after",
            "before <img/ src=data:image/png;base64,AAAA after",
            "before <img /src=data:image/png;base64,AAAA after",
            "before <img src =data:image/png;base64,AAAA after",
            "before <img src = data:image/png;base64,AAAA after",
            "before <img height=100 /src=data:image/png;base64,AAAA after",
            "before <IMG SRC =DATA:IMAGE/PNG;BASE64,AAAA after"
        )
        cells.forEach { cell ->
            val html = tableCellHtml(cell) { sanitizeMathml(it) }
            assertEquals("marker keeps the prose for $cell: $html",
                "before $CELL_IMAGE_MARKER after", html)
            assertEquals("text surface for $cell",
                "before $CELL_IMAGE_MARKER after", sanitizeTableCell(cell))
            assertEquals("spoken surface for $cell",
                "before $CELL_IMAGE_MARKER after",
                accessibleContentText(listOf(ContentElement.TableElement(listOf(listOf(cell))))))
            assertFalse("never the payload for $cell: $html",
                html.lowercase().contains("base64,"))
            assertFalse("no portable picture for $cell", cellHasPortableImage(cell))
            assertEquals("native path stays native for $cell",
                ContentRenderPath.NATIVE,
                contentRenderPath(listOf(ContentElement.TableElement(listOf(listOf(cell))))))
        }
    }

    @Test
    fun `a quoted attribute before an unquoted src in a torn tag leaks nothing`() {
        // A complete quoted value used to end the truncated tag's span, so the
        // unquoted scanner never ran and the `src` after the quote printed as
        // prose on every surface. The span now continues from the quoted end
        // and takes the later boundary.
        val cells = listOf(
            "before <img alt=\"x\" src=data:image/png;base64,AAAA after",
            "before <img alt=\"x>y\" src=data:image/png;base64,AAAA after",
            "before <img title=\"x\" /src=data:image/png;base64,AAAA after",
            "before <img alt=\"x\" src =data:image/png;base64,AAAA after",
            "before <img alt=\"x\"src=data:image/png;base64,AAAA after",
            "before <img alt='x' SRC = DATA:IMAGE/PNG;BASE64,AAAA after"
        )
        cells.forEach { cell ->
            val html = tableCellHtml(cell) { sanitizeMathml(it) }
            assertEquals("marker keeps the prose for $cell: $html",
                "before $CELL_IMAGE_MARKER after", html)
            assertEquals("text surface for $cell",
                "before $CELL_IMAGE_MARKER after", sanitizeTableCell(cell))
            assertEquals("spoken surface for $cell",
                "before $CELL_IMAGE_MARKER after",
                accessibleContentText(listOf(ContentElement.TableElement(listOf(listOf(cell))))))
            assertFalse("never the payload for $cell: $html",
                html.lowercase().contains("base64,"))
            assertFalse("never a leaked src for $cell: $html",
                html.lowercase().contains("src"))
            assertFalse("no portable picture for $cell", cellHasPortableImage(cell))
            assertEquals("native path stays native for $cell",
                ContentRenderPath.NATIVE,
                contentRenderPath(listOf(ContentElement.TableElement(listOf(listOf(cell))))))
        }
    }

    @Test
    fun `a bare truncated tag at the end of a cell is the marker`() {
        val cell = "before <img"
        assertEquals("before $CELL_IMAGE_MARKER", sanitizeTableCell(cell))
        assertEquals("before $CELL_IMAGE_MARKER", tableCellHtml(cell) { sanitizeMathml(it) })
        assertEquals(
            "before $CELL_IMAGE_MARKER",
            accessibleContentText(listOf(ContentElement.TableElement(listOf(listOf(cell)))))
        )
        assertFalse("no portable picture", cellHasPortableImage(cell))
    }

    @Test
    fun `a greater-than inside a quoted attribute does not end the tag`() {
        val tags = listOf(
            "<img alt=\"x>y\" src=\"data:image/png;base64,AAAA\">",
            "<img alt='x>y' src='data:image/png;base64,AAAA'>"
        )
        tags.forEach { tag ->
            val cell = "before $tag after"
            // The whole tag is one malformed picture: it collapses to the
            // marker, never leaving `y"` or base64 behind as prose.
            val html = tableCellHtml(cell) { sanitizeMathml(it) }
            assertEquals("marker only for $tag: $html", "before $CELL_IMAGE_MARKER after", html)
            assertEquals("before $CELL_IMAGE_MARKER after", sanitizeTableCell(cell))
            assertEquals(
                "spoken marker only for $tag",
                "before $CELL_IMAGE_MARKER after",
                accessibleContentText(listOf(ContentElement.TableElement(listOf(listOf(cell)))))
            )
            assertFalse("no payload for $tag: $html", html.contains("base64,"))
        }
    }

    @Test
    fun `an unterminated quote is consumed to the end of the cell`() {
        val cell = "before <img src=\"data:image/png;base64,AAAA"
        assertEquals("before $CELL_IMAGE_MARKER", sanitizeTableCell(cell))
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        assertEquals("before $CELL_IMAGE_MARKER", html)
        assertFalse("never the payload: $html", html.contains("base64,"))
        assertEquals(
            "before $CELL_IMAGE_MARKER",
            accessibleContentText(listOf(ContentElement.TableElement(listOf(listOf(cell)))))
        )
        // With the quote still open the tail cannot be told from the payload,
        // so it is consumed too: the open quote is the one case that must
        // swallow the prose after the tag.
        val withProse = "before <img src=\"data:image/png;base64,AAAA after"
        assertEquals("before $CELL_IMAGE_MARKER", sanitizeTableCell(withProse))
        assertEquals(
            "before $CELL_IMAGE_MARKER",
            tableCellHtml(withProse) { sanitizeMathml(it) }
        )
    }

    @Test
    fun `two pictures keep their order and both canonical tags`() {
        val cell = "a <img src=\"data:image/png;base64,AAAA\"/> b " +
            "<img src='data:image/png;base64,BBBB'> c"
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        val first = html.indexOf("<img src=\"data:image/png;base64,AAAA\"")
        val second = html.indexOf("<img src=\"data:image/png;base64,BBBB\"")
        assertTrue("both pictures are drawn: $html", first >= 0 && second > first)
        assertTrue("prose before them: $html", html.startsWith("a <img "))
        assertTrue("prose between them: $html", html.contains("/> b <img "))
        assertTrue("prose after them: $html", html.endsWith("/> c"))
        assertEquals("exactly two tags", 2, Regex("<img ").findAll(html).count())
        assertFalse("never a marker: $html", html.contains(CELL_IMAGE_MARKER))
        // The text surfaces keep both markers, in the same order.
        assertEquals("a $CELL_IMAGE_MARKER b $CELL_IMAGE_MARKER c", sanitizeTableCell(cell))
    }

    @Test
    fun `an image-only cell is one rebuilt tag and nothing else`() {
        val cell = "<img src=\"data:image/png;base64,AAAA\"/>"
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        assertTrue("canonical tag: $html", html.startsWith("<img src=\"data:image/png;base64,AAAA\""))
        assertTrue("nothing after it: $html", html.endsWith("/>"))
        assertFalse("never a marker: $html", html.contains(CELL_IMAGE_MARKER))
        assertEquals(CELL_IMAGE_MARKER, sanitizeTableCell(cell))
        assertTrue(cellHasPortableImage(cell))
    }

    @Test
    fun `inline formatting across a picture stays balanced`() {
        val html = tableCellHtml("<b>a <img src=\"data:image/png;base64,AAAA\"/> b</b>") {
            sanitizeMathml(it)
        }
        // Each prose chunk is rendered on its own, but the tags it carries are
        // re-emitted verbatim, so the pair still wraps the picture.
        assertTrue("opening markup: $html", html.startsWith("<b>a <img "))
        assertTrue("closing markup: $html", html.endsWith("/> b</b>"))
        assertEquals("one open tag", 1, Regex("<b>").findAll(html).count())
        assertEquals("one close tag", 1, Regex("</b>").findAll(html).count())
    }

    @Test
    fun `the scanner maps only picture tags and copies everything else`() {
        val cell = "<b>a</b> <img src=\"data:image/png;base64,AAAA\"/> <i>b</i>"
        val mapped = mapCellImages(cell) { src ->
            if (src == null) "NULL" else "SRC($src)"
        }
        assertEquals(
            "<b>a</b> SRC(data:image/png;base64,AAAA) <i>b</i>",
            mapped
        )
    }

    @Test
    fun `a chem cell renders through the hook and degrades to handles without one`() {
        val element = ContentElement.ChemElement(
            smiles = "CCO",
            formula = "C2H5OH",
            caption = "ethanol",
            diagram = "data:image/png;base64,iVBORw0KGgo="
        )
        val cell = "see " + chemCellHtml(element) + " here"

        // The mixed page passes a renderer: each structure becomes its drawing
        // in storage order among the surrounding prose.
        val hooked = tableCellHtml(cell, chemHtml = { "DRAW(${it.smiles})" }) { sanitizeMathml(it) }
        assertEquals("see DRAW(CCO) here", hooked)

        // Every other surface keeps the readable handles — and an in-marker
        // PNG never becomes the [image] marker (the structure is not a loose
        // picture; the payload is an export cache).
        val degraded = tableCellHtml(cell) { sanitizeMathml(it) }
        assertEquals("see ethanol C2H5OH CCO here", degraded)
        assertFalse("no picture marker: $degraded", degraded.contains(CELL_IMAGE_MARKER))
        assertFalse("no payload: $degraded", degraded.contains("data-png"))
        assertFalse("no base64: $degraded", degraded.contains("base64,"))
    }

    @Test
    fun `a table with a portable picture takes the mixed path`() {
        val cell = "a <img src=\"data:image/png;base64,AAAA\"/> b"
        assertEquals(
            ContentRenderPath.MIXED,
            contentRenderPath(listOf(ContentElement.TableElement(listOf(listOf(cell)))))
        )
    }

    @Test
    fun `a table with only a non-portable picture stays native`() {
        val cell = "a <img src=\"http://example.com/x.png\"/> b"
        assertEquals(
            ContentRenderPath.NATIVE,
            contentRenderPath(listOf(ContentElement.TableElement(listOf(listOf(cell)))))
        )
    }

    @Test
    fun `a plain table stays native`() {
        assertEquals(
            ContentRenderPath.NATIVE,
            contentRenderPath(
                listOf(ContentElement.TableElement(listOf(listOf("plain <b>text</b>"))))
            )
        )
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
    fun `a picture stays a text part and a text-only surface replaces it with the marker`() {
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
        // HTML surfaces: the formula stays live markup and the picture is now
        // drawn, in the stored order; the data URI stays an attribute.
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        assertTrue(
            "formula keeps its order: $html",
            html.startsWith("a <math><mi>x</mi></math> <img src=\"data:image/png;base64,AAAA\"")
        )
        assertTrue("picture keeps its place: $html", html.endsWith("/> b"))
        assertTrue("picture is labelled: $html", html.contains("alt=\"$CELL_IMAGE_ALT\""))
        assertFalse("marker only where no image can be drawn: $html", html.contains(CELL_IMAGE_MARKER))
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

    // ---- bare MathLive fragments stored by the buggy build ----------------

    /**
     * MathLive's `getValue('math-ml')` is an atom run, not a document: the
     * buggy build stored `<mrow>…</mrow>` (and sibling runs) verbatim, so
     * every surface printed the raw XML and the column widened to it.
     */
    @Test
    fun `a bare mrow fragment is repaired into one formula in place`() {
        val fragment = "<mrow><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></mrow>"
        val cell = "Solve $fragment = 0"
        val parts = tableCellParts(cell)
        assertEquals(3, parts.size)
        assertEquals(CellPart.Text("Solve "), parts[0])
        assertEquals(
            CellPart.Math("<math><mrow><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></mrow></math>"),
            parts[1]
        )
        assertEquals(CellPart.Text(" = 0"), parts[2])
        // The native/text surfaces see the marker, never the bare tag text.
        assertEquals("Solve $CELL_FORMULA_MARKER = 0", sanitizeTableCell(cell))
        // And the table now needs the mixed renderer, which can typeset it.
        assertEquals(
            ContentRenderPath.MIXED,
            contentRenderPath(listOf(ContentElement.TableElement(listOf(listOf(cell)))))
        )
    }

    @Test
    fun `sibling bare elements are wrapped in one mrow`() {        val cell = "<msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn>"
        assertEquals(
            listOf(
                CellPart.Math(
                    "<math><mrow><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></mrow></math>"
                )
            ),
            tableCellParts(cell)
        )
        // A lone token element is a valid run too.
        assertEquals(
            listOf(CellPart.Math("<math><mrow><mi>x</mi></mrow></math>")),
            tableCellParts("<mi>x</mi>")
        )
    }

    @Test
    fun `a complete math block is never rewrapped by the repair`() {
        val cell = "<math display=\"block\"><mi>x</mi></math>"
        assertEquals(listOf(CellPart.Math(cell)), tableCellParts(cell))
    }

    @Test
    fun `two bare formulas in one cell stay separate`() {
        assertEquals(
            listOf(
                CellPart.Math("<math><mrow><mi>a</mi></mrow></math>"),
                CellPart.Text(" and "),
                CellPart.Math("<math><mrow><mi>b</mi></mrow></math>")
            ),
            tableCellParts("<mi>a</mi> and <mi>b</mi>")
        )
    }

    @Test
    fun `a newline between bare siblings does not split the formula`() {
        assertEquals(
            listOf(CellPart.Math("<math><mrow><mi>x</mi>\n<mo>+</mo><mn>1</mn></mrow></math>")),
            tableCellParts("<mi>x</mi>\n<mo>+</mo><mn>1</mn>")
        )
    }

    @Test
    fun `a bare fragment beside a portable picture keeps both parts`() {
        val cell = "<msup><mi>x</mi><mn>2</mn></msup> " +
            "<img src=\"data:image/png;base64,AAAA\"/>"
        val parts = tableCellParts(cell)
        assertEquals(2, parts.size)
        assertEquals(
            CellPart.Math("<math><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow></math>"),
            parts[0]
        )
        assertTrue("the picture stays prose: ${parts[1]}", parts[1] is CellPart.Text)
        // Text-only surfaces: formula marker then picture marker.
        assertEquals("$CELL_FORMULA_MARKER $CELL_IMAGE_MARKER", sanitizeTableCell(cell))
    }

    @Test
    fun `a bare fragment beside a chem marker keeps both parts`() {
        val chem = chemCellHtml(
            ContentElement.ChemElement(smiles = "CCO", formula = "C2H5OH", caption = "ethanol")
        )
        val cell = "$chem <mi>y</mi>"
        // The marker is prose to this grammar; the adjacent atom run is still
        // repaired, and neither is swallowed by the other.
        assertEquals(
            CellPart.Math("<math><mrow><mi>y</mi></mrow></math>"),
            tableCellParts(cell).filterIsInstance<CellPart.Math>().single()
        )
        val text = sanitizeTableCell(cell)
        assertTrue("formula marker: $text", text.contains(CELL_FORMULA_MARKER))
        assertTrue("structure handles: $text", text.contains("ethanol C2H5OH CCO"))
        assertFalse("no raw marker: $text", text.contains("mcq-chem-cell"))
        assertFalse("no raw tags: $text", text.contains("<mi>"))
    }

    @Test
    fun `an unclosed or unbalanced bare fragment stays prose`() {
        val unclosed = "<mrow><mi>x</mi>"
        val mismatched = "<mrow><mi>x</mn></mrow>"
        for (cell in listOf(unclosed, mismatched)) {
            val parts = tableCellParts(cell)
            assertEquals("stays prose: $cell", listOf(CellPart.Text(cell)), parts)
            assertEquals(cell, sanitizeTableCell(cell))
        }
    }

    @Test
    fun `html, hostile tags and escaped tags inside a run keep it prose`() {
        val bad = listOf(
            "<mrow><b>x</b></mrow>",
            "<mrow><span>x</span></mrow>",
            "<mrow><img src=\"data:image/png;base64,AAAA\"/></mrow>",
            "<mrow><script>alert(1)</script></mrow>",
            "<mrow>&lt;mi&gt;x&lt;/mi&gt;</mrow>"
        )
        for (cell in bad) {
            val parts = tableCellParts(cell)
            assertTrue("no formula from: $cell", parts.all { it is CellPart.Text })
            assertEquals("verbatim: $cell", cell, parts.joinToString("") { (it as CellPart.Text).html })
        }
        val escaped = "&lt;mrow&gt;&lt;mi&gt;x&lt;/mi&gt;&lt;/mrow&gt;"
        assertEquals(listOf(CellPart.Text(escaped)), tableCellParts(escaped))
        assertEquals(escaped, sanitizeTableCell(escaped))
        // An unclosed `<mi>` after prose is not a run either.
        val prose = "Use <mi> as the tag"
        assertEquals(listOf(CellPart.Text(prose)), tableCellParts(prose))
    }

    @Test
    fun `a bare run with an escaped comparison operator still repairs`() {
        // MathLive serialises `<` in an operator as `&lt;`; that is math
        // content, not an escaped tag, so an inequality must not fall back to
        // prose (the owner's bug would otherwise persist for `x < 2`).
        val cell = "<mi>x</mi><mo>&lt;</mo><mn>2</mn>"
        assertEquals(
            listOf(CellPart.Math("<math><mrow><mi>x</mi><mo>&lt;</mo><mn>2</mn></mrow></math>")),
            tableCellParts(cell)
        )
    }

    @Test
    fun `a bare run with escaped token text still repairs`() {
        // MathLive writes `\text{a < b}` as `&lt;` inside mtext. That is
        // content, not escaped markup, so it must not fall back to prose.
        val cell = "<mtext>a &lt; b</mtext>"
        assertEquals(
            listOf(CellPart.Math("<math><mrow><mtext>a &lt; b</mtext></mrow></math>")),
            tableCellParts(cell)
        )
    }

    @Test
    fun `a self-closing math space keeps its siblings`() {
        val cell = "<mi>a</mi><mspace/><mi>b</mi>"
        assertEquals(
            listOf(
                CellPart.Math("<math><mrow><mi>a</mi><mspace/><mi>b</mi></mrow></math>")
            ),
            tableCellParts(cell)
        )
        // Whitespace before the closing `>` is still self-closing.
        assertEquals(
            listOf(
                CellPart.Math("<math><mrow><mi>a</mi><mspace/><mi>b</mi></mrow></math>")
            ),
            tableCellParts("<mi>a</mi><mspace/ ><mi>b</mi>")
        )
        // A complete stored block keeps its raw form, but the HTML/text
        // surfaces must not turn `<mspace/>` into a container.
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        assertTrue("space preserved: $html", html.contains("<mspace/>"))
        assertTrue("the sibling after the space survives: $html", html.contains("<mi>b</mi>"))
        assertFalse("never an open space tag: $html", html.contains("<mspace>"))
    }

    @Test
    fun `a repaired run drops attributes before it reaches an export`() {
        // Anki re-emits formula tags; a hostile stored fragment must not carry
        // a handler into the deck.
        val cell = "<mi onclick=\"alert(1)\" mathvariant=\"bold\">x</mi>"
        val math = tableCellParts(cell).filterIsInstance<CellPart.Math>().single()
        assertEquals("<math><mrow><mi>x</mi></mrow></math>", math.mathml)
        assertFalse("no handler: ${math.mathml}", math.mathml.contains("onclick"))
        assertFalse("no attributes: ${math.mathml}", math.mathml.contains("mathvariant"))
    }

    @Test
    fun `sibling runs cut at the repair cap stay wholly prose`() {
        // A run that CONTINUES past the cap must not be repaired as a prefix
        // plus a second formula: the cap is a bound, not a split.
        val continuing = "<mi>abcde</mi>" + "<mi>x</mi>".repeat(2_000)
        assertTrue(continuing.length > MAX_BARE_MATH_RUN_CHARS)
        assertEquals(listOf(CellPart.Text(continuing)), tableCellParts(continuing))
    }

    @Test
    fun `a short run followed by long prose is still repaired`() {
        val cell = "<mi>x</mi>" + " y".repeat(20_000)
        val parts = tableCellParts(cell)
        assertEquals(2, parts.size)
        assertEquals(CellPart.Math("<math><mrow><mi>x</mi></mrow></math>"), parts[0])
        assertTrue("the prose stays text: ${parts[1]}", parts[1] is CellPart.Text)
    }

    @Test
    fun `a huge html tag before a complete formula does not hide it`() {
        val image = hugeImageTag()
        assertTrue("fixture must exceed the cap: ${image.length}",
            image.length > MAX_BARE_MATH_RUN_CHARS)
        val block = "<math><mrow><mi>x</mi></mrow></math>"
        assertEquals(
            listOf(CellPart.Text(image), CellPart.Math(block)),
            tableCellParts(image + block)
        )
        assertEquals(
            listOf(CellPart.Math(block), CellPart.Text(image)),
            tableCellParts(block + image)
        )
    }

    @Test
    fun `a huge html tag before or after a bare formula does not hide it`() {
        val image = hugeImageTag()
        val bare = "<msup><mi>v</mi><mn>2</mn></msup>"
        val repaired = CellPart.Math("<math><mrow><msup><mi>v</mi><mn>2</mn></msup></mrow></math>")
        assertEquals(
            listOf(CellPart.Text(image), repaired),
            tableCellParts(image + bare)
        )
        assertEquals(
            listOf(repaired, CellPart.Text(image)),
            tableCellParts(bare + image)
        )
        // The text-only surface still shows the markers, not the tags.
        assertEquals(
            "$CELL_IMAGE_MARKER$CELL_FORMULA_MARKER",
            sanitizeTableCell(image + bare)
        )
    }

    /** A valid-shaped `<img>` tag longer than the bare-run repair cap. */
    private fun hugeImageTag(): String =
        "<img src=\"data:image/png;base64," + "A".repeat(17_000) + "\"/>"

    @Test
    fun `a hostile escaped-tag chunk is scanned in linear time`() {
        // Thousands of `&lt;` starts with the only `&gt;` BEFORE them: the old
        // lazy-looking matcher rescanned to the chunk end from every start.
        val chunk = "&gt;" + "&lt;a".repeat(3_200)
        val cell = "<mtext>$chunk</mtext>"
        val scan = scanEscapedMathTag(chunk)
        assertFalse("no allow-listed escaped tag is present", scan.found)
        // The discriminating guard: a linear pass examines each character a
        // bounded number of times; a rescan-from-every-`&lt;` implementation
        // reports O(n^2) visits here.
        assertTrue(
            "scan visits ${scan.visits} for ${chunk.length} chars",
            scan.visits <= 2 * chunk.length
        )
        val started = System.nanoTime()
        val parts = tableCellParts(cell)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(1, parts.size)
        assertTrue("the token text is one math run: ${parts.single()}", parts.single() is CellPart.Math)
        assertTrue("scanned a ${cell.length}-char cell in ${elapsedMs}ms", elapsedMs < 1_000)
    }

    @Test
    fun `a bare run past the repair cap stays prose`() {
        val long = "<mrow>" + "<mi>x</mi>".repeat(2_000) + "</mrow>"
        assertTrue("fixture must exceed the cap: ${long.length}", long.length > MAX_BARE_MATH_RUN_CHARS)
        assertEquals(listOf(CellPart.Text(long)), tableCellParts(long))
    }

    /**
     * Every `<` that is not a tag must be visited once, not trigger a rescan
     * of the rest of the cell: this 40 KB input used to be quadratic.
     *
     * The budget mirrors the chem-scan guard: 2000 ms is ~15x headroom over
     * the linear cost (one pass, ~10 ms) while a quadratic regression takes
     * tens of seconds, and a loaded CI runner cannot make it flaky. One
     * measurement, no average.
     */
    @Test
    fun `a hostile cell of malformed math tags stays prose and is scanned once`() {
        val hostile = "<mi>" + "< ".repeat(20_000) + "<mo>"
        val started = System.nanoTime()
        assertEquals(listOf(CellPart.Text(hostile)), tableCellParts(hostile))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("scanned a ${hostile.length}-char cell in ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun `the repaired form is stable and is never wrapped twice`() {
        val bare = "<mrow><msup><mi>x</mi><mn>2</mn></msup></mrow>"
        val repaired = (tableCellParts(bare).single() as CellPart.Math).mathml
        assertEquals("<math><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow></math>", repaired)
        assertEquals(listOf(CellPart.Math(repaired)), tableCellParts(repaired))
        // Sibling atoms wrap once and stay put on the next read.
        val siblings = "<mi>x</mi><mo>+</mo><mn>1</mn>"
        val wrapped = (tableCellParts(siblings).single() as CellPart.Math).mathml
        assertEquals("<math><mrow><mi>x</mi><mo>+</mo><mn>1</mn></mrow></math>", wrapped)
        assertEquals(listOf(CellPart.Math(wrapped)), tableCellParts(wrapped))
    }

    @Test
    fun `a repaired fragment reaches every html and text surface as a formula`() {
        val cell = "before <msup><mi>v</mi><mn>2</mn></msup> after"
        val text = sanitizeTableCell(cell)
        assertEquals("before $CELL_FORMULA_MARKER after", text)
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        assertTrue("formula live: $html", html.contains("<math><mrow><msup><mi>v</mi><mn>2</mn></msup></mrow></math>"))
        assertFalse("never raw tag text: $html", html.contains("&lt;msup"))
        val elements = listOf(ContentElement.TableElement(listOf(listOf(cell))))
        val spoken = accessibleContentText(elements)
        assertTrue("spoken around the formula: $spoken", spoken.contains("before"))
        assertTrue("spoken around the formula: $spoken", spoken.contains("after"))
        // The old text path treated the fragment as prose and dropped the
        // formula, so this structural wording is what fails it now.
        assertTrue("the structure is spoken: $spoken", spoken.contains("v superscribed 2"))
        assertFalse("never raw tags in speech: $spoken", spoken.contains("msup"))
        // Search flattens the stored cell too: the formula's text stays
        // findable and no tag name leaks into the index.
        val search = elements.searchableText
        assertTrue("formula text is searchable: $search", search.contains("v 2"))
        assertFalse("no markup in search: $search", search.contains("msup"))
        assertFalse("no markup in search: $search", search.contains("<"))
    }
}
