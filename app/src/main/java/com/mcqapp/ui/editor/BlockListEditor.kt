package com.mcqapp.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CellEditPart
import com.mcqapp.util.CellEditPartKind
import com.mcqapp.util.ContentElements
import com.mcqapp.util.InlineHtml
import com.mcqapp.util.QuestionImage
import com.mcqapp.util.cellEditParts
import com.mcqapp.util.cellEditorText
import com.mcqapp.util.cellTokenKey
import com.mcqapp.util.isValidCellSmiles
import com.mcqapp.util.kind
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
    /**
     * Rich-cell editing, additive to [onUpdateCell]. The cell field edits a
     * value in which every formula, picture and structure is one opaque
     * token, so the host writes it back through [onUpdateCellText] (which
     * preserves those parts) rather than as raw storage markup. All four
     * callbacks are addressed by the block's stable id, so a picker result or
     * dialog that outlives a recomposition cannot target a neighbour.
     */
    onUpdateCellText: ((blockId: String, row: Int, col: Int, text: String) -> Unit)? = null,
    onSetCellPart: ((
        blockId: String,
        row: Int,
        col: Int,
        partIndex: Int?,
        part: CellEditPart
    ) -> Unit)? = null,
    onRemoveCellPart: ((blockId: String, row: Int, col: Int, partIndex: Int) -> Unit)? = null,
    /** Starts a gallery pick for one cell; a non-null index replaces that part. */
    onPickCellImage: ((blockId: String, row: Int, col: Int, replacePart: Int?) -> Unit)? = null,
    /** Starts a gallery pick for the block with this stable id. */
    onPickImage: (blockId: String) -> Unit,
    /**
     * Receives a renderer-generated PNG data URI for one chem preview.
     *
     * [chemKey] is `"<blockId>@<smiles>"` — the stable block id *and* the
     * SMILES that produced the drawing. It is not a bare block id: the host
     * passes it to `EditorViewModel.updateChemDiagram`, which rejects a key
     * whose SMILES no longer matches the block (a capture queued before a
     * SMILES edit must not restore the old picture). Forward the key exactly
     * as the renderer reports it.
     */
    onChemDiagram: (chemKey: String, pngDataUri: String) -> Unit = { _, _ -> },
    /**
     * Draws a local live preview under a rich table in this list. The question
     * preview already draws the body and a flashcard's back, so those lists
     * leave it off; an option or a regular explanation has no such preview, so
     * its structures can capture their PNG here.
     */
    richCellPreview: Boolean = false,
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
                                blockId = blockIds.getOrNull(index) ?: "index$index",
                                tablePosition = index + 1,
                                richCellPreview = richCellPreview,
                                onChemDiagram = onChemDiagram,
                                onUpdateCell = { row, col, value ->
                                    onUpdateCell?.invoke(index, row, col, value)
                                },
                                onUpdateCellText = onUpdateCellText,
                                onSetCellPart = onSetCellPart,
                                onRemoveCellPart = onRemoveCellPart,
                                onPickCellImage = onPickCellImage,
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
    onChemDiagram: (chemKey: String, pngDataUri: String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().testTag("chem-block-$blockId")) {
        ChemSpecFields(
            element = element,
            onUpdate = onUpdate,
            tag = { name -> "chem-$name-$blockId" }
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
            // The key carries the SMILES that produced this drawing, so a
            // capture queued before a SMILES edit is rejected by the ViewModel
            // instead of stamping the old raster onto the new structure.
            chemKey = "$blockId@${element.smiles}",
            onChemDiagram = onChemDiagram,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag("chem-preview-$blockId")
                .semantics {
                    contentDescription = structureDescription
                    // The preview's source is observable to UI tests and to
                    // TalkBack: it changes the moment the SMILES does.
                    stateDescription = element.smiles
                }
        )
    }
}

/**
 * The template picker and the four source fields shared by a chem block and
 * the in-cell structure dialog. [tag] builds a test tag from a short field
 * name, so the block keeps its existing `chem-smiles-<blockId>` tags and the
 * dialog gets its own namespaced ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChemSpecFields(
    element: ContentElement.ChemElement,
    onUpdate: (ContentElement.ChemElement) -> Unit,
    tag: (String) -> String
) {
    val selected = ChemTemplates.templateFor(element)
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
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
                    .testTag(tag("template"))
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ChemTemplates.ALL.forEach { template ->
                    DropdownMenuItem(
                        text = { Text(template.label) },
                        onClick = {
                            onUpdate(template.toElement())
                            expanded = false
                        },
                        modifier = Modifier.testTag(tag("template-item-${template.id}"))
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = element.smiles,
            onValueChange = { onUpdate(withChemSmiles(element, it)) },
            label = { Text(stringResource(R.string.chem_editor_smiles)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(tag("smiles"))
        )
        OutlinedTextField(
            value = element.formula,
            onValueChange = { onUpdate(element.copy(formula = it)) },
            label = { Text(stringResource(R.string.chem_editor_formula)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(tag("formula"))
        )
        OutlinedTextField(
            value = element.caption,
            onValueChange = { onUpdate(element.copy(caption = it)) },
            label = { Text(stringResource(R.string.chem_editor_caption)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(tag("caption"))
        )
        OutlinedTextField(
            value = element.spoken,
            onValueChange = { onUpdate(element.copy(spoken = it)) },
            label = { Text(stringResource(R.string.chem_editor_spoken)) },
            modifier = Modifier.fillMaxWidth().testTag(tag("spoken"))
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

/** Cap on a chip's preview so a huge imported cell cannot build a huge utterance. */
private const val CELL_CHIP_PREVIEW_CHARS = 120

/** One open formula dialog; [partIndex] null means "insert a new formula". */
private data class CellFormulaTarget(
    val row: Int,
    val col: Int,
    val partIndex: Int?,
    val mathml: String
)

/** One open structure dialog; [element] null starts a new structure. */
private data class CellStructureTarget(
    val row: Int,
    val col: Int,
    val partIndex: Int?,
    val element: ContentElement.ChemElement?
)

@Composable
private fun TableBlockEditor(
    table: ContentElement.TableElement,
    blockId: String,
    /** 1-based position of this table in the list, for named toolbar actions. */
    tablePosition: Int,
    richCellPreview: Boolean,
    onChemDiagram: (chemKey: String, pngDataUri: String) -> Unit,
    onUpdateCell: (row: Int, col: Int, value: String) -> Unit,
    onUpdateCellText: ((blockId: String, row: Int, col: Int, text: String) -> Unit)?,
    onSetCellPart: ((blockId: String, row: Int, col: Int, partIndex: Int?, part: CellEditPart) -> Unit)?,
    onRemoveCellPart: ((blockId: String, row: Int, col: Int, partIndex: Int) -> Unit)?,
    onPickCellImage: ((blockId: String, row: Int, col: Int, replacePart: Int?) -> Unit)?,
    onAddRow: () -> Unit,
    onRemoveRow: (row: Int) -> Unit,
    onAddColumn: () -> Unit,
    onRemoveColumn: (col: Int) -> Unit
) {
    val rich = onUpdateCellText != null && onSetCellPart != null &&
        onRemoveCellPart != null && onPickCellImage != null
    var formulaTarget by remember { mutableStateOf<CellFormulaTarget?>(null) }
    var structureTarget by remember { mutableStateOf<CellStructureTarget?>(null) }
    // Which cell the toolbar acts on. Null means "nothing focused yet", which
    // falls back to the last cell — the same place add row/column leaves the
    // cursor. Kept by block id and re-validated against the current table, so
    // a removed row or column can never leave a stale target.
    var activeCell by remember(blockId) { mutableStateOf<Pair<Int, Int>?>(null) }
    // The cell whose text field is open in the popup editor.
    var editorCell by remember(blockId) { mutableStateOf<Pair<Int, Int>?>(null) }
    val rowCount = table.rows.size
    val colCount = table.rows.maxOfOrNull { it.size } ?: 0
    val hasRichCell = remember(table) {
        table.rows.any { cells ->
            cells.any { cell -> cellEditParts(cell).any { it.kind() != CellEditPartKind.TEXT } }
        }
    }
    val activeRow = activeCell?.first?.takeIf { it in table.rows.indices }
        ?: table.rows.lastIndex
    val activeCol = activeCell
        ?.takeIf { it.first == activeRow && it.second in (table.rows.getOrNull(activeRow)?.indices ?: IntRange.EMPTY) }
        ?.second
        ?: (table.rows.getOrNull(activeRow)?.lastIndex ?: 0)
    Column(modifier = Modifier.fillMaxWidth()) {
        // One compact toolbar instead of a delete icon on every row/column and
        // two large pill buttons under the grid. Each control names its target
        // for a screen reader; the row/column it acts on is the focused cell,
        // or the last cell when none is focused.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.table_dimensions, rowCount, colCount),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f).testTag("table-size-$blockId")
            )
            IconButton(
                onClick = onAddRow,
                modifier = Modifier.testTag("table-add-row-$blockId")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = stringResource(
                        R.string.table_add_row_named,
                        tablePosition
                    )
                )
            }
            IconButton(
                onClick = { onRemoveRow(activeRow) },
                enabled = rowCount > 1,
                modifier = Modifier.testTag("table-remove-row-$blockId")
            ) {
                Icon(
                    Icons.Default.PlaylistRemove,
                    contentDescription = stringResource(
                        R.string.table_remove_row_named,
                        activeRow + 1,
                        tablePosition
                    )
                )
            }
            IconButton(
                onClick = onAddColumn,
                modifier = Modifier.testTag("table-add-column-$blockId")
            ) {
                Icon(
                    Icons.Default.AddBox,
                    contentDescription = stringResource(
                        R.string.table_add_column_named,
                        tablePosition
                    )
                )
            }
            IconButton(
                onClick = { onRemoveColumn(activeCol) },
                enabled = table.canRemoveColumn(activeCol),
                modifier = Modifier.testTag("table-remove-column-$blockId")
            ) {
                Icon(
                    Icons.Default.IndeterminateCheckBox,
                    contentDescription = stringResource(
                        R.string.table_remove_column_named,
                        activeCol + 1,
                        tablePosition
                    )
                )
            }
        }
        table.rows.forEachIndexed { row, cells ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                cells.forEachIndexed { col, cell ->
                    // Keyed by position so a row/column insertion cannot hand
                    // one cell's popup to another cell.
                    androidx.compose.runtime.key(row, col) {
                        TableCellChip(
                            blockId = blockId,
                            row = row,
                            col = col,
                            cell = cell,
                            selected = row == activeRow && col == activeCol,
                            modifier = Modifier.weight(1f).padding(end = 4.dp),
                            onClick = {
                                activeCell = row to col
                                editorCell = row to col
                            }
                        )
                    }
                }
                // Pad short rows so the fields stay aligned to the columns.
                repeat((colCount - cells.size).coerceAtLeast(0)) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        // No second table preview for the question body: the whole-question
        // preview above already draws it. An option or a regular explanation
        // has no other preview, so [richCellPreview] opts into a local one —
        // that page is also what captures a cell structure's PNG.
        if (richCellPreview && hasRichCell) {
            Text(
                stringResource(R.string.chem_editor_preview),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            ContentElements(
                elements = listOf(table),
                cellBlockIds = listOf(blockId),
                onChemDiagram = onChemDiagram,
                modifier = Modifier.fillMaxWidth().testTag("table-preview-$blockId")
            )
        }
    }

    editorCell?.let { target ->
        val cell = table.rows.getOrNull(target.first)?.getOrNull(target.second)
        if (cell != null) {
            CellEditorDialog(
                blockId = blockId,
                row = target.first,
                col = target.second,
                cell = cell,
                rich = rich,
                onDismiss = { editorCell = null },
                onUpdateCell = onUpdateCell,
                onUpdateCellText = onUpdateCellText,
                onPickCellImage = onPickCellImage,
                onRemoveCellPart = onRemoveCellPart,
                onEditFormula = { partIndex, mathml ->
                    formulaTarget = CellFormulaTarget(target.first, target.second, partIndex, mathml)
                },
                onEditStructure = { partIndex, element ->
                    structureTarget = CellStructureTarget(target.first, target.second, partIndex, element)
                }
            )
        }
    }

    formulaTarget?.let { target ->
        CellFormulaDialog(
            target = target,
            blockId = blockId,
            onDismiss = { formulaTarget = null },
            onCommit = { mathml ->
                cellFormulaCommit(
                    blockId, target.row, target.col, target.partIndex, mathml, onSetCellPart
                )
                formulaTarget = null
            }
        )
    }
    structureTarget?.let { target ->
        CellStructureDialog(
            target = target,
            blockId = blockId,
            onDismiss = { structureTarget = null },
            onCommit = { element ->
                onSetCellPart?.invoke(
                    blockId, target.row, target.col, target.partIndex,
                    CellEditPart.Structure(element)
                )
                structureTarget = null
            }
        )
    }
}

/**
 * The compact, read-only chip that stands for one cell in the grid.
 *
 * It shows a short preview — prose in reading order with one label per rich
 * part — so the author can see the cell's shape without raw markup or base64.
 * Tapping it opens [CellEditorDialog], which holds the only editable field.
 * The chip stays empty-but-tappable for an empty cell.
 */
@Composable
private fun TableCellChip(
    blockId: String,
    row: Int,
    col: Int,
    cell: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val formulaToken = stringResource(R.string.chem_editor_cell_token_formula)
    val imageToken = stringResource(R.string.chem_editor_cell_token_image)
    val structureToken = stringResource(R.string.chem_editor_cell_token_structure)
    val parts = remember(cell) { cellEditParts(cell) }
    val preview = remember(parts, formulaToken, imageToken, structureToken) {
        parts.joinToString(" ") { part ->
            when (part) {
                is CellEditPart.Text -> InlineHtml.parse(part.html).text
                is CellEditPart.Formula -> formulaToken
                is CellEditPart.Image -> imageToken
                is CellEditPart.Structure -> structureToken
            }
        }.replace(Regex("\\s+"), " ").trim().take(CELL_CHIP_PREVIEW_CHARS)
    }
    val chipDescription = stringResource(
        R.string.table_cell_chip_cd,
        row + 1,
        col + 1,
        preview.ifBlank { stringResource(R.string.table_cell_empty) }
    )
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        // The active cell gets a clear outline so the toolbar's target is
        // visible, not only inferred from the last tap.
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .testTag("cell-chip-$blockId-$row-$col")
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                // A contentDescription replaces the chip's text for a screen
                // reader, so it carries the position and the same preview.
                contentDescription = chipDescription
            }
    ) {
        Text(
            preview.ifBlank { stringResource(R.string.table_cell_empty) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp)
        )
    }
}

/**
 * The popup cell editor: the token field, the per-cell menu and Done, in a
 * near-full-screen dialog so the soft keyboard and the token field have room.
 * The field edits the draft live through [onUpdateCellText]; Done only closes.
 */
@Composable
private fun CellEditorDialog(
    blockId: String,
    row: Int,
    col: Int,
    cell: String,
    rich: Boolean,
    onDismiss: () -> Unit,
    onUpdateCell: (row: Int, col: Int, value: String) -> Unit,
    onUpdateCellText: ((blockId: String, row: Int, col: Int, text: String) -> Unit)?,
    onPickCellImage: ((blockId: String, row: Int, col: Int, replacePart: Int?) -> Unit)?,
    onRemoveCellPart: ((blockId: String, row: Int, col: Int, partIndex: Int) -> Unit)?,
    onEditFormula: (partIndex: Int?, mathml: String) -> Unit,
    onEditStructure: (partIndex: Int?, element: ContentElement.ChemElement?) -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(blockId, row, col) { focus.requestFocus() }
    val label = stringResource(R.string.table_cell_label, row + 1, col + 1)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .safeDrawingPadding()
                .testTag("cell-dialog-$blockId-$row-$col")
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cell-dialog-title-$blockId-$row-$col")
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("cell-close-$blockId-$row-$col")
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TableCellField(
                    blockId = blockId,
                    row = row,
                    col = col,
                    cell = cell,
                    rich = rich,
                    focusRequester = focus,
                    modifier = Modifier.fillMaxWidth(),
                    onUpdateCell = onUpdateCell,
                    onUpdateCellText = onUpdateCellText,
                    onPickCellImage = onPickCellImage,
                    onRemoveCellPart = onRemoveCellPart,
                    onEditFormula = onEditFormula,
                    onEditStructure = onEditStructure
                )
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("cell-done-$blockId-$row-$col")
                    ) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
        }
    }
}

/**
 * One cell's text field plus its insert/edit menu. The field edits a value in
 * which every rich part is one opaque token ([cellEditorText]); the
 * transformation only changes how that token is displayed, so the value stays
 * a single character per part and deleting a label deletes exactly that part.
 */
@Composable
private fun TableCellField(
    blockId: String,
    row: Int,
    col: Int,
    cell: String,
    rich: Boolean,
    modifier: Modifier,
    focusRequester: FocusRequester? = null,
    onUpdateCell: (row: Int, col: Int, value: String) -> Unit,
    onUpdateCellText: ((blockId: String, row: Int, col: Int, text: String) -> Unit)?,
    onPickCellImage: ((blockId: String, row: Int, col: Int, replacePart: Int?) -> Unit)?,
    onRemoveCellPart: ((blockId: String, row: Int, col: Int, partIndex: Int) -> Unit)?,
    onEditFormula: (partIndex: Int?, mathml: String) -> Unit,
    onEditStructure: (partIndex: Int?, element: ContentElement.ChemElement?) -> Unit
) {
    val tableCellLabel = stringResource(R.string.table_cell_label, row + 1, col + 1)
    val formulaToken = stringResource(R.string.chem_editor_cell_token_formula)
    val imageToken = stringResource(R.string.chem_editor_cell_token_image)
    val structureToken = stringResource(R.string.chem_editor_cell_token_structure)
    val parts = remember(cell, rich) { if (rich) cellEditParts(cell) else emptyList() }
    val tokenKey = remember(blockId, row, col) { cellTokenKey(blockId, row, col) }
    val value = remember(cell, rich, tokenKey) {
        if (rich) cellEditorText(cell, tokenKey) else cell
    }
    val labels = remember(parts, formulaToken, imageToken, structureToken) {
        parts.map { part ->
            when (part.kind()) {
                CellEditPartKind.FORMULA -> formulaToken
                CellEditPartKind.IMAGE -> imageToken
                CellEditPartKind.STRUCTURE -> structureToken
                CellEditPartKind.TEXT -> ""
            }
        }
    }
    val transformation = remember(labels, parts, tokenKey) {
        CellTokenVisualTransformation(labels, parts, tokenKey)
    }
    val localFocus = remember { FocusRequester() }
    val focus = focusRequester ?: localFocus
    OutlinedTextField(
        value = value,
        onValueChange = {
            if (rich) onUpdateCellText?.invoke(blockId, row, col, it)
            else onUpdateCell(row, col, it)
        },
        visualTransformation = if (rich) transformation else VisualTransformation.None,
        trailingIcon = if (rich) {
            {
                CellEditMenu(
                    blockId = blockId,
                    row = row,
                    col = col,
                    parts = parts,
                    onFocusText = { focus.requestFocus() },
                    onFormula = { partIndex ->
                        val existing = parts.getOrNull(partIndex ?: -1) as? CellEditPart.Formula
                        onEditFormula(partIndex, existing?.mathml.orEmpty())
                    },
                    onImage = { partIndex ->
                        onPickCellImage?.invoke(blockId, row, col, partIndex)
                    },
                    onStructure = { partIndex ->
                        val existing = parts.getOrNull(partIndex ?: -1) as? CellEditPart.Structure
                        onEditStructure(partIndex, existing?.element)
                    },
                    onRemove = { partIndex ->
                        onRemoveCellPart?.invoke(blockId, row, col, partIndex)
                    }
                )
            }
        } else null,
        singleLine = true,
        label = { Text(tableCellLabel) },
        modifier = modifier
            .testTag("cell-field-$blockId-$row-$col")
            .focusRequester(focus)
            // A long value keeps scrolling horizontally in this single-line
            // field; the stored value is never truncated.
            .semantics {
                // A contentDescription would replace the field's value for
                // screen readers. A stateDescription adds the target-specific
                // cell label while the typed value stays accessible.
                stateDescription = tableCellLabel
            }
    )
}

/**
 * The single per-cell menu: text, formula, picture and structure. Insert and
 * edit share one entry per kind — tapping it edits the first part of that kind
 * when one exists and inserts a new one otherwise. Every present part also
 * gets a remove entry, so a cell can be cleaned without deleting its text.
 */
@Composable
private fun CellEditMenu(
    blockId: String,
    row: Int,
    col: Int,
    parts: List<CellEditPart>,
    onFocusText: () -> Unit,
    onFormula: (partIndex: Int?) -> Unit,
    onImage: (partIndex: Int?) -> Unit,
    onStructure: (partIndex: Int?) -> Unit,
    onRemove: (partIndex: Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val formulaIndexes = remember(parts) { parts.allIndexOfKind(CellEditPartKind.FORMULA) }
    val imageIndexes = remember(parts) { parts.allIndexOfKind(CellEditPartKind.IMAGE) }
    val structureIndexes = remember(parts) { parts.allIndexOfKind(CellEditPartKind.STRUCTURE) }
    val firstFormula = formulaIndexes.firstOrNull()
    val firstStructure = structureIndexes.firstOrNull()

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("cell-menu-$blockId-$row-$col")
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.chem_editor_cell_menu, row + 1, col + 1)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chem_editor_cell_text)) },
                onClick = {
                    onFocusText()
                    expanded = false
                },
                modifier = Modifier.testTag("cell-menu-text-$blockId-$row-$col")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chem_editor_cell_formula)) },
                onClick = {
                    onFormula(firstFormula)
                    expanded = false
                },
                modifier = Modifier.testTag(
                    "cell-menu-formula-$blockId-$row-$col-${firstFormula ?: "new"}"
                )
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chem_editor_cell_image)) },
                onClick = {
                    onImage(imageIndexes.firstOrNull())
                    expanded = false
                },
                modifier = Modifier.testTag(
                    "cell-menu-image-$blockId-$row-$col-${imageIndexes.firstOrNull() ?: "new"}"
                )
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chem_editor_cell_structure)) },
                onClick = {
                    onStructure(firstStructure)
                    expanded = false
                },
                modifier = Modifier.testTag(
                    "cell-menu-structure-$blockId-$row-$col-${firstStructure ?: "new"}"
                )
            )
            formulaIndexes.forEach { index ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chem_editor_cell_remove_formula)) },
                    onClick = {
                        onRemove(index)
                        expanded = false
                    },
                    modifier = Modifier.testTag("cell-menu-remove-$blockId-$row-$col-$index")
                )
            }
            imageIndexes.forEach { index ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chem_editor_cell_remove_image)) },
                    onClick = {
                        onRemove(index)
                        expanded = false
                    },
                    modifier = Modifier.testTag("cell-menu-remove-$blockId-$row-$col-$index")
                )
            }
            structureIndexes.forEach { index ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chem_editor_cell_remove_structure)) },
                    onClick = {
                        onRemove(index)
                        expanded = false
                    },
                    modifier = Modifier.testTag("cell-menu-remove-$blockId-$row-$col-$index")
                )
            }
        }
    }
}

private fun List<CellEditPart>.allIndexOfKind(kind: CellEditPartKind): List<Int> =
    indices.filter { this[it].kind() == kind }

/** MathLive in a near-full-screen dialog; the cell changes only on confirm. */
@Composable
private fun CellFormulaDialog(
    target: CellFormulaTarget,
    blockId: String,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit
) {
    var mathml by remember(target) { mutableStateOf(target.mathml) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .safeDrawingPadding()
                .testTag("cell-formula-dialog-$blockId-${target.row}-${target.col}")
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    stringResource(
                        R.string.chem_editor_cell_formula_title,
                        target.row + 1,
                        target.col + 1
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // MathLive sizes its virtual keyboard from the page font.
                    // A narrow phone needs a slightly smaller field font so all
                    // key columns fit, but never below the user's own text
                    // scale and never above the default: a low-vision user
                    // keeps the larger formula where the width allows it.
                    val narrow = maxWidth < 360.dp
                    val userScale = com.mcqapp.util.FontScale.LocalScale.current
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        MathLiveEditor(
                            initialLatex = mathMlToLatex(target.mathml),
                            blockId = "cell-formula-$blockId-${target.row}-${target.col}",
                            onMathMl = { _, value -> mathml = value },
                            fontScaleOverride = if (narrow) minOf(userScale, 1.0f) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cell-formula-editor-$blockId-${target.row}-${target.col}")
                        )
                        Text(
                            stringResource(R.string.tap_formula_to_edit),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = { onCommit(mathml) },
                        enabled = mathml.isNotBlank()
                    ) {
                        Text(
                            stringResource(
                                if (target.partIndex == null) R.string.chem_editor_cell_insert
                                else R.string.chem_editor_cell_replace
                            )
                        )
                    }
                }
            }
        }
    }
}

/** SMILES + optional handles for one cell; confirm stays disabled until valid. */
@Composable
private fun CellStructureDialog(
    target: CellStructureTarget,
    blockId: String,
    onDismiss: () -> Unit,
    onCommit: (ContentElement.ChemElement) -> Unit
) {
    val initial = target.element ?: ChemTemplates.BENZENE.toElement()
    var draft by remember(target) { mutableStateOf(initial) }
    val smilesValid = isValidCellSmiles(draft.smiles)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    R.string.chem_editor_cell_structure_title,
                    target.row + 1,
                    target.col + 1
                )
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ChemSpecFields(
                    element = draft,
                    onUpdate = { draft = it },
                    tag = { name -> "cell-chem-$name-$blockId-${target.row}-${target.col}" }
                )
                if (!smilesValid) {
                    Text(
                        stringResource(R.string.chem_editor_smiles_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCommit(draft) },
                enabled = smilesValid
            ) {
                Text(
                    stringResource(
                        if (target.partIndex == null) R.string.chem_editor_cell_insert
                        else R.string.chem_editor_cell_replace
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
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
