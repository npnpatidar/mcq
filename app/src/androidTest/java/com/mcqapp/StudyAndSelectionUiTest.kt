package com.mcqapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.navigation.McqNavHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose behaviour for the screens changed recently, on the CI emulator.
 *
 * Everything else is covered by JVM unit tests; these pieces live in
 * composables and the glue around them, which no unit test can see. Each had
 * already failed once in a way the suite stayed green through: a badge that
 * only refreshed when it was tapped, a dialog that opened already refusing to
 * start, an option marker that lied about how many answers a question takes, an
 * unconfirmed delete.
 *
 * Each test seeds only the paper it needs. With one paper on screen its card is
 * at the top and needs no scrolling, which is what makes a tap land — several
 * earlier rounds failed on scroll semantics rather than on anything behavioural.
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
        // More than one right answer is what makes the marker a square.
        correctOptionIds = setOf("$id-a", "$id-c")
    )

    private val seeded = mutableListOf<String>()

    private fun seedPaper(id: String, title: String, categoryId: String, vararg questions: Question) =
        runBlocking {
            repository.ensurePaperAndCategory(id, title, categoryId, "$title Cat")
            questions.forEach { repository.saveQuestion(it) }
            seeded += id
        }

    @After
    fun cleanup() = runBlocking {
        seeded.forEach { repository.deletePaper(it) }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * A multi-correct question is marked with a square and a single-answer one
     * with a circle, mirroring the checkbox and radio button the test screen
     * draws. Study genuinely allows several picks, so the marker has to say so.
     */
    @Test
    fun studyMarksMultiCorrectOptionsWithASquare() {
        seedPaper(
            "ui-study", "UIStudy Paper", "ui-study-cat",
            single("ui-study-q1", "ui-study-cat"),
            multi("ui-study-q2", "ui-study-cat")
        )
        compose.setContent { McqNavHost(repository = repository) }

        // Never-studied cards count as new rather than due.
        compose.onNodeWithTag("paper-title-ui-study").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-study").performClick()

        waitFor("Show answer")
        // First card is single-answer: hollow circles, one per option.
        assertTrue(
            "a single-answer question should be marked with circles",
            compose.onAllNodesWithText("○", substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        )

        compose.onNodeWithText("Alpha", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Show answer", substring = false).performScrollTo().performClick()

        // Each grade advertises the delay it would produce, as in Anki. The value
        // depends on the clock and the day boundary, so this only checks that all
        // four labels are rendered rather than pinning a particular interval.
        //
        // The unmerged tree is required: TestTag merges by keeping the PARENT's
        // value, so a tag on a Text inside a Button is invisible in the merged
        // tree — the Button has no tag of its own and wins with null.
        listOf("Again", "Hard", "Good", "Easy").forEach { grade ->
            assertTrue(
                "the $grade button should show a next-interval preview",
                compose.onAllNodesWithTag("grade-preview-$grade", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            )
        }

        compose.onNodeWithText("Good", substring = false).performScrollTo().performClick()

        // The second card takes several answers, so its markers are squares.
        waitFor("Show answer")
        assertTrue(
            "a multi-correct question should be marked with squares",
            compose.onAllNodesWithText("▢", substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    /**
     * A drill larger than the paper cannot be run, and the dialog says why
     * instead of quietly handing back a smaller drill.
     */
    @Test
    fun aDrillBiggerThanThePaperIsRefused() {
        seedPaper(
            "ui-sel", "UISel Paper", "ui-sel-cat",
            single("ui-sel-q1", "ui-sel-cat"),
            single("ui-sel-q2", "ui-sel-cat")
        )
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-sel").assertIsDisplayed()
        compose.onNodeWithTag("paper-drill-ui-sel").performClick()

        waitFor("Quick drill")

        // The default is what this paper can supply, so the dialog opens valid.
        // The whole line reads "2 random questions, 5:00 on the clock."
        compose.onNodeWithText("2 random questions", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Start drill", substring = false).assertIsEnabled()

        // Ask for more than it holds and it refuses, naming the real figure.
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("99")
        compose.onNodeWithText("This paper has only 2 questions. Enter 2 or fewer.", substring = false)
            .assertIsDisplayed()
        compose.onNodeWithText("Start drill", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("Cancel", substring = false).performClick()
    }

    /**
     * The select-mode row offers Export alongside Edit, Move and Delete.
     */
    @Test
    fun selectModeOffersExport() {
        seedPaper(
            "ui-sel", "UISel Paper", "ui-sel-cat",
            single("ui-sel-q1", "ui-sel-cat"),
            single("ui-sel-q2", "ui-sel-cat")
        )
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-sel").assertIsDisplayed()
        compose.onNodeWithTag("paper-browse-ui-sel").performClick()

        waitFor("Select")
        compose.onNodeWithText("Select", substring = false).performClick()
        compose.onNodeWithText("Export", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Edit", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Move", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Delete", substring = false).assertIsDisplayed()
    }

    /**
     * Deleting a paper asks first and says what else goes, instead of taking the
     * history and bookmarks with a single tap.
     */
    @Test
    fun deletingAPaperConfirmsAndReportsTheLoss() {
        seedPaper("ui-sel", "UISel Paper", "ui-sel-cat", single("ui-sel-q1", "ui-sel-cat"))
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
        // Delete lives inside the collapsed card, which expands by animation.
        compose.onNodeWithTag("paper-manage-ui-sel").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Delete paper", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Delete paper", substring = true).performClick()

        waitFor("This cannot be undone.")
        compose.onNodeWithText("This also deletes:", substring = false).assertIsDisplayed()
        compose.onNodeWithText("• 1 past attempt", substring = false).assertIsDisplayed()
        compose.onNodeWithText("• 1 bookmark", substring = false).assertIsDisplayed()
        // Cancelling must leave the paper alone.
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertTrue(runBlocking { repository.getPaper("ui-sel") != null })
    }
}