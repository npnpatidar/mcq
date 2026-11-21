package com.mcqapp

import com.mcqapp.data.io.ImportReport
import com.mcqapp.ui.importscreen.ImportReportLineKind
import com.mcqapp.ui.importscreen.importReportLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The completion dialog used to list only questions/bookmarks/attempts. A
 * backup restore had no line for its papers or its restored study schedules,
 * which is exactly the reassurance the restore needs.
 */
class FollowupImportCompletionTest {

    private fun report(
        newPapers: Int = 0,
        updatedPapers: Int = 0,
        newQuestions: Int = 0,
        updatedQuestions: Int = 0,
        duplicateQuestions: Int = 0,
        answersRefreshed: Int = 0,
        restoredBookmarks: Int = 0,
        restoredAttempts: Int = 0,
        restoredSchedules: Int = 0,
        droppedAnkiStems: Int = 0,
        recallConverted: Int = 0
    ) = ImportReport(
        newPapers = newPapers,
        updatedPapers = updatedPapers,
        newQuestions = newQuestions,
        updatedQuestions = updatedQuestions,
        duplicateQuestions = duplicateQuestions,
        answersRefreshed = answersRefreshed,
        restoredBookmarks = restoredBookmarks,
        restoredAttempts = restoredAttempts,
        restoredSchedules = restoredSchedules,
        droppedAnkiStems = droppedAnkiStems,
        recallConverted = recallConverted
    )

    @Test
    fun aBackupRestoreEnumeratesPapersAndRestoredSchedules() {
        val lines = importReportLines(
            report(newPapers = 2, restoredSchedules = 37, restoredAttempts = 1)
        )
        assertEquals(
            listOf(
                ImportReportLineKind.NEW_PAPERS to 2,
                ImportReportLineKind.RESTORED_SCHEDULES to 37,
                ImportReportLineKind.RESTORED_ATTEMPTS to 1
            ),
            lines.map { it.kind to it.count }
        )
    }

    @Test
    fun zeroCountsProduceNoLines() {
        assertTrue(importReportLines(report()).isEmpty())
        val lines = importReportLines(report(newQuestions = 1, restoredSchedules = 0))
        assertEquals(listOf(ImportReportLineKind.NEW_QUESTIONS), lines.map { it.kind })
        assertFalse(lines.any { it.kind == ImportReportLineKind.RESTORED_SCHEDULES })
    }

    @Test
    fun everyCountIsPresentWithItsValueInDisplayOrder() {
        val lines = importReportLines(
            report(
                newPapers = 1, updatedPapers = 2, newQuestions = 3, updatedQuestions = 4,
                duplicateQuestions = 5, answersRefreshed = 6, restoredSchedules = 7,
                restoredBookmarks = 8, restoredAttempts = 9, droppedAnkiStems = 10,
                recallConverted = 11
            )
        )
        assertEquals(
            listOf(
                ImportReportLineKind.NEW_PAPERS,
                ImportReportLineKind.UPDATED_PAPERS,
                ImportReportLineKind.NEW_QUESTIONS,
                ImportReportLineKind.UPDATED_QUESTIONS,
                ImportReportLineKind.DUPLICATE_QUESTIONS,
                ImportReportLineKind.ANSWERS_REFRESHED,
                ImportReportLineKind.RESTORED_SCHEDULES,
                ImportReportLineKind.RESTORED_BOOKMARKS,
                ImportReportLineKind.RESTORED_ATTEMPTS,
                ImportReportLineKind.DROPPED_ANKI_STEMS,
                ImportReportLineKind.RECALL_CONVERTED
            ),
            lines.map { it.kind }
        )
        assertEquals((1..11).toList(), lines.map { it.count })
    }

    /** Every counter non-zero, except the one at [omittingIndex]. */
    private fun countersAtOne(omittingIndex: Int = -1): ImportReport {
        fun value(index: Int) = if (index == omittingIndex) 0 else 1
        return report(
            newPapers = value(0),
            updatedPapers = value(1),
            newQuestions = value(2),
            updatedQuestions = value(3),
            duplicateQuestions = value(4),
            answersRefreshed = value(5),
            restoredSchedules = value(6),
            restoredBookmarks = value(7),
            restoredAttempts = value(8),
            droppedAnkiStems = value(9),
            recallConverted = value(10)
        )
    }

    /**
     * The longest report (a restore, every counter non-zero) lists all eleven
     * kinds once, in the enum's display order. This is the data the completion
     * dialog sizes itself against, so a missing or reordered kind is a visible
     * bug before it is a layout bug.
     */
    @Test
    fun allElevenKindsAreListedOnceInDisplayOrder() {
        assertEquals(
            ImportReportLineKind.entries,
            importReportLines(countersAtOne()).map { it.kind }
        )
    }

    /** A zero counter is omitted from every position, not just the tail. */
    @Test
    fun aZeroCounterIsOmittedAtEveryPosition() {
        ImportReportLineKind.entries.forEachIndexed { index, omitted ->
            val lines = importReportLines(countersAtOne(omittingIndex = index))
            assertEquals("omitting $omitted must leave ten lines", 10, lines.size)
            assertFalse("$omitted must not be listed", lines.any { it.kind == omitted })
        }
    }
}
