package com.mcqapp.ui.settings

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.R
import com.mcqapp.data.repository.McqRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: McqRepository,
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themePalette by viewModel.themePalette.collectAsStateWithLifecycle()
    val customThemes by viewModel.customThemes.collectAsStateWithLifecycle()
    val fontScale by viewModel.fontScale.collectAsStateWithLifecycle()
    val shuffleQuestions by viewModel.shuffleQuestions.collectAsStateWithLifecycle()
    val simplifiedStudy by viewModel.simplifiedStudy.collectAsStateWithLifecycle()
    val updateAnswersOnDuplicate by viewModel.updateAnswersOnDuplicate.collectAsStateWithLifecycle()
    val shuffleOptions by viewModel.shuffleOptions.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    val practiceMode by viewModel.practiceMode.collectAsStateWithLifecycle()
    val strictMode by viewModel.strictMode.collectAsStateWithLifecycle()
    val autoAdvance by viewModel.autoAdvance.collectAsStateWithLifecycle()
    val avoidRecentQuestions by viewModel.avoidRecentQuestions.collectAsStateWithLifecycle()
    val loadRemoteImages by viewModel.loadRemoteImages.collectAsStateWithLifecycle()
    val schedulerConfig by viewModel.schedulerConfig.collectAsStateWithLifecycle()
    val resetError by viewModel.resetError.collectAsStateWithLifecycle()
    val fsrsReviewCount by viewModel.fsrsReviewCount.collectAsStateWithLifecycle()
    val fsrsOptimize by viewModel.fsrsOptimize.collectAsStateWithLifecycle()
    val pdfTwoColumn by viewModel.pdfTwoColumn.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()

    // rememberSaveable: the pickers and confirm dialogs recreate the activity,
    // and plain `remember` dismissed the confirm and lost the picked URI and
    // the typed-error strings (consolidated_bugs.md F4/H10).
    // The failure dialog's title follows the operation: backup and restore
    // failures used to report under "Export failed" (consolidated_bugs.md
    // H1). H6: a busy flag keeps a second export/backup/restore from queuing
    // behind (and wiping) the first.
    var exportError by rememberSaveable { mutableStateOf<String?>(null) }
    // The failure's title as a resource id, resolved inside the dialog's
    // Text: the callbacks are not composable scopes (consolidated_bugs.md
    // H1).
    var exportErrorTitleRes by rememberSaveable { mutableStateOf(0) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // LocalResources (not LocalContext.current) keeps the share copy
    // configuration-aware: LocalContext.getString() is not re-read when the
    // Configuration changes (LocalContextGetResourceValueCall).
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    /**
     * The Backup & data share buttons: build the file in the cache, then hand
     * it to a chooser. A failure reports through the same dialog the export,
     * backup and restore buttons use.
     */
    fun share(failureTitleRes: Int, build: suspend () -> android.content.Intent?) {
        busy = true
        scope.launch {
            val intent = build()
            busy = false
            if (intent != null) {
                try {
                    context.startActivity(
                        android.content.Intent.createChooser(
                            intent,
                            resources.getString(R.string.share_chooser_title)
                        )
                    )
                } catch (_: ActivityNotFoundException) {
                    exportErrorTitleRes = R.string.export_failed
                    exportError = resources.getString(R.string.share_no_app)
                }
            } else {
                exportErrorTitleRes = failureTitleRes
                exportError = resources.getString(R.string.share_failed)
            }
        }
    }

    fun fail(titleRes: Int) = { error: String ->
        exportErrorTitleRes = titleRes
        exportError = error
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            busy = true
            viewModel.exportAll(it, onError = fail(R.string.export_failed)) { busy = false }
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let {
            busy = true
            viewModel.backupAll(it, onError = fail(R.string.backup_failed)) { busy = false }
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

    val themeImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // JSON is the advertised type; text/plain and octet-stream are
            // there for pickers that do not label .json files.
            viewModel.importTheme(
                it,
                onError = fail(R.string.theme_import_failed)
            )
        }
    }

    var appearanceExpanded by rememberSaveable { mutableStateOf(true) }
    var testsExpanded by rememberSaveable { mutableStateOf(true) }
    var studyExpanded by rememberSaveable { mutableStateOf(true) }
    var backupExpanded by rememberSaveable { mutableStateOf(true) }
    var aboutExpanded by rememberSaveable { mutableStateOf(true) }

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
            AppearanceSection(
                themeMode = themeMode,
                themePalette = themePalette,
                customThemes = customThemes,
                fontScale = fontScale,
                loadRemoteImages = loadRemoteImages,
                hapticsEnabled = hapticsEnabled,
                expanded = appearanceExpanded,
                onExpandedChange = { appearanceExpanded = it },
                onThemeModeChange = { viewModel.setThemeMode(it) },
                onThemePaletteChange = { viewModel.setThemePalette(it) },
                onFontScaleChange = { viewModel.setFontScale(it) },
                onLoadRemoteImagesChange = { viewModel.setLoadRemoteImages(it) },
                onHapticsChange = { viewModel.setHapticsEnabled(it) },
                onImportTheme = {
                    themeImportLauncher.launch(
                        arrayOf(
                            "application/json",
                            "text/plain",
                            "application/octet-stream"
                        )
                    )
                },
                onRemoveTheme = { viewModel.removeCustomTheme(it) }
            )

            Spacer(Modifier.height(12.dp))
            TestsSection(
                shuffleQuestions = shuffleQuestions,
                shuffleOptions = shuffleOptions,
                practiceMode = practiceMode,
                strictMode = strictMode,
                autoAdvance = autoAdvance,
                avoidRecentQuestions = avoidRecentQuestions,
                expanded = testsExpanded,
                onExpandedChange = { testsExpanded = it },
                onShuffleQuestionsChange = { viewModel.setShuffleQuestions(it) },
                onShuffleOptionsChange = { viewModel.setShuffleOptions(it) },
                onPracticeModeChange = { viewModel.setPracticeMode(it) },
                onStrictModeChange = { viewModel.setStrictMode(it) },
                onAutoAdvanceChange = { viewModel.setAutoAdvance(it) },
                onAvoidRecentQuestionsChange = { viewModel.setAvoidRecentQuestions(it) }
            )

            Spacer(Modifier.height(12.dp))
            StudySchedulerSection(
                config = schedulerConfig,
                onChange = { viewModel.updateSchedulerConfig(it) },
                onFastChange = { viewModel.setFastSeconds(it) },
                onSlowChange = { viewModel.setSlowSeconds(it) },
                onRuleChange = { viewModel.setGradeCorrectnessOnly(it) },
                onReset = { viewModel.resetSchedulerConfig() },
                simplifiedStudy = simplifiedStudy,
                onSimplifiedChange = { viewModel.setSimplifiedStudy(it) },
                fsrsReviewCount = fsrsReviewCount,
                fsrsOptimizeState = fsrsOptimize,
                onOptimizeFsrs = { viewModel.optimizeFsrsParameters() },
                onDismissFsrsOptimize = { viewModel.dismissFsrsOptimize() },
                onRestoreFsrsDefaults = { viewModel.restoreFsrsDefaults() },
                expanded = studyExpanded,
                onExpandedChange = { studyExpanded = it }
            )

            Spacer(Modifier.height(12.dp))
            BackupDataSection(
                storage = storage,
                updateAnswersOnDuplicate = updateAnswersOnDuplicate,
                pdfTwoColumn = pdfTwoColumn,
                busy = busy,
                expanded = backupExpanded,
                onExpandedChange = { backupExpanded = it },
                onUpdateAnswersChange = { viewModel.setUpdateAnswersOnDuplicate(it) },
                onPdfTwoColumnChange = { viewModel.setPdfTwoColumn(it) },
                onRefreshStorage = { viewModel.refreshStorage() },
                onExportClick = { exportLauncher.launch("mcq-export.json") },
                onBackupClick = { backupLauncher.launch("mcqapp-backup.zip") },
                onShareAllClick = {
                    share(R.string.export_failed) { viewModel.shareAllIntent() }
                },
                onShareBackupClick = {
                    share(R.string.backup_failed) { viewModel.shareBackupIntent() }
                },
                onRestoreClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                onExportLogsError = { titleRes, message ->
                    exportErrorTitleRes = titleRes
                    exportError = message
                }
            )

            Spacer(Modifier.height(12.dp))
            AboutSection(
                expanded = aboutExpanded,
                onExpandedChange = { aboutExpanded = it }
            )
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
                            onError = fail(R.string.restore_failed),
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
    resetError?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissResetError() },
            title = { Text(stringResource(R.string.error)) },
            text = { Text(message) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.dismissResetError() }
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
    exportError?.let { error ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { exportError = null },
            title = {
                Text(
                    stringResource(
                        if (exportErrorTitleRes != 0) {
                            exportErrorTitleRes
                        } else {
                            R.string.export_failed
                        }
                    )
                )
            },
            text = { Text(error) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { exportError = null }) { Text(stringResource(R.string.ok)) }
            }
        )
    }
}
