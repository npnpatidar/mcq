package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Layout/accessibility decisions that no behaviour test can observe: the
 * per-card scroll reset before visibility is reported, spoken descriptions on
 * every Study image, a 48 dp option target, matched grade-button colours, the
 * break controls and the drill's honest one-pass note. Pure JVM source guards,
 * so they run on the ARM64 host where the Robolectric/UI suites cannot.
 *
 * BEHAVIOUR: a new card's scroll offset dies with the card, every content image
 * is spoken, a grade's fill and content colour come from one palette, and a
 * break pauses both clocks without ending the sitting.
 */
class StudyPolishStructureGuardTest {

    private val screen by lazy {
        File("src/main/java/com/mcqapp/ui/study/StudyScreen.kt").readText()
    }
    private val viewModel by lazy {
        File("src/main/java/com/mcqapp/ui/study/StudyViewModel.kt").readText()
    }

    /** The body's own slice; the summary has its own (harmless) scroll state. */
    private fun studyBody(): String {
        val start = screen.indexOf("private fun StudyBody(")
        assertTrue("StudyBody is missing", start >= 0)
        return screen.substring(start, screen.indexOf("\n@Composable", start))
    }

    @Test
    fun aNewCardResetsScrollBeforeItsVisibilityIsReported() {
        assertTrue(
            "the body must be keyed on the question id so stale visibility dies with the old card",
            screen.contains("else -> key(state.current?.questionId) {")
        )
        val body = studyBody()
        assertTrue(
            "the scroll state must be fresh per card",
            body.contains("val scrollState = remember(question.id) { ScrollState(0) }")
        )
        assertEquals(2, Regex("""\.verticalScroll\(scrollState\)""").findAll(body).count())
        assertFalse(
            "an unkeyed scroll state would carry the old card's offset forward",
            body.contains(".verticalScroll(rememberScrollState())")
        )
        // The scroll container is tagged so the UI test can scroll the card out
        // of view before advancing to the next one.
        assertEquals(2, Regex("""\.testTag\("study-question-scroll"\)""").findAll(body).count())
        // Visibility is reported from the same onGloballyPositioned callbacks,
        // and the report still drives the VM gate. The readings are card-scoped
        // and the flow restarts per card: a body that kept its scrolled-past
        // "not visible" reading handed it to a card that starts at the top, and
        // the clock stayed paused because the effect's key never changed again.
        assertTrue(body.contains("snapshotFlow { questionOnScreen }"))
        assertTrue(body.contains("viewModel.setQuestionVisible(it)"))
        assertTrue(
            "the viewport reading must be keyed on the card",
            body.contains("var questionOnScreen by remember(question.id) { mutableStateOf(true) }")
        )
        assertTrue(
            "an arriving card must re-read its own geometry after its frame",
            body.contains("withFrameNanos { }")
        )
        assertTrue(
            "an unplaced container must not read as a hidden card",
            body.contains("if (container.size.height <= 0 || card.size.height <= 0) return")
        )
    }

    @Test
    fun everyContentBearingStudyImageHasASpokenDescription() {
        val calls = Regex("""QuestionImage\(""").findAll(screen).toList()
        assertEquals("four image sites in Study: passage, stem, option and back", 4, calls.size)
        calls.forEach { call ->
            val window = screen.substring(call.range.first, minOf(screen.length, call.range.first + 260))
            assertTrue(
                "a Study image has no contentDescription: ${window.take(90)}",
                window.contains("contentDescription =")
            )
        }
        // The four descriptions are resources, not English literals.
        listOf(
            "R.string.passage_image", "R.string.question_image",
            "R.string.image_for_this_option", "R.string.explanation_image"
        ).forEach { name -> assertTrue("$name missing", screen.contains(name)) }
    }

    @Test
    fun optionRowsReserveA48DpTargetAndKeepTheirTags() {
        // QuestionCard is a separate composable below StudyBody, so read the
        // whole screen here rather than the body slice.
        assertTrue(
            "the row must reserve a 48 dp tap target",
            screen.contains(".heightIn(min = 48.dp)")
        )
        assertTrue("the test tag must survive", screen.contains(".testTag(\"study-option-\" + option.id)"))
        // Adequate spacing, not just a minimum height: the rows are no longer
        // packed at 6 dp with an 8 dp inset.
        assertTrue(screen.contains(".padding(top = 8.dp)"))
        assertTrue(screen.contains(".padding(horizontal = 8.dp, vertical = 4.dp)"))
        assertTrue("the shared mark component must be used", screen.contains("OptionMark("))
        assertTrue(
            "one mark component to be reused by Results later",
            Regex("""internal fun OptionMark\(""").containsMatchIn(screen)
        )
    }

    @Test
    fun gradeButtonsResolveMatchedRolePairsAndUseThemForText() {
        assertTrue(
            "the UI must use the pure resolver",
            screen.contains("gradePaletteFor(grade, MaterialTheme.colorScheme)")
        )
        assertTrue(
            "content colour must come from the same pair as the fill",
            screen.contains("contentColor = palette.onContainer")
        )
        assertFalse(
            "the old unmatched fill is gone",
            screen.contains("ButtonDefaults.buttonColors(containerColor = color)")
        )
        for (grade in listOf("AGAIN", "HARD", "GOOD", "EASY")) {
            assertTrue("grade $grade must go through the resolver", screen.contains("ReviewGrade.$grade,"))
        }
    }

    @Test
    fun theBreakIsADistractionFreePauseNotAnExit() {
        assertTrue("ViewModel must bank the clocks on break", viewModel.contains("fun takeBreak() {"))
        assertTrue("ViewModel must resume without ending the sitting", viewModel.contains("fun resumeFromBreak() {"))
        val takeBreak = functionOf(viewModel, "fun takeBreak() {")
        assertTrue(takeBreak.contains("pauseQuestionClock()"))
        assertTrue(takeBreak.contains("bankSessionClock()"))
        assertFalse("a break must not end the sitting", takeBreak.contains("finishSession("))
        assertTrue(screen.contains(".testTag(\"study-take-break\")"))
        assertTrue(screen.contains(".testTag(\"study-resume-break\")"))
        assertTrue(screen.contains(".testTag(\"study-on-break\")"))
        assertTrue(screen.contains("R.string.study_polish_on_break"))
        // Dismiss remains: it still only silences the ten-review suggestion.
        assertTrue(screen.contains("Text(stringResource(R.string.dismiss))"))
    }

    @Test
    fun theLeechChipIsNotDrillOnlyAndTheNoticeIsOneShot() {
        assertTrue(
            "the chip must follow the card's own flag, not the pull reason",
            screen.contains("if (isLeech || reason == StudyReason.LEECH) {")
        )
        assertTrue(screen.contains("isLeech = state.current?.state?.leech == true"))
        assertTrue(screen.contains(".testTag(\"study-leech-notice\")"))
        // The crossing is computed from the persisted result...
        assertTrue(viewModel.contains("crossedLeechThreshold(current.current?.state, next)"))
        // ...and the flag travels in the very update that publishes the card,
        // so a reader can never see the new card without its one-shot notice.
        val pull = functionOf(
            viewModel,
            "private suspend fun pullCard(flagLeechCrossing: Boolean = false) {"
        )
        assertTrue(pull.contains("justFlaggedLeech = flagLeechCrossing"))
        // Both grade paths hand the crossing to the pull; neither writes the
        // flag back afterwards, which made the notice lag one state update.
        assertEquals(
            2,
            Regex("""pullCard\(flagLeechCrossing = becameLeech\)""").findAll(viewModel).count()
        )
        assertFalse(
            "no post-pull justFlaggedLeech = true write may linger",
            viewModel.contains("justFlaggedLeech = true")
        )
        assertTrue(
            "consecutive crossings must re-announce on the newly served card",
            screen.contains("LaunchedEffect(state.current?.questionId, state.justFlaggedLeech)")
        )
        assertTrue(screen.contains("announcer.announce(leechNoticeMessage)"))
    }

    @Test
    fun theDrillSaysItMakesOnePass() {
        assertTrue("the drill must document its one-pass contract", viewModel.contains("ONE PASS"))
        assertTrue(screen.contains("R.string.study_polish_drill_one_pass"))
        assertTrue(screen.contains("state.leechesOnly && state.reviewed > 0"))
    }

    @Test
    fun switchingScopeStopsTheOldSittingBeforeNavigating() {
        // Changing scope pushes a fresh study route, so navigate() retains the
        // old ViewModel in the back stack. Every scope pick must end that sitting
        // first (exactly as Back and "Done for now" do), or its ticker keeps
        // running and a later pop writes an inflated study_sessions row.
        val start = screen.indexOf("onPickWhole = {")
        assertTrue("scope handlers are missing", start >= 0)
        val handlers = screen.substring(start, screen.indexOf("onSaveMistakes", start))
        val stops = Regex("""viewModel\.stop\(\)""").findAll(handlers).count()
        val navigations = Regex("""navController\.navigate\(scopeRoute\(""").findAll(handlers).count()
        assertEquals("all three scope picks must stop the sitting", 3, stops)
        assertEquals("all three scope picks must navigate", 3, navigations)
        assertEquals(
            "each scope pick must stop before it navigates",
            3,
            Regex("""viewModel\.stop\(\)\s*\n\s*navController\.navigate\(scopeRoute\(""")
                .findAll(handlers).count()
        )
        // stop() is the thing that banks both clocks and writes the row once.
        val stop = functionOf(viewModel, "fun stop() {")
        assertTrue(stop.contains("bankSessionClock()"))
        assertTrue(stop.contains("publishSessionSeconds()"))
    }

    @Test
    fun enableNewCardsRestartsBothClocksAndTheSitting() {
        val enable = functionOf(viewModel, "fun enableNewCards() {")
        assertTrue("the sitting clock must be re-based", enable.contains("resetSessionClock()"))
        assertTrue(
            "the sitting's wall-clock start must follow the new card",
            enable.contains("sessionStartedAtWall = System.currentTimeMillis()")
        )
        assertTrue("the empty-state's frozen total must not leak", enable.contains("sessionTotalSeconds = 0L"))
        assertTrue(
            "the pull must happen after the clocks restart",
            enable.indexOf("resetSessionClock()") < enable.indexOf("pullCard()")
        )
    }

    /** One function body, from its signature to the next top-level close. */
    private fun functionOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is missing", start >= 0)
        val end = source.indexOf("\n    }\n", start).let {
            if (it < 0) source.indexOf("\n}\n", start) else it
        }
        assertTrue("$signature has no closing brace", end > start)
        return source.substring(start, end)
    }
}
