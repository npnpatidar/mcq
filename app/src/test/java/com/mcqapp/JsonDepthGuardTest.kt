package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The parser streams now, so the depth budget lives in the slice reader: a
 * slice nested deeper than the limit is refused, and the token walk beneath
 * it iterates instead of recursing one level per document level. A hostile
 * `[[[[...` document of 200k levels used to blow either the stack or — via
 * a whole-document DOM — the heap; both are Errors the import screen's
 * `catch (e: Exception)` never saw, so the app crashed instead of reporting
 * a parse failure.
 */
class JsonDepthGuardTest {

    private fun nest(depth: Int, open: String = "[", close: String = "]"): String =
        open.repeat(depth) + close.repeat(depth)

    @Test
    fun ordinaryFilesAreUnaffected() {
        LegacyParser.parse("""{"papers":[{"categories":[{"questions":[]}]}]}""")
        LegacyParser.parse("[]")
        LegacyParser.parse("")
    }

    @Test
    fun nestingAtTheLimitIsAccepted() {
        val file = LegacyParser.parse(nest(LegacyParser.MAX_NESTING_DEPTH))
        assertTrue(file.papers.isNotEmpty())
    }

    @Test
    fun nestingBeyondTheLimitIsRejected() {
        // The root array the parser opens itself spends one bracket, so the
        // slice budget runs out two brackets deeper than the raw count.
        val e = runCatching {
            LegacyParser.parse(nest(LegacyParser.MAX_NESTING_DEPTH + 3))
        }.exceptionOrNull()
        assertTrue("expected a rejection, got $e", e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("${LegacyParser.MAX_NESTING_DEPTH}"))
    }

    @Test
    fun deepNestingIsRejectedRatherThanOverflowingTheStack() {
        // The real failure mode: 200k levels would recurse until the stack died.
        val bomb = nest(200_000)
        val e = runCatching { LegacyParser.parse(bomb) }.exceptionOrNull()
        assertTrue("expected a clean rejection, got $e", e is IllegalArgumentException)
    }

    @Test
    fun bracesInsideStringsDoNotCountAsNesting() {
        // A question may legitimately contain JSON as text.
        LegacyParser.parse("""{"text":"[[[[[[[[[[ not nesting"}""")
        LegacyParser.parse("""{"text":"escaped \" quote [[[ "}""")
    }

    @Test
    fun parseReportsDeepNestingAsAnIllegalArgument() {
        try {
            LegacyParser.parse(nest(200_000))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("deep"))
        }
    }

    @Test
    fun aNormalFileStillParses() {
        val json = """
            {"papers":[{"id":"p1","title":"P","categories":[{"id":"c1","title":"C",
            "questions":[{"id":"q1","text":"2+2?","options":[{"id":"a","text":"4"}],
            "correct":["a"]}]}]}]}
        """.trimIndent()
        val file = LegacyParser.parse(json)
        assertEquals(1, file.papers.size)
        assertEquals(1, file.papers.first().categories.first().questions.size)
    }
}
