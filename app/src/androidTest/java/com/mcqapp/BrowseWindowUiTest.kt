package com.mcqapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.browse.BrowseScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Browse reads the rows around the viewport ahead of time and drops the ones it
 * has left behind, so a paper of any length costs the window, not the paper.
 *
 * This pins the half of that bargain that is easy to get wrong: a card that was
 * evicted and then scrolled back to has to be *read again*. The cache kept the
 * hash of every card it had ever read but the prune dropped only the cards, so
 * a row coming back answered "hash unchanged, nothing to read" while its card
 * was gone — the row stayed on its placeholder and the options never came back.
 * That is what "scroll down and up again, the options are missing" was.
 *
 * Runs on the CI emulator; the paper is longer than a window so scrolling to the
 * end really does evict the first rows.
 */
@RunWith(AndroidJUnit4::class)
class BrowseWindowUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(PAPER, "UITest Window", CATEGORY, "Only Cat")
        for (i in 1..COUNT) {
            repository.saveQuestion(
                Question(
                    id = "$PAPER-q$i",
                    categoryId = CATEGORY,
                    elements = listOf(ContentElement.TextElement("W$i")),
                    // Option text unique to the row, so "this row's options are
                    // on screen" is checkable.
                    options = listOf(
                        QuestionOption("a", "opt-$i-a"),
                        QuestionOption("b", "opt-$i-b")
                    ),
                    correctOptionIds = setOf("a")
                )
            )
        }
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper(PAPER)
    }

    private fun open() {
        compose.setContent {
            BrowseScreen(
                repository = repository,
                paperId = PAPER,
                navController = rememberNavController()
            )
        }
    }

    /** Lazy index 0 is the controls header, so question n is at index n. */
    private fun scrollToQuestion(index: Int) {
        compose.onNode(hasScrollAction()).performScrollToIndex(index)
        compose.waitForIdle()
    }

    private fun awaitOption(text: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aCardReadsItsOptionsAgainAfterScrollingAwayAndBack() {
        open()
        awaitOption("opt-1-a")

        // Far enough that the first rows are outside the window and get pruned.
        scrollToQuestion(COUNT)
        awaitOption("opt-$COUNT-a")

        scrollToQuestion(1)
        // The read this asserts used to be skipped: the row came back to a cache
        // that still held its hash, so it kept showing the placeholder.
        awaitOption("opt-1-a")
        compose.onNodeWithText("opt-1-b").assertIsDisplayed()
    }

    /**
     * The row that is still on screen when the window moves must keep its card:
     * the eviction is by window, and a clamp that kept only the window's *ids*
     * without re-reading would also blank the row the reader is looking at.
     */
    @Test
    fun theRowBeingReadKeepsItsOptionsWhileTheListScrolls() {
        open()
        awaitOption("opt-1-a")
        scrollToQuestion(MID)
        awaitOption("opt-$MID-a")
        compose.onNodeWithText("opt-$MID-a").assertIsDisplayed()
    }

    private companion object {
        const val PAPER = "uitest-window-paper"
        const val CATEGORY = "uitest-window-c"

        /** Well past a window (viewport + 12 rows each side). */
        const val COUNT = 120

        const val MID = 60
    }
}
