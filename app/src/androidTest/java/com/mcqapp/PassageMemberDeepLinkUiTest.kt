package com.mcqapp

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Passage
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.editor.PassagesScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A passage's member rows (runs on the CI emulator).
 *
 * Two things the reader hit: the row rendered the elements JSON instead of the
 * stem, and tapping it opened the editor rather than the question in its paper.
 * The member row now reads as text and lands on the question in Browse, the
 * same arrival a search hit gets.
 */
@RunWith(AndroidJUnit4::class)
class PassageMemberDeepLinkUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository

    @Before
    fun seed() = runBlocking {
        wipe()
        repository.ensurePaperAndCategory(PAPER, "UITest Passage Link", CATEGORY, "Only Cat")
        repository.savePassage(Passage(id = PASSAGE, categoryId = CATEGORY, title = "Reading"))
        repository.saveQuestion(
            Question(
                id = QUESTION,
                categoryId = CATEGORY,
                passageId = PASSAGE,
                elements = listOf(ContentElement.TextElement("<b>Marked</b> read stem")),
                options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
                correctOptionIds = setOf("a")
            )
        )
        repository.saveQuestion(
            Question(
                id = LONG_QUESTION,
                categoryId = CATEGORY,
                passageId = PASSAGE,
                elements = listOf(ContentElement.TextElement(LONG_MEMBER_STEM)),
                options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
                correctOptionIds = setOf("a")
            )
        )
    }

    @After
    fun cleanup() = runBlocking { wipe() }

    /** Idempotent: drops the members first, so the passage can go too. */
    private suspend fun wipe() {
        repository.deletePaper(PAPER)
        repository.deletePassage(PASSAGE)
    }

    private fun showPassages(withBrowseProbe: Boolean = false) {
        compose.setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "passages") {
                composable("passages") {
                    PassagesScreen(repository = repository, navController = navController)
                }
                // The real browse route pattern. The focus argument is the
                // point, so a probe stands in for the screen itself.
                if (withBrowseProbe) {
                    composable("browse/{paperId}?focus={focus}") { entry ->
                        Text(
                            "BROWSE ${entry.arguments?.getString("paperId").orEmpty()} " +
                                entry.arguments?.getString("focus").orEmpty()
                        )
                    }
                }
            }
        }
    }

    /** Open the member list: the button the reader presses. */
    private fun openMemberList() {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Show questions", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Show questions", substring = true).performClick()
    }

    @Test
    fun tappingAMemberOpensBrowseFocusedOnIt() {
        showPassages(withBrowseProbe = true)
        openMemberList()

        // The row shows the stem as text, markup stripped — not the elements
        // JSON, which is what the reader saw before.
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(MEMBER_STEM, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Scroll it in and prove it is on screen before tapping: a touch at an
        // off-window coordinate is dropped without an error, which reads as
        // "tapping a member does nothing".
        compose.onNodeWithText(MEMBER_STEM, substring = true)
            .performScrollTo().assertIsDisplayed()
        // The member's own row is its own tap target, tagged by question id.
        // It used to be one merged node that also held the Unassign button, so
        // a tap aimed at the row's centre could land on that button and open
        // the unassign dialog instead of Browse.
        compose.onNodeWithTag("passage-member-$QUESTION").assertIsDisplayed()
        // The destructive action stays reachable beside the row, not inside
        // it. It is a compact icon button now: its label is spoken, not drawn
        // as a wide TextButton that squeezes the stem.
        compose.onNodeWithTag("passage-unassign-$QUESTION").assertIsDisplayed()
        assertTrue(
            "TalkBack lost the member's unassign label",
            compose.onAllNodesWithContentDescription(
                app.getString(R.string.passage_unassign_member)
            ).fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithTag("passage-member-$QUESTION").performClick()

        val landed = "BROWSE $PAPER $QUESTION"
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(landed, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(landed, substring = true).assertIsDisplayed()
    }

    /**
     * The row's width was eaten by a wide "Remove from passage" TextButton:
     * a long stem read as two truncated words. The unassign control is now a
     * compact icon button, so the stem keeps most of the row.
     */
    @Test
    fun longStemKeepsItsWidthAndUnassignIsAnIconButton() {
        showPassages()
        openMemberList()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("passage-member-$LONG_QUESTION")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("passage-member-$LONG_QUESTION")
            .performScrollTo().assertIsDisplayed()

        val unassign = compose.onNodeWithTag("passage-unassign-$LONG_QUESTION").assertIsDisplayed()
        assertTrue(
            "TalkBack lost the long member's unassign label",
            compose.onAllNodesWithContentDescription(
                app.getString(R.string.passage_unassign_member)
            ).fetchSemanticsNodes().isNotEmpty()
        )
        val stemWidth = compose.onNodeWithTag("passage-member-$LONG_QUESTION")
            .fetchSemanticsNode().size.width
        val actionWidth = unassign.fetchSemanticsNode().size.width
        assertTrue(
            "stem squeezed to $stemWidth px beside a $actionWidth px action",
            stemWidth > actionWidth * 2
        )
        // A TextButton carrying the whole label was hundreds of dp wide;
        // an IconButton stays at the material minimum touch target.
        assertTrue(
            "unassign control is not compact: $actionWidth px",
            actionWidth <= 64f * compose.density.density
        )
        // The visible label is gone; only the spoken description remains.
        assertTrue(
            "the wide unassign label is still drawn",
            compose.onAllNodesWithText(
                app.getString(R.string.passage_unassign_member), substring = false
            ).fetchSemanticsNodes().isEmpty()
        )
    }

    private companion object {
        const val PAPER = "uitest-passage-link"
        const val CATEGORY = "uitest-passage-link-c"
        const val PASSAGE = "uitest-passage-link-p"
        const val QUESTION = "uitest-passage-link-q"
        const val LONG_QUESTION = "uitest-passage-link-long-q"
        const val MEMBER_STEM = "Marked read stem"
        const val LONG_MEMBER_STEM =
            "According to the passage, what kind of energy change does the graph " +
                "show when the figure-dependent velocity doubles under friction?"
    }
}
