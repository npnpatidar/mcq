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
 * must still print its readable handles, and the base64 raster must never be
 * printed as text — the picture is drawn through the image path instead.
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

    /** Records what the writer would print as text, without drawing it. */
    private class RecordingWriter : PdfPaperWriter.Writer(RecordingSink(), twoColumn = false) {
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
        }
    }

    @Test
    fun aPreparedStructurePrintsItsHandlesButNeverItsBase64() = runBlocking {
        val (png, raw) = realPng()
        val chem = ContentElement.ChemElement(
            smiles = "c1ccccc1",
            formula = "C6H6",
            caption = "benzene"
        )
        val paper = PaperDto(
            id = "p1",
            title = "Chemistry",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Structures",
                    questions = listOf(
                        QuestionDto(
                            id = "q1",
                            elements = listOf(chem),
                            options = listOf(OptionDto(id = "a", text = "A"))
                        )
                    )
                )
            )
        )
        val renderer = object : ChemDiagramRenderer {
            override suspend fun render(smiles: String, textAbove: String): String = png
        }
        val stream = ChemDiagramPreparation.enrichPaper(PaperStream.of(paper), renderer)
        var question: QuestionDto? = null
        stream.walk(onCategory = {}, onQuestion = { question = it })

        val writer = RecordingWriter()
        writer.elements(question!!.elements)
        val printed = writer.paragraphs.joinToString("\n")

        assertTrue(
            "the handles must still print: $printed",
            printed.contains(chemElementText(chem))
        )
        assertFalse(
            "the base64 raster must never be printed as text",
            printed.contains(Base64.getEncoder().encodeToString(raw)) ||
                printed.contains("data:image/png")
        )
    }
}
