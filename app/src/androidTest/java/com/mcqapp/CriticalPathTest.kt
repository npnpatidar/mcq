package com.mcqapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Critical-path UI test (runs on the CI emulator): seed a paper, start a
 * test from the library, answer, submit, land on results with the right
 * score. Everything else is covered by JVM unit tests.
 */
@RunWith(AndroidJUnit4::class)
class CriticalPathTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    private fun question(id: String) = Question(
        id = id,
        categoryId = "uitest-cat",
        text = "UITest $id?",
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = setOf("$id-a")
    )

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory("uitest-paper", "UITest Paper", "uitest-cat", "UITest Cat")
        repository.saveQuestion(question("uitest-q1"))
        repository.saveQuestion(question("uitest-q2"))
        repository.saveQuestion(question("uitest-q3"))
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("uitest-paper")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun libraryToResults() {
        compose.setContent { McqNavHost(repository = repository) }

        // Library -> start the seeded paper (drawer holds a duplicate title).
        // The tag assert proves layout is done, so the adjacent Start click
        // needs no scroll synchronization.
        waitFor("UITest Paper")
        compose.onNodeWithTag("paper-title").assertIsDisplayed()
        compose.onNodeWithText("Start", substring = false).performClick()

        // Q1 correct, Q2 skipped, Q3 wrong.
        waitFor("Question 1 of 3")
        compose.onNodeWithText("Alpha", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Next", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Next", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Beta", substring = false).performScrollTo().performClick()

        // Submit through the confirmation dialog (tagged: the screen behind
        // holds another Submit button).
        compose.onNodeWithText("Submit", substring = false).performScrollTo().performClick()
        waitFor("Submit test?")
        compose.onNodeWithTag("confirm-submit").performClick()

        // Results: 1 correct, 1 wrong, no negative marking -> 1.0 / 3.
        waitFor("1.0 / 3")
        compose.onNodeWithText("1.0 / 3", substring = false).assertIsDisplayed()
    }
}
