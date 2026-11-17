package com.mcqapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.stats.StatsScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The nine Stats tabs on the CI emulator. Each tab builds its own slice on a
 * background dispatcher behind the screen-level spinner; this test waits for
 * the spinner to leave before moving on, so a tab whose aggregator throws (or
 * whose build never publishes) fails instead of being silently skipped.
 */
@RunWith(AndroidJUnit4::class)
class StatsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository
    private val paperId = "uitest-stats-paper"
    private val categoryId = "uitest-stats-cat"

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(paperId, "Stats Paper", categoryId, "Stats Cat")
        val question = Question(
            id = "uitest-stats-q1",
            categoryId = categoryId,
            text = "Stats question?",
            options = listOf(
                QuestionOption("uitest-stats-q1-a", "Alpha"),
                QuestionOption("uitest-stats-q1-b", "Beta")
            ),
            correctOptionIds = setOf("uitest-stats-q1-a")
        )
        repository.saveQuestion(question)
        repeat(2) {
            repository.recordStudyReview(
                paperId = paperId,
                questionId = question.id,
                grade = ReviewGrade.GOOD,
                dwellSeconds = 30L
            )
        }
        repository.clearTestProgress()
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper(paperId)
    }

    private fun spinnerVisible(): Boolean =
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .fetchSemanticsNodes().isNotEmpty()

    private fun hasTextNode(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = false)
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun allNineTabsRenderWithoutCrash() {
        val labels = listOf(
            app.getString(R.string.tab_overview),
            app.getString(R.string.tab_retention),
            app.getString(R.string.tab_workload),
            app.getString(R.string.tab_sessions),
            app.getString(R.string.tab_memory),
            app.getString(R.string.tab_cards),
            app.getString(R.string.tab_guessing),
            app.getString(R.string.tab_tests),
            app.getString(R.string.tab_progress)
        )
        val loadError = app.getString(R.string.stats_load_error)

        compose.setContent {
            MaterialTheme {
                StatsScreen(repository = repository, navController = rememberNavController())
            }
        }
        // The first slice starts on Dispatchers.Default; wait on semantics, not
        // on the idle clock (an indeterminate spinner never lets it settle).
        compose.waitUntil(30_000) { !spinnerVisible() }

        labels.forEach { label ->
            // Several nodes can carry a tab label (the tab itself and a chart
            // title that reuses the same word). The tab row is composed before
            // the section body, so its node is the first match.
            compose.onAllNodesWithText(label, substring = false)
                .also { check(it.fetchSemanticsNodes().isNotEmpty()) { "no tab '$label'" } }[0]
                .performScrollTo().performClick()
            compose.waitUntil(30_000) {
                !spinnerVisible() && hasTextNode(label) &&
                    compose.onAllNodesWithText(loadError, substring = true)
                        .fetchSemanticsNodes().isEmpty()
            }
            // A build that throws publishes loadError instead of the section;
            // a slice that never publishes leaves the spinner up (timeout).
            assertFalse(
                "tab $label rendered the load error",
                compose.onAllNodesWithText(loadError, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            )
        }
        assertTrue("the last tab label is still on screen", hasTextNode(labels.last()))
    }
}
