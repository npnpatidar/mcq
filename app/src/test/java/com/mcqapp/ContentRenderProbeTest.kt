package com.mcqapp

import com.mcqapp.data.docx.DocxBlock
import com.mcqapp.data.docx.readDocx
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.ContentRenderPath
import com.mcqapp.util.InlineHtml
import com.mcqapp.util.contentRenderPath
import com.mcqapp.util.mixedContentHtml
import com.mcqapp.util.stripMathAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bug-hunt probe (maths/table/underline/highlight pass). Evidence only:
 * every test documents a suspected defect found by reading the code.
 */
class ContentRenderProbeTest {

    private fun docx(bodyInner: String): ByteArray {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>$bodyInner</w:body></w:document>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="xml" ContentType="application/xml"/></Types>""".toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun tc(text: String, extraPr: String = "") =
        """<w:tc>$extraPr<w:p><w:r><w:t>$text</w:t></w:r></w:p></w:tc>"""

    /**
     * BUG: a horizontally merged DOCX cell (w:gridSpan) is imported as ONE
     * cell, so the row has fewer cells than the grid — every following cell
     * of that row shifts one column left against the other rows, in-app,
     * in the PDF and in re-exported papers.
     */
    @Test
    fun gridSpanMergedCellShiftsColumns() {
        val bytes = docx(
            "<w:tbl>" +
                "<w:tr>" + tc("H1", "<w:tcPr><w:gridSpan w:val=\"2\"/></w:tcPr>") + tc("H2") + "</w:tr>" +
                "<w:tr>" + tc("A") + tc("B") + tc("C") + "</w:tr>" +
                "</w:tbl>"
        )
        val read = readDocx(bytes)
        val table = read.blocks.filterIsInstance<DocxBlock.Table>().single()
        assertEquals(listOf(listOf("H1", "H2"), listOf("A", "B", "C")), table.rows)
    }

    /**
     * BUG: a cell formatted in Word (underline here) imports as raw HTML in
     * the cell string — the native table shows it literally, the mixed view
     * escapes it literally, HTML export escapes it literally, DOCX export
     * writes it as literal text. Only the PDF writer renders it.
     */
    @Test
    fun underlinedCellImportsAsRawMarkup() {
        val bytes = docx(
            "<w:tbl><w:tr>" +
                "<w:tc><w:p><w:r><w:rPr><w:u/></w:rPr><w:t>u</w:t></w:r>" +
                "<w:r><w:rPr><w:highlight w:val=\"yellow\"/></w:rPr><w:t>m</w:t></w:r></w:p></w:tc>" +
                "</w:tr></w:tbl>"
        )
        val read = readDocx(bytes)
        val table = read.blocks.filterIsInstance<DocxBlock.Table>().single()
        assertEquals(listOf(listOf("<u>u</u><mark>m</mark>")), table.rows)
    }

    /**
     * FIXED (consolidated_bugs.md G1): a table cell's inline markup renders
     * through the same pipeline as a TextElement.
     */
    @Test
    fun mixedViewRendersCellMarkup() {
        val html = mixedContentHtml(
            listOf(ContentElement.TableElement(listOf(listOf("<u>u</u>", "<mark>m</mark>")))),
            16f, "rgba(0,0,0,1)"
        )
        assertTrue(html.contains("<td><u>u</u></td>"))
        assertTrue(html.contains("<td><mark>m</mark></td>"))
    }

    /**
     * FIXED (consolidated_bugs.md G10): newlines inside a table cell become
     * <br/>, matching how a TextElement renders.
     */
    @Test
    fun mixedViewBreaksNewlinesInTableCell() {
        val html = mixedContentHtml(
            listOf(ContentElement.TableElement(listOf(listOf("line1\nline2")))),
            16f, "rgba(0,0,0,1)"
        )
        assertTrue(html.contains("<td>line1<br/>line2</td>"))
    }

    /**
     * FIXED (images in tables): a DOCX table-cell equation stays in the cell
     * string (the import contract), but the mixed view now typesets it instead
     * of printing raw MathML — [com.mcqapp.util.contentRenderPath] sends a
     * math-bearing table to the WebView and `tableCellHtml` keeps the formula
     * for MathJax.
     */
    @Test
    fun mathInTableCellIsTypesetByTheMixedView() {
        val bytes = docx(
            "<w:tbl><w:tr><w:tc>" +
                "<w:p><m:oMath xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\">" +
                "<m:r><m:t>x</m:t></m:r></m:oMath></w:p>" +
                "</w:tc></w:tr></w:tbl>"
        )
        val read = readDocx(bytes)
        val table = read.blocks.filterIsInstance<DocxBlock.Table>().single()
        val cell = table.rows.single().single()
        assertTrue("cell keeps the MathML: ${table.rows}", cell.contains("<math"))
        val html = mixedContentHtml(
            listOf(ContentElement.TableElement(table.rows)), 16f, "rgba(0,0,0,1)"
        )
        assertTrue("the formula reaches MathJax: $html", html.contains("<math>"))
        assertFalse("never escaped markup: $html", html.contains("&lt;math"))
    }

    /**
     * FIXED (images in tables): a picture inside a DOCX table cell imports as
     * a portable data-URI `<img>` in the cell string — that part is the
     * import contract, not a bug — and every drawing surface now uses it. A
     * table with a portable cell picture takes the mixed/WebView path, which
     * draws the real image sized to the cell; a text-only surface still shows
     * the marker. No surface prints the base64 as text.
     */
    @Test
    fun pictureInTableCellIsDrawnInsteadOfBase64Text() {
        val png = java.util.Base64.getEncoder().encodeToString(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        )
        // rels + content types with a (tiny, fake) media part: the reader
        // only needs the rel target to resolve inside the zip.
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body><w:tbl><w:tr><w:tc><w:p><w:r><w:drawing>
<wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">
<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData>
<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:blipFill>
<a:blip r:embed="rId1"
xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/>
</pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline>
</w:drawing></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml")); zip.write(xml.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zip.write(
                """<?xml version="1.0"?><Relationships
xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Target="media/image1.png"/></Relationships>""".toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/media/image1.png")); zip.write(png.toByteArray()); zip.closeEntry()
        }
        val read = readDocx(out.toByteArray())
        val table = read.blocks.filterIsInstance<DocxBlock.Table>().single()
        val cell = table.rows.single().single()
        assertTrue("cell carries the portable tag: $cell", cell.startsWith("<img src=\"data:image/png;base64,"))

        val elements = listOf(ContentElement.TableElement(table.rows))
        assertEquals(ContentRenderPath.MIXED, contentRenderPath(elements))
        val html = mixedContentHtml(elements, 16f, "rgba(0,0,0,1)")
        assertTrue("the screen draws the image: $html", html.contains("<img src=\"data:image/png;base64,"))
        assertTrue(
            "sized to the cell: $html",
            html.contains("style=\"max-width:100%;height:auto;max-height:240px;vertical-align:middle\"")
        )
        assertTrue("labelled for a reader: $html", html.contains("alt=\"Image in table\""))
        assertFalse("no marker where the picture can be drawn: $html", html.contains(CELL_IMAGE_MARKER))
    }

    /**
     * FIXED (consolidated_bugs.md G7): text around the <math> block is kept,
     * escaped as text, instead of being dropped.
     */
    @Test
    fun textAroundMathBlockIsKept() {
        val rendered = stripMathAttributes("Solve <math><mi>x</mi></math> now")
        assertEquals(
            "surrounding text kept",
            "Solve <math><mrow><mi>x</mi></mrow></math> now",
            rendered
        )
    }

    /**
     * BUG (consistency): the in-app renderer strips unknown tags, every
     * exporter keeps them as visible text — the screen hides "<one>" while
     * the HTML/Anki/PDF/DOCX exports (and a re-import of them) show it.
     */
    @Test
    fun unknownTagFateDiffersOnScreenVsExports() {
        assertEquals("Pick ?", InlineHtml.parse("Pick <one>?").text)
        // renderInlineHtml keeps it visible (RichTextSanitizingTest pins this).
    }

    /**
     * BUG (consistency): consecutive <br>s collapse to one newline in-app,
     * but the DOCX writer emits one w:br per tag — the same stored text
     * exports with a blank line the screen never shows.
     */
    @Test
    fun doubleBreakCollapsesInAppOnly() {
        assertEquals("a\nb", InlineHtml.parse("a<br/><br/>b").text)
    }
}
