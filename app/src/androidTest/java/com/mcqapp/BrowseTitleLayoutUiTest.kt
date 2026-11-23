package com.mcqapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.Paper
import com.mcqapp.ui.browse.BrowseScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Browse top bar on a narrow screen (runs on the CI emulator).
 *
 * An unbounded title wrapped into a column of broken words beside the actions.
 * At 320 dp neither the title nor the actions fit unless the title is one
 * ellipsized line, and TalkBack must still hear the whole paper name.
 */
@RunWith(AndroidJUnit4::class)
class BrowseTitleLayoutUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    @Before
    fun seed() = runBlocking {
        repository.savePaper(Paper(id = PAPER, title = LONG_TITLE))
    }

    @After
    fun cleanup() = runBlocking { repository.deletePaper(PAPER) }

    @Test
    fun longTitleStaysOnOneLineAndTheActionsRemainOnScreen() {
        compose.setContent {
            // The owner's 320 dp screenshot width, independent of the device
            // the test runs on.
            Box(Modifier.width(320.dp).height(640.dp)) {
                BrowseScreen(
                    repository = repository,
                    paperId = PAPER,
                    navController = rememberNavController()
                )
            }
        }

        // The content description is the uncut title, so waiting on it also
        // waits for the paper to load instead of catching the fallback label.
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription(LONG_TITLE)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val title = compose.onNodeWithTag("browse-title").assertIsDisplayed()
        title.assertContentDescriptionEquals(LONG_TITLE)

        // The two actions and Back are what the owner could no longer reach.
        // maxLines/ellipsis themselves are pinned by the source-level guard:
        // this Compose BOM exposes no public layout-result assertion.
        compose.onNodeWithText(app.getString(R.string.duplicates_title)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.select)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.back)).assertIsDisplayed()
    }

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication

    private companion object {
        const val PAPER = "uitest-long-title-paper"
        const val LONG_TITLE =
            "Sample Bank — figure-dependent questions (3) with an unusually long " +
                "descriptive suffix for narrow screens"
    }
}
