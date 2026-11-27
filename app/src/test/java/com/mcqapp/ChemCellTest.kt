package com.mcqapp

import com.mcqapp.data.escapeHtmlText
import com.mcqapp.data.export.chemElementText
import com.mcqapp.data.sanitizeMathml
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CELL_CHEM_ATTR
import com.mcqapp.util.CELL_CHEM_CLASS
import com.mcqapp.util.CELL_FORMULA_MARKER
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.CellSegment
import com.mcqapp.util.ContentRenderPath
import com.mcqapp.util.accessibleContentText
import com.mcqapp.util.cellSegments
import com.mcqapp.util.chemCellHtml
import com.mcqapp.util.contentRenderPath
import com.mcqapp.util.hasChemCell
import com.mcqapp.util.mixedContentHtml
import com.mcqapp.util.sanitizeCellChem
import com.mcqapp.util.sanitizeTableCell
import com.mcqapp.util.tableCellHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * One storage grammar for a chemical structure inside a table cell: a
 * `<span class="mcq-chem-cell" …>handles</span>` marker. These tests pin the
 * round trip, the strict reader (unknown attributes, an unclosed span and an
 * oversized/non-PNG payload all stay safe) and the degradation every existing
 * text surface gets. Rendering is deliberately unchanged in this PR.
 */
class ChemCellTest {

    private val pngBytes = ByteArray(24).also { b ->
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        sig.copyInto(b, 0)
        'I'.code.toByte().let { b[8] = it }
        'H'.code.toByte().let { b[9] = it }
        'D'.code.toByte().let { b[10] = it }
        'R'.code.toByte().let { b[11] = it }
        b[16] = 0; b[17] = 0; b[18] = 0; b[19] = 2 // width = 2
        b[20] = 0; b[21] = 0; b[22] = 0; b[23] = 2 // height = 2
    }

    private val pngPayload: String = Base64.getEncoder().encodeToString(pngBytes)
    private val png: String = "data:image/png;base64,$pngPayload"

    private fun chem(
        smiles: String = "c1ccccc1",
        formula: String = "C6H6",
        caption: String = "Benzene ring",
        spoken: String = "aromatic ring",
        diagram: String = png
    ) = ContentElement.ChemElement(smiles, formula, caption, spoken, diagram)

    private fun body(html: String): String =
        html.substringAfter('>').removeSuffix("</span>")

    // ---- the marker round trip -----------------------------------------

    @Test
    fun `all five fields round-trip through the marker`() {
        val element = chem(
            smiles = "C[C@H](N)C(=O)O",
            formula = "C3H7NO2 & <stuff>",
            caption = "Levo \"dopa\"\nsecond line",
            spoken = "L dopa & a < b > c",
            diagram = png
        )
        val html = chemCellHtml(element)

        assertEquals(listOf<CellSegment>(CellSegment.Chem(element)), cellSegments(html))
        assertTrue("bare png payload: $html", html.contains("data-png=\"$pngPayload\""))
        assertFalse("no data-uri prefix in the cell: $html", html.contains("data:image/png"))
        // The body is the same readable handles every export prints, escaped
        // once: the marker never shows markup where a text surface looks.
        assertEquals(escapeHtmlText(chemElementText(element)), body(html))
        assertFalse("the tag is the only markup: $html", body(html).contains('<'))
    }

    @Test
    fun `the body is the readable handles text`() {
        val element = chem(caption = "Benzene ring", formula = "C6H6", spoken = "aromatic ring")
        assertEquals(
            "Benzene ring C6H6 c1ccccc1",
            body(chemCellHtml(element))
        )
        // The spoken label is an accessibility field, never printed text.
        assertFalse(body(chemCellHtml(element)).contains("aromatic ring"))
    }

    @Test
    fun `a structure with no optional fields round-trips with blank values`() {
        val element = ContentElement.ChemElement("CCO")
        val html = chemCellHtml(element)
        // No empty attributes are emitted at all: `data-png=""` on every
        // captionless structure would double the stored markup for nothing.
        assertEquals(
            "<span class=\"$CELL_CHEM_CLASS\" $CELL_CHEM_ATTR=\"CCO\">CCO</span>",
            html
        )
        assertFalse(html.contains("data-formula"))
        assertFalse(html.contains("data-caption"))
        assertFalse(html.contains("data-spoken"))
        assertFalse(html.contains("data-png"))
        assertEquals(listOf<CellSegment>(CellSegment.Chem(element)), cellSegments(html))
    }

    @Test
    fun `the emitter drops a jpeg or non-png diagram`() {
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val jpeg = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpegBytes)
        val notPng = "data:image/png;base64," +
            Base64.getEncoder().encodeToString("not a png".toByteArray())
        // A bare JPEG payload (no data: prefix) is accepted as a payload shape
        // by the emitter, then rejected by the shared PNG validator.
        val bareJpeg = Base64.getEncoder().encodeToString(jpegBytes)
        listOf(jpeg, notPng, bareJpeg).forEach { diagram ->
            val html = chemCellHtml(chem(diagram = diagram))
            assertFalse("no payload attribute for $diagram", html.contains("data-png"))
            val parsed = cellSegments(html).single() as CellSegment.Chem
            assertEquals("dropped for $diagram", "", parsed.element.diagram)
        }
    }

    @Test
    fun `two structures in one cell keep their order`() {
        val first = chem()
        val second = chem(smiles = "CCO", formula = "C2H5OH", caption = "ethanol")
        val cell = "a " + chemCellHtml(first) + " b " + chemCellHtml(second) + " c"

        assertEquals(
            listOf(
                CellSegment.Text("a "),
                CellSegment.Chem(first),
                CellSegment.Text(" b "),
                CellSegment.Chem(second),
                CellSegment.Text(" c")
            ),
            cellSegments(cell)
        )
        // Text between and around both structures survives sanitizing.
        val sanitized = sanitizeCellChem(cell)
        assertTrue(sanitized.startsWith("a Benzene ring C6H6 c1ccccc1 b "))
        assertTrue(sanitized.endsWith("ethanol C2H5OH CCO c"))
    }

    @Test
    fun `a valid png payload round-trips and includePng false drops it`() {
        val withPng = cellSegments(chemCellHtml(chem())).single() as CellSegment.Chem
        assertEquals(png, withPng.element.diagram)

        val noPng = chemCellHtml(chem(), includePng = false)
        assertFalse("no payload: $noPng", noPng.contains("data-png"))
        val parsed = cellSegments(noPng).single() as CellSegment.Chem
        assertEquals("", parsed.element.diagram)
        assertEquals(chem().copy(diagram = ""), parsed.element)
    }

    @Test
    fun `field text is escaped so it cannot add an attribute`() {
        // A caption written to look like markup is still a caption: the quote
        // is escaped, so it can never close the attribute early.
        val element = chem(caption = "onclick=\"alert(1)\" style=\"color:red\"")
        val html = chemCellHtml(element)
        assertFalse("no injected handler: $html", html.contains("onclick=\""))
        assertFalse("no injected style: $html", html.contains(" style=\""))
        assertEquals(listOf<CellSegment>(CellSegment.Chem(element)), cellSegments(html))
    }

    @Test
    fun `a hostile span beside a valid marker stays prose`() {
        val element = chem()
        val hostile = "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\" " +
            "onclick=\"x()\">ethanol CCO</span>"
        val cell = hostile + " " + chemCellHtml(element)

        assertEquals(
            listOf(CellSegment.Text("$hostile "), CellSegment.Chem(element)),
            cellSegments(cell)
        )
        assertTrue(hasChemCell(cell))
        // The valid marker degrades to its handles; the hostile span is left
        // for the caller's inline pipeline, which escapes or drops its tags.
        val sanitized = sanitizeCellChem(cell)
        assertTrue("hostile span untouched: $sanitized", sanitized.startsWith(hostile))
        assertTrue(
            "valid marker becomes handles: $sanitized",
            sanitized.endsWith(escapeHtmlText(chemElementText(element)))
        )
    }

    // ---- the strict reader ---------------------------------------------

    @Test
    fun `the class token is matched among others and case-insensitively`() {
        val uppercase = "<SPAN CLASS=\"$CELL_CHEM_CLASS\" DATA-SMILES=\"CCO\">ethanol CCO</SPAN>"
        val multiToken = "<span class=\"x $CELL_CHEM_CLASS y\" data-smiles=\"CCO\">ethanol CCO</span>"
        val singleQuoted = "<span class='$CELL_CHEM_CLASS' data-smiles = 'CCO' >ethanol CCO</span>"
        listOf(uppercase, multiToken, singleQuoted).forEach { cell ->
            assertEquals(
                "parsed: $cell",
                listOf<CellSegment>(CellSegment.Chem(ContentElement.ChemElement("CCO"))),
                cellSegments(cell)
            )
            assertTrue("marker: $cell", hasChemCell(cell))
        }
        // A span that merely contains the class name as a substring of another
        // token is not a marker.
        val substring = "<span class=\"not-$CELL_CHEM_CLASS\" data-smiles=\"CCO\">ethanol CCO</span>"
        assertEquals(listOf<CellSegment>(CellSegment.Text(substring)), cellSegments(substring))
        assertFalse(hasChemCell(substring))
    }

    @Test
    fun `a span without data-smiles stays text`() {
        val cell = "<span class=\"$CELL_CHEM_CLASS\">ethanol CCO</span>"
        assertEquals(listOf<CellSegment>(CellSegment.Text(cell)), cellSegments(cell))
        assertFalse(hasChemCell(cell))
        assertEquals(cell, sanitizeCellChem(cell))
    }

    @Test
    fun `a blank data-smiles stays text`() {
        val cell = "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"   \">ethanol CCO</span>"
        assertEquals(listOf<CellSegment>(CellSegment.Text(cell)), cellSegments(cell))
        assertFalse(hasChemCell(cell))
    }

    @Test
    fun `an unclosed span stays text`() {
        val cell = "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\">ethanol CCO"
        assertEquals(listOf<CellSegment>(CellSegment.Text(cell)), cellSegments(cell))
        assertFalse(hasChemCell(cell))
        assertEquals(cell, sanitizeCellChem(cell))
    }

    @Test
    fun `a span with nested markup is not a marker`() {
        val cell = "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\"><b>x</b></span>"
        assertEquals(listOf<CellSegment>(CellSegment.Text(cell)), cellSegments(cell))
        assertFalse(hasChemCell(cell))
    }

    @Test
    fun `a five megabyte payload is rejected before decode`() {
        // Over the 4 MB cap: no base64 decode may run for it. The payload is a
        // real PNG prefix plus a 5 MiB base64 tail — valid base64 with the PNG
        // signature intact — so removing the cap would decode to a non-empty
        // diagram and fail here, rather than passing for the wrong reason. The
        // tail length keeps the payload divisible by 4 (32 + 5242880 chars).
        val huge = pngPayload + "A".repeat(5 * 1024 * 1024)
        assertEquals(0, huge.length % 4)
        val cell = "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\" " +
            "data-png=\"$huge\">ethanol CCO</span>"
        val parsed = cellSegments(cell).single() as CellSegment.Chem
        assertEquals("CCO", parsed.element.smiles)
        assertEquals("", parsed.element.diagram)
        assertTrue(hasChemCell(cell))
    }

    @Test
    fun `a hostile cell of unclosed spans is scanned in linear time`() {
        // ~300 KB of "<span" with no '>' after any of them, plus the class
        // token that makes the cell worth scanning: the old attribute class
        // `[^>]*` scanned to the end of the cell for every occurrence, which
        // took ~30 s here and would freeze the UI thread. The linear pattern
        // finishes in ~10 ms. One assertion, no repeat/average: the budget is
        // deliberately far above the real cost, so a loaded runner cannot make
        // it flaky while a quadratic regression still blows it.
        val cell = "<span".repeat(60 * 1024) + CELL_CHEM_CLASS
        val started = System.nanoTime()
        assertEquals(listOf<CellSegment>(CellSegment.Text(cell)), cellSegments(cell))
        assertFalse(hasChemCell(cell))
        assertEquals(cell, sanitizeCellChem(cell))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("scanned a ${cell.length}-char cell in ${elapsedMs}ms", elapsedMs < 500)
    }

    @Test
    fun `a non-png payload is dropped`() {
        val notPng = Base64.getEncoder().encodeToString("not a png".toByteArray())
        val jpeg = Base64.getEncoder().encodeToString(pngBytes)
        val cells = listOf(
            "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\" data-png=\"$notPng\">ethanol CCO</span>",
            "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\" data-png=\"AA\">ethanol CCO</span>",
            // A full data URI in the attribute is not the bare payload grammar.
            "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\" " +
                "data-png=\"data:image/png;base64,$jpeg\">ethanol CCO</span>"
        )
        cells.forEach { cell ->
            val parsed = cellSegments(cell).single() as CellSegment.Chem
            assertEquals("dropped for $cell", "", parsed.element.diagram)
        }
    }

    @Test
    fun `unknown duplicate and unquoted attributes are never accepted`() {
        val hostile = listOf(
            "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\" onclick=\"x()\">ethanol CCO</span>",
            "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\" style=\"x\">ethanol CCO</span>",
            "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\" data-extra=\"x\">ethanol CCO</span>",
            "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\" data-smiles=\"CCC\">ethanol CCO</span>",
            "<span class=\"$CELL_CHEM_CLASS\" data-smiles=CCO>ethanol CCO</span>",
            "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\" hidden>ethanol CCO</span>"
        )
        hostile.forEach { cell ->
            assertEquals("not a marker: $cell", listOf<CellSegment>(CellSegment.Text(cell)), cellSegments(cell))
            assertFalse("not a marker: $cell", hasChemCell(cell))
        }
    }

    // ---- degradation on the existing cell surfaces ---------------------

    @Test
    fun `sanitizeTableCell yields the handles and never the image marker`() {
        val element = chem()
        val sanitized = sanitizeTableCell("see " + chemCellHtml(element) + " here")
        assertEquals(
            "see " + escapeHtmlText(chemElementText(element)) + " here",
            sanitized
        )
        assertFalse("never a picture marker: $sanitized", sanitized.contains(CELL_IMAGE_MARKER))
        assertFalse("never base64: $sanitized", sanitized.contains("base64,"))
        assertFalse("never the raw marker: $sanitized", sanitized.contains(CELL_CHEM_CLASS))
    }

    @Test
    fun `tableCellHtml still renders only the handles in this pr`() {
        val element = chem()
        val html = tableCellHtml(chemCellHtml(element)) { sanitizeMathml(it) }
        assertTrue("handles text: $html", html.contains(chemElementText(element)))
        assertFalse("no raw marker yet: $html", html.contains(CELL_CHEM_CLASS))
        assertFalse("no attribute leak: $html", html.contains("data-png"))
        assertFalse("no base64: $html", html.contains("base64,"))
    }

    @Test
    fun `a chem cell beside math and a picture keeps their order`() {
        val element = chem()
        val cell = "a " + chemCellHtml(element) +
            " <math><mi>x</mi></math> <img src=\"data:image/png;base64,AAAA\"/> b"
        val handles = escapeHtmlText(chemElementText(element))

        // Text-only surface: handles, formula marker, image marker, in order.
        assertEquals(
            "a $handles $CELL_FORMULA_MARKER $CELL_IMAGE_MARKER b",
            sanitizeTableCell(cell)
        )

        // HTML surface: the handles, then the formula for MathJax, then the
        // rebuilt picture, still in the stored order.
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        val handlesAt = html.indexOf(chemElementText(element))
        val mathAt = html.indexOf("<math>")
        val imageAt = html.indexOf("<img src=")
        assertTrue("handles first: $html", handlesAt >= 0)
        assertTrue("formula after handles: $html", mathAt > handlesAt)
        assertTrue("picture after formula: $html", imageAt > mathAt)
        assertFalse("never the raw marker: $html", html.contains(CELL_CHEM_CLASS))
    }

    @Test
    fun `a plain cell is untouched`() {
        val cell = "<b>bold</b> & <sub>2</sub>"
        assertEquals(cell, sanitizeCellChem(cell))
        assertEquals(cell, sanitizeTableCell(cell))
    }

    @Test
    fun `contentRenderPath does not change in this pr`() {
        // A chem cell alone is still NATIVE: no rendering change ships here.
        assertEquals(
            ContentRenderPath.NATIVE,
            contentRenderPath(
                listOf(ContentElement.TableElement(listOf(listOf(chemCellHtml(chem())))))
            )
        )
        // The existing triggers are unchanged: a formula and a portable picture
        // still select the mixed path, with a chem marker beside them.
        val marker = chemCellHtml(chem())
        assertEquals(
            ContentRenderPath.MIXED,
            contentRenderPath(
                listOf(ContentElement.TableElement(listOf(listOf("$marker <math><mi>x</mi></math>"))))
            )
        )
        assertEquals(
            ContentRenderPath.MIXED,
            contentRenderPath(
                listOf(
                    ContentElement.TableElement(
                        listOf(listOf("$marker <img src=\"data:image/png;base64,AAAA\"/>"))
                    )
                )
            )
        )
    }

    // ---- hostile placements: a marker inside other markup --------------

    @Test
    fun `a marker inside a formula degrades to its handles inside the formula`() {
        val element = chem(smiles = "CCO", formula = "C2H5OH", caption = "ethanol")
        val marker = chemCellHtml(element)
        val cell = "<math><mtext>$marker</mtext></math>"
        val handles = escapeHtmlText(chemElementText(element))

        // The substitution is position-independent: the marker becomes its
        // handles wherever it is, so nothing downstream sees a raw span.
        val substituted = sanitizeCellChem(cell)
        assertTrue("handles in the formula: $substituted", substituted.contains(handles))
        assertFalse("no raw marker: $substituted", substituted.contains(CELL_CHEM_CLASS))
        assertFalse("no payload: $substituted", substituted.contains(pngPayload))

        // The text surface still sees the formula, now around the handles.
        assertEquals(CELL_FORMULA_MARKER, sanitizeTableCell(cell))

        // The HTML surface still typesets the formula, with the handles as its
        // text content.
        val html = tableCellHtml(cell) { sanitizeMathml(it) }
        assertTrue("formula kept: $html", html.contains("<math"))
        assertTrue("handles inside: $html", html.contains("ethanol"))
        assertFalse("no raw marker: $html", html.contains(CELL_CHEM_CLASS))
        assertFalse("no payload: $html", html.contains(pngPayload))
    }

    @Test
    fun `a marker inside an image tag cannot leak a payload or a handler`() {
        val element = chem(smiles = "CCO", formula = "C2H5OH", caption = "ethanol")
        // The markers sit inside the alt value of complete <img> tags; the
        // single quotes keep the outer attribute intact. One is a valid marker,
        // the other hostile (an unknown attribute).
        val marker = chemCellHtml(element)
        val hostile = "<span class=\"$CELL_CHEM_CLASS\" data-smiles=\"CCO\" " +
            "onclick=\"x()\">ethanol CCO</span>"
        val validCell = "<img src=\"$png\" alt='$marker'/>"
        val hostileCell = "<img src=\"$png\" alt='$hostile'/>"

        // The pure substitution still shows the valid marker's handles.
        val substituted = sanitizeCellChem(validCell)
        assertTrue(
            "valid marker becomes handles: $substituted",
            substituted.contains(escapeHtmlText(chemElementText(element)))
        )
        assertFalse("no raw marker left: $substituted", substituted.contains(CELL_CHEM_CLASS))
        assertFalse("no payload: $substituted", substituted.contains("data-png"))

        // The picture surfaces cannot print text that lived in an attribute:
        // both tags have a second attribute, so the existing grammar degrades
        // each to the visible marker. What matters is that neither the raw
        // marker, its base64 nor a handler reaches the page.
        val text = sanitizeTableCell(validCell + hostileCell)
        assertEquals("$CELL_IMAGE_MARKER$CELL_IMAGE_MARKER", text)
        val html = tableCellHtml(validCell + hostileCell) { sanitizeMathml(it) }
        assertEquals("$CELL_IMAGE_MARKER$CELL_IMAGE_MARKER", html)
        listOf(text, html).forEach { out ->
            assertFalse("no payload: $out", out.contains(pngPayload))
            assertFalse("no base64: $out", out.contains("base64,"))
            assertFalse("no handler: $out", out.contains("onclick"))
            assertFalse("no raw marker: $out", out.contains(CELL_CHEM_CLASS))
        }
    }

    // ---- the spoken and mixed surfaces ---------------------------------

    @Test
    fun `talkback speaks a chem cell's handles, never its attributes or payload`() {
        val element = chem(smiles = "CCO", formula = "C2H5OH", caption = "ethanol")
        val spoken = accessibleContentText(
            listOf(ContentElement.TableElement(listOf(listOf(chemCellHtml(element)))))
        )
        assertTrue("caption spoken: $spoken", spoken.contains("ethanol"))
        assertTrue("formula spoken: $spoken", spoken.contains("C2H5OH"))
        assertTrue("smiles spoken: $spoken", spoken.contains("CCO"))
        assertFalse("no attribute names: $spoken", spoken.contains("data-"))
        assertFalse("no payload: $spoken", spoken.contains(pngPayload))
        assertFalse("no marker: $spoken", spoken.contains(CELL_CHEM_CLASS))
    }

    @Test
    fun `the mixed page draws only the handles of a chem cell`() {
        val element = chem(smiles = "CCO", formula = "C2H5OH", caption = "ethanol")
        val html = mixedContentHtml(
            listOf(
                ContentElement.TableElement(
                    listOf(listOf("see " + chemCellHtml(element) + " here"))
                )
            ),
            16f,
            "#000000"
        )
        assertTrue("handles text: $html", html.contains(chemElementText(element)))
        assertFalse("no raw marker: $html", html.contains(CELL_CHEM_CLASS))
        assertFalse("no attribute leak: $html", html.contains("data-png"))
        assertFalse("no payload: $html", html.contains(pngPayload))
        assertFalse(
            "no handler attribute: $html",
            Regex("<[^>]*onclick", RegexOption.IGNORE_CASE).containsMatchIn(html)
        )
    }
}
