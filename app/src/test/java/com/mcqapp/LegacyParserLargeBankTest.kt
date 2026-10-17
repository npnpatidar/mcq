package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The failure this guards: a 48 MB, 169k-question bank was parsed as a whole
 * document DOM, which exhausted the device heap and died with an
 * OutOfMemoryError inside the parser. The parser now streams, so a bank of
 * this shape must parse in bounded memory and keep every card.
 */
class LegacyParserLargeBankTest {

    @Test
    fun aHundredThousandQuestionBankStreamsThrough() {
        val file = File.createTempFile("big-bank", ".json")
        try {
            val categories = 20
            val perCategory = 5_000
            file.bufferedWriter().use { w ->
                w.write("""{"version":2,"papers":[{"id":"p1","title":"Big Bank",""")
                w.write(""""categories":[""")
                for (c in 0 until categories) {
                    if (c > 0) w.write(",")
                    w.write("""{"id":"c$c","title":"Cat $c","questions":[""")
                    for (q in 0 until perCategory) {
                        if (q > 0) w.write(",")
                        // Legacy field names (question_elements/content), a
                        // \u-escaped stem with realistic body text, four
                        // options — the real bank's shape and density.
                        w.write(
                            """{"question_elements":[{"type":"text",""" +
                                """"content":"q-$c-$q\u0928\u093f ${"padding ".repeat(30)}"""" +
                                """}],"options":[""" +
                                """"Element $q","Two","Three","Four"""" +
                                """],"correct":["Element $q"]}"""
                        )
                    }
                    w.write("]}")
                }
                w.write("]}]}")
            }
            assertTrue("test bank should be tens of MB: ${file.length()}", file.length() > 30_000_000)

            val parsed = LegacyParser.parse(file.inputStream())

            assertEquals(1, parsed.papers.size)
            val paper = parsed.papers.first()
            assertEquals(categories.toLong(), paper.categories.size.toLong())
            val total = paper.categories.sumOf { it.questions.size.toLong() }
            assertEquals((categories * perCategory).toLong(), total)
            // Every question survived with its options and text intact.
            val sample = paper.categories[3].questions[42]
            assertEquals(4L, sample.options.size.toLong())
            // The \u escapes decoded to real text, not escape soup.
            assertTrue(sample.text.contains("नि"))
            assertTrue(sample.text.contains("q-3-42"))
            // Ids stay unique and deterministic without authored ids.
            val ids = paper.categories.flatMap { it.questions }.map { it.id }
            assertEquals(ids.size.toLong(), ids.toHashSet().size.toLong())
            // Plain-string options keep their text; the stem keeps its serial.
            assertEquals("Element 42", sample.options.first().text)
            // Spot the last card: a truncated stream would lose the tail.
            val last = paper.categories.last().questions.last()
            assertTrue(last.text.contains("q-19-4999"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun duplicatesAreSuffixedRatherThanDroppedWhileStreaming() {
        val json = """
            {"papers":[{"id":"p1","categories":[{"id":"c1","questions":[
            {"id":"dup","text":"a","options":["1"],"correct":["1"]},
            {"id":"dup","text":"b","options":["2"],"correct":["2"]}]}]}]}
        """.trimIndent()
        val questions = LegacyParser.parse(json).papers.first().categories.first().questions
        assertEquals(listOf("dup", "dup-2"), questions.map { it.id })
    }

    @Test
    fun theParserNeverBuildsAWholeDocumentDom() {
        // The OOM this guards came from parseToJsonElement materialising
        // every node of a 48 MB bank. The stream entry point and the slice
        // reader are the only ways in now.
        val source = java.io.File(
            "src/main/java/com/mcqapp/data/io/LegacyParser.kt"
        ).readText()
        assertTrue(
            "the parser must read from a stream",
            source.contains("fun parse(input: InputStream)")
        )
        assertTrue(
            "no whole-document DOM may stay in the parser",
            !source.contains("parseToJsonElement")
        )
    }
}
