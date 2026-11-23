package com.mcqapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.common.PagingErrorRow
import com.mcqapp.ui.search.SearchScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search has no emulator tests. These pin the debounce cue, the exact result
 * count, the blank-query state and the shared append-error retry UI. CI-only:
 * the real repository opens SQLite (x86_64 emulator).
 */
@RunWith(AndroidJUnit4::class)
class SearchUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository

    private fun question(id: String, text: String) = Question(
        id = id,
        categoryId = CAT,
        text = text,
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = setOf("$id-a")
    )

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(PAPER, "UITest Search", CAT, "UITest Search Cat")
        repeat(MANY) { i -> repository.saveQuestion(question("uisearch-many-$i", "MANYSEARCH token $i")) }
        repeat(FEW) { i -> repository.saveQuestion(question("uisearch-few-$i", "FEWSEARCH token $i")) }
    }

    @After
    fun cleanup() = runBlocking { repository.deletePaper(PAPER) }

    private fun open() {
        compose.setContent { SearchScreen(navController = rememberNavController()) }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Only the result-count Text — never a hint or a card — is a valid label. */
    private fun matchLabel(): String? =
        compose.onAllNodesWithText("match", substring = true).fetchSemanticsNodes()
            .mapNotNull { node -> node.config[SemanticsProperties.Text]?.joinToString("") { it.text } }
            .firstOrNull { COUNT_LABEL.matches(it) }

    /**
     * During the debounce the committed results are hidden behind the loading
     * cue; once the first page lands the cue is replaced by the result count.
     * (The current ViewModel compares only the query in `debouncing`, so a
     * scope-only change does NOT show this cue — see the report.)
     */
    @Test
    fun typingShowsTheSearchingCueUntilTheFirstPageLoads() {
        open()
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("MANYSEARCH")

        val cue = app.getString(R.string.searching)
        waitFor(cue)
        compose.onNodeWithText(cue).assertIsDisplayed()

        // The cue is transient: wait for the settled count and assert it is
        // gone. A fixed sleep would race the 300 ms debounce and paging load.
        compose.waitUntil(20_000) { matchLabel() != null }
        assertTrue(
            "the cue must clear once results arrive",
            compose.onAllNodesWithText(cue).fetchSemanticsNodes().isEmpty()
        )
    }

    /** The count is exact for both multi-page and single-page result sets. */
    @Test
    fun countShowsTheExactTotalForAnyResultSize() {
        open()
        compose.onNode(hasSetTextAction()).performTextInput("MANYSEARCH")

        // The total is computed from the same two-phase pipeline on IO, so a
        // result set larger than one page still resolves to an exact count.
        compose.waitUntil(20_000) { matchLabel() == "$MANY matches" }
        assertTrue("a multi-page result set must show its exact total", matchLabel() == "$MANY matches")

        compose.onNodeWithContentDescription(app.getString(R.string.clear_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("FEWSEARCH")

        compose.waitUntil(20_000) { matchLabel() == "$FEW matches" }
        assertTrue("a short result set is exact", matchLabel() == "$FEW matches")
    }

    /** On entry the query is blank: no result count, no false "No matches". */
    @Test
    fun aBlankQueryShowsNeitherResultsNorACount() {
        open()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "blank query must not claim any matches",
            compose.onAllNodesWithText("match", substring = true).fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "blank query must not claim 'no matches'",
            compose.onAllNodesWithText(app.getString(R.string.no_matches))
                .fetchSemanticsNodes().isEmpty()
        )
    }

    /** The append-error row shared with Bookmarks carries a working retry. */
    @Test
    fun appendErrorRowOffersAWorkingRetry() {
        var retried = false
        compose.setContent {
            MaterialTheme {
                PagingErrorRow(
                    messageRes = R.string.paging_append_failed,
                    onRetry = { retried = true }
                )
            }
        }
        compose.onNodeWithText(app.getString(R.string.paging_append_failed)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.retry)).performClick()
        assertTrue("Retry must invoke the append retry action", retried)
    }

    private companion object {
        const val PAPER = "uisearch-paper"
        const val CAT = "uisearch-cat"
        /**
         * More than one 200-row candidate window, so the exact total has to
         * come from the full two-phase count rather than a single window.
         */
        const val MANY = 260
        const val FEW = 3
        val COUNT_LABEL = Regex("""^\d+ matches?$""")
    }
}
