package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `parseToJsonElement` recurses once per nesting level, so a small file of
 * `[[[[...` blew the stack. StackOverflowError is an `Error`, so the import
 * screen's `catch (e: Exception)` never saw it and the app crashed instead of
 * reporting a parse failure.
 */
class JsonDepthGuardTest {

    private fun nest(depth: Int, open: String = "[", close: String = "]"): String =
        open.repeat(depth) + close.repeat(depth)

    @Test
    fun ordinaryFilesAreUnaffected() {
        LegacyParser.requireNestingDepth("""{"papers":[{"categories":[{"questions":[]}]}]}""")
        LegacyParser.requireNestingDepth("[]")
        LegacyParser.requireNestingDepth("")
    }

    @Test
    fun nestingAtTheLimitIsAccepted() {
        LegacyParser.requireNestingDepth(nest(LegacyParser.MAX_NESTING_DEPTH))
    }

    @Test
    fun nestingBeyondTheLimitIsRejected() {
        val e = runCatching {
            LegacyParser.requireNestingDepth(nest(LegacyParser.MAX_NESTING_DEPTH + 1))
        }.exceptionOrNull()
        assertTrue("expected a rejection, got $e", e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("${LegacyParser.MAX_NESTING_DEPTH}"))
    }

    @Test
    fun deepNestingIsRejectedRatherThanOverflowingTheStack() {
        // The real failure mode: 200k levels would recurse until the stack died.
        val bomb = nest(200_000)
        val e = runCatching { LegacyParser.requireNestingDepth(bomb) }.exceptionOrNull()
        assertTrue("expected a clean rejection, got $e", e is IllegalArgumentException)
    }

    @Test
    fun bracesInsideStringsDoNotCountAsNesting() {
        // A question may legitimately contain JSON as text.
        LegacyParser.requireNestingDepth("""{"text":"[[[[[[[[[[ not nesting"}""")
        LegacyParser.requireNestingDepth("""{"text":"escaped \" quote [[[ "}""")
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
