package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.navigation.McqNavHost
import kotlin.math.abs
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
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Locale-proof copy assertions: resolve the same resources the composables
    // render instead of pinning English text, as ImportUiTest already does.
    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication

    private val repository
        get() = app.repository

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

    /**
     * A Study question whose revealed back is [explanationElements] (plus an
     * optional scalar image): the text fields are empty on purpose, so the
     * flattened `explanation` cannot be what makes the back visible.
     */
    private fun explanationQuestion(
        id: String,
        categoryId: String,
        explanationElements: List<ContentElement>,
        explanationImage: String? = null
    ) = Question(
        id = id,
        categoryId = categoryId,
        elements = listOf(ContentElement.TextElement("UITest $id?")),
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = setOf("$id-a"),
        explanationElements = explanationElements,
        explanationImage = explanationImage
    )

    /**
     * A real 1x1 PNG. [com.mcqapp.util.QuestionImage] only tags its slot once
     * the data URI decodes, so a placeholder base64 string would never appear.
     */
    private val tinyPng =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

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
        // The mode tests mutate the shared scheduler settings and the process-
        // wide study mode; restoring both keeps test order irrelevant. The
        // reveal tests pin Simplified off, so leaving it would make a later
        // Simplified test wait forever for its Check button.
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
     * Waits for a tag that exists only in the unmerged tree: the clock lines are
     * Text children under a parent that declares its own contentDescription and
     * stateDescription, which hides them from the merged lookup [waitForTag]
     * uses. Kept separate so every existing wait stays on the merged tree.
     */
    private fun waitForUnmergedTag(tag: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Opens [paperId] in Study and presses the classic Show-answer button. */
    private fun revealStudyAnswer(paperId: String) {
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-study-$paperId").performClick()
        waitFor("Show answer")
        compose.onNodeWithText("Show answer", substring = false).performClick()
        compose.waitForIdle()
    }

    /**
     * Reads the variable clock line from the unmerged tree, or null before it
     * renders. Its test tag sits on a Text inside the clickable clock, so it is
     * invisible to the merged lookup; the unmerged tree is required.
     */
    private fun variableClockText(): String? {
        val nodes = compose.onAllNodesWithTag("study-clock-state", useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        return nodes.first().config
            .getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString("") { it.text }
    }

    /**
     * Brings the study clock to [paused], reading its live state first and only
     * tapping when a toggle is actually needed. The clock starts paused and is
     * also flipped to running when the card enters the viewport, so its initial
     * state is racy — a blind click can do the opposite of what a test expects.
     * Paused is detected from the localized Resume verb / Paused label the
     * variable line renders, using the same resources the composable uses.
     */
    private fun setStudyClock(paused: Boolean) {
        val resumeVerb = app.getString(R.string.r2_study_resume)
        val pausedLabel = app.getString(R.string.clock_paused)
        fun isPaused(text: String) = text.contains(resumeVerb) || text.contains(pausedLabel)

        waitForUnmergedTag("study-clock-state")
        if (isPaused(variableClockText().orEmpty()) == paused) return

        compose.onNodeWithTag("study-question-clock").performClick()
        compose.waitUntil(10_000) {
            val text = variableClockText()
            text != null && isPaused(text) == paused
        }
    }

    /** Dispatches the system back gesture to the activity hosting the content. */
    private fun pressSystemBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /**
     * Every Test tap lands on the setup sheet, whose defaults are a random
     * sample of the whole paper with the suggested time. Confirming starts the
     * session with those defaults.
     */
    private fun confirmTestStart() {
        waitFor("Start a test")
        waitForTag("test-setup-start")
        // The sheet is a scrollable column and Start sits at its bottom; the
        // type chips alone can push it past the fold on the emulator. Scroll
        // it into view, or the click lands off-screen and is swallowed.
        compose.onNodeWithTag("test-setup-start").performScrollTo().performClick()
    }

    /** Scrolls the drawer's list until [tag] is on screen, and returns it. */
    private fun drawerNode(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    /**
     * Drawer rows below the fold are not composed at all — the list is a
     * LazyColumn — so a plain [waitForTag] never sees them. The extra navigation
     * rows (including AI schemas) pushed the paper rows down; swipe the list in
     * bounded steps until the row exists, then let [drawerNode] bring it fully
     * into view. This keeps the test's selection assertions untouched.
     */
    private fun waitForDrawerRow(tag: String) {
        repeat(16) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            compose.onNodeWithTag("drawer-list").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        waitForTag(tag)
    }

    /** Opens the drawer and scrolls [paperId]'s row into view. */
    private fun openDrawer(paperId: String) {
        compose.onNodeWithContentDescription("Open menu").performClick()
        waitForDrawerRow("drawer-paper-$paperId")
        drawerNode("drawer-paper-$paperId")
    }

    /**
     * A paper whose tree is `parent`, with children `a` and `b`, one question
     * in each child. Ticking the parent alone must reach both.
     */
    private fun seedNestedPaper(paperId: String, title: String) = runBlocking {
        repository.ensurePaperAndCategory(paperId, title, "$paperId-p", "Parent")
        for ((index, child) in listOf("a", "b").withIndex()) {
            val childId = "$paperId-c$child"
            repository.db().categoryDao().upsert(
                CategoryEntity(
                    id = childId,
                    paperId = paperId,
                    title = "Child ${child.uppercase()}",
                    parentId = "$paperId-p",
                    sortOrder = index
                )
            )
            repository.saveQuestion(single("$paperId-q$child", childId))
        }
        seeded += paperId
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

    /** Starts a one-question sitting and waits for both clock lines to render. */
    private fun startClockStudy(paperId: String) {
        seedPaper(paperId, "UIStudy Clock", "$paperId-cat", single("$paperId-q", "$paperId-cat"))
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-study-$paperId").performClick()
        waitFor("Check")
        // Bring the question card fully into the viewport before touching the
        // clock. StudyScreen sets questionInView from the card's position, and
        // resumeQuestionClock() refuses to start when it is false — so a clock
        // tap while the card is off-screen is a legitimate no-op.
        compose.onNodeWithTag("study-option-$paperId-q-a").performScrollTo()
        compose.waitForIdle()
        waitForUnmergedTag("study-clock-time")
        waitForUnmergedTag("study-clock-state")
    }

    /**
     * The clock is two lines: a permanent one (timer icon + live M:SS time) and
     * a variable one (Pause/Resume verb, dwell band, optional Paused). Both must
     * render so the header keeps one height as the clock flips.
     */
    @Test
    fun studyClockRendersTimeAndStateOnSeparateLines() {
        runBlocking { repository.setSimplifiedStudy(true) }
        startClockStudy("ui-clock-lines")

        val time = compose.onNodeWithTag("study-clock-time", useUnmergedTree = true)
        val state = compose.onNodeWithTag("study-clock-state", useUnmergedTree = true)
        time.assertIsDisplayed()
        state.assertIsDisplayed()
        // The icon carries no text; the clock's content description proves the
        // permanent line still has its timer alongside the time.
        compose.onNodeWithContentDescription(app.getString(R.string.question_clock))
            .assertIsDisplayed()

        time.assertTextContains(":", substring = true)
        val pauseVerb = app.getString(R.string.r2_study_pause)
        val resumeVerb = app.getString(R.string.r2_study_resume)
        val pausedLabel = app.getString(R.string.clock_paused)
        val stateText = variableClockText().orEmpty()
        val showsPause = stateText.contains(pauseVerb)
        val showsResume = stateText.contains(resumeVerb)
        // A verb is always present — which one depends on the clock's live
        // state, which starts paused and can flip on viewport entry.
        assertTrue(
            "the variable clock line shows neither Pause nor Resume: $stateText",
            showsPause || showsResume
        )
        // The Paused suffix belongs to the paused state, not to a fixed initial
        // state: it is present exactly when the line offers Resume.
        assertTrue(
            "the Paused suffix did not match the clock state: $stateText",
            stateText.contains(pausedLabel) == showsResume
        )
        compose.onNodeWithTag("study-reviewed").assertIsDisplayed()
    }

    /**
     * The variable clock line and the session-minutes readout must sit on one
     * visual line. The header once stacked them — the clock's lines first,
     * minutes underneath — so "Pause · Neutral · Paused" and "N min" landed on
     * different baselines even though both describe the same session. The header
     * now renders two columns of two matching lines: tally over minutes on the
     * left, live time over verb/band on the right, all in labelMedium. This
     * measures that pairing. It fails on the stacked layout, where the clock
     * state sat a whole text line above the minutes and shared no vertical span
     * with it.
     */
    @Test
    fun variableClockLineSitsOnTheSessionMinutesLine() {
        runBlocking { repository.setSimplifiedStudy(true) }
        startClockStudy("ui-clock-align")

        waitForUnmergedTag("study-session-minutes")
        waitForUnmergedTag("study-clock-state")
        compose.waitForIdle()

        val minutesBounds = compose.onNodeWithTag("study-session-minutes", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        val stateBounds = compose.onNodeWithTag("study-clock-state", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        val timeBounds = compose.onNodeWithTag("study-clock-time", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        val reviewedBounds = compose.onNodeWithTag("study-reviewed", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow

        // Line 1 as well: the tally and the live time must share a line. The
        // icon makes the clock's first line 20 dp tall, and the tally reserves
        // the same height, so neither second line can drift.
        assertTrue(
            "the tally and the live time are on different lines: " +
                "$reviewedBounds / $timeBounds",
            reviewedBounds.top < timeBounds.bottom && timeBounds.top < reviewedBounds.bottom
        )

        // Same visual line: the two vertical spans overlap. A stacked layout
        // leaves them disjoint, because one starts where the other ended.
        assertTrue(
            "the clock state and the minutes line are on different lines: " +
                "$stateBounds / $minutesBounds",
            stateBounds.top < minutesBounds.bottom && minutesBounds.top < stateBounds.bottom
        )
        // Their centre lines must coincide too, not merely touch: the rows are
        // centre-aligned and use the same typography.
        val centreGap = abs(stateBounds.center.y - minutesBounds.center.y)
        val tolerance = maxOf(stateBounds.height, minutesBounds.height) / 2f
        assertTrue(
            "the clock state is off the minutes line's centre by ${centreGap}px " +
                "(tolerance ${tolerance}px): $stateBounds / $minutesBounds",
            centreGap <= tolerance
        )
        // ... and the variable line sits to the minutes line's right, as the
        // weighted minutes text leaves it the row's remaining space.
        assertTrue(
            "the clock state is not to the right of the minutes line: " +
                "$stateBounds / $minutesBounds",
            stateBounds.left >= minutesBounds.right
        )
        // Negative: alignment must not come from collapsing the header onto one
        // line. The live time stays on the line above the minutes, so its bottom
        // edge is at or above the minutes line's top edge — true for the two
        // clock lines at any font scale, false as soon as the time joins the
        // minutes line. The 0.5 px epsilon only absorbs float rounding where the
        // two text bounds can meet exactly (a large font scale makes each line as
        // tall as its text); a collapsed line would put them a full text height
        // apart.
        assertTrue(
            "the live time must stay on the row above the minutes: " +
                "$timeBounds / $minutesBounds",
            timeBounds.bottom <= minutesBounds.top + 0.5f &&
                timeBounds.top < minutesBounds.top
        )
        // The clock block enforces the 48 dp target with heightIn(min = 48.dp);
        // the Material touch-target helper is a no-op in this header and left
        // the node at its 36 dp content height. The block centres its two lines
        // inside those 48 dp, so the block measures 48 dp, the row stays compact,
        // and the 36 dp left column centres against it to keep both lines
        // aligned.
        compose.onNodeWithTag("study-question-clock", useUnmergedTree = true)
            .assertHeightIsAtLeast(48.dp)
    }

    /**
     * The variable line must faithfully reflect the clock's authoritative state,
     * while the permanent time line keeps its M:SS shape and the tally stays put.
     * A tap here may legitimately be a no-op on the emulator — resumeQuestionClock
     * is gated by the viewport/user-pause state, and the real toggle behaviour is
     * already covered by [tappingTheStudyQuestionClockPausesIt]. So this checks
     * agreement between the clock's stateDescription and the line after the tap,
     * without assuming which state the tap leaves behind.
     */
    @Test
    fun pausingTheStudyClockChangesOnlyTheVariableLine() {
        runBlocking { repository.setSimplifiedStudy(true) }
        startClockStudy("ui-clock-toggle")
        val pauseVerb = app.getString(R.string.r2_study_pause)
        val resumeVerb = app.getString(R.string.r2_study_resume)
        val pausedLabel = app.getString(R.string.clock_paused)
        val runningLabel = app.getString(R.string.clock_running)
        val clockShape = Regex("""^\d+:\d{2}$""")

        // The permanent line, read the same way as [variableClockText] so the
        // shape check is not fooled by the icon or by the merged tree.
        fun timeClockText(): String? {
            val nodes = compose.onAllNodesWithTag("study-clock-time", useUnmergedTree = true)
                .fetchSemanticsNodes()
            if (nodes.isEmpty()) return null
            return nodes.first().config
                .getOrElse(SemanticsProperties.Text) { emptyList() }
                .joinToString("") { it.text }
        }

        // The paused label embeds the pause verb ("Paused" contains "Pause"),
        // so strip it before deciding which verb the line actually offers.
        fun verbsIn(text: String): Pair<Boolean, Boolean> {
            val withoutPaused = text.replace(pausedLabel, "").trim()
            return withoutPaused.contains(pauseVerb) to withoutPaused.contains(resumeVerb)
        }

        // One verb and a matching suffix on the variable line, whatever state
        // it is in; both checks share the strip-the-suffix logic above.
        fun assertSelfConsistent(text: String) {
            val (hasPause, hasResume) = verbsIn(text)
            assertTrue(
                "the variable line shows neither or both Pause/Resume verbs: $text",
                hasResume != hasPause
            )
            assertTrue(
                "the variable line's Paused suffix does not match its verb: $text",
                text.contains(pausedLabel) == hasResume
            )
        }

        val time = compose.onNodeWithTag("study-clock-time", useUnmergedTree = true)
        val state = compose.onNodeWithTag("study-clock-state", useUnmergedTree = true)

        // Tap once and settle: no wait for a change, no assumed direction.
        compose.onNodeWithTag("study-question-clock").performClick()
        compose.waitForIdle()

        // The clock's own stateDescription is authoritative. It is declared on
        // the tagged clock node, so read it from the unmerged tree alongside the
        // lines it owns.
        val stateDescription = compose.onNodeWithTag("study-question-clock", useUnmergedTree = true)
            .fetchSemanticsNode().config
            .getOrElse(SemanticsProperties.StateDescription) { "" }
        val descSaysPaused = stateDescription.contains(pausedLabel)
        val descSaysRunning = stateDescription.contains(runningLabel)
        assertTrue(
            "the clock stateDescription has no running/paused label: $stateDescription",
            descSaysPaused || descSaysRunning
        )
        val paused = descSaysPaused && !descSaysRunning

        val lineText = checkNotNull(variableClockText()) {
            "the variable clock line did not render"
        }
        assertSelfConsistent(lineText)
        val (hasPause, hasResume) = verbsIn(lineText)
        // The line must agree with the stateDescription, not pick its own state.
        if (paused) {
            assertTrue(
                "clock is paused but the line offers no Resume: $stateDescription / $lineText",
                hasResume
            )
            assertTrue(
                "clock is paused but the line has no Paused suffix: $stateDescription / $lineText",
                lineText.contains(pausedLabel)
            )
        } else {
            assertTrue(
                "clock is running but the line offers no Pause: $stateDescription / $lineText",
                hasPause
            )
            assertTrue(
                "clock is running but the line still shows Paused: $stateDescription / $lineText",
                !lineText.contains(pausedLabel)
            )
        }

        // The permanent line survives in M:SS shape, and the tally stays visible.
        time.assertIsDisplayed()
        val timeText = timeClockText()
        assertTrue(
            "the time line is not in M:SS shape: $timeText",
            timeText != null && clockShape.matches(timeText)
        )
        state.assertIsDisplayed()
        compose.onNodeWithTag("study-reviewed").assertIsDisplayed()
    }

    /**
     * The original defect: one long readout touched "Reviewed N this session".
     * After the split the two must not overlap horizontally, and the tally stays
     * fully visible even while the clock is paused.
     */
    @Test
    fun pausedClockDoesNotOverlapTheReviewedSessionText() {
        runBlocking { repository.setSimplifiedStudy(true) }
        startClockStudy("ui-clock-overlap")
        // Normalise to paused: the clock's starting state is racy, so a blind
        // click could resume it and leave this measurement on a running header.
        setStudyClock(paused = true)

        val reviewed = compose.onNodeWithTag("study-reviewed")
        val clock = compose.onNodeWithTag("study-question-clock")
        reviewed.assertIsDisplayed()
        clock.assertIsDisplayed()
        val reviewedBounds = reviewed.fetchSemanticsNode().boundsInWindow
        val clockBounds = clock.fetchSemanticsNode().boundsInWindow
        assertTrue(
            "the clock overlaps the reviewed-session text: $reviewedBounds / $clockBounds",
            reviewedBounds.right <= clockBounds.left || clockBounds.right <= reviewedBounds.left
        )
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
        confirmTestStart()
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
     * Declaring a guess and then overriding the proposal must rewrite the result
     * line. It used to keep claiming "marked Again" while the footer showed the
     * Good interval and Next persisted Good — the card narrated a review that
     * was never written.
     */
    @Test
    fun overridingAGuessedGradeRewritesTheResultLine() {
        runBlocking { repository.setSimplifiedStudy(true) }
        seedPaper(
            "ui-guess", "UIGuess Paper", "ui-guess-cat",
            single("ui-guess-q1", "ui-guess-cat")
        )
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-title-ui-guess").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-guess").performClick()
        waitFor("UITest ui-guess-q1?")

        // The guess is declared before the answer, as the switch requires.
        compose.onNodeWithTag("study-guess").performClick()
        compose.onNodeWithText("Alpha", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("study-check").performClick()

        compose.onNodeWithTag("study-result").performScrollTo()
        compose.onNodeWithText("marked Again", substring = true).assertIsDisplayed()

        // Change grade edits the proposal, so the line has to follow it.
        compose.onNodeWithText("Change grade", substring = false).performClick()
        compose.onNodeWithText("Good", substring = false).performClick()

        compose.onNodeWithText("marked Again", substring = true).assertDoesNotExist()
        compose.onNodeWithText("marked Good", substring = true).assertIsDisplayed()
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
        // Destructive actions live behind the card's overflow menu; the action
        // row can sit below the fold on the small emulator screen, so scroll
        // the overflow in, open it, then tap its Delete item.
        compose.waitForIdle()
        compose.onNodeWithTag("paper-overflow-ui-sel").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag("paper-overflow-ui-sel").performClick()
        compose.onNodeWithTag("paper-delete-ui-sel").performClick()

        waitFor("This cannot be undone.")
        compose.onNodeWithText("This also deletes:", substring = false).assertIsDisplayed()
        compose.onNodeWithText("• 1 past attempt", substring = false).assertIsDisplayed()
        compose.onNodeWithText("• 1 bookmark", substring = false).assertIsDisplayed()
        // Cancelling must leave the paper alone.
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertTrue(runBlocking { repository.getPaper("ui-sel") != null })
    }

    /**
     * Manage mode keeps one line per category — chevron, title, `⋮` — and the
     * tree-order actions live in the menu with the enable rules the four arrows
     * had. A collapsed branch must hide its subtree and come back.
     */
    @Test
    fun manageModePutsEachCategoryOnOneLineWithItsActionsInTheMenu() {
        seedNestedPaper("ui-manage", "UIManage Paper")
        compose.setContent { McqNavHost(repository = repository) }

        waitForTag("paper-manage-ui-manage")
        compose.onNodeWithTag("paper-manage-ui-manage").performScrollTo().performClick()
        waitForTag("cat-actions-ui-manage-p")
        compose.waitForIdle()

        // Chevron + title + ⋮. The arrows are menu items now, so none of them
        // is a control of its own any more.
        compose.onNodeWithTag("cat-expand-ui-manage-p").assertIsDisplayed()
        compose.onNodeWithContentDescription("Move up").assertDoesNotExist()
        compose.onNodeWithContentDescription("Move down").assertDoesNotExist()
        compose.onNodeWithContentDescription("Nest into the category above").assertDoesNotExist()
        compose.onNodeWithContentDescription("Move out of the parent category").assertDoesNotExist()

        // Collapsing a branch hides its children; the chevron brings them back.
        compose.onNodeWithTag("cat-title-ui-manage-ca").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cat-expand-ui-manage-p").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cat-title-ui-manage-ca").assertDoesNotExist()
        compose.onNodeWithTag("cat-expand-ui-manage-p").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cat-title-ui-manage-ca").assertIsDisplayed()

        // The only top-level category can neither move nor leave a parent.
        compose.onNodeWithTag("cat-actions-ui-manage-p").performScrollTo().performClick()
        waitForTag("cat-promote-ui-manage-p")
        compose.onNodeWithTag("cat-move-up-ui-manage-p").assertIsNotEnabled()
        compose.onNodeWithTag("cat-move-down-ui-manage-p").assertIsNotEnabled()
        compose.onNodeWithTag("cat-promote-ui-manage-p").assertIsNotEnabled()
        // Edit category is in the menu too, and cancelling must change nothing.
        compose.onNodeWithTag("cat-edit-ui-manage-p").performClick()
        waitFor("Edit category")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        // First child: nothing above it to nest into, but a sibling to move
        // past and a parent to leave.
        compose.onNodeWithTag("cat-actions-ui-manage-ca").performScrollTo().performClick()
        waitForTag("cat-move-down-ui-manage-ca")
        compose.onNodeWithTag("cat-move-up-ui-manage-ca").assertIsNotEnabled()
        compose.onNodeWithTag("cat-nest-ui-manage-ca").assertIsNotEnabled()
        compose.onNodeWithTag("cat-move-down-ui-manage-ca").assertIsEnabled()
        compose.onNodeWithTag("cat-promote-ui-manage-ca").assertIsEnabled()
        compose.onNodeWithTag("cat-edit-ui-manage-ca").performClick()
        waitFor("Edit category")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        // The name itself opens Browse scoped to that branch: the child's
        // question is the only place its stem can come from.
        compose.onNodeWithTag("cat-title-ui-manage-ca").performScrollTo().performClick()
        waitFor("UITest ui-manage-qa")

        // Back must land where the tap happened: the card still open in Manage
        // mode, not collapsed to the top of the list.
        compose.onNodeWithContentDescription("Back").performClick()
        waitForTag("cat-actions-ui-manage-p")
        compose.waitForIdle()
        compose.onNodeWithTag("cat-title-ui-manage-ca").assertExists()
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
            // A card graduated from a fresh one a year ago: FSRS-7's mixture
            // curve is slow-dominated (three months is still ~75% recall), so
            // a year puts it clearly into the low-recall floor — the
            // most-at-risk review here.
            repository.recordStudyReview(
                "ui-chips", "ui-chips-low", ReviewGrade.GOOD,
                now = now - 365 * day
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
        // Tap the option Row by its stable tag, not by its "Alpha" text: the
        // text lives in a child of the selectable Row and can resolve to a
        // different/overlapping node, so the click never reached the selector.
        // Scroll it into view first: the clock and dwell cues push the first
        // option low enough that its centre can sit outside the window, and a
        // touch outside the window is dropped without an error.
        compose.onNodeWithTag("study-option-ui-stop-q1-a")
            .performScrollTo()
            .performClick()
        // The tap dispatches to the option Row's selectable action, which flips
        // ViewModel state and recomposes. performClick can return before that
        // state has settled, and Check then grades an empty selection as Again
        // — the summary would read 1/1/0 instead of 1/0/1. Wait for the
        // selection to actually land in the semantics tree before grading.
        val selectedOption = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        compose.waitUntil(10_000) {
            compose.onAllNodes(selectedOption).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(selectedOption).assertIsDisplayed()
        compose.onNodeWithTag("study-check").performClick()
        compose.onNodeWithTag("study-next").performClick()
        waitFor("UITest ui-stop-q2?")

        compose.onNodeWithText("Done for now", substring = false).performClick()
        waitFor("Session complete")
        // The tally is now localized; resolve the exact resource the Summary
        // renders with the 1/0/1 this sitting actually produced.
        val summary = app.getString(R.string.study_session_summary, 1, 0, 1)
        // The heading renders before the tally does; with the summary now
        // scrollable, wait for the exact node to exist so the scroll does not
        // race the async body render.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(summary, substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The tally sits under the clock/verdict copy, which can push it past
        // the fold on the emulator; bring it into view before asserting it is
        // displayed, the same way the other study assertions do.
        compose.onNodeWithText(summary, substring = false)
            .performScrollTo()
            .assertIsDisplayed()
        // Done means done: the stop affordance is gone, nothing asks to go on.
        assertTrue(
            "the session kept asking the learner to continue",
            compose.onAllNodesWithText("Done for now", substring = false)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    /**
     * Back from a live sitting is a voluntary stop, not a silent exit: the
     * summary and its tally appear, and only the next back leaves the screen.
     */
    @Test
    fun systemBackEndsTheSessionLikeDoneForNow() {
        runBlocking { repository.setSimplifiedStudy(true) }
        seedPaper(
            "ui-back", "UIBack Paper", "ui-back-cat",
            single("ui-back-q1", "ui-back-cat")
        )
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-title-ui-back").assertIsDisplayed()
        compose.onNodeWithTag("paper-study-ui-back").performClick()
        waitFor("Done for now")

        pressSystemBack()

        // Still on Study: the stop ends the sitting, it does not leave it —
        // and with nothing reviewed it says so, instead of claiming a
        // completed session with a tally of zero.
        waitFor("Stopped before reviewing a card")
        assertTrue(
            "a stop with nothing done must not read as a completed session",
            compose.onAllNodesWithText("Session complete", substring = false)
                .fetchSemanticsNodes().isEmpty()
        )

        // The handler stands down on the summary, so back now leaves.
        pressSystemBack()
        waitForTag("paper-title-ui-back")
    }

    /**
     * Back from a live test is the test's own finish affordance: it opens the
     * submit confirmation rather than leaving the paper ungraded, and Cancel
     * puts the learner back on the question.
     */
    @Test
    fun systemBackInATestOpensTheSubmitConfirmation() {
        seedPaper(
            "ui-test-back", "UITestBack Paper", "ui-test-back-cat",
            single("ui-test-back-q1", "ui-test-back-cat"),
            single("ui-test-back-q2", "ui-test-back-cat")
        )
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-title-ui-test-back").assertIsDisplayed()
        compose.onNodeWithTag("paper-test-ui-test-back").performClick()
        confirmTestStart()
        waitFor("Question 1 of 2")

        pressSystemBack()

        // The same dialog the Submit button opens, not the library.
        waitFor("Submit test?")
        compose.onNodeWithTag("confirm-submit").assertIsDisplayed()

        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.onNodeWithTag("confirm-submit").assertDoesNotExist()
        // Still in the sitting, on the question that was showing.
        compose.onNodeWithText("Question 1 of 2", substring = true).assertIsDisplayed()
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
        // H3 relabelled this control to say it applies to every paper; match
        // the rendered resource so the copy change stays covered, not bypassed.
        compose.onNodeWithText(
            app.getString(R.string.r2_study_enable_new_cards_all_papers),
            substring = false
        ).performClick()
        waitFor("UITest ui-empty-q1?")
        // The offer is not a one-screen gesture: the toggle behind it persists.
        assertTrue(runBlocking { repository.schedulerConfigNow().introduceNewCards })
    }

    // --- the drawer's category scope (§9.7) ---

    @Test
    fun tickingAParentCategoryTicksItsChildren() {
        seedNestedPaper("ui-scope", "UIScope Paper")
        compose.setContent { McqNavHost(repository = repository) }

        openDrawer("ui-scope")
        drawerNode("drawer-paper-ui-scope").performClick()
        waitForTag("drawer-cat-ui-scope-p")

        drawerNode("drawer-cat-ui-scope-p").performClick()
        drawerNode("drawer-cat-ui-scope-ca").assertIsOn()
        drawerNode("drawer-cat-ui-scope-cb").assertIsOn()
    }

    @Test
    fun untickingOneChildShowsTheParentIndeterminate() {
        seedNestedPaper("ui-scope-tri", "UIScope Tri")
        compose.setContent { McqNavHost(repository = repository) }

        openDrawer("ui-scope-tri")
        drawerNode("drawer-paper-ui-scope-tri").performClick()
        waitForTag("drawer-cat-ui-scope-tri-p")
        drawerNode("drawer-cat-ui-scope-tri-p").performClick()

        // One of two children off: the parent is partial, not off.
        drawerNode("drawer-cat-ui-scope-tri-ca").performClick()
        drawerNode("drawer-cat-ui-scope-tri-p").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ToggleableState,
                ToggleableState.Indeterminate
            )
        )
        drawerNode("drawer-cat-ui-scope-tri-ca").assertIsOff()
        drawerNode("drawer-cat-ui-scope-tri-cb").assertIsOn()
    }

    /**
     * The bug §7.1.1 fixes: a drawer-wide selection let paper A's ids ride
     * along with paper B's id, and the order then served A's questions under
     * B. One selection per paper, and B's Test must not see A's question.
     */
    @Test
    fun tickingInTwoPapersKeepsTheirSelectionsApart() {
        seedPaper("ui-iso-a", "UIIso A", "ui-iso-a-cat", single("ui-iso-a-q", "ui-iso-a-cat"))
        seedPaper("ui-iso-b", "UIIso B", "ui-iso-b-cat", single("ui-iso-b-q", "ui-iso-b-cat"))
        compose.setContent { McqNavHost(repository = repository) }

        openDrawer("ui-iso-a")
        drawerNode("drawer-paper-ui-iso-a").performClick()
        waitForTag("drawer-cat-ui-iso-a-cat")
        drawerNode("drawer-cat-ui-iso-a-cat").performClick()

        // Collapse A (its tick is kept) and tick only B.
        drawerNode("drawer-paper-ui-iso-a").performClick()
        drawerNode("drawer-paper-ui-iso-b").performClick()
        waitForTag("drawer-cat-ui-iso-b-cat")
        drawerNode("drawer-cat-ui-iso-b-cat").performClick()

        // The action row appears only once something is ticked, so it may be
        // below the fold: scroll to it, then read its own label.
        drawerNode("drawer-test").assertIsDisplayed()
        compose.onNodeWithTag("drawer-test").assert(hasText("Test (1 categories)"))
        drawerNode("drawer-test").performClick()
        confirmTestStart()
        waitFor("Question 1 of")
        waitFor("UITest ui-iso-b-q?")
        assertTrue(
            "paper A's question leaked into paper B's test",
            compose.onAllNodesWithText("UITest ui-iso-a-q?", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun studyFromTheDrawerServesOnlyTheSelectedCategory() {
        val second = runBlocking {
            repository.ensurePaperAndCategory("ui-drawer", "UIDrawer Paper", "ui-drawer-c1", "First")
            repository.saveQuestion(single("ui-drawer-q1", "ui-drawer-c1"))
            val c2 = repository.addCategory("ui-drawer", "Second", null)
            repository.saveQuestion(single("ui-drawer-q2", c2))
            seeded += "ui-drawer"
            c2
        }
        compose.setContent { McqNavHost(repository = repository) }

        openDrawer("ui-drawer")
        drawerNode("drawer-paper-ui-drawer").performClick()
        waitForTag("drawer-cat-$second")
        drawerNode("drawer-cat-$second").performClick()

        // One ticked branch, whatever its subtree contains. The row is the
        // last child of the item and appears only now, so it can be below the
        // fold: scroll to it and read the tagged node's own label.
        drawerNode("drawer-study").assertIsDisplayed()
        compose.onNodeWithTag("drawer-study").assert(hasText("Study (1 categories)"))
        drawerNode("drawer-study").performClick()

        waitFor("UITest ui-drawer-q2?")
        assertTrue(
            "the other category's question leaked into the scoped session",
            compose.onAllNodesWithText("UITest ui-drawer-q1?", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    // --- the card's selection tree (§9.4) ---

    @Test
    fun theEyeOpensASelectionTreeOnTheCard() {
        seedNestedPaper("ui-eye", "UIEye Paper")
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-show-ui-eye").performScrollTo().performClick()
        waitForTag("card-cat-ui-eye-p")
        compose.onNodeWithTag("card-cat-ui-eye-p").performScrollTo().performClick()
        waitForTag("paper-study-selection-ui-eye")
        // Both children are fresh, the parent rolls them up: 0 due, 2 new.
        compose.onNodeWithTag("paper-study-selection-ui-eye")
            .assert(hasText("Study selection (2 new)"))
    }

    @Test
    fun theCardAndTheDrawerShareOneSelection() {
        seedNestedPaper("ui-share", "UIShare Paper")
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-show-ui-share").performScrollTo().performClick()
        waitForTag("card-cat-ui-share-p")
        compose.onNodeWithTag("card-cat-ui-share-p").performScrollTo().performClick()
        waitForTag("paper-study-selection-ui-share")

        // The drawer reads the same ViewModel-owned selection.
        openDrawer("ui-share")
        drawerNode("drawer-paper-ui-share").performClick()
        waitForTag("drawer-cat-ui-share-p")
        drawerNode("drawer-cat-ui-share-p").assertIsOn()

        // Unticking there clears the card's scope too: one owner, two readers.
        drawerNode("drawer-cat-ui-share-p").performClick()
        drawerNode("drawer-cat-ui-share-p").assertIsOff()
        compose.onNodeWithTag("paper-study-selection-ui-share").assertDoesNotExist()
        compose.onNodeWithTag("card-cat-ui-share-p").assertIsOff()
    }

    @Test
    fun theScopedCardButtonStartsAScopedSession() {
        seedNestedPaper("ui-cardscope", "UICardScope Paper")
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-show-ui-cardscope").performScrollTo().performClick()
        waitForTag("card-cat-ui-cardscope-p")
        compose.onNodeWithTag("card-cat-ui-cardscope-p").performScrollTo().performClick()
        waitForTag("paper-study-selection-ui-cardscope")
        compose.onNodeWithTag("paper-study-selection-ui-cardscope")
            .performScrollTo()
            .performClick()

        // The parent's tick expands to both children; the first served card is
        // the first child's, and the header names the scope. The served stem
        // is the arrival signal — the Study screen has no session counter.
        waitFor("UITest ui-cardscope-qa?")
        compose.onNodeWithTag("study-scope-label").assert(hasText("Parent"))
        assertTrue(
            "the second child's question appeared before it was served",
            compose.onAllNodesWithText("UITest ui-cardscope-qb?", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
        // Grading through reaches the second child: the expansion is real.
        compose.onNodeWithText("Alpha", substring = false).performClick()
        compose.onNodeWithTag("study-check").performClick()
        compose.onNodeWithTag("study-next").performClick()
        waitFor("UITest ui-cardscope-qb?")
    }

    /**
     * A paper with two ROOT categories, one question each: enough to tell a
     * scoped action from a whole-paper one, because the second root is the
     * only thing that can be excluded.
     */
    private fun seedTwoRoots(paperId: String, title: String): String = runBlocking {
        repository.ensurePaperAndCategory(paperId, title, "$paperId-c1", "First")
        repository.saveQuestion(single("$paperId-q1", "$paperId-c1"))
        val second = repository.addCategory(paperId, "Second", null)
        repository.saveQuestion(single("$paperId-q2", second))
        seeded += paperId
        second
    }

    @Test
    fun cardExportFollowsTheSelection() {
        seedTwoRoots("ui-cexp", "UICExport Paper")
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-show-ui-cexp").performScrollTo().performClick()
        waitForTag("card-cat-ui-cexp-c1")
        compose.onNodeWithTag("card-cat-ui-cexp-c1").performScrollTo().performClick()

        // One ticked branch: the dialog says so, instead of "Export format".
        // Export is the card's icon now, aimed by its own tag.
        compose.onNodeWithTag("paper-export-ui-cexp").performScrollTo().performClick()
        // The dialog title is a localized plural keyed by the selected count
        // (one branch here); resolve the same resource instead of English copy.
        val exportTitle = app.resources.getQuantityString(
            R.plurals.r2_lib_export_selection_title, 1, 1
        )
        compose.onNodeWithText(exportTitle, substring = false).assertIsDisplayed()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(exportTitle, substring = false)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun exportDialogOffersDirectSaveShareAndCancel() {
        seedTwoRoots("ui-cexp-share", "UICShare Paper")
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-show-ui-cexp-share").performScrollTo().performClick()
        waitForTag("card-cat-ui-cexp-share-c1")
        compose.onNodeWithTag("card-cat-ui-cexp-share-c1").performScrollTo().performClick()
        compose.onNodeWithTag("paper-export-ui-cexp-share").performScrollTo().performClick()

        // Same localized plural as the production dialog, for one selection.
        val exportTitle = app.resources.getQuantityString(
            R.plurals.r2_lib_export_selection_title, 1, 1
        )
        compose.onNodeWithText(exportTitle, substring = false).assertIsDisplayed()
        // The actions are direct buttons, not FilterChips with a Selected
        // mode that a second confirm tap follows.
        compose.onNodeWithText("Save to file", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Share", substring = false)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .assertIsDisplayed()
        compose.onNodeWithText("Cancel", substring = false).assertIsDisplayed()
        assertTrue(
            "the old two-step Export confirm is still present",
            compose.onAllNodesWithText("Export", substring = false)
                .fetchSemanticsNodes().isEmpty()
        )
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.waitForIdle()
    }

    @Test
    fun cardTestFollowsTheSelection() {
        // Tick the SECOND root: a whole-paper Test would serve the first one,
        // so serving the second is the proof the scope was honoured.
        val second = seedTwoRoots("ui-ctest", "UICTest Paper")
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-show-ui-ctest").performScrollTo().performClick()
        waitForTag("card-cat-$second")
        compose.onNodeWithTag("card-cat-$second").performScrollTo().performClick()
        compose.onNodeWithTag("paper-test-ui-ctest").performScrollTo().performClick()
        confirmTestStart()

        waitFor("UITest ui-ctest-q2?")
        assertTrue(
            "the unticked root's question leaked into the scoped test",
            compose.onAllNodesWithText("UITest ui-ctest-q1?", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun theMistakesShortcutOpensTheSheetWithMistakesSelected() {
        // A saved wrong answer gives the paper a mistake, so the card shows
        // the Mistakes shortcut. Tapping it must preselect Mistakes in the
        // same setup sheet, not start a legacy session.
        seedTwoRoots("ui-cmist", "UICMistakes Paper")
        runBlocking {
            repository.saveAttempt(
                paperId = "ui-cmist",
                paperTitle = "UICMistakes Paper",
                questions = listOf(single("ui-cmist-q1", "ui-cmist-c1")),
                selections = mapOf("ui-cmist-q1" to setOf("ui-cmist-q1-b")),
                negativeMarking = 0.0,
                durationSeconds = 10,
                finishedAt = System.currentTimeMillis()
            )
        }
        compose.setContent { McqNavHost(repository = repository) }

        waitForTag("paper-mistakes-ui-cmist")
        compose.onNodeWithTag("paper-mistakes-ui-cmist").performScrollTo().performClick()
        waitFor("Start a test")
        compose.onNodeWithText("Mistakes", substring = false).assertIsSelected()
    }

    @Test
    fun cardBrowseFollowsTheSelection() {
        // The second root is the only thing a scoped Browse can exclude, so a
        // whole-paper list would show its sibling too.
        val second = seedTwoRoots("ui-cbrowse", "UICBrowse Paper")
        compose.setContent { McqNavHost(repository = repository) }

        compose.onNodeWithTag("paper-show-ui-cbrowse").performScrollTo().performClick()
        waitForTag("card-cat-$second")
        compose.onNodeWithTag("card-cat-$second").performScrollTo().performClick()
        compose.onNodeWithTag("paper-browse-ui-cbrowse").performScrollTo().performClick()

        // Pre-filtered, and the field names the applied category. The field is
        // addressed by tag: a scoped list also renders that title as a row
        // label, so the text would match two nodes.
        waitFor("UITest ui-cbrowse-q2?")
        assertTrue(
            "the unticked root's question leaked into the list",
            compose.onAllNodesWithText("UITest ui-cbrowse-q1?", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
        compose.onNodeWithTag("browse-category-filter").assertTextContains("Second")

        // "All categories" clears the pre-filter: both questions are listed.
        compose.onNodeWithTag("browse-category-filter").performClick()
        waitFor("All categories")
        compose.onNodeWithText("All categories", substring = false).performClick()
        waitFor("UITest ui-cbrowse-q1?")
        waitFor("UITest ui-cbrowse-q2?")
    }

    /**
     * An explanation that is only a picture flattens to ""; the reveal gate
     * used to read that as "no explanation" and rendered nothing at all.
     */
    @Test
    fun studyRevealsAnImageOnlyExplanation() {
        seedPaper(
            "ui-back-image", "UIBack Image", "ui-back-image-cat",
            explanationQuestion(
                "ui-back-image-q", "ui-back-image-cat",
                listOf(ContentElement.ImageElement(tinyPng))
            )
        )
        runBlocking { repository.setSimplifiedStudy(false) }
        revealStudyAnswer("ui-back-image")

        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("question-image", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("question-image", useUnmergedTree = true)[0]
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * A table-only back likewise has no flattened text. Its cells must reach
     * the revealed card, not just the stored row.
     */
    @Test
    fun studyRevealsATableOnlyExplanation() {
        seedPaper(
            "ui-back-table", "UIBack Table", "ui-back-table-cat",
            explanationQuestion(
                "ui-back-table-q", "ui-back-table-cat",
                listOf(
                    ContentElement.TableElement(listOf(listOf("cell-alpha", "cell-beta")))
                )
            )
        )
        runBlocking { repository.setSimplifiedStudy(false) }
        revealStudyAnswer("ui-back-table")

        compose.onNodeWithText("cell-alpha", substring = false)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * A table back and the scalar explanation image are two visible slots; a
     * gate keyed on either one alone would drop the other.
     */
    @Test
    fun studyRevealsATableExplanationAndItsImage() {
        seedPaper(
            "ui-back-table-image", "UIBack Table Image", "ui-back-table-image-cat",
            explanationQuestion(
                "ui-back-table-image-q", "ui-back-table-image-cat",
                listOf(ContentElement.TableElement(listOf(listOf("cell-alpha")))),
                explanationImage = tinyPng
            )
        )
        runBlocking { repository.setSimplifiedStudy(false) }
        revealStudyAnswer("ui-back-table-image")

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("cell-alpha", substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("cell-alpha", substring = false).assertExists()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("question-image", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("question-image", useUnmergedTree = true)[0]
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Guard: the plain text back, which already worked, must keep working. */
    @Test
    fun studyStillRevealsATextOnlyExplanation() {
        seedPaper(
            "ui-back-text", "UIBack Text", "ui-back-text-cat",
            explanationQuestion(
                "ui-back-text-q", "ui-back-text-cat",
                listOf(ContentElement.TextElement("plain-back-text"))
            )
        )
        runBlocking { repository.setSimplifiedStudy(false) }
        revealStudyAnswer("ui-back-text")

        compose.onNodeWithText("plain-back-text", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
