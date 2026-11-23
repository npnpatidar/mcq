package com.mcqapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.ui.library.QuickCaptureDialog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Quick capture's text fields used plain remember, so a rotation wiped what
 * the learner typed; a second Save then wrote a duplicate card. Every field
 * must survive saved-state restoration (the dialog itself stays owned by the
 * NavHost's saveable state).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class QuickCaptureRotationUiTest {

    @Test
    fun rotationKeepsEveryTypedQuickCaptureField() = runComposeUiTest {
        val restoration = StateRestorationTester(this)
        restoration.setContent {
            QuickCaptureDialog(
                papers = emptyList(),
                sharedText = "Shared front",
                onSave = { _, _, _, _, _, _, _ -> },
                onDismiss = {}
            )
        }
        onNodeWithTag("quick-capture-front").performTextReplacement("Typed front")
        onNodeWithTag("quick-capture-back").performTextInput("Typed back")
        onNodeWithTag("quick-capture-tag").performTextInput("Typed tag")
        onNodeWithTag("quick-capture-new-paper").performTextInput("My paper")
        onNodeWithTag("quick-capture-category").performTextInput("My category")
        waitForIdle()

        restoration.emulateSaveAndRestore()
        waitForIdle()

        onNodeWithTag("quick-capture-front").assertTextContains("Typed front")
        onNodeWithTag("quick-capture-back").assertTextContains("Typed back")
        onNodeWithTag("quick-capture-tag").assertTextContains("Typed tag")
        onNodeWithTag("quick-capture-new-paper").assertTextContains("My paper")
        onNodeWithTag("quick-capture-category").assertTextContains("My category")
    }

    /**
     * The NavHost used to clear the offered snippet only after the dialog's
     * suspend write returned. A rotation mid-write killed the dialog, so the
     * callback never fired, the snippet stayed queued and a second Save wrote
     * a duplicate card. Now the snippet is cleared on the tap and the
     * completion is a one-shot event from the ViewModel.
     */
    @Test
    fun saveOnTapClosesTheOfferSoRotationCannotSaveTwice() = runComposeUiTest {
        val restoration = StateRestorationTester(this)
        var offeredText by mutableStateOf<String?>("Shared front")
        var saveCalls = 0
        var completionEvents = 0
        restoration.setContent {
            val snippet = offeredText
            if (snippet != null) {
                QuickCaptureDialog(
                    papers = emptyList(),
                    sharedText = snippet,
                    onSave = { _, _, _, _, _, _, _ ->
                        saveCalls++
                        offeredText = null // NavHost clears on the tap
                        completionEvents++ // stands in for the VM's buffered event
                    },
                    onDismiss = { offeredText = null }
                )
            }
        }

        onNodeWithTag("quick-capture-save").performClick()
        waitForIdle()
        assertEquals(1, saveCalls)
        assertEquals(1, completionEvents)
        onNodeWithTag("quick-capture-front").assertDoesNotExist()

        restoration.emulateSaveAndRestore()
        waitForIdle()
        assertEquals("rotation must not re-offer a snippet whose Save was tapped", 1, saveCalls)
        assertEquals("completion must be reported exactly once", 1, completionEvents)
        onNodeWithTag("quick-capture-front").assertDoesNotExist()
    }
}
