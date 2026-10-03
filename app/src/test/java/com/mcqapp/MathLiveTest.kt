package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.blockKey
import com.mcqapp.ui.editor.DEFAULT_MATHLIVE_FONT_PX
import com.mcqapp.ui.editor.MATHLIVE_JS_URL
import com.mcqapp.ui.editor.mathLiveFontPx
import com.mcqapp.ui.editor.mathLiveHtml
import com.mcqapp.ui.editor.mathMlToLatex
import com.mcqapp.ui.editor.shouldPushLatex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MathLiveTest {

    @Test
    fun theFieldIsNeverWrittenToOnceTheUserHasTypedInIt() {
        // Regression: a programmatic setValue drops the selection, so the next
        // character landed at the start and the formula appeared to delete what
        // was already typed. Once the user has edited, the host must stop
        // writing entirely.
        assertFalse(shouldPushLatex("x^2", "x^3", userEdited = true))
        assertFalse(shouldPushLatex("x^2", "x^2", userEdited = true))
        assertFalse(shouldPushLatex(null, "anything", userEdited = true))
    }

    @Test
    fun anUntouchedFieldStillAcceptsAnExternalChange() {
        // Loading or restoring a formula has to reach the field.
        assertTrue(shouldPushLatex("x^2", "x^3", userEdited = false))
    }

    @Test
    fun aProgrammaticSetKeepsTheCaret() {
        // Defence in depth: if a write is ever needed, the selection survives.
        val html = mathLiveHtml("\"x^2\"")
        assertTrue("the caret must be saved and restored", html.contains("mf.selection.position"))
    }

    @Test
    fun aRecycledEditorOnlyPushesAChangedFormula() {
        // Pushing unconditionally would reset the caret on every
        // recomposition; never pushing leaves the previous formula on screen.
        assertFalse(shouldPushLatex("x^2", "x^2", userEdited = false))
        assertTrue(shouldPushLatex("x^2", "x^3", userEdited = false))
        // Nothing loaded yet: the factory already set the value.
        assertFalse(shouldPushLatex(null, "x^2", userEdited = false))
    }

    @Test
    fun theFormulaFontStaysSmallEnoughForTheKeyboardToFit() {
        // MathLive sizes its virtual keyboard from the field font size. An
        // earlier version passed device pixels (48 on a 3x screen) and the
        // panel was taller than the WebView, so it was clipped.
        assertEquals("default text size", 16, mathLiveFontPx(1.0f))
        assertEquals(13, mathLiveFontPx(0.8f))
        assertEquals(21, mathLiveFontPx(1.3f))
        // Clamped: however large the user's setting, the panel must fit.
        assertEquals(26, mathLiveFontPx(2.0f))
        assertEquals(26, mathLiveFontPx(4.0f))
        assertTrue("must never exceed the cap", mathLiveFontPx(3.0f) <= 26)
    }

    @Test
    fun theFormulaFontIsNeverDevicePixels() {
        // A 3x screen would give 48 device pixels; CSS pixels must not scale
        // with density.
        assertTrue(mathLiveFontPx(1.0f) <= 20)
    }

    @Test
    fun theFieldHonoursTheAppsTextSize() {
        val small = mathLiveHtml("\"x^2\"", fontPx = 18)
        val large = mathLiveHtml("\"x^2\"", fontPx = 34)
        assertTrue(small.contains("font-size:18px"))
        assertTrue(large.contains("font-size:34px"))
        // The default must not have changed for anyone not using the setting.
        assertTrue(mathLiveHtml("\"x^2\"").contains("font-size:${DEFAULT_MATHLIVE_FONT_PX}px"))
    }

    @Test
    fun aProgrammaticSetIsNotEchoedBackAsUserInput() {
        val html = mathLiveHtml("\"x^2\"")
        assertTrue(html.contains("window.setLatex"))
        assertTrue(html.contains("if (applying) return;"))
    }

    @Test
    fun blockKeysSurviveEditsSoTheFieldKeepsFocus() {
        val before = ContentElement.MathElement("<math><mi>x</mi></math>")
        val after = ContentElement.MathElement("<math><msup><mi>x</mi><mn>2</mn></msup></math>")
        // The key must NOT change when the content changes: a key derived from
        // the payload made Compose rebuild the block on every keystroke, which
        // closed the soft keyboard in text fields and destroyed the WebView
        // holding the MathLive keyboard.
        assertEquals(blockKey(before, 0), blockKey(after, 0))
        assertEquals(blockKey(before, 1), blockKey(after, 1))
    }

    @Test
    fun blockKeysSeparateBlocksAndKinds() {
        val math = ContentElement.MathElement("<math><mi>x</mi></math>")
        val other = ContentElement.MathElement("<math><mi>y</mi></math>")
        val text = ContentElement.TextElement("x")
        // Different position, and different kind, are different blocks.
        assertNotEquals(blockKey(math, 0), blockKey(math, 1))
        assertNotEquals(blockKey(math, 0), blockKey(text, 0))
        // Two blocks of the same kind at the same slot are the same block.
        assertEquals(blockKey(math, 0), blockKey(other, 0))
    }


    @Test
    fun squareExpressionKeepsExponent() {
        val latex = mathMlToLatex(
            "<math><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo>" +
                "<mn>2</mn><mi>x</mi><mo>+</mo><mn>1</mn></math>"
        )
        assertTrue(latex.contains("^{2}"))
    }

    @Test
    fun sqrtMapsToCommand() {
        val latex = mathMlToLatex(
            "<math><msqrt><msup><mi>a</mi><mn>2</mn></msup>" +
                "<mo>+</mo><msup><mi>b</mi><mn>2</mn></msup></msqrt></math>"
        )
        assertTrue(latex.contains("\\sqrt"))
    }

    @Test
    fun fracMapsToCommand() {
        val latex = mathMlToLatex("<math><mfrac><mi>a</mi><mi>b</mi></mfrac></math>")
        assertTrue(latex.contains("\\frac"))
    }

    @Test
    fun unknownTagPassesTextThrough() {
        val latex = mathMlToLatex("<math><mystery>hello</mystery></math>")
        assertTrue(latex.contains("hello"))
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertEquals("", mathMlToLatex(""))
    }

    @Test
    fun htmlUsesBundledAssetsOnly() {
        val html = mathLiveHtml("\"x^2\"")
        assertTrue(html.contains(MATHLIVE_JS_URL))
        assertTrue(!html.contains("<script src=\"http"))
        assertTrue(html.contains("fontsDirectory"))
    }

    @Test
    fun offlineAssetsAreBundled() {
        val js = File("src/main/assets/mathlive/mathlive.min.js")
        assertTrue(js.exists())
        assertTrue(js.length() > 500 * 1024)
        val fonts = File("src/main/assets/mathlive/fonts")
            .listFiles { _, name -> name.endsWith(".woff2") }
        assertEquals(20, fonts?.size)
    }
}
