package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards for fixes that a local compile cannot prove: lint suppressions, the
 * opt-in stop write, the cold-start goal read and the emulator's drawer scroll.
 * All of these are source-level; the behaviour they describe is also covered by
 * the Robolectric/Compose tests that CI runs on x86_64.
 */
class CiAndRound2FixGuardTest {

    private fun source(path: String): String = File("src/main/$path").readText()

    @Test
    fun pluralsCandidatesAreSuppressedInBothLocales() {
        listOf(
            "res/values/strings_ai_schema.xml" to "ai_schema_size",
            "res/values/strings_daily_goal.xml" to "daily_goal_progress",
            "res/values/strings_suspend.xml" to "suspend_indicator_count",
            "res/values-hi/strings_hi_ai_schema.xml" to "ai_schema_size",
            "res/values-hi/strings_hi_daily_goal.xml" to "daily_goal_progress",
            "res/values-hi/strings_hi_suspend.xml" to "suspend_indicator_count"
        ).forEach { (path, name) ->
            val xml = source(path)
            assertTrue("$path must declare the tools namespace", "<resources xmlns:tools=" in xml)
            assertTrue(
                "$path:$name must suppress PluralsCandidate",
                Regex("""<string name="$name" tools:ignore="PluralsCandidate">""").containsMatchIn(xml)
            )
        }
    }

    @Test
    fun optInStopWritesTheGradeThenClosesWithoutServingACard() {
        val vm = source("java/com/mcqapp/ui/study/StudyViewModel.kt")
        val stop = vm.substringAfter("    fun stop() {").substringBefore("\n    }")
        assertTrue("one stop per sitting", "stopped.compareAndSet(false, true)" in stop)
        assertTrue("the staged grade goes through the one-tap write path", "grade(proposed)" in stop)
        assertTrue("the write is awaited before the summary", "gradeWriteJob?.join()" in stop)
        assertTrue("the card is cleared for the summary", "finishSession(clearCard = true)" in stop)
        assertFalse("a stop never pulls another card", "pullCard()" in stop)
        assertTrue(
            "join must come before the summary",
            stop.indexOf("gradeWriteJob?.join()") < stop.indexOf("finishSession(clearCard = true)")
        )
        val finish = vm.substringAfter("private fun finishSession(").substringBefore("\n    }")
        assertTrue("the clear must actually drop the current card", "current = null" in finish)
    }

    @Test
    fun libraryReadsTheGoalPreferenceDirectlyLikeStatistics() {
        val library = source("java/com/mcqapp/ui/library/LibraryViewModel.kt")
        val refresh = library.substringAfter("fun refreshDailyGoal()").substringBefore("\n    }")
        assertTrue(
            "cold start must read the preference, not a stopped state mirror",
            "repository.dailyGoalEnabled().first()" in refresh
        )
        assertFalse("the WhileSubscribed mirror is not a source of truth here", "dailyGoalEnabled.value" in refresh)
        val stats = source("java/com/mcqapp/ui/stats/StatsViewModel.kt")
        assertTrue("both screens must agree", "repository.dailyGoalEnabled().first()" in stats)
    }

    @Test
    fun drawerScrollHelperIsBoundedAndKeepsTheSelectionAssertion() {
        val drawer = source("java/com/mcqapp/ui/library/LibraryDrawer.kt")
        assertTrue("the drawer list carries the helper's tag", "testTag(\"drawer-list\")" in drawer)
        val test = File("src/androidTest/java/com/mcqapp/StudyAndSelectionUiTest.kt").readText()
        assertTrue("the helper scrolls the tagged list", "drawer-list" in test)
        assertTrue("the scroll is bounded", "repeat(16)" in test && "swipeUp()" in test)
        val selectionTest = test.substringAfter("fun tickingInTwoPapersKeepsTheirSelectionsApart()")
        assertTrue(
            "the selection assertion must not be weakened",
            "paper A's question leaked into paper B's test" in selectionTest
        )
    }
}
