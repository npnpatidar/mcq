package com.mcqapp

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.mcqapp.ui.editor.EditorSession
import com.mcqapp.ui.editor.QuestionEditorScreen
import com.mcqapp.ui.importscreen.ImportDataHolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The card-type surface of the Question Editor as the learner sees it: a
 * flashcard opens without an options section, previews its back, and the type
 * switch either asks before clearing typed options or seeds two blank ones.
 *
 * Runs on the CI emulator; the editor's state machine is pinned by
 * `FlashcardEditorStateTest` and `FlashcardEditorViewModelTest` too.
 */
@RunWith(AndroidJUnit4::class)
class FlashcardEditorUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(
            "uitest-fc-paper", "UITest FC Paper", "uitest-fc-cat", "UITest FC Cat"
        )
        // A flashcard: no options, the back is the answer.
        repository.saveQuestion(
            Question(
                id = "uitest-fc-card",
                categoryId = "uitest-fc-cat",
                elements = listOf(ContentElement.TextElement("Capital of France?")),
                options = emptyList(),
                correctOptionIds = emptySet(),
                explanationElements = listOf(ContentElement.TextElement("Paris"))
            )
        )
        // A card that is not answered yet.
        repository.saveQuestion(
            Question(
                id = "uitest-fc-empty",
                categoryId = "uitest-fc-cat",
                elements = listOf(ContentElement.TextElement("Capital of Italy?")),
                options = emptyList(),
                correctOptionIds = emptySet()
            )
        )
        // A question with typed options.
        repository.saveQuestion(
            Question(
                id = "uitest-fc-q1",
                categoryId = "uitest-fc-cat",
                elements = listOf(ContentElement.TextElement("UITest FC question?")),
                options = listOf(
                    QuestionOption("uitest-fc-q1-a", "Alpha"),
                    QuestionOption("uitest-fc-q1-b", "Beta")
                ),
                correctOptionIds = setOf("uitest-fc-q1-a")
            )
        )
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("uitest-fc-paper")
        EditorSession.clear()
        ImportDataHolder.pendingEditQuestion = null
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setEditor(questionId: String) {
        compose.setContent {
            QuestionEditorScreen(
                repository = repository,
                questionId = questionId,
                paperId = "uitest-fc-paper",
                categoryId = "uitest-fc-cat",
                navController = rememberNavController()
            )
        }
        waitFor("Add block")
    }

    private fun dialogNode(text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    @Test
    fun aFlashcardOpensWithoutOptionsAndPreviewsItsBack() {
        setEditor("uitest-fc-card")
        waitFor("Capital of France?")

        compose.onNodeWithText("Multiple choice").assertExists()
        compose.onNodeWithText("Flashcard").assertExists()
        compose.onNodeWithText("Option A").assertDoesNotExist()
        compose.onNodeWithText("Add option").assertDoesNotExist()
        compose.onNodeWithText("Tick the correct answer(s)").assertDoesNotExist()
        // The preview shows the back, not just the stem.
        compose.onNode(
            hasText("Paris") and hasAnyAncestor(hasTestTag("editor-preview"))
        ).assertExists()
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun aCardWithoutABackCannotBeSavedAndSaysWhy() {
        setEditor("uitest-fc-empty")
        waitFor("Capital of Italy?")

        compose.onNodeWithText("Add the answer to save this flashcard.").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun switchingAQuestionToAFlashcardAsksBeforeClearingItsOptions() {
        setEditor("uitest-fc-q1")
        waitFor("UITest FC question?")

        compose.onNodeWithTag("card-type-flashcard").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Switch to a flashcard?") and hasAnyAncestor(isDialog()))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Cancel keeps everything.
        dialogNode("Cancel").performClick()
        compose.onNodeWithText("Switch to a flashcard?").assertDoesNotExist()
        compose.onNodeWithText("Option A").assertExists()

        // Confirming clears them and hides the section.
        compose.onNodeWithTag("card-type-flashcard").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Switch to a flashcard?") and hasAnyAncestor(isDialog()))
                .fetchSemanticsNodes().isNotEmpty()
        }
        dialogNode("Switch").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Option A").assertDoesNotExist()
        compose.onNodeWithText("Tick the correct answer(s)").assertDoesNotExist()
        compose.onNodeWithText("No answer is marked correct — this question will not be graded.")
            .assertDoesNotExist()
    }

    @Test
    fun switchingACardBackToAQuestionSeedsTwoBlankOptions() {
        setEditor("uitest-fc-card")
        waitFor("Capital of France?")

        compose.onNodeWithTag("card-type-multiple-choice").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Option A").assertExists()
        compose.onNodeWithText("Option B").assertExists()
        compose.onNodeWithText("Tick the correct answer(s)").assertExists()
        compose.onNodeWithText("Every option needs content to save.").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun aQuestionStillReadsAsAQuestion() {
        setEditor("uitest-fc-q1")
        waitFor("UITest FC question?")

        compose.onNodeWithText("Option A").assertExists()
        compose.onNodeWithText("Tick the correct answer(s)").assertExists()
        assertTrue(
            "a question's preview has one side",
            compose.onAllNodes(hasText("Paris") and hasAnyAncestor(hasTestTag("editor-preview")))
                .fetchSemanticsNodes().isEmpty()
        )
    }
}
