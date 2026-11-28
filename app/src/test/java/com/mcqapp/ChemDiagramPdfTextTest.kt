package com.mcqapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.mcqapp.data.export.ChemDiagramPreparation
import com.mcqapp.data.export.ChemDiagramRenderer
import com.mcqapp.data.export.DocxPaperWriter
import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.export.PdfPaperWriter
import com.mcqapp.data.export.chemElementText
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The PDF writer's page seam makes the rich-element path observable without a
 * real PdfDocument (Robolectric cannot construct one). A prepared structure
 * draws its picture and prints no visible handles (PDF has no alt text); a
 * reaction's product label still prints after the picture, a PNG-less structure
 * keeps its handles, and the base64 raster is never printed as text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChemDiagramPdfTextTest {

    /** A picture wider and taller than the PDF label test needs (>= 60 px). */
    private fun realPng(): Pair<String, ByteArray> {
        val bytes = ByteArrayOutputStream()
        Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            compress(Bitmap.CompressFormat.PNG, 100, bytes)
            recycle()
        }
        val raw = bytes.toByteArray()
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(raw) to raw
    }

    /** A PNG with chosen pixel dimensions (a flat one makes the label taller). */
    private fun pngDataUri(width: Int, height: Int): String {
        val bytes = ByteArrayOutputStream()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            compress(Bitmap.CompressFormat.PNG, 100, bytes)
            recycle()
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private class RecordingSink : PdfPaperWriter.PdfPageSink {
        override fun startPage(pageNum: Int): PdfPaperWriter.PdfPageHost =
            object : PdfPaperWriter.PdfPageHost {
                override val canvas = Canvas(
                    Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                )
                override fun finish() = Unit
            }
    }

    private data class DrawnBitmap(
        val left: Float,
        val top: Float,
        val width: Int,
        val height: Int
    )

    private data class DrawnText(
        val text: String,
        val x: Float,
        val y: Float,
        val top: Float,
        val bottom: Float
    )

    /** The real writer/page seam: records where the writer draws. */
    private class DrawingSink : PdfPaperWriter.PdfPageSink {
        val bitmaps = mutableListOf<DrawnBitmap>()
        val texts = mutableListOf<DrawnText>()

        override fun startPage(pageNum: Int): PdfPaperWriter.PdfPageHost =
            object : PdfPaperWriter.PdfPageHost {
                override val canvas = object : Canvas(
                    Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                ) {
                    override fun drawBitmap(
                        bitmap: Bitmap,
                        left: Float,
                        top: Float,
                        paint: Paint?
                    ) {
                        bitmaps += DrawnBitmap(left, top, bitmap.width, bitmap.height)
                    }

                    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                        val fm = paint.fontMetrics
                        texts += DrawnText(text, x, y, y + fm.ascent, y + fm.descent)
                    }
                }
                override fun finish() = Unit
            }
    }

    /** The real page seam plus a record of every `paragraph()` the writer makes. */
    private class SeamWriter(
        val sink: DrawingSink = DrawingSink()
    ) : PdfPaperWriter.Writer(sink, twoColumn = false) {
        val paragraphs = mutableListOf<String>()

        override fun paragraph(
            text: CharSequence,
            size: Float,
            style: Int,
            color: Int,
            indent: Float,
            spaceBefore: Float,
            spaceAfter: Float
        ) {
            paragraphs += text.toString()
            super.paragraph(text, size, style, color, indent, spaceBefore, spaceAfter)
        }
    }

    /** Records what the writer would print and draw, without drawing it. */
    private class RecordingWriter : PdfPaperWriter.Writer(RecordingSink(), twoColumn = false) {
        val paragraphs = mutableListOf<String>()
        val images = mutableListOf<String>()
        val labels = mutableListOf<String>()

        /** What the writer emitted, in order: `image` or `text:<content>`. */
        val events = mutableListOf<String>()

        override fun paragraph(
            text: CharSequence,
            size: Float,
            style: Int,
            color: Int,
            indent: Float,
            spaceBefore: Float,
            spaceAfter: Float
        ) {
            paragraphs += text.toString()
            events += "text:$text"
        }

        override fun image(src: String?, indent: Float, trailingLabel: String?) {
            // A null src is the legacy image slot, not a structure picture.
            if (src == null) return
            images += src
            // The label rides the picture's own call now; it must never reach
            // `paragraph`, which would put it on a line of its own.
            events += if (trailingLabel.isNullOrBlank()) "image" else "image+label:$trailingLabel"
            if (!trailingLabel.isNullOrBlank()) labels += trailingLabel
        }
    }

    private fun paperWith(element: ContentElement): PaperDto = PaperDto(
        id = "p1",
        title = "Chemistry",
        categories = listOf(
            CategoryDto(
                id = "c1",
                title = "Structures",
                questions = listOf(
                    QuestionDto(
                        id = "q1",
                        elements = listOf(element),
                        options = listOf(OptionDto(id = "a", text = "A"))
                    )
                )
            )
        )
    )

    private suspend fun prepared(element: ContentElement, png: String): QuestionDto {
        val renderer = object : ChemDiagramRenderer {
            override suspend fun render(smiles: String, textAbove: String): String = png
        }
        val stream = ChemDiagramPreparation.enrichPaper(
            PaperStream.of(paperWith(element)), renderer
        )
        var question: QuestionDto? = null
        stream.walk(onCategory = {}, onQuestion = { question = it })
        return question!!
    }

    @Test
    fun aPreparedStructureDrawsItsPictureAndPrintsNoBase64() = runBlocking {
        val (png, raw) = realPng()
        val chem = ContentElement.ChemElement(
            smiles = "c1ccccc1",
            formula = "C6H6",
            caption = "benzene"
        )
        val writer = RecordingWriter()
        writer.elements(prepared(chem, png).elements)
        val printed = writer.paragraphs.joinToString("\n")

        assertEquals("the drawing replaces the handles", listOf(png), writer.images)
        assertEquals("the picture is the only emitted event", listOf("image"), writer.events)
        assertFalse(
            "the handles are not printed beside the picture: $printed",
            printed.contains(chemElementText(chem))
        )
        assertFalse(
            "the base64 raster must never be printed as text",
            printed.contains(Base64.getEncoder().encodeToString(raw)) ||
                printed.contains("data:image/png")
        )
    }

    @Test
    fun aStructureWithoutAPngStillPrintsItsHandles() = runBlocking {
        val chem = ContentElement.ChemElement(
            smiles = "c1ccccc1",
            formula = "C6H6",
            caption = "benzene"
        )
        val writer = RecordingWriter()
        writer.elements(listOf(chem))
        val printed = writer.paragraphs.joinToString("\n")

        assertEquals("no picture without a valid PNG", emptyList<String>(), writer.images)
        assertEquals(
            "only the handles text is emitted",
            listOf("text:" + chemElementText(chem)),
            writer.events
        )
        assertTrue("the handles must still print: $printed", printed.contains(chemElementText(chem)))
    }

    @Test
    fun aPreparedReactionPrintsItsProductLabelWithThePicture() = runBlocking {
        val (png, raw) = realPng()
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = "A",
            caption = "LiAlH₄ / ether"
        )
        val writer = RecordingWriter()
        writer.elements(prepared(reaction, png).elements)
        val printed = writer.paragraphs.joinToString("\n")

        assertEquals("the scheme is drawn", listOf(png), writer.images)
        assertEquals("the label never becomes a paragraph", emptyList<String>(), writer.paragraphs)
        assertEquals("the label travels with the picture call", listOf("A"), writer.labels)
        assertEquals(listOf("image+label:A"), writer.events)
        assertFalse(
            "the handles are not printed beside the picture: $printed",
            printed.contains(chemElementText(reaction))
        )
        assertFalse(
            "the base64 raster must never be printed as text",
            writer.paragraphs.any { it.contains(Base64.getEncoder().encodeToString(raw)) }
        )
    }

    /**
     * The owner-reported defect: the product label used to print as its own
     * paragraph below the scheme. On the page it must sit beside the picture,
     * sharing its vertical band, with no second label-only line.
     */
    @Test
    fun aDrawnReactionPutsItsLabelBesideThePicture() = runBlocking {
        val (png, _) = realPng()
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = "A",
            caption = "LiAlH₄ / ether"
        )
        val writer = SeamWriter()
        val sink = writer.sink
        writer.elements(prepared(reaction, png).elements)
        writer.finish()

        val picture = sink.bitmaps.single()
        val labels = sink.texts.filter { it.text == "A" }
        assertEquals("the label is drawn once, not as a second paragraph", 1, labels.size)
        val label = labels.single()
        assertTrue(
            "the label starts at or after the picture's right edge: " +
                "label x=${label.x}, picture right=${picture.left + picture.width}",
            label.x >= picture.left + picture.width
        )
        val labelCentre = (label.top + label.bottom) / 2f
        val pictureCentre = picture.top + picture.height / 2f
        assertEquals(
            "the label is centred on the picture: label=$labelCentre picture=$pictureCentre",
            pictureCentre.toDouble(),
            labelCentre.toDouble(),
            1.0
        )
        assertEquals(
            "the same-line path never calls paragraph() for the label",
            emptyList<String>(),
            writer.paragraphs
        )
    }

    /**
     * A flat picture is shorter than its label line; the label must still
     * start inside the block, not above the flow's y (where the cell clip or
     * the previous paragraph would eat it).
     */
    @Test
    fun aTallerLabelStaysInsideTheBodyBlock() = runBlocking {
        val png = pngDataUri(2000, 10)
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = "A",
            caption = "LiAlH₄ / ether"
        )
        val writer = SeamWriter()
        writer.elements(prepared(reaction, png).elements)
        writer.finish()

        val picture = writer.sink.bitmaps.single()
        val label = writer.sink.texts.single { it.text == "A" }
        assertTrue("the fixture picture is flat", picture.height < 11)
        assertTrue(
            "the label baseline starts at/inside the block: label y=${label.y}, " +
                "flow start=${PdfPaperWriter.MARGIN}",
            label.y >= PdfPaperWriter.MARGIN - 0.5f
        )
        assertTrue(
            "the picture is centred inside the block, never above its top: " +
                "picture=${picture.top}..${picture.top + picture.height}",
            picture.top >= PdfPaperWriter.MARGIN - 0.5f &&
                picture.top + picture.height <= PdfPaperWriter.BOTTOM
        )
    }

    @Test
    fun aLongReactionLabelFallsBackToItsOwnBodyParagraph() = runBlocking {
        val (png, _) = realPng()
        val longLabel = "product".repeat(100)
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = longLabel,
            caption = "LiAlH₄ / ether"
        )
        val writer = SeamWriter()
        writer.elements(prepared(reaction, png).elements)
        writer.finish()

        assertEquals("the scheme still draws", 1, writer.sink.bitmaps.size)
        assertEquals(
            "the label takes its own paragraph when it cannot fit beside the picture",
            listOf(longLabel),
            writer.paragraphs
        )
        assertTrue(
            "the label is not also drawn beside the picture",
            writer.sink.texts.none { it.text == longLabel }
        )
    }

    @Test
    fun aReactionLabelIsAlwaysPlainText() = runBlocking {
        val (png, _) = realPng()
        val label = "A<sub>2</sub> & B"
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = label,
            caption = "LiAlH₄ / ether"
        )
        val writer = SeamWriter()
        writer.elements(prepared(reaction, png).elements)
        writer.finish()

        assertEquals(
            "the whole label is drawn literally, never parsed as markup",
            listOf(label),
            writer.sink.texts.map { it.text }.filter { it == label }
        )
        assertEquals("no label paragraph", emptyList<String>(), writer.paragraphs)
    }

    /**
     * A pathological indent leaves no room for the label; the fallback must
     * stay inert (picture at the flow's own width, label as a paragraph)
     * rather than drawing a negative-width image or crashing.
     */
    @Test
    fun aZeroWidthTailStillFallsBackWithoutCrashing() = runBlocking {
        val (png, _) = realPng()
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = "A",
            caption = "LiAlH₄ / ether"
        )
        val writer = SeamWriter()
        writer.image(
            prepared(reaction, png).elements.filterIsInstance<ContentElement.ChemElement>()
                .single().diagram,
            indent = PdfPaperWriter.CONTENT_W - 2f,
            trailingLabel = "A"
        )
        writer.finish()

        assertEquals("the label fell back to its own paragraph", listOf("A"), writer.paragraphs)
        assertTrue("a picture is still drawn", writer.sink.bitmaps.isNotEmpty())
        assertTrue(
            "the picture keeps a positive extent",
            writer.sink.bitmaps.single().width >= 1 && writer.sink.bitmaps.single().height >= 1
        )
    }

    @Test
    fun aStoredEntityInAReactionLabelStaysLiteral() = runBlocking {
        val (png, _) = realPng()
        val label = "A&amp;B"
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = label,
            caption = "LiAlH₄ / ether"
        )
        val writer = SeamWriter()
        writer.elements(prepared(reaction, png).elements)
        writer.finish()

        assertEquals(
            "PDF draws the stored characters, not a decoded version",
            listOf(label),
            writer.sink.texts.map { it.text }.filter { it == label || it == "A&B" }
        )
    }

    /**
     * DOCX cannot measure text without a Typeface, so its reserve must be
     * conservative enough for the same 11 pt metrics the PDF uses — including
     * the widest glyphs.
     */
    @Test
    fun docxLabelReserveCoversThePdfMeasuredWidth() {
        val paint = TextPaint().apply {
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }
        for (label in listOf("A", "WW", "WWWW", "product A <sub>2</sub>")) {
            val pdfCx = (paint.measureText(label) * 9525).toInt()
            assertTrue(
                "reserve for '$label' (${DocxPaperWriter.labelReserveCx(label)}) " +
                    "must cover the PDF width $pdfCx",
                DocxPaperWriter.labelReserveCx(label) >= pdfCx
            )
        }
    }

    @Test
    fun aReactionWithABlankFormulaAddsNoLabel() = runBlocking {
        val (png, _) = realPng()
        val reaction = ContentElement.ChemElement(
            smiles = "O=C1OCCCCC(=O)C1>>",
            formula = "",
            caption = "LiAlH₄ / ether"
        )
        val writer = RecordingWriter()
        writer.elements(prepared(reaction, png).elements)

        assertEquals("the scheme is drawn", listOf(png), writer.images)
        assertEquals("a blank formula prints no label", emptyList<String>(), writer.paragraphs)
        assertEquals(listOf("image"), writer.events)
    }
}
