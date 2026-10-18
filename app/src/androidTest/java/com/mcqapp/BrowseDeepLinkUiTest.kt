package com.mcqapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
 * The deep link into Browse that search and bookmarks use (runs on the CI
 * emulator).
 *
 * Browse is a paging window, so on the first composition the list holds no rows
 * at all, and a question far down a paper is not among the rows the window ever
 * loads by itself. The screen has to wait for the list and then let paging jump
 * to a row it has not loaded — otherwise the reader is left at the top of the
 * paper instead of on the question they picked, which is the bug this pins.
 */
@RunWith(AndroidJUnit4::class)
class BrowseDeepLinkUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    @Before
    fun seed() = runBlocking {
        // Ids unique to this class: instrumented classes share the app database.
        repository.ensurePaperAndCategory(BIG_PAPER, "UITest Deep Link", BIG_CAT, "Only Cat")
        for (i in 1..BIG_COUNT) {
            repository.saveQuestion(question("$BIG_PAPER-q$i", BIG_CAT, "Q$i"))
        }
        repository.ensurePaperAndCategory(SMALL_PAPER, "UITest Deep Link Small", SMALL_CAT, "Only Cat")
        for (i in 1..SMALL_COUNT) {
            repository.saveQuestion(question("$SMALL_PAPER-q$i", SMALL_CAT, "S$i"))
        }
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper(BIG_PAPER)
        repository.deletePaper(SMALL_PAPER)
    }

    private fun question(id: String, categoryId: String, text: String) = Question(
        id = id,
        categoryId = categoryId,
        elements = listOf(ContentElement.TextElement(text)),
        options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
        correctOptionIds = setOf("a")
    )

    private fun openFocused(paperId: String, questionId: String) {
        compose.setContent {
            BrowseScreen(
                repository = repository,
                paperId = paperId,
                focusQuestionId = questionId,
                navController = rememberNavController()
            )
        }
    }

    /**
     * Row [FAR] is past the two pages the list loads to begin with, so it is
     * not merely below the viewport — it is not in the list at all until paging
     * is asked to jump to it.
     */
    @Test
    fun aDeepLinkLandsOnAQuestionTheFirstPagesDoNotHold() {
        openFocused(BIG_PAPER, "$BIG_PAPER-q$FAR")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Q$FAR", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Q$FAR", substring = true).assertIsDisplayed()
        // Numbered by its place in the paper, not by its place in the window:
        // that is what placeholders give the list, and it is why the same
        // number is on screen as in the editor's "N of M".
        compose.onNodeWithText("$FAR.").assertIsDisplayed()
    }

    /**
     * The same deep link inside the first page still has to wait: the list does
     * not exist until its first page arrives, so a scroll asked for on the
     * first composition is asked of nothing.
     */
    @Test
    fun aDeepLinkLandsOnAQuestionInsideTheFirstPage() {
        openFocused(SMALL_PAPER, "$SMALL_PAPER-q$NEAR")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("S$NEAR", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithText("S$NEAR", substring = true).assertIsDisplayed()
        compose.onNodeWithText("$NEAR.").assertIsDisplayed()
    }

    private companion object {
        const val BIG_PAPER = "uitest-deeplink-paper"
        const val BIG_CAT = "uitest-deeplink-c"
        const val BIG_COUNT = 200

        /** Past initialLoadSize (120): must be jumped to, not scrolled past. */
        const val FAR = 151

        const val SMALL_PAPER = "uitest-deeplink-small"
        const val SMALL_CAT = "uitest-deeplink-small-c"
        const val SMALL_COUNT = 30

        /** On the first page, but below the viewport of an un-scrolled list. */
        const val NEAR = 20
    }
}
