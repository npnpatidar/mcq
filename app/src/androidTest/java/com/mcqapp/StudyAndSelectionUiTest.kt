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
import com.mcqapp.domain.ReviewGrade
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
        // The mode tests below mutate the shared scheduler settings; leaving
        // them as found keeps test order irrelevant.
        repository.resetSchedulerConfig()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun studyHeaderStaysVisibleWhenTheQuestionScrolls() {
        seedPaper(
            "ui-study-sticky", "UIStudy Sticky", "ui-study-sticky-cat",
            Question(
                id = "ui-study-sticky-q",
                categoryId = "ui-study-sticky-cat",
                text = "Long question ${"very long ".repeat(30)}",
                options = (1..10).map { index ->
                    QuestionOption("ui-study-sticky-q-$index", "Option $index")
                },
                correctOptionIds = setOf("ui-study-sticky-q-1")
            )
        )
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-study-sticky").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-study-sticky").performClick()

        waitFor("Check")
        compose.onNodeWithText("Option 10", substring = false).performScrollTo()

        compose.onNodeWithTag("study-paper-title").assertIsDisplayed()
        compose.onNodeWithTag("study-done").assertIsDisplayed()
        compose.onNodeWithTag("study-question-clock").assertIsDisplayed()
    }

    @Test
    fun tappingTheStudyQuestionClockPausesIt() {
        seedPaper(
            "ui-study-clock", "UIStudy Clock", "ui-study-clock-cat",
            single("ui-study-clock-q", "ui-study-clock-cat")
        )
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-study-ui-study-clock").performClick()
        waitFor("Check")

        compose.onNodeWithTag("study-question-clock").performClick()
        compose.onNodeWithText("Paused", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingTheTestQuestionClockPausesIt() {
        seedPaper(
            "ui-test-clock", "UITest Clock", "ui-test-clock-cat",
            single("ui-test-clock-q", "ui-test-clock-cat")
        )
        compose.setContent { McqNavHost(repository = repository) }

        // By tag, not by the word: every paper card has a Test button, and these
        // tests share the app database with the other instrumented classes, so
        // matching on "Test" alone finds however many papers happen to be left
        // on the library screen — which made this tap order-dependent.
        compose.onNodeWithTag("paper-test-ui-test-clock").performClick()
        waitFor("Question 1 of")

        compose.onNodeWithTag("test-question-clock").performClick()
        compose.onNodeWithText("Paused", substring = true).assertIsDisplayed()
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
        // This test drives the manual four-button flow, so pin it: the default
        // is now Simplified, whose button says Check, not Show answer.
        runBlocking { repository.setSimplifiedStudy(false) }
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
        // Show answer lives in the fixed footer now.
        compose.onNodeWithText("Show answer", substring = false).performClick()

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

        // The grade buttons live in the fixed footer now.
        compose.onNodeWithText("Good", substring = false).performClick()

        // The second card takes several answers, so its markers are squares.
        waitFor("Show answer")
        assertTrue(
            "a multi-correct question should be marked with squares",
            compose.onAllNodesWithText("▢", substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    /**
     * Simplified mode (the default): answer, Check, see the result, Next — no
     * grade buttons. Pins the flag on because the manual tests pin it off and
     * DataStore persists across tests, so execution order must not matter.
     */
    @Test
    fun simplifiedStudyGradesFromTheAnswer() {
        seedPaper(
            "ui-simple", "UISimple Paper", "ui-simple-cat",
            single("ui-simple-q1", "ui-simple-cat")
        )
        runBlocking { repository.setSimplifiedStudy(true) }
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-simple").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-simple").performClick()

        waitFor("UITest ui-simple-q1?")
        // Alpha is the right answer, so Check must report Correct whatever the
        // dwell ladder says about the grade.
        compose.onNodeWithText("Alpha", substring = false).performScrollTo().performClick()
        // Check lives in the fixed footer, so no scroll is needed or possible.
        compose.onNodeWithTag("study-check").performClick()

        compose.onNodeWithTag("study-result").performScrollTo().assertIsDisplayed()
        // The label carries a tick prefix ("✓ Correct"), so this is a substring
        // match. It cannot collide with "Incorrect", which has a lowercase c.
        compose.onNodeWithText("Correct", substring = true).assertIsDisplayed()

        // A single-question session finishes on Next, also in the footer.
        compose.onNodeWithTag("study-next").performClick()
        waitFor("Session complete")
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
        // The expanded card's actions can sit below the fold on the small
        // emulator screen, and the expand animation is still moving them:
        // settle first, then scroll the button into view, then click.
        compose.waitForIdle()
        compose.onNodeWithText("Delete paper", substring = true).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Delete paper", substring = true).performClick()

        waitFor("This cannot be undone.")
        compose.onNodeWithText("This also deletes:", substring = false).assertIsDisplayed()
        compose.onNodeWithText("• 1 past attempt", substring = false).assertIsDisplayed()
        compose.onNodeWithText("• 1 bookmark", substring = false).assertIsDisplayed()
        // Cancelling must leave the paper alone.
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertTrue(runBlocking { repository.getPaper("ui-sel") != null })
    }

    // ---- the pull-based session surface ----

    /**
     * Each chip is rendered for exactly the pool that served the card: a due
     * relearning step says "Learning step", an overdue review whose predicted
     * recall has sunk says "Likely to be forgotten soon", and unseen material
     * says "New question". A chip that described the wrong pool (or never
     * rendered) would send the learner hunting for meaning that is not there.
     */
    @Test
    fun eachPulledCardNamesThePoolThatServedIt() {
        runBlocking { repository.setSimplifiedStudy(true) }
        seedPaper(
            "ui-chips", "UIChips Paper", "ui-chips-cat",
            single("ui-chips-learn", "ui-chips-cat"),
            single("ui-chips-low", "ui-chips-cat"),
            single("ui-chips-new", "ui-chips-cat")
        )
        val minute = 60_000L
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        runBlocking {
            // A card that failed 15 minutes ago is inside its 10-minute
            // relearning step, now overdue — the learning pool's card.
            repository.recordStudyReview(
                "ui-chips", "ui-chips-learn", ReviewGrade.AGAIN,
                now = now - 15 * minute
            )
            // A card graduated from a fresh one 90 days ago: its interval was
            // ~2 days, so its predicted recall is far into the floor — the
            // most-at-risk review.
            repository.recordStudyReview(
                "ui-chips", "ui-chips-low", ReviewGrade.GOOD,
                now = now - 90 * day
            )
        }
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-title-ui-chips").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-chips").performClick()

        // The learning step outranks the rest of the backlog.
        waitFor("Learning step")
        compose.onNodeWithText("Alpha", substring = false).performClick()
        compose.onNodeWithTag("study-check").performClick()
        compose.onNodeWithTag("study-next").performClick()

        waitFor("Likely to be forgotten soon")
        compose.onNodeWithText("Alpha", substring = false).performClick()
        compose.onNodeWithTag("study-check").performClick()
        compose.onNodeWithTag("study-next").performClick()

        waitFor("New question")
        compose.onNodeWithText("Alpha", substring = false).performClick()
        compose.onNodeWithTag("study-check").performClick()
        compose.onNodeWithTag("study-next").performClick()

        // The third card was the last: the header with its counter goes with
        // the session, so the summary's own tally is what the screen shows now.
        waitFor("3 reviewed")
    }

    /**
     * A voluntary stop is offered from the first card, ends the session on
     * the summary with the real tally, and never badgers the learner to
     * continue.
     */
    @Test
    fun stoppingShowsTheSessionSummaryWithoutDemandingMore() {
        runBlocking { repository.setSimplifiedStudy(true) }
        seedPaper(
            "ui-stop", "UIStop Paper", "ui-stop-cat",
            single("ui-stop-q1", "ui-stop-cat"),
            single("ui-stop-q2", "ui-stop-cat")
        )
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-title-ui-stop").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-stop").performClick()

        // Offered before anything has been graded: a stop is always reachable.
        waitFor("Done for now")
        compose.onNodeWithText("Alpha", substring = false).performClick()
        compose.onNodeWithTag("study-check").performClick()
        compose.onNodeWithTag("study-next").performClick()
        waitFor("UITest ui-stop-q2?")

        compose.onNodeWithText("Done for now", substring = false).performClick()
        waitFor("Session complete")
        compose.onNodeWithText("1 reviewed • 0 again • 1 remembered", substring = false)
            .assertIsDisplayed()
        // Done means done: the stop affordance is gone, nothing asks to go on.
        assertTrue(
            "the session kept asking the learner to continue",
            compose.onAllNodesWithText("Done for now", substring = false)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    /**
     * The break strip suggests at the ten-review marker, dismisses without
     * a fight, and a dismissal never blocks the pull: the next card arrives
     * as though the strip had never shown.
     */
    @Test
    fun theBreakStripSuggestsThenYieldsWithoutBlockingTheNextCard() {
        runBlocking { repository.setSimplifiedStudy(true) }
        seedPaper(
            "ui-break", "UIBreak Paper", "ui-break-cat",
            *(1..12).map { single("ui-break-q$it", "ui-break-cat") }.toTypedArray()
        )
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-title-ui-break").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-break").performClick()

        repeat(10) { n ->
            waitFor("UITest ui-break-q${n + 1}?")
            compose.onNodeWithText("Alpha", substring = false).performClick()
            compose.onNodeWithTag("study-check").performClick()
            compose.onNodeWithTag("study-next").performClick()
            waitFor("Reviewed ${n + 1} this session")
        }

        waitFor("a good moment for a break")
        compose.onNodeWithText("Dismiss", substring = false).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("a good moment", substring = true)
                .fetchSemanticsNodes().isEmpty()
        }

        // The pull keeps working through the dismissal: card 11 is served,
        // reviewed, and the counter moves without the strip returning (the
        // dismissal holds until the next milestone).
        waitFor("UITest ui-break-q11?")
        assertTrue(
            "the dismissed strip came back too early",
            compose.onAllNodesWithText("a good moment", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
        compose.onNodeWithText("Alpha", substring = false).performClick()
        compose.onNodeWithTag("study-check").performClick()
        compose.onNodeWithTag("study-next").performClick()
        waitFor("Reviewed 11 this session")
    }

    /**
     * The session's only progress indicator is what has been done: an
     * incrementing count with no denominator. The fixed "index/queue" bar of
     * the pre-pull UI must not have crept back.
     */
    @Test
    fun theSessionCounterCountsUpWithoutADenominator() {
        runBlocking { repository.setSimplifiedStudy(true) }
        seedPaper(
            "ui-count", "UICount Paper", "ui-count-cat",
            single("ui-count-q1", "ui-count-cat"),
            single("ui-count-q2", "ui-count-cat")
        )
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-title-ui-count").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-count").performClick()

        waitFor("Reviewed 0 this session")
        compose.onNodeWithText("Alpha", substring = false).performClick()
        compose.onNodeWithTag("study-check").performClick()
        compose.onNodeWithTag("study-next").performClick()
        waitFor("Reviewed 1 this session")
        // The old fixed-queue progress bar rendered "done/total". With the
        // pull-based counter there is no total to show.
        assertTrue(
            "a fixed-queue progress bar reappeared",
            compose.onAllNodesWithText("1/2", substring = false)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    /**
     * With nothing due and new cards switched off, the empty state offers the
     * flip right there instead of sending the learner through settings, and
     * the offer starts a real session.
     */
    @Test
    fun anEmptyReviewOnlySessionOffersToTurnNewCardsOn() {
        runBlocking { repository.setSimplifiedStudy(true) }
        seedPaper("ui-empty", "UIEmpty Paper", "ui-empty-cat", single("ui-empty-q1", "ui-empty-cat"))
        runBlocking {
            repository.setSchedulerConfig(
                repository.schedulerConfigNow().copy(introduceNewCards = false)
            )
        }
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-title-ui-empty").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-empty").performClick()

        waitFor("New cards are switched off")
        compose.onNodeWithText("Introduce new cards", substring = false).performClick()
        waitFor("UITest ui-empty-q1?")
        // The offer is not a one-screen gesture: the toggle behind it persists.
        assertTrue(runBlocking { repository.schedulerConfigNow().introduceNewCards })
    }
}