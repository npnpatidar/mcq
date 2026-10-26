package com.mcqapp

import com.mcqapp.data.export.spannedFromInlineHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The PDF writer's inline pipeline must close spans the way InlineHtml.parse
 * does: a mis-nested close pops until its tag is found, so formatting cannot
 * leak past it (consolidated_bugs.md G5). Robolectric needs the android.text
 * classes, so this pin runs on x86_64 CI only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfInlineSpanTest {

    @Test
    fun aMisNestedCloseEndsTheTagsAboveIt() {
        val spanned = spannedFromInlineHtml("<u>a <b>b</u> c</b>")
        assertEquals("a b c", spanned.toString())

        val underline = spanned.getSpans(0, spanned.length, android.text.style.UnderlineSpan::class.java)
        assertTrue("one underline span", underline.size == 1)
        assertEquals("underline covers 'a b' only", 0, spanned.getSpanStart(underline[0]))
        assertEquals(3, spanned.getSpanEnd(underline[0]))

        val bold = spanned.getSpans(0, spanned.length, android.text.style.StyleSpan::class.java)
            .filter { it.style == android.graphics.Typeface.BOLD }
        assertTrue("one bold span", bold.size == 1)
        assertEquals("bold covers 'b' only", 2, spanned.getSpanStart(bold[0]))
        assertEquals(3, spanned.getSpanEnd(bold[0]))
    }

    @Test
    fun aCloseWithNoOpenIsDropped() {
        val spanned = spannedFromInlineHtml("a</u>b")
        assertEquals("ab", spanned.toString())
    }
}
