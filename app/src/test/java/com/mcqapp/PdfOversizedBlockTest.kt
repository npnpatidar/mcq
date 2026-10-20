package com.mcqapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextPaint
import android.text.StaticLayout
import com.mcqapp.data.export.PdfPaperWriter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A block taller than one printable page used to draw ONCE past the page
 * bottom: `advance()` protects a block's START only, so a ~60-line stem, a
 * giant cell or a huge explanation silently lost everything past the page
 * in the produced PDF.
 *
 * The proof is on the writer's page/draw OUTPUT, not on eyeballed bytes:
 * the page host is injected as a recorder (Robolectric cannot construct a
 * PdfDocument page — "document is closed!" — but it can run StaticLayout,
 * which is what the banding consumes), and the assertions are the
 * observable phenomena: a tall block must occupy more pages than its
 * first advance, and the flow's y must end inside the printable area
 * (the old code ended it far past BOTTOM).
 *
 * Mutant: revert the two `drawTallBands` calls to single draws — both
 * assertions above go red on both tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfOversizedBlockTest {

    /** Records how many pages the flow allocated, without PdfDocument. */
    private class RecordingSink : PdfPaperWriter.PdfPageSink {
        var pageCount = 0
        override fun startPage(pageNum: Int): PdfPaperWriter.PdfPageHost =
            object : PdfPaperWriter.PdfPageHost {
                override val canvas = Canvas()
                override fun finish() {
                    pageCount = pageNum
                }
            }
    }

    private val lineCount = 210

    @Test
    fun anOversizedParagraphBandsOntoFollowingPages() {
        val sink = RecordingSink()
        val writer = PdfPaperWriter.Writer(sink, twoColumn = false)
        val text = (1..lineCount).joinToString("\n") { "stem line $it for an oversized paragraph" }
        writer.paragraph(text, 11f, Typeface.NORMAL, Color.BLACK, spaceBefore = 0f, spaceAfter = 0f)
        writer.finish()

        // The layout's raw size for the same parameters, so the expectation
        // is the block's own metrics, not a hardcoded guess.
        val paint = TextPaint().apply { textSize = 11f }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, PdfPaperWriter.CONTENT_W.toInt())
            .build()
        val printable = PdfPaperWriter.BOTTOM - PdfPaperWriter.MARGIN
        val expectedPages = kotlin.math.ceil(layout.height / printable.toDouble()).toLong()
        assertTrue(
            "a ${layout.height}px block must band onto $expectedPages pages, got ${sink.pageCount}",
            sink.pageCount >= expectedPages
        )
        assertTrue(
            "the flow ended at y=${writer.finalY()}, outside the printable area",
            writer.finalY() in PdfPaperWriter.MARGIN.toFloat()..(PdfPaperWriter.BOTTOM + 0.5f)
        )
    }

    @Test
    fun anOversizedTableRowBandsOntoFollowingPages() {
        val sink = RecordingSink()
        val writer = PdfPaperWriter.Writer(sink, twoColumn = false)
        val giantCell = (1..lineCount).joinToString("\n") { "cell line $it taller than any page" }
        writer.table(listOf(listOf(giantCell, "small")), indent = 0f)
        writer.finish()

        assertTrue(
            "a row taller than a page must continue over several pages, got ${sink.pageCount}",
            sink.pageCount >= 3
        )
        assertTrue(
            "the flow ended at y=${writer.finalY()}, outside the printable area",
            writer.finalY() in PdfPaperWriter.MARGIN.toFloat()..(PdfPaperWriter.BOTTOM + 0.5f)
        )
    }
}
