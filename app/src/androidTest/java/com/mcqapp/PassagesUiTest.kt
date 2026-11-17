package com.mcqapp

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.Passage
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.editor.PassagesScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The passage-management screen on the CI emulator. Moving a passage whose
 * members live with it into another paper's category carries the whole group
 * across the boundary, so saving must ask first and Cancel must write nothing.
 */
@RunWith(AndroidJUnit4::class)
class PassagesUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    private lateinit var passageId: String

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory("pui-p1", "PUI Paper 1", "pui-c1", "PUI Cat 1")
        repository.ensurePaperAndCategory("pui-p2", "PUI Paper 2", "pui-c2", "PUI Cat 2")
        listOf("pui-q1", "pui-q2").forEach { id ->
            repository.saveQuestion(
                Question(
                    id = id,
                    categoryId = "pui-c1",
                    text = "PUI $id",
                    options = listOf(
                        QuestionOption("$id-a", "Alpha"),
                        QuestionOption("$id-b", "Beta")
                    ),
                    correctOptionIds = setOf("$id-a")
                )
            )
        }
        passageId = repository.savePassage(
            Passage(id = "", categoryId = "pui-c1", title = "PUI Group")
        )
        repository.assignQuestionsToPassage(listOf("pui-q1", "pui-q2"), passageId)
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("pui-p1")
        repository.deletePaper("pui-p2")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun movingAPassageToAnotherPaperAsksFirstAndCancelWritesNothing() = runBlocking {
        compose.setContent { PassagesScreen(repository, rememberNavController()) }
        waitFor("PUI Group")

        compose.onNodeWithContentDescription("Edit passage").performClick()
        waitFor("Passage title")

        // Change the passage's category to the other paper's.
        compose.onNodeWithText("PUI Cat 1").performClick()
        compose.onNodeWithText("PUI Cat 2").performClick()
        compose.onNodeWithText("Save").performClick()

        waitFor("Moving to another paper keeps each card's schedule")
        compose.onNodeWithTag("cross-paper-move-cancel").performClick()

        assertEquals(
            "Cancel must leave the passage in its original paper",
            "pui-c1",
            repository.getPassage(passageId)!!.categoryId
        )
    }
}
