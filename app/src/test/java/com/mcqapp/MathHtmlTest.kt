package com.mcqapp

import androidx.compose.ui.graphics.Color
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.MATHJAX_ASSET_URL
import com.mcqapp.util.mixedContentHtml
import com.mcqapp.util.toCssRgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MathHtmlTest {

    private fun sampleElements() = listOf(
        ContentElement.TextElement("हल करें: "),
        ContentElement.MathElement("<math><mi>x</mi></math>"),
        ContentElement.TextElement(" जारी"),
        ContentElement.TableElement(listOf(listOf("a<b", "c&d"))),
        ContentElement.ImageElement("https://example.com/f.png")
    )

    @Test
    fun mixedPageFlowsTextAndFormulaInline() {
        val html = mixedContentHtml(sampleElements(), 48f, "rgba(0,0,0,1)")
        // One body, no per-element block wrappers: the browser lays text
        // and the formula out in a single sentence.
        assertTrue(html.contains("हल करें: <math><mi>x</mi></math> जारी"))
        assertTrue(html.contains(MATHJAX_ASSET_URL))
        assertTrue(html.contains("font-size:48.0px"))
        assertTrue(html.contains("color:rgba(0,0,0,1)"))
        assertTrue(html.contains("background:transparent"))
    }

    @Test
    fun mixedPageSerializesTablesAndImages() {
        val html = mixedContentHtml(sampleElements(), 48f, "rgba(0,0,0,1)")
        assertTrue(html.contains("<td>a&lt;b</td>"))
        assertTrue(html.contains("<td>c&amp;d</td>"))
        assertTrue(html.contains("<img src=\"https://example.com/f.png\""))
    }

    @Test
    fun mixedPageNeedsNoNetwork() {
        val html = mixedContentHtml(sampleElements(), 48f, "rgba(0,0,0,1)")
        // Any remote script would reintroduce the offline failure.
        assertTrue(html.lines().none { it.contains("<script src=\"http") })
    }

    @Test
    fun cssColorFormatsRgba() {
        assertEquals("rgba(0,0,0,1)", Color(0f, 0f, 0f, 1f).toCssRgba())
        assertEquals("rgba(255,255,255,1)", Color.White.toCssRgba())
    }

    @Test
    fun bundledMathJaxAssetIsPresent() {
        // Unit tests run with the app module as working directory.
        val asset = File("src/main/assets/mathjax/tex-mml-svg.js")
        assertTrue("missing: ${asset.absolutePath}", asset.isFile)
        val content = asset.readText()
        assertTrue(content.length > 1_000_000)
        assertTrue(content.contains("output/svg"))
        assertTrue(content.contains("input/mml"))
    }
}
