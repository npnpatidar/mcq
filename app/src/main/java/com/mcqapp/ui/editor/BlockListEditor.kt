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
    onAddBlock: (EditorBlockType) -> Unit,
    onUpdateBlock: (index: Int, element: ContentElement) -> Unit,
    onRemoveBlock: (index: Int) -> Unit,
    onMoveUp: (index: Int) -> Unit,
    onMoveDown: (index: Int) -> Unit,
    onUpdateCell: ((index: Int, row: Int, col: Int, value: String) -> Unit)? = null,
    onAddRow: ((index: Int) -> Unit)? = null,
    onRemoveRow: ((index: Int, row: Int) -> Unit)? = null,
    onAddColumn: ((index: Int) -> Unit)? = null,
    onRemoveColumn: ((index: Int, col: Int) -> Unit)? = null,
    onPickImage: (onPicked: (String) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val tablesEditable = onUpdateCell != null && onAddRow != null &&
        onRemoveRow != null && onAddColumn != null && onRemoveColumn != null
    Column(modifier = modifier.fillMaxWidth()) {
        elements.forEachIndexed { index, element ->
            // Keyed by content: inserting, removing or reordering a block now
            // gives the moved block its own composition instead of handing the
            // existing WebView to a different formula.
            androidx.compose.runtime.key(blockKey(element, index)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Block ${index + 1} (${blockLabel(element)})",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onMoveUp(index) }, enabled = index > 0) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.move_block_up))
                    }
                    IconButton(onClick = { onMoveDown(index) }, enabled = index < elements.size - 1) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.move_block_down))
                    }
                    IconButton(onClick = { onRemoveBlock(index) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_block))
                    }
                }
                when (element) {
                    is ContentElement.TextElement -> OutlinedTextField(
                        value = element.text,
                        onValueChange = { onUpdateBlock(index, ContentElement.TextElement(it)) },
                        label = { stringResource(R.string.text) },
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
                                label = { stringResource(R.string.image_url) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = {
                                onPickImage { picked ->
                                    onUpdateBlock(index, ContentElement.ImageElement(picked))
                                }
                            }) {
                                stringResource(R.string.pick)
                            }
                        }
                        if (element.src.isNotBlank()) {
                            QuestionImage(src = element.src, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    is ContentElement.MathElement -> MathLiveEditor(
                        initialLatex = mathMlToLatex(element.mathml),
                        onMathMl = { onUpdateBlock(index, ContentElement.MathElement(it)) }
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
 * Stable identity for a content block.
 *
 * The content model carries no ids, so identity is the block's own payload
 * plus its position: editing a block changes its key (a fresh editor is the
 * right answer when the formula itself changed), while an unrelated block
 * moving does not.
 */
internal fun blockKey(element: ContentElement, index: Int): String = when (element) {
    is ContentElement.TextElement -> "text:$index:${element.text.hashCode()}"
    is ContentElement.ImageElement -> "image:$index:${element.src.hashCode()}"
    is ContentElement.TableElement -> "table:$index:${element.rows.hashCode()}"
    is ContentElement.MathElement -> "math:$index:${element.mathml.hashCode()}"
}

private fun blockLabel(element: ContentElement): String = when (element) {
    is ContentElement.TextElement -> "Text"
    is ContentElement.ImageElement -> "Image"
    is ContentElement.TableElement -> "Table"
    is ContentElement.MathElement -> "Formula"
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
                        IconButton(onClick = { onRemoveColumn(col) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_column_col_1))
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
                    OutlinedTextField(
                        value = cell,
                        onValueChange = { onUpdateCell(row, col, it) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp)
                    )
                }
                // Pad short rows so the delete button stays aligned.
                repeat((colCount - cells.size).coerceAtLeast(0)) {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = { onRemoveRow(row) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_row_row_1))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddRow) { stringResource(R.string.add_row) }
            OutlinedButton(onClick = onAddColumn) { stringResource(R.string.add_column) }
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
            stringResource(R.string.add_block)
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { stringResource(R.string.text) },
                onClick = { onAddBlock(EditorBlockType.TEXT); expanded = false }
            )
            DropdownMenuItem(
                text = { stringResource(R.string.image) },
                onClick = { onAddBlock(EditorBlockType.IMAGE); expanded = false }
            )
            DropdownMenuItem(
                text = { stringResource(R.string.table) },
                onClick = { onAddBlock(EditorBlockType.TABLE); expanded = false }
            )
            DropdownMenuItem(
                text = { stringResource(R.string.formula) },
                onClick = { onAddBlock(EditorBlockType.MATH); expanded = false }
            )
        }
    }
}
