package com.mcqapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.stats.SessionsData
import com.mcqapp.domain.stats.StudySession
import com.mcqapp.ui.sessions.SessionsList
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
}
