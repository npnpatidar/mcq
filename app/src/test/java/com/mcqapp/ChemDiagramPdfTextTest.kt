package com.mcqapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import com.mcqapp.data.export.ChemDiagramPreparation
import com.mcqapp.data.export.ChemDiagramRenderer
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

    private fun realPng(): Pair<String, ByteArray> {
        val bytes = ByteArrayOutputStream()
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            compress(Bitmap.CompressFormat.PNG, 100, bytes)
            recycle()
        }
        val raw = bytes.toByteArray()
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(raw) to raw
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

    /** Records what the writer would print and draw, without drawing it. */
    private class RecordingWriter : PdfPaperWriter.Writer(RecordingSink(), twoColumn = false) {
        val paragraphs = mutableListOf<String>()
        val images = mutableListOf<String>()

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

        override fun image(src: String?, indent: Float) {
            // A null src is the legacy image slot, not a structure picture.
            if (src == null) return
            images += src
            events += "image"
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
    fun aPreparedReactionPrintsItsProductLabelAfterThePicture() = runBlocking {
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
        assertEquals("the product label is the only printed text", listOf("A"), writer.paragraphs)
        assertEquals(
            "the picture is emitted before its product label",
            listOf("image", "text:A"),
            writer.events
        )
        assertFalse(
            "the handles are not printed beside the picture: $printed",
            printed.contains(chemElementText(reaction))
        )
        assertFalse(
            "the base64 raster must never be printed as text",
            writer.paragraphs.any { it.contains(Base64.getEncoder().encodeToString(raw)) }
        )
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
