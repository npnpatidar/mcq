package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A malformed file can put a hundred thousand separators in one spot.
 * `JsonPull.scan()` recursed once per separator, so the parse died with a
 * StackOverflowError — an Error, which the importers' `catch (Exception)`
 * never caught, so the whole app crashed instead of reporting a bad file.
 */
class JsonPullSeparatorRecursionTest {

    @Test
    fun aRunOfSeparatorsDoesNotOverflowTheStack() {
        val commas = ",".repeat(100_000)
        val json = "[$commas]"
        // The parse must simply survive. A separator-only bare array carries
        // no questions, so like `[]` it degenerates to a paper with none —
        // the pinned contract is "no crash", not a warning.
        val file = try {
            LegacyParser.parse(json.byteInputStream())
        } catch (e: StackOverflowError) {
            throw AssertionError("scan() recursed once per separator and overflowed the stack", e)
        }
        assertTrue(file.papers.all { it.questions.isEmpty() })
    }

    @Test
    fun aRunOfSeparatorsBetweenMembersDoesNotOverflowTheStack() {
        val commas = ",".repeat(100_000)
        val json = "{\"papers\":[{\"id\":\"p1\"}$commas]}"
        val file = try {
            LegacyParser.parse(json.byteInputStream())
        } catch (e: StackOverflowError) {
            throw AssertionError("scan() recursed once per separator and overflowed the stack", e)
        }
        assertTrue(file.papers.isNotEmpty() || file.warnings.isNotEmpty())
    }
}
