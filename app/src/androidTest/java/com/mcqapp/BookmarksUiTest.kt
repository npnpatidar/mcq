package com.mcqapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.common.PagingErrorRow
import com.mcqapp.ui.history.BookmarksScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bookmarks has no emulator tests. These pin the share dialog, the delete-last
 * empty state, the Browse focus deep link and the shared paging refresh-error
 * retry UI. CI-only: the real repository opens SQLite (x86_64 emulator).
 */
@RunWith(AndroidJUnit4::class)
class BookmarksUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository

    private fun question(id: String, text: String) = Question(
        id = id,
        categoryId = CAT,
        text = text,
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = setOf("$id-a")
    )

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(PAPER, TITLE, CAT, "UITest Bookmark Cat")
        repository.saveQuestion(question("uibook-q1", "UIBOOK focused question?"))
        repository.toggleBookmark("uibook-q1")
    }

    @After
    fun cleanup() = runBlocking { repository.deletePaper(PAPER) }

    private fun waitFor(text: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForAction(label: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openScreen() {
        compose.setContent {
            BookmarksScreen(repository = repository, navController = rememberNavController())
        }
    }

    /** The share button and the format dialog are the entry to save / share. */
    @Test
    fun exportButtonOpensTheFormatDialogWithSaveAndShare() {
        openScreen()
        waitFor("UIBOOK focused question?")
        waitForAction(app.getString(R.string.export_bookmarks))

        compose.onNodeWithContentDescription(app.getString(R.string.export_bookmarks)).performClick()

        waitFor("Export 1 bookmarked question")
        compose.onNodeWithText("Export 1 bookmarked question").assertIsDisplayed()
        // A format row and both commit actions are wired into the dialog.
        compose.onNodeWithText("JSON (images inline)").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.share_save)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.share_share)).assertIsDisplayed()
    }

    /** Idempotent delete: removing the last row must reveal the empty state. */
    @Test
    fun deletingTheLastBookmarkShowsTheEmptyState() {
        openScreen()
        waitFor("UIBOOK focused question?")
        waitForAction(app.getString(R.string.remove_bookmark))

        compose.onNodeWithContentDescription(app.getString(R.string.remove_bookmark)).performClick()

        waitFor(app.getString(R.string.no_bookmarked_questions))
        compose.onNodeWithText(app.getString(R.string.no_bookmarked_questions)).assertIsDisplayed()
        assertTrue(
            "the removed row must be gone",
            compose.onAllNodesWithText("UIBOOK focused question?", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    /**
     * A bookmark opens Browse focused on that question, not the editor. A stub
     * destination reads the route arguments so the test fails if the paper or
     * focus id is dropped.
     */
    @Test
    fun tappingABookmarkNavigatesToBrowseFocusedOnThatQuestion() {
        compose.setContent {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "bookmarks") {
                composable("bookmarks") {
                    BookmarksScreen(repository = repository, navController = nav)
                }
                composable(
                    route = "browse/{paperId}?focus={focus}&categories={categories}",
                    arguments = listOf(
                        androidx.navigation.navArgument("paperId") {
                            type = androidx.navigation.NavType.StringType
                        },
                        androidx.navigation.navArgument("focus") {
                            type = androidx.navigation.NavType.StringType
                            defaultValue = ""
                        },
                        androidx.navigation.navArgument("categories") {
                            type = androidx.navigation.NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) { entry ->
                    val paperId = entry.arguments?.getString("paperId")
                    val focus = entry.arguments?.getString("focus")
                    Text("BROWSE_DEST $paperId focus=$focus")
                }
            }
        }
        waitFor("UIBOOK focused question?")
        compose.onNodeWithText("UIBOOK focused question?").performClick()

        waitFor("BROWSE_DEST")
        compose.onNodeWithText("BROWSE_DEST $PAPER focus=uibook-q1").assertIsDisplayed()
    }

    /**
     * Both paged screens share this error row, which carries the retry action.
     * A screen-level forced DB failure needs a repository seam the app does not
     * expose; this pins the retry wiring the Bookmarks refresh path relies on.
     */
    @Test
    fun refreshErrorRowOffersAWorkingRetry() {
        var retried = false
        compose.setContent {
            MaterialTheme {
                PagingErrorRow(
                    messageRes = R.string.paging_refresh_failed,
                    onRetry = { retried = true }
                )
            }
        }
        compose.onNodeWithText(app.getString(R.string.paging_refresh_failed)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.retry)).performClick()
        assertTrue("Retry must invoke the refresh retry action", retried)
    }

    private companion object {
        const val PAPER = "uibook-paper"
        const val CAT = "uibook-cat"
        const val TITLE = "UITest Bookmark Paper"
    }
}
