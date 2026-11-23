package com.mcqapp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.ui.library.QuickCaptureDialog
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
                onSave = { _, _, _, _, _, _, _ -> null },
                onSaved = {},
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
}
