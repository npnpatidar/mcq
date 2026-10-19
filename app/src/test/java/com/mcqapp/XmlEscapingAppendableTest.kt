package com.mcqapp

import com.mcqapp.data.export.XmlEscapingAppendable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The DOCX writer streams `docProps/custom.xml` through this appendable so the
 * part never exists as an escaped copy of itself. That only holds while the
 * class honours the `Appendable` contract, which is what these pin: an append
 * returns the appendable, so a caller that chains keeps escaping.
 *
 * Plain JVM: the class touches nothing but `Appendable`.
 */
class XmlEscapingAppendableTest {

    @Test
    fun everyAppendReturnsTheAppendableItself() {
        val out = StringBuilder()
        val xml = XmlEscapingAppendable(out)
        assertSame("append(Char) must return the appendable", xml, xml.append('a'))
        assertSame("append(CharSequence) must return the appendable", xml, xml.append("b"))
        assertSame("append(CharSequence, Int, Int) must return the appendable", xml, xml.append("cd", 0, 1))
    }

    @Test
    fun aChainedAppendIsStillEscaped() {
        // `xml.append('a').append(" & <b>")` used to hand the wrapped writer
        // back, so the second append wrote straight into the entry and the text
        // reached the part unescaped. A chain starting with a Char append is
        // what nothing does today - which is why this is a contract test rather
        // than an export test.
        val out = StringBuilder()
        XmlEscapingAppendable(out).append('a').append(" & <b>")
        assertEquals("a &amp; &lt;b&gt;", out.toString())
    }

    @Test
    fun eachAppendEscapesWhatItWrites() {
        val out = StringBuilder()
        val xml = XmlEscapingAppendable(out)
        xml.append('&').append('<').append('>').append("ok")
        assertEquals("&amp;&lt;&gt;ok", out.toString())
    }

    @Test
    fun ordinaryTextIsPassedThroughUnchanged() {
        val out = StringBuilder()
        val xml = XmlEscapingAppendable(out)
        xml.append("{\"id\":").append('7').append("}")
        assertEquals("{\"id\":7}", out.toString())
    }
}
