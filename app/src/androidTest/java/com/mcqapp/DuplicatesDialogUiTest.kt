package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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

    @Test
    fun mergeAndDeleteStayVisibleWithManyKeeperChips() = runBlocking {
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
        compose.setContent { McqNavHost(repository = repository) }
        compose.onNodeWithTag("paper-browse-$paperId").performClick()
        compose.onNodeWithText(app.getString(R.string.duplicates_title)).performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("duplicates-keeper-chip-7")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The keeper preview in this label is dynamic; match the fixed prefix.
        val mergePrefix = app.getString(R.string.duplicates_merge_history_into, "").trimEnd()
        compose.onNodeWithText(mergePrefix, substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            app.getString(R.string.duplicates_delete_others),
            substring = false
        ).performScrollTo().assertIsDisplayed()
    }
}
