package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.mcqapp.ui.test.TestViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the mid-save Back press (audit H3).
 *
 * submit() publishes `saving = true` before it starts the attempt write. The
 * screen-scoped ViewModel is the only owner of that in-flight write, so a pop
 * clears it and the Room transaction rolls back — the answer is lost with no
 * error shown to the learner. A test-only gate holds the window open so both
 * Back affordances are pressed deterministically while the attempt is *not*
 * durable: the destination must not pop, and the write must still commit.
 */
@RunWith(AndroidJUnit4::class)
class TestSessionBackWhileSavingUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    private val saveGate = CompletableDeferred<Unit>()

    @Before
    fun setUp() = runBlocking {
        TestViewModel.beforeSaveForTest = { saveGate.await() }
        repository.ensurePaperAndCategory(PAPER, "H3 Back Paper", CAT, "H3 Cat")
        repository.saveQuestion(question("h3-q1"))
        repository.saveQuestion(question("h3-q2"))
    }

    @After
    fun tearDown() = runBlocking {
        TestViewModel.beforeSaveForTest = null
        // Unblock anything still waiting so a failed assertion cannot hang CI.
        saveGate.complete(Unit)
        repository.deletePaper(PAPER)
    }

    private fun question(id: String) = Question(
        id = id,
        categoryId = CAT,
        text = "H3 $id?",
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = setOf("$id-a")
    )

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun pressSystemBack() {
        compose.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    @Test
    fun backDuringSaveCannotPopTheTestScreenOrLoseTheAttempt() {
        compose.setContent { McqNavHost(repository = repository) }

        waitFor("H3 Back Paper")
        compose.onNodeWithTag("paper-test-$PAPER").performClick()
        waitFor("Start a test")
        compose.onNodeWithTag("test-setup-start").performScrollTo().performClick()

        waitFor("Question 1 of 2")
        compose.onNodeWithText("Alpha", substring = false).performClick()
        // The footer's exact "Submit" only exists on the last question; the
        // bottom "Submit test" control is on every question, so stay on Q1 and
        // keep the assertions below anchored to the same question.
        compose.onNodeWithText("Submit test", substring = false).performClick()
        waitFor("Submit test?")
        compose.onNodeWithTag("confirm-submit").performClick()

        // The gate holds the write open. This indicator only renders while
        // `saving` is true, so the back callback is enabled by now.
        waitFor("Saving result")
        compose.onNodeWithText("Saving result", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Question 1 of 2", substring = true).assertIsDisplayed()

        // Both ways off the screen: system Back and the top-bar arrow.
        // On the old code each fell through to popBackStack(), clearing the
        // ViewModel and cancelling the write.
        pressSystemBack()
        compose.onNodeWithText("Question 1 of 2", substring = true).assertIsDisplayed()
        assertTrue(
            "system Back escaped to the library mid-save",
            compose.onAllNodesWithTag("paper-title-$PAPER").fetchSemanticsNodes().isEmpty()
        )

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Question 1 of 2", substring = true).assertIsDisplayed()
        assertTrue(
            "the top-bar arrow escaped to the library mid-save",
            compose.onAllNodesWithTag("paper-title-$PAPER").fetchSemanticsNodes().isEmpty()
        )

        // Release the gate: the attempt must land and the screen move on to
        // results on its own. A popped ViewModel would have cancelled here.
        saveGate.complete(Unit)
        compose.waitUntil(30_000) {
            runBlocking { repository.db().attemptDao().getByPaper(PAPER).size == 1 }
        }
        assertEquals(1, runBlocking { repository.db().attemptDao().getByPaper(PAPER).size })
        waitFor("1.0 / 2")
        compose.onNodeWithText("1.0 / 2", substring = false).assertIsDisplayed()
    }

    private companion object {
        const val PAPER = "uitest-h3-back"
        const val CAT = "uitest-h3-cat"
    }
}
