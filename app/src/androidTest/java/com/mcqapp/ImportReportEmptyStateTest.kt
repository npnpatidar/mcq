package com.mcqapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.data.io.ImportReport
import com.mcqapp.ui.importscreen.IMPORT_REPORT_EMPTY_TAG
import com.mcqapp.ui.importscreen.ImportReportDetails
import com.mcqapp.ui.importscreen.ImportReportLineKind
import com.mcqapp.ui.importscreen.importReportLineTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An Anki import that finds no question used to open the "Imported" dialog with
 * a completely blank body — every count zero, every line hidden. The reader now
 * reads the real collection instead of the compatibility stub, and as defence
 * in depth the dialog names the empty outcome rather than staying silent.
 */
@RunWith(AndroidJUnit4::class)
class ImportReportEmptyStateTest {

    @get:Rule
    val compose = createComposeRule()

    private val emptyReport = ImportReport(
        newPapers = 0,
        updatedPapers = 0,
        newQuestions = 0,
        updatedQuestions = 0,
        duplicateQuestions = 0
    )

    private fun setReport(report: ImportReport) {
        compose.setContent {
            MaterialTheme {
                ImportReportDetails(report, emptyMessageRes = R.string.import_report_no_questions)
            }
        }
    }

    @Test
    fun anAllZeroReportSaysNoQuestionsWereFound() {
        setReport(emptyReport)

        compose.onNodeWithTag(IMPORT_REPORT_EMPTY_TAG)
            .assertIsDisplayed()
            .assertTextEquals("No questions were found in this deck.")
    }

    /** A report with any count keeps its counted lines and no extra sentence. */
    @Test
    fun aReportWithCountsShowsItsCountedLineAndNoEmptyMessage() {
        setReport(emptyReport.copy(newPapers = 1, newQuestions = 2))

        compose.onNodeWithTag(importReportLineTag(ImportReportLineKind.NEW_QUESTIONS))
            .assertIsDisplayed()
        compose.onNodeWithTag(IMPORT_REPORT_EMPTY_TAG).assertDoesNotExist()
    }
}
