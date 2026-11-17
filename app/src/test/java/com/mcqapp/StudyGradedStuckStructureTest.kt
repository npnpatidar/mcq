package com.mcqapp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-shape guards for the "graded but stuck" fixes. These run as pure JVM
 * tests (no Robolectric/SQLite), so they catch a regression on the host while
 * the behavioural tests are authoritative on CI's x86_64 runners.
 */
class StudyGradedStuckStructureTest {

    private fun source(name: String): String =
        File("src/main/java/com/mcqapp/ui/study/$name").readText()

    @Test
    fun stopDoesNotRePersistAnAlreadyStuckGrade() {
        val vm = source("StudyViewModel.kt")
        val stop = vm.substringAfter("fun stop() {").substringBefore("fun finishSession()")
        assertTrue(
            "stop's early-finish guard must include gradedAndStuck, or Back writes " +
                "an already-persisted grade a second time",
            stop.contains("current.gradedAndStuck")
        )
    }

    @Test
    fun simplifiedFooterOffersRetryWhenTheGradeIsStuck() {
        val screen = source("StudyScreen.kt")
        val start = screen.indexOf("private fun SimplifiedFooter(")
        assertTrue("SimplifiedFooter is missing", start >= 0)
        val end = screen.indexOf("\n}\n", start)
        assertTrue("SimplifiedFooter has no closing brace", end > start)
        val footer = screen.substring(start, end)
        assertTrue(
            "the simplified footer must offer Retry (viewModel.loadNext()), " +
                "not just a disabled Next with no way out",
            footer.contains("viewModel.loadNext()")
        )
        assertTrue("the Retry must carry a label", footer.contains("R.string.retry"))
    }
}
