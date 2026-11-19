package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.util.ContentRenderPath
import com.mcqapp.util.accessibleContentLabelFor
import com.mcqapp.util.accessibleContentText
import com.mcqapp.util.accessibleMathText
import com.mcqapp.util.contentRenderPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
