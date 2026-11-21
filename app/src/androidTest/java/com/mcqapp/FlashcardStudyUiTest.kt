package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.ui.navigation.McqNavHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A flashcard in Study, on the CI emulator: Show answer instead of Check, the
 * "Answer" heading with real heading semantics, the four self-grading buttons
 * in one row of tap targets, and no correctness claim anywhere.
 *
 * Kept in its own class so the Study copy/layout tests stay one subject each.
 */
@RunWith(AndroidJUnit4::class)
class FlashcardStudyUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository

    private val seeded = mutableListOf<String>()

    private fun flashcard(id: String, categoryId: String) = Question(
        id = id,
        categoryId = categoryId,
        elements = listOf(ContentElement.TextElement("Prompt $id")),
        options = emptyList(),
        correctOptionIds = emptySet(),
        explanationElements = listOf(ContentElement.TextElement("Answer $id"))
    )

    private fun seedPaper(id: String, title: String, vararg questions: Question) = runBlocking {
        repository.ensurePaperAndCategory(id, title, "$id-cat", "$title Cat")
        questions.forEach { repository.saveQuestion(it) }
        seeded += id
    }

    @After
    fun cleanup() = runBlocking {
        seeded.forEach { repository.deletePaper(it) }
        repository.resetSchedulerConfig()
        repository.setSimplifiedStudy(true)
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The card's own flow: no Check, no guess switch, Show answer, then the
     * revealed back labelled Answer with the four buttons. Grade buttons are
     * the 48 dp tap targets the learner actually hits.
     */
    @Test
    fun aCardIsRevealedAndSelfGraded() {
        seedPaper(
            "ui-card", "UICard Paper",
            flashcard("ui-card-1", "ui-card-cat"),
            flashcard("ui-card-2", "ui-card-cat")
        )
        runBlocking { repository.setSimplifiedStudy(true) }
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-card").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-card").performClick()
        waitFor("Prompt ui-card-1")

        // A card is studied, never checked: the MCQ controls are absent.
        waitForTag("study-show-answer")
        compose.onNodeWithTag("study-check").assertDoesNotExist()
        compose.onNodeWithTag("study-guess").assertDoesNotExist()
        // No answer is on screen before the reveal, so nothing claims a verdict.
        compose.onNodeWithText(app.getString(R.string.incorrect), substring = false)
            .assertDoesNotExist()

        compose.onNodeWithTag("study-show-answer").performClick()
        compose.waitForIdle()

        // The back is an answer, not an explanation, and screen readers get a
        // real heading to jump to.
        val heading = app.getString(R.string.r2_study_answer_heading)
        compose.onNode(
            hasText(heading) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        ).assertIsDisplayed()

        // The four self-grades are all there, all reachable, all 48 dp.
        listOf("Again", "Hard", "Good", "Easy").forEach { grade ->
            compose.onNodeWithTag("study-grade-$grade")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
        // One prompt, one answer: no result card and no Next step.
        compose.onNodeWithTag("study-result").assertDoesNotExist()
        compose.onNodeWithTag("study-next").assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.correct), substring = false)
            .assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.incorrect), substring = false)
            .assertDoesNotExist()

        // The clock keeps counting the card's reading time but makes no band
        // claim: the learner's buttons decide the grade, not the dwell.
        val clock = compose.onNodeWithTag("study-question-clock", useUnmergedTree = true)
            .fetchSemanticsNode()
        val clockState = clock.config[SemanticsProperties.StateDescription]
        assertTrue(
            "a card's clock must stay neutral, was: $clockState",
            clockState.contains(app.getString(R.string.r2_study_band_neutral))
        )

        // Tapping a grade persists it and advances straight to the next card.
        compose.onNodeWithTag("study-grade-Good").performClick()
        waitFor("Prompt ui-card-2")

        // Still no correctness language anywhere in the card flow.
        compose.onNodeWithText(app.getString(R.string.incorrect), substring = false)
            .assertDoesNotExist()
        assertFalse(
            "a card must never produce a result card",
            compose.onAllNodesWithTag("study-result").fetchSemanticsNodes().isNotEmpty()
        )
    }

    /**
     * The classic footer (Simplified off) gives a card the same path a
     * multiple-choice question takes: Show answer, then the four grades. The
     * write underneath is the card's self-graded row — pinned by the VM test —
     * so this checks the footer itself.
     */
    @Test
    fun aClassicCardIsRevealedAndSelfGraded() {
        seedPaper(
            "ui-card-classic", "UICardClassic Paper",
            flashcard("ui-card-classic-1", "ui-card-classic-cat"),
            flashcard("ui-card-classic-2", "ui-card-classic-cat")
        )
        runBlocking { repository.setSimplifiedStudy(false) }
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-card-classic").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-card-classic").performClick()
        waitFor("Prompt ui-card-classic-1")

        // Classic mode has no Check for any card, and no guess switch for one.
        compose.onNodeWithTag("study-check").assertDoesNotExist()
        compose.onNodeWithTag("study-guess").assertDoesNotExist()
        val showAnswer = app.getString(R.string.show_answer)
        compose.onNodeWithText(showAnswer, substring = false).assertIsDisplayed()
        compose.onNodeWithText(showAnswer, substring = false).performClick()
        compose.waitForIdle()

        // The back is labelled Answer with heading semantics, as in Simplified.
        val heading = app.getString(R.string.r2_study_answer_heading)
        compose.onNode(
            hasText(heading) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        ).assertIsDisplayed()

        listOf("Again", "Hard", "Good", "Easy").forEach { grade ->
            compose.onNodeWithTag("study-grade-$grade")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText(app.getString(R.string.incorrect), substring = false)
            .assertDoesNotExist()

        // The grade persists and advances without a Next step.
        compose.onNodeWithTag("study-grade-Good").performClick()
        waitFor("Prompt ui-card-classic-2")
    }
}
