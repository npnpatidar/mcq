package com.mcqapp.ui.settings

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.io.Exporter
import com.mcqapp.domain.FsrsOptimizer
import com.mcqapp.BuildConfig
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.util.Logger
import com.mcqapp.R
import java.util.Locale

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
    val simplifiedStudy by viewModel.simplifiedStudy.collectAsStateWithLifecycle()
    val updateAnswersOnDuplicate by viewModel.updateAnswersOnDuplicate.collectAsStateWithLifecycle()
    val shuffleOptions by viewModel.shuffleOptions.collectAsStateWithLifecycle()
    val practiceMode by viewModel.practiceMode.collectAsStateWithLifecycle()
    val strictMode by viewModel.strictMode.collectAsStateWithLifecycle()
    val autoAdvance by viewModel.autoAdvance.collectAsStateWithLifecycle()
    val loadRemoteImages by viewModel.loadRemoteImages.collectAsStateWithLifecycle()
    val schedulerConfig by viewModel.schedulerConfig.collectAsStateWithLifecycle()
    val fsrsReviewCount by viewModel.fsrsReviewCount.collectAsStateWithLifecycle()
    val fsrsOptimize by viewModel.fsrsOptimize.collectAsStateWithLifecycle()
    val pdfTwoColumn by viewModel.pdfTwoColumn.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // rememberSaveable: the pickers and confirm dialogs recreate the activity,
    // and plain `remember` dismissed the confirm and lost the picked URI and
    // the typed-error strings (consolidated_bugs.md F4/H10).
    // The failure dialog's title follows the operation: backup and restore
    // failures used to report under "Export failed" (consolidated_bugs.md
    // H1). H6: a busy flag keeps a second export/backup/restore from queuing
    // behind (and wiping) the first.
    var exportError by rememberSaveable { mutableStateOf<String?>(null) }
    var exportErrorTitle by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    // Resolved in composition: the callbacks are not composable scopes.
    val exportFailedTitle = stringResource(R.string.export_failed)
    val backupFailedTitle = stringResource(R.string.backup_failed)
    val restoreFailedTitle = stringResource(R.string.restore_failed)
    val exportLogsTitle = stringResource(R.string.export_logs)
    fun fail(title: String) = { error: String ->
        exportErrorTitle = title
        exportError = error
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            busy = true
            viewModel.exportAll(it, onError = fail(exportFailedTitle)) { busy = false }
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let {
            busy = true
            viewModel.backupAll(it, onError = fail(backupFailedTitle)) { busy = false }
        }
    }
    var showRestoreConfirm by rememberSaveable { mutableStateOf(false) }
    var restoreDone by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // The picked uri rides in state; the confirmation reads it.
        if (uri != null) {
            restoreUri = uri
            showRestoreConfirm = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
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
                    listOf(
                        "system" to R.string.theme_system_default,
                        "light" to R.string.theme_light,
                        "dark" to R.string.theme_dark
                    ).forEach { (value, labelRes) ->
                    val label = stringResource(labelRes)
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
                    Text(stringResource(R.string.privacy), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.load_remote_images))
                            Text(
                                stringResource(R.string.when_off_pictures_from_the_web_stay_hidden_so_whoever_aut),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = loadRemoteImages,
                            onCheckedChange = { viewModel.setLoadRemoteImages(it) }
                        )
                    }
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
                            Text(stringResource(R.string.shuffle_question_order))
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
                            Text(stringResource(R.string.refresh_answers_on_re_import))
                            Text(
                                stringResource(R.string.update_answers_help),
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
                            Text(stringResource(R.string.shuffle_options))
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
                            Text(stringResource(R.string.practice_mode))
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
                            Text(stringResource(R.string.strict_exam_mode))
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
                            Text(stringResource(R.string.auto_advance))
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
                        stringResource(R.string.review_order_help),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            AnkiSchedulerSection(
                config = schedulerConfig,
                onChange = { viewModel.updateSchedulerConfig(it) },
                onReset = { viewModel.resetSchedulerConfig() },
                simplifiedStudy = simplifiedStudy,
                onSimplifiedChange = { viewModel.setSimplifiedStudy(it) },
                fsrsReviewCount = fsrsReviewCount,
                fsrsOptimizeState = fsrsOptimize,
                onOptimizeFsrs = { viewModel.optimizeFsrsParameters() },
                onDismissFsrsOptimize = { viewModel.dismissFsrsOptimize() },
                onRestoreFsrsDefaults = { viewModel.restoreFsrsDefaults() }
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
                            Text(stringResource(R.string.refresh))
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
                            stringResource(R.string.image_weight_help),
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
                            Text(stringResource(R.string.two_column_layout))
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
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.export_all_data_json))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { backupLauncher.launch("mcqapp-backup.zip") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.backup_full))
                    }
                    Text(
                        stringResource(R.string.backup_full_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.restore_backup))
                    }
                    Text(
                        stringResource(R.string.restore_backup_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            // Both failure modes used to be silent (no log
                            // file) or fatal (no chooser activity)
                            // (consolidated_bugs.md H9).
                            val path = com.mcqapp.util.Logger.logFilePath()
                            if (path == null) {
                                exportErrorTitle = exportLogsTitle
                                exportError = "No log file exists yet."
                                return@Button
                            }
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
                            try {
                                context.startActivity(
                                    android.content.Intent.createChooser(intent, "Export Logs")
                                )
                            } catch (e: ActivityNotFoundException) {
                                exportErrorTitle = exportLogsTitle
                                exportError = "No app can handle this share."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.export_logs))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.export_all_help),
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
                        stringResource(R.string.app_about_help),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    // From BuildConfig, not a literal: the version used to be
                    // hardcoded here and went stale the moment the build's
                    // versionName changed.
                    Text(
                        stringResource(R.string.version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }

    if (showRestoreConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text(stringResource(R.string.restore_backup)) },
            text = {
                Text(stringResource(R.string.restore_backup_confirm))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showRestoreConfirm = false
                    restoreUri?.let { uri ->
                        busy = true
                        viewModel.restoreBackup(
                            uri,
                            onError = fail(restoreFailedTitle),
                            onDone = { report ->
                                busy = false
                                restoreDone = report
                            }
                        )
                    }
                }) { Text(stringResource(R.string.restore_replace_everything)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showRestoreConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    restoreDone?.let { report ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { restoreDone = null },
            title = { Text(stringResource(R.string.restore_backup)) },
            text = { Text(report) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { restoreDone = null }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
    exportError?.let { error ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { exportError = null },
            title = { Text(stringResource(R.string.export_failed)) },
            text = { Text(error) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { exportError = null }) { Text(stringResource(R.string.ok)) }
            }
        )
    }
}

/**
 * Scheduler settings.
 *
 * The everyday controls (retention, ranking mode, new cards) sit above; the
 * collapsed section holds the pacing knobs FSRS reads — the study-day
 * boundary, the interval ceiling, the relearn delay and the leech threshold —
 * plus the response-time thresholds that drive automatic grading. They use
 * Anki's own wording so a value read out of Anki lands in the obvious place;
 * defaults are Anki's defaults.
 */
@Composable
private fun AnkiSchedulerSection(
    config: com.mcqapp.domain.SchedulerConfig,
    onChange: (com.mcqapp.domain.SchedulerConfig) -> Unit,
    onReset: () -> Unit,
    simplifiedStudy: Boolean,
    onSimplifiedChange: (Boolean) -> Unit,
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
                TextButton(onClick = onReset) { Text(stringResource(R.string.reset)) }
            }
            Text(
                stringResource(R.string.sm_2_scheduling_for_study_these_match_anki_s_review_options_) +
                    stringResource(R.string.sm_2_scheduling_tail_the_same_values_work_in_both_apps),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.simplified_anki))
                    Text(
                        stringResource(R.string.grade_study_cards_automatically_from_your_answers_instead_of_),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = simplifiedStudy,
                    onCheckedChange = onSimplifiedChange
                )
            }
            Spacer(Modifier.height(12.dp))

            // --- the everyday pull-based controls ---

            Text(stringResource(R.string.study_section), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.target_retention), style = MaterialTheme.typography.bodyMedium)
            // One commit per gesture: the drag frames used to write DataStore
            // per frame (consolidated_bugs.md F17). The text rows' per-keystroke
            // writes are documented deliberate; the slider's were not.
            var dragRetention by rememberSaveable(config.desiredRetention) {
                mutableStateOf(config.desiredRetention.toFloat())
            }
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
                        TextButton(onClick = onRestoreFsrsDefaults) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.introduce_new_cards))
                    Text(
                        stringResource(R.string.introduce_new_cards_help),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = config.introduceNewCards,
                    onCheckedChange = { onChange(config.copy(introduceNewCards = it)) }
                )
            }
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
                    .clickable { advancedExpanded = !advancedExpanded }
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
            Icon(Icons.Default.Remove, contentDescription = "Decrease $label")
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
            Icon(Icons.Default.Add, contentDescription = "Increase $label")
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
