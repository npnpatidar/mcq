package com.mcqapp

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import com.mcqapp.util.InlineHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineHtmlTest {

    private fun stylesOf(parsed: androidx.compose.ui.text.AnnotatedString) =
        parsed.spanStyles.map { Triple(it.start, it.end, it.item) }

    @Test
    fun plainTextPassesThroughUntouched() {
        val parsed = InlineHtml.parse("H2O?")
        assertEquals("H2O?", parsed.text)
        assertTrue(parsed.spanStyles.isEmpty())
    }

    @Test
    fun boldAndItalicRanges() {
        val parsed = InlineHtml.parse("a<b>x</b>c<i>y</i>")
        assertEquals("axcy", parsed.text)
        val styles = stylesOf(parsed)
        assertTrue(styles.any { it == Triple(1, 2, androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) })
        assertTrue(styles.any { it == Triple(3, 4, androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic)) })
    }

    @Test
    fun chemistrySubscript() {
        val parsed = InlineHtml.parse("H<sub>2</sub>O")
        assertEquals("H2O", parsed.text)
        val sub = parsed.spanStyles.single()
        assertEquals(1, sub.start)
        assertEquals(2, sub.end)
        assertEquals(BaselineShift.Subscript, sub.item.baselineShift)
    }

    @Test
    fun entitiesDecodeButBareSymbolsSurvive() {
        val parsed = InlineHtml.parse("A &amp; B & C < D, 5 < 6, 100&#37;")
        assertEquals("A & B & C < D, 5 < 6, 100%", parsed.text)
        assertTrue(parsed.spanStyles.isEmpty())
    }

    @Test
    fun brBecomesNewline() {
        val parsed = InlineHtml.parse("a<br/>b<br>c")
        assertEquals("a\nb\nc", parsed.text)
    }

    @Test
    fun unknownTagsAreStripped() {
        val parsed = InlineHtml.parse("a<foo>x</foo>b")
        assertEquals("axb", parsed.text)
        assertTrue(parsed.spanStyles.isEmpty())
    }

    @Test
    fun strayClosingTagPopsNothing() {
        val parsed = InlineHtml.parse("a</b>b")
        assertEquals("ab", parsed.text)
        assertTrue(parsed.spanStyles.isEmpty())
    }

    @Test
    fun unclosedSpanRunsToEnd() {
        val parsed = InlineHtml.parse("a<b>bc")
        assertEquals("abc", parsed.text)
        val bold = parsed.spanStyles.single()
        assertEquals(1, bold.start)
        assertEquals(3, bold.end)
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
    }

    @Test
    fun nestedSpansCompose() {
        val parsed = InlineHtml.parse("a<b>x<i>y</i></b>b")
        assertEquals("axyb", parsed.text)
        val styles = stylesOf(parsed)
        assertTrue(styles.any { it.first == 1 && it.second == 3 && it.third.fontWeight == FontWeight.Bold })
        assertTrue(styles.any { it.first == 2 && it.second == 3 && it.third.fontStyle == FontStyle.Italic })
    }

    @Test
    fun underlineAndStrikeAndMark() {
        val parsed = InlineHtml.parse("<u>u</u><del>d</del><mark>m</mark>")
        assertEquals("udm", parsed.text)
        val styles = stylesOf(parsed)
        assertTrue(styles.any { it.third.textDecoration == TextDecoration.Underline })
        assertTrue(styles.any { it.third.textDecoration == TextDecoration.LineThrough })
        assertTrue(styles.any { it.third.background != androidx.compose.ui.graphics.Color.Unspecified })
    }
}
