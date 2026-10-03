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
    fun theUsersOwnTypingIsNeverPushedBackAtThem() {
        // The field reports MathML, the block turns it into LaTeX and hands
        // it straight back. Pushing that would fight the user mid-edit and
        // cost them the virtual keyboard focus.
        assertFalse(shouldPushLatex("x^2", "x^3", latexFromOwnOutput = "x^3"))
        // A change from outside the field still goes through.
        assertTrue(shouldPushLatex("x^2", "x^4", latexFromOwnOutput = "x^3"))
    }

    @Test
    fun aReportedFormulaRoundTripsToTheLatexTheFieldHolds() {
        val mml = mathMlToLatex("<math><msup><mi>x</mi><mn>2</mn></msup></math>")
        assertEquals("x^{2}", mml)
        assertFalse(shouldPushLatex(null, mml, latexFromOwnOutput = mml))
    }

    @Test
    fun aRecycledEditorOnlyPushesAChangedFormula() {
        // Pushing unconditionally would reset the caret on every
        // recomposition; never pushing leaves the previous formula on screen.
        assertFalse(shouldPushLatex("x^2", "x^2", latexFromOwnOutput = null))
        assertTrue(shouldPushLatex("x^2", "x^3", latexFromOwnOutput = null))
        // Nothing loaded yet: the factory already set the value.
        assertFalse(shouldPushLatex(null, "x^2", latexFromOwnOutput = null))
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
    fun blockKeysDistinguishBlocksAndFollowEdits() {
        val math = ContentElement.MathElement("<math><mi>x</mi></math>")
        val other = ContentElement.MathElement("<math><mi>y</mi></math>")
        assertEquals(blockKey(math, 0), blockKey(math, 0))
        // A different block, or the same block after an edit, gets a new key.
        assertNotEquals(blockKey(math, 0), blockKey(other, 0))
        assertNotEquals(blockKey(math, 0), blockKey(math, 1))
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
