package com.mcqapp.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper

@Composable
internal fun PaperDialog(
    /** Null creates a paper; non-null edits that paper's details in place. */
    paper: Paper? = null,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Double) -> Unit
) {
    var title by rememberSaveable(paper) { mutableStateOf(paper?.title ?: "") }
    var description by rememberSaveable(paper) { mutableStateOf(paper?.description ?: "") }
    var duration by rememberSaveable(paper) {
        mutableStateOf(paper?.durationMinutes?.takeIf { it > 0 }?.toString() ?: "")
    }
    var negative by rememberSaveable(paper) {
        mutableStateOf(
            paper?.negativeMarking?.takeIf { it > 0.0 }?.let { com.mcqapp.util.formatDecimalEntry(it) } ?: ""
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (paper == null) R.string.new_paper else R.string.edit_paper)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.description)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.duration_minutes_0_untimed)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = negative,
                    onValueChange = { input -> negative = com.mcqapp.util.filterDecimalEntry(input) },
                    label = { Text(stringResource(R.string.negative_marking_e_g_0_33)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        title.trim(),
                        description.trim(),
                        duration.toIntOrNull() ?: 0,
                        negative.toDoubleOrNull() ?: 0.0
                    )
                },
                enabled = title.isNotBlank()
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}


/**
 * The per-attempt duration picker shown when Test is tapped. [initialMinutes]
 * is the paper's stored duration (0 when untimed), so the field opens ready
 * to start unchanged; blank means untimed for this run only — the paper's
 * stored duration is not rewritten.
 */
@Composable
internal fun TestDurationDialog(
    paperTitle: String,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onStart: (minutes: Int) -> Unit
) {
    var duration by remember(paperTitle, initialMinutes) {
        mutableStateOf(if (initialMinutes > 0) initialMinutes.toString() else "")
    }
    // Clamp through ExamTiming so a typed 10_000_000 lands on the same
    // ceiling the importer and the ViewModel use.
    val minutes = com.mcqapp.domain.ExamTiming.minutesFrom(duration.toIntOrNull() ?: 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.test_action)) },
        text = {
            Column {
                Text(
                    paperTitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text(stringResource(R.string.duration_minutes_blank_untimed)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(minutes) }) { Text(stringResource(R.string.start_test)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}


@Composable
internal fun EditCategoryDialog(
    category: CategoryNode,
    papers: List<Paper>,
    onDismiss: () -> Unit,
    onSave: (title: String, parentId: String?) -> Unit
) {
    var title by rememberSaveable { mutableStateOf(category.title) }
    val paper = papers.firstOrNull { it.id == category.paperId }
    val categories = paper?.categories ?: emptyList()
    var selectedParent by rememberSaveable { mutableStateOf(category.parentId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_category)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                // The category's own subtree cannot be its parent (cycles);
                // those nodes are hidden from the picker.
                ParentPicker(
                    categories = categories,
                    selectedParent = selectedParent,
                    onSelect = { selectedParent = it },
                    excludeSubtreeOf = category.id
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), selectedParent) },
                enabled = title.isNotBlank()
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
internal fun CategoryDialog(
    papers: List<Paper>,
    paperId: String,
    parentId: String?,
    onDismiss: () -> Unit,
    onSave: (title: String, parentId: String?) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    val paper = papers.firstOrNull { it.id == paperId }
    val categories = paper?.categories ?: emptyList()
    var selectedParent by rememberSaveable { mutableStateOf(parentId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_category)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                ParentPicker(
                    categories = categories,
                    selectedParent = selectedParent,
                    onSelect = { selectedParent = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), selectedParent) },
                enabled = title.isNotBlank()
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * The radio list of possible parent categories. The dialog clips content
 * taller than its max height, so the list scrolls inside a bounded column —
 * a paper with many categories otherwise hides its lower half behind an
 * edge nothing can scroll to.
 */
@Composable
private fun ParentPicker(
    categories: List<CategoryNode>,
    selectedParent: String?,
    onSelect: (String?) -> Unit,
    excludeSubtreeOf: String? = null
) {
    Text(
        stringResource(R.string.parent_category),
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium
    )
    Column(
        modifier = Modifier
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selectedParent == null,
                onClick = { onSelect(null) }
            )
            Text(stringResource(R.string.none_top_level))
        }
        categories.forEach { node ->
            ParentOptions(
                node = node,
                depth = 0,
                selectedParent = selectedParent,
                onSelect = { onSelect(it) },
                excludeSubtreeOf = excludeSubtreeOf
            )
        }
    }
}

@Composable
private fun ParentOptions(
    node: CategoryNode,
    depth: Int,
    selectedParent: String?,
    onSelect: (String) -> Unit,
    excludeSubtreeOf: String? = null
) {
    // A category's own subtree is hidden from its parent picker (cycles).
    if (node.id == excludeSubtreeOf) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = node.id == selectedParent,
            onClick = { onSelect(node.id) }
        )
        Text(node.title)
    }
    node.children.forEach { child ->
        ParentOptions(child, depth + 1, selectedParent, onSelect, excludeSubtreeOf)
    }
}
