package com.mcqapp

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
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
 * The Question Editor screen as the learner sees it (runs on the CI
 * emulator): new-question framing, the validation surface, the leave
 * prompt, delete confirmation, option labelling and boundary controls.
 *
 * These pin the visible side of the audit fixes — a disabled control must
 * say why, a dirty draft must not vanish in one tap, and a destructive
 * action must ask first.
 */
@RunWith(AndroidJUnit4::class)
class QuestionEditorUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(
            "uitest-ui-paper", "UITest UI Paper", "uitest-ui-cat", "UITest UI Cat"
        )
        repository.saveQuestion(
            Question(
                id = "uitest-ui-q1",
                categoryId = "uitest-ui-cat",
                text = "UITest UI question?",
                options = listOf(
                    QuestionOption("uitest-ui-q1-a", "Alpha"),
                    QuestionOption("uitest-ui-q1-b", "Beta")
                ),
                correctOptionIds = setOf("uitest-ui-q1-a")
            )
        )
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("uitest-ui-paper")
        repository.deletePaper("uitest-ui-other")
        EditorSession.clear()
        ImportDataHolder.pendingEditQuestion = null
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The editor inside a two-destination graph, so Back is observable. */
    private fun setEditorInAGraph(questionId: String) {
        compose.setContent {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "list") {
                composable("list") { Text("LIST SCREEN") }
                composable("editor") {
                    QuestionEditorScreen(
                        repository = repository,
                        questionId = questionId,
                        paperId = "uitest-ui-paper",
                        categoryId = "uitest-ui-cat",
                        navController = nav
                    )
                }
            }
            LaunchedEffect(Unit) { nav.navigate("editor") }
        }
        waitFor("Add block")
    }

    private fun setPlainEditor(questionId: String) {
        compose.setContent {
            QuestionEditorScreen(
                repository = repository,
                questionId = questionId,
                paperId = "uitest-ui-paper",
                categoryId = "uitest-ui-cat",
                navController = rememberNavController()
            )
        }
        waitFor("Add block")
    }

    private fun bodyFields() = compose.onAllNodesWithText("Text", substring = false)

    private fun dialogNode(text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    // ---- new-question framing ----

    @Test
    fun newQuestionIsTitledNewAndOffersNoDelete() {
        setPlainEditor("")

        waitFor("Option A")
        compose.onNodeWithText("New question").assertExists()
        assertTrue(
            "a new question cannot delete a row it does not have",
            compose.onAllNodesWithContentDescription("Delete question").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun blankNewQuestionExplainsTheDisabledSave() {
        setPlainEditor("")
        waitFor("Option A")

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Every option needs content to save.").assertExists()
    }

    @Test
    fun optionsAreLabelledByPositionAndBoundaryMovesAreDisabled() {
        setPlainEditor("")
        waitFor("Option A")

        compose.onNodeWithText("Option A").assertExists()
        compose.onNodeWithText("Option B").assertExists()
        compose.onNodeWithContentDescription("Move option A up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move option B down").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move option B up").assertIsEnabled()
    }

    // ---- marks validation ----

    @Test
    fun marksEntryIsValidatedInline() {
        setPlainEditor("uitest-ui-q1")
        waitFor("UITest UI question?")

        val marks = compose.onNodeWithText("Marks (default 1)")
        marks.performTextClearance()
        marks.performTextInput("abc")

        compose.onNodeWithText("Marks must be zero or a positive number.").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()

        marks.performTextClearance()
        marks.performTextInput("2")
        compose.onNodeWithText("Marks must be zero or a positive number.").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    // ---- leave prompt ----

    @Test
    fun cleanBackLeavesWithoutAPrompt() {
        setEditorInAGraph("")
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("LIST SCREEN")
        compose.onNodeWithText("Unsaved changes").assertDoesNotExist()
    }

    @Test
    fun dirtyBackPromptsAndCancelKeepsTheDraft() {
        setEditorInAGraph("")
        bodyFields()[0].performTextInput("Typed body")

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Unsaved changes")
        dialogNode("Cancel").performClick()

        compose.onNodeWithText("Unsaved changes").assertDoesNotExist()
        assertTrue(
            "the draft is still on screen after Cancel",
            compose.onAllNodesWithText("Typed body", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithText("LIST SCREEN").assertDoesNotExist()
    }

    @Test
    fun dirtyInvalidDraftCannotBeSavedFromThePrompt() {
        setEditorInAGraph("")
        // Body typed, options still empty: dirty but not savable.
        bodyFields()[0].performTextInput("Typed body")

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Unsaved changes")
        compose.onNodeWithText("Fix the highlighted problems to save, or discard your changes.")
            .assertExists()
        dialogNode("Save").assertIsNotEnabled()
        // Discard is still offered, and it leaves.
        dialogNode("Discard").performClick()
        waitFor("LIST SCREEN")
    }

    @Test
    fun discardFromThePromptLeavesTheEditor() {
        setEditorInAGraph("uitest-ui-q1")
        waitFor("UITest UI question?")
        bodyFields()[0].performTextInput(" edited")

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Unsaved changes")
        dialogNode("Discard").performClick()
        waitFor("LIST SCREEN")
    }

    // ---- tables and tags ----

    @Test
    fun aBodyTableCanBeAddedAndItsCellsTypedInto() {
        setPlainEditor("uitest-ui-q1")
        waitFor("UITest UI question?")

        compose.onAllNodesWithText("Add block")[0].performClick()
        compose.onNodeWithText("Table").performClick()

        // The cell uses a stateDescription (not contentDescription), so the
        // typed value stays accessible and the cell is still targetable.
        val cell = compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Row 1, column 1")
        )
        cell.assertExists()
        cell.performTextInput("cell")
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Row 1, column 1") and
                hasText("cell")
        ).assertExists()
    }

    @Test
    fun tagsAddedThroughTheFieldBecomeRemovableChips() {
        setPlainEditor("uitest-ui-q1")
        waitFor("UITest UI question?")

        compose.onNodeWithText("Add a tag…").performTextInput("cells")
        compose.onNodeWithText("Add").performClick()

        compose.onNodeWithContentDescription("Remove tag cells").assertExists()
        compose.onNodeWithContentDescription("Remove tag cells").performClick()
        compose.onNodeWithContentDescription("Remove tag cells").assertDoesNotExist()
    }

    // ---- delete confirmation ----

    @Test
    fun deleteAsksForConfirmationAndCancelKeepsTheQuestion() {
        setPlainEditor("uitest-ui-q1")
        waitFor("UITest UI question?")

        compose.onNodeWithContentDescription("Delete question").performClick()
        waitFor("Delete question?")
        dialogNode("Cancel").performClick()

        compose.onNodeWithText("Delete question?").assertDoesNotExist()
        compose.onNodeWithText("UITest UI question?").assertExists()
    }

    // ---- review-fix batch (M3, M5c) ----

    /** M5c: Save in the leave dialog persists the edit and then pops. */
    @Test
    fun saveFromTheLeaveDialogPersistsThenPops() {
        setEditorInAGraph("uitest-ui-q1")
        waitFor("UITest UI question?")
        bodyFields()[0].performTextInput(" edited")

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Unsaved changes")
        dialogNode("Save").performClick()
        waitFor("LIST SCREEN")

        val saved = runBlocking { repository.getQuestion("uitest-ui-q1") }
        assertTrue("the edit was persisted", saved?.text?.contains("edited") == true)
    }

    /** M5c: confirming Delete removes exactly the question being edited. */
    @Test
    fun confirmingDeleteRemovesTheQuestion() {
        setPlainEditor("uitest-ui-q1")
        waitFor("UITest UI question?")

        compose.onNodeWithContentDescription("Delete question").performClick()
        waitFor("Delete question?")
        dialogNode("Delete").performClick()
        compose.waitUntil(10_000) {
            runBlocking { repository.getQuestion("uitest-ui-q1") == null }
        }
        assertTrue(
            "the row is gone",
            runBlocking { repository.getQuestion("uitest-ui-q1") == null }
        )
    }

    /**
     * M3: an import-source edit carries a passage id but no passage list. The
     * picker must be hidden rather than show "No passage" for a set id whose
     * only action clears membership.
     */
    @Test
    fun importEditorWithNoPassageListHidesThePicker() {
        val dto = QuestionDto(
            id = "uitest-import-q",
            elements = listOf(ContentElement.TextElement("Imported question?")),
            options = listOf(
                OptionDto("a", "A", listOf(ContentElement.TextElement("A"))),
                OptionDto("b", "B", listOf(ContentElement.TextElement("B")))
            ),
            correctOptionIds = listOf("a"),
            passageId = "missing-passage"
        )
        ImportDataHolder.pendingEditQuestion = dto
        compose.setContent {
            QuestionEditorScreen(
                repository = repository,
                questionId = dto.id,
                paperId = "",
                categoryId = "",
                navController = rememberNavController(),
                source = "import"
            )
        }
        waitFor("Add block")
        compose.onNodeWithText("Assign to passage").assertDoesNotExist()
        compose.onNodeWithText("No passage").assertDoesNotExist()
    }
}
