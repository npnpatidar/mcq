package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.data.export.ExportFormat
import com.mcqapp.ui.navigation.McqNavHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The export-format picker lists every [ExportFormat] inside an AlertDialog.
 * On a phone the last entries sit below the fold, so the content column must
 * be scrollable and each row must be individually targetable. These tests open
 * the Library's paper export dialog, scroll to the FINAL entry and select it.
 */
@RunWith(AndroidJUnit4::class)
class ExportFormatDialogUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository

    // Derived from the enum so the test follows the real list if a format is
    // added or reordered: the default is the first entry, the target the last.
    private val defaultFormat = ExportFormat.JSON_INLINE
    private val lastFormat = ExportFormat.entries.last()

    private val seeded = mutableListOf<String>()

    @After
    fun cleanup() = runBlocking {
        seeded.forEach { repository.deletePaper(it) }
    }

    private fun openLibraryExportDialog() {
        val paperId = "ui-export-format-dialog"
        runBlocking {
            repository.ensurePaperAndCategory(paperId, "Export Format Paper", "$paperId-cat", "Cat")
        }
        seeded += paperId

        compose.setContent { McqNavHost(repository = repository) }

        // One seeded paper: its export icon is on screen, opening the dialog
        // for the whole paper (no category selection required).
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("paper-export-$paperId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("paper-export-$paperId").performScrollTo().performClick()

        // Wait for the format rows themselves, not just a title, so the picker
        // is fully composed before we assert on selection.
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("export-format-${defaultFormat.name}")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Positive: the final format is reachable by scrolling and becomes selected. */
    @Test
    fun scrollToLastExportFormatAndSelectIt() {
        openLibraryExportDialog()

        compose.onNodeWithTag("export-format-${defaultFormat.name}").assertIsSelected()

        compose.onNodeWithTag("export-format-${lastFormat.name}")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        compose.onNodeWithTag("export-format-${lastFormat.name}").assertIsSelected()
    }

    /**
     * Negative: tapping the final row must clear the previously selected
     * default. If the scroll landed on the wrong row (or the tap was swallowed
     * because the row was unreachable), the default would still be selected.
     */
    @Test
    fun selectingLastExportFormatDeselectsDefault() {
        openLibraryExportDialog()

        compose.onNodeWithTag("export-format-${defaultFormat.name}").assertIsSelected()

        compose.onNodeWithTag("export-format-${lastFormat.name}")
            .performScrollTo()
            .performClick()

        compose.onNodeWithTag("export-format-${defaultFormat.name}").assertIsNotSelected()
        compose.onNodeWithTag("export-format-${lastFormat.name}").assertIsSelected()
    }
}
