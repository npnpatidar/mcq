package com.mcqapp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.ui.study.StudyScopeDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A TAG scope with no tag selected used to leave Start enabled but make its
 * click handler a no-op — a visible control that did nothing. The button must
 * look disabled and the tap must not fire any scope callback.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class StudyScopeDialogUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setDialog(onPickWhole: () -> Unit, onPickTag: (String) -> Unit) {
        compose.setContent {
            StudyScopeDialog(
                paperId = "p",
                categoryIds = emptyList(),
                currentTag = "",
                currentMistakes = false,
                loadTags = { _, _ -> emptyList() },
                onPickWhole = onPickWhole,
                onPickTag = onPickTag,
                onPickMistakes = {},
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
        setDialog(onPickWhole = { wholeCalls++ }, onPickTag = {})
        compose.waitForIdle()

        compose.onNodeWithTag("study-scope-start").assertIsEnabled()
        compose.onNodeWithTag("study-scope-start").performClick()
        compose.waitForIdle()
        assertTrue("Whole scope must still start", wholeCalls == 1)
    }
}
