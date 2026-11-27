package com.mcqapp

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.AttemptMetadata
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewFilters
import com.mcqapp.domain.labelRes
import com.mcqapp.ui.Verdict
import com.mcqapp.ui.common.LocalUiMessageSender
import com.mcqapp.ui.common.UiMessage
import com.mcqapp.ui.results.ResultsScreen
import com.mcqapp.util.accessibleContentText
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Result-review coverage for the four-state verdict, the filter chips and the
 * export-failure feedback path. The verdict is shared with the CSV export, so
 * the spoken label/glyph on screen is the user-visible half of that contract.
 */
@RunWith(AndroidJUnit4::class)
class ResultsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository
    private val paperId = "uitest-results-paper"
    private val categoryId = "uitest-results-cat"
    private var attemptId = 0L

    /** A separate attempt for the formula-option regression (see [seed]). */
    private var chemAttemptId = 0L

    /**
     * The elements a formula-carrying answer stores: a text run and a MathML
     * block. `QuestionOption.text` flattens this to `"Benzene, "` — the block
     * contributes no text — which is exactly the regression this fixture pins.
     */
    private val benzeneOptionElements = listOf(
        ContentElement.TextElement("Benzene, "),
        ContentElement.MathElement(
            "<math><msub><mtext>C</mtext><mn>6</mn></msub>" +
                "<msub><mtext>H</mtext><mn>6</mn></msub></math>"
        )
    )

    private fun question(index: Int, hasAnswerKey: Boolean) = Question(
        id = "uitest-results-q$index",
        categoryId = categoryId,
        text = "UIResults question $index?",
        options = listOf(
            QuestionOption("uitest-results-q$index-a", "Alpha"),
            QuestionOption("uitest-results-q$index-b", "Beta")
        ),
        correctOptionIds = if (hasAnswerKey) setOf("uitest-results-q$index-a") else emptySet()
    )

    @Before
    fun seed() {
        runBlocking<Unit> {
            repository.ensurePaperAndCategory(paperId, "Results Paper", categoryId, "Results Cat")
            // Correct, wrong, skipped and ungraded in one attempt.
            val questions = listOf(
                question(1, hasAnswerKey = true),
                question(2, hasAnswerKey = true),
                question(3, hasAnswerKey = true),
                question(4, hasAnswerKey = false)
            )
            val selections = mapOf(
                "uitest-results-q1" to setOf("uitest-results-q1-a"),
                "uitest-results-q2" to setOf("uitest-results-q2-b"),
                // q3 deliberately unanswered; q4 answered but has no answer key.
                "uitest-results-q4" to setOf("uitest-results-q4-a")
            )
            attemptId = repository.saveAttempt(
                paperId = paperId,
                paperTitle = "Results Paper",
                questions = questions,
                selections = selections,
                negativeMarking = 0.0,
                durationSeconds = 300,
                finishedAt = System.currentTimeMillis(),
                dwellSeconds = questions.associate { it.id to 10L },
                metadata = AttemptMetadata()
            )
            // The formula option gets its OWN attempt. A fifth correct question
            // in the attempt above would duplicate the "Correct ✓" verdict
            // description those tests match exactly, making every such
            // assertion ambiguous; a separate attempt keeps both fixtures
            // precise. Its one question is answered correctly on purpose, so
            // the rendered row carries the formula option and a state label.
            chemAttemptId = repository.saveAttempt(
                paperId = paperId,
                paperTitle = "Results Paper",
                questions = listOf(
                    Question(
                        id = "uitest-results-chem",
                        categoryId = categoryId,
                        elements = listOf(ContentElement.TextElement("Which formula is benzene?")),
                        options = listOf(
                            QuestionOption("uitest-results-chem-a", benzeneOptionElements),
                            QuestionOption("uitest-results-chem-b", "C2H6O")
                        ),
                        correctOptionIds = setOf("uitest-results-chem-a")
                    )
                ),
                selections = mapOf("uitest-results-chem" to setOf("uitest-results-chem-a")),
                negativeMarking = 0.0,
                durationSeconds = 60,
                finishedAt = System.currentTimeMillis(),
                dwellSeconds = mapOf("uitest-results-chem" to 10L),
                metadata = AttemptMetadata()
            )
            // A clean slate for the export-failure injection.
            File(app.cacheDir, "reports").deleteRecursively()
        }
    }

    @After
    fun cleanup() {
        runBlocking<Unit> {
            File(app.cacheDir, "reports").deleteRecursively()
            repository.deletePaper(paperId)
        }
    }

    /** The chip label resolved through the same resource the screen renders. */
    private fun chipLabel(key: String): String = app.getString(key.labelRes)

    private fun spoken(verdict: Verdict): String = app.getString(
        R.string.verdict_spoken,
        app.getString(verdict.labelRes),
        verdict.glyph
    )

    private fun waitForSpoken(verdict: Verdict) {
        val text = spoken(verdict)
        // Result rows sit below the report cards in a LazyColumn, so they are
        // not composed until scrolled into view. ScrollToNode walks the list
        // until the matching row exists, then the wait anchors on semantics.
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("results-list").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("results-list")
            .performScrollToNode(hasContentDescriptionExactly(text))
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription(text, substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setContentCapturingMessages(
        attempt: Long = attemptId,
        sink: (UiMessage) -> Unit
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalUiMessageSender provides sink) {
                ResultsScreen(
                    repository = repository,
                    attemptId = attempt,
                    navController = rememberNavController(),
                    reviewMode = true
                )
            }
        }
    }

    @Test
    fun fourVerdictsRenderDistinctlyIncludingSkipped() {
        // The shared predicate is what keeps a skipped row out of the red group:
        // SKIPPED needs a graded question (answer key present) and no selection.
        assertEquals(Verdict.SKIPPED, Verdict.of(setOf("a"), emptySet(), false))
        assertEquals(Verdict.WRONG, Verdict.of(setOf("a"), setOf("b"), false))
        assertEquals(
            Verdict.UNGRADED,
            Verdict.of(emptySet(), setOf("a"), false)
        )

        setContentCapturingMessages {}
        waitForSpoken(Verdict.CORRECT)

        listOf(Verdict.CORRECT, Verdict.WRONG, Verdict.SKIPPED, Verdict.UNGRADED)
            .forEach { verdict ->
                // The report cards push the result rows below the fold; scroll
                // each spoken verdict into view before asserting it.
                compose.onNodeWithTag("results-list")
                    .performScrollToNode(hasContentDescriptionExactly(spoken(verdict)))
                compose.onNodeWithContentDescription(spoken(verdict), substring = false)
                    .assertIsDisplayed()
            }
        // Four different spoken strings: the glyph is not the only signal.
        val spokenValues = Verdict.values().map { spoken(it) }
        assertEquals(4, spokenValues.toSet().size)
    }

    @Test
    fun filterChipsRestrictTheVisibleVerdicts() {
        setContentCapturingMessages {}
        waitForSpoken(Verdict.CORRECT)

        // Addressed by tag, not by label: a chip's word ("Skipped") is also the
        // verdict word on the row it filters, so text matched two nodes.
        compose.onNodeWithTag("results-list")
            .performScrollToNode(hasTestTag("results-filter-${ReviewFilters.CORRECT}"))
        listOf(
            ReviewFilters.ALL, ReviewFilters.CORRECT, ReviewFilters.WRONG,
            ReviewFilters.SKIPPED, ReviewFilters.UNGRADED
        ).forEach { key ->
            compose.onNodeWithTag("results-filter-$key").assertIsDisplayed()
            // The visible label is still the localized word, so the chip is
            // not just a nameless box.
            compose.onNodeWithTag("results-filter-$key")
                .assertTextContains(chipLabel(key))
        }

        compose.onNodeWithTag("results-filter-${ReviewFilters.CORRECT}").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription(spoken(Verdict.CORRECT), substring = false)
                .fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithContentDescription(spoken(Verdict.WRONG), substring = false)
                    .fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription(spoken(Verdict.SKIPPED), substring = false)
                    .fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("results-list")
            .performScrollToNode(hasContentDescriptionExactly(spoken(Verdict.CORRECT)))
        compose.onNodeWithContentDescription(spoken(Verdict.CORRECT), substring = false)
            .assertIsDisplayed()

        // Going back to All restores the other verdict rows.
        compose.onNodeWithTag("results-filter-${ReviewFilters.ALL}").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription(spoken(Verdict.WRONG), substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("results-list")
            .performScrollToNode(hasContentDescriptionExactly(spoken(Verdict.WRONG)))
        compose.onNodeWithContentDescription(spoken(Verdict.WRONG), substring = false)
            .assertIsDisplayed()
    }

    @Test
    fun exportFailureIsAnnouncedThroughTheMessageSender() {
        // reportFile() calls mkdirs() on this path; a regular file there makes
        // the export's FileOutputStream throw, the VM returns null and the
        // screen must send the generic failure message rather than fail silent.
        val reports = File(app.cacheDir, "reports")
        check(reports.createNewFile()) { "could not stage the export failure" }

        // The VM now renders on IO and emits a one-shot completion event that
        // the screen delivers through LocalUiMessageSender in a LaunchedEffect.
        // Blocking this thread on a Deferred would starve that effect (Main is
        // the test dispatcher), so capture into an atomic and let waitUntil
        // pump the composition instead.
        val delivered = AtomicReference<UiMessage>()
        setContentCapturingMessages { delivered.set(it) }

        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription(
                app.getString(R.string.report_export), substring = false
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(
            app.getString(R.string.report_export), substring = false
        ).performClick()
        compose.onNodeWithText(app.getString(R.string.report_export_csv), substring = false)
            .performClick()

        compose.waitUntil(30_000) { delivered.get() != null }
        val message = delivered.get()!!
        assertEquals(app.getString(R.string.report_export_failed), message.text)
    }

    @Test
    fun formulaOptionIsAnnouncedWithItsFullAccessibleText() {
        // Regression: moving a formula into MathElements left option.text as
        // the plain-text runs only, so TalkBack announced "Benzene,  — correct
        // answer, you chose this" and never the formula. The state label must
        // stay merged with the formula, not replace it.
        val expected = accessibleContentText(benzeneOptionElements)
        // The two msub siblings are adjacent MathML children, so the spoken
        // form joins them with no separator: "C subscripted 6H subscripted 6".
        assertTrue(
            "fixture no longer starts with the option text: $expected",
            expected.startsWith("Benzene,")
        )
        assertTrue(
            "fixture no longer carries the spoken formula: $expected",
            expected.contains("subscripted 6H subscripted 6")
        )

        setContentCapturingMessages(chemAttemptId) {}
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("results-list").fetchSemanticsNodes().isNotEmpty()
        }
        // The old description (option.text) does not contain the spoken
        // formula, so this scroll finds no node and the test fails on it.
        compose.onNodeWithTag("results-list")
            .performScrollToNode(hasContentDescription(expected, substring = true))
        compose.onNodeWithContentDescription(expected, substring = true)
            .assertIsDisplayed()
    }
}
