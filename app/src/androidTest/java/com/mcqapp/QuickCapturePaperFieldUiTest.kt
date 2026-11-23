package com.mcqapp

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.ui.library.QuickCaptureDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * INCIDENT: with no paper selected the paper dropdown became editable and was
 * bound to the same `newPaperTitle` as the dedicated new-paper field, so the
 * dialog showed two identically labelled "Paper" editors for one value. The
 * dropdown must stay a selector; only `quick-capture-new-paper` takes typing.
 */
@RunWith(AndroidJUnit4::class)
class QuickCapturePaperFieldUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setDialog() {
        compose.setContent {
            QuickCaptureDialog(
                papers = emptyList(),
                sharedText = "Shared front",
                onSave = { _, _, _, _, _, _, _ -> },
                onDismiss = {}
            )
        }
    }

    @Test
    fun withNoPaperChosenTheDropdownIsReadOnlyAndOnlyTheNewPaperFieldAcceptsText() {
        setDialog()
        compose.waitForIdle()

        assertFalse(
            "the paper dropdown is a selector, not a second editor",
            hasSetTextAction().matches(
                compose.onNodeWithTag("quick-capture-paper").fetchSemanticsNode()
            )
        )
        assertTrue(
            "the dedicated new-paper field must still be editable",
            hasSetTextAction().matches(
                compose.onNodeWithTag("quick-capture-new-paper").fetchSemanticsNode()
            )
        )
    }

    @Test
    fun typingANewPaperNameDoesNotEchoItIntoTheSelector() {
        setDialog()
        compose.waitForIdle()

        compose.onNodeWithTag("quick-capture-new-paper").performTextInput("My new paper")

        val selectorText = compose.onNodeWithTag("quick-capture-paper")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertNotEquals("the two fields must not share one value", "My new paper", selectorText)
    }
}
