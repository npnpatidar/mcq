package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.navigation.McqNavHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The duplicates dialog used one non-wrapping Row for every keeper chip plus
 * Merge and Delete. With several duplicates the actions were pushed off the
 * right edge of a phone. They now sit on their own row below the wrapping
 * chips and must stay on screen.
 */
@RunWith(AndroidJUnit4::class)
class DuplicatesDialogUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository
    private val paperId = "ui-dupes"

    @After
    fun cleanup() = runBlocking { repository.deletePaper(paperId) }

    /**
     * A block body on purpose: `= runBlocking { ... }` as an expression body
     * infers the lambda's non-Unit value, and JUnit then refuses the whole
     * class with "should be void" instead of running this test.
     */
    @Test
    fun mergeAndDeleteStayVisibleWithManyKeeperChips() {
        runBlocking {
            repository.ensurePaperAndCategory(paperId, "UIDupes", "$paperId-cat", "Cat")
            repeat(8) { i ->
                repository.saveQuestion(
                    Question(
                        id = "$paperId-q$i",
                        categoryId = "$paperId-cat",
                        text = "Same duplicate question?",
                        options = listOf(
                            QuestionOption("$paperId-a", "Alpha"),
                            QuestionOption("$paperId-b", "Beta")
                        ),
                        correctOptionIds = setOf("$paperId-a")
                    )
                )
            }
        }
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-browse-$paperId").performClick()
        compose.onNodeWithText(app.getString(R.string.duplicates_title)).performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("duplicates-keeper-chip-7")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Both destructive/merge actions sit on their own row below the chips.
        compose.onNodeWithTag("duplicates-merge").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("duplicates-delete-others").performScrollTo().assertIsDisplayed()

        // One way out, not two identical ones: "Done" closes the dialog and a
        // second "Cancel" beside it said the same thing twice. Back/outside tap
        // still dismisses through onDismissRequest.
        compose.onNodeWithTag("duplicates-done").performScrollTo().assertIsDisplayed()
        val exitActions = compose.onAllNodesWithTag("duplicates-done").fetchSemanticsNodes().size
        assertTrue("the dialog should offer exactly one exit action, found $exitActions", exitActions == 1)
        assertTrue(
            "a second (Cancel) exit is still rendered",
            compose.onAllNodesWithText(app.getString(R.string.cancel), substring = false)
                .fetchSemanticsNodes().isEmpty()
        )
    }
}
