package com.mcqapp.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.ContentElements
import com.mcqapp.util.QuestionImage
import com.mcqapp.R

/**
 * Reusable editor for a list of [ContentElement] blocks. Text and formula
 * blocks edit in place; image blocks edit the src with a gallery Pick button;
 * tables get a full grid editor when the table callbacks are supplied,
 * otherwise they render read-only via [ContentElements].
 */
@Composable
fun BlockListEditor(
    elements: List<ContentElement>,
    /** Stable ids parallel to [elements]. The key is built from these, not
     *  from the position or the payload, so a reordered formula keeps its own
     *  WebView and an edit never tears the block down. */
    blockIds: List<String> = emptyList(),
    /** Who owns this list (the question, plus the option or "explanation"):
     *  two questions that share an option id must never share a WebView
     *  (question_editor_audit.md H-05). */
    owner: String = "",
    onAddBlock: (EditorBlockType) -> Unit,
    onUpdateBlock: (index: Int, element: ContentElement) -> Unit,
    /**
     * Writes a formula by the block's stable id, never by its position. The
     * MathLive bridge is installed once per keyed WebView and does not rebind,
     * so the host resolves [blockId] against the current list when it fires.
     */
    onUpdateMath: (blockId: String, mml: String) -> Unit,
    onRemoveBlock: (index: Int) -> Unit,
    onMoveUp: (index: Int) -> Unit,
    onMoveDown: (index: Int) -> Unit,
    onUpdateCell: ((index: Int, row: Int, col: Int, value: String) -> Unit)? = null,
    onAddRow: ((index: Int) -> Unit)? = null,
    onRemoveRow: ((index: Int, row: Int) -> Unit)? = null,
    onAddColumn: ((index: Int) -> Unit)? = null,
    onRemoveColumn: ((index: Int, col: Int) -> Unit)? = null,
    /** Starts a gallery pick for the block with this stable id. */
    onPickImage: (blockId: String) -> Unit,
    /**
     * Receives a renderer-generated PNG data URI for the chem block named by
     * [blockId]. The host writes it through `EditorViewModel.updateChemDiagram`,
     * which keeps the captured diagram on the right block after a reorder.
     */
    onChemDiagram: (blockId: String, pngDataUri: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val tablesEditable = onUpdateCell != null && onAddRow != null &&
        onRemoveRow != null && onAddColumn != null && onRemoveColumn != null
    Column(modifier = modifier.fillMaxWidth()) {
        elements.forEachIndexed { index, element ->
            // Keyed by stable id (see blockKey): a moved block keeps its own
            // composition instead of handing its WebView to a different formula.
            androidx.compose.runtime.key(
                blockKey(owner, blockIds.getOrNull(index) ?: "index$index", element)
            ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.block_label, index + 1, stringResource(blockLabelRes(element))),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    // Every repeated control names its target, so a screen
                    // reader does not announce ten identical "Move up"s
                    // (question_editor_audit.md M-11).
                    IconButton(onClick = { onMoveUp(index) }, enabled = index > 0) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = stringResource(R.string.move_block_up_named, index + 1)
                        )
                    }
                    IconButton(onClick = { onMoveDown(index) }, enabled = index < elements.size - 1) {
                        Icon(
                            Icons.Default.ArrowDownward,
                            contentDescription = stringResource(R.string.move_block_down_named, index + 1)
                        )
                    }
                    IconButton(onClick = { onRemoveBlock(index) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.remove_block_named, index + 1)
                        )
                    }
                }
                when (element) {
                    is ContentElement.TextElement -> OutlinedTextField(
                        value = element.text,
                        onValueChange = { onUpdateBlock(index, ContentElement.TextElement(it)) },
                        label = { Text(stringResource(R.string.text)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    is ContentElement.ImageElement -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = element.src,
                                onValueChange = { onUpdateBlock(index, ContentElement.ImageElement(it)) },
                                label = { Text(stringResource(R.string.image_url)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = {
                                onPickImage(blockIds.getOrNull(index) ?: "")
                            }) {
                                Text(stringResource(R.string.pick))
                            }
                        }
                        if (element.src.isNotBlank()) {
                            QuestionImage(src = element.src, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    is ContentElement.MathElement -> {
                        // Capture the stable id, not the loop position. After a
                        // reorder the WebView follows its key (blockKey), but a
                        // callback built from `index` would keep writing into
                        // the block that used to be at that slot (H5).
                        MathLiveEditor(
                            initialLatex = mathMlToLatex(element.mathml),
                            blockId = blockIds.getOrNull(index) ?: "index$index",
                            onMathMl = onUpdateMath
                        )
                        // The MathLive keyboard only appears once the field has
                        // focus, which is not obvious from a formula on screen.
                        Text(
                            stringResource(R.string.tap_formula_to_edit),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    is ContentElement.ChemElement -> ChemBlockEditor(
                        element = element,
                        blockId = blockIds.getOrNull(index) ?: "index$index",
                        onUpdate = { onUpdateBlock(index, it) },
                        onChemDiagram = onChemDiagram
                    )
                    is ContentElement.TableElement -> {
                        if (tablesEditable) {
                            TableBlockEditor(
                                table = element,
                                onUpdateCell = { row, col, value ->
                                    onUpdateCell?.invoke(index, row, col, value)
                                },
                                onAddRow = { onAddRow?.invoke(index) },
                                onRemoveRow = { row -> onRemoveRow?.invoke(index, row) },
                                onAddColumn = { onAddColumn?.invoke(index) },
                                onRemoveColumn = { col -> onRemoveColumn?.invoke(index, col) }
                            )
                        } else {
                            ContentElements(listOf(element), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            }
        }
        AddBlockMenu(onAddBlock = onAddBlock)
    }
}

/**
 * Source editor for one chemical structure.
 *
 * Deliberately text-first: there is no WYSIWYG drawing canvas here, and a
 * chem block must never be handed to MathLive — `\ce{}` is silently dropped
 * into an empty MathML element. The live preview goes through the shared
 * [ContentElements] renderer, the same one Study and Browse use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChemBlockEditor(
    element: ContentElement.ChemElement,
    blockId: String,
    onUpdate: (ContentElement.ChemElement) -> Unit,
    onChemDiagram: (blockId: String, pngDataUri: String) -> Unit
) {
    val selected = ChemTemplates.templateFor(element)
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().testTag("chem-block-$blockId")) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected?.label ?: stringResource(R.string.chem_editor_custom),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.chem_editor_template)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
                    .testTag("chem-template-$blockId")
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ChemTemplates.ALL.forEach { template ->
                    DropdownMenuItem(
                        text = { Text(template.label) },
                        onClick = {
                            onUpdate(template.toElement())
                            expanded = false
                        },
                        modifier = Modifier.testTag("chem-template-item-${template.id}-$blockId")
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = element.smiles,
            onValueChange = { onUpdate(element.copy(smiles = it)) },
            label = { Text(stringResource(R.string.chem_editor_smiles)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("chem-smiles-$blockId")
        )
        OutlinedTextField(
            value = element.formula,
            onValueChange = { onUpdate(element.copy(formula = it)) },
            label = { Text(stringResource(R.string.chem_editor_formula)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("chem-formula-$blockId")
        )
        OutlinedTextField(
            value = element.caption,
            onValueChange = { onUpdate(element.copy(caption = it)) },
            label = { Text(stringResource(R.string.chem_editor_caption)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("chem-caption-$blockId")
        )
        OutlinedTextField(
            value = element.spoken,
            onValueChange = { onUpdate(element.copy(spoken = it)) },
            label = { Text(stringResource(R.string.chem_editor_spoken)) },
            modifier = Modifier.fillMaxWidth().testTag("chem-spoken-$blockId")
        )
        Text(
            stringResource(R.string.chem_editor_preview),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        val described = element.caption.ifBlank { element.formula }.ifBlank { element.smiles }
        val structureDescription = stringResource(R.string.chem_editor_structure_cd, described)
        ContentElements(
            elements = listOf(element),
            // The renderer keys its capture callback by identity: a reordered
            // block must receive its own PNG, and an unchanged capture is a
            // no-op in the ViewModel (no feedback loop).
            chemKey = blockId,
            onChemDiagram = onChemDiagram,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag("chem-preview-$blockId")
                .semantics { contentDescription = structureDescription }
        )
    }
}

/**
 * Stable identity for a content block: owner, block id, kind.
 *
 * Deliberately **not** derived from the content or the position. A
 * content-derived key meant every keystroke rebuilt the block (the caret and
 * the soft keyboard vanished, and the MathLive WebView with its virtual
 * keyboard was recreated), while a position-derived one meant swapping two
 * formulas of the same kind kept the same keys and one WebView went on
 * showing the other block's formula (question_editor_audit.md M-01).
 *
 * The owner is the question plus the list (an option id, "explanation"), so
 * navigating to another question that uses the same option id cannot hand it
 * the previous question's WebView and its user-edited latch (H-05).
 */
internal fun blockKey(owner: String, blockId: String, element: ContentElement): String =
    "$owner|$blockId|${element::class.simpleName}"

private fun blockLabelRes(element: ContentElement): Int = when (element) {
    is ContentElement.TextElement -> R.string.text
    is ContentElement.ImageElement -> R.string.image
    is ContentElement.TableElement -> R.string.table
    is ContentElement.MathElement -> R.string.formula
    is ContentElement.ChemElement -> R.string.chem_editor_block_label
}

@Composable
private fun TableBlockEditor(
    table: ContentElement.TableElement,
    onUpdateCell: (row: Int, col: Int, value: String) -> Unit,
    onAddRow: () -> Unit,
    onRemoveRow: (row: Int) -> Unit,
    onAddColumn: () -> Unit,
    onRemoveColumn: (col: Int) -> Unit
) {
    val colCount = table.rows.maxOfOrNull { it.size } ?: 0
    Column(modifier = Modifier.fillMaxWidth()) {
        // Per-column delete buttons.
        if (colCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(colCount) { col ->
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        // Disable exactly the no-op cases (last column, or a
                        // ragged row lacking this index) so the control agrees
                        // with withColumnRemoved (question_editor_audit.md L-04).
                        IconButton(
                            onClick = { onRemoveColumn(col) },
                            enabled = table.canRemoveColumn(col)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.remove_column_named, col + 1)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(48.dp))
            }
        }
        table.rows.forEachIndexed { row, cells ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                cells.forEachIndexed { col, cell ->
                    val tableCellLabel = stringResource(
                        R.string.table_cell_label,
                        row + 1,
                        col + 1
                    )
                    OutlinedTextField(
                        value = cell,
                        onValueChange = { onUpdateCell(row, col, it) },
                        singleLine = true,
                        label = { Text(tableCellLabel) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp)
                            .semantics {
                                // A contentDescription would replace the field's
                                // value for screen readers. A stateDescription
                                // adds the target-specific cell label while the
                                // typed value stays accessible.
                                stateDescription = tableCellLabel
                            }
                    )
                }
                // Pad short rows so the delete button stays aligned.
                repeat((colCount - cells.size).coerceAtLeast(0)) {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(
                    onClick = { onRemoveRow(row) },
                    enabled = table.rows.size > 1
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.remove_row_named, row + 1)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddRow) { Text(stringResource(R.string.add_row)) }
            OutlinedButton(onClick = onAddColumn) { Text(stringResource(R.string.add_column)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBlockMenu(onAddBlock: (EditorBlockType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.menuAnchor()
        ) {
            Text(stringResource(R.string.add_block))
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.text)) },
                onClick = { onAddBlock(EditorBlockType.TEXT); expanded = false }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.image)) },
                onClick = { onAddBlock(EditorBlockType.IMAGE); expanded = false }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.table)) },
                onClick = { onAddBlock(EditorBlockType.TABLE); expanded = false }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.formula)) },
                onClick = { onAddBlock(EditorBlockType.MATH); expanded = false }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chem_editor_block_label)) },
                onClick = { onAddBlock(EditorBlockType.CHEM); expanded = false }
            )
        }
    }
}
