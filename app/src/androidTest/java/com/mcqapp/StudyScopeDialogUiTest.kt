package com.mcqapp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.data.local.TagCount
import com.mcqapp.ui.study.StudyScopeDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A TAG scope with no tag selected used to leave Start enabled but make its
 * click handler a no-op — a visible control that did nothing. The button must
 * look disabled and the tap must not fire any scope callback.
 *
 * The fake tag loader hands back real [TagCount]s: with an empty list the TAG
 * branch renders its "no tags" line and the "a tag was picked" half of the
 * contract can never be exercised (which is how this test passed while the
 * enabled path was untested).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class StudyScopeDialogUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val tags = listOf(
        TagCount(tag = "Algebra", tagFolded = "algebra", count = 4),
        TagCount(tag = "Geometry", tagFolded = "geometry", count = 2)
    )

    private fun setDialog(
        onPickWhole: () -> Unit = {},
        onPickTag: (String) -> Unit = {},
        onPickMistakes: () -> Unit = {},
        loadTags: suspend (String, List<String>) -> List<TagCount> = { _, _ -> tags }
    ) {
        compose.setContent {
            StudyScopeDialog(
                paperId = "p",
                categoryIds = emptyList(),
                currentTag = "",
                currentMistakes = false,
                loadTags = loadTags,
                onPickWhole = onPickWhole,
                onPickTag = onPickTag,
                onPickMistakes = onPickMistakes,
                onDismiss = {}
            )
        }
    }

    @Test
    fun startIsVisiblyDisabledAndDoesNothingWhenTagScopeHasNoTag() {
        var wholeCalls = 0
        var tagCalls = 0
        setDialog(onPickWhole = { wholeCalls++ }, onPickTag = { tagCalls++ })
        compose.waitForIdle()

        compose.onNodeWithTag("study-scope-tag").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("study-scope-start").assertIsNotEnabled()

        compose.onNodeWithTag("study-scope-start").performClick()
        compose.waitForIdle()
        assertFalse("no tag means no study scope was chosen", tagCalls > 0)
        assertFalse("a TAG tap must not silently start the whole paper", wholeCalls > 0)
    }

    @Test
    fun startIsEnabledForWholeAndMistakesAndForATagWithASelection() {
        var wholeCalls = 0
        var mistakesCalls = 0
        var picked = ""
        setDialog(
            onPickWhole = { wholeCalls++ },
            onPickTag = { picked = it },
            onPickMistakes = { mistakesCalls++ }
        )
        compose.waitForIdle()

        compose.onNodeWithTag("study-scope-start").assertIsEnabled()
        compose.onNodeWithTag("study-scope-start").performClick()
        compose.waitForIdle()
        assertEquals("Whole scope must still start", 1, wholeCalls)

        compose.onNodeWithTag("study-scope-mistakes").performClick()
        compose.onNodeWithTag("study-scope-start").assertIsEnabled()
        compose.onNodeWithTag("study-scope-start").performClick()
        compose.waitForIdle()
        assertEquals("Mistakes scope must start", 1, mistakesCalls)

        // A tag with a selection: Start is enabled and hands back the folded
        // tag identity the pool query uses.
        compose.onNodeWithTag("study-scope-tag").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("study-scope-tag-algebra").performClick()
        compose.onNodeWithTag("study-scope-start").assertIsEnabled()
        compose.onNodeWithTag("study-scope-start").performClick()
        compose.waitForIdle()
        assertEquals("algebra", picked)
        assertTrue("a tag pick must not start the whole paper", wholeCalls == 1)
    }
}
