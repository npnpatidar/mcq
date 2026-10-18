package com.mcqapp

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

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
    }

    @After
    fun cleanup() = runBlocking { wipe() }

    /** Idempotent: drops the members first, so the passage can go too. */
    private suspend fun wipe() {
        repository.deletePaper(PAPER)
        repository.deletePassage(PASSAGE)
    }

    @Test
    fun tappingAMemberOpensBrowseFocusedOnIt() {
        compose.setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "passages") {
                composable("passages") {
                    PassagesScreen(repository = repository, navController = navController)
                }
                // The real browse route pattern. The focus argument is the
                // point, so a probe stands in for the screen itself.
                composable("browse/{paperId}?focus={focus}") { entry ->
                    Text(
                        "BROWSE ${entry.arguments?.getString("paperId").orEmpty()} " +
                            entry.arguments?.getString("focus").orEmpty()
                    )
                }
            }
        }

        // Open the member list: the button the reader presses.
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Show questions", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Show questions", substring = true).performClick()

        // The row shows the stem as text, markup stripped — not the elements
        // JSON, which is what the reader saw before.
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(MEMBER_STEM, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(MEMBER_STEM, substring = true).performClick()

        val landed = "BROWSE $PAPER $QUESTION"
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(landed, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(landed, substring = true).assertIsDisplayed()
    }

    private companion object {
        const val PAPER = "uitest-passage-link"
        const val CATEGORY = "uitest-passage-link-c"
        const val PASSAGE = "uitest-passage-link-p"
        const val QUESTION = "uitest-passage-link-q"
        const val MEMBER_STEM = "Marked read stem"
    }
}
