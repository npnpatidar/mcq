package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-shape guards for the verdict batch. The screen has no Compose test
 * harness in this module (and no new test dependency is allowed), so the
 * rendering contract is pinned structurally while the behaviour lives in
 * [VerdictTest] and [ResultsCsvVerdictTest].
 */
class ResultsVerdictStructureTest {

    private fun source(path: String): String =
        File("src/main/java/com/mcqapp/$path").readText()

    private fun resultCard(): String = source("ui/results/ResultsScreen.kt")
        .substringAfter("private fun ResultCard(")

    @Test
    fun resultCardDerivesContainerTagAndGlyphFromTheSharedVerdict() {
        val card = resultCard()
        assertTrue("ResultCard must use Verdict.of", "Verdict.of(" in card)
        assertTrue(
            "container colour must come from the verdict",
            "verdict.containerColor(" in card
        )
        assertTrue(
            "tag text must use the matching on*Container role",
            "verdict.onContainerColor(" in card
        )
        assertTrue("the verdict glyph must be rendered", "verdict.glyph" in card)
        assertFalse(
            "the old ungraded predicate must be gone",
            "ungraded = result.correctOptionIds.isEmpty()" in card
        )
        assertFalse(
            "skipped must no longer be coloured as wrong",
            "skipped -> MaterialTheme.colorScheme.outline" in card
        )
    }

    @Test
    fun resultCardMergesSpokenVerdictSemantics() {
        val card = resultCard()
        assertTrue(
            "the verdict tag must replace its children's semantics",
            "clearAndSetSemantics" in card
        )
        assertTrue(
            "TalkBack must get the word + glyph, not a bare mark",
            "R.string.verdict_spoken" in card
        )
    }

    @Test
    fun csvExportUsesTheSameVerdictPredicate() {
        val rows = source("ui/results/ResultsViewModel.kt")
            .substringAfter("internal fun buildCsvRows(")
        assertTrue("CSV must classify through Verdict.of", "Verdict.of(" in rows)
        assertFalse(
            "the old string-only classification must be gone",
            "entity.correctOptionIds.isBlank()" in rows
        )
    }
}
