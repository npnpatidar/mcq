package com.mcqapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Browse inline section moves (runs on the CI emulator): a category's
 * first row carries the whole-category up move as a double-arrow button —
 * the two positions that could never move their own question — and every
 * row labels its category so a section's bounds explain themselves.
 */
@RunWith(AndroidJUnit4::class)
class BrowseCategoryMoveUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    @Before
    fun seed() = runBlocking {
        // Ids unique to this class: instrumented classes share the app database,
        // so a leak from another class must never be able to collide.
        repository.ensurePaperAndCategory(
            "uitest-browse-paper", "UITest Browse Paper", "uitest-browse-c1", "First Cat"
        )
        val c2 = repository.addCategory("uitest-browse-paper", "Second Cat", parentId = null)
        repository.saveQuestion(question("ub-q1", "uitest-browse-c1", "First question?"))
        repository.saveQuestion(question("ub-q2", c2, "Second question?"))
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("uitest-browse-paper")
    }

    private fun question(id: String, categoryId: String, text: String) = Question(
        id = id,
        categoryId = categoryId,
        elements = listOf(ContentElement.TextElement(text)),
        options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
        correctOptionIds = setOf("a")
    )

    @Test
    fun rowsShowTheirCategoryAndTheSectionEdgeMovesTheWholeCategory() {
        compose.setContent {
            BrowseScreen(
                repository = repository,
                paperId = "uitest-browse-paper",
                navController = rememberNavController()
            )
        }
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Second Cat", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Both rows label their category.
        compose.onAllNodesWithText("First Cat", substring = true)[0].assertIsDisplayed()
        compose.onAllNodesWithText("Second Cat", substring = true)[0].assertIsDisplayed()

        // Both rows carry the section edges (each category here has exactly
        // one question, so each card is both first and last of its section):
        // each category move exists twice, exactly one of them enabled. The
        // matcher selects that one and fails loudly if the enablement is
        // wrong — a positional index would click a disabled button silently.
        val orderBefore = runBlocking { repository.getQuestionsForPaper("uitest-browse-paper").map { it.id } }
        assertEquals(listOf("ub-q1", "ub-q2"), orderBefore)
        // The CI emulator screen is short: with the search, chips and
        // category filter stacked above the list, the second card's button
        // row can sit at (or past) the window edge. assertIsDisplayed on
        // the row text above it still passes, but a tap at an off-window
        // coordinate is dropped without error — scroll the card into view
        // first, like the delete-paper test.
        compose.onNode(hasContentDescription("Move the whole category up") and isEnabled())
            .performScrollTo()
            .performClick()
        compose.waitUntil(15_000) {
            runBlocking { repository.getQuestionsForPaper("uitest-browse-paper").map { it.id } } ==
                listOf("ub-q2", "ub-q1")
        }
        // And back down for symmetry: after the swap the enabled down move
        // belongs to the section that is now first.
        compose.onNode(hasContentDescription("Move the whole category down") and isEnabled())
            .performScrollTo()
            .performClick()
        compose.waitUntil(15_000) {
            runBlocking { repository.getQuestionsForPaper("uitest-browse-paper").map { it.id } } ==
                listOf("ub-q1", "ub-q2")
        }
        assertTrue(true)
    }
}
