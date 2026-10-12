package com.mcqapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
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
        repository.ensurePaperAndCategory("p1", "UITest Paper", "c1", "First Cat")
        val c2 = repository.addCategory("p1", "Second Cat", parentId = null)
        repository.saveQuestion(question("q1", "c1", "First question?"))
        repository.saveQuestion(question("q2", "c2", "Second question?"))
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("p1")
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
                paperId = "p1",
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
        // the up move exists twice — disabled on the first section, enabled
        // on the second — so address the enabled one by position.
        val orderBefore = runBlocking { repository.getQuestionsForPaper("p1").map { it.id } }
        assertEquals(listOf("q1", "q2"), orderBefore)
        val categoryUp = compose.onAllNodesWithContentDescription("Move the whole category up")
        categoryUp[0].assertIsNotEnabled()
        categoryUp[1].performClick()
        compose.waitUntil(15_000) {
            runBlocking { repository.getQuestionsForPaper("p1").map { it.id } } ==
                listOf("q2", "q1")
        }
        // And back down for symmetry: after the swap the second category is
        // the first section, so its (enabled) down move sits at index 0.
        val categoryDown = compose.onAllNodesWithContentDescription("Move the whole category down")
        categoryDown[1].assertIsNotEnabled()
        categoryDown[0].performClick()
        compose.waitUntil(15_000) {
            runBlocking { repository.getQuestionsForPaper("p1").map { it.id } } ==
                listOf("q1", "q2")
        }
        assertTrue(true)
    }
}
