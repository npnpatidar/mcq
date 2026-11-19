package com.mcqapp

import com.mcqapp.data.io.DEFAULT_MAX_SCHEDULE_PAGES
import com.mcqapp.data.io.DEFAULT_MAX_SCHEDULE_TARGETS
import com.mcqapp.data.io.ScheduleRestoreBudget
import com.mcqapp.data.io.allDuplicatesPaperSkip
import com.mcqapp.data.io.countPaper
import com.mcqapp.data.io.schedulePageWithinCap
import com.mcqapp.data.io.scheduleTargetWithinCap
import com.mcqapp.data.io.ImportReport
import com.mcqapp.ui.importscreen.importReportNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** I3 and I4 pure seams (no Android/Room). */
class Round2ImportBudgetTest {

    // ---- I3: explicit schedule budget, tested at its boundaries ------------

    @Test
    fun pageBudgetAcceptsUpToTheCapAndExhaustsOnTheNext() {
        val budget = ScheduleRestoreBudget(maxPages = 2, maxTargets = 100)
        assertTrue(budget.tryAddPage(1))
        assertTrue(budget.tryAddPage(1))
        assertFalse("the third page crosses the cap", budget.tryAddPage(1))
        assertTrue(budget.exhausted)
        // Once exhausted nothing else is accepted.
        assertFalse(budget.tryAddPage(1))
        assertFalse(budget.tryAddTarget())
    }

    @Test
    fun targetBudgetAcceptsExactlyMaxTargetsThenExhausts() {
        val budget = ScheduleRestoreBudget(maxPages = 100, maxTargets = 3)
        assertTrue(budget.tryAddTarget())
        assertTrue(budget.tryAddTarget())
        assertTrue(budget.tryAddTarget())
        assertFalse("the fourth target crosses the cap", budget.tryAddTarget())
        assertTrue(budget.exhausted)
    }

    @Test
    fun aLargeSinglePageCrossingTheCapIsRejectedWhole() {
        val budget = ScheduleRestoreBudget(maxPages = 2, maxTargets = 100)
        assertFalse(budget.tryAddPage(3))
        assertTrue(budget.exhausted)
    }

    @Test
    fun defaultBudgetIsGenerous() {
        assertTrue(DEFAULT_MAX_SCHEDULE_PAGES >= 128)
        assertTrue(DEFAULT_MAX_SCHEDULE_TARGETS >= 100_000)
        assertTrue(schedulePageWithinCap(DEFAULT_MAX_SCHEDULE_PAGES - 1, 1))
        assertFalse(schedulePageWithinCap(DEFAULT_MAX_SCHEDULE_PAGES, 1))
        assertTrue(scheduleTargetWithinCap(DEFAULT_MAX_SCHEDULE_TARGETS - 1))
        assertFalse(scheduleTargetWithinCap(DEFAULT_MAX_SCHEDULE_TARGETS))
    }

    @Test
    fun freshBudgetIsNotExhausted() {
        assertFalse(ScheduleRestoreBudget().exhausted)
    }

    private fun report(skipped: Boolean) = ImportReport(
        newPapers = 1, updatedPapers = 0, newQuestions = 10, updatedQuestions = 0,
        duplicateQuestions = 0, progressRestoreSkipped = skipped
    )

    @Test
    fun reportNoticeShownOnlyWhenProgressWasSkipped() {
        // Positive: the report carries a friendly notice resource.
        assertEquals(
            com.mcqapp.R.string.r2_import_progress_skipped_notice,
            importReportNotice(report(skipped = true))
        )
        // Negative: a complete restore shows no misleading notice.
        assertEquals(null, importReportNotice(report(skipped = false)))
    }

    // ---- I4: all-duplicates skip drops the shell and counts no paper --------

    @Test
    fun allDuplicateShellIsSkippedAndCountedAsNothing() {
        assertTrue(allDuplicatesPaperSkip(isNewPaper = true, needsWrite = false, sawAnyQuestion = true))
        val (newPapers, updatedPapers) = countPaper(isNewPaper = true, skipped = true)
        assertEquals(0, newPapers)
        assertEquals(0, updatedPapers)
    }

    @Test
    fun aRealNewPaperAndAReimportAreStillCounted() {
        // Positive: a genuinely new paper and a matched (updated) paper count.
        assertFalse(allDuplicatesPaperSkip(isNewPaper = true, needsWrite = true, sawAnyQuestion = true))
        assertEquals(1 to 0, countPaper(isNewPaper = true, skipped = false))
        assertEquals(0 to 1, countPaper(isNewPaper = false, skipped = false))
    }

    @Test
    fun anEmptyPaperWithNoQuestionsIsNotTheSkipCase() {
        assertFalse(allDuplicatesPaperSkip(isNewPaper = true, needsWrite = false, sawAnyQuestion = false))
    }
}
