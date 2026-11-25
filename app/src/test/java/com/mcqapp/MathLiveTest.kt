package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.blockKey
import com.mcqapp.ui.editor.DEFAULT_MATHLIVE_FONT_PX
import com.mcqapp.ui.editor.MATHLIVE_JS_URL
import com.mcqapp.ui.editor.MATHLIVE_MAX_EDITOR_PX
import com.mcqapp.ui.editor.MATHLIVE_MIN_EDITOR_PX
import com.mcqapp.ui.editor.mathLiveEditorHeightPx
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
        assertEquals(blockKey("q1", "b0", before), blockKey("q1", "b0", after))
    }

    @Test
    fun blockKeysFollowTheBlockNotItsPosition() {
        val math = ContentElement.MathElement("<math><mi>x</mi></math>")
        val other = ContentElement.MathElement("<math><mi>y</mi></math>")
        val text = ContentElement.TextElement("x")
        // Two formulas of the same kind carry different ids, so swapping them
        // gives each its own WebView instead of reusing the neighbour's
        // (question_editor_audit.md M-01).
        assertNotEquals(blockKey("q1", "b0", math), blockKey("q1", "b1", other))
        // Kind is still part of the identity.
        assertNotEquals(blockKey("q1", "b0", math), blockKey("q1", "b0", text))
    }

    @Test
    fun blockKeysBelongToTheirQuestionAndOption() {
        val math = ContentElement.MathElement("<math><mi>x</mi></math>")
        // `q1`'s option `a` and `q2`'s option `a` must not share a WebView:
        // the user-edited latch would refuse q2's formula and typing would
        // write q1's formula into q2 (question_editor_audit.md H-05).
        assertNotEquals(
            blockKey("q1|a", "b0", math),
            blockKey("q2|a", "b0", math)
        )
        // The same block keeps its identity across a recomposition.
        assertEquals(blockKey("q1|a", "b0", math), blockKey("q1|a", "b0", math))
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

    @Test
    fun aShortUnfocusedFormulaGetsACompactWrapper() {
        val height = mathLiveEditorHeightPx(28, 0)
        assertTrue("compact height was $height", height < MATHLIVE_MAX_EDITOR_PX && height <= 80)
        assertEquals(MATHLIVE_MIN_EDITOR_PX, mathLiveEditorHeightPx(0, 0))
    }

    @Test
    fun theVirtualKeyboardGrowsTheWrapperButNeverPastTheCap() {
        val base = mathLiveEditorHeightPx(30, 0)
        assertEquals(base + 240, mathLiveEditorHeightPx(30, 240))
        assertEquals(MATHLIVE_MAX_EDITOR_PX, mathLiveEditorHeightPx(30, 4_000))
        assertEquals(MATHLIVE_MAX_EDITOR_PX, mathLiveEditorHeightPx(4_000, 4_000))
    }

    @Test
    fun bogusGeometryIsClamped() {
        assertEquals(MATHLIVE_MIN_EDITOR_PX, mathLiveEditorHeightPx(-20, -100))
        assertEquals(mathLiveEditorHeightPx(30, 0), mathLiveEditorHeightPx(30, -5))
        assertTrue(
            mathLiveEditorHeightPx(Int.MIN_VALUE, Int.MAX_VALUE) in
                MATHLIVE_MIN_EDITOR_PX..MATHLIVE_MAX_EDITOR_PX
        )
    }

    @Test
    fun thePageReportsFieldAndKeyboardGeometry() {
        val html = mathLiveHtml("\"x^2\"")
        assertFalse("the 120px minimum is gone", html.contains("min-height:120px"))
        assertTrue(html.contains("window.Android.onGeometry"))
        assertTrue(html.contains("mathVirtualKeyboard"))
        assertTrue(html.contains("boundingRect.height"))
        assertTrue(html.contains("focusin") && html.contains("focusout"))
        assertTrue(html.contains("'input'"))
        // MathML delivery must be unchanged by the new bridge.
        assertTrue(html.contains("Android.onMathMl(mf.getValue('math-ml'))"))
        assertTrue(html.contains("window.setLatex"))
    }

    @Test
    fun theNativeMenuIsDarkAndStaysFlatOnNarrowScreens() {
        val html = mathLiveHtml("\"x^2\"")
        assertTrue(html.contains("theme=\"dark\""))
        assertTrue(html.contains("--ui-menu-bg: #303030"))
        assertTrue(html.contains("--ui-menu-text: #f5f5f5"))
        assertTrue(html.contains("--keyboard-background: #2b2b2b"))
        assertTrue(html.contains("::part(menu-toggle)"))
        // The clipped submenus (Insert/Mode/Font Style/Color/matrices) are
        // replaced by a short documented flat menu via mf.menuItems.
        assertTrue(html.contains("mf.menuItems.filter"))
        assertTrue(html.contains("'cut','paste','select-all'"))
    }

    @Test
    fun theEditorWrapperHasNoFixedKeyboardSizedHeight() {
        val src = File("src/main/java/com/mcqapp/ui/editor/MathLiveEditor.kt").readText()
        assertFalse(".height(560.dp)" in src)
        assertFalse("min-height:120px" in src)
        assertTrue("mathLiveEditorHeightPx(" in src)
    }

    @Test
    fun geometryChangesAndFlatCopyAreWired() {
        val html = mathLiveHtml("\"x^2\"")
        // MathLive shows the keyboard after ~300ms: a focusin sample is too
        // early. The documented geometrychange event is the authoritative
        // signal, and a hidden keyboard already reports height 0.
        assertTrue(
            "listen for geometrychange on mathVirtualKeyboard",
            html.contains("kbEvents.addEventListener('geometrychange', reportGeometry)")
        )
        assertFalse("no focus gate", html.contains("if (focused)"))
        assertFalse("no 60ms focus timer", html.contains("setTimeout(reportGeometry, 60)"))
        assertTrue(html.contains("Math.round(kb.boundingRect.height)"))
        // `copy` is a submenu in 0.111.0 and cannot be kept; add a flat leaf
        // that invokes the verified copyToClipboard command instead.
        assertTrue("copy must survive without a submenu", html.contains("id: 'copy-latex'"))
        assertTrue(html.contains("mf.executeCommand('copyToClipboard')"))
        assertTrue(html.contains("].concat(rows)"))
        assertTrue(html.contains("'cut','paste','select-all'"))
    }
}
