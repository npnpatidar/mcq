package com.mcqapp

import com.mcqapp.data.docx.parseXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The XML parser is the app's only entry point for untrusted markup. A
 * Word part must not be able to pull in an external entity, and if the parser
 * cannot be locked down the part has to be rejected rather than parsed anyway.
 */
class XxeHardeningTest {

    private val wordNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private val doctypePayload = """
        <?xml version="1.0"?>
        <!DOCTYPE root [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
        </w:document>
    """.trimIndent()

    @Test
    fun aDoctypeCarryingExternalEntityCanNeverReadAnything() {
        // Two acceptable outcomes, depending on what the platform parser
        // supports: the part is refused outright, or it parses with the
        // entity resolving to nothing. The one unacceptable outcome is the
        // local file's contents ending up in the document.
        val parsed = runCatching { parseXml(doctypePayload.toByteArray(), "document.xml") }
        if (parsed.isFailure) {
            assertTrue(
                "a refusal must be reported as an unreadable Word part",
                parsed.exceptionOrNull() is IllegalArgumentException
            )
            return
        }
        val doc = parsed.getOrThrow()
        val nodes = doc.getElementsByTagNameNS(wordNs, "t")
        val text = (0 until nodes.length).joinToString("") { nodes.item(it).textContent }
        assertEquals("the entity must not expand", "", text)
        assertFalse("no file contents may leak in", text.contains("root:"))
    }

    @Test
    fun anOrdinaryWordPartStillParses() {
        val doc = parseXml(
            """
            <?xml version="1.0"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>hello</w:t></w:r></w:p></w:body>
            </w:document>
            """.trimIndent().toByteArray(),
            "document.xml"
        )
        val nodes = doc.getElementsByTagNameNS(wordNs, "t")
        assertEquals("hello", nodes.item(0).textContent)
    }

    @Test
    fun malformedXmlStillReportsThePart() {
        val e = runCatching { parseXml("<not xml".toByteArray(), "media/x.xml") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("media/x.xml"))
    }
}
