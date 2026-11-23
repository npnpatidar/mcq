package com.mcqapp.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.Paper

/**
 * The paper field starts on the first real paper, but the papers flow can still
 * be empty when a shared snippet opens the dialog. Once papers arrive the blank
 * choice must be filled before Save, or the flashcard silently creates a new
 * paper. An explicit "New paper" pick is blank too; the caller guards that case
 * with `paperChosen` so it is not overwritten when the flow emits again.
 */
internal fun initialQuickCapturePaperId(current: String, papers: List<Paper>): String =
    if (current.isBlank()) papers.firstOrNull()?.id.orEmpty() else current

/**
 * Quick capture for plain text shared from another app: a front/back pair with
 * paper, category and tag selection, saved straight to the bank as a flashcard
 * (no import preview, no schema change). Copy lives here and in resources; the
 * ViewModel owns the write.
 */
@Composable
internal fun QuickCaptureDialog(
    papers: List<Paper>,
    sharedText: String,
    onSave: (
        paperId: String?, paperTitle: String,
        categoryId: String?, categoryTitle: String,
        front: String, back: String, tag: String
    ) -> Unit,
    onDismiss: () -> Unit
) {
    // Every field the learner typed is saveable: a rotation used to clear the
    // front/back/tag and the new paper/category names, and a second Save then
    // wrote a second card. The ephemeral menu/error flags stay plain remember.
    var front by rememberSaveable { mutableStateOf(sharedText.take(2000)) }
    var back by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var newPaperTitle by rememberSaveable { mutableStateOf("") }
    var newCategoryTitle by rememberSaveable { mutableStateOf("") }
    var selectedPaperId by rememberSaveable { mutableStateOf(papers.firstOrNull()?.id.orEmpty()) }
    // Distinguish "papers not loaded yet" from the learner deliberately picking
    // "New paper" — both leave selectedPaperId blank, but only the first may be
    // filled in when the papers flow emits.
    var paperChosen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(papers) {
        if (!paperChosen) selectedPaperId = initialQuickCapturePaperId(selectedPaperId, papers)
    }
    var selectedCategoryId by rememberSaveable { mutableStateOf("") }
    var paperExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var frontError by remember { mutableStateOf(false) }
    val selectedPaper = papers.firstOrNull { it.id == selectedPaperId }
    val categories = selectedPaper?.categories.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.of_quick_capture_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it; frontError = false },
                    label = { Text(stringResource(R.string.of_quick_capture_front)) },
                    isError = frontError,
                    modifier = Modifier.fillMaxWidth().testTag("quick-capture-front")
                )
                if (frontError) {
                    Text(
                        stringResource(R.string.of_quick_capture_missing_front),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = back,
                    onValueChange = { back = it },
                    label = { Text(stringResource(R.string.of_quick_capture_back)) },
                    modifier = Modifier.fillMaxWidth().testTag("quick-capture-back")
                )
                Spacer(Modifier.height(8.dp))
                // A selector, never an editor: it only mirrors the chosen paper.
                // Binding it to newPaperTitle when nothing is chosen put two
                // editable "Paper" fields on screen for the same value.
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedPaper?.title.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.of_quick_capture_paper)) },
                        trailingIcon = {
                            IconButton(onClick = { paperExpanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("quick-capture-paper")
                    )
                    DropdownMenu(
                        expanded = paperExpanded,
                        onDismissRequest = { paperExpanded = false }
                    ) {
                        papers.forEach { paper ->
                            DropdownMenuItem(
                                text = { Text(paper.title) },
                                onClick = {
                                    selectedPaperId = paper.id
                                    paperChosen = true
                                    selectedCategoryId = ""
                                    paperExpanded = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.of_quick_capture_new_paper)) },
                            onClick = { selectedPaperId = ""; paperChosen = true; paperExpanded = false }
                        )
                    }
                }
                if (selectedPaperId.isEmpty()) {
                    OutlinedTextField(
                        value = newPaperTitle,
                        onValueChange = { newPaperTitle = it },
                        label = { Text(stringResource(R.string.of_quick_capture_new_paper)) },
                        modifier = Modifier.fillMaxWidth().testTag("quick-capture-new-paper")
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = categories.firstOrNull { it.id == selectedCategoryId }?.title
                            ?: newCategoryTitle,
                        onValueChange = { newCategoryTitle = it },
                        readOnly = selectedCategoryId.isNotEmpty(),
                        label = { Text(stringResource(R.string.of_quick_capture_category)) },
                        trailingIcon = {
                            IconButton(onClick = { categoryExpanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("quick-capture-category")
                    )
                    DropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.title) },
                                onClick = { selectedCategoryId = cat.id; categoryExpanded = false }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_category)) },
                            onClick = { selectedCategoryId = ""; categoryExpanded = false }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text(stringResource(R.string.of_quick_capture_tag)) },
                    modifier = Modifier.fillMaxWidth().testTag("quick-capture-tag")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (front.isBlank()) {
                        frontError = true
                        return@TextButton
                    }
                    // Hand the fields to the owner on the tap. The owner clears
                    // the offered snippet immediately and lets the ViewModel
                    // finish the write in its own scope, so a rotation here
                    // cannot re-offer this dialog and save a second card.
                    onSave(
                        selectedPaperId.ifBlank { null },
                        newPaperTitle,
                        selectedCategoryId.ifBlank { null },
                        newCategoryTitle,
                        front,
                        back,
                        tag
                    )
                },
                modifier = Modifier.testTag("quick-capture-save")
            ) { Text(stringResource(R.string.of_quick_capture_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
