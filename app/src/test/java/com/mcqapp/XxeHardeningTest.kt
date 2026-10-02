package com.mcqapp

import com.mcqapp.data.docx.parseXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The XML parser is the app's only entry point for untrusted markup. A
 * Word part must not be able to pull in an external entity, and if the parser
 * cannot be locked down the part has to be rejected rather than parsed anyway.
 */
class XxeHardeningTest {

    private val doctypePayload = """
        <?xml version="1.0"?>
        <!DOCTYPE root [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
        </w:document>
    """.trimIndent()

    @Test
    fun aDoctypeCarryingExternalEntityIsRejected() {
        val e = runCatching { parseXml(doctypePayload.toByteArray(), "document.xml") }.exceptionOrNull()
        // The doctype ban rejects it outright. What must never happen is the
        // external entity resolving and the file's contents leaking in.
        if (e == null) fail("a DOCTYPE carrying an entity must not be accepted")
        assertTrue("message was: $e", e is IllegalArgumentException)
    }

    @Test
    fun aPlainWordPartStillParses() {
        val doc = parseXml(
            """
            <?xml version="1.0"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>hello</w:t></w:r></w:p></w:body>
            </w:document>
            """.trimIndent().toByteArray(),
            "document.xml"
        )
        assertEquals("w:document", doc.documentElement.tagName)
    }

    @Test
    fun malformedXmlStillReportsThePart() {
        val e = runCatching { parseXml("<not xml".toByteArray(), "media/x.xml") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("media/x.xml"))
    }
}
