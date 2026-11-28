package com.mcqapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import com.mcqapp.data.export.CHEM_LABEL_GAP
import com.mcqapp.data.export.CellBlock
import com.mcqapp.data.export.MathSpan
import com.mcqapp.data.export.PdfPaperWriter
import com.mcqapp.data.export.TABLE_CELL_IMAGE_NOMINAL_PX
import com.mcqapp.data.export.TABLE_CELL_PAD
import com.mcqapp.data.export.buildCellBlocks
import com.mcqapp.data.export.cellBlocksHeight
import com.mcqapp.data.export.cellFlatText
import com.mcqapp.data.export.cellNaturalWidth
import com.mcqapp.data.export.chemElementText
import com.mcqapp.data.export.fitColumns
import com.mcqapp.data.export.releaseCellBlocks
import com.mcqapp.data.export.rowBands
import com.mcqapp.data.export.visibleLineRange
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.chemCellHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Rich content inside a PDF table cell: a portable picture and a captured
 * chemistry PNG are drawn in the grid, MathML is typeset through [MathSpan] and
 * fitted to its (possibly scaled) column, inline formatting survives a picture,
 * and nothing that cannot be drawn ever prints its base64 or a `[image]` /
 * `[formula]` marker where the reader could have seen the real thing.
 *
 * Robolectric because the assertions are on the span/bitmap/Layout and page
 * flow the writer actually produces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfTableCellContentTest {

    private fun paint() = TextPaint().apply {
        textSize = 10f
        color = Color.BLACK
        isAntiAlias = true
    }

    /** A real, decodable PNG data URI (a fake header would only pass isPngDataUri). */
    private fun pngDataUri(width: Int, height: Int): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        return "data:image/png;base64," +
            Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    /** A payload the strict signature check accepts but no decoder can read. */
    private fun brokenPngDataUri(): String {
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 1, 2, 3
        )
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    private fun imageTag(src: String) = "<img src=\"$src\"/>"

    private fun textOf(blocks: List<CellBlock>): String = blocks
        .filterIsInstance<CellBlock.Text>()
        .joinToString("") { it.layout.text.toString() }

    private fun spansOf(blocks: List<CellBlock>): List<MathSpan> {
        val out = mutableListOf<MathSpan>()
        for (block in blocks) {
            if (block !is CellBlock.Text) continue
            val text = block.layout.text as Spanned
            out += text.getSpans(0, text.length, MathSpan::class.java)
        }
        return out
    }

    private val formula =
        "<math><mrow><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></mrow></math>"

    /** The exact shape the buggy editor build stored: no `<math>` root. */
    private val formulaWithoutRoot =
        "<mrow><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></mrow>"

    /** The one text run of a cell that draws no picture. */
    private fun cellSpanned(cell: String, width: Float = 400f): Spanned {
        val blocks = buildCellBlocks(cell, width, paint())
        try {
            return blocks.filterIsInstance<CellBlock.Text>().single().layout.text as Spanned
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    // ------------------------------------------------------------------
    // Content grammar, formatting and fallbacks
    // ------------------------------------------------------------------

    /**
     * The owner-reported bug: a bare MathLive fragment stored in a cell used to
     * print as raw XML. The cell grammar repairs it, so the PDF gets one fitted
     * [MathSpan] and the prose around it, never the markup or a marker.
     */
    @Test
    fun aBareMathLiveFragmentInACellDrawsOneMathSpanBesideItsProse() {
        val cell = "Find x $formulaWithoutRoot now"
        val blocks = buildCellBlocks(cell, 200f, paint())
        try {
            val text = textOf(blocks)
            assertTrue("prose before survives: $text", text.contains("Find x"))
            assertTrue("prose after survives: $text", text.contains("now"))
            assertFalse("never raw markup: $text", text.contains("mrow"))
            assertFalse("never the fallback marker: $text", text.contains("[formula"))
            assertEquals("the repaired fragment is one MathSpan", 1, spansOf(blocks).size)
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    /**
     * `<mspace/>` is one empty atom: the sanitizer used to re-emit it as an
     * open `<mspace>` that swallowed every following sibling, so the PDF drew
     * a truncated formula.
     */
    @Test
    fun aMathSpaceInACellStillDrawsTheNextAtom() {
        val cell = "<mi>a</mi><mspace width=\"0.5em\"/><mi>b</mi>"
        val blocks = buildCellBlocks(cell, 300f, paint())
        try {
            val text = blocks.filterIsInstance<CellBlock.Text>().single().layout.text as Spanned
            val span = text.getSpans(0, text.length, MathSpan::class.java).single()
            val canvas = RecordingTextCanvas()
            span.draw(
                canvas, text,
                text.getSpanStart(span), text.getSpanEnd(span),
                0f, 0, 0, 0, paint()
            )
            assertTrue("the atom before the space is drawn: ${canvas.texts}",
                canvas.texts.contains("a"))
            assertTrue("the atom after the space is drawn: ${canvas.texts}",
                canvas.texts.contains("b"))
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    @Test
    fun textImageFormulaAndChemCellDrawsEverySupportedPiece() {
        val image = pngDataUri(40, 40)
        val chemPng = pngDataUri(30, 30)
        val chem = ContentElement.ChemElement(
            smiles = "CCO",
            formula = "C2H5OH",
            caption = "ethanol",
            diagram = chemPng
        )
        val cell = "Water " + formula + " " + imageTag(image) + " then " +
            chemCellHtml(chem)

        val blocks = buildCellBlocks(cell, 200f, paint())
        try {
            assertEquals(
                "one cell picture and one chemistry picture",
                2,
                blocks.count { it is CellBlock.Picture }
            )
            val text = textOf(blocks)
            assertFalse("a drawn picture must not print its marker", text.contains("[image]"))
            assertFalse("a typeset formula must not print its marker", text.contains("[formula]"))
            assertFalse("base64 must never reach the page", text.contains("data:image"))
            assertEquals("the formula is one MathSpan", 1, spansOf(blocks).size)
            assertTrue("the prose survives around the formula", text.contains("Water"))
            assertTrue("the prose after the picture survives", text.contains("then"))
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    /**
     * The same orphan-strip class as a bare max-height picture, but with prose
     * in the same cell: the text block's height must come out of the picture's
     * page budget, so the bottom of the picture is not sliced into the next
     * band/column.
     */
    @Test
    fun aPictureSharesItsBandWithTheCellsProse() {
        val png = pngDataUri(100, 800)
        val cell = "spectrum " + imageTag(png)
        val printable = PdfPaperWriter.BOTTOM - PdfPaperWriter.MARGIN - 2 * TABLE_CELL_PAD
        val blocks = buildCellBlocks(cell, TABLE_CELL_IMAGE_NOMINAL_PX, paint())
        try {
            assertEquals("prose block then picture", 2, blocks.size)
            val text = blocks[0] as CellBlock.Text
            val picture = blocks[1] as CellBlock.Picture
            assertTrue("the prose was laid out", text.height > 0f)
            assertEquals(
                "text, gap and picture fill exactly one band",
                printable.toDouble(),
                cellBlocksHeight(blocks).toDouble(),
                1.0
            )
            assertTrue(
                "the prose shrank the picture below the picture-only cap " +
                    "(picture=${picture.height} printable=$printable)",
                picture.height < printable - 1f
            )
            assertTrue("a sane floor was not hit", picture.height > 100f)
        } finally {
            releaseCellBlocks(blocks)
        }

        val sink = RecordingSink()
        val writer = PdfPaperWriter.Writer(sink, twoColumn = false)
        writer.table(listOf(listOf(cell)), indent = 0f)
        writer.finish()

        assertEquals("text + picture row stays on one page", 1, sink.pageCount)
        val drawn = sink.bitmaps.single()
        assertFalse("the bitmap must be alive while it is drawn", drawn.recycled)
        assertTrue("the picture starts inside its band", drawn.top >= drawn.clipTop - 0.5f)
        assertTrue(
            "the picture is complete, not sliced at the band bottom",
            drawn.top + drawn.height <= drawn.clipBottom + 0.5f
        )
    }

    /**
     * A `<b>` opened before a picture used to be lost: the cell was split at
     * the image and each chunk was styled alone, so both sides printed
     * unbolded. The run must carry its open tags across the picture.
     */
    @Test
    fun boldSurvivesOnBothSidesOfACellPicture() {
        val cell = "<b>see " + imageTag(pngDataUri(20, 20)) + " here</b>"
        val blocks = buildCellBlocks(cell, 200f, paint())
        try {
            val texts = blocks.filterIsInstance<CellBlock.Text>()
            assertEquals("prose before and after the picture", 2, texts.size)
            assertEquals("one picture between them", 1, blocks.count { it is CellBlock.Picture })
            for (block in texts) {
                val spanned = block.layout.text as Spanned
                val bold = spanned.getSpans(0, spanned.length, android.text.style.StyleSpan::class.java)
                    .filter { it.style == android.graphics.Typeface.BOLD }
                assertEquals(
                    "each side is one bold run: '${spanned}'",
                    1,
                    bold.size
                )
                assertEquals(0, spanned.getSpanStart(bold.single()))
                assertEquals(spanned.length, spanned.getSpanEnd(bold.single()))
            }
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    /**
     * Chem handles are prose too: an enclosing <b> that spans a preceding
     * picture must still style the handles printed after it.
     */
    @Test
    fun boldSurvivesAroundAChemHandleAfterAPicture() {
        val chem = ContentElement.ChemElement(
            smiles = "CCO", formula = "C2H5OH", caption = "ethanol", diagram = ""
        )
        val cell = "<b>see " + imageTag(pngDataUri(20, 20)) + " then " +
            chemCellHtml(chem) + "</b>"
        val blocks = buildCellBlocks(cell, 300f, paint())
        try {
            assertEquals("one picture", 1, blocks.count { it is CellBlock.Picture })
            val trailing = blocks.filterIsInstance<CellBlock.Text>().last()
            val spanned = trailing.layout.text as Spanned
            assertTrue(
                "the handles follow the picture in the same bold run",
                spanned.toString().contains("ethanol")
            )
            val bold = spanned.getSpans(0, spanned.length, android.text.style.StyleSpan::class.java)
                .filter { it.style == android.graphics.Typeface.BOLD }
            assertTrue("the handles are bold", bold.isNotEmpty())
            assertEquals(0, bold.minOf { spanned.getSpanStart(it) })
            assertEquals(spanned.length, bold.maxOf { spanned.getSpanEnd(it) })
            assertEquals(
                "bold covers every character (adjacent chunks may be separate spans)",
                spanned.length,
                bold.sumOf { spanned.getSpanEnd(it) - spanned.getSpanStart(it) }
            )
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    @Test
    fun hostileAndNonPortableSourcesKeepTheTextMarker() {
        val sources = listOf(
            "http://example.com/a.png",
            "file:///tmp/a.png",
            "data:image/svg+xml;base64,PHN2Zy8+",
            "data:image/png;base64,AA AA",
            "javascript:alert(1)"
        )
        for (src in sources) {
            val cell = imageTag(src)
            val blocks = buildCellBlocks("before $cell after", 200f, paint())
            try {
                assertEquals("no picture for $src", 0, blocks.count { it is CellBlock.Picture })
                val text = textOf(blocks)
                assertTrue("marker for $src", text.contains("[image]"))
                assertFalse("no payload for $src", text.contains("base64"))
                assertFalse(
                    "no source leaked for $src",
                    text.contains("http://") || text.contains("file://")
                )
                assertTrue(
                    "prose survives for $src",
                    text.contains("before") && text.contains("after")
                )
            } finally {
                releaseCellBlocks(blocks)
            }
        }

        // A second attribute is a hostile tag, not a lone source: the strict
        // renderer must refuse it and keep the prose around it.
        val hostile = "<img src=\"${pngDataUri(8, 8)}\" onerror=\"alert(1)\"/>"
        val hostileBlocks = buildCellBlocks("before $hostile after", 200f, paint())
        try {
            assertEquals(0, hostileBlocks.count { it is CellBlock.Picture })
            val hostileText = textOf(hostileBlocks)
            assertTrue(hostileText.contains("[image]"))
            assertFalse(hostileText.contains("data:image"))
            assertTrue(hostileText.contains("before") && hostileText.contains("after"))
        } finally {
            releaseCellBlocks(hostileBlocks)
        }
    }

    /**
     * A tag cut off before its closing quote has no delimited payload, so the
     * scanner (correctly) runs to the end of the cell: the tail is swallowed
     * with the torn tag. What matters is that the payload and the tail are not
     * printed as base64 prose and the prefix survives.
     */
    @Test
    fun aTruncatedPictureSwallowsItsTornTailByDesign() {
        val truncated = "<img src=\"data:image/png;base64,AAAA"
        val blocks = buildCellBlocks("before $truncated after", 200f, paint())
        try {
            assertEquals(0, blocks.count { it is CellBlock.Picture })
            assertEquals("before [image]", textOf(blocks))
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    @Test
    fun anUndecodableImagePrintsTheUnreadableLabel() {
        val payload = Base64.getEncoder().encodeToString("not an image".toByteArray())
        try {
            // Robolectric otherwise hands back a fake 100x100 bitmap for any
            // bytes, which would make the decode path look successful.
            ShadowBitmapFactory.setAllowInvalidImageData(false)
            val blocks = buildCellBlocks(
                imageTag("data:image/png;base64,$payload"), 200f, paint()
            )
            try {
                assertEquals(0, blocks.count { it is CellBlock.Picture })
                assertEquals("[unreadable image]", textOf(blocks))
                assertFalse(textOf(blocks).contains("[image]"))
                assertFalse("the payload is never printed", textOf(blocks).contains(payload))
            } finally {
                releaseCellBlocks(blocks)
            }
        } finally {
            ShadowBitmapFactory.setAllowInvalidImageData(true)
        }
    }

    @Test
    fun twoPicturesInOneCellKeepTheirOrder() {
        val first = imageTag(pngDataUri(20, 20))
        val second = imageTag(pngDataUri(30, 10))
        val blocks = buildCellBlocks("$first middle $second", 200f, paint())
        try {
            assertEquals(2, blocks.count { it is CellBlock.Picture })
            val types = blocks.map { it::class }
            assertEquals(CellBlock.Picture::class, types[0])
            assertEquals(CellBlock.Text::class, types[1])
            assertEquals(CellBlock.Picture::class, types[2])
            assertEquals("middle", (blocks[1] as CellBlock.Text).layout.text.toString().trim())
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    @Test
    fun aPictureInsideAFormulaIsAMarkerNotAPayload() {
        val cell = "<math><mrow><mi>x</mi><mo>+</mo>" +
            imageTag(pngDataUri(8, 8)) + "</mrow></math>"
        val blocks = buildCellBlocks(cell, 400f, paint())
        try {
            val text = blocks.filterIsInstance<CellBlock.Text>().single().layout.text as Spanned
            val span = spansOf(blocks).single()
            val ink = RecordingTextCanvas()
            span.draw(ink, text, 0, 1, 0f, 0, 10, 20, paint())
            val drawn = ink.texts.joinToString("")
            assertTrue("the picture marker is typeset inside the formula", drawn.contains("[image]"))
            assertFalse("no base64 reaches the formula ink", drawn.contains("data:image"))
            assertFalse(drawn.contains("AAAA"))
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    // ------------------------------------------------------------------
    // Sizing and column geometry
    // ------------------------------------------------------------------

    @Test
    fun aPortablePictureIsScaledToItsColumn() {
        // 2000 px wide on a 100 px column: the drawn extent must fit the cell.
        val blocks = buildCellBlocks(imageTag(pngDataUri(2000, 100)), 100f, paint())
        try {
            val picture = blocks.single() as CellBlock.Picture
            assertTrue("scaled width ${picture.width} must fit the column", picture.width <= 100f)
            assertEquals("the aspect ratio is kept", 100f, picture.width, 1f)
            assertEquals("the height follows the ratio", 5f, picture.height, 1f)
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    @Test
    fun aSmallPictureIsNeverUpscaled() {
        val blocks = buildCellBlocks(imageTag(pngDataUri(8, 8)), 200f, paint())
        try {
            val picture = blocks.single() as CellBlock.Picture
            assertEquals(8f, picture.width, 0.01f)
            assertEquals(8f, picture.height, 0.01f)
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    @Test
    fun aTallPictureIsClampedToThePrintableHeight() {
        val blocks = buildCellBlocks(imageTag(pngDataUri(100, 2000)), 120f, paint())
        try {
            val picture = blocks.single() as CellBlock.Picture
            val maxHeight = PdfPaperWriter.BOTTOM - PdfPaperWriter.MARGIN - 2 * TABLE_CELL_PAD
            assertTrue("clamped to one band: ${picture.height}", picture.height <= maxHeight + 0.5f)
            assertTrue("not shrunk into a sliver", picture.height > maxHeight - 2f)
            assertTrue("the width follows the ratio", picture.width < 40f)
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    /**
     * A scaled table: the formula's natural width is larger than the column it
     * lands in. A ReplacementSpan cannot wrap, so it is built at a size that
     * fits, not painted over the neighbour — but below the legibility floor it
     * is not shrunk any further. The cell prints the same linear fallback the
     * body uses instead of a clipped 4 pt span.
     */
    @Test
    fun aFormulaTooWideForItsScaledColumnFallsBackToLinearText() {
        val wide = "<math><mrow><mfrac><mn>" + "1234567890".repeat(6) +
            "</mn><mn>" + "0987654321".repeat(6) + "</mn></mfrac><mo>+</mo>" +
            "<mn>1234567890</mn></mrow></math>"
        val prose = "a prose column long enough to force the formula column to scale down".repeat(10)
        val p = paint()
        val natural = floatArrayOf(
            cellNaturalWidth(wide, p) + 2 * TABLE_CELL_PAD,
            cellNaturalWidth(prose, p) + 2 * TABLE_CELL_PAD
        )
        val scaled = fitColumns(natural, PdfPaperWriter.CONTENT_W)
        val contentWidth = scaled[0] - 2 * TABLE_CELL_PAD
        val formulaWidth = cellNaturalWidth(wide, p)
        assertTrue(
            "the repro needs a scaled column narrower than the natural formula " +
                "(formula=$formulaWidth column=$contentWidth natural=${natural.toList()})",
            contentWidth < formulaWidth
        )

        val blocks = buildCellBlocks(wide, contentWidth, p)
        try {
            assertTrue(
                "no span below the 6 pt legibility floor may be built",
                spansOf(blocks).isEmpty()
            )
            val text = textOf(blocks)
            assertTrue("the linear fallback is printed: $text", text.contains("[formula:"))
            assertFalse("not the bare marker", text.trim() == "[formula]")
            assertTrue("the formula's readable text survives: $text", text.contains("1234567890"))
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    /**
     * The 6 pt decision only rejects a formula the column cannot hold at a
     * legible size. A formula that fits keeps the real span and its native
     * layout — the floor must not turn every cell into linear text.
     */
    @Test
    fun aFormulaThatFitsItsColumnKeepsTheNativeSpan() {
        val blocks = buildCellBlocks("y $formula", 400f, paint())
        try {
            val span = spansOf(blocks).single()
            assertTrue("a fitting formula is typeset at a legible size", span.sizePx >= 6f)
            assertFalse("a legible formula keeps its native layout", textOf(blocks).contains("[formula"))
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    @Test
    fun columnMeasurementReservesRoomForAPictureAndAFormula() {
        val p = paint()
        val textOnly = cellNaturalWidth("hi", p)
        val imageCell = cellNaturalWidth("hi ${imageTag(pngDataUri(8, 8))}", p)
        assertEquals(TABLE_CELL_IMAGE_NOMINAL_PX, imageCell, 0.01f)
        assertTrue(imageCell > textOnly)

        val formulaCell = cellNaturalWidth("x $formula", p)
        val formulaWidth = cellSpanned("x $formula").let { text ->
            // Compare against the span's own width, not the marker's two chars.
            val span = text.getSpans(0, text.length, MathSpan::class.java).single()
            span!!.getSize(p, text, 0, 1, null).toFloat()
        }
        assertTrue(
            "the measured column must cover the real formula width ($formulaWidth)",
            formulaCell >= formulaWidth
        )
        assertTrue(formulaCell > textOnly)
    }

    @Test
    fun chemColumnMeasurementUsesTheNominalWidthOnlyWhenAPngExists() {
        val withPng = ContentElement.ChemElement(
            smiles = "CCO", caption = "e", diagram = pngDataUri(40, 40)
        )
        val withoutPng = withPng.copy(diagram = "")
        val p = paint()
        assertEquals(
            TABLE_CELL_IMAGE_NOMINAL_PX,
            cellNaturalWidth(chemCellHtml(withPng), p),
            0.01f
        )
        val handlesWidth = cellNaturalWidth(chemElementText(withoutPng), p)
        assertTrue("the handles have a real width", handlesWidth > 0f)
        assertEquals(
            "without a PNG the cell measures as its handles text",
            handlesWidth,
            cellNaturalWidth(chemCellHtml(withoutPng), p),
            0.5f
        )
        assertTrue(handlesWidth < TABLE_CELL_IMAGE_NOMINAL_PX)
    }

    // ------------------------------------------------------------------
    // Answer-key flattening
    // ------------------------------------------------------------------

    @Test
    fun theAnswerKeyFlatteningUsesLinearFormulaText() {
        val cell = "x <math><mrow><msup><mi>a</mi><mn>2</mn></msup></mrow></math>"
        val flat = cellFlatText(cell)
        assertTrue("the formula linearises to 'a2' in '$flat'", flat.contains("a2"))
        assertFalse(flat.contains("[formula]"))
    }

    /**
     * The answer key is read as plain text. A structure cell must contribute
     * the same readable handles every other text surface prints, never the
     * storage marker or its captured base64 payload.
     */
    @Test
    fun theAnswerKeyFlattensAStructureCellToItsHandles() {
        val element = ContentElement.ChemElement(
            smiles = "CCO",
            formula = "C2H5OH",
            caption = "ethanol",
            spoken = "ethanol",
            diagram = pngDataUri(8, 8)
        )
        val flat = cellFlatText(chemCellHtml(element))
        assertEquals("ethanol C2H5OH CCO", flat)
        assertFalse("no marker class in '$flat'", flat.contains("mcq-chem-cell"))
        assertFalse("no marker attribute in '$flat'", flat.contains("data-smiles"))
        assertFalse("no base64 in '$flat'", flat.contains("data:image"))
        assertFalse("no captured payload in '$flat'", flat.contains(element.diagram.substringAfter(',')))
        assertFalse("the structure is not an image marker", flat.contains("[image]"))
    }

    @Test
    fun theAnswerKeyFlatteningMarksAPictureInsideAFormula() {
        val cell = "<math><mrow><mi>x</mi>" +
            imageTag("data:image/png;base64,AAAA") + "</mrow></math>"
        val flat = cellFlatText(cell)
        assertTrue("the picture is marked in '$flat'", flat.contains("[image]"))
        assertFalse(flat.contains("AAAA"))
        assertFalse(flat.contains("data:"))
    }

    // ------------------------------------------------------------------
    // Page flow and canvas output
    // ------------------------------------------------------------------

    /**
     * Records bitmap draws with the clip in force; clips are page-space.
     *
     * Robolectric's StaticLayout draw does not dispatch text to Canvas, so text
     * coverage is asserted through [visibleLineRange]/[rowBands] instead of a
     * recorded `drawText`; a bitmap draw is a direct writer call and observable.
     */
    private class RecordingSink : PdfPaperWriter.PdfPageSink {
        data class BitmapDraw(
            val left: Float,
            val top: Float,
            val width: Int,
            val height: Int,
            val page: Int,
            val clipTop: Float,
            val clipBottom: Float,
            val recycled: Boolean
        )

        data class TextDraw(
            val text: String,
            val left: Float,
            val baseline: Float,
            val top: Float,
            val bottom: Float
        )

        val bitmaps = mutableListOf<BitmapDraw>()
        val texts = mutableListOf<TextDraw>()
        /** Every clipRect the writer issued: left, top, right, bottom. */
        val clips = mutableListOf<FloatArray>()
        var pageCount = 0

        override fun startPage(pageNum: Int): PdfPaperWriter.PdfPageHost {
            pageCount = pageNum
            return object : PdfPaperWriter.PdfPageHost {
                override val canvas = RecordingCanvas(pageNum)
                override fun finish() {}
            }
        }

        private inner class RecordingCanvas(private val page: Int) : Canvas() {
            private val saved = ArrayDeque<Pair<Float, Float>>()
            private var clipTop = 0f
            private var clipBottom = Float.MAX_VALUE

            override fun save(): Int {
                saved.addLast(clipTop to clipBottom)
                return super.save()
            }

            override fun restore() {
                if (saved.isNotEmpty()) {
                    val (top, bottom) = saved.removeLast()
                    clipTop = top
                    clipBottom = bottom
                }
                super.restore()
            }

            override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
                clips += floatArrayOf(left, top, right, bottom)
                clipTop = maxOf(clipTop, top)
                clipBottom = minOf(clipBottom, bottom)
                return true
            }

            override fun drawBitmap(
                bitmap: Bitmap,
                left: Float,
                top: Float,
                paint: Paint?
            ) {
                bitmaps += BitmapDraw(
                    left, top, bitmap.width, bitmap.height, page,
                    clipTop, clipBottom, bitmap.isRecycled
                )
            }

            override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                val fm = paint.fontMetrics
                texts += TextDraw(text, x, y, y + fm.ascent, y + fm.descent)
            }
        }
    }

    /** Captures strings a span draws, for assertions on MathSpan ink. */
    private class RecordingTextCanvas : Canvas() {
        val texts = mutableListOf<String>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts += text
        }
    }

    @Test
    fun anImageIsDrawnInsideItsCell() {
        val sink = RecordingSink()
        val writer = PdfPaperWriter.Writer(sink, twoColumn = false)
        writer.table(listOf(listOf(imageTag(pngDataUri(2000, 100)))), indent = 0f)
        writer.finish()

        val drawn = sink.bitmaps.single()
        // One column starts at MARGIN; the picture sits inside the cell's
        // padding, and its scaled extent stays within the nominal 120 px
        // content box (the column is 120 + 2*pad wide).
        assertEquals(PdfPaperWriter.MARGIN + TABLE_CELL_PAD, drawn.left, 0.5f)
        assertTrue("drawn above the flow start", drawn.top >= PdfPaperWriter.MARGIN - 0.5f)
        assertTrue("scaled to the column", drawn.width <= TABLE_CELL_IMAGE_NOMINAL_PX.toInt())
        assertEquals("height follows the 20:1 ratio", 5.0, drawn.height.toDouble(), 1.0)
        assertTrue(
            "the picture stays inside its padded cell",
            drawn.left + drawn.width <=
                PdfPaperWriter.MARGIN + TABLE_CELL_IMAGE_NOMINAL_PX + 2 * TABLE_CELL_PAD + 0.5f
        )
        assertFalse("the bitmap must be alive while it is drawn", drawn.recycled)
        // The drawing is clipped one pixel inside the cell's outer border, not
        // the page: a glyph overhang may use the padding, but nothing can paint
        // over the border/neighbour.
        val clip = sink.clips.last()
        assertEquals(
            "clipped one pixel inside the cell border",
            PdfPaperWriter.MARGIN + TABLE_CELL_IMAGE_NOMINAL_PX + 2 * TABLE_CELL_PAD - 1f,
            clip[2],
            0.5f
        )
        assertTrue("not a page-wide clip", clip[2] < 595f)
        assertTrue(
            "the flow ended inside the printable area",
            writer.finalY() in PdfPaperWriter.MARGIN.toFloat()..(PdfPaperWriter.BOTTOM + 0.5f)
        )
    }

    /**
     * A portrait picture at the height cap plus the row's padding used to be
     * 8 px taller than a page, so it always banded and its bottom sliver
     * landed in the next column/page. It must fit one band, drawn whole.
     */
    @Test
    fun aPortraitPictureOnlyRowFitsOneBandComplete() {
        val png = pngDataUri(100, 800)
        val blocks = buildCellBlocks(imageTag(png), TABLE_CELL_IMAGE_NOMINAL_PX, paint())
        val expectedHeight = (blocks.single() as CellBlock.Picture).height.toInt()
        val maxHeight = (PdfPaperWriter.BOTTOM - PdfPaperWriter.MARGIN - 2 * TABLE_CELL_PAD).toInt()
        assertEquals(
            "clamped to the page budget",
            maxHeight.toDouble(),
            expectedHeight.toDouble(),
            1.0
        )
        releaseCellBlocks(blocks)

        val sink = RecordingSink()
        val writer = PdfPaperWriter.Writer(sink, twoColumn = false)
        writer.table(listOf(listOf(imageTag(png))), indent = 0f)
        writer.finish()

        assertEquals("a picture-only row must stay on one page", 1, sink.pageCount)
        val drawn = sink.bitmaps.single()
        assertFalse("the bitmap must be alive while it is drawn", drawn.recycled)
        assertEquals("the complete scaled bitmap is drawn", expectedHeight, drawn.height)
        assertTrue("drawn inside its band", drawn.top >= drawn.clipTop - 0.5f)
        assertTrue(
            "not sliced at the band bottom",
            drawn.top + drawn.height <= drawn.clipBottom + 0.5f
        )
    }

    /**
     * A picture is a block between two prose blocks, and the writer draws the
     * blocks in order: prose before the image, prose after it. The block order
     * is the draw order (drawCellBlocks walks the list top to bottom), which is
     * what Robolectric cannot observe for text.
     */
    @Test
    fun cellBlocksKeepProsePictureProseOrder() {
        val cell = "before <b>bold</b> " + imageTag(pngDataUri(20, 20)) + " after"
        val blocks = buildCellBlocks(cell, 200f, paint())
        try {
            assertEquals(3, blocks.size)
            assertEquals("before bold ", textOf(blocks.subList(0, 1)))
            assertTrue(blocks[1] is CellBlock.Picture)
            assertEquals(" after", textOf(blocks.subList(2, 3)))
        } finally {
            releaseCellBlocks(blocks)
        }

        // The picture still reaches the writer's canvas at the position the
        // blocks give it.
        val sink = RecordingSink()
        val writer = PdfPaperWriter.Writer(sink, twoColumn = false)
        writer.table(listOf(listOf(cell)), indent = 0f)
        writer.finish()
        assertEquals(1, sink.bitmaps.size)
    }

    @Test
    fun aFormulaAndAChemPngReachTheCanvas() {
        val chem = ContentElement.ChemElement(
            smiles = "CCO",
            formula = "C2H5OH",
            caption = "ethanol",
            diagram = pngDataUri(30, 30)
        )
        val cell = formula + " " + chemCellHtml(chem)
        val blocks = buildCellBlocks(cell, 300f, paint())
        try {
            val text = blocks.filterIsInstance<CellBlock.Text>().single().layout.text as Spanned
            val span = text.getSpans(0, text.length, MathSpan::class.java).single()
            val ink = RecordingTextCanvas()
            span!!.draw(ink, text, 0, 1, 0f, 0, 10, 20, paint())
            assertTrue("the formula draws its children", ink.texts.isNotEmpty())
            assertTrue("the chem PNG is a picture block", blocks.any { it is CellBlock.Picture })
        } finally {
            releaseCellBlocks(blocks)
        }

        val sink = RecordingSink()
        val writer = PdfPaperWriter.Writer(sink, twoColumn = false)
        writer.table(listOf(listOf(cell)), indent = 0f)
        writer.finish()
        assertTrue("the chem PNG reaches the page as a bitmap", sink.bitmaps.isNotEmpty())
    }

    @Test
    fun aReactionCellDrawsItsPictureAndProductLabelOnOneBlock() {
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = "A",
            caption = "LiAlH₄ / ether",
            spoken = "lactone reduced by LiAlH4 and ether",
            // A wide scheme: it must shrink to leave the label its room.
            diagram = pngDataUri(2000, 100)
        )
        val blocks = buildCellBlocks(
            com.mcqapp.util.chemCellHtml(reaction), 300f, paint()
        )
        try {
            val scheme = blocks.single() as CellBlock.LabelledPicture
            assertEquals("the label rides the picture's block", "A", scheme.label.text.toString())
            val labelWidth = Layout.getDesiredWidth(scheme.label.text, paint())
            assertTrue(
                "the picture leaves room for the label: picture=${scheme.width}, " +
                    "label=$labelWidth, width=300",
                scheme.width + CHEM_LABEL_GAP + labelWidth <= 300f + 1f
            )
            assertTrue(
                "the picture still scales down from its 2000 px natural width",
                scheme.width < 300f
            )
            assertEquals(
                "the block is as tall as the taller of picture and label",
                maxOf(scheme.bitmap.height.toFloat(), scheme.label.height.toFloat()),
                scheme.height,
                0f
            )
            assertFalse(
                "the handles are not printed beside the picture",
                textOf(blocks).contains("O=C1OCCCCC(=O)C1>>")
            )
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    @Test
    fun aReactionCellHeightTakesTheTallerOfPictureAndLabel() {
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = "A",
            caption = "LiAlH₄ / ether",
            // 200:1, so the scaled picture is far shorter than the label line.
            diagram = pngDataUri(2000, 10)
        )
        val blocks = buildCellBlocks(
            com.mcqapp.util.chemCellHtml(reaction), 300f, paint()
        )
        try {
            val scheme = blocks.single() as CellBlock.LabelledPicture
            assertTrue(
                "the fixture needs the label to be the taller side",
                scheme.label.height > scheme.bitmap.height
            )
            assertEquals(
                "the cell height is the label's, not the picture's",
                scheme.label.height.toFloat(),
                scheme.height,
                0f
            )
            assertEquals(scheme.height, cellBlocksHeight(blocks), 0f)
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    @Test
    fun aReactionCellDrawsItsLabelBesideThePicture() {
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = "A",
            caption = "LiAlH₄ / ether",
            diagram = pngDataUri(2000, 300)
        )
        val cell = com.mcqapp.util.chemCellHtml(reaction)
        val p = paint()
        val contentWidth = fitColumns(
            floatArrayOf(cellNaturalWidth(cell, p) + 2 * TABLE_CELL_PAD),
            PdfPaperWriter.CONTENT_W
        )[0] - 2 * TABLE_CELL_PAD
        val scheme = buildCellBlocks(cell, contentWidth, p).single() as CellBlock.LabelledPicture
        val blockHeight = scheme.height
        releaseCellBlocks(listOf(scheme))

        val sink = RecordingSink()
        val writer = PdfPaperWriter.Writer(sink, twoColumn = false)
        writer.table(listOf(listOf(cell)), indent = 0f)
        writer.finish()

        val picture = sink.bitmaps.single()
        val label = sink.texts.single { it.text == "A" }
        val contentTop = PdfPaperWriter.MARGIN + TABLE_CELL_PAD
        assertTrue(
            "the label starts at or after the picture's right edge: " +
                "label x=${label.left}, picture right=${picture.left + picture.width}",
            label.left >= picture.left + picture.width
        )
        assertTrue(
            "the label stays in the scheme's block band: label y=${label.baseline}, " +
                "block ${contentTop}..${contentTop + blockHeight}",
            label.baseline >= contentTop - 0.5f && label.baseline <= contentTop + blockHeight + 0.5f
        )
        val pictureCentre = picture.top + picture.height / 2f
        val blockCentre = contentTop + blockHeight / 2f
        assertEquals(
            "the picture is centred in the scheme's block: picture=$pictureCentre block=$blockCentre",
            blockCentre.toDouble(),
            pictureCentre.toDouble(),
            1.0
        )
    }

    /**
     * A flat picture is shorter than the label line. Both are centred inside
     * the block, so the label must start at the cell's content top, never
     * above it (where the cell clip would erase it).
     */
    @Test
    fun aFlatReactionCellKeepsATallerLabelInsideTheCell() {
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = "A",
            caption = "LiAlH₄ / ether",
            diagram = pngDataUri(2000, 10)
        )
        val cell = com.mcqapp.util.chemCellHtml(reaction)
        val p = paint()
        val contentWidth = fitColumns(
            floatArrayOf(cellNaturalWidth(cell, p) + 2 * TABLE_CELL_PAD),
            PdfPaperWriter.CONTENT_W
        )[0] - 2 * TABLE_CELL_PAD
        val scheme = buildCellBlocks(cell, contentWidth, p).single() as CellBlock.LabelledPicture
        assertTrue(
            "the fixture needs the label to be the taller side",
            scheme.label.height > scheme.bitmap.height
        )
        val blockHeight = scheme.height
        releaseCellBlocks(listOf(scheme))

        val sink = RecordingSink()
        val writer = PdfPaperWriter.Writer(sink, twoColumn = false)
        writer.table(listOf(listOf(cell)), indent = 0f)
        writer.finish()

        val picture = sink.bitmaps.single()
        val label = sink.texts.single { it.text == "A" }
        val contentTop = PdfPaperWriter.MARGIN + TABLE_CELL_PAD
        assertTrue(
            "the label baseline starts at/inside the cell content top: label y=${label.baseline}, " +
                "expected at least $contentTop",
            label.baseline >= contentTop - 0.5f
        )
        assertTrue(
            "the label stays inside the cell band: label y=${label.baseline}, " +
                "band ${contentTop}..${contentTop + blockHeight}",
            label.baseline <= contentTop + blockHeight + 0.5f
        )
        assertTrue(
            "the shorter picture is centred inside the block, never above its top: " +
                "picture=${picture.top}..${picture.top + picture.height}",
            picture.top >= contentTop - 0.5f
        )
    }

    @Test
    fun twoStructuresInOneCellKeepTheirOrderAndABudgetedHeight() {
        val labelled = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = "A",
            caption = "LiAlH₄ / ether",
            diagram = pngDataUri(2000, 100)
        )
        val plain = ContentElement.ChemElement(
            smiles = "CCO",
            formula = "C2H5OH",
            caption = "ethanol",
            // Tall enough that an over-allocated share shows up as an
            // over-tall row, not as a harmless natural-size picture. Kept
            // small in pixels so the marker's payload stays under the cell
            // raster cap on a host whose PNG compressor is a pass-through.
            diagram = pngDataUri(40, 400)
        )
        // Grow the label until the same measurement the writer uses makes it
        // too wide to leave a 48 px picture beside it. Deriving it from the
        // host's font metrics keeps the stacked path exercised everywhere.
        val p = paint()
        val cellWidth = 400f
        var longLabel = "W".repeat(10)
        while (Layout.getDesiredWidth(longLabel, p) <
            cellWidth - CHEM_LABEL_GAP - 48f
        ) {
            longLabel += "W".repeat(10)
            check(longLabel.length < 10_000) { "label never exceeded the picture floor" }
        }
        val stacked = labelled.copy(formula = longLabel, diagram = pngDataUri(40, 400))
        val mixed = buildCellBlocks(
            com.mcqapp.util.chemCellHtml(stacked) + com.mcqapp.util.chemCellHtml(plain),
            cellWidth, p
        )
        try {
            // The first label cannot leave a 48 px picture here, so it takes
            // the stacked path: picture, label text, then the second picture.
            assertEquals(3, mixed.size)
            assertTrue(mixed[0] is CellBlock.Picture)
            assertEquals(longLabel, (mixed[1] as CellBlock.Text).layout.text.toString())
            assertTrue(mixed[2] is CellBlock.Picture)
            val printable =
                PdfPaperWriter.BOTTOM - PdfPaperWriter.MARGIN - 2 * TABLE_CELL_PAD
            assertTrue(
                "the stacked label must reduce the pictures' budget: " +
                    "height=${cellBlocksHeight(mixed)}, printable=$printable",
                cellBlocksHeight(mixed) <= printable
            )
            mixed.filterIsInstance<CellBlock.Picture>().forEach {
                assertTrue("each picture keeps a positive height", it.height >= 1f)
            }
        } finally {
            releaseCellBlocks(mixed)
        }

        val second = labelled.copy(formula = "B")
        val both = buildCellBlocks(
            com.mcqapp.util.chemCellHtml(labelled) + com.mcqapp.util.chemCellHtml(second),
            400f, paint()
        )
        try {
            assertEquals(2, both.size)
            val schemes = both.filterIsInstance<CellBlock.LabelledPicture>()
            assertEquals(listOf("A", "B"), schemes.map { it.label.text.toString() })
            schemes.forEach {
                assertEquals(
                    "a labelled block is as tall as its taller side",
                    maxOf(it.bitmap.height.toFloat(), it.label.height.toFloat()).toDouble(),
                    it.height.toDouble(),
                    0.01
                )
            }
        } finally {
            releaseCellBlocks(both)
        }
    }

    @Test
    fun aReactionCellWithAnUndecodablePngPrintsTextThenTheLabel() {
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = "A",
            caption = "LiAlH₄ / ether",
            diagram = brokenPngDataUri()
        )
        val blocks = buildCellBlocks(
            com.mcqapp.util.chemCellHtml(reaction), 300f, paint()
        )
        try {
            assertEquals("the unreadable notice and the label", 2, blocks.size)
            val notice = blocks[0] as CellBlock.Text
            assertTrue(
                "the picture failure is readable text",
                notice.layout.text.toString().contains("[unreadable image]")
            )
            val label = blocks[1] as CellBlock.Text
            assertEquals("the label still follows", "A", label.layout.text.toString())
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    /**
     * A caller can hand the block builder a zero or negative width; the
     * layout must fall back inertly rather than crash or emit a negative
     * picture extent.
     */
    @Test
    fun aZeroOrNegativeCellWidthFallsBackWithoutCrashing() {
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = "A",
            caption = "LiAlH₄ / ether",
            diagram = pngDataUri(2000, 100)
        )
        val cell = com.mcqapp.util.chemCellHtml(reaction)
        for (width in listOf(0f, -10f)) {
            val blocks = buildCellBlocks(cell, width, paint())
            try {
                assertTrue("width=$width still yields the label", textOf(blocks).contains("A"))
                blocks.filterIsInstance<CellBlock.Picture>().forEach {
                    assertTrue("width=$width: positive picture width", it.width >= 1f)
                    assertTrue("width=$width: positive picture height", it.height >= 1f)
                }
            } finally {
                releaseCellBlocks(blocks)
            }
        }
    }

    /**
     * A label too wide to leave a legible picture keeps the old stacked form:
     * the picture on its own line, then the label text below. The documented
     * floor is 48 px.
     */
    @Test
    fun aVeryLongReactionLabelFallsBackToTheStackedLayout() {
        val longLabel = "product A after reduction and work-up"
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = longLabel,
            caption = "LiAlH₄ / ether",
            diagram = pngDataUri(2000, 100)
        )
        // A narrow cell leaves the label no legible picture width on either
        // measurement path (Robolectric's own text metrics vary by host).
        val blocks = buildCellBlocks(
            com.mcqapp.util.chemCellHtml(reaction), 80f, paint()
        )
        try {
            assertEquals("the picture and the label are separate blocks", 2, blocks.size)
            val picture = blocks[0] as CellBlock.Picture
            assertEquals(
                "the picture keeps the full column when the label cannot fit beside it",
                80f,
                picture.width,
                1f
            )
            val label = blocks[1] as CellBlock.Text
            assertEquals("the label follows below", longLabel, label.layout.text.toString())
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    @Test
    fun aReactionCellMeasuresItsProductLabelBeyondTheNominalPictureWidth() {
        val p = paint()
        // One unbroken token measures predictably on every Robolectric host;
        // this one is long enough even where the test font reports one pixel
        // per glyph.
        val longLabel = "product" + "A".repeat(140)
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = longLabel,
            caption = "LiAlH₄ / ether",
            diagram = pngDataUri(30, 30)
        )
        val labelWidth = Layout.getDesiredWidth(longLabel, p)
        assertTrue(
            "the fixture label must be wider than the nominal picture column: $labelWidth",
            labelWidth > TABLE_CELL_IMAGE_NOMINAL_PX
        )
        assertEquals(
            "without a label the cell measures as the picture column",
            TABLE_CELL_IMAGE_NOMINAL_PX,
            cellNaturalWidth(chemCellHtml(reaction.copy(formula = "")), p),
            0.01f
        )
        assertEquals(
            "the measured column must cover the product label",
            labelWidth,
            cellNaturalWidth(chemCellHtml(reaction), p),
            0.5f
        )
        // The sample's short "A" fits inside the nominal column and still draws.
        assertEquals(
            TABLE_CELL_IMAGE_NOMINAL_PX,
            cellNaturalWidth(chemCellHtml(reaction.copy(formula = "A")), p),
            0.01f
        )
    }

    @Test
    fun anInvalidStoredPngCellFallsBackToItsHandles() {
        val payload = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val marker = "<span class=\"mcq-chem-cell\" data-smiles=\"CCO\" " +
            "data-formula=\"C2H5OH\" data-caption=\"ethanol\" data-png=\"$payload\">" +
            "ethanol C2H5OH CCO</span>"
        val blocks = buildCellBlocks(marker, 300f, paint())
        try {
            assertEquals("an invalid payload is not a picture", 0,
                blocks.count { it is CellBlock.Picture })
            val text = textOf(blocks)
            assertEquals("ethanol C2H5OH CCO", text)
            assertFalse("no data URI may reach the page", text.contains("data:image"))
        } finally {
            releaseCellBlocks(blocks)
        }
    }

    @Test
    fun aPictureColumnScaledBelowItsNominalWidthDrawsAtThatWidth() {
        val png = pngDataUri(2000, 100)
        val prose = "a long prose column that makes the picture column scale below its nominal width".repeat(10)
        val p = paint()
        val natural = floatArrayOf(
            cellNaturalWidth(imageTag(png), p) + 2 * TABLE_CELL_PAD,
            cellNaturalWidth(prose, p) + 2 * TABLE_CELL_PAD
        )
        val scaled = fitColumns(natural, PdfPaperWriter.CONTENT_W)
        val contentWidth = scaled[0] - 2 * TABLE_CELL_PAD
        assertTrue(
            "the repro needs a scaled picture column (column=$contentWidth natural=${natural.toList()})",
            contentWidth < TABLE_CELL_IMAGE_NOMINAL_PX
        )

        val sink = RecordingSink()
        val writer = PdfPaperWriter.Writer(sink, twoColumn = false)
        writer.table(listOf(listOf(imageTag(png), prose)), indent = 0f)
        writer.finish()

        val drawn = sink.bitmaps.single()
        assertEquals(
            "the picture is sized from its scaled column",
            contentWidth.toDouble(),
            drawn.width.toDouble(),
            1.0
        )
        assertFalse("the bitmap must be alive while it is drawn", drawn.recycled)
    }

    @Test
    fun aTallPictureRowBandsWithoutLosingContent() {
        val giant = (1..210).joinToString("\n") { "cell line $it| taller than any page" }
        val image = imageTag(pngDataUri(200, 200))
        val p = paint()
        val natural = floatArrayOf(
            cellNaturalWidth(giant, p) + 2 * TABLE_CELL_PAD,
            cellNaturalWidth("small", p) + 2 * TABLE_CELL_PAD,
            cellNaturalWidth(image, p) + 2 * TABLE_CELL_PAD
        )
        val columnWidth = fitColumns(natural, PdfPaperWriter.CONTENT_W)[0] - 2 * TABLE_CELL_PAD
        val giantBlocks = buildCellBlocks(giant, columnWidth, p)
        val layout = (giantBlocks.single() as CellBlock.Text).layout
        val contentH = cellBlocksHeight(giantBlocks)
        val bands = rowBands(
            contentH,
            PdfPaperWriter.MARGIN.toFloat(),
            PdfPaperWriter.MARGIN.toFloat(),
            PdfPaperWriter.BOTTOM,
            TABLE_CELL_PAD
        )
        try {
            assertTrue("a giant row must band over several pages", bands.size >= 3)
            assertEquals(
                "the bands cover the whole cell without a gap",
                contentH,
                bands.last().endInclusive,
                0.5f
            )

            // Every band shows its own lines, and together they cover every
            // line of the layout — no band is empty, none duplicates a line.
            val covered = mutableSetOf<Int>()
            for (band in bands) {
                val lines = visibleLineRange(layout, 0f, band.start, band.endInclusive)
                assertTrue("band $band shows no line", lines != null)
                covered += lines!!.toList()
            }
            assertEquals(
                "every layout line is drawn exactly by one band's range",
                (0 until layout.lineCount).toSet(),
                covered
            )
        } finally {
            releaseCellBlocks(giantBlocks)
        }

        val sink = RecordingSink()
        val writer = PdfPaperWriter.Writer(sink, twoColumn = false)
        writer.table(listOf(listOf(giant, "small", image)), indent = 0f)
        writer.finish()

        assertEquals("the table paginated", bands.size, sink.pageCount)
        // A fully clipped block is skipped, so the picture is drawn exactly in
        // the one band that shows it, complete and still alive.
        val shown = sink.bitmaps.single()
        assertFalse("the bitmap must not be recycled before the row ends", shown.recycled)
        assertTrue("the picture is inside its band", shown.top >= shown.clipTop - 0.5f)
        assertTrue(
            "the picture is not sliced by its band",
            shown.top + shown.height <= shown.clipBottom + 0.5f
        )
        assertTrue(
            "the flow ended inside the printable area",
            writer.finalY() in PdfPaperWriter.MARGIN.toFloat()..(PdfPaperWriter.BOTTOM + 0.5f)
        )
    }
}
