package com.mcqapp

import com.mcqapp.data.io.ImportReport
import com.mcqapp.ui.importscreen.ImportReportLineKind
import com.mcqapp.ui.importscreen.importReportLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An Anki conversion can drop a card (no stem, no answer) or import cards. The
 * dropped count is the reader's; the card count is the shared importer's — every
 * card actually written — so it means the same thing for JSON, Anki and restore.
 * The completion dialog enumerates them rather than hiding them in a log line.
 */
class FollowupAnkiImportCountsTest {

    private fun ankiReport() = ImportReport(
        newPapers = 1,
        updatedPapers = 0,
        newQuestions = 9,
        updatedQuestions = 0,
        duplicateQuestions = 0,
        restoredSchedules = 4,
        droppedAnkiStems = 2,
        recallConverted = 3
    )

    @Test
    fun reportCarriesReaderCountsAlongsideDatabaseCounts() {
        val report = ankiReport()
        assertEquals(2, report.droppedAnkiStems)
        assertEquals(3, report.recallConverted)
        assertEquals(4, report.restoredSchedules)
    }

    @Test
    fun completionReportNamesDropsAndRecallConversions() {
        val kinds = importReportLines(ankiReport())
        assertEquals(2, kinds.first { it.kind == ImportReportLineKind.DROPPED_ANKI_STEMS }.count)
        assertEquals(3, kinds.first { it.kind == ImportReportLineKind.RECALL_CONVERTED }.count)
        assertTrue(kinds.any { it.kind == ImportReportLineKind.RESTORED_SCHEDULES })
    }

    @Test
    fun aDeckWithNoDropsOrConversionsDoesNotShowThoseLines() {
        val kinds = importReportLines(
            ImportReport(
                newPapers = 0, updatedPapers = 0, newQuestions = 1,
                updatedQuestions = 0, duplicateQuestions = 0
            )
        )
        assertTrue(kinds.none { it.kind == ImportReportLineKind.DROPPED_ANKI_STEMS })
        assertTrue(kinds.none { it.kind == ImportReportLineKind.RECALL_CONVERTED })
    }
}
