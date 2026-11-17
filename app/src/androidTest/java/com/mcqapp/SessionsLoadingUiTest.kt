package com.mcqapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.stats.SessionsData
import com.mcqapp.domain.stats.SessionSlice
import com.mcqapp.domain.stats.StatsAggregator
import com.mcqapp.domain.stats.StudySession
import com.mcqapp.ui.sessions.SessionsList
import com.mcqapp.ui.sessions.SessionsScreen
import com.mcqapp.ui.sessions.SessionsViewModel
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Sessions body keeps the previous range's sittings on screen while a new
 * range loads. Before the fix, `UiState.loading` was written but never read by
 * the screen: tapping a range chip highlighted it over stale rows with no cue
 * in the body. The inline indicator is that cue (CI emulator, testTag).
 */
@RunWith(AndroidJUnit4::class)
class SessionsLoadingUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val staleData = SessionsData(
        totalSessions = 1,
        totalReviews = 2,
        sessions = listOf(
            StudySession(
                startedAt = 1_000L,
                endedAt = 61_000L,
                day = 1L,
                reviews = 2,
                questions = 2,
                again = 0,
                hard = 0,
                good = 2,
                easy = 0,
                timedSeconds = 10L,
                timedReviews = 2,
                papers = emptyList(),
                categories = emptyList()
            )
        )
    )

    @Test
    fun staleSittingsShowAnInlineIndicatorWhileLoading() {
        compose.setContent {
            MaterialTheme { SessionsList(data = staleData, loading = true) }
        }
        // An indeterminate progress bar never settles the Compose idle clock,
        // so wait on the semantics tree instead of waitForIdle().
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("sessions-loading").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("sessions-list").assertIsDisplayed()
        compose.onNodeWithTag("sessions-loading").assertIsDisplayed()
    }

    @Test
    fun settledSittingsShowNoIndicator() {
        compose.setContent {
            MaterialTheme { SessionsList(data = staleData, loading = false) }
        }
        // Wait for the body to be composed before asserting the indicator is
        // absent, so the check can't pass against an empty semantics tree.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("sessions-list").assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithTag("sessions-loading").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun topicExpandCollapseRevealsAndHidesRemainingTopics() {
        val topics = (1..6).map { SessionSlice("Topic $it", it) }
        val data = staleData.copy(
            sessions = listOf(staleData.sessions.first().copy(categories = topics))
        )
        compose.setContent {
            MaterialTheme { SessionsList(data = data) }
        }
        waitForText("Show all topics")

        // Four topics fit before the control; the other two are behind "+2 more".
        compose.onNodeWithText("Show all topics", substring = false).assertIsDisplayed()
        assertTextPresent("+2 more")
        assertTextAbsent("Topic 6")

        compose.onNodeWithText("Show all topics", substring = false).performClick()
        waitForText("Show less")

        compose.onNodeWithText("Show less", substring = false).assertIsDisplayed()
        assertTextPresent("Topic 6")
        assertTextAbsent("+2 more")

        compose.onNodeWithText("Show less", substring = false).performClick()
        waitForText("Show all topics")
        assertTextPresent("+2 more")
    }

    @Test
    fun emptyRangeShowsTheEmptyMessageNotAnError() {
        compose.setContent {
            MaterialTheme { SessionsList(data = SessionsData(totalSessions = 0)) }
        }
        waitForText("No study sessions in this range yet.")

        compose.onNodeWithText(
            "No study sessions in this range yet.", substring = false
        ).assertIsDisplayed()
    }

    @Test
    fun failedLoadShowsGenericErrorAndRetryRecovers() {
        val app = ApplicationProvider.getApplicationContext() as McqApplication
        val viewModel = SessionsViewModel(app)
        val rawDetail = "sqlite detail: disk I/O error"

        // Publish the failure before composition. Main.immediate inside
        // runOnIdle makes the state transition synchronous, so the error branch
        // is present on the first frame and there is no spinner to settle.
        compose.runOnIdle {
            viewModel.load(
                range = StatsAggregator.Range.D30,
                loader = { throw IllegalStateException(rawDetail) },
                dispatcher = Dispatchers.Main.immediate
            )
        }
        // Hold the screen at STARTED: collectAsStateWithLifecycle still observes
        // the state, but the screen's ON_RESUME refresh cannot clear the injected
        // error before it is asserted (the old version raced that refresh).
        val lifecycleOwner = object : LifecycleOwner {
            private val registry = LifecycleRegistry(this).apply {
                currentState = Lifecycle.State.STARTED
            }
            override val lifecycle: Lifecycle get() = registry
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                MaterialTheme {
                    SessionsScreen(
                        repository = app.repository,
                        navController = rememberNavController(),
                        viewModel = viewModel
                    )
                }
            }
        }
        waitForText("Sessions could not be loaded.")

        compose.onNodeWithText(
            "Sessions could not be loaded.", substring = false
        ).assertIsDisplayed()
        // The exception text goes to the log, never to the learner.
        assertTextAbsent(rawDetail)

        // Retry is wired to refresh(), not a dead label: the list returns.
        compose.onNodeWithText("Retry", substring = false).performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("sessions-list").assertIsDisplayed()
        assertTextAbsent("Sessions could not be loaded.")
    }

    private fun waitForText(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertTextPresent(text: String) {
        assertTrue(
            "expected at least one '$text' node",
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    private fun assertTextAbsent(text: String) {
        assertFalse(
            "did not expect a '$text' node",
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }
}
