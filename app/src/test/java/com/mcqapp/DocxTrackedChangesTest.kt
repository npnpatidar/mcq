package com.mcqapp

import com.mcqapp.data.docx.parseDocx
import com.mcqapp.data.io.LegacyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A document edited in Word over Track Changes, imported before the revisions
 * were accepted, has to read as the *accepted* document would: inserted text is
 * real content, deleted (or moved-out) text is not. The reader used to drop the
 * insertions silently (they fell into the paragraph reader's no-content branch)
 * and keep the deletions struck, so an edit made the ordinary way — a question
 * fixed over Track Changes — imported as if it never happened.
 *
 * The fixtures mirror Word's shape: revision children at the paragraph level,
 * runs inside carrying their own `w:rPr`.
 */
class DocxTrackedChangesTest {

    /** The minimal marker-format document, as [Word] stores it. */
    private fun document(body: String): ByteArray {
        val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val documentXml = "<w:document xmlns:w=\"$W_NS\"><w:body>$body</w:body></w:document>"
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray())
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    /** One complete question whose stem is [stemBody] (other fields fixed). */
    private fun questionStem(stemBody: String): String =
        "\u003Cw:p\u003E$stemBody\u003C/w:p\u003E" +
            "\u003Cw:p\u003E\u003Cw:r\u003E\u003Cw:t\u003E(a) one\u003C/w:t\u003E\u003C/w:r\u003E\u003C/w:p\u003E" +
            "\u003Cw:p\u003E\u003Cw:r\u003E\u003Cw:t\u003E(b) two\u003C/w:t\u003E\u003C/w:r\u003E\u003C/w:p\u003E" +
            "\u003Cw:p\u003E\u003Cw:r\u003E\u003Cw:t\u003E(c) three\u003C/w:t\u003E\u003C/w:r\u003E\u003C/w:p\u003E" +
            "\u003Cw:p\u003E\u003Cw:r\u003E\u003Cw:t\u003E(d) four\u003C/w:t\u003E\u003C/w:r\u003E\u003C/w:p\u003E" +
            "\u003Cw:p\u003E\u003Cw:r\u003E\u003Cw:t\u003EAns. a\u003C/w:t\u003E\u003C/w:r\u003E\u003C/w:p\u003E" +
            "\u003Cw:p\u003E\u003Cw:r\u003E\u003Cw:t\u003EExp: because\u003C/w:t\u003E\u003C/w:r\u003E\u003C/w:p\u003E"

    private fun stemOf(bytes: ByteArray): String {
        val parsed = parseDocx(bytes)
        val file = LegacyParser.parse(parsed.json)
        val question = file.papers[0].categories[0].questions[0]
        assertNotNull("the question must import: ${parsed.warnings}", question)
        return question.elements.joinToString("") {
            (it as com.mcqapp.domain.ContentElement.TextElement).text
        }
    }

    @Test
    fun anUnacceptedInsertionIsImportedAsContent() {
        val bytes = document(
            questionStem(
                "\u003Cw:r\u003E\u003Cw:t\u003E1.) Speed of \u003C/w:t\u003E\u003C/w:r\u003E" +
                    "\u003Cw:ins w:id=\"1\" w:author=\"A\"\u003E" +
                    "\u003Cw:r\u003E\u003Cw:t\u003Elight\u003C/w:t\u003E\u003C/w:r\u003E" +
                    "\u003C/w:ins\u003E" +
                    "\u003Cw:r\u003E\u003Cw:t\u003E in vacuum?\u003C/w:t\u003E\u003C/w:r\u003E"
            )
        )
        val stem = stemOf(bytes)
        assertTrue(
            "the inserted text must be part of the stem: '$stem'",
            stem.contains("light")
        )
        assertTrue("no warning is needed for an applied insertion", firstParseWarnings(bytes).isEmpty())
    }

    @Test
    fun anUnacceptedDeletionIsRefusedAndWarned() {
        val bytes = document(
            questionStem(
                "\u003Cw:r\u003E\u003Cw:t\u003E1.) Speed of light\u003C/w:t\u003E\u003C/w:r\u003E" +
                    "\u003Cw:del w:id=\"2\" w:author=\"A\"\u003E" +
                    "\u003Cw:r\u003E\u003Cw:delText\u003E, more or less\u003C/w:delText\u003E\u003C/w:r\u003E" +
                    "\u003C/w:del\u003E" +
                    "\u003Cw:r\u003E\u003Cw:t\u003E in vacuum?\u003C/w:t\u003E\u003C/w:r\u003E"
            )
        )
        val stem = stemOf(bytes)
        assertTrue(
            "the deleted text must not stay in the stem: '$stem'",
            !stem.contains("more or less")
        )
        assertTrue(
            "the applied deletion must say so: ${firstParseWarnings(bytes)}",
            firstParseWarnings(bytes).any { it.contains("tracked change") }
        )
    }

    @Test
    fun aMoveOutIsRefusedLikeADeletionAndAMoveToIsImported() {
        val bytes = document(
            questionStem(
                "\u003Cw:r\u003E\u003Cw:t\u003E1.) Speed of \u003C/w:t\u003E\u003C/w:r\u003E" +
                    "\u003Cw:moveFrom w:id=\"3\" w:author=\"A\"\u003E" +
                    "\u003Cw:r\u003E\u003Cw:t\u003EWORD_MOVED-away\u003C/w:t\u003E\u003C/w:r\u003E" +
                    "\u003C/w:moveFrom\u003E" +
                    "\u003Cw:moveTo w:id=\"4\" w:author=\"A\"\u003E" +
                    "\u003Cw:r\u003E\u003Cw:t\u003Elight\u003C/w:t\u003E\u003C/w:r\u003E" +
                    "\u003C/w:moveTo\u003E" +
                    "\u003Cw:r\u003E\u003Cw:t\u003E in vacuum?\u003C/w:t\u003E\u003C/w:r\u003E"
            )
        )
        val stem = stemOf(bytes)
        assertEquals(
            "the accepted text is exactly the inserted/moved-in run",
            "Speed of light in vacuum?",
            stem.trim()
        )
        assertTrue(
            "the applied move-out must say so: ${firstParseWarnings(bytes)}",
            firstParseWarnings(bytes).any { it.contains("tracked change") }
        )
    }

    /** The reader's own warnings list (parseDocx folds the reader's into its result). */
    private fun firstParseWarnings(bytes: ByteArray): List<String> = parseDocx(bytes).warnings

    /** Guards the shape change the fix makes: empty revisions stay silent. */
    @Test
    fun anEmptyRevisionContainerIsSilent() {
        val bytes = document(
            questionStem(
                "\u003Cw:r\u003E\u003Cw:t\u003E1.) Speed of light in vacuum?\u003C/w:t\u003E\u003C/w:r\u003E" +
                    "\u003Cw:ins w:id=\"5\" w:author=\"A\"/\u003E"
            )
        )
        assertEquals(1, LegacyParser.parse(parseDocx(bytes).json).papers[0].categories[0].questions.size)
        assertTrue(firstParseWarnings(bytes).isEmpty())
        // Silence check equal on deletions too.
        val deletionOnly = questionStem(
            "\u003Cw:r\u003E\u003Cw:t\u003E1.) Speed of light in vacuum?\u003C/w:t\u003E\u003C/w:r\u003E" +
                "\u003Cw:del w:id=\"6\" w:author=\"A\"/\u003E"
        ).let { document(it) }
        assertTrue(firstParseWarnings(deletionOnly).isEmpty())
    }
}
