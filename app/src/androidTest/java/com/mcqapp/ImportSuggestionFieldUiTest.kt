package com.mcqapp

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.ui.importscreen.SuggestionTextField
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * H7 regression for the import paper/category fields (runs on the CI emulator).
 *
 * These fields are editable, so their anchor must be `PrimaryEditable`: when a
 * keystroke expands the menu the popup stays non-focusable and focus (plus the
 * IME) remains on the field, so a name that is not in the suggestions can still
 * be typed in full. With `PrimaryNotEditable` the focusable popup steals focus
 * the moment typing expands it, and the rest of a novel name never reaches the
 * field. See https://developer.android.com/reference/kotlin/androidx/compose/material3/ExposedDropdownMenuAnchorType
 */
@RunWith(AndroidJUnit4::class)
class ImportSuggestionFieldUiTest {

    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    private fun setField(suggestions: List<String>, label: String = "Paper title") {
        val value = mutableStateOf("")
        compose.setContent {
            SuggestionTextField(
                label = label,
                value = value.value,
                onValueChange = { value.value = it },
                suggestions = suggestions
            )
        }
    }

    /**
     * Types a name that is not in the suggestion list and checks the whole
     * string survives and the field keeps focus while the menu is open. Value
     * retention alone passes on the old code (the test injects text through
     * semantics, not the IME); the focus check is what fails there, because
     * `PrimaryNotEditable` makes the expanded popup steal focus from the field.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun typingANovelNameKeepsFocusAndRetainsTheWholeValue() {
        setField(suggestions = listOf("Existing Paper"))
        compose.waitForIdle()

        val field = compose.onNode(hasSetTextAction())
        // Poll focused+SetText rather than asserting once: the popup's focus
        // request runs asynchronously and a single check could race it.
        val focusedField = hasSetTextAction() and
            SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)
        fun fieldStillFocused() =
            compose.onAllNodes(focusedField).fetchSemanticsNodes().isNotEmpty()

        // First keystroke mirrors the real IME: it expands the menu. Focus must
        // stay on the field before the rest of the name is injected.
        field.performTextInput("B")
        compose.waitUntil(10_000) { fieldStillFocused() }

        // The focused node must be the editable field, not the dropdown popup.
        val focusedNodes = compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)
        ).fetchSemanticsNodes()
        assertTrue(
            "the text field must hold focus, not the suggestion popup",
            focusedNodes.isNotEmpty() && focusedNodes.all { hasSetTextAction().matches(it) }
        )

        // Continue typing while the menu is open, then assert every character.
        field.performTextInput("rand-new paper 2026")
        field.assertTextContains("Brand-new paper 2026")
        compose.onNodeWithText("Brand-new paper 2026").assertExists()
        compose.waitUntil(10_000) { fieldStillFocused() }
    }

    /** The fix must not break the normal suggestion flow. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun suggestionsStillAppearAndCanBeSelected() {
        setField(suggestions = listOf("Existing Category"), label = "Category name")
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).performTextInput("Exist")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Existing Category").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Existing Category").performClick()
        compose.waitForIdle()

        // Selecting a row fills the field and closes the menu.
        compose.onNode(hasSetTextAction()).assertTextContains("Existing Category")
    }
}
