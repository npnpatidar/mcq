package com.mcqapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.FsrsOptimizer
import java.util.Locale
import kotlin.math.roundToInt

/** The Study / Spaced repetition group: the whole scheduler card, collapsible. */
@Composable
fun StudySchedulerSection(
    config: com.mcqapp.domain.SchedulerConfig,
    onChange: (com.mcqapp.domain.SchedulerConfig) -> Unit,
    onReset: () -> Unit,
    simplifiedStudy: Boolean,
    onSimplifiedChange: (Boolean) -> Unit,
    automaticLadderAdaptation: Boolean,
    onAutomaticLadderChange: (Boolean) -> Unit,
    fsrsReviewCount: Int,
    fsrsOptimizeState: FsrsOptimizeState,
    onOptimizeFsrs: () -> Unit,
    onDismissFsrsOptimize: () -> Unit,
    onRestoreFsrsDefaults: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    CollapsibleSettingsSection(
        titleRes = R.string.settings_section_study,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        testTag = "settings-section-study"
    ) {
        AnkiSchedulerBody(
            config = config,
            onChange = onChange,
            onReset = onReset,
            simplifiedStudy = simplifiedStudy,
            onSimplifiedChange = onSimplifiedChange,
            automaticLadderAdaptation = automaticLadderAdaptation,
            onAutomaticLadderChange = onAutomaticLadderChange,
            fsrsReviewCount = fsrsReviewCount,
            fsrsOptimizeState = fsrsOptimizeState,
            onOptimizeFsrs = onOptimizeFsrs,
            onDismissFsrsOptimize = onDismissFsrsOptimize,
            onRestoreFsrsDefaults = onRestoreFsrsDefaults
        )
    }
}

/**
 * Scheduler settings body.
 *
 * The everyday controls (retention, ranking mode, new cards) sit above; the
 * collapsed section holds the pacing knobs FSRS reads — the study-day
 * boundary, the interval ceiling, the relearn delay and the leech threshold —
 * plus the response-time thresholds that drive automatic grading. They use
 * Anki's own wording so a value read out of Anki lands in the obvious place;
 * defaults are Anki's defaults.
 */
@Composable
private fun AnkiSchedulerBody(
    config: com.mcqapp.domain.SchedulerConfig,
    onChange: (com.mcqapp.domain.SchedulerConfig) -> Unit,
    onReset: () -> Unit,
    simplifiedStudy: Boolean,
    onSimplifiedChange: (Boolean) -> Unit,
    automaticLadderAdaptation: Boolean,
    onAutomaticLadderChange: (Boolean) -> Unit,
    fsrsReviewCount: Int,
    fsrsOptimizeState: FsrsOptimizeState,
    onOptimizeFsrs: () -> Unit,
    onDismissFsrsOptimize: () -> Unit,
    onRestoreFsrsDefaults: () -> Unit
) {
    // Collapsed by default: the tuning knobs underneath are for Anki
    // veterans, and an open wall of numbers is what made this screen feel
    // complex. The everyday controls stay above the fold.
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    // Reset re-applies defaults to every study setting below this row, not
    // only the schedule knobs, so it asks first.
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    var showRestoreDefaultsConfirm by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.spaced_repetition),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { showResetConfirm = true }) { Text(stringResource(R.string.reset)) }
    }
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.reset)) },
            text = { Text(stringResource(R.string.reset_study_settings_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    onReset()
                }) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    Text(
        stringResource(R.string.sm_2_scheduling_for_study_these_match_anki_s_review_options_) +
            stringResource(R.string.sm_2_scheduling_tail_the_same_values_work_in_both_apps),
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(12.dp))
    SwitchSettingRow(
        label = stringResource(R.string.simplified_anki),
        help = stringResource(R.string.grade_study_cards_automatically_from_your_answers_instead_of_),
        checked = simplifiedStudy,
        onChange = onSimplifiedChange,
        testTag = "setting-simplified-anki"
    )
    Spacer(Modifier.height(8.dp))
    SwitchSettingRow(
        label = stringResource(R.string.automatic_ladder_adaptation),
        help = stringResource(R.string.automatic_ladder_adaptation_help),
        checked = automaticLadderAdaptation,
        onChange = onAutomaticLadderChange,
        testTag = "setting-automatic-ladder"
    )
    Spacer(Modifier.height(12.dp))

    // --- the everyday pull-based controls ---

    Text(stringResource(R.string.study_section), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    // One commit per gesture: the drag frames used to write DataStore
    // per frame (consolidated_bugs.md F17). The text rows' per-keystroke
    // writes are documented deliberate; the slider's were not.
    var dragRetention by rememberSaveable(config.desiredRetention) {
        mutableStateOf(config.desiredRetention.toFloat())
    }
    // The label follows the thumb so the percentage is readable while
    // dragging, not only after a commit.
    Text(
        stringResource(
            R.string.target_retention_value,
            (dragRetention * 100).roundToInt()
        ),
        style = MaterialTheme.typography.bodyMedium
    )
    Slider(
        value = dragRetention,
        onValueChange = { dragRetention = it },
        onValueChangeFinished = {
            onChange(config.copy(desiredRetention = dragRetention.toDouble()))
        },
        // 0.75..0.97 in 0.01 steps: matches SchedulerConfig.sanitized's clamp.
        valueRange = 0.75f..0.97f,
        steps = 21
    )
    Text(
        stringResource(R.string.target_retention_help),
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(12.dp))

    // --- optimizer: Anki's "Optimize FSRS parameters" button ---

    Text(
        stringResource(R.string.optimize_fsrs_parameters),
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        stringResource(
            R.string.optimize_fsrs_parameters_help,
            FsrsOptimizer.MIN_REVIEWS
        ),
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(4.dp))
    val optimizing = fsrsOptimizeState is FsrsOptimizeState.Running
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onOptimizeFsrs,
            enabled = fsrsReviewCount >= FsrsOptimizer.MIN_REVIEWS && !optimizing,
            modifier = Modifier.weight(1f)
        ) {
            if (optimizing) {
                val running = fsrsOptimizeState as FsrsOptimizeState.Running
                Text(
                    // The schedule size lands with the fit's first
                    // progress report; until then there is no honest
                    // denominator to show.
                    if (running.total == 0) {
                        stringResource(R.string.optimizing_fsrs_start)
                    } else {
                        stringResource(
                            R.string.optimizing_fsrs,
                            running.step,
                            running.total
                        )
                    }
                )
            } else {
                Text(stringResource(R.string.optimize_fsrs_parameters))
            }
        }
        if (optimizing) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    }
    if (!optimizing && fsrsReviewCount < FsrsOptimizer.MIN_REVIEWS) {
        Text(
            stringResource(
                R.string.optimize_fsrs_needs_reviews,
                fsrsReviewCount,
                FsrsOptimizer.MIN_REVIEWS
            ),
            style = MaterialTheme.typography.bodySmall
        )
    }
    when (val state = fsrsOptimizeState) {
        is FsrsOptimizeState.Done -> {
            Text(
                stringResource(
                    R.string.optimize_fsrs_done,
                    state.result.predictions,
                    state.result.cards,
                    String.format(Locale.US, "%.4f", state.result.lossBefore),
                    String.format(Locale.US, "%.4f", state.result.lossAfter)
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Row {
                TextButton(onClick = { showRestoreDefaultsConfirm = true }) {
                    Text(stringResource(R.string.restore_defaults))
                }
                TextButton(onClick = onDismissFsrsOptimize) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
        is FsrsOptimizeState.Failed -> {
            Text(
                stringResource(R.string.optimize_fsrs_failed, state.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onDismissFsrsOptimize) {
                Text(stringResource(R.string.ok))
            }
        }
        else -> Unit
    }
    if (showRestoreDefaultsConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreDefaultsConfirm = false },
            title = { Text(stringResource(R.string.restore_defaults)) },
            text = { Text(stringResource(R.string.restore_fsrs_defaults_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreDefaultsConfirm = false
                    onRestoreFsrsDefaults()
                }) { Text(stringResource(R.string.restore_defaults)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDefaultsConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.ranking_mode), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = config.studyMode == com.mcqapp.domain.StudyMode.MEMORY_FIRST,
            onClick = {
                onChange(config.copy(studyMode = com.mcqapp.domain.StudyMode.MEMORY_FIRST))
            },
            label = { Text(stringResource(R.string.memory_first)) }
        )
        FilterChip(
            selected = config.studyMode == com.mcqapp.domain.StudyMode.EXPLORE_NEW,
            onClick = {
                onChange(config.copy(studyMode = com.mcqapp.domain.StudyMode.EXPLORE_NEW))
            },
            label = { Text(stringResource(R.string.explore_new_cards)) }
        )
    }
    Text(
        stringResource(R.string.ranking_mode_help),
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(12.dp))
    SwitchSettingRow(
        label = stringResource(R.string.introduce_new_cards),
        help = stringResource(R.string.introduce_new_cards_help),
        checked = config.introduceNewCards,
        onChange = { onChange(config.copy(introduceNewCards = it)) },
        testTag = "setting-introduce-new-cards"
    )
    Spacer(Modifier.height(12.dp))

    AnkiNumberRow(
        label = stringResource(R.string.easy_answer_threshold),
        help = stringResource(R.string.easy_answer_threshold_help),
        value = config.fastSeconds,
        step = 1.0,
        // 1..3600: the domain clamps fastSeconds to 1..3600
        // (SpacedRepetition.sanitized), and a restored 601-3600 value
        // used to render as a permanent error with dead steppers
        // (consolidated_bugs.md H3).
        range = 1f..3600f,
        suffix = "s",
        onChange = { onChange(config.copy(fastSeconds = it.toLong())) }
    )
    AnkiNumberRow(
        label = stringResource(R.string.hard_answer_threshold),
        help = stringResource(R.string.hard_answer_threshold_help),
        value = config.slowSeconds,
        step = 1.0,
        range = 1f..3600f,
        suffix = "s",
        // The domain clamps slow up to fast; without this check a typed
        // value below fast "succeeded" silently, left a phantom in the
        // field and stored the old value (consolidated_bugs.md F16/H2).
        validExtra = { it >= config.fastSeconds },
        onChange = { onChange(config.copy(slowSeconds = it.toLong())) }
    )

    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.response_time_thresholds_help),
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = advancedExpanded,
                role = Role.Button,
                onValueChange = { advancedExpanded = !advancedExpanded }
            )
            .testTag("advanced-anki-settings"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (advancedExpanded) Icons.Default.KeyboardArrowDown
            else Icons.Default.KeyboardArrowRight,
            contentDescription = null
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.advanced_anki_settings),
            style = MaterialTheme.typography.labelLarge
        )
    }
    if (advancedExpanded) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.study_day), style = MaterialTheme.typography.labelLarge)
        AnkiNumberRow(
            label = stringResource(R.string.next_day_starts_at),
            help = stringResource(R.string.next_day_starts_at_help),
            value = config.dayStartHour,
            step = 1.0,
            range = 0f..23f,
            suffix = "h",
            onChange = { onChange(config.copy(dayStartHour = it.toInt())) }
        )
        AnkiNumberRow(
            label = stringResource(R.string.maximum_interval),
            help = stringResource(R.string.maximum_interval_help),
            value = config.maxIntervalDays,
            step = 1.0,
            range = 1f..36500f,
            suffix = "d",
            onChange = { onChange(config.copy(maxIntervalDays = it.toInt())) }
        )

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.lapses), style = MaterialTheme.typography.labelLarge)
        AnkiNumberRow(
            label = stringResource(R.string.relearning_delay),
            help = stringResource(R.string.relearning_delay_help),
            value = config.relearnMs / 60000.0,
            // Half-minute steps: a restored 90000 ms is 1.5 real minutes,
            // and step 1.0 both displayed it as "1" and overwrote it with
            // 2.0 on the first step (consolidated_bugs.md F16).
            step = 0.5,
            range = 1.0f..1440.0f,
            suffix = "m",
            onChange = { onChange(config.copy(relearnMs = (it * 60000).toLong())) }
        )
        AnkiNumberRow(
            label = stringResource(R.string.leech_threshold),
            help = stringResource(R.string.leech_threshold_help),
            value = config.leechThreshold,
            step = 1.0,
            range = 1f..100f,
            onChange = { onChange(config.copy(leechThreshold = it.toInt())) }
        )
    }
}

/**
 * A labelled numeric setting with decrement/increment and direct entry.
 *
 * Uses stepper buttons rather than a slider because these values span 1 to 36500
 * days, where a slider is unusable, and because a typed value is exact where a
 * dragged one is not. The text field is the source of truth while focused so a
 * partially typed number is not clobbered by the stepper.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnkiNumberRow(
    label: String,
    help: String,
    value: Number,
    step: Double,
    range: ClosedFloatingPointRange<Float>,
    suffix: String? = null,
    /** Cross-field validity on top of [range]: a typed value failing it shows
     *  an error and is never committed, instead of being clamped away
     *  silently (consolidated_bugs.md F16/H2). */
    validExtra: ((Double) -> Boolean)? = null,
    onChange: (Double) -> Unit
) {
    val current = value.toDouble()
    var text by remember(current) { mutableStateOf(formatValue(current, step)) }
    val parsed = text.trim().toDoubleOrNull()
    val valid = parsed != null && parsed >= range.start && parsed <= range.endInclusive &&
        (validExtra == null || validExtra.invoke(parsed))
    // Step from what the user typed when it is valid; an invalid field's
    // buttons are disabled rather than silently snapping back to the last
    // committed value.
    val stepBase = if (valid) parsed!! else current

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            Text(help, style = MaterialTheme.typography.bodySmall)
        }
        val downTarget = (stepBase - step).coerceIn(range.start.toDouble(), range.endInclusive.toDouble())
        IconButton(
            onClick = { if (validExtra == null || validExtra.invoke(downTarget)) onChange(downTarget) },
            enabled = valid && stepBase > range.start
        ) {
            Icon(
                Icons.Default.Remove,
                contentDescription = stringResource(R.string.decrease_label, label)
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                // One shared numeric-entry pipeline: comma-locale keyboards type
                // ',' for the decimal key, so that folds to '.' before filtering
                // (sealed by util/NumericInput.kt, shared with the library and
                // import screens).
                val filtered = com.mcqapp.util.filterDecimalEntry(input, allowLeadingMinus = true)
                text = filtered
                val candidate = filtered.trim().toDoubleOrNull()
                if (candidate != null && candidate >= range.start && candidate <= range.endInclusive &&
                    (validExtra == null || validExtra.invoke(candidate))
                ) {
                    onChange(candidate)
                }
            },
            singleLine = true,
            isError = !valid,
            suffix = suffix?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(88.dp)
        )
        val upTarget = (stepBase + step).coerceIn(range.start.toDouble(), range.endInclusive.toDouble())
        IconButton(
            onClick = { if (validExtra == null || validExtra.invoke(upTarget)) onChange(upTarget) },
            enabled = valid && stepBase < range.endInclusive
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.increase_label, label)
            )
        }
    }
}

/** Rounds to the row's step so the text matches the value the steppers produce. */
internal fun formatValue(value: Double, step: Double): String {
    val decimals = when {
        step >= 1.0 -> 0
        step >= 0.1 -> 1
        else -> 2
    }
    // Locale.ROOT: the row re-parses this text on edit, and a default-locale
    // comma ("10,5") would fail its own validity check.
    return if (decimals == 0) value.toInt().toString()
    else String.format(Locale.ROOT, "%.${decimals}f", value)
}
