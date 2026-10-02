package com.mcqapp.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.util.Logger
import com.mcqapp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: McqRepository,
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val fontScale by viewModel.fontScale.collectAsStateWithLifecycle()
    val shuffleQuestions by viewModel.shuffleQuestions.collectAsStateWithLifecycle()
    val updateAnswersOnDuplicate by viewModel.updateAnswersOnDuplicate.collectAsStateWithLifecycle()
    val shuffleOptions by viewModel.shuffleOptions.collectAsStateWithLifecycle()
    val practiceMode by viewModel.practiceMode.collectAsStateWithLifecycle()
    val strictMode by viewModel.strictMode.collectAsStateWithLifecycle()
    val autoAdvance by viewModel.autoAdvance.collectAsStateWithLifecycle()
    val schedulerConfig by viewModel.schedulerConfig.collectAsStateWithLifecycle()
    val pdfTwoColumn by viewModel.pdfTwoColumn.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var exportError by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            viewModel.exportAll(it) { error -> exportError = error }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { stringResource(R.string.settings) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = themeMode == value,
                                    onClick = { viewModel.setThemeMode(value) }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = themeMode == value,
                                onClick = { viewModel.setThemeMode(value) }
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.text_size), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    com.mcqapp.util.FontScale.OPTIONS.forEach { (scale, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = fontScale == scale,
                                    onClick = { viewModel.setFontScale(scale) }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = fontScale == scale,
                                onClick = { viewModel.setFontScale(scale) }
                            )
                            // Preview of the chosen size, independent of the
                            // ambient scale: McqNavHost overrides LocalDensity
                            // with it, so an sp value multiplied by it again
                            // rendered the labels at scale squared. Going via dp
                            // cancels the ambient scale out first.
                            val baseSize = with(androidx.compose.ui.platform.LocalDensity.current) {
                                MaterialTheme.typography.bodyLarge.fontSize.value.dp.toSp()
                            }
                            Text(
                                label,
                                modifier = Modifier.padding(start = 8.dp),
                                fontSize = baseSize * (scale / com.mcqapp.util.FontScale.DEFAULT)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.preview_the_quick_brown_fox_jumps_over_13_lazy_dogs),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.test), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            stringResource(R.string.shuffle_question_order)
                            Text(
                                stringResource(R.string.present_questions_in_random_order_each_attempt),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = shuffleQuestions,
                            onCheckedChange = { viewModel.setShuffleQuestions(it) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            stringResource(R.string.refresh_answers_on_re_import)
                            Text(
                                stringResource(R.string.when_a_file_repeats_a_question_s_text_and_options_but_correc) +
                                    "its answer key, update the stored answer instead of " +
                                    "reporting it as a duplicate.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = updateAnswersOnDuplicate,
                            onCheckedChange = { viewModel.setUpdateAnswersOnDuplicate(it) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            stringResource(R.string.shuffle_options)
                            Text(
                                stringResource(R.string.present_answer_options_in_random_order),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = shuffleOptions,
                            onCheckedChange = { viewModel.setShuffleOptions(it) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            stringResource(R.string.practice_mode)
                            Text(
                                stringResource(R.string.show_correct_answers_and_explanations_instantly),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = practiceMode,
                            onCheckedChange = { viewModel.setPracticeMode(it) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            stringResource(R.string.strict_exam_mode)
                            Text(
                                stringResource(R.string.hides_answers_flags_and_the_question_palette),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = strictMode,
                            onCheckedChange = { viewModel.setStrictMode(it) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            stringResource(R.string.auto_advance)
                            Text(
                                stringResource(R.string.move_to_the_next_question_after_answering_single_answer_only),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = autoAdvance,
                            onCheckedChange = { viewModel.setAutoAdvance(it) }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.review_always_shows_the_order_you_were_given) +
                            "Scoring is unaffected: answers are matched by option, not position.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            AnkiSchedulerSection(
                config = schedulerConfig,
                onChange = { viewModel.updateSchedulerConfig(it) },
                onReset = { viewModel.resetSchedulerConfig() }
            )

            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.storage),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.refreshStorage() }) {
                            stringResource(R.string.refresh)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val report = storage
                    if (report == null) {
                        Text(stringResource(R.string.measuring), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            "Database: ${com.mcqapp.domain.StorageInfo.formatBytes(report.dbBytes)}" +
                                " · ${report.papers} papers · ${report.questions} questions" +
                                " · ${report.attempts} attempts · ${report.bookmarks} bookmarks",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        report.perPaper.take(10).forEach { usage ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    usage.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                Text(
                                    "${usage.questions} q · " +
                                        com.mcqapp.domain.StorageInfo.formatBytes(usage.imageChars),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.image_weight_counts_embedded_pictures_large_banks_shrink) +
                                "automatically at import.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.pdf_export), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            stringResource(R.string.two_column_layout)
                            Text(
                                stringResource(R.string.flow_questions_down_the_left_column_then_the_right),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = pdfTwoColumn,
                            onCheckedChange = { viewModel.setPdfTwoColumn(it) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.data), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { exportLauncher.launch("mcq-export.json") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        stringResource(R.string.export_all_data_json)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val path = com.mcqapp.util.Logger.logFilePath()
                            if (path != null) {
                                val file = java.io.File(path)
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "Export Logs"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        stringResource(R.string.export_logs)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.backs_up_every_paper_bookmark_and_attempt_to_one_json_file) +
                            "Re-import it anywhere to restore content and history.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.about), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mcq_app_practice_multiple_choice_papers_offline) +
                            "Import JSON question banks, take timed tests with negative marking, " +
                            "review explanations, and track your history.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.version_1_0_0), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    exportError?.let { error ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { exportError = null },
            title = { stringResource(R.string.export_failed) },
            text = { Text(error) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { exportError = null }) { stringResource(R.string.ok) }
            }
        )
    }
}

/**
 * Anki-parity scheduler settings.
 *
 * Every tunable number the spaced repetition scheduler uses is editable here,
 * using Anki's own review-options wording so a value copied from Anki lands in
 * the obvious place. Defaults are Anki's defaults. The three ease bounds are
 * interdependent, so [SchedulerConfig.sanitized] clamps them rather than the UI
 * rejecting input field by field.
 */
@Composable
private fun AnkiSchedulerSection(
    config: com.mcqapp.domain.SchedulerConfig,
    onChange: (com.mcqapp.domain.SchedulerConfig) -> Unit,
    onReset: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.spaced_repetition),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onReset) { stringResource(R.string.reset) }
            }
            Text(
                stringResource(R.string.sm_2_scheduling_for_study_these_match_anki_s_review_options_) +
                    "the same values work in both apps. Changes apply to the next " +
                    "review; cards already scheduled keep their current date.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            AnkiNumberRow(
                label = "Starting ease",
                help = "Ease a new card begins at",
                value = config.defaultEase,
                step = 0.05,
                range = 1.3f..5.0f,
                onChange = { onChange(config.copy(defaultEase = it.toDouble())) }
            )
            AnkiNumberRow(
                label = "Minimum ease",
                help = "Floor, so a card stays learnable",
                value = config.minEase,
                step = 0.05,
                range = 1.0f..3.0f,
                onChange = { onChange(config.copy(minEase = it.toDouble())) }
            )
            AnkiNumberRow(
                label = "Maximum ease",
                help = "Ceiling for easy cards",
                value = config.maxEase,
                step = 0.05,
                range = 1.0f..5.0f,
                onChange = { onChange(config.copy(maxEase = it.toDouble())) }
            )
            AnkiNumberRow(
                label = "Easy bonus",
                help = "Extra interval factor on Easy",
                value = config.easyBonus,
                step = 0.05,
                range = 1.0f..5.0f,
                onChange = { onChange(config.copy(easyBonus = it.toDouble())) }
            )
            AnkiNumberRow(
                label = "Hard interval",
                help = "Multiplier applied on Hard",
                value = config.hardIntervalMultiplier,
                step = 0.05,
                range = 1.0f..5.0f,
                onChange = { onChange(config.copy(hardIntervalMultiplier = it.toDouble())) }
            )

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.starting_intervals), style = MaterialTheme.typography.labelLarge)
            AnkiNumberRow(
                label = "Easy interval",
                help = "Days until a new card is reviewed after Easy",
                value = config.easyFirstIntervalDays,
                step = 1.0,
                range = 1f..365f,
                suffix = "d",
                onChange = { onChange(config.copy(easyFirstIntervalDays = it.toInt())) }
            )
            AnkiNumberRow(
                label = "Normal interval",
                help = "Days until a new card is reviewed after Good",
                value = config.firstIntervalDays,
                step = 1.0,
                range = 1f..365f,
                suffix = "d",
                onChange = { onChange(config.copy(firstIntervalDays = it.toInt())) }
            )
            AnkiNumberRow(
                label = "Second interval",
                help = "Days for the second review after Good",
                value = config.secondIntervalDays,
                step = 1.0,
                range = 1f..365f,
                suffix = "d",
                onChange = { onChange(config.copy(secondIntervalDays = it.toInt())) }
            )
            AnkiNumberRow(
                label = "Minimum interval",
                help = "Shortest interval any review can get",
                value = config.minimumIntervalDays,
                step = 1.0,
                range = 1f..365f,
                suffix = "d",
                onChange = { onChange(config.copy(minimumIntervalDays = it.toInt())) }
            )
            AnkiNumberRow(
                label = "Maximum interval",
                help = "Longest interval any review can get",
                value = config.maxIntervalDays,
                step = 1.0,
                range = 1f..36500f,
                suffix = "d",
                onChange = { onChange(config.copy(maxIntervalDays = it.toInt())) }
            )

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.lapses), style = MaterialTheme.typography.labelLarge)
            AnkiNumberRow(
                label = "Relearning delay",
                help = "How long until a failed card returns",
                value = config.relearnMs / 60000.0,
                step = 1.0,
                range = 1.0f..1440.0f,
                suffix = "m",
                onChange = { onChange(config.copy(relearnMs = (it * 60000).toLong())) }
            )
            AnkiNumberRow(
                label = "Leech threshold",
                help = "Failures before a card is flagged tricky",
                value = config.leechThreshold,
                step = 1.0,
                range = 1f..100f,
                onChange = { onChange(config.copy(leechThreshold = it.toInt())) }
            )
            AnkiNumberRow(
                label = "Easy ease bonus",
                help = "Ease added on Easy",
                value = config.easyEaseFactor,
                step = 0.05,
                range = 0f..1f,
                onChange = { onChange(config.copy(easyEaseFactor = it.toDouble())) }
            )
            AnkiNumberRow(
                label = "Hard ease penalty",
                help = "Ease removed on Hard",
                value = config.hardEaseFactor,
                step = 0.05,
                range = 0f..1f,
                onChange = { onChange(config.copy(hardEaseFactor = it.toDouble())) }
            )
            AnkiNumberRow(
                label = "Again ease penalty",
                help = "Ease removed on Again",
                value = config.againEaseFactor,
                step = 0.05,
                range = 0f..1f,
                onChange = { onChange(config.copy(againEaseFactor = it.toDouble())) }
            )

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.daily_limits), style = MaterialTheme.typography.labelLarge)
            AnkiNumberRow(
                label = "New cards per day",
                help = "Unseen cards offered each day",
                value = config.newLimit,
                step = 1.0,
                range = 0f..9999f,
                onChange = { onChange(config.copy(newLimit = it.toInt())) }
            )
            AnkiNumberRow(
                label = "Reviews per day",
                help = "Maximum due cards offered each day",
                value = config.reviewLimit,
                step = 1.0,
                range = 0f..9999f,
                onChange = { onChange(config.copy(reviewLimit = it.toInt())) }
            )
            AnkiNumberRow(
                label = "Easy answer threshold",
                help = "Auto-graded Easy below this response time",
                value = config.fastSeconds,
                step = 1.0,
                range = 1f..600f,
                suffix = "s",
                onChange = { onChange(config.copy(fastSeconds = it.toLong())) }
            )
            AnkiNumberRow(
                label = "Hard answer threshold",
                help = "Auto-graded Hard above this response time",
                value = config.slowSeconds,
                step = 1.0,
                range = 1f..3600f,
                suffix = "s",
                onChange = { onChange(config.copy(slowSeconds = it.toLong())) }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.the_response_time_thresholds_only_apply_when_rebuilding_a_sc) +
                    "from past test attempts. In Study you grade every card yourself.",
                style = MaterialTheme.typography.bodySmall
            )
        }
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
    onChange: (Double) -> Unit
) {
    val current = value.toDouble()
    var text by remember(current) { mutableStateOf(formatValue(current, step)) }
    val parsed = text.trim().toDoubleOrNull()
    val valid = parsed != null && parsed >= range.start && parsed <= range.endInclusive

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            Text(help, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(
            onClick = { onChange((current - step).coerceIn(range.start.toDouble(), range.endInclusive.toDouble())) },
            enabled = current > range.start
        ) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.decrease_label))
        }
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                // Allow digits, one dot, and a leading minus so a negative entry
                // can be typed and rejected rather than silently swallowed.
                val filtered = input.filter { it.isDigit() || it == '.' || (it == '-' && input.indexOf('-') == 0) }
                text = filtered
                val candidate = filtered.trim().toDoubleOrNull()
                if (candidate != null && candidate >= range.start && candidate <= range.endInclusive) {
                    onChange(candidate)
                }
            },
            singleLine = true,
            isError = !valid,
            suffix = suffix?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(88.dp)
        )
        IconButton(
            onClick = { onChange((current + step).coerceIn(range.start.toDouble(), range.endInclusive.toDouble())) },
            enabled = current < range.endInclusive
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.increase_label))
        }
    }
}

/** Rounds to the row's step so the text matches the value the steppers produce. */
private fun formatValue(value: Double, step: Double): String {
    val decimals = when {
        step >= 1.0 -> 0
        step >= 0.1 -> 1
        else -> 2
    }
    return if (decimals == 0) value.toInt().toString()
    else String.format("%.${decimals}f", value)
}
