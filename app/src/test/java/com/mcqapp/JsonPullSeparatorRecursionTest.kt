package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * A malformed file can put a hundred thousand separators in one spot.
 * `JsonPull.scan()` recursed once per separator, so the parse died with a
 * StackOverflowError — an Error, which the importers' `catch (Exception)`
 * never caught, so the whole app crashed instead of reporting a bad file.
 */
class JsonPullSeparatorRecursionTest {

    @Test
    @Ignore("documents consolidated_bugs.md A1; enable when fixed")
    fun aRunOfSeparatorsDoesNotOverflowTheStack() {
        val commas = ",".repeat(100_000)
        val json = "[$commas]"
        try {
            val file = LegacyParser.parse(json.byteInputStream())
            assertTrue(file.warnings.isNotEmpty() || file.papers.isEmpty())
        } catch (e: StackOverflowError) {
            throw AssertionError("scan() recursed once per separator and overflowed the stack", e)
        }
    }

    @Test
    @Ignore("documents consolidated_bugs.md A1; enable when fixed")
    fun aRunOfSeparatorsBetweenMembersDoesNotOverflowTheStack() {
        val commas = ",".repeat(100_000)
        val json = "{\"papers\":[{\"id\":\"p1\"}$commas]}"
        try {
            val file = LegacyParser.parse(json.byteInputStream())
            assertTrue(file.papers.isNotEmpty() || file.warnings.isNotEmpty())
        } catch (e: StackOverflowError) {
            throw AssertionError("scan() recursed once per separator and overflowed the stack", e)
        }
    }
}
