package com.mcqapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.navigation.McqNavHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The blueprint paths from exam_blueprint.md §11.3, on the CI emulator:
 * create a nested blueprint (Science → Physics, Chemistry) in the editor,
 * start it, assert the session banner and the report's section tree; a
 * section larger than its pool shows the shortfall in the setup preview
 * before Start; delete the blueprint and assert the Tests tab still shows
 * the attempt's label from its composition.
 *
 * The bank is 15 Physics + 15 Chemistry questions under Science, so a
 * 50-question request is short by the preview's own count (30 achievable).
 */
@RunWith(AndroidJUnit4::class)
class BlueprintPathsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    private val paperId = "uitest-blueprint"
    private val scienceId = "uitest-blueprint-science"
    private lateinit var physicsId: String
    private lateinit var chemistryId: String

    private fun question(index: Int, categoryId: String, prefix: String) = Question(
        id = "uitest-blueprint-$prefix-q$index",
        categoryId = categoryId,
        text = "UIBlueprint $prefix question $index?",
        options = listOf(
            QuestionOption("uitest-blueprint-$prefix-q$index-a", "Alpha"),
            QuestionOption("uitest-blueprint-$prefix-q$index-b", "Beta")
        ),
        correctOptionIds = setOf("uitest-blueprint-$prefix-q$index-a")
    )

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(paperId, "UIBlueprint Paper", scienceId, "Science")
        physicsId = repository.addCategory(paperId, "Physics", scienceId)
        chemistryId = repository.addCategory(paperId, "Chemistry", scienceId)
        (1..15).forEach { repository.saveQuestion(question(it, physicsId, "physics")) }
        (1..15).forEach { repository.saveQuestion(question(it, chemistryId, "chemistry")) }
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper(paperId)
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun createNestedBlueprintRunItAndReadItsLabelAfterDeletion() {
        compose.setContent { McqNavHost(repository = repository) }

        waitFor("UIBlueprint Paper")
        compose.onNodeWithTag("paper-test-$paperId").performClick()
        waitFor("Start a test")
        compose.waitForIdle()

        // The Blueprint chip and its picker.
        compose.onNodeWithText("Blueprint", substring = false).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("New…", substring = false).performScrollTo().performClick()

        // The editor: name it and weight Science, Physics and Chemistry.
        waitFor("New blueprint")
        compose.onNodeWithTag("blueprint-name").performTextInput("UIBlueprint")
        compose.onNodeWithTag("blueprint-inc-$scienceId").performScrollTo().performClick()
        compose.onNodeWithTag("blueprint-inc-$physicsId").performScrollTo().performClick()
        compose.onNodeWithTag("blueprint-inc-$chemistryId").performScrollTo().performClick()
        compose.onNodeWithTag("blueprint-save").performScrollTo().performClick()

        // The saved blueprint is picked, and its resolved tree is shown:
        // Science's 20 questions split evenly between its children.
        // onSaved already selected it, so the chip must not be clicked again —
        // the chip toggles, and a second click would deselect it.
        waitFor("UIBlueprint · 3")
        compose.onNodeWithText("UIBlueprint · 3", substring = false).assertIsDisplayed()
        compose.onNodeWithTag("test-setup-preset-20").performScrollTo().performClick()
        waitFor("Physics · 10/15")
        compose.onNodeWithText("Physics · 10/15", substring = false).assertExists()

        // The blueprint's union is 30 questions; a 20-question run fits it
        // exactly, so Start runs the requested length. A preset above the
        // achievable pool is disabled by design, so the asked/will-run clamp
        // line is unreachable from this bank.
        compose.onNodeWithTag("test-setup-start").performScrollTo().performClick()

        // Session banner names the type and the run length.
        waitFor("Practice · Blueprint · 20 questions")
        compose.onNodeWithText("Practice · Blueprint · 20 questions", substring = false)
            .assertIsDisplayed()

        // Skip every question, then submit: the report carries the blueprint tree.
        repeat(19) {
            compose.onNodeWithText("Next", substring = false).performClick()
        }
        compose.onNodeWithText("Submit", substring = false).performClick()
        waitFor("Submit test?")
        compose.onNodeWithTag("confirm-submit").performClick()

        waitFor("Blueprint · UIB")
        compose.onNodeWithText("Blueprint · UIB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Physics", substring = true).assertIsDisplayed()

        // Back to the library and delete the blueprint.
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("UIBlueprint Paper")
        compose.onNodeWithTag("paper-test-$paperId").performClick()
        waitFor("Start a test")
        compose.onNodeWithText("Blueprint", substring = false).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Delete blueprint").performScrollTo().performClick()
        waitFor("Delete blueprint")
        compose.onNodeWithText("Delete", substring = false).performClick()
        waitFor("No blueprints yet")

        // The attempt's label survives the deletion, read from its
        // composition on the Tests tab.
        compose.onNodeWithText("Cancel", substring = false).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Statistics", substring = false).performClick()
        waitFor("Tests")
        compose.onNodeWithText("Tests", substring = false).performScrollTo().performClick()
        waitFor("UIBlueprint")
        compose.onNodeWithText("UIBlueprint", substring = true).assertIsDisplayed()
    }
}
