package com.mcqapp

import com.mcqapp.data.docx.MathNode
import com.mcqapp.data.docx.decodeXmlEntities
import com.mcqapp.data.docx.mathMlLinearText
import com.mcqapp.data.docx.mathMlToOmml
import com.mcqapp.data.docx.ommlToMathMl
import com.mcqapp.data.docx.parseMathTree
import com.mcqapp.data.docx.parseXml
import com.mcqapp.data.normalizeMathText
import com.mcqapp.data.sanitizeMathml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MathML to OMML converter is the mirror of the DOCX reader's
 * ommlToMathMl: whatever the writer produces must convert back to the
 * same MathML (after normalization), because that is exactly what happens
 * when an exported `.docx` is re-imported.
 */
class MathMlToOmmlTest {

    /**
     * The reader wraps superscript/subscript parts in mrow; the writer does
     * not. Same tokens either way, so comparisons ignore mrow wrappers.
     */
    private fun canon(mathml: String): String =
        normalizeMathText(decodeXmlEntities(mathml))
            .replace("<mrow>", "")
            .replace("</mrow>", "")

    private fun roundTrip(mathml: String): Pair<String, Int> {
        val omml = mathMlToOmml(mathml)
        assertNotNull("supported subset must convert, got null for: $mathml", omml)
        val mNs = "http://schemas.openxmlformats.org/officeDocument/2006/math"
        val doc = parseXml(
            ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                "xmlns:m=\"$mNs\"><w:body><w:p><m:oMath>$omml</m:oMath></w:p></w:body></w:document>")
                .toByteArray(),
            "test document"
        )
        val oMath = doc.getElementsByTagName("m:oMath").item(0) as org.w3c.dom.Element
        val warnings = mutableListOf<String>()
        val back = ommlToMathMl(oMath, warnings, "test")
        assertNotNull("re-import must understand its own OMML: $omml", back)
        return back!! to warnings.size
    }

    @Test
    fun textRunsSurvive() {
        val mathml = "<math><mi>v</mi><mo>=</mo><mi>u</mi><mo>+</mo><mi>a</mi><mi>t</mi></math>"
        val (back, warnings) = roundTrip(mathml)
        assertEquals(0, warnings)
        assertEquals(
            canon(mathml),
            canon(back)
        )
    }

    @Test
    fun fractionSuperscriptAndRootSurvive() {
        val mathml =
            "<math><mi>x</mi><mo>=</mo><mfrac><mrow><mo>&#x2212;</mo><mi>b</mi></mrow><mrow>" +
                "<mn>2</mn><mi>a</mi></mrow></mfrac></math>"
        val (back, warnings) = roundTrip(mathml)
        assertEquals(0, warnings)
        assertEquals(
            canon(mathml),
            canon(back)
        )
    }

    @Test
    fun subscriptSurvives() {
        val mathml = "<math><msub><mi>S</mi><mi>n</mi></msub><mo>=</mo><mn>7</mn></math>"
        val (back, _) = roundTrip(mathml)
        // `back` is MathML again, not the OMML the writer emitted.
        assertTrue(back.contains("<msub>"))
        assertEquals(
            canon(mathml),
            canon(back)
        )
    }

    @Test
    fun squareRootSurvives() {
        val mathml = "<math><msqrt><mrow><msup><mi>b</mi><mn>2</mn></msup></mrow></msqrt></math>"
        val (back, _) = roundTrip(mathml)
        assertTrue("square root must round-trip through m:rad: $back", back.contains("msqrt"))
    }

    @Test
    fun namedAndNumericEntitiesDecode() {
        val omml = mathMlToOmml("<math><mo>&prime;</mo><mn>&#x00B1;</mn></math>")
        assertNotNull(omml)
        assertTrue("′ must decode", omml!!.contains("′"))
        assertTrue("± must decode", omml.contains("±"))
    }

    @Test
    fun unknownEntityStaysLiteral() {
        val omml = mathMlToOmml("<math><mi>&nosuchentity;</mi></math>")
        assertNotNull(omml)
        // Unknown references stay literal; the XML escape hides the &.
        assertTrue(omml!!.contains("nosuchentity;"))
    }

    @Test
    fun unsupportedConstructFallsBackToNull() {
        assertNull(mathMlToOmml("<math><munder><mi>x</mi><mo>^</mo></munder></math>"))
        assertNull(mathMlToOmml("<math><mtable><mtr><mtd><mi>x</mi></mtd></mtr></mtable></math>"))
        assertNull(mathMlToOmml("   "))
    }

    @Test
    fun wrongArityFallsBackToNull() {
        assertNull(mathMlToOmml("<math><msup><mi>x</mi></msup></math>"))
        assertNull(mathMlToOmml("<math><mfrac><mi>a</mi></mfrac></math>"))
    }

    @Test
    fun linearTextStripsTags() {
        val linear = mathMlLinearText("<math><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></math>")
        assertEquals("x2+1", linear)
    }

    private fun childOf(parent: MathNode.El, name: String): MathNode.El =
        parent.children.filterIsInstance<MathNode.El>().single { it.name == name }

    @Test
    fun aSelfClosingTagWithAttributesIsOneTag() {
        // The attribute group of the tag pattern was greedy, so
        // `<mtext id="x"/>` matched as an OPEN tag whose attributes happened to
        // end in "/" - it opened a container, and the next sibling became its
        // child instead of its sibling.
        val math = parseMathTree("<math><mtext id=\"x\"/><mi>b</mi></math>").single() as MathNode.El
        val mtext = childOf(math, "mtext")
        assertTrue("a self-closing tag has no children: ${mtext.children}", mtext.children.isEmpty())
        assertEquals(
            "the sibling must stay a sibling",
            listOf("mtext", "mi"),
            math.children.filterIsInstance<MathNode.El>().map { it.name }
        )
    }

    @Test
    fun layoutWhitespaceIsDroppedButTokenWhitespaceIsKept() {
        // The parser's own doc: whitespace-only text between tags is layout and
        // is dropped; character data inside a token element is content.
        val math = parseMathTree("<math>\n  <mi>x</mi>\n  <mtext> </mtext>\n</math>")
            .single() as MathNode.El
        assertTrue(
            "layout whitespace must not become a text node: ${math.children}",
            math.children.none { it is MathNode.Txt }
        )
        val mtext = childOf(math, "mtext")
        assertTrue(
            "whitespace inside mtext is content: ${mtext.children}",
            mtext.children.single() is MathNode.Txt
        )
    }

    @Test
    fun aSpaceElementDoesNotSwallowItsSiblings() {
        // MathLive writes a space as `<mspace width="0.5em"/>` and the app's
        // own sanitizer drops both the attributes and the self-closing slash,
        // so what reaches the converter is a bare `<mspace>`. Treated as a
        // container it swallowed every following sibling, and convertNode
        // drops a container's children - the rest of the formula vanished
        // silently instead of falling back with a warning.
        val stored = sanitizeMathml("<math><mi>a</mi><mspace width=\"0.5em\"/><mi>b</mi></math>")
        val omml = mathMlToOmml(stored)
        assertNotNull("the stored formula must convert: $stored", omml)
        assertTrue("the space must not eat b: $omml", omml!!.contains(">a<") && omml.contains(">b<"))

        // The unsanitized spelling is a space element too.
        val raw = mathMlToOmml("<math><mi>a</mi><mspace/><mi>b</mi></math>")
        assertNotNull(raw)
        assertTrue("nor may the self-closing form: $raw", raw!!.contains(">b<"))
    }

    @Test
    fun aMathElementInsideAFragmentIsTransparent() {
        // A fragment whose forest is not a lone <math> root: the root was
        // walked as an ordinary node, matched no construct and refused the
        // whole equation.
        val omml = mathMlToOmml("x<math><mi>y</mi></math>")
        assertNotNull(omml)
        assertTrue("the fragment must convert: $omml", omml!!.contains(">x<") && omml.contains(">y<"))
    }
}
