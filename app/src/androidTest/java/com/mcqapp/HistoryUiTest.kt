package com.mcqapp

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.history.HistoryScreen
import com.mcqapp.ui.history.HistoryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * History has no emulator tests. These pin the two navigation actions, the
 * empty state, and — the audit's highest-value gap — the ON_RESUME observer.
 * Calling `refresh()` directly would pass even if the DisposableEffect were
 * deleted, so the refresh test drives the lifecycle and asserts that a write
 * made while the screen is open reaches the UI only after ON_RESUME.
 *
 * CI-only: the real repository opens SQLite (x86_64 emulator).
 */
@RunWith(AndroidJUnit4::class)
class HistoryUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository

    @After
    fun cleanup() = runBlocking { repository.deletePaper(PAPER) }

    private fun question(id: String, text: String) = Question(
        id = id,
        categoryId = CAT,
        text = text,
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = setOf("$id-a")
    )

    /** A wrong answer so the question enters the hardest list. */
    private suspend fun attempt(question: Question, finishedAt: Long): Long =
        repository.saveAttempt(
            paperId = PAPER,
            paperTitle = TITLE,
            questions = listOf(question),
            selections = mapOf(question.id to setOf("${question.id}-b")),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = finishedAt
        )

    private fun waitFor(text: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun hostWithStubDestinations() {
        compose.setContent {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "history") {
                composable("history") { HistoryScreen(repository = repository, navController = nav) }
                composable(
                    route = "results/{attemptId}",
                    arguments = listOf(navArgument("attemptId") { type = NavType.LongType })
                ) { entry ->
                    val attemptId = entry.arguments?.getLong("attemptId")
                    Text("RESULTS_DEST $attemptId")
                }
                composable("stats") { Text("STATS_DEST") }
            }
        }
    }

    /** The attempt row shows "0/1 correct" — unique, unlike the paper title. */
    @Test
    fun tappingAnAttemptRowOpensItsResults() {
        val attemptId = runBlocking { attempt(question("uihist-q1", "Row tap question?"), 1_000L) }
        hostWithStubDestinations()

        waitFor("0/1 correct")
        compose.onNodeWithText("0/1 correct").performClick()

        waitFor("RESULTS_DEST $attemptId")
        compose.onNodeWithText("RESULTS_DEST $attemptId").assertIsDisplayed()
    }

    @Test
    fun statsIconNavigatesToStats() {
        runBlocking { attempt(question("uihist-q2", "Stats icon question?"), 2_000L) }
        hostWithStubDestinations()

        waitFor("0/1 correct")
        compose.onNodeWithContentDescription(app.getString(R.string.stats)).performClick()

        waitFor("STATS_DEST")
        compose.onNodeWithText("STATS_DEST").assertIsDisplayed()
    }

    @Test
    fun withNoAttemptsTheEmptyStateIsShown() {
        // No attempt seeded; the list reads every paper, so this is the true
        // empty state and not merely an empty paper.
        compose.setContent {
            HistoryScreen(repository = repository, navController = rememberNavController())
        }
        waitFor(app.getString(R.string.no_attempts_yet))
        compose.onNodeWithText(app.getString(R.string.no_attempts_yet)).assertIsDisplayed()
    }

    /**
     * A repository write made while the screen is open must not leak into the
     * hardest list until ON_RESUME. With the DisposableEffect removed the second
     * question never appears and this test fails.
     */
    @Test
    fun onResumeRefreshesTopListsAfterARepositoryWrite() {
        runBlocking { attempt(question("uihist-alpha", "ONRESUME ALPHA?"), 3_000L) }

        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        // STARTED so collectAsStateWithLifecycle observes attempts (the screen
        // must not show the empty state), but not yet RESUMED.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner.registry.currentState = Lifecycle.State.STARTED
        }
        val vm = HistoryViewModel(app)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HistoryScreen(
                    repository = repository,
                    navController = rememberNavController(),
                    viewModel = vm
                )
            }
        }

        // The ViewModel's init read already picked up the first attempt.
        waitFor("ONRESUME ALPHA")
        compose.onNodeWithText("ONRESUME ALPHA?").assertIsDisplayed()

        // A study session elsewhere adds a second hardest question. No flow
        // drives the top lists, so without a resume it must stay invisible.
        runBlocking { attempt(question("uihist-beta", "ONRESUME BETA?"), 4_000L) }
        assertTrue(
            "no refresh before ON_RESUME",
            compose.onAllNodesWithText("ONRESUME BETA?", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        waitFor("ONRESUME BETA")
        compose.onNodeWithText("ONRESUME BETA?").assertIsDisplayed()
    }

    private companion object {
        const val PAPER = "uihist-paper"
        const val CAT = "uihist-cat"
        const val TITLE = "UITest History Title"
    }
}
