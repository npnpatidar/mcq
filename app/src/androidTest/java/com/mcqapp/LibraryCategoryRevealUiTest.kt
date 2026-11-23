package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.ui.library.PaperCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library card's paged category tree (CI/emulator only).
 *
 * "Show less" has to survive the reveal: once the level is expanded the pager
 * reports `hiddenAfter == 0`, and a condition of `hiddenAfter > 0` alone draws
 * the toggle away, stranding the learner with a permanently expanded tree and
 * no way to fold it back. The card therefore also keeps the toggle while its
 * reveal key is set. This test drives the real card, so it fails on the old
 * condition: after the reveal there is no "Show less" node to click.
 */
@RunWith(AndroidJUnit4::class)
class LibraryCategoryRevealUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication

    private companion object {
        const val PAPER = "ui-reveal-paper"
        const val SIBLINGS = 7
    }

    private fun paper(): Paper = Paper(
        id = PAPER,
        title = "UIReveal Paper",
        categories = (1..SIBLINGS).map { index ->
            CategoryNode(
                id = "$PAPER-c$index",
                paperId = PAPER,
                title = "Sibling $index",
                parentId = null,
                questionCount = 1
            )
        }
    )

    /** The paged page is six rows, then "Show 1 more" for the seventh. */
    private val revealKey: String get() = "$PAPER-c6"

    @Test
    fun showLessFoldsARevealedLevelBack() {
        val card = paper()
        compose.setContent {
            MaterialTheme {
                PaperCard(
                    paper = card,
                    onStart = {},
                    onBrowse = {},
                    onExport = {},
                    onDelete = {},
                    onAddCategory = {},
                    onEditQuestion = { _, _ -> },
                    onAddQuestion = {}
                )
            }
        }

        // Open the read-only category picker.
        compose.onNodeWithTag("paper-show-$PAPER").performClick()

        // The pager keeps six siblings and hides the seventh behind the toggle.
        assertEquals(
            "the pager should hold six rows before the reveal",
            6,
            compose.onAllNodesWithText("Sibling ", substring = true).fetchSemanticsNodes().size
        )
        compose.onNodeWithTag("card-more-$revealKey").assertIsDisplayed()
        compose.onNodeWithText("Sibling 7", substring = true).assertDoesNotExist()

        // Expand: all seven rows, and "Show less" must stay tappable.
        compose.onNodeWithTag("card-more-$revealKey").performClick()
        compose.onNodeWithText("Sibling 7", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("card-more-$revealKey")
            .assertIsDisplayed()
            .assertTextEquals(app.getString(R.string.of_show_less))

        // Fold back: six rows again, seventh gone, toggle restored to "more".
        compose.onNodeWithTag("card-more-$revealKey").performClick()
        compose.onNodeWithText("Sibling 7", substring = true).assertDoesNotExist()
        assertEquals(
            "folding the level back should leave six rows",
            6,
            compose.onAllNodesWithText("Sibling ", substring = true).fetchSemanticsNodes().size
        )
        compose.onNodeWithTag("card-more-$revealKey").assertIsDisplayed()
    }
}
