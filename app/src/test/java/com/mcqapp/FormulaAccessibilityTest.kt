package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.ContentRenderPath
import com.mcqapp.util.accessibleContentLabelFor
import com.mcqapp.util.accessibleContentText
import com.mcqapp.util.accessibleMathText
import com.mcqapp.util.contentRenderPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E3/A4: a formula question must be readable by TalkBack, not just labelled. */
class FormulaAccessibilityTest {

    @Test
    fun formulaQuestionExposesTextTableAndMathToAccessibility() {
        val elements = listOf(
            ContentElement.TextElement("Solve <b>x</b>: "),
            ContentElement.MathElement("<math><mi>x</mi><mo>=</mo><mn>2</mn></math>"),
            ContentElement.TableElement(
                listOf(
                    listOf("case", "<math><mi>y</mi></math> + 1"),
                    listOf("-", "value")
                )
            )
        )
        assertEquals(ContentRenderPath.MIXED, contentRenderPath(elements))

        val spoken = accessibleContentText(elements)
        assertTrue("question text: $spoken", spoken.contains("Solve x"))
        assertTrue("formula text: $spoken", spoken.contains("x=2"))
        assertTrue("table cells: $spoken", spoken.contains("case"))
        assertTrue("formula inside a cell: $spoken", spoken.contains("y + 1"))
        // The old code supplied only a fixed generic description here.
        assertEquals(spoken, accessibleContentLabelFor(elements))
        assertFalse("generic description alone", spoken.contains("Rendered question content"))
    }

    @Test
    fun tableOnlyFormulaQuestionTakesTheMixedPathAndIsSpoken() {
        val elements = listOf(
            ContentElement.TableElement(
                listOf(
                    // The formula sits in the SECOND row on purpose: a decision
                    // that only inspected rows.first() (or the first cell)
                    // would send this question down the native path.
                    listOf("speed", "rate"),
                    listOf("-", "<math><mi>v</mi></math> + 1")
                )
            )
        )
        // No top-level formula beside the table: the old decision sent this to
        // the native column, which can only print the literal [formula] marker.
        assertEquals(ContentRenderPath.MIXED, contentRenderPath(elements))

        val label = accessibleContentLabelFor(elements)
        assertNotNull("the mixed view must carry its table cells: $label", label)
        val spoken = label.orEmpty()
        assertTrue("cell prose: $spoken", spoken.contains("speed"))
        assertTrue("formula inside the cell: $spoken", spoken.contains("v + 1"))

        // An option/explanation list mixing text with the same table takes the
        // same path; nothing about the decision depends on list position.
        val withText = listOf(ContentElement.TextElement("Table: ")) + elements
        assertEquals(ContentRenderPath.MIXED, contentRenderPath(withText))
        val textLabel = accessibleContentLabelFor(withText).orEmpty()
        assertTrue("text and table are spoken: $textLabel", textLabel.contains("Table: speed"))
    }

    @Test
    fun plainTextQuestionKeepsLegacyPathWithoutExtraLabel() {
        val plain = listOf(ContentElement.TextElement("What is 2+2?"))
        assertEquals(ContentRenderPath.PLAIN_TEXT, contentRenderPath(plain))
        assertNull("plain text must not get a mixed-view label", accessibleContentLabelFor(plain))

        val twoRuns = listOf(ContentElement.TextElement("a"), ContentElement.TextElement("b"))
        assertEquals(ContentRenderPath.NATIVE, contentRenderPath(twoRuns))
        assertNull(accessibleContentLabelFor(twoRuns))
    }

    @Test
    fun mathEntitiesAreSpokenAsReadableCharacters() {
        val elements = listOf(
            ContentElement.TextElement("Area: "),
            ContentElement.MathElement("<math><mi>A</mi><mo>&times;</mo><mn>2</mn></math>")
        )
        assertTrue("decoded sign: ${accessibleContentText(elements)}",
            accessibleContentText(elements).contains("A×2"))
        assertTrue("only the mixed view is labelled", accessibleContentLabelFor(elements) != null)
    }

    @Test
    fun structuredMathIsSpokenWithMeaning() {
        // The visual renderer still uses mathMlLinearText ("ab" for a
        // fraction); only the accessibility speech names the structure.
        assertEquals("a over b",
            accessibleMathText("<math><mfrac><mi>a</mi><mi>b</mi></mfrac></math>"))
        assertEquals("square root of x",
            accessibleMathText("<math><msqrt><mi>x</mi></msqrt></math>"))
        assertEquals("root of x with index 3",
            accessibleMathText("<math><mroot><mi>x</mi><mn>3</mn></mroot></math>"))
        assertEquals("x superscribed 2",
            accessibleMathText("<math><msup><mi>x</mi><mn>2</mn></msup></math>"))
        assertEquals("x subscripted 1",
            accessibleMathText("<math><msub><mi>x</mi><mn>1</mn></msub></math>"))
        assertEquals("x subscripted 1 superscribed 2",
            accessibleMathText("<math><msubsup><mi>x</mi><mn>1</mn><mn>2</mn></msubsup></math>"))
    }

    @Test
    fun fractionAndCellMathSpeakStructureInFallback() {
        val elements = listOf(
            ContentElement.TextElement("Half: "),
            ContentElement.MathElement("<math><mfrac><mn>1</mn><mn>2</mn></mfrac></math>"),
            ContentElement.TableElement(listOf(listOf("<math><msqrt><mi>y</mi></msqrt></math>")))
        )
        val spoken = accessibleContentText(elements)
        assertTrue("fraction: $spoken", spoken.contains("1 over 2"))
        assertTrue("root inside a cell: $spoken", spoken.contains("square root of y"))
    }

    @Test
    fun cellMathAngleBracketsAreNotReinterpretedAsMarkup() {
        val elements = listOf(
            ContentElement.TextElement("Compare "),
            ContentElement.MathElement("<math><mi>x</mi></math>"),
            ContentElement.TableElement(
                listOf(listOf("<math><mo>&lt;</mo><mi>b</mi><mo>&gt;</mo></math>"))
            )
        )
        val spoken = accessibleContentText(elements)
        assertTrue("spoken math must stay text, not a <b> tag: $spoken", spoken.contains("<b>"))
    }

    @Test
    fun pictureInsideACellFormulaIsSpokenAsTheImageMarker() {
        val elements = listOf(
            ContentElement.TableElement(
                listOf(
                    listOf(
                        "before",
                        "<math><mi>x</mi><img src=\"data:image/png;base64,AAAA\"/></math>"
                    )
                )
            )
        )
        val spoken = accessibleContentText(elements)
        // The visual path marks it; the spoken one must match instead of
        // dropping the unknown <img> silently (or reading its data URI).
        assertTrue("picture is announced: $spoken", spoken.contains(CELL_IMAGE_MARKER))
        assertTrue("the formula is still spoken: $spoken", spoken.contains("x"))
        assertFalse("never the raw data URI: $spoken", spoken.contains("base64,"))
    }

    @Test
    fun pictureInsideACellIsSpokenAsTheImageMarkerNeverBase64() {
        val elements = listOf(
            ContentElement.TableElement(
                listOf(
                    listOf(
                        "see <img src=\"data:image/png;base64,AAAA\"/> now",
                        "<img src=\"http://example.com/p.png\"/>"
                    )
                )
            )
        )
        val spoken = accessibleContentText(elements)
        // A picture is announced exactly like a top-level image; the payload
        // and the remote URL are never read out.
        assertTrue("picture is announced: $spoken", spoken.contains(CELL_IMAGE_MARKER))
        assertTrue("surrounding text still spoken: $spoken", spoken.contains("see"))
        assertFalse("never the raw data URI: $spoken", spoken.contains("base64,"))
        assertFalse("never a remote URL: $spoken", spoken.contains("example.com"))
    }

    @Test
    fun formulaAndPictureInOneCellKeepTheirSpokenOrder() {
        val cell = "a <math><mi>x</mi></math> b <img src=\"data:image/png;base64,AAAA\"/> c"
        val spoken = accessibleContentText(
            listOf(ContentElement.TableElement(listOf(listOf(cell))))
        )
        val formulaAt = spoken.indexOf("x")
        val beforeAt = spoken.indexOf("b")
        val imageAt = spoken.indexOf(CELL_IMAGE_MARKER)
        val afterAt = spoken.indexOf("c")
        assertTrue("formula is spoken: $spoken", formulaAt >= 0)
        assertTrue("word between them survives: $spoken", beforeAt > formulaAt)
        assertTrue("picture follows the formula: $spoken", imageAt > beforeAt)
        assertTrue("text after the picture survives: $spoken", afterAt > imageAt)
        assertFalse("never the raw data URI: $spoken", spoken.contains("base64,"))
    }

    @Test
    fun truncatedPictureKeepsTheWordsAroundItWhenSpoken() {
        // The regex used to swallow everything after an unclosed <img, so
        // TalkBack heard "before [image]" and never "after".
        val cell = "before <img src=\"data:image/png;base64,AAAA\"/ after"
        val spoken = accessibleContentText(
            listOf(ContentElement.TableElement(listOf(listOf(cell))))
        )
        assertEquals("before $CELL_IMAGE_MARKER after", spoken)
        assertFalse("never the raw data URI: $spoken", spoken.contains("base64,"))
    }

    @Test
    fun unquotedTruncatedPictureKeepsTheWordsAroundItWhenSpoken() {
        // Unquoted, unclosed and no trailing `/`: nothing delimits the payload,
        // so only the tag-like `src=…` run may go — never the word after it.
        val cell = "before <img src=data:image/png;base64,AAAA after"
        val spoken = accessibleContentText(
            listOf(ContentElement.TableElement(listOf(listOf(cell))))
        )
        assertEquals("before $CELL_IMAGE_MARKER after", spoken)
        assertFalse("never the raw data URI: $spoken", spoken.contains("base64,"))
        // A bare `<img` at the end is a torn tag too, announced as the marker.
        assertEquals(
            "before $CELL_IMAGE_MARKER",
            accessibleContentText(
                listOf(ContentElement.TableElement(listOf(listOf("before <img"))))
            )
        )
    }

    @Test
    fun greaterThanInsideAQuotedAttributeNeverLeaksTheTagRemainder() {
        val cell = "<img alt=\"x>y\" src=\"data:image/png;base64,AAAA\">"
        val spoken = accessibleContentText(
            listOf(ContentElement.TableElement(listOf(listOf(cell))))
        )
        assertEquals(CELL_IMAGE_MARKER, spoken)
        assertFalse("never the raw data URI: $spoken", spoken.contains("base64,"))
        assertFalse("no attribute remnant is spoken: $spoken", spoken.contains("y"))
    }
}
