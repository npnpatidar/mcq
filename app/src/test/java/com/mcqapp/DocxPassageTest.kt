package com.mcqapp

import com.mcqapp.data.docx.parseDocx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DOCX `Passage:` marker (D4): questions until the next marker belong to
 * the passage, questions before any marker stay standalone, and the `N.)`
 * split is untouched. Options are line-start markers like everywhere else, so
 * each option is its own paragraph in these documents.
 */
class DocxPassageTest {

    private val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun docxOf(documentXml: String): ByteArray {
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

    /** A document skeleton the reader accepts: namespaces on the root, w:body. */
    private fun document(paragraphs: List<String>): String =
        "<w:document xmlns:w=\"$W_NS\"><w:body>" +
            paragraphs.joinToString("") +
            "</w:body></w:document>"

    private fun para(text: String): String =
        "<w:p><w:r><w:t>$text</w:t></w:r></w:p>"

    /** One question as its own paragraphs: stem, four options, answer, explanation. */
    private fun question(num: String, stem: String, ans: String, exp: String): List<String> =
        listOf(
            para("$num.) $stem"),
            para("(a) one"),
            para("(b) two"),
            para("(c) three"),
            para("(d) four"),
            para("Ans. $ans"),
            para("Exp: $exp")
        )

    @Test
    fun passageMarkerGroupsFollowingQuestions() {
        val result = parseDocx(docxOf(document(listOf(
            question("1", "Standalone question", "a", "none."),
            listOf(para("Passage: The water cycle")),
            listOf(para("Rain fills rivers.")),
            question("2", "Water evaporates due to", "a", "Solar heat drives evaporation."),
            question("3", "Condensation forms", "b", "Vapor cools into droplets.")
        ).flatten())))
        val root = kotlinx.serialization.json.Json.parseToJsonElement(result.json)
            .let { it as kotlinx.serialization.json.JsonObject }
        val questions = root["questions"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(3, questions.size)
        val passages = root["passages"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(1, passages.size)
        val passage = passages.first().let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("passage-1", passage["id"]!!.let { it as kotlinx.serialization.json.JsonPrimitive }.content)
        assertEquals("The water cycle", passage["title"]!!.let { it as kotlinx.serialization.json.JsonPrimitive }.content)
        // The passage body is the context text only, never its questions.
        val body = passage["elements"]!!.let { it as kotlinx.serialization.json.JsonArray }
            .joinToString("") { el ->
                ((el as kotlinx.serialization.json.JsonObject)["content"]
                    as kotlinx.serialization.json.JsonPrimitive).content
            }
        assertTrue(body.contains("Rain fills rivers."))
        assertTrue(!body.contains("Water evaporates"))
        // The pre-marker question stays standalone; the following two are members.
        assertTrue(firstMember(questions[0], "passageId") == null)
        assertEquals("passage-1", firstMember(questions[1], "passageId"))
        assertEquals("passage-1", firstMember(questions[2], "passageId"))
    }

    @Test
    fun noPassageMarkerKeepsLegacyOutputShape() {
        val result = parseDocx(docxOf(document(listOf(
            para("1.) Plain question"),
            para("(a) x"),
            para("(b) y"),
            para("(c) z"),
            para("(d) w"),
            para("Ans. a"),
            para("Exp: because.")
        ))))
        // No passages: the output is still the bare questions array.
        val root = kotlinx.serialization.json.Json.parseToJsonElement(result.json)
        assertTrue(root is kotlinx.serialization.json.JsonArray)
        assertEquals(1, (root as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun twoPassagesSplitMembership() {
        val result = parseDocx(docxOf(document(listOf(
            listOf(para("Passage: One")),
            listOf(para("Body one.")),
            question("1", "Q one", "a", "e."),
            listOf(para("Passage: Two")),
            listOf(para("Body two.")),
            question("2", "Q two", "b", "e.")
        ).flatten())))
        val root = kotlinx.serialization.json.Json.parseToJsonElement(result.json)
            .let { it as kotlinx.serialization.json.JsonObject }
        val questions = root["questions"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(2, questions.size)
        assertEquals("passage-1", firstMember(questions[0], "passageId"))
        assertEquals("passage-2", firstMember(questions[1], "passageId"))
    }

    @Test
    fun docxWrapperParsesIntoAPaperWithItsPassages() {
        // The wrapper ({"questions", "passages"}) is what the import screen
        // feeds to LegacyParser; if the root object lost its questions the
        // whole Word import would report "No papers found".
        val result = parseDocx(docxOf(document(listOf(
            question("1", "Standalone", "a", "none."),
            listOf(para("Passage: The water cycle")),
            listOf(para("Rain fills rivers.")),
            question("2", "Q one", "a", "e.")
        ).flatten())))
        val file = com.mcqapp.data.io.LegacyParser.parse(result.json)
        assertEquals(1, file.passages.size)
        assertEquals("passage-1", file.passages.single().id)
        assertEquals("The water cycle", file.passages.single().title)
        val paper = file.papers.single()
        val questions = paper.categories.single().questions
        assertEquals(2, questions.size)
        assertEquals(null, questions[0].passageId)
        assertEquals("passage-1", questions[1].passageId)
    }

    private fun firstMember(element: kotlinx.serialization.json.JsonElement, name: String): String? =
        (element as? kotlinx.serialization.json.JsonObject)?.get(name)
            ?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content
}
