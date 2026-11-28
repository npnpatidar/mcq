package com.mcqapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.data.export.ChemDiagramPreparation
import com.mcqapp.data.export.ChemDiagramRenderer
import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.export.PdfPaperWriter
import com.mcqapp.data.export.SmilesWebViewRenderer
import com.mcqapp.data.export.isPngDataUri
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.common.ChemDiagramRenderHostView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The real offscreen WebView renderer, on the CI emulator (the host's ARM64
 * JVM cannot run a WebView). It proves the attached host can draw the bundled
 * SmilesDrawer asset for both a molecule and a reaction, and that the PNG it
 * returns is a real PNG rather than an SVG or an empty string.
 */
@RunWith(AndroidJUnit4::class)
class ChemDiagramRendererDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(smiles: String, textAbove: String = ""): String? {
        compose.setContent { ChemDiagramRenderHostView() }
        compose.waitForIdle()
        val renderer = SmilesWebViewRenderer()
        return runBlocking {
            withTimeout(20_000) { renderer.render(smiles, textAbove) }
        }
    }

    /**
     * True only for a real drawing: at least 32 px on each side and at least
     * one non-transparent pixel. A blank canvas or a 1×1 raster fails.
     */
    private fun isDrawableRaster(bytes: ByteArray): Boolean {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        try {
            if (bitmap.width < 32 || bitmap.height < 32) return false
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (Color.alpha(bitmap.getPixel(x, y)) != 0) return true
                }
            }
            return false
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertPng(uri: String?) {
        assertNotNull("the renderer returned no PNG", uri)
        assertTrue("not a PNG data URI: ${uri?.take(40)}", isPngDataUri(uri!!))
        val bytes = Base64.getDecoder().decode(uri.substringAfter(','))
        assertEquals(
            listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            bytes.take(8).map { it.toInt() and 0xFF }
        )
        assertTrue("the PNG is blank or too small (1×1 raster)", isDrawableRaster(bytes))
    }

    @Test
    fun aBlankOnePixelRasterWouldFailTheDrawingCheck() {
        val bytes = java.io.ByteArrayOutputStream()
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            compress(Bitmap.CompressFormat.PNG, 100, bytes)
            recycle()
        }
        assertFalse(
            "the drawable-raster assertion must reject a blank 1×1 image",
            isDrawableRaster(bytes.toByteArray())
        )
    }

    @Test
    fun benzeneRendersToAPng() {
        assertPng(render("c1ccccc1"))
    }

    @Test
    fun aReactionRendersToAPng() {
        // A real caption exercises the arbitrary JS text path, not just SMILES.
        assertPng(render("CCO>>CC=O", "LiAlH₄ / ether"))
    }

    /**
     * The preparation pass + PDF writer with the fake renderer: the completed
     * structure must become a PDF image XObject, not just handles text.
     */
    @Test
    fun pdfEmbedsAPreparedStructureAsAnImage() {
        val bytes = ByteArrayOutputStream()
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
            compress(Bitmap.CompressFormat.PNG, 100, bytes)
            recycle()
        }
        val png = "data:image/png;base64," +
            Base64.getEncoder().encodeToString(bytes.toByteArray())
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
                            elements = listOf(
                                ContentElement.ChemElement(smiles = "c1ccccc1", caption = "benzene")
                            ),
                            options = listOf(OptionDto(id = "a", text = "A"))
                        )
                    )
                )
            )
        )
        val renderer = object : ChemDiagramRenderer {
            override suspend fun render(smiles: String, textAbove: String): String = png
        }
        val stream = runBlocking {
            ChemDiagramPreparation.enrichPaper(PaperStream.of(paper), renderer)
        }
        val out = ByteArrayOutputStream()
        runBlocking { PdfPaperWriter.writePdf(stream, out) }
        val pdf = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertTrue("the PDF must contain the structure image",
            pdf.contains("/Subtype /Image"))
        assertTrue("the PDF must still be complete", pdf.contains("%%EOF"))
    }
}
