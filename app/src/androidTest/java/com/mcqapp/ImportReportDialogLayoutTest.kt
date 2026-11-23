package com.mcqapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.data.io.ImportReport
import com.mcqapp.ui.importscreen.IMPORT_REPORT_NOTICE_TAG
import com.mcqapp.ui.importscreen.ImportReportDetails
import com.mcqapp.ui.importscreen.ImportReportLineKind
import com.mcqapp.ui.importscreen.importReportLineTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The completion dialog's report, at the worst report length: every counter is
 * non-zero (a backup restore) plus the progress-skipped notice, inside a
 * container as tight as AlertDialog's text slot.
 *
 * On the old layout the lines were loose siblings in that slot's Box, so they
 * were drawn on top of each other and the tail was clipped under the buttons:
 * both assertions below fail there. The fix wraps them in a scrolling column
 * with spacing, so lines stay separated and the last one is reachable.
 */
@RunWith(AndroidJUnit4::class)
class ImportReportDialogLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** The longest possible report: every counted kind plus the notice. */
    private val fullReport = ImportReport(
        newPapers = 1,
        updatedPapers = 2,
        newQuestions = 3,
        updatedQuestions = 4,
        duplicateQuestions = 5,
        answersRefreshed = 6,
        restoredSchedules = 7,
        restoredBookmarks = 8,
        restoredAttempts = 9,
        droppedAnkiStems = 10,
        recallConverted = 11,
        schedulesReset = 12,
        rowsSkipped = 13,
        progressRestoreSkipped = true
    )

    private fun setReport() {
        compose.setContent {
            MaterialTheme {
                // 140dp mirrors the constrained height Material3 gives the
                // AlertDialog text slot; the report is far taller than this.
                Box(modifier = Modifier.height(140.dp).testTag(SLOT_TAG)) {
                    ImportReportDetails(fullReport)
                }
            }
        }
    }

    /**
     * Positive: the tail of the report (the final counted line and the notice)
     * is reachable by scrolling and fully inside the slot.
     */
    @Test
    fun theLastReportLineIsReachableAndInsideTheSlot() {
        setReport()
        val slot = compose.onNodeWithTag(SLOT_TAG).getUnclippedBoundsInRoot()

        val lastKind = ImportReportLineKind.entries.last()
        compose.onNodeWithTag(importReportLineTag(lastKind))
            .performScrollTo()
            .assertIsDisplayed()
        val lastLine = compose.onNodeWithTag(importReportLineTag(lastKind))
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the final counted line must sit inside the dialog slot " +
                "(top=${lastLine.top}, bottom=${lastLine.bottom}, slot=${slot})",
            lastLine.top >= slot.top && lastLine.bottom <= slot.bottom
        )

        compose.onNodeWithTag(IMPORT_REPORT_NOTICE_TAG)
            .performScrollTo()
            .assertIsDisplayed()
        val notice = compose.onNodeWithTag(IMPORT_REPORT_NOTICE_TAG).getUnclippedBoundsInRoot()
        assertTrue(
            "the notice must sit inside the dialog slot " +
                "(top=${notice.top}, bottom=${notice.bottom}, slot=${slot})",
            notice.top >= slot.top && notice.bottom <= slot.bottom
        )
    }

    /**
     * Negative/regression: consecutive lines never share vertical space, and the
     * notice follows the last line. Overlapping bounds are exactly the reported
     * bug — the old layout stacked them at the same origin.
     */
    @Test
    fun reportLinesNeverOverlapInsideTheConstrainedSlot() {
        setReport()

        val bounds = ImportReportLineKind.entries.map { kind ->
            kind to compose.onNodeWithTag(importReportLineTag(kind)).getUnclippedBoundsInRoot()
        }
        bounds.zipWithNext().forEach { (prev, next) ->
            assertTrue(
                "${prev.first} overlaps ${next.first} " +
                    "(bottom=${prev.second.bottom} > top=${next.second.top})",
                prev.second.bottom <= next.second.top
            )
        }

        val (lastKind, lastBounds) = bounds.last()
        val notice = compose.onNodeWithTag(IMPORT_REPORT_NOTICE_TAG).getUnclippedBoundsInRoot()
        assertTrue(
            "the notice must sit below $lastKind " +
                "(bottom=${lastBounds.bottom} > top=${notice.top})",
            lastBounds.bottom <= notice.top
        )
    }

    private companion object {
        const val SLOT_TAG = "import-report-slot"
    }
}
