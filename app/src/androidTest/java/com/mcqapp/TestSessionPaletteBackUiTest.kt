package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
 * System Back while the question palette is open must dismiss the palette, not
 * pop the test. The palette is an inline Card rather than a dialog, so with no
 * handler of its own Back fell through to the NavController and dropped the
 * learner out of the sitting with no confirm and no save.
 */
@RunWith(AndroidJUnit4::class)
class TestSessionPaletteBackUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    @Before
    fun setUp() = runBlocking {
        repository.setStrictMode(false)
        repository.clearTestProgress()
        repository.ensurePaperAndCategory(PAPER, "Palette Back Paper", CAT, "Palette Cat")
        repository.saveQuestion(question("pb-q1"))
        repository.saveQuestion(question("pb-q2"))
    }

    @After
    fun tearDown() = runBlocking {
        repository.clearTestProgress()
        repository.deletePaper(PAPER)
    }

    private fun question(id: String) = Question(
        id = id,
        categoryId = CAT,
        text = "PB $id?",
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = setOf("$id-a")
    )

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun paletteIsOpen() =
        compose.onAllNodesWithContentDescription("Go to question 1", substring = true)
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun backClosesThePaletteAndKeepsTheTestOnScreen() {
        compose.setContent { McqNavHost(repository = repository) }

        waitFor("Palette Back Paper")
        compose.onNodeWithTag("paper-test-$PAPER").performClick()
        waitFor("Start a test")
        compose.onNodeWithTag("test-setup-start").performScrollTo().performClick()
        waitFor("Question 1 of 2")

        // Open the inline palette through the position chip.
        compose.onNodeWithText("0/2 answered", substring = false).performClick()
        compose.waitUntil(30_000) { paletteIsOpen() }
        compose.onNodeWithText("Question 1 of 2", substring = true).assertIsDisplayed()

        compose.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()

        // On the old code Back fell through to the NavController: the palette,
        // the question and the submit-confirm dialog all vanished at once and
        // the library was on screen instead.
        compose.waitUntil(30_000) { !paletteIsOpen() }
        compose.onNodeWithText("Question 1 of 2", substring = true).assertIsDisplayed()
        check(
            compose.onAllNodesWithText("Submit test?", substring = false)
                .fetchSemanticsNodes().isEmpty()
        ) { "closing the palette must not open the submit confirmation" }
    }

    private companion object {
        const val PAPER = "uitest-palette-back"
        const val CAT = "uitest-palette-cat"
    }
}
