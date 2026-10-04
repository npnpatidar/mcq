package com.mcqapp

import com.mcqapp.data.docx.splitDocxMarkersForTest
import com.mcqapp.data.io.LegacyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Word banks that need several right answers, or more than four options, could
 * not be expressed at all.
 *
 * Both failed *silently*, which is worse than a refusal:
 *
 *  - only `(d)`, `(c)`, `(b)`, `(a)` were ever split off, so `(e)` and `(f)`
 *    stayed inside option (d)'s text and the question imported successfully
 *    with a corrupted option;
 *  - `Ans. b, c` reached the importer as one string, matched no single option,
 *    and the question imported **ungraded** — looking fine, scoring nothing.
 *
 * So these cases are pinned in both directions: the new forms work, and a
 * malformed one is now refused instead of quietly mangled.
 */
class DocxMultiAnswerTest {

    private fun parse(coded: String) = splitDocxMarkersForTest(coded)

    @Test
    fun sixOptionsAreAllKeptSeparate() {
        val parsed = parse(
            "1.) Which planet is largest?\n" +
                "(a) Earth\n(b) Jupiter\n(c) Saturn\n(d) Neptune\n(e) Uranus\n(f) Mars\n" +
                "Ans. b\nExp: Jupiter is the largest."
        )
        val q = parsed.single()
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), q.options.keys.toList())
        assertEquals("Jupiter", q.options["b"])
        assertEquals("Uranus", q.options["e"])
        assertEquals("Mars", q.options["f"])
        // The old parser folded (e) and (f) into (d).
        assertEquals("Neptune", q.options["d"])
    }

    @Test
    fun tenOptionsAreAccepted() {
        val letters = ('a'..'j').joinToString("\n") { "($it) Option $it" }
        val parsed = parse("1.) Stem\n$letters\nAns. j\nExp: Because.")
        assertEquals(10, parsed.single().options.size)
        assertEquals("Option j", parsed.single().options["j"])
    }

    @Test
    fun severalCorrectAnswersResolve() {
        val parsed = parse(
            "1.) Select every even number.\n" +
                "(a) 1\n(b) 2\n(c) 3\n(d) 4\n(e) 5\n(f) 6\n" +
                "Ans. b, d, f\nExp: Even numbers end in 0, 2, 4, 6 or 8."
        )
        assertEquals(listOf("b", "d", "f"), parsed.single().correctIds)
    }

    @Test
    fun answerSeparatorsAreAllAccepted() {
        fun ids(answerLine: String) = parse(
            "1.) Stem\n(a) A\n(b) B\n(c) C\n(d) D\n$answerLine\nExp: Because."
        ).single().correctIds

        assertEquals(listOf("b", "d"), ids("Ans. b, d"))
        assertEquals(listOf("b", "d"), ids("Ans. b and d"))
        assertEquals(listOf("b", "d"), ids("Ans. b & d"))
        assertEquals(listOf("b", "d"), ids("Ans. b,d"))
        assertEquals(listOf("b", "d"), ids("Ans. B, D"))
    }

    @Test
    fun aSingleAnswerStillWorksExactlyAsBefore() {
        val parsed = parse("1.) Stem\n(a) A\n(b) B\n(c) C\n(d) D\nAns. c\nExp: Because.")
        assertEquals(listOf("c"), parsed.single().correctIds)
        assertEquals("c", parsed.single().answer)
    }

    @Test
    fun anAnswerNamingAMissingOptionIsRefused() {
        // Used to import as an ungraded question with no warning at all.
        try {
            parse("1.) Stem\n(a) A\n(b) B\n(c) C\n(d) D\nAns. e\nExp: Because.")
            fail("expected an answer naming a missing option to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should name the problem: ${e.message}",
                e.message!!.contains("(e)", ignoreCase = true)
            )
        }
    }

    @Test
    fun aGapInTheOptionLettersIsRefused() {
        // (d) missing: the old parser reported only "missing option (d)"; the
        // author still needs to know (e) cannot appear without it.
        try {
            parse("1.) Stem\n(a) A\n(b) B\n(c) C\n(e) E\nAns. a\nExp: Because.")
            fail("expected a gap in the option labels to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("option", ignoreCase = true))
        }
    }

    @Test
    fun tooManyOptionsAreRefused() {
        val letters = ('a'..'k').joinToString("\n") { "($it) Option $it" }
        try {
            parse("1.) Stem\n$letters\nAns. a\nExp: Because.")
            fail("expected more than ten options to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should mention the limit: ${e.message}",
                e.message!!.contains("(j)", ignoreCase = true)
            )
        }
    }

    @Test
    fun aDuplicateOptionLabelIsRefused() {
        try {
            parse("1.) Stem\n(a) A\n(b) B\n(b) B again\n(c) C\n(d) D\nAns. a\nExp: Why.")
            fail("expected a repeated option label to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("(b)", ignoreCase = true))
        }
    }

    @Test
    fun aNumericOrTextAnswerStillResolvesThroughTheImporter() {
        // The zero-based index and exact-text forms predate this change and must
        // keep working: neither names an option letter.
        val byIndex = parse("1.) Stem\n(a) A\n(b) B\n(c) C\n(d) D\nAns. 2\nExp: Why.")
        assertEquals("2", byIndex.single().answer)
        assertEquals(emptyList<String>(), byIndex.single().correctIds)

        val byText = parse("1.) Stem\n(a) Alpha\n(b) Beta\n(c) C\n(d) D\nAns. Beta\nExp: Why.")
        assertEquals("Beta", byText.single().answer)
        assertEquals(emptyList<String>(), byText.single().correctIds)
    }

    @Test
    fun aMultiAnswerQuestionScoresAllOfItsChoices() {
        val json = parse(
            "1.) Select every even number.\n" +
                "(a) 1\n(b) 2\n(c) 3\n(d) 4\n(e) 5\n(f) 6\n" +
                "Ans. b, d, f\nExp: Even numbers end in 0, 2, 4, 6 or 8."
        ).single().let { q -> com.mcqapp.data.docx.questionJsonForTest(q).toString() }
        val question = LegacyParser.parse("[$json]").papers[0].categories[0].questions.single()
        assertEquals(listOf("b", "d", "f"), question.correctOptionIds)
        assertEquals(6, question.options.size)
    }
}