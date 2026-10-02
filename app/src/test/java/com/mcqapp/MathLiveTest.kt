package com.mcqapp

import com.mcqapp.ui.editor.MATHLIVE_JS_URL
import com.mcqapp.ui.editor.mathLiveHtml
import com.mcqapp.ui.editor.mathMlToLatex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MathLiveTest {

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
