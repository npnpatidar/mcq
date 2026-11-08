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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestComparability
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestSetup
import com.mcqapp.domain.TestTime
import com.mcqapp.domain.TestType
import com.mcqapp.domain.signals.Breadth
import com.mcqapp.domain.signals.Composition
import com.mcqapp.domain.signals.SelectionPreview
import com.mcqapp.ui.common.testTypeLabelRes
import com.mcqapp.ui.common.testTypeLabelText
import kotlin.math.roundToInt

/**
 * The phase-1 setup sheet: what kind of test, how many questions, how long,
 * and what the learner is about to get (sections 7.2-7.5).
 *
 * It replaces the duration-only picker, which could only ever start a
 * full-paper legacy session. Everything the sheet offers is a **bounded** plan
 * the session accepts: Random, Balanced, Mistakes and the seven focus types in
 * [TestSetup.STARTABLE_TYPES], a length of 20..150 in steps of ten, and the
 * exclusion rule the settings choose.
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
    /** Whether representative tests should avoid recently asked questions. */
    avoidRecentQuestions: Boolean,
    /**
     * Counts the mistakes inside an expanded scope. The paper-level badge
     * counts every mistake in the paper, which would overstate a scoped
     * pool, so the scope needs its own count.
     */
    loadScopedMistakeCount: suspend (List<String>) -> Int,
    /**
     * The smallest passage block the selected type would sample. The sampler
     * pre-check uses it so Start cannot emit a route that fails at load
     * (section 9.6).
     */
    loadMinBlockSize: suspend (TestType, List<String>) -> Int,
    /**
     * The live composition preview for a signal-selected type. Display-only: a
     * failure leaves the preview blank and Start still runs the real selector.
     * [variant] is the custom choice (phase 3): the folded tag or difficulty
     * name, or null.
     */
    loadPreview: suspend (TestType, Int, Breadth, String?) -> SelectionPreview,
    /** The scope's distinct tags for the phase-3 Tag picker. */
    loadTags: suspend () -> List<com.mcqapp.data.local.TagCount> = { emptyList() },
    onDismiss: () -> Unit,
    onStart: (
        type: TestType,
        requestedCount: Int,
        minutes: Int,
        exclusion: ExclusionMode,
        breadth: Breadth,
        variant: String?
    ) -> Unit
) {
    var type by rememberSaveable(initialType) { mutableStateOf(initialType) }
    var requestedCount by rememberSaveable { mutableIntStateOf(TestPlan.DEFAULT_LENGTH) }
    var breadth by rememberSaveable { mutableStateOf(Breadth.MEDIUM) }
    /** The custom choice (phase 3): folded tag for `TAG`, difficulty name else. */
    var variant by rememberSaveable { mutableStateOf<String?>(null) }
    /** The scope's distinct tags, loaded once for the Tag picker. */
    var tags by remember(paper.id, categoryIds) { mutableStateOf<List<com.mcqapp.data.local.TagCount>?>(null) }
    LaunchedEffect(paper.id, categoryIds, type) {
        if (type == TestType.TAG && tags == null) {
            tags = runCatching { loadTags() }.getOrDefault(emptyList())
        }
    }
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
    // A custom type's pool comes from the preview (computed below); until
    // it lands the pool reads empty and Start waits.
    val isCustom = type == TestType.BOOKMARKED || type == TestType.TAG ||
        type == TestType.AUTHORED_DIFFICULTY || type == TestType.SLOWEST

    // The live preview of what the current request would select. It is a read
    // of the same signals the session uses, so the composition the learner sees
    // before Start is the one the attempt records.
    var selectionPreview by remember(paper.id, expanded) {
        mutableStateOf<SelectionPreview?>(null)
    }
    var previewLoading by remember(paper.id, expanded) { mutableStateOf(false) }
    LaunchedEffect(paper.id, expanded, type, requestedCount, breadth, variant) {
        if (!type.usesSignalSelector) {
            selectionPreview = null
            previewLoading = false
            return@LaunchedEffect
        }
        previewLoading = true
        selectionPreview = runCatching {
            loadPreview(type, requestedCount, breadth, variant)
        }.getOrNull()
        previewLoading = false
    }
    // A custom type's pool comes from the preview: Start clamps against the
    // pool the selector will actually draw from, not the whole scope.
    val available = when {
        type == TestType.MISTAKES -> mistakePool ?: 0
        isCustom -> selectionPreview?.pool ?: 0
        else -> questionPool
    }

    // The sampler's smallest block for the pool the selected type draws from.
    // Null while the query is in flight, so Start waits instead of emitting a
    // route the session would refuse at load.
    var minBlockSize by remember(paper.id, expanded, type, available) {
        mutableStateOf<Int?>(null)
    }
    LaunchedEffect(paper.id, expanded, type, available) {
        minBlockSize = if (available <= 0) {
            1
        } else {
            runCatching { loadMinBlockSize(type, expanded) }.getOrDefault(1)
        }
    }
    val blockLoading = minBlockSize == null

    val exclusionMode = if (type.supportsFreshSample && avoidRecentQuestions) {
        ExclusionMode.RECENT_3
    } else {
        ExclusionMode.NONE
    }

    val setup = TestSetup(
        paperId = paper.id,
        categoryIds = categoryIds,
        scopeKey = scopeKey,
        type = type,
        requestedCount = requestedCount,
        time = minutesOverride?.let { TestTime.Fixed(it.toIntOrNull() ?: 0) }
            ?: TestTime.Suggested,
        available = available,
        exclusionMode = exclusionMode,
        variant = if (type == TestType.TAG || type == TestType.AUTHORED_DIFFICULTY) variant else null,
        minBlockSize = minBlockSize ?: 1
    )
    // The largest length this pool can actually supply. Chips above it are
    // disabled rather than left to the clamp message (§7.2, §14); the message
    // still covers a length carried over from a larger scope or type.
    val maxSelectable = TestPlan.maxAllowedFor(available)
    val maxLengthIndex = TestPlan.LENGTHS.indexOf(maxSelectable).coerceAtLeast(0)

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
                TestSetup.STARTABLE_TYPES.forEach { candidate ->
                    FilterChip(
                        selected = type == candidate,
                        onClick = {
                            if (type != candidate) variant = null
                            type = candidate
                        },
                        label = { Text(stringResource(testTypeLabelRes(candidate))) }
                    )
                }
            }
            Text(
                stringResource(
                    when (type) {
                        TestType.MISTAKES -> R.string.test_setup_type_mistakes_help
                        TestType.WEAKEST_TOPICS -> R.string.test_setup_type_weakest_help
                        TestType.TOUGHEST_QUESTIONS -> R.string.test_setup_type_toughest_help
                        TestType.EASIEST_QUESTIONS -> R.string.test_setup_type_easiest_help
                        TestType.BALANCED -> R.string.test_setup_type_balanced_help
                        TestType.TRICKY -> R.string.test_setup_type_tricky_help
                        TestType.AT_RISK -> R.string.test_setup_type_at_risk_help
                        TestType.UNSEEN -> R.string.test_setup_type_unseen_help
                        TestType.MIXED -> R.string.test_setup_type_mixed_help
                        TestType.BOOKMARKED -> R.string.test_setup_type_bookmarked_help
                        TestType.TAG -> R.string.test_setup_type_tag_help
                        TestType.AUTHORED_DIFFICULTY -> R.string.test_setup_type_authored_help
                        TestType.SLOWEST -> R.string.test_setup_type_slowest_help
                        else -> R.string.test_setup_type_random_help
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // A custom type needs its choice before Start is offered.
            when (type) {
                TestType.TAG -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.test_setup_pick_tag),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    if (tags.isNullOrEmpty()) {
                        Text(
                            stringResource(R.string.test_setup_no_tags),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tags.orEmpty().forEach { counted ->
                                FilterChip(
                                    selected = variant == counted.tagFolded,
                                    onClick = {
                                        variant = if (variant == counted.tagFolded) null else counted.tagFolded
                                    },
                                    label = {
                                        Text(
                                            "${counted.tag} · ${counted.count}"
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                TestType.AUTHORED_DIFFICULTY -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.test_setup_pick_difficulty),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.mcqapp.domain.Difficulty.entries.forEach { difficulty ->
                            FilterChip(
                                selected = variant == difficulty.name,
                                onClick = {
                                    variant = if (variant == difficulty.name) null else difficulty.name
                                },
                                label = { Text(difficulty.label) }
                            )
                        }
                    }
                }
                else -> Unit
            }

            // Breadth only means something for the category-based practice
            // selectors; the metric selectors have no K to choose.
            if (type == TestType.WEAKEST_TOPICS || type == TestType.MIXED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.test_setup_breadth),
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Breadth.entries.forEach { option ->
                        FilterChip(
                            selected = breadth == option,
                            onClick = { breadth = option },
                            label = { Text(stringResource(breadthLabel(option))) }
                        )
                    }
                }
            }

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
                    type.usesSignalSelector && selectionPreview != null -> stringResource(
                        R.string.test_setup_pool_type, selectionPreview!!.pool
                    )
                    else -> stringResource(R.string.test_setup_pool_questions, questionPool)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (type.usesSignalSelector) {
                Text(
                    if (previewLoading && selectionPreview == null) {
                        stringResource(R.string.test_setup_preview_loading)
                    } else {
                        stringResource(
                            R.string.test_setup_composition,
                            compositionText(selectionPreview?.composition)
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                selectionPreview?.targets.orEmpty().forEach { target ->
                    Text(
                        stringResource(
                            R.string.test_setup_target_row,
                            target.title,
                            (target.accuracy * 100).roundToInt(),
                            target.correct,
                            target.asked
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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
                    if (requestedCount > maxSelectable) {
                        stringResource(
                            R.string.test_setup_asked_run,
                            requestedCount,
                            setup.resolvedCount()
                        )
                    } else {
                        stringResource(R.string.test_setup_count_label, requestedCount)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (maxLengthIndex > 0) {
                    Slider(
                        value = TestPlan.LENGTHS.indexOf(requestedCount)
                            .coerceIn(0, maxLengthIndex)
                            .toFloat(),
                        onValueChange = {
                            val index = it.roundToInt().coerceIn(0, maxLengthIndex)
                            requestedCount = TestPlan.LENGTHS[index]
                        },
                        valueRange = 0f..maxLengthIndex.toFloat(),
                        // One stop between the selectable lengths: the slider
                        // lands on a length, never between two.
                        steps = maxLengthIndex - 1
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TestPlan.PRESETS.forEach { preset ->
                        FilterChip(
                            selected = requestedCount == preset,
                            enabled = preset <= maxSelectable,
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
                    append(testTypeLabelText(type))
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
                            TestComparability.FRESH -> R.string.test_preview_fresh
                            TestComparability.PRACTICE -> R.string.test_preview_practice
                            TestComparability.SHORT -> R.string.test_preview_short
                        },
                        setup.resolvedCount()
                    ))
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (type.supportsFreshSample && avoidRecentQuestions) {
                Text(
                    stringResource(R.string.test_setup_fresh_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                onClick = { onStart(type, requestedCount, setup.minutes(), exclusionMode, breadth, variant) },
                enabled = setup.startable() && !poolLoading && !blockLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("test-setup-start")
            ) {
                Text(
                    when {
                        !setup.startable() -> stringResource(R.string.start_test)
                        setup.minutes() <= 0 -> stringResource(
                            R.string.test_setup_start_untimed, setup.resolvedCount()
                        )
                        else -> stringResource(
                            R.string.test_setup_start,
                            setup.resolvedCount(),
                            setup.minutes()
                        )
                    }
                )
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

private fun breadthLabel(breadth: Breadth): Int = when (breadth) {
    Breadth.NARROW -> R.string.test_setup_breadth_narrow
    Breadth.MEDIUM -> R.string.test_setup_breadth_medium
    Breadth.BROAD -> R.string.test_setup_breadth_broad
}

/** One recorded segment's short label, e.g. `weakest` or `random`. */
@Composable
private fun fillSourceLabel(source: String): String = when (source.uppercase()) {
    "WEAKEST_TOPICS" -> stringResource(R.string.test_setup_fill_weakest)
    "TOUGHEST_QUESTIONS" -> stringResource(R.string.test_setup_fill_toughest)
    "EASIEST_QUESTIONS" -> stringResource(R.string.test_setup_fill_easiest)
    "TRICKY" -> stringResource(R.string.test_setup_fill_tricky)
    "AT_RISK" -> stringResource(R.string.test_setup_fill_at_risk)
    "UNSEEN" -> stringResource(R.string.test_setup_fill_unseen)
    "BALANCED" -> stringResource(R.string.test_setup_fill_balanced)
    "MIXED" -> stringResource(R.string.test_setup_fill_mixed)
    "BOOKMARKED" -> stringResource(R.string.test_setup_fill_bookmarked)
    "TAG" -> stringResource(R.string.test_setup_fill_tag)
    "AUTHORED_EASY" -> stringResource(R.string.test_setup_fill_authored_easy)
    "SLOWEST" -> stringResource(R.string.test_setup_fill_slowest)
    "LEAST_RECENTLY_REVIEWED", "AUTHORED_HARD", "AUTHORED_MEDIUM" ->
        stringResource(R.string.test_setup_fill_recent)
    else -> stringResource(R.string.test_setup_fill_random)
}

/** `12 weakest + 8 random`, or an em dash when the selection is empty. */
@Composable
private fun compositionText(composition: Composition?): String {
    if (composition == null || composition.isEmpty) return "—"
    val text = StringBuilder()
    for (segment in composition.segments) {
        if (text.isNotEmpty()) text.append(" + ")
        text.append(segment.count)
        text.append(' ')
        text.append(fillSourceLabel(segment.source))
    }
    return text.toString()
}
