package com.mcqapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.AttemptMetadata
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.signals.Composition
import com.mcqapp.domain.signals.FillSource
import com.mcqapp.ui.results.ResultsScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The practice result page on the CI emulator: the practice headline, the
 * per-question "why this matters" tag and the Study badge. The pure rules are
 * pinned in TestReportBuilderTest; this test proves the page renders them.
 */
@RunWith(AndroidJUnit4::class)
class ResultsPracticeUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    private val categoryId = "uitest-res-cat"
    private val paperId = "uitest-res"
    private var attemptId = 0L

    private fun question(index: Int) = Question(
        id = "uitest-res-q$index",
        categoryId = categoryId,
        text = "UIRes question $index?",
        options = listOf(
            QuestionOption("uitest-res-q$index-a", "Alpha"),
            QuestionOption("uitest-res-q$index-b", "Beta")
        ),
        correctOptionIds = setOf("uitest-res-q$index-a")
    )

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(paperId, "UIRes Paper", categoryId, "UIRes Cat")
        val questions = (1..20).map { question(it) }
        questions.forEach { repository.saveQuestion(it) }
        // 12 correct, 4 wrong, 4 skipped; a practice plan needs a full-length
        // attempt or the report is SHORT rather than PRACTICE.
        val selections = questions.mapIndexed { index, item ->
            item.id to when {
                index < 12 -> setOf("${item.id}-a")
                index < 16 -> setOf("${item.id}-b")
                else -> emptySet()
            }
        }.toMap()
        attemptId = repository.saveAttempt(
            paperId = paperId,
            paperTitle = "UIRes Paper",
            questions = questions,
            selections = selections,
            negativeMarking = 0.0,
            durationSeconds = 900,
            finishedAt = System.currentTimeMillis(),
            dwellSeconds = questions.associate { it.id to 40L },
            metadata = AttemptMetadata(
                testType = TestType.WEAKEST_TOPICS.name,
                requestedCount = 20,
                scopeCategoryIds = categoryId,
                scopeKey = ScopeKey.ofExpanded(listOf(categoryId)),
                samplingVersion = TestPlan.SAMPLING_VERSION,
                exclusionMode = "NONE",
                targetTopicIds = categoryId,
                compositionJson = Composition.of(
                    FillSource.WEAKEST_TOPICS to 20
                ).toJson()
            )
        )
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper(paperId)
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Several review rows can carry the same tag, so assert any one is shown. */
    private fun assertAnyText(text: String) {
        assertTrue(
            "expected at least one '$text' node",
            compose.onAllNodesWithText(text, substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun practiceResultsShowTheHeadlineTagAndStudyBadge() {
        compose.setContent {
            ResultsScreen(
                repository = repository,
                attemptId = attemptId,
                navController = rememberNavController(),
                reviewMode = true
            )
        }

        // The practice headline leads with the recorded target, not the score.
        waitFor("Targeted practice")
        compose.onNodeWithText("Targeted practice", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Weakest topics", substring = true).assertIsDisplayed()

        // The review list is below the header cards: scroll to the tags.
        compose.onNodeWithTag("results-list").performScrollToNode(hasText("Solid correct"))
        assertAnyText("Solid correct")
        // No card was ever written for these questions, and the page says so
        // instead of implying a memory estimate.
        assertAnyText("No Study history")

        compose.onNodeWithTag("results-list").performScrollToNode(hasText("Knowledge gap"))
        assertAnyText("Knowledge gap")

        compose.onNodeWithTag("results-list").performScrollToNode(
            hasText("Targeted · this question came from a practice target")
        )
        assertTrue(
            compose.onAllNodesWithText(
                "Targeted · this question came from a practice target",
                substring = false
            ).fetchSemanticsNodes().isNotEmpty()
        )
    }
}
