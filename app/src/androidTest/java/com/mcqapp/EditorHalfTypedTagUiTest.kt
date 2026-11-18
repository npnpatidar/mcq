package com.mcqapp

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.editor.EditorSession
import com.mcqapp.ui.editor.QuestionEditorScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half-typed "add a tag" draft belongs to the question on screen. It used
 * to be a plain [androidx.compose.runtime.saveable.rememberSaveable] with no
 * input, so pressing Prev/Next kept the text and showed A's draft on B.
 *
 * The editor's ViewModel stays the same across a move (Prev/Next only swaps
 * the state's questionId), so this is exactly the case the input key fixes.
 * Runs on the CI emulator with the real repository, like the other editor
 * Compose tests.
 */
@RunWith(AndroidJUnit4::class)
class EditorHalfTypedTagUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(
            "tagtest-paper", "TagTest Paper", "tagtest-cat", "TagTest Cat"
        )
        listOf("tagtest-q1" to "First tag question?", "tagtest-q2" to "Second tag question?")
            .forEach { (id, text) ->
                repository.saveQuestion(
                    Question(
                        id = id,
                        categoryId = "tagtest-cat",
                        text = text,
                        options = listOf(
                            QuestionOption("$id-a", "Alpha"),
                            QuestionOption("$id-b", "Beta")
                        ),
                        correctOptionIds = setOf("$id-a")
                    )
                )
            }
    }

    @After
    fun cleanup() = runBlocking {
        EditorSession.clear()
        repository.deletePaper("tagtest-paper")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun halfTypedTagDoesNotFollowNextToAnotherQuestion() {
        EditorSession.start(listOf("tagtest-q1", "tagtest-q2"), index = 0)
        compose.setContent {
            QuestionEditorScreen(
                repository = repository,
                questionId = "tagtest-q1",
                paperId = "tagtest-paper",
                categoryId = "tagtest-cat",
                navController = rememberNavController()
            )
        }

        waitFor("First tag question?")
        compose.onNodeWithTag("new-tag-field").performTextInput("half-tag")
        // The Add button is enabled only for a nonblank draft: proof the input
        // really holds the text before navigating.
        compose.onNodeWithText("Add").assertIsEnabled()

        compose.onNodeWithText("Next").performClick()
        waitFor("Second tag question?")

        // A's draft must not appear on B: the field is keyed by question id.
        // Assert the editable value, not the merged Text semantics — an empty
        // field still shows the "Add a tag…" placeholder as its Text.
        // On the old unkeyed rememberSaveable this still read "half-tag" and
        // the Add button stayed enabled.
        val editable = compose.onNodeWithTag("new-tag-field")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("", editable)
        compose.onNodeWithText("Add").assertIsNotEnabled()
    }
}
