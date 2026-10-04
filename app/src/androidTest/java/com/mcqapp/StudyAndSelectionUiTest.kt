package com.mcqapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose behaviour for the screens changed recently, on the CI emulator.
 *
 * Everything else in the app is covered by JVM unit tests, but these pieces
 * live in composables and so are only reachable here. Each one had already
 * broken once in a way no unit test could see: a badge that only refreshed
 * when it was tapped, a dialog that opened already refusing to start, an
 * option marker that lied about how many answers a question takes.
 */
@RunWith(AndroidJUnit4::class)
class StudyAndSelectionUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    private fun single(id: String, categoryId: String) = Question(
        id = id,
        categoryId = categoryId,
        text = "UITest $id?",
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = setOf("$id-a")
    )

    private fun multi(id: String, categoryId: String) = Question(
        id = id,
        categoryId = categoryId,
        text = "UITest $id multi?",
        options = listOf(
            QuestionOption("$id-a", "Alpha"),
            QuestionOption("$id-b", "Beta"),
            QuestionOption("$id-c", "Gamma")
        ),
        // More than one right answer is what makes the option marker a square.
        correctOptionIds = setOf("$id-a", "$id-c")
    )

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory("ui-sel", "UISel Paper", "ui-sel-cat", "UISel Cat")
        repository.saveQuestion(single("ui-sel-q1", "ui-sel-cat"))
        repository.saveQuestion(single("ui-sel-q2", "ui-sel-cat"))

        repository.ensurePaperAndCategory("ui-multi", "UIMulti Paper", "ui-multi-cat", "UIMulti Cat")
        repository.saveQuestion(multi("ui-multi-q1", "ui-multi-cat"))

        // A study paper whose first card is single-answer and second is not.
        repository.ensurePaperAndCategory("ui-study", "UIStudy Paper", "ui-study-cat", "UIStudy Cat")
        repository.saveQuestion(single("ui-study-q1", "ui-study-cat"))
        repository.saveQuestion(multi("ui-study-q2", "ui-study-cat"))
    }

    @After
    fun cleanup() = runBlocking {
        listOf("ui-sel", "ui-multi", "ui-study").forEach { repository.deletePaper(it) }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * A multi-correct question is marked with a square, a single-answer one
     * with a circle — mirroring the checkbox and radio button the test screen
     * draws. Study genuinely allows several picks, so the marker has to say so.
     */
    @Test
    fun studyMarksMultiCorrectOptionsWithASquare() {
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-study").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-study").performClick()

        waitFor("Show answer")
        // First card is single-answer: a hollow circle.
        compose.onNodeWithText("○", substring = false).assertIsDisplayed()

        compose.onNodeWithText("Alpha", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Show answer", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Good", substring = false).performScrollTo().performClick()

        // Second card takes several answers, so its markers are squares.
        waitFor("Show answer")
        compose.onNodeWithText("▢", substring = false).assertIsDisplayed()
    }

    /**
     * A drill larger than the paper cannot be run, and the dialog says why
     * instead of quietly handing back a smaller drill.
     */
    @Test
    fun aDrillBiggerThanThePaperIsRefused() {
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-sel").assertIsDisplayed()
        compose.onNodeWithTag("paper-drill-ui-sel").performClick()

        waitFor("Quick drill")
        compose.onNodeWithText("Questions", substring = false).performScrollTo()
        // The default is 10 on a paper that holds 2.
        compose.onNodeWithText("This paper has only 2 questions. Enter 2 or fewer.", substring = false)
            .assertIsDisplayed()
        // Start stays disabled while the count is impossible.
        compose.onNodeWithText("Start drill", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("Cancel", substring = false).performClick()
    }

    /**
     * The select-mode row offers Export alongside Edit, Move and Delete.
     */
    @Test
    fun selectModeOffersExport() {
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-sel").assertIsDisplayed()
        compose.onNodeWithTag("paper-browse-ui-sel").performClick()

        waitFor("UITest ui-sel-q1?")
        compose.onNodeWithText("Select", substring = false).performClick()
        compose.onNodeWithText("Export", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Edit", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Move", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Delete", substring = false).assertIsDisplayed()
    }

    /**
     * Deleting a paper asks first and says what else goes, instead of taking
     * the history and bookmarks with a single tap.
     */
    @Test
    fun deletingAPaperConfirmsAndReportsTheLoss() {
        runBlocking {
            repository.toggleBookmark("ui-sel-q1")
            repository.saveAttempt(
                paperId = "ui-sel",
                paperTitle = "UISel Paper",
                questions = repository.getQuestionsForPaper("ui-sel"),
                selections = mapOf("ui-sel-q1" to setOf("ui-sel-q1-a")),
                negativeMarking = 0.0,
                durationSeconds = 10,
                finishedAt = System.currentTimeMillis()
            )
        }
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-sel").assertIsDisplayed()
        compose.onNodeWithTag("paper-delete-ui-sel").performClick()

        waitFor("This cannot be undone.")
        compose.onNodeWithText("This also deletes:", substring = false).assertIsDisplayed()
        compose.onNodeWithText("• 1 past attempt", substring = false).assertIsDisplayed()
        compose.onNodeWithText("• 1 bookmark", substring = false).assertIsDisplayed()
        // Cancelling must leave the paper alone.
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertTrue(runBlocking { repository.getPaper("ui-sel") != null })
    }
}