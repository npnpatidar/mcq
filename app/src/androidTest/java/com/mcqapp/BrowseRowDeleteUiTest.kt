package com.mcqapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.browse.BrowseScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The per-question action on a Browse row (runs on the CI emulator).
 *
 * The copy icon used to be the prominent action and Delete sat beside it. The
 * row now offers Delete alone, but it must still ask before removing anything.
 */
@RunWith(AndroidJUnit4::class)
class BrowseRowDeleteUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(PAPER, "UITest Row Delete", CATEGORY, "Only Cat")
        repository.saveQuestion(
            Question(
                id = QUESTION,
                categoryId = CATEGORY,
                elements = listOf(ContentElement.TextElement("A question to delete")),
                options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
                correctOptionIds = setOf("a")
            )
        )
    }

    @After
    fun cleanup() = runBlocking { repository.deletePaper(PAPER) }

    @Test
    fun rowOffersDeleteOnlyAndItOpensTheConfirmation() {
        compose.setContent {
            BrowseScreen(
                repository = repository,
                paperId = PAPER,
                focusQuestionId = QUESTION,
                navController = rememberNavController()
            )
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("browse-delete-$QUESTION")
                .fetchSemanticsNodes().isNotEmpty()
        }
        val delete = compose.onNodeWithTag("browse-delete-$QUESTION")
            .performScrollTo().assertIsDisplayed()
        delete.assertIsDisplayed()
        assertTrue(
            "the row's Delete action is not announced",
            compose.onAllNodesWithContentDescription(
                app.getString(R.string.delete_question)
            ).fetchSemanticsNodes().isNotEmpty()
        )
        // The per-question copy action is gone; bulk duplicate remains in the
        // selection overflow and is a different (top-bar) control.
        assertTrue(
            "the per-question copy action is still on the row",
            compose.onAllNodesWithContentDescription(
                app.getString(R.string.duplicate_question)
            ).fetchSemanticsNodes().isEmpty()
        )

        delete.performClick()
        // Context.getString cannot resolve a <plurals> resource; use the
        // quantity helper, as the screen itself does.
        val title = app.resources.getQuantityString(R.plurals.delete_n_questions, 1, 1)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(title, substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(
            app.getString(R.string.browse_delete_with_undo, 1), substring = false
        ).assertIsDisplayed()
        // Cancelling leaves the question in the paper: the row tap only asks.
        compose.onNodeWithText(app.getString(R.string.cancel)).performClick()
        assertTrue(runBlocking { repository.getQuestion(QUESTION) != null })
        // The old row action also toasted "Question duplicated"; it must not
        // surface from this row any more.
        assertTrue(
            "the row's old duplicate toast appeared",
            compose.onAllNodesWithText(
                app.getString(R.string.question_duplicated), substring = false
            ).fetchSemanticsNodes().isEmpty()
        )
    }

    private companion object {
        const val PAPER = "uitest-row-delete-paper"
        const val CATEGORY = "uitest-row-delete-cat"
        const val QUESTION = "uitest-row-delete-q"
    }
}
