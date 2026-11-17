package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * Test-session aids and overlays on the CI emulator. Strict mode is a stored
 * setting, so each test sets it explicitly before opening a paper; the palette
 * and flag controls must disappear together with answer reveal.
 */
@RunWith(AndroidJUnit4::class)
class TestSessionUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository

    @Before
    fun seed() = runBlocking {
        repository.setStrictMode(false)
        repository.clearTestProgress()
        repository.ensurePaperAndCategory(PAPER, "TUI Paper", CAT, "TUI Cat")
        repository.saveQuestion(question("tui-q1"))
        repository.saveQuestion(question("tui-q2"))
    }

    @After
    fun tearDown() = runBlocking {
        repository.setStrictMode(false)
        repository.clearTestProgress()
        repository.deletePaper(PAPER)
    }

    private fun question(id: String) = Question(
        id = id,
        categoryId = CAT,
        text = "TUI $id?",
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = setOf("$id-a")
    )

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertNoText(text: String) {
        check(
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isEmpty()
        ) { "did not expect a '$text' node" }
    }

    private fun openTest() {
        compose.setContent { McqNavHost(repository = repository) }
        waitFor("TUI Paper")
        compose.onNodeWithTag("paper-test-$PAPER").performClick()
        waitFor("Start a test")
        compose.onNodeWithTag("test-setup-start").performScrollTo().performClick()
        waitFor("Question 1 of 2")
    }

    private fun pressSystemBack() {
        compose.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    @Test
    fun strictModeHidesFlagPaletteAndAnswerReveal() {
        // Strict mode is read once when the screen-scoped ViewModel launches.
        runBlocking { repository.setStrictMode(true) }
        openTest()

        val strictNotice = app.getString(R.string.strict_exam_aids_hidden_until_submit)
        waitFor(strictNotice)
        compose.onNodeWithText(strictNotice, substring = true).assertIsDisplayed()

        compose.onNodeWithText("Question 1 of 2", substring = true).assertIsDisplayed()
        check(
            compose.onAllNodesWithContentDescription("Flag question", substring = true)
                .fetchSemanticsNodes().isEmpty()
        ) { "flag control must be hidden in strict mode" }
        assertNoText("0/2 answered")
        assertNoText("Show answer")
        // Without the palette button, only the plain position remains.
        check(
            compose.onAllNodesWithText("1/2", substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        ) { "expected the plain position readout in strict mode" }
    }

    @Test
    fun normalModeShowsFlagAndOpensPalette() {
        openTest()

        compose.onNodeWithContentDescription("Flag question", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("0/2 answered", substring = false).assertIsDisplayed()

        compose.onNodeWithText("0/2 answered", substring = false).performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("Go to question 1", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Go to question 1", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun answerReviewOpensFromSubmitAndDismisses() {
        openTest()

        // The only route to the review sheet is the submit summary's action.
        compose.onNodeWithText("Submit test", substring = false).performClick()
        waitFor("Submit test?")
        compose.onNodeWithText("Review answers", substring = false).performClick()
        waitFor("Your answers")

        compose.onNodeWithText("Your answers", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Q1", substring = false).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.status_unanswered), substring = false)
            .assertIsDisplayed()

        compose.onNodeWithText("Close", substring = false).performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Your answers", substring = false)
                .fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Question 1 of 2", substring = true).assertIsDisplayed()
    }

    @Test
    fun backDuringLiveQuestionAsksToSubmitInsteadOfLeaving() {
        openTest()

        pressSystemBack()
        waitFor("Submit test?")

        compose.onNodeWithText("Submit test?", substring = false).assertIsDisplayed()
        // The destination is still on screen; Back did not pop the test.
        compose.onNodeWithText("Question 1 of 2", substring = true).assertIsDisplayed()
    }

    private companion object {
        const val PAPER = "uitest-tui-paper"
        const val CAT = "uitest-tui-cat"
    }
}
