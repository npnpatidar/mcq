package com.mcqapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.ui.settings.AiSchemaScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The AI schema action row on the owner's 320 dp screen (runs on the CI
 * emulator). Share used to be squeezed into a narrow pill with one letter per
 * line; all three actions must stay horizontal and reachable.
 */
@RunWith(AndroidJUnit4::class)
class AiSchemaActionLayoutUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication

    @Test
    fun allThreeActionsStayHorizontalAndShareIsTappable() {
        compose.setContent {
            Box(Modifier.width(320.dp).height(640.dp)) {
                AiSchemaScreen(navController = rememberNavController())
            }
        }
        // The action row sits below long output cards: scroll the last one in,
        // which brings the whole two-row block into the viewport.
        compose.onNodeWithTag("ai-schema-share").performScrollTo()
        compose.waitForIdle()
        val tags = listOf("ai-schema-copy-prompt", "ai-schema-save", "ai-schema-share")
        tags.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }

        val copy = compose.onNodeWithTag("ai-schema-copy-prompt").getBoundsInRoot()
        val save = compose.onNodeWithTag("ai-schema-save").getBoundsInRoot()
        val share = compose.onNodeWithTag("ai-schema-share").getBoundsInRoot()
        tags.zip(listOf(copy, save, share)).forEach { (tag, bounds) ->
            assertTrue("$tag squeezed to ${bounds.w()}", bounds.w() >= 96.dp)
            assertTrue("$tag stacked its letters vertically", bounds.w() > bounds.h())
        }
        assertTrue(
            "Copy prompt and Save schema must share a row",
            abs(copy.top.value - save.top.value) < 1f
        )
        assertTrue("Share must sit on its own full-width row", share.top > copy.bottom)
        // Click action, not a chooser launch: the test proves reachability
        // without starting an external activity.
        compose.onNodeWithTag("ai-schema-share")
            .assertIsEnabled()
            .assertHasClickAction()
        // The removed wrapper choices must not reintroduce a second,
        // conflicting container control.
        listOf(
            R.string.ai_schema_wrapper,
            R.string.ai_schema_wrapper_full,
            R.string.ai_schema_wrapper_paperless,
            R.string.ai_schema_passage_none
        ).forEach { res ->
            assertTrue(
                "obsolete choice still shown: ${app.getString(res)}",
                compose.onAllNodesWithText(app.getString(res)).fetchSemanticsNodes().isEmpty()
            )
        }
    }

    /**
     * Each container toggle changes the generated schema on its own: none of
     * them is a decorative switch wired to the same fixed options.
     */
    @Test
    fun eachContainerToggleChangesTheGeneratedSchema() {
        compose.setContent {
            Box(Modifier.width(320.dp).height(640.dp)) {
                AiSchemaScreen(navController = rememberNavController())
            }
        }
        compose.onNodeWithTag("ai-schema-include-papers").assertIsOn()
        compose.onNodeWithTag("ai-schema-include-categories").assertIsOn()
        compose.onNodeWithTag("ai-schema-include-passages").assertIsOff()

        listOf(
            "ai-schema-include-papers",
            "ai-schema-include-categories",
            "ai-schema-include-passages"
        ).forEach { tag ->
            val before = schemaText()
            compose.onNodeWithTag(tag).performClick()
            compose.waitForIdle()
            assertTrue("flipping $tag did not change the schema", schemaText() != before)
        }
        compose.onNodeWithTag("ai-schema-include-passages").assertIsOn()
        compose.onNodeWithTag("ai-schema-passage").performScrollTo().assertIsDisplayed()
    }

    /**
     * The output-card tag sits on the Card, which does not merge its children.
     * Read the JSON Text node from the unmerged tree, with the same empty
     * fallback StudyAndSelectionUiTest uses for its clock label.
     */
    private fun schemaText(): String {
        val nodes = compose.onAllNodesWithTag(
            "ai-schema-output-schema-text", useUnmergedTree = true
        ).fetchSemanticsNodes()
        return nodes.firstOrNull()?.config
            ?.getOrElse(SemanticsProperties.Text) { emptyList() }
            ?.joinToString("\n") { it.text } ?: ""
    }

    private fun DpRect.w(): Dp = right - left
    private fun DpRect.h(): Dp = bottom - top
}
