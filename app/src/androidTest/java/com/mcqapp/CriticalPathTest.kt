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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    private fun question(id: String, categoryId: String = "uitest-cat") = Question(
        id = id,
        categoryId = categoryId,
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
        compose.onNodeWithTag("paper-title-uitest-paper").assertIsDisplayed()
        compose.onNodeWithText("Start", substring = false).performClick()

        // Q1 correct, Q2 skipped, Q3 wrong. Options live in the
        // scrolling column; Next/Submit sit in the fixed footer.
        waitFor("Question 1 of 3")
        compose.onNodeWithText("Alpha", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Next", substring = false).performClick()
        compose.onNodeWithText("Next", substring = false).performClick()
        compose.onNodeWithText("Beta", substring = false).performScrollTo().performClick()

        // Submit through the confirmation dialog (tagged: the screen behind
        // holds another Submit button).
        compose.onNodeWithText("Submit", substring = false).performClick()
        waitFor("Submit test?")
        compose.onNodeWithTag("confirm-submit").performClick()

        // Results: 1 correct, 1 wrong, no negative marking -> 1.0 / 3.
        waitFor("1.0 / 3")
        compose.onNodeWithText("1.0 / 3", substring = false).assertIsDisplayed()
    }

    @Test
    fun studyGradesAndSchedulesTheNextReview() {
        // This test needs a paper whose cards have never been scheduled. The
        // other test in this class submits an attempt on uitest-paper, which
        // would seed card_state from that history, so use a separate paper.
        runBlocking {
            repository.ensurePaperAndCategory("uitest-sr", "UISR Paper", "uitest-sr-cat", "UISR Cat")
            repository.saveQuestion(question("uitest-sr-q1", "uitest-sr-cat"))
            repository.saveQuestion(question("uitest-sr-q2", "uitest-sr-cat"))
        }
        compose.setContent { McqNavHost(repository = repository) }

        // The tag assert proves the seeded card is laid out, so the Study
        // button beside it needs no scroll synchronization. Matching on the
        // title text alone would hit the drawer's duplicate copy.
        compose.onNodeWithTag("paper-title-uitest-sr").assertIsDisplayed()
        // Never-studied cards are counted as new rather than due.
        compose.onNodeWithText("Study (2 new)", substring = false).performClick()

        waitFor("Show answer")
        compose.onNodeWithText("Alpha", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Show answer", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Good", substring = false).performScrollTo().performClick()

        // Grading advances to the next card rather than ending the session.
        waitFor("Show answer")
        compose.onNodeWithText("Show answer", substring = false).assertIsDisplayed()

        // The schedule is persisted, so the card is no longer new and is not
        // due until its first interval elapses.
        val scheduled = runBlocking {
            repository.cardState("uitest-sr", "uitest-sr-q1")
        }
        assertNotNull(scheduled)
        assertEquals(1, scheduled!!.reps)
        assertTrue(scheduled.dueAt > System.currentTimeMillis())

        // The queue is per session, so only one card is graded; the other
        // stays new and is untouched until its turn.
        val untouched = runBlocking {
            repository.cardState("uitest-sr", "uitest-sr-q2")
        }
        assertNotNull(untouched)
        assertEquals(0, untouched!!.reps)
        assertTrue(untouched.isNew)

        runBlocking { repository.deletePaper("uitest-sr") }
    }
}
