package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
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
 * Compose behaviour for the Study polish pass: a new card must arrive at the
 * top with its clock already running, option rows must be real tap targets,
 * and "Take a break" must pause the sitting without ending it. CI/emulator
 * only; the pure and Robolectric suites cover the logic on the host.
 */
@RunWith(AndroidJUnit4::class)
class StudyPolishUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository
    private val seeded = mutableListOf<String>()

    private fun question(id: String, categoryId: String, long: Boolean = false) = Question(
        id = id,
        categoryId = categoryId,
        text = "UITest $id?",
        options = if (long) {
            (1..10).map { QuestionOption("$id-$it", "Option $it") }
        } else {
            listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta"))
        },
        correctOptionIds = setOf(if (long) "$id-1" else "$id-a")
    )

    private fun seedPaper(id: String, vararg questions: Question) = runBlocking {
        repository.ensurePaperAndCategory(id, "UIPolish $id", "$id-cat", "Cat")
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
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The variable clock line, read from the node that actually renders text.
     * The tag sits on a Text inside a merged, clickable parent, so the unmerged
     * tree is required; taking the first node blindly could read a stale or
     * blank stand-in, which is why the first *text-bearing* node wins.
     */
    private fun clockStateText(): String? =
        compose.onAllNodesWithTag("study-clock-state", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .joinToString("") { it.text }
                    .takeIf { it.isNotBlank() }
            }
            .firstOrNull()

    /**
     * Running means the clock offers "Pause" and carries no "Paused" label.
     * Both words are the composable's own resources, so a translation or a
     * renamed verb cannot make this silently true.
     */
    /** Every rendered dwell value; at least one must advance while running. */
    private fun clockTimeTexts(): Set<String> =
        compose.onAllNodesWithTag("study-clock-time", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .joinToString("") { it.text }
                    .takeIf { it.isNotBlank() }
            }
            .toSet()

    private fun waitForRunningClock() {
        compose.waitForIdle()
        val start = clockTimeTexts()
        compose.waitUntil(5_000) { clockTimeTexts() != start }
    }

    /**
     * The body used to keep its scroll offset across cards. A long card was
     * scrolled to its answer, so the next card arrived already scrolled past —
     * and the stale "not visible" report left its clock paused. Both symptoms
     * are checked: the new stem is on screen without scrolling, and the clock
     * offers Pause rather than Resume.
     */
    @Test
    fun theNextCardArrivesAtTheTopWithItsClockRunning() {
        runBlocking { repository.setSimplifiedStudy(true) }
        seedPaper(
            "ui-polish-scroll",
            question("ui-polish-scroll-q1", "ui-polish-scroll-cat", long = true),
            question("ui-polish-scroll-q2", "ui-polish-scroll-cat")
        )
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-study-ui-polish-scroll").performClick()
        waitFor("UITest ui-polish-scroll-q1?")

        // Scroll the long card fully past the viewport, then reveal to make the
        // answer the thing on screen when Next is tapped (the fixed-footer bug).
        compose.onNodeWithText("Option 10", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("study-check").performClick()
        waitForTag("study-question-scroll")
        repeat(4) {
            compose.onNodeWithTag("study-question-scroll").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("study-next").performClick()

        waitFor("UITest ui-polish-scroll-q2?")
        // No performScrollTo: if the old offset survived, this node is composed
        // but off screen.
        compose.onNodeWithText("UITest ui-polish-scroll-q2?", substring = true).assertIsDisplayed()
        // A running clock changes its own text; a paused one stays frozen.
        waitForRunningClock()
    }

    @Test
    fun optionRowsAreAtLeast48Dp() {
        runBlocking { repository.setSimplifiedStudy(true) }
        seedPaper("ui-polish-height", question("ui-polish-height-q1", "ui-polish-height-cat"))
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-study-ui-polish-height").performClick()
        waitForTag("study-option-ui-polish-height-q1-a")
        compose.onNodeWithTag("study-option-ui-polish-height-q1-a", useUnmergedTree = true)
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("study-option-ui-polish-height-q1-b", useUnmergedTree = true)
            .assertHeightIsAtLeast(48.dp)
    }

    /**
     * Taking the break banks the clocks and offers Resume; it must NOT open the
     * summary or drop the card. Dismiss beside it stays only a suggestion
     * silencer, so both buttons remain reachable at the ten-review marker.
     *
     * Twelve cards, not eleven: the counter assertion below reads the header,
     * and grading the LAST card ends the sitting, replacing the header with the
     * summary ("11 reviewed · …"). The eleventh review really was written —
     * the log says so — but its "Reviewed 11 this session" line was never on
     * screen to be found, which made the old test fail for the fixture, not the
     * break. With a twelfth card waiting, the sitting stays open and the
     * counter is what the learner sees.
     */
    @Test
    fun takeABreakPausesTheSittingWithoutEndingIt() {
        runBlocking { repository.setSimplifiedStudy(true) }
        seedPaper(
            "ui-polish-break",
            *(1..12).map { question("ui-polish-break-q$it", "ui-polish-break-cat") }.toTypedArray()
        )
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-study-ui-polish-break").performClick()

        repeat(10) { n ->
            waitFor("UITest ui-polish-break-q${n + 1}?")
            compose.onNodeWithText("Alpha", substring = false).performClick()
            compose.onNodeWithTag("study-check").performClick()
            compose.onNodeWithTag("study-next").performClick()
            waitFor("Reviewed ${n + 1} this session")
        }

        // A plural must go through getQuantityString; getString resolves the
        // raw array resource and throws Resources$NotFoundException on device.
        waitFor(app.resources.getQuantityString(R.plurals.break_suggestion, 10, 10))
        compose.onNodeWithTag("study-take-break").performClick()
        waitForTag("study-resume-break")
        compose.onNodeWithTag("study-resume-break").assertIsDisplayed()
        compose.onNodeWithTag("study-on-break").assertIsDisplayed()
        // Still in the session, with the next card waiting.
        compose.onNodeWithTag("study-done").assertIsDisplayed()
        waitFor("UITest ui-polish-break-q11?")

        compose.onNodeWithTag("study-resume-break").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("study-resume-break").fetchSemanticsNodes().isEmpty()
        }
        // Answering still works after the resume: Check stages the simplified
        // grade, and Next persists it and pulls the eleventh review. A twelfth
        // card is still waiting, so the sitting stays open and the header's
        // counter is what proves the eleventh review landed.
        compose.onNodeWithText("Alpha", substring = false).performClick()
        compose.onNodeWithTag("study-check").performClick()
        compose.onNodeWithTag("study-next").performClick()
        waitFor("Reviewed 11 this session")
        waitFor("UITest ui-polish-break-q12?")
    }
}
