package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.navigation.McqNavHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A multiple-choice question with options but **no answer key** (an ungraded
 * import) on the CI emulator, in both footer modes: it must behave like a card
 * for grading purposes — Show answer, four self-grades, no Check, no guess —
 * while keeping the multiple-choice presentation (an Explanation heading and no
 * verdict tint or wording on the options).
 */
@RunWith(AndroidJUnit4::class)
class KeylessQuestionStudyUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository

    private val seeded = mutableListOf<String>()

    /** Options present, key empty: the app cannot know the answer. */
    private fun keyless(id: String, categoryId: String) = Question(
        id = id,
        categoryId = categoryId,
        elements = listOf(ContentElement.TextElement("Prompt $id")),
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = emptySet(),
        explanationElements = listOf(ContentElement.TextElement("Because $id"))
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

    /** The reveal must show an explanation and no verdict anywhere. */
    private fun assertNoVerdictOnReveal(optionId: String) {
        // A keyless question is not a card: its back keeps the Explanation
        // heading, never the card's "Answer".
        compose.onNodeWithText(app.getString(R.string.explanation), substring = false)
            .assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.r2_study_answer_heading), substring = false)
            .assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.incorrect), substring = false)
            .assertDoesNotExist()
        // No verdict glyphs at all: the ticks and crosses belong to a known key.
        compose.onAllNodesWithText("✓", substring = false).assertCountEquals(0)
        compose.onAllNodesWithText("✗", substring = false).assertCountEquals(0)
        // The selected option is announced as selected, never as wrong.
        val selected = compose.onNodeWithTag("study-option-$optionId", useUnmergedTree = true)
            .fetchSemanticsNode()
        val spoken = selected.config[SemanticsProperties.StateDescription]
        assertEquals(app.getString(R.string.r2_study_option_selected), spoken)
        assertFalse(
            "a keyless reveal must not claim the answer was wrong: $spoken",
            spoken.contains(app.getString(R.string.r2_study_option_selected_wrong))
        )
        // The result card belongs to a question the app could grade.
        compose.onNodeWithTag("study-result").assertDoesNotExist()
    }

    private fun assertFourGradesAndAdvance(nextPrompt: String) {
        listOf("Again", "Hard", "Good", "Easy").forEach { grade ->
            compose.onNodeWithTag("study-grade-$grade")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("study-grade-Good").performClick()
        waitFor(nextPrompt)
        compose.onNodeWithText(app.getString(R.string.incorrect), substring = false)
            .assertDoesNotExist()
    }

    @Test
    fun aKeylessQuestionIsSelfGradedInSimplifiedMode() {
        seedPaper(
            "ui-nokey", "UINoKey Paper",
            keyless("ui-nokey-1", "ui-nokey-cat"),
            keyless("ui-nokey-2", "ui-nokey-cat")
        )
        runBlocking { repository.setSimplifiedStudy(true) }
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-nokey").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-nokey").performClick()
        waitFor("Prompt ui-nokey-1")

        // No Check and no guess switch: the app cannot grade this question.
        waitForTag("study-show-answer")
        compose.onNodeWithTag("study-check").assertDoesNotExist()
        compose.onNodeWithTag("study-guess").assertDoesNotExist()

        // A selection is still possible before the reveal, as for any MCQ.
        compose.onNodeWithText("Alpha", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("study-show-answer").performClick()
        compose.waitForIdle()

        assertNoVerdictOnReveal("ui-nokey-1-a")
        // One prompt, one answer: no Next and no change-grade step.
        compose.onNodeWithTag("study-next").assertDoesNotExist()
        assertFourGradesAndAdvance("Prompt ui-nokey-2")
    }

    /**
     * The classic footer already leads with Show answer for every question; the
     * point here is that a keyless one still gets the four grades and no Check,
     * and that the grade advances.
     */
    @Test
    fun aKeylessQuestionIsSelfGradedInClassicMode() {
        seedPaper(
            "ui-nokey-classic", "UINoKeyClassic Paper",
            keyless("ui-nokey-classic-1", "ui-nokey-classic-cat"),
            keyless("ui-nokey-classic-2", "ui-nokey-classic-cat")
        )
        runBlocking { repository.setSimplifiedStudy(false) }
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-nokey-classic").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-nokey-classic").performClick()
        waitFor("Prompt ui-nokey-classic-1")

        // Classic mode has no Check for anything, and no guess switch here.
        compose.onNodeWithTag("study-check").assertDoesNotExist()
        compose.onNodeWithTag("study-guess").assertDoesNotExist()
        val showAnswer = app.getString(R.string.show_answer)
        compose.onNodeWithText(showAnswer, substring = false).assertIsDisplayed()
        compose.onNodeWithText(showAnswer, substring = false).performClick()
        compose.waitForIdle()

        assertNoVerdictOnReveal("ui-nokey-classic-1-a")
        assertFourGradesAndAdvance("Prompt ui-nokey-classic-2")
    }
}
