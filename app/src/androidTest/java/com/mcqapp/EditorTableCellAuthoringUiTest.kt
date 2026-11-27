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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.BlockListEditor
import com.mcqapp.util.CellEditPart
import com.mcqapp.ui.editor.withCell
import com.mcqapp.ui.editor.withCellEditorText
import com.mcqapp.ui.editor.withCellPart
import com.mcqapp.ui.editor.withCellPartRemoved
import com.mcqapp.ui.editor.withColumnAdded
import com.mcqapp.ui.editor.withColumnRemoved
import com.mcqapp.ui.editor.withRowAdded
import com.mcqapp.ui.editor.withRowRemoved
import com.mcqapp.util.cellImageTag
import com.mcqapp.util.cellTokenKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * The table cell's authoring UI on the 320 dp screen the editor must stay
 * usable on: one menu offers Text / Formula / Image / Structure, and each
 * insertion removes the raw markup from the field while the live preview
 * below the grid draws it. Runs on the CI emulator; the pure grammar and the
 * write-back transforms are pinned by CellEditorTextTest and
 * EditorTableCellAuthoringTest locally.
 */
@RunWith(AndroidJUnit4::class)
class EditorTableCellAuthoringUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val png: String = "data:image/png;base64," + Base64.getEncoder().encodeToString(
        ByteArray(24).also { b ->
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b, 0)
            'I'.code.toByte().let { b[8] = it }
            'H'.code.toByte().let { b[9] = it }
            'D'.code.toByte().let { b[10] = it }
            'R'.code.toByte().let { b[11] = it }
            b[19] = 2
            b[23] = 2
        }
    )

    private fun setTable(initial: String) {
        compose.setContent {
            MaterialTheme {
                var table by remember {
                    mutableStateOf(ContentElement.TableElement(listOf(listOf(initial))))
                }
                Box(
                    Modifier
                        .width(320.dp)
                        .height(720.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    BlockListEditor(
                        elements = listOf(table),
                        blockIds = listOf("t1"),
                        owner = "cell-ui-test",
                        onAddBlock = {},
                        onUpdateBlock = { _, _ -> },
                        onUpdateMath = { _, _ -> },
                        onRemoveBlock = {},
                        onMoveUp = {},
                        onMoveDown = {},
                        onUpdateCell = { _, r, c, value ->
                            table = table.withCell(r, c, value)
                        },
                        onAddRow = { table = table.withRowAdded() },
                        onRemoveRow = { _, row -> table = table.withRowRemoved(row) },
                        onAddColumn = { table = table.withColumnAdded() },
                        onRemoveColumn = { _, col -> table = table.withColumnRemoved(col) },
                        onUpdateCellText = { _, r, c, text ->
                            table = table.withCellEditorText(r, c, text, cellTokenKey("t1", r, c))
                        },
                        onSetCellPart = { _, r, c, index, part ->
                            table = table.withCellPart(r, c, index, part)
                        },
                        onRemoveCellPart = { _, r, c, index ->
                            table = table.withCellPartRemoved(r, c, index)
                        },
                        onPickCellImage = { _, r, c, replace ->
                            table = table.withCellPart(
                                r,
                                c,
                                replace,
                                CellEditPart.Image(cellImageTag(png))
                            )
                        },
                        onPickImage = {}
                    )
                }
            }
        }
    }

    private fun editable(tag: String): String = compose.onNodeWithTag(tag)
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun openMenu() {
        compose.onNodeWithTag("cell-menu-t1-0-0").performScrollTo().performClick()
    }

    @Test
    fun theCellMenuOffersTextFormulaImageAndStructure() {
        setTable("")
        openMenu()
        compose.onNodeWithText("Text").assertIsDisplayed()
        compose.onNodeWithText("Formula").assertIsDisplayed()
        compose.onNodeWithText("Image").assertIsDisplayed()
        compose.onNodeWithText("Structure").assertIsDisplayed()
    }

    @Test
    fun theFormulaEntryOpensAMathLiveDialog() {
        setTable("")
        openMenu()
        compose.onNodeWithText("Formula").performClick()
        compose.onNodeWithText("Formula in row 1, column 1").assertIsDisplayed()
        // An empty formula cannot be inserted: the confirm is disabled until
        // MathLive reports a value.
        compose.onNodeWithText("Insert").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test
    fun aStructureIsInsertedAndDrawnInThePreview() {
        setTable("name")
        openMenu()
        compose.onNodeWithText("Structure").performClick()
        compose.onNodeWithText("Structure in row 1, column 1").assertIsDisplayed()
        compose.onNodeWithText("Insert").performClick()

        val view = editable("cell-field-t1-0-0")
        // The cell text field never exposes the marker; it shows one token.
        assertFalse(view.contains("mcq-chem-cell"))
        assertTrue(view.any { it.code in 0xE000..0xF8FF })
        // The live preview under the grid is what draws the structure.
        compose.onNodeWithTag("table-preview-t1").assertExists()
    }

    @Test
    fun anImageIsInsertedAsATokenWithoutBase64InTheField() {
        setTable("")
        openMenu()
        compose.onNodeWithText("Image").performClick()

        val view = editable("cell-field-t1-0-0")
        assertFalse("base64 must never reach the field: $view", view.contains("data:image"))
        assertTrue(view.any { it.code in 0xE000..0xF8FF })
        compose.onNodeWithTag("table-preview-t1").assertExists()
    }

    @Test
    fun aFormulaCanBeRemovedFromTheMenuAndTheTextSurvives() {
        setTable("a <math><mi>x</mi></math> b")
        openMenu()
        compose.onNodeWithText("Remove formula").performClick()
        assertEquals("a  b", editable("cell-field-t1-0-0"))
    }

    @Test
    fun anExistingFormulaIsReopenedAndCommittedFromTheMenu() {
        setTable("a <math><mi>x</mi></math> b")
        openMenu()
        compose.onNodeWithText("Formula").performClick()
        compose.onNodeWithText("Formula in row 1, column 1").assertIsDisplayed()
        // The dialog starts from the stored MathML, so replacing without a
        // keystroke is a valid commit of the same formula.
        compose.onNodeWithText("Replace").performClick()
        compose.onNodeWithText("Formula in row 1, column 1").assertDoesNotExist()
        assertTrue(
            "the formula part must survive the round trip",
            editable("cell-field-t1-0-0").any { it.code in 0xE000..0xF8FF }
        )
        compose.onNodeWithTag("table-preview-t1").assertExists()
    }
}
