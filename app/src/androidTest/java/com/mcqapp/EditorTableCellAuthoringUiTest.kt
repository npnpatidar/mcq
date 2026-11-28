package com.mcqapp

import android.content.Context
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
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
import com.mcqapp.util.chemCellHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * The table cell's authoring UI on the 320 dp screen the editor must stay
 * usable on: the grid is read-only chips, tapping one opens a near-full-width
 * popup with the token field and one menu offering Text / Formula / Image /
 * Structure. Insertions remove the raw markup from the field, and the chip
 * shows a readable preview once the popup closes. Runs on the CI emulator; the
 * pure grammar and the write-back transforms are pinned by CellEditorTextTest
 * and EditorTableCellAuthoringTest locally.
 */
@RunWith(AndroidJUnit4::class)
class EditorTableCellAuthoringUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
    private val chemElement = ContentElement.ChemElement(
        smiles = "CCO",
        formula = "C2H5OH",
        caption = "ethanol",
        spoken = "ethanol"
    )

    private fun setTable(initial: String, richCellPreview: Boolean = false) {
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
                        onPickImage = {},
                        richCellPreview = richCellPreview
                    )
                }
            }
        }
    }

    private fun editable(tag: String): String = compose.onNodeWithTag(tag)
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun chipText(row: Int, col: Int): String = compose
        .onNodeWithTag("cell-chip-t1-$row-$col")
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString("") { it.text }

    private fun chipDescription(row: Int, col: Int): String = compose
        .onNodeWithTag("cell-chip-t1-$row-$col")
        .fetchSemanticsNode()
        .config[SemanticsProperties.ContentDescription]
        .joinToString(" ")

    private fun openCell(row: Int = 0, col: Int = 0) {
        compose.onNodeWithTag("cell-chip-t1-$row-$col").performScrollTo().performClick()
        compose.onNodeWithTag("cell-dialog-t1-$row-$col").assertExists()
    }

    private fun setCell(row: Int, col: Int, text: String) {
        openCell(row, col)
        compose.onNodeWithTag("cell-field-t1-$row-$col").performTextInput(text)
        compose.onNodeWithTag("cell-done-t1-$row-$col").performClick()
    }

    private fun openMenu() {
        openCell()
        // The menu lives inside the dialog window, which has no scroll
        // container: performScrollTo would throw for this node.
        compose.onNodeWithTag("cell-menu-t1-0-0").performClick()
    }

    @Test
    fun theTableHasNoSecondPreviewAndTheCellMenuStillWorks() {
        setTable("")
        // The block's own grid is the authoring surface; the whole-question
        // preview above is the only drawing of the table now.
        compose.onNodeWithTag("table-preview-t1").assertDoesNotExist()
        compose.onNodeWithText("Preview").assertDoesNotExist()
        openMenu()
        compose.onNodeWithText("Text").assertIsDisplayed()
        compose.onNodeWithText("Formula").assertIsDisplayed()
        compose.onNodeWithText("Image").assertIsDisplayed()
        compose.onNodeWithText("Structure").assertIsDisplayed()
    }

    @Test
    fun theCellPopupOpensOnTapAndDoneClosesIt() {
        setTable("")
        compose.onNodeWithTag("cell-dialog-t1-0-0").assertDoesNotExist()
        openCell()
        compose.onNodeWithTag("cell-field-t1-0-0").assertExists()
        compose.onNodeWithTag("cell-done-t1-0-0").performClick()
        compose.onNodeWithTag("cell-dialog-t1-0-0").assertDoesNotExist()
    }

    @Test
    fun typingInThePopupUpdatesTheChipPreviewWhenDone() {
        setTable("")
        openCell()
        compose.onNodeWithTag("cell-field-t1-0-0").performTextInput("ethanol")
        compose.onNodeWithTag("cell-done-t1-0-0").performClick()
        // The chip shows the prose preview, never a raw marker or base64.
        compose.onNodeWithText("ethanol").assertExists()
    }

    @Test
    fun thePopupEditsTheTappedCellNotTheFirstOne() {
        setTable("")
        setCell(0, 0, "first")
        compose.onNodeWithTag("table-add-row-t1").performClick()

        // Tap the NEW second row: a popup that ignored its target would write
        // into 0-0, so the two texts would swap or merge.
        openCell(1, 0)
        compose.onNodeWithTag("cell-field-t1-1-0").performTextInput("second")
        compose.onNodeWithTag("cell-done-t1-1-0").performClick()

        assertEquals("first", chipText(0, 0))
        assertEquals("second", chipText(1, 0))
    }

    @Test
    fun aMixedCellChipShowsProseAndOneLabelPerRichPart() {
        setTable("name " + chemCellHtml(chemElement, includePng = false))
        assertEquals("name [Structure]", chipText(0, 0))
        assertFalse("marker leaked: ${chipText(0, 0)}", chipText(0, 0).contains("mcq-chem-cell"))
        assertFalse("attribute leaked: ${chipText(0, 0)}", chipText(0, 0).contains("data-"))
    }

    @Test
    fun optionAndExplanationTablesKeepALocalPreviewForCapture() {
        setTable("name " + chemCellHtml(chemElement, includePng = false), richCellPreview = true)
        // The body's whole-question preview covers its own tables; an option or
        // regular explanation opts in because it has no other preview page.
        compose.onNodeWithTag("table-preview-t1").assertExists()
    }

    @Test
    fun aLongCellStaysOneLine() {
        setTable("short")
        compose.onNodeWithTag("table-add-row-t1").performClick()
        setCell(
            1,
            0,
            "a very long cell value that must not wrap the chip onto a second line, " +
                "because a table with many rows has to stay compact"
        )
        val short = compose.onNodeWithTag("cell-chip-t1-0-0")
            .getUnclippedBoundsInRoot()
        val long = compose.onNodeWithTag("cell-chip-t1-1-0")
            .getUnclippedBoundsInRoot()
        // maxLines=1 + ellipsis: the long preview keeps the one-line height of
        // a short chip. Semantics does not expose the ellipsis itself.
        assertEquals(
            "long chip wrapped: ${long.bottom - long.top}dp vs ${short.bottom - short.top}dp",
            (short.bottom - short.top).value,
            (long.bottom - long.top).value,
            0.5f
        )
    }

    @Test
    fun theChipAndPopupAnnounceTheirPositionAndPreview() {
        setTable("")
        assertEquals("Row 1, column 1, Empty cell", chipDescription(0, 0))

        openCell()
        compose.onNodeWithTag("cell-dialog-title-t1-0-0").assertTextEquals("Row 1, column 1")
        compose.onNodeWithTag("cell-field-t1-0-0").performTextInput("hello")
        compose.onNodeWithTag("cell-done-t1-0-0").performClick()
        assertEquals("Row 1, column 1, hello", chipDescription(0, 0))
    }

    @Test
    fun theCompactToolbarAddsAndRemovesRowsAndColumns() {
        setTable("")
        compose.onNodeWithTag("table-size-t1").assertTextEquals("1 × 1")

        compose.onNodeWithTag("table-add-row-t1").performClick()
        compose.onNodeWithTag("table-size-t1").assertTextEquals("2 × 1")
        compose.onNodeWithTag("cell-chip-t1-1-0").assertExists()

        compose.onNodeWithTag("table-add-column-t1").performClick()
        compose.onNodeWithTag("table-size-t1").assertTextEquals("2 × 2")
        compose.onNodeWithTag("cell-chip-t1-0-1").assertExists()

        // No cell is focused, so the toolbar targets the last cell.
        compose.onNodeWithTag("table-remove-column-t1").performClick()
        compose.onNodeWithTag("table-size-t1").assertTextEquals("2 × 1")
        compose.onNodeWithTag("cell-chip-t1-0-1").assertDoesNotExist()

        compose.onNodeWithTag("table-remove-row-t1").performClick()
        compose.onNodeWithTag("table-size-t1").assertTextEquals("1 × 1")
        compose.onNodeWithTag("cell-chip-t1-1-0").assertDoesNotExist()
        // The last row cannot be removed.
        compose.onNodeWithTag("table-remove-row-t1").assertIsNotEnabled()
        compose.onNodeWithTag("table-remove-column-t1").assertIsNotEnabled()
    }

    @Test
    fun removingARowKeepsTheFocusedRowsNeighbours() {
        setTable("")
        compose.onNodeWithTag("table-add-row-t1").performClick()
        setCell(0, 0, "top")
        setCell(1, 0, "bottom")

        // Focus the first row, then remove it. A no-focus fallback would remove
        // the last row instead and leave "top" behind.
        openCell(0, 0)
        compose.onNodeWithTag("cell-done-t1-0-0").performClick()
        compose.onNodeWithTag("table-remove-row-t1").performClick()

        assertEquals("bottom", chipText(0, 0))
        compose.onNodeWithTag("cell-chip-t1-1-0").assertDoesNotExist()
    }

    @Test
    fun removingAColumnKeepsTheFocusedColumnsNeighbours() {
        setTable("")
        compose.onNodeWithTag("table-add-column-t1").performClick()
        setCell(0, 0, "left")
        setCell(0, 1, "right")

        // Focus the LEFT column. Correct behaviour removes column 0, leaving
        // "right" in cell 0-0; a no-focus fallback would remove the last
        // (right) column and leave "left" behind.
        openCell(0, 0)
        compose.onNodeWithTag("cell-done-t1-0-0").performClick()
        compose.onNodeWithTag("table-remove-column-t1").performClick()

        assertEquals("right", chipText(0, 0))
        compose.onNodeWithTag("cell-chip-t1-0-1").assertDoesNotExist()
    }

    @Test
    fun twoRichPartsFromThePopupShowBothChipLabels() {
        // Seed the formula, then insert the structure through the popup menu.
        setTable("<math><mi>x</mi></math>")
        openMenu()
        compose.onNodeWithText("Structure").performClick()
        compose.onNodeWithText("Structure in row 1, column 1").assertIsDisplayed()
        compose.onNodeWithText("Insert").performClick()
        compose.onNodeWithTag("cell-done-t1-0-0").performClick()

        assertEquals("[Formula] [Structure]", chipText(0, 0))
    }

    @Test
    fun dismissingThePopupKeepsTheLiveTypedText() {
        setTable("")
        openCell()
        compose.onNodeWithTag("cell-field-t1-0-0").performTextInput("kept")
        // Close (not Done) is a deterministic dismissal with the IME up. The
        // edit was already written through the ViewModel callback, so it must
        // survive without the Done button.
        compose.onNodeWithTag("cell-close-t1-0-0").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cell-dialog-t1-0-0").assertDoesNotExist()
        assertEquals("kept", chipText(0, 0))
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
    fun theCellFormulaDialogGivesMathLiveTheNearFullScreenWidth() {
        setTable("")
        openMenu()
        compose.onNodeWithText("Formula").performClick()

        val metrics = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics
        val screenDp = metrics.widthPixels / metrics.density
        val dialog = compose.onNodeWithTag("cell-formula-dialog-t1-0-0")
            .getUnclippedBoundsInRoot()
        // This measures the dialog's width, not whether a child is clipped:
        // the old AlertDialog used the platform's narrow default width, while
        // the replacement asks for nearly the whole screen.
        val dialogWidth = (dialog.right - dialog.left).value
        assertTrue(
            "dialog too narrow: ${dialogWidth}dp of ${screenDp}dp",
            dialogWidth >= screenDp * 0.9f
        )
        // The MathLive container receives that width (the 16 dp dialog padding
        // is the only subtraction), so its keyboard is laid out at full width.
        val editor = compose.onNodeWithTag("cell-formula-editor-t1-0-0")
            .getUnclippedBoundsInRoot()
        val editorWidth = (editor.right - editor.left).value
        assertTrue(
            "editor did not receive the dialog width: ${editorWidth}dp in ${dialogWidth}dp",
            editorWidth >= dialogWidth - 40f
        )
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Insert").assertIsDisplayed()
    }

    @Test
    fun aStructureIsInsertedAsATokenWithoutMarkupInTheField() {
        setTable("name")
        openMenu()
        compose.onNodeWithText("Structure").performClick()
        compose.onNodeWithText("Structure in row 1, column 1").assertIsDisplayed()
        compose.onNodeWithText("Insert").performClick()

        val view = editable("cell-field-t1-0-0")
        // The field shows the readable label, never the stored marker or its
        // attributes; the raw salted token is transformed away for display.
        assertTrue("expected the structure label, saw '$view'", view.contains("[Structure]"))
        assertFalse("the marker must never reach the field: $view", view.contains("mcq-chem-cell"))
        assertFalse("no stored attribute may leak: $view", view.contains("data-"))
        // The whole-question preview, not a second table copy, draws it.
        compose.onNodeWithTag("table-preview-t1").assertDoesNotExist()
        compose.onNodeWithTag("cell-done-t1-0-0").performClick()
        // The chip shows the prose plus one label per rich part, so the label
        // is never the whole chip text.
        val chip = chipText(0, 0)
        assertEquals("name [Structure]", chip)
        assertFalse("the marker must never reach the chip: $chip", chip.contains("mcq-chem-cell"))
        assertFalse("no stored attribute may leak into the chip: $chip", chip.contains("data-"))
    }

    @Test
    fun anImageIsInsertedAsATokenWithoutBase64InTheField() {
        setTable("")
        openMenu()
        compose.onNodeWithText("Image").performClick()

        val view = editable("cell-field-t1-0-0")
        assertTrue("expected the image label, saw '$view'", view.contains("[Image]"))
        assertFalse("a data URI must never reach the field: $view", view.contains("data:image"))
        assertFalse("base64 must never reach the field: $view", view.contains("base64"))
        compose.onNodeWithTag("table-preview-t1").assertDoesNotExist()
        compose.onNodeWithTag("cell-done-t1-0-0").performClick()
        compose.onNodeWithText("[Image]").assertExists()
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
        val view = editable("cell-field-t1-0-0")
        assertTrue(
            "the formula part must survive the round trip; saw '$view'",
            view.contains("[Formula]")
        )
        compose.onNodeWithTag("table-preview-t1").assertDoesNotExist()
    }
}
