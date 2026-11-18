package com.mcqapp

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.AttemptMetadata
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewFilters
import com.mcqapp.domain.labelRes
import com.mcqapp.ui.Verdict
import com.mcqapp.ui.common.LocalUiMessageSender
import com.mcqapp.ui.common.UiMessage
import com.mcqapp.ui.results.ResultsScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun setContentCapturingMessages(sink: (UiMessage) -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalUiMessageSender provides sink) {
                ResultsScreen(
                    repository = repository,
                    attemptId = attemptId,
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

        compose.onNodeWithTag("results-list").performScrollToNode(
            hasText(chipLabel(ReviewFilters.CORRECT), substring = false)
        )
        listOf(
            ReviewFilters.ALL, ReviewFilters.CORRECT, ReviewFilters.WRONG,
            ReviewFilters.SKIPPED, ReviewFilters.UNGRADED
        ).forEach { key ->
            compose.onNodeWithText(chipLabel(key), substring = false).assertIsDisplayed()
        }

        compose.onNodeWithText(chipLabel(ReviewFilters.CORRECT), substring = false).performClick()
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
        compose.onNodeWithText(chipLabel(ReviewFilters.ALL), substring = false).performClick()
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

        val delivered = CompletableDeferred<UiMessage>()
        setContentCapturingMessages { delivered.complete(it) }

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

        val message = runBlocking { withTimeout(30_000) { delivered.await() } }
        assertEquals(app.getString(R.string.report_export_failed), message.text)
    }
}
