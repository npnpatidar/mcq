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
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.AttemptMetadata
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.ui.editor.QuestionEditorScreen
import com.mcqapp.ui.navigation.McqNavHost
import com.mcqapp.ui.results.ResultsScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The deferred custom-test checks from plan §9.2, on the CI emulator: the four
 * phase-3 chips, choosing a tag, the session and result headers naming the
 * choice, and a tag added in the editor reaching the picker's source.
 */
@RunWith(AndroidJUnit4::class)
class CustomTestPathsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    private val paperId = "uitest-custom"
    private val categoryId = "uitest-custom-cat"

    private fun question(index: Int) = Question(
        id = "uitest-custom-q$index",
        categoryId = categoryId,
        text = "UICustom question $index?",
        options = listOf(
            QuestionOption("uitest-custom-q$index-a", "Alpha"),
            QuestionOption("uitest-custom-q$index-b", "Beta")
        ),
        correctOptionIds = setOf("uitest-custom-q$index-a"),
        tags = listOf("polity")
    )

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(paperId, "UICustom Paper", categoryId, "UICustom Cat")
        (1..20).forEach { repository.saveQuestion(question(it)) }
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

    /**
     * The setup sheet offers all four custom chips; choosing Tag, picking the
     * seeded tag and starting names the type in the session banner and the
     * result header instead of calling it Random.
     */
    @Test
    fun customChipsRunATagTestAndBothHeadersNameIt() {
        compose.setContent { McqNavHost(repository = repository) }

        waitFor("UICustom Paper")
        compose.onNodeWithTag("paper-test-$paperId").performClick()
        waitFor("Start a test")

        // The four phase-3 custom types are offered by the one setup sheet.
        listOf("Bookmarked", "Tag", "Authored difficulty", "Slowest").forEach { label ->
            compose.onNodeWithText(label, substring = false).performScrollTo().assertIsDisplayed()
        }

        compose.onNodeWithText("Tag", substring = false).performScrollTo().performClick()
        // The tag picker lists the scope's distinct tags with their counts.
        waitFor("polity · 20")
        compose.onNodeWithText("polity · 20", substring = false).performScrollTo().performClick()

        // The chosen tag resolves a pool of 20, so Start offers the full run.
        waitFor("Start 20-question test")
        compose.onNodeWithTag("test-setup-start").performScrollTo().performClick()

        // Session header: a Tag run is practice, not a Random run.
        waitFor("Practice · Tag · 20 questions")
        compose.onNodeWithText("Practice · Tag · 20 questions", substring = false)
            .assertIsDisplayed()

        // Skip every question, then submit: the result header repeats the
        // type and the chosen tag.
        repeat(19) {
            compose.onNodeWithText("Next", substring = false).performClick()
        }
        compose.onNodeWithText("Submit", substring = false).performClick()
        waitFor("Submit test?")
        compose.onNodeWithTag("confirm-submit").performClick()

        waitFor("Tag · polity")
        compose.onNodeWithText("Tag · polity", substring = true).assertIsDisplayed()
    }

    /**
     * A tag added through the editor's own Add control is what the Tag picker
     * reads. The full picker is exercised above; this pins the editor half of
     * the same loop without a second composition.
     */
    @Test
    fun aTagAddedInTheEditorIsPersistedForThePicker() {
        compose.setContent {
            QuestionEditorScreen(
                repository = repository,
                questionId = "uitest-custom-q1",
                paperId = paperId,
                categoryId = categoryId,
                navController = rememberNavController()
            )
        }

        waitFor("UICustom question 1?")
        compose.onNodeWithText("Add a tag…", substring = false).performTextInput("economy")
        compose.onNodeWithText("Add", substring = false).performClick()
        compose.onNodeWithText("Save", substring = false).performClick()

        compose.waitUntil(30_000) {
            runBlocking {
                repository.getQuestion("uitest-custom-q1")
                    ?.tags?.any { it.equals("economy", ignoreCase = true) } == true
            }
        }
        assertTrue(
            runBlocking {
                repository.getQuestion("uitest-custom-q1")!!
                    .tags.any { it.equals("economy", ignoreCase = true) }
            }
        )
    }

    private fun saveCustomAttempt(): Long = runBlocking {
        val questions = repository.getQuestionsForPaper(paperId)
        repository.saveAttempt(
            paperId = paperId,
            paperTitle = "UICustom Paper",
            questions = questions,
            selections = questions.associate { it.id to setOf("${it.id}-a") },
            negativeMarking = 0.0,
            durationSeconds = 600,
            finishedAt = System.currentTimeMillis(),
            dwellSeconds = questions.associate { it.id to 30L },
            metadata = AttemptMetadata(
                testType = TestType.TAG.name,
                requestedCount = questions.size,
                scopeCategoryIds = categoryId,
                scopeKey = ScopeKey.ofExpanded(listOf(categoryId)),
                samplingVersion = TestPlan.SAMPLING_VERSION,
                exclusionMode = "NONE",
                variant = "polity"
            )
        )
    }

    /**
     * The result page's header names a custom attempt's type and variant even
     * when the report is opened later from history, and the export action
     * offers both share formats. (The chooser itself is an external activity;
     * asserting it needs espresso-intents, which this project does not depend
     * on.)
     */
    @Test
    fun theResultHeaderKeepsTheCustomTypeAndOffersExport() {
        val attemptId = saveCustomAttempt()

        compose.setContent {
            ResultsScreen(
                repository = repository,
                attemptId = attemptId,
                navController = rememberNavController(),
                reviewMode = true
            )
        }

        waitFor("Tag · polity")
        compose.onNodeWithText("Tag · polity", substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription("Export report").performClick()
        compose.onNodeWithText("Export CSV", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Export PDF", substring = false).assertIsDisplayed()
    }
}
