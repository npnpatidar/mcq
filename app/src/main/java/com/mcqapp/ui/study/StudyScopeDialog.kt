package com.mcqapp.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.mcqapp.data.local.TagCount

/** Which pool the learner chose in the scope sheet. */
internal enum class ScopeChoice { WHOLE, TAG, MISTAKES }

/**
 * A TAG scope is only actionable once a concrete tag is selected. Kept as a
 * pure seam so the guard can be tested on the JVM; the dialog both disables the
 * Start button with it and re-checks inside the click handler.
 */
internal fun canStartScope(choice: ScopeChoice, selectedTag: String): Boolean =
    choice != ScopeChoice.TAG || selectedTag.isNotBlank()

/**
 * Reuses the exact-tag picker already used by the Test setup sheet: folded tag
 * identity, chip label with a count. Choosing a scope navigates to a fresh
 * Study route, so the ViewModel key changes and the sitting is rebuilt from the
 * same scheduler pools with an id filter — no new schema, no schedule rewrite.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudyScopeDialog(
    paperId: String,
    categoryIds: List<String>,
    currentTag: String,
    currentMistakes: Boolean,
    loadTags: suspend (String, List<String>) -> List<TagCount>,
    onPickWhole: () -> Unit,
    onPickTag: (String) -> Unit,
    onPickMistakes: () -> Unit,
    onSaveMistakes: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var tags by remember(paperId, categoryIds) { mutableStateOf<List<TagCount>?>(null) }
    LaunchedEffect(paperId, categoryIds) {
        tags = runCatching { loadTags(paperId, categoryIds) }.getOrDefault(emptyList())
    }
    // Saveable: a rotation while the sheet is open must keep the learner's
    // pending choice, not snap back to the route that is still active.
    var choice by rememberSaveable {
        mutableStateOf(
            when {
                currentMistakes -> ScopeChoice.MISTAKES
                currentTag.isNotBlank() -> ScopeChoice.TAG
                else -> ScopeChoice.WHOLE
            }
        )
    }
    var selectedTag by rememberSaveable { mutableStateOf(currentTag) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.of_study_scope)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                FilterChip(
                    selected = choice == ScopeChoice.WHOLE,
                    onClick = { choice = ScopeChoice.WHOLE },
                    label = { Text(stringResource(R.string.of_scope_whole)) },
                    modifier = Modifier.testTag("study-scope-whole")
                )
                Spacer(Modifier.height(6.dp))
                FilterChip(
                    selected = choice == ScopeChoice.MISTAKES,
                    onClick = { choice = ScopeChoice.MISTAKES },
                    label = { Text(stringResource(R.string.of_scope_mistakes)) },
                    modifier = Modifier.testTag("study-scope-mistakes")
                )
                if (choice == ScopeChoice.MISTAKES) {
                    TextButton(
                        onClick = onSaveMistakes,
                        modifier = Modifier.testTag("study-scope-save-mistakes")
                    ) {
                        Text(stringResource(R.string.of_save_mistakes_pool))
                    }
                }
                Spacer(Modifier.height(6.dp))
                FilterChip(
                    selected = choice == ScopeChoice.TAG,
                    onClick = { choice = ScopeChoice.TAG },
                    label = { Text(stringResource(R.string.of_scope_tag)) },
                    modifier = Modifier.testTag("study-scope-tag")
                )
                if (choice == ScopeChoice.TAG) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.of_scope_pick_tag),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    if (tags.isNullOrEmpty()) {
                        Text(
                            stringResource(R.string.of_scope_no_tags),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            tags.orEmpty().forEach { counted ->
                                FilterChip(
                                    selected = selectedTag == counted.tagFolded,
                                    onClick = { selectedTag = counted.tagFolded },
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.name_count_line,
                                                counted.tag,
                                                counted.count
                                            )
                                        )
                                    },
                                    modifier = Modifier.testTag("study-scope-tag-${counted.tagFolded}")
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canStartScope(choice, selectedTag),
                onClick = {
                    when (choice) {
                        ScopeChoice.WHOLE -> onPickWhole()
                        ScopeChoice.MISTAKES -> onPickMistakes()
                        ScopeChoice.TAG -> if (selectedTag.isNotBlank()) onPickTag(selectedTag)
                    }
                },
                modifier = Modifier.padding(horizontal = 4.dp).testTag("study-scope-start")
            ) { Text(stringResource(R.string.of_scope_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
