package com.mcqapp

import com.mcqapp.data.export.decodeEntities
import com.mcqapp.data.export.HtmlPaperWriter.writeHtml
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.renderInlineHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four renderers must agree on stored entity references. Stored text is
 * characters: `&lt;` in the database DECIDES the display — the in-app
 * renderer (InlineHtml), the PDF writer and the DOCX writer decode it and
 * show `5 < 6`; HTML export used to escape it again and displayed the literal
 * characters `&lt;`. Three renderings of the same question, and the exporters'
 * fifteen hundred neighbours were aligned while this family was not.
 *
 * Decode ONCE at the input, then escape the decoded characters: a literal
 * `&` stays `&amp;`, and `&amp;amp;` (double-escaped by a machine pipeline)
 * renders as the characters `&amp;`, NOT as `&` — the assertion below pins
 * exactly the triples the bug report named. PDF's table additionally gained
 * `nbsp`, which it printed literally while in-app and DOCX produced the space.
 *
 * Mutants: revert escapeHtmlText to escape-without-decode — every
 * renderInlineHtml assertion goes red; drop `nbsp` from the shared table —
 * the decodeEntities assertions go red.
 */
class EntityDecodingAgreementTest {

    @Test
    fun htmlExportDecodesAStoredInsertionPoint() {
        assertEquals("5 &lt; 6", renderInlineHtml("5 &lt; 6"))
    }

    @Test
    fun aLiteralAmpersandStaysOneAmpersand() {
        assertEquals("AT&amp;T", renderInlineHtml("AT&T"))
    }

    @Test
    fun doubleEscapedAmpersandRendersAsAmpersandText() {
        // Stored &amp;amp; = the characters "&amp;": displays as "&amp;",
        // NOT as "&" — decode once, never twice.
        assertEquals("x &amp;amp; y", renderInlineHtml("x &amp;amp; y"))
    }

    @Test
    fun anUnknownReferenceStaysLiteral() {
        // Not a named entity in the table, so the literal characters are kept
        // (the in-app renderer keeps them too) and escaped for display.
        assertEquals("?&amp;sect;2", renderInlineHtml("?&sect;2"))
    }

    @Test
    fun numericReferencesDecodeToTheirCharacter() {
        assertEquals("£5", renderInlineHtml("&#163;5"))
    }

    @Test
    fun pdfDecodesNbspAsTheSpaceInAppProduces() {
        assertEquals("a\u00A0b", decodeEntities("a&nbsp;b"))
        assertEquals("5 < 6", decodeEntities("5 &lt; 6"))
        assertEquals("x &amp; y", decodeEntities("x &amp;amp; y"))
        assertEquals("?&sect;2", decodeEntities("?&sect;2"))
        assertEquals("£5", decodeEntities("&#163;5"))
    }

    @Test
    fun theHtmlWriterCarriesTheDecodedFormThroughTheBlock() {
        val q = QuestionDto(
            id = "q1",
            text = "Stem",
            options = listOf(OptionDto(id = "a", text = "A"), OptionDto(id = "b", text = "B")),
            correctOptionIds = listOf("a"),
            explanation = "5 &lt; 6 &nbsp; &amp;amp; AT&T"
        )
        val paper = PaperDto(
            id = "p1",
            title = "T",
            categories = listOf(CategoryDto(id = "c1", title = "C", questions = listOf(q)))
        )
        val html = htmlOf(paper)
        assertTrue(html.contains("Explanation: 5 &lt; 6"))
        assertFalse("stored entities must not be re-escaped", html.contains("&amp;lt;"))
        assertFalse("nbsp must not survive as literal characters", html.contains("&nbsp;"))
    }
}
