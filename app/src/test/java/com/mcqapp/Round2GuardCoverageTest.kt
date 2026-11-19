package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the source-scanning rule that backs the Round-2 localization
 * guard (finding A7). The rule lives on [StringResourceUsageTest] so the guard
 * can apply it to real holders; here we feed it synthetic holders to prove the
 * rule actually fails on the blind spot it is meant to close, and passes on a
 * correctly paired one. No Android resources or new dependencies are needed.
 */
class Round2GuardCoverageTest {

    private val rule = StringResourceUsageTest()

    /** Negative: a holder holding user-facing label Strings with no resource pair. */
    @Test
    fun aLabelFieldWithoutAPairedStringResourceIsReported() {
        val src = """
            enum class FakeHolder(
                val title: String,
                val description: String
            )
        """.trimIndent()
        val violations = rule.enumLabelPairingViolations(src, "FakeHolder")
        assertTrue("both label fields must be flagged: $violations", violations.size == 2)
        assertTrue("title must be flagged: $violations", violations.any { ".title:" in it })
        assertTrue("description must be flagged: $violations", violations.any { ".description:" in it })
    }

    /** Positive: each label field has a paired @StringRes field. */
    @Test
    fun aFullyPairedHolderPassesTheRule() {
        val src = """
            enum class FakeHolder(
                val label: String,
                @StringRes val labelRes: Int,
                val title: String,
                @StringRes val titleRes: Int
            )
        """.trimIndent()
        assertTrue(
            "a correctly paired holder must pass: ${rule.enumLabelPairingViolations(src, "FakeHolder")}",
            rule.enumLabelPairingViolations(src, "FakeHolder").isEmpty()
        )
    }

    /** Positive: fully-qualified annotation is recognised too. */
    @Test
    fun aFullyQualifiedStringResAnnotationCountsAsAPair() {
        val src = """
            enum class FakeHolder(
                val title: String,
                @androidx.annotation.StringRes val titleRes: Int
            )
        """.trimIndent()
        assertTrue(rule.enumLabelPairingViolations(src, "FakeHolder").isEmpty())
    }

    /** Positive: non-label metadata and curated allowlisted fields are ignored. */
    @Test
    fun nonLabelAndAllowlistedFieldsAreIgnored() {
        val src = """
            enum class FakeHolder(
                val mimeType: String,
                val extension: String,
                val title: String
            )
        """.trimIndent()
        assertTrue(
            "mimeType/extension are metadata; title is allowlisted",
            rule.enumLabelPairingViolations(src, "FakeHolder", setOf("title")).isEmpty()
        )
    }

    /** Negative: private fields are not part of the public holder contract. */
    @Test
    fun aPrivateLabelFieldIsNotTreatedAsAPublicHolderField() {
        val src = """
            enum class FakeHolder(
                private val title: String
            )
        """.trimIndent()
        assertTrue(rule.enumLabelPairingViolations(src, "FakeHolder").isEmpty())
    }

    /** Negative/positive: the parity helper really reports absent names. */
    @Test
    fun missingFromReportsOnlyAbsentNames() {
        assertEquals(
            setOf("missing_a", "missing_b"),
            rule.missingFrom(listOf("ok", "missing_a", "missing_b"), setOf("ok", "extra"))
        )
        assertTrue(rule.missingFrom(listOf("a", "b"), setOf("b", "a")).isEmpty())
    }
}
