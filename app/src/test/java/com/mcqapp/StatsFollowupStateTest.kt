package com.mcqapp

import com.mcqapp.domain.stats.ReviewHistoryEntry
import com.mcqapp.ui.stats.StatsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM coverage for the two Stats follow-ups that otherwise need the
 * emulator. The sheet's failure state is a pure data decision and is exercised
 * directly; the "All time" label and the Retry wiring are asserted structurally
 * because no JVM test composes StatsScreen.
 */
class StatsFollowupStateTest {

    private fun source(path: String): String = File(path).readText()

    @Test
    fun aFailedCardLoadKeepsTheSheetMountedWithItsIdentityAndAnError() {
        val detail = StatsViewModel.CardDetail.failedLoad("p1", "q1", "boom")
        assertEquals("p1", detail.paperId)
        assertEquals("q1", detail.questionId)
        assertEquals("boom", detail.error)
        assertEquals(emptyList<ReviewHistoryEntry>(), detail.history)
        assertNull(detail.state)
        assertNull(detail.text)
    }

    @Test
    fun theTestsTabShowsAnAllTimeLabelWhereTheRangeChipsAreHidden() {
        val screen = source("src/main/java/com/mcqapp/ui/stats/StatsScreen.kt")
        assertTrue(
            "TESTS must label its lifetime-only range where chips are hidden",
            screen.contains("R.string.stats_tests_all_time")
        )
        // It must be in the else of the TESTS guard, not a global label.
        val guard = screen.indexOf("state.tab != StatsViewModel.Tab.TESTS")
        assertTrue("the TESTS range guard is missing", guard >= 0)
        val elseStart = screen.indexOf("} else {", guard)
        assertTrue("the TESTS guard must have an else branch", elseStart >= 0)
        val elseWindow = screen.substring(elseStart, (elseStart + 400).coerceAtMost(screen.length))
        assertTrue(
            "the All time label must sit in the TESTS else branch",
            elseWindow.contains("stats_tests_all_time")
        )
    }

    @Test
    fun theSheetRendersAnInlineErrorWithAWorkingRetryAction() {
        val viewModel = source("src/main/java/com/mcqapp/ui/stats/StatsViewModel.kt")
        val sheet = source("src/main/java/com/mcqapp/ui/stats/CardInfoSheet.kt")
        val screen = source("src/main/java/com/mcqapp/ui/stats/StatsScreen.kt")

        assertTrue(
            "the failure path must keep the sheet mounted with an error",
            viewModel.contains("CardDetail.failedLoad(")
        )
        assertTrue(
            "the ViewModel must expose a retry entry point",
            viewModel.contains("fun retryCard()")
        )
        assertTrue("the sheet must read the error state", sheet.contains("detail.error"))
        assertTrue(
            "the sheet must show an inline Retry button",
            sheet.contains("Button(onClick = onRetry)") && sheet.contains("R.string.retry")
        )
        assertTrue(
            "Retry must re-open the card currently on screen",
            screen.contains("onRetry = viewModel::retryCard")
        )
    }

    @Test
    fun theNewLabelsAreDeclaredInTheFollowupStringsFile() {
        val declared = source("src/main/res/values/strings_followup_stats.xml")
        listOf("stats_tests_all_time", "stats_card_detail_error", "stats_trend_scope_whole", "stats_trend_scope_topics")
            .forEach { name ->
                assertTrue("missing string $name", declared.contains("name=\"$name\""))
            }
    }
}
