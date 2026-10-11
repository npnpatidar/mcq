package com.mcqapp

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.editor.QuestionEditorScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's Save-button enablement (runs on the CI emulator).
 *
 * `canProceed` used to be read straight off the ViewModel inside the screen's
 * outer composable scope, which holds no other state read — so it was
 * computed once while the form was still loading and never recomputed, and
 * Save stayed disabled for a fully valid question, edited or new. These tests
 * pin the button's reactivity: enabled for a loaded valid question, and
 * flipped on by typing for a brand-new one.
 */
@RunWith(AndroidJUnit4::class)
class EditorSaveButtonUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(
            "uitest-editor-paper", "UITest Editor Paper", "uitest-editor-cat", "UITest Editor Cat"
        )
        repository.saveQuestion(
            Question(
                id = "uitest-editor-q1",
                categoryId = "uitest-editor-cat",
                text = "UITest editor question?",
                options = listOf(
                    QuestionOption("uitest-editor-q1-a", "Alpha"),
                    QuestionOption("uitest-editor-q1-b", "Beta")
                ),
                correctOptionIds = setOf("uitest-editor-q1-a")
            )
        )
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("uitest-editor-paper")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun editingAValidQuestionEnablesSave() {
        compose.setContent {
            QuestionEditorScreen(
                repository = repository,
                questionId = "uitest-editor-q1",
                paperId = "uitest-editor-paper",
                categoryId = "uitest-editor-cat",
                navController = rememberNavController()
            )
        }

        // The loaded question is valid, so Save must be clickable without a
        // single edit. Before the fix this never became true.
        waitFor("UITest editor question?")
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun saveFollowsTypingForANewQuestion() {
        compose.setContent {
            QuestionEditorScreen(
                repository = repository,
                questionId = "",
                paperId = "uitest-editor-paper",
                categoryId = "uitest-editor-cat",
                navController = rememberNavController()
            )
        }
        compose.waitForIdle()

        // A fresh form is empty: Save must stay disabled. The text blocks are
        // found by their "Text" label; the body comes before the two option
        // fields in the column, so indices address them.
        compose.onNodeWithText("Save").assertIsNotEnabled()
        val textBlocks = compose.onAllNodesWithText("Text", substring = false)
        textBlocks[0].performTextInput("What is 2+2?")
        textBlocks[1].performTextInput("3")
        textBlocks[2].performTextInput("4")
        compose.onNodeWithText("Save").assertIsEnabled()
    }
}
