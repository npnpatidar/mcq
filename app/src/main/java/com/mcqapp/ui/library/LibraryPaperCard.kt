package com.mcqapp.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.util.Logger

/** One library card: a paper's actions, drill dialog and category management. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaperCard(
    paper: Paper,
    onStart: () -> Unit,
    onBrowse: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onAddCategory: () -> Unit,
    onEditQuestion: (String, String) -> Unit,
    onAddQuestion: (String) -> Unit,
    onExportCategory: (String, String) -> Unit,
    onMoveCategory: (String, Int) -> Unit = { _, _ -> },
    onNestCategory: (String) -> Unit = {},
    onPromoteCategory: (String) -> Unit = {},
    onEditCategory: (CategoryNode) -> Unit = {},
    onDeleteCategory: (String) -> Unit = {},
    onPracticeMistakes: () -> Unit = {},
    onDrill: (Int, Int) -> Unit = { _, _ -> },
    onStudy: () -> Unit = {},
    onStudyLeeches: () -> Unit = {},
    mistakeCount: Int = 0,
    dueCount: Int = 0,
    freshCount: Int = 0,
    leechCount: Int = 0
) {
    var showDrillDialog by remember { mutableStateOf(false) }
    var drillCountText by remember { mutableStateOf("10") }
    var drillMinutesText by remember { mutableStateOf("5") }
    val drillCount = drillCountText.toIntOrNull()?.takeIf { it > 0 }
    val drillMinutes = drillMinutesText.toIntOrNull()?.takeIf { it > 0 }
    // Asking for more questions than the paper holds used to be accepted
    // silently: Drill.sample returns the whole set, so the drill just came out
    // smaller than the dialog promised. Refuse it here instead.
    val available = paper.totalQuestions
    val countCheck = drillCount?.let {
        com.mcqapp.domain.Drill.checkCount(it, available)
    }
    val drillCountValid = countCheck is com.mcqapp.domain.Drill.CountCheck.Ok
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        paper.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("paper-title-${paper.id}")
                    )
                    if (paper.description.isNotBlank()) {
                        Text(
                            paper.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        buildString {
                            append("${paper.totalQuestions} questions")
                            if (paper.durationMinutes > 0) append(" • ${paper.durationMinutes} min")
                            if (paper.negativeMarking > 0) append(" • -${paper.negativeMarking} neg")
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("paper-manage-${paper.id}")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.manage))
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStudy,
                    modifier = Modifier.testTag("paper-study-${paper.id}"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when {
                            dueCount > 0 && freshCount > 0 -> "Study ($dueCount due, $freshCount new)"
                            dueCount > 0 -> "Study ($dueCount due)"
                            freshCount > 0 -> "Study ($freshCount new)"
                            else -> "Study"
                        }
                    )
                }
                Button(onClick = onStart) {
                    Text(stringResource(R.string.test_action))
                }
                if (leechCount > 0) {
                    // Used to run the identical handler as "Study", so the
                    // label promised the tricky questions and delivered the
                    // ordinary due+new queue.
                    TextButton(onClick = onStudyLeeches) {
                        Text("${leechCount} tricky")
                    }
                }
                OutlinedButton(onClick = onExport) {
                    Text(stringResource(R.string.export))
                }
                OutlinedButton(
                    onClick = onBrowse,
                    modifier = Modifier.testTag("paper-browse-${paper.id}")
                ) {
                    Text(stringResource(R.string.browse))
                }
                OutlinedButton(
                    onClick = {
                        // Default to something this paper can actually supply: ten
                        // where there are ten or more, otherwise the whole paper.
                        // Falling back to 1 for any shortfall made a 2-question
                        // paper open on a single-question drill.
                        drillCountText = minOf(10, paper.totalQuestions)
                            .coerceAtLeast(1).toString()
                        showDrillDialog = true
                    },
                    modifier = Modifier.testTag("paper-drill-${paper.id}")
                ) {
                    Text(stringResource(R.string.drill))
                }
                if (mistakeCount > 0) {
                    OutlinedButton(onClick = {
                        Logger.i("LIB", "Start paper: paperId=${paper.id}")
                        onPracticeMistakes()
                    }) {
                        Text("Mistakes ($mistakeCount)")
                    }
                }
            }
            if (showDrillDialog) {
                AlertDialog(
                    onDismissRequest = { showDrillDialog = false },
                    title = { Text(stringResource(R.string.quick_drill)) },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = drillCountText,
                                onValueChange = { drillCountText = it.filter { c -> c.isDigit() }.take(4) },
                                label = { Text(stringResource(R.string.questions)) },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = drillMinutesText,
                                onValueChange = { drillMinutesText = it.filter { c -> c.isDigit() }.take(4) },
                                label = { Text(stringResource(R.string.minutes)) },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            val tooMany = countCheck as?
                                com.mcqapp.domain.Drill.CountCheck.TooManyForPaper
                            val noQuestions =
                                countCheck is com.mcqapp.domain.Drill.CountCheck.NoQuestionsAvailable
                            val guidance = when {
                                noQuestions ->
                                    "This paper has no questions yet, so there is nothing to drill."
                                tooMany != null ->
                                    "This paper has only $available question${if (available == 1) "" else "s"}. " +
                                        "Enter $available or fewer."
                                drillCount != null && drillMinutes != null ->
                                    "$drillCount random question${if (drillCount == 1) "" else "s"}, " +
                                        "$drillMinutes:00 on the clock."
                                else -> "Enter positive numbers for both."
                            }
                            Text(
                                guidance,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (noQuestions || tooMany != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDrillDialog = false
                                Logger.i("LIB", "Drill: paperId=${paper.id}, count=$drillCount, min=$drillMinutes")
                                onDrill(drillCount!!, drillMinutes!!)
                            },
                            enabled = drillCount != null && drillMinutes != null && drillCountValid
                        ) { Text(stringResource(R.string.start_drill)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDrillDialog = false }) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.categories), style = MaterialTheme.typography.labelLarge)
                    paper.categories.forEachIndexed { index, node ->
                        CategoryRow(
                            node = node,
                            depth = 0,
                            canMoveUp = index > 0,
                            canMoveDown = index < paper.categories.lastIndex,
                            onMoveCategory = onMoveCategory,
                            onNestCategory = onNestCategory,
                            onPromoteCategory = onPromoteCategory,
                            onEditCategory = onEditCategory,
                            onDeleteCategory = onDeleteCategory,
                            onEditQuestion = onEditQuestion,
                            onAddQuestion = onAddQuestion,
                            onExportCategory = onExportCategory
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onAddCategory, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.add_category))
                        }
                        TextButton(onClick = onDuplicate, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.duplicate))
                        }
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f).testTag("paper-delete-${paper.id}")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.delete_paper))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    node: CategoryNode,
    depth: Int,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveCategory: (String, Int) -> Unit = { _, _ -> },
    onNestCategory: (String) -> Unit = {},
    onPromoteCategory: (String) -> Unit = {},
    onEditCategory: (CategoryNode) -> Unit = {},
    onDeleteCategory: (String) -> Unit = {},
    onEditQuestion: (String, String) -> Unit,
    onAddQuestion: (String) -> Unit,
    onExportCategory: (String, String) -> Unit
) {
    Column(modifier = Modifier.padding(start = (depth * 16).dp)) {
        // Eight 48dp actions do not fit one line on a phone, so the row
        // splits: movement gestures beside the title (line 1) and the
        // management set on their own line (line 2). Sub-48dp buttons are
        // rejected by UiAccessibilityTest for good reason.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                node.title + if (node.totalQuestionCount > 0) " (${node.totalQuestionCount})" else "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (canMoveUp || canMoveDown) {
                IconButton(onClick = { onMoveCategory(node.id, -1) }, enabled = canMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
                }
                IconButton(onClick = { onMoveCategory(node.id, +1) }, enabled = canMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
                }
            }
            // Outline gestures: -> nests into the category above (nothing
            // moves visually), <- promotes out of the parent.
            IconButton(onClick = { onNestCategory(node.id) }, enabled = canMoveUp) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = stringResource(R.string.nest_category))
            }
            IconButton(onClick = { onPromoteCategory(node.id) }, enabled = depth > 0) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = stringResource(R.string.promote_category))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onEditCategory(node) }) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_category))
            }
            IconButton(onClick = { onExportCategory(node.id, node.title) }) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.export_category))
            }
            IconButton(onClick = { onAddQuestion(node.id) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_question))
            }
            IconButton(onClick = { onDeleteCategory(node.id) }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_category))
            }
        }
        node.children.forEachIndexed { index, child ->
            CategoryRow(
                node = child,
                depth = depth + 1,
                canMoveUp = index > 0,
                canMoveDown = index < node.children.lastIndex,
                onMoveCategory = onMoveCategory,
                onNestCategory = onNestCategory,
                onPromoteCategory = onPromoteCategory,
                onEditCategory = onEditCategory,
                onDeleteCategory = onDeleteCategory,
                onEditQuestion = onEditQuestion,
                onAddQuestion = onAddQuestion,
                onExportCategory = onExportCategory
            )
        }
    }
}
