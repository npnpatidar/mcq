package com.mcqapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Spanned
import android.text.TextPaint
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
     * lands in. A ReplacementSpan cannot wrap, so it must be built at a size
     * that fits, not painted over the neighbour.
     */
    @Test
    fun aFormulaIsShrunkToFitItsScaledColumn() {
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
            val span = spansOf(blocks).single()
            // The fitted size is the proportional shrink, floored at
            // MIN_FORMULA_SIZE when the column is narrower than the floor.
            val proportional = p.textSize * (contentWidth / formulaWidth).coerceAtMost(1f)
            val expected = maxOf(4f, proportional)
            assertTrue(
                "fitted size ${span.sizePx} must be <= expected $expected",
                span.sizePx <= expected + 0.01f
            )
            assertTrue("the formula was shrunk from the base size", span.sizePx < p.textSize)
            assertTrue("a sane minimum keeps it legible", span.sizePx >= 4f)
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

        val bitmaps = mutableListOf<BitmapDraw>()
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
