package com.mcqapp.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.Paper
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestComparability
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestSetup
import com.mcqapp.domain.TestTime
import com.mcqapp.domain.TestType
import kotlin.math.roundToInt

/**
 * The phase-1 setup sheet: what kind of test, how many questions, how long,
 * and what the learner is about to get (sections 7.2-7.5).
 *
 * It replaces the duration-only picker, which could only ever start a
 * full-paper legacy session. Everything the sheet offers is a **bounded** plan
 * the session accepts: only Random and Mistakes are startable in phase 1, the
 * length is 20..150 in steps of ten, and the exclusion rule is always NONE.
 *
 * The rules live in [TestSetup] so they can be tested without a screen; this
 * file only draws them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TestSetupSheet(
    paper: Paper,
    /** The type the entry point preselected (Random or Mistakes). */
    initialType: TestType,
    /** The paper's mistake count: the whole-paper pool for Mistakes. */
    mistakeCount: Int,
    /** The ticked branches; empty means the whole paper. */
    categoryIds: List<String>,
    /**
     * Counts the mistakes inside an expanded scope. The paper-level badge
     * counts every mistake in the paper, which would overstate a scoped
     * pool, so the scope needs its own count.
     */
    loadScopedMistakeCount: suspend (List<String>) -> Int,
    onDismiss: () -> Unit,
    onStart: (type: TestType, requestedCount: Int, minutes: Int) -> Unit
) {
    var type by rememberSaveable(initialType) { mutableStateOf(initialType) }
    var requestedCount by rememberSaveable { mutableIntStateOf(TestPlan.DEFAULT_LENGTH) }
    /** Null means "follow the suggested pace"; a number is the learner's own. */
    var minutesOverride by rememberSaveable { mutableStateOf<String?>(null) }

    val expanded = remember(paper.categories, categoryIds) {
        CategorySelection.orderedIds(paper.categories, categoryIds)
    }
    val scopeKey = remember(paper.id, expanded) {
        if (categoryIds.isEmpty()) {
            ScopeKey.wholePaper(paper.id)
        } else {
            ScopeKey.ofExpanded(expanded)
        }
    }
    // The scope's question count: the paper's own when nothing is ticked.
    val questionPool = remember(paper.categories, paper.totalQuestions, categoryIds) {
        if (categoryIds.isEmpty()) {
            paper.totalQuestions
        } else {
            CategorySelection.questionCount(paper.categories, categoryIds)
        }
    }

    // The scoped mistake pool is a query of its own; until it lands the
    // sheet cannot say how many mistakes the scope holds.
    var scopedMistakes by remember(paper.id, expanded) { mutableStateOf<Int?>(null) }
    LaunchedEffect(paper.id, expanded) {
        if (categoryIds.isEmpty()) {
            scopedMistakes = null
        } else {
            scopedMistakes = runCatching { loadScopedMistakeCount(expanded) }.getOrDefault(0)
        }
    }
    val mistakePool = if (categoryIds.isEmpty()) mistakeCount else scopedMistakes
    val poolLoading = type == TestType.MISTAKES && mistakePool == null
    val available = if (type == TestType.MISTAKES) mistakePool ?: 0 else questionPool

    val setup = TestSetup(
        paperId = paper.id,
        categoryIds = categoryIds,
        scopeKey = scopeKey,
        type = type,
        requestedCount = requestedCount,
        time = minutesOverride?.let { TestTime.Fixed(it.toIntOrNull() ?: 0) }
            ?: TestTime.Suggested,
        available = available
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(R.string.test_setup_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                paper.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // --- type ---
            Text(stringResource(R.string.test_setup_type), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = type == TestType.RANDOM,
                    onClick = { type = TestType.RANDOM },
                    label = { Text(stringResource(R.string.test_setup_type_random)) }
                )
                FilterChip(
                    selected = type == TestType.MISTAKES,
                    onClick = { type = TestType.MISTAKES },
                    label = { Text(stringResource(R.string.test_setup_type_mistakes)) }
                )
            }
            Text(
                stringResource(
                    if (type == TestType.MISTAKES) {
                        R.string.test_setup_type_mistakes_help
                    } else {
                        R.string.test_setup_type_random_help
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.test_setup_more_types),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // --- scope and pool ---
            Text(
                if (categoryIds.isEmpty()) {
                    stringResource(R.string.test_setup_scope_whole_paper)
                } else {
                    stringResource(R.string.test_setup_scope_categories, categoryIds.size)
                },
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                when {
                    poolLoading -> stringResource(R.string.test_setup_counting)
                    type == TestType.MISTAKES -> stringResource(
                        R.string.test_setup_pool_mistakes, mistakePool ?: 0
                    )
                    else -> stringResource(R.string.test_setup_pool_questions, questionPool)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // --- length ---
            if (setup.short()) {
                // The whole scope is below the minimum: there is nothing to
                // choose, the session runs every question it holds.
                Text(
                    stringResource(R.string.test_setup_short_test, setup.resolvedCount()),
                    style = MaterialTheme.typography.labelLarge
                )
            } else {
                Text(
                    stringResource(R.string.test_setup_length),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    stringResource(R.string.test_setup_count_label, requestedCount),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = TestPlan.LENGTHS.indexOf(requestedCount)
                        .coerceAtLeast(0)
                        .toFloat(),
                    onValueChange = {
                        val index = it.roundToInt().coerceIn(0, TestPlan.LENGTHS.lastIndex)
                        requestedCount = TestPlan.LENGTHS[index]
                    },
                    valueRange = 0f..TestPlan.LENGTHS.lastIndex.toFloat(),
                    // One stop between the selectable lengths: the slider
                    // lands on a length, never between two.
                    steps = TestPlan.LENGTHS.size - 2
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TestPlan.PRESETS.forEach { preset ->
                        FilterChip(
                            selected = requestedCount == preset,
                            onClick = { requestedCount = preset },
                            label = { Text(preset.toString()) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- time ---
            Text(stringResource(R.string.test_setup_time), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = minutesOverride ?: setup.suggestedMinutes().toString(),
                onValueChange = { minutesOverride = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text(stringResource(R.string.test_setup_minutes)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.test_setup_suggested, setup.suggestedMinutes()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (minutesOverride != null) {
                    TextButton(onClick = { minutesOverride = null }) {
                        Text(stringResource(R.string.test_setup_use_suggested))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- preview ---
            Text(
                buildString {
                    append(stringResource(
                        when (type) {
                            TestType.MISTAKES -> R.string.test_setup_type_mistakes
                            else -> R.string.test_setup_type_random
                        }
                    ))
                    append(" · ")
                    append(stringResource(R.string.test_setup_count_label, setup.resolvedCount()))
                    append(" · ")
                    append(if (setup.minutes() <= 0) {
                        stringResource(R.string.test_setup_untimed)
                    } else {
                        stringResource(R.string.test_setup_minutes_label, setup.minutes())
                    })
                    append(" · ")
                    append(stringResource(
                        when (setup.comparability()) {
                            TestComparability.COMPARABLE -> R.string.test_preview_comparable
                            TestComparability.PRACTICE -> R.string.test_preview_practice
                            TestComparability.SHORT -> R.string.test_preview_short
                        },
                        setup.resolvedCount()
                    ))
                },
                style = MaterialTheme.typography.bodyMedium
            )
            val note = when {
                poolLoading -> null
                type == TestType.MISTAKES && available == 0 ->
                    stringResource(R.string.test_setup_no_mistakes)
                else -> setup.message()
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { onStart(type, requestedCount, setup.minutes()) },
                enabled = setup.startable() && !poolLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.start_test))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
