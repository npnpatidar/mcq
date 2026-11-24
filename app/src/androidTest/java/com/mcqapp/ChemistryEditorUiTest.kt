package com.mcqapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.BlockListEditor
import com.mcqapp.ui.editor.EditorBlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The chem block in the shared block editor on a 320 dp-wide screen (the
 * narrow layout the editor has to stay usable on). Every field and the live
 * preview must be reachable by scrolling, and the template dropdown must
 * rewrite the source fields in one tap.
 */
@RunWith(AndroidJUnit4::class)
class ChemistryEditorUiTest {

    @get:Rule
    val compose = createComposeRule()

    /** Keys the real renderer reports when it rasterises the preview. */
    private val diagramKeys = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun setChemEditor() {
        diagramKeys.clear()
        compose.setContent {
            MaterialTheme {
                var element by remember { mutableStateOf(EditorBlockType.CHEM.defaultElement()) }
                Box(
                    Modifier
                        .width(320.dp)
                        .height(520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    BlockListEditor(
                        elements = listOf(element),
                        blockIds = listOf("chem1"),
                        owner = "chem-ui-test",
                        onAddBlock = {},
                        onUpdateBlock = { _, updated ->
                            element = updated as ContentElement.ChemElement
                        },
                        onUpdateMath = { _, _ -> },
                        onRemoveBlock = {},
                        onMoveUp = {},
                        onMoveDown = {},
                        onPickImage = {},
                        onChemDiagram = { key, _ -> diagramKeys.add(key) }
                    )
                }
            }
        }
    }

    private fun editable(tag: String): String = compose.onNodeWithTag(tag)
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun previewDescriptions(): List<String> = compose.onNodeWithTag("chem-preview-chem1")
        .fetchSemanticsNode().config[SemanticsProperties.ContentDescription]

    private fun previewSmiles(): String = compose.onNodeWithTag("chem-preview-chem1")
        .fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    private fun templateLabel(): String = compose.onNodeWithTag("chem-template-chem1")
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun chemFieldsAndPreviewAreReachableAt320dp() {
        setChemEditor()
        compose.onNodeWithTag("chem-block-chem1").assertIsDisplayed()
        compose.onNodeWithTag("chem-template-chem1").performScrollTo().assertIsDisplayed()
        val smiles = compose.onNodeWithTag("chem-smiles-chem1").performScrollTo()
        smiles.performTextClearance()
        smiles.performTextInput("CCO")
        assertEquals("CCO", editable("chem-smiles-chem1"))
        compose.onNodeWithTag("chem-formula-chem1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chem-caption-chem1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chem-spoken-chem1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chem-preview-chem1").performScrollTo().assertIsDisplayed()

        // The preview carries the localized spoken description of the block,
        // so TalkBack announces the structure rather than an empty WebView.
        assertTrue(
            "expected a benzene structure description, saw ${previewDescriptions()}",
            previewDescriptions().any { it == "Chemical structure: benzene" }
        )
        // A chem edit propagates to the preview (the UI-visible side of the
        // dirty snapshot: ChemistryEditorTest pins the snapshot itself).
        val caption = compose.onNodeWithTag("chem-caption-chem1").performScrollTo()
        caption.performTextClearance()
        caption.performTextInput("ring")
        assertTrue(
            "an edited chem block must change what the preview describes",
            previewDescriptions().any { it == "Chemical structure: ring" }
        )
    }

    @Test
    fun editingSmilesRefreshesThePreviewAndDropsTheTemplate() {
        // No diagram is seeded, so the renderer draws from SMILES, rasterises
        // the SVG and reports a capture keyed by "<blockId>@<smiles>".
        setChemEditor()
        assertEquals("c1ccccc1", previewSmiles())
        assertTrue(
            previewDescriptions().any { it == "Chemical structure: benzene" }
        )
        compose.waitUntil(30_000) { diagramKeys.contains("chem1@c1ccccc1") }

        val smiles = compose.onNodeWithTag("chem-smiles-chem1").performScrollTo()
        smiles.performTextClearance()
        smiles.performTextInput("c")
        // The preview follows the new source immediately: its semantics carry
        // the SMILES actually being drawn, the template no longer claims
        // benzene, and the capture emitted by the redrawn WebView is bound to
        // the new SMILES. The rejection of a queued old-key capture is pinned
        // by the pure ViewModel tests.
        assertEquals("c", previewSmiles())
        assertEquals("Custom", templateLabel())
        compose.waitUntil(30_000) { diagramKeys.contains("chem1@c") }
        assertTrue(
            "renderer must emit a capture keyed by the edited SMILES, saw $diagramKeys",
            diagramKeys.contains("chem1@c")
        )
    }

    @Test
    fun pickingATemplateRewritesTheSourceFields() {
        setChemEditor()
        assertEquals("c1ccccc1", editable("chem-smiles-chem1"))
        compose.onNodeWithTag("chem-template-chem1").performScrollTo().performClick()
        compose.onNodeWithTag("chem-template-item-water-chem1").performClick()
        assertEquals("O", editable("chem-smiles-chem1"))
        assertEquals("H2O", editable("chem-formula-chem1"))
    }
}
