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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.AppLocale
import com.mcqapp.R
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.ui.common.CompletionActions
import com.mcqapp.ui.common.LocalAnnouncer
import com.mcqapp.util.stampedFileName

/** The backup date as shown in the restore confirmation, in the device's zone. */
internal fun formatBackupDate(exportedAt: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(exportedAt))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: McqRepository,
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val themePalette by viewModel.themePalette.collectAsStateWithLifecycle()
    val customThemes by viewModel.customThemes.collectAsStateWithLifecycle()
    val fontScale by viewModel.fontScale.collectAsStateWithLifecycle()
    val shuffleQuestions by viewModel.shuffleQuestions.collectAsStateWithLifecycle()
    val simplifiedStudy by viewModel.simplifiedStudy.collectAsStateWithLifecycle()
    val confirmGradeBeforeNext by viewModel.confirmGradeBeforeNext.collectAsStateWithLifecycle()
    val dailyGoalEnabled by viewModel.dailyGoalEnabled.collectAsStateWithLifecycle()
    val dailyGoalCount by viewModel.dailyGoalCount.collectAsStateWithLifecycle()
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
    val fsrs7Notice by viewModel.fsrs7Notice.collectAsStateWithLifecycle()
    val pdfTwoColumn by viewModel.pdfTwoColumn.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val storageError by viewModel.storageError.collectAsStateWithLifecycle()

    // SE1: operation state lives in the ViewModel, which survives the Activity
    // recreation a language change triggers. Keeping it in rememberSaveable
    // left the new composition stuck at busy=true while the VM's coroutine
    // cleared the old, orphaned state setters. The controls and both result
    // dialogs below now render from these flows.
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val operationError by viewModel.operationError.collectAsStateWithLifecycle()
    val restoreReport by viewModel.restoreReport.collectAsStateWithLifecycle()
    val restorePreview by viewModel.restorePreview.collectAsStateWithLifecycle()
    val shareRequest by viewModel.shareRequest.collectAsStateWithLifecycle()
    // After a restore this is the fresh library, so the first surviving paper
    // is the one the completion dialog can offer to study/open. An empty
    // backup leaves the list empty and the dialog falls back to Open library.
    val restoredPapers by repository.observePapers().collectAsStateWithLifecycle(emptyList())

    val context = LocalContext.current
    // LocalResources (not LocalContext.current) keeps the chooser title
    // configuration-aware: LocalContext.getString() is not re-read when the
    // Configuration changes (LocalContextGetResourceValueCall).
    val resources = LocalResources.current

    // The ViewModel applies the platform locale and writes the preference; the
    // only UI side effect left is to recreate this activity so every Compose
    // resource is read again in the new language.
    LaunchedEffect(Unit) {
        viewModel.languageChanged.collect { AppLocale.recreate(context) }
    }

    // Restore/export/reset/storage failures surface in an error dialog already;
    // announce each new message so it is not silent under TalkBack.
    val announcer = LocalAnnouncer.current
    LaunchedEffect(resetError) {
        resetError?.let { announcer.announce(it) }
    }
    LaunchedEffect(storageError) {
        storageError?.let { announcer.announce(it) }
    }
    LaunchedEffect(operationError) {
        operationError?.let { announcer.announce(it.message) }
    }

    // The VM builds the share file; this composition only shows the chooser,
    // using its own (fresh) context so a language-change recreation mid-build
    // cannot leave the chooser attached to a destroyed Activity.
    LaunchedEffect(shareRequest) {
        val request = shareRequest ?: return@LaunchedEffect
        try {
            context.startActivity(
                android.content.Intent.createChooser(
                    request.intent,
                    resources.getString(R.string.share_chooser_title)
                )
            )
        } catch (_: ActivityNotFoundException) {
            viewModel.reportShareNoApp()
        } finally {
            viewModel.shareRequestHandled(request.id)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportAll(it) }
    }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { viewModel.backupAll(it) }
    }
    var showRestoreConfirm by rememberSaveable { mutableStateOf(false) }
    var restoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    // "Back up current library first" from inside the confirm dialog opens a
    // second document picker; the restore choice stays pending meanwhile.
    val backupBeforeRestoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { viewModel.backupAll(it) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // The picked uri rides in state; the confirmation reads the manifest
        // the ViewModel loads for it before the learner agrees to a wipe.
        if (uri != null) {
            restoreUri = uri
            showRestoreConfirm = true
            viewModel.prepareRestore(uri)
        }
    }
    // A manifest that cannot be read leaves no preview. Close the confirm and
    // forget the picked uri so an enabled "replace everything" button can never
    // wipe the library using an archive whose contents we failed to inspect.
    LaunchedEffect(operationError) {
        if (restoreConfirmShouldClose(
                showRestoreConfirm,
                restorePreview != null,
                operationError?.titleRes,
                R.string.restore_failed
            )
        ) {
            showRestoreConfirm = false
            restoreUri = null
            viewModel.clearRestorePreview()
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
                onError = { error -> viewModel.reportOperationError(R.string.theme_import_failed, error) }
            )
        }
    }

    var appearanceExpanded by rememberSaveable { mutableStateOf(true) }
    var testsExpanded by rememberSaveable { mutableStateOf(true) }
    var studyExpanded by rememberSaveable { mutableStateOf(true) }
    var backupExpanded by rememberSaveable { mutableStateOf(true) }
    var aiSchemaExpanded by rememberSaveable { mutableStateOf(false) }
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
                appLanguage = appLanguage,
                onAppLanguageChange = { viewModel.setAppLanguage(it) },
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
                confirmGradeBeforeNext = confirmGradeBeforeNext,
                onConfirmGradeBeforeNextChange = { viewModel.setConfirmGradeBeforeNext(it) },
                dailyGoalEnabled = dailyGoalEnabled,
                dailyGoalCount = dailyGoalCount,
                onDailyGoalEnabledChange = { viewModel.setDailyGoalEnabled(it) },
                onDailyGoalCountChange = { viewModel.setDailyGoalCount(it) },
                fsrsReviewCount = fsrsReviewCount,
                fsrsOptimizeState = fsrsOptimize,
                fsrs7Notice = fsrs7Notice,
                onDismissFsrs7Notice = { viewModel.dismissFsrs7Notice() },
                onOptimizeFsrs = { viewModel.optimizeFsrsParameters() },
                onDismissFsrsOptimize = { viewModel.dismissFsrsOptimize() },
                onRestoreFsrsDefaults = { viewModel.restoreFsrsDefaults() },
                expanded = studyExpanded,
                onExpandedChange = { studyExpanded = it }
            )

            Spacer(Modifier.height(12.dp))
            BackupDataSection(
                storage = storage,
                storageError = storageError,
                updateAnswersOnDuplicate = updateAnswersOnDuplicate,
                pdfTwoColumn = pdfTwoColumn,
                busy = busy,
                expanded = backupExpanded,
                onExpandedChange = { backupExpanded = it },
                onUpdateAnswersChange = { viewModel.setUpdateAnswersOnDuplicate(it) },
                onPdfTwoColumnChange = { viewModel.setPdfTwoColumn(it) },
                onRefreshStorage = { viewModel.refreshStorage() },
                onExportClick = { exportLauncher.launch(stampedFileName("mcq-export", "json")) },
                onBackupClick = { backupLauncher.launch(stampedFileName("mcqapp-backup", "zip")) },
                onShareAllClick = { viewModel.shareAll() },
                onShareBackupClick = { viewModel.shareBackup() },
                onRestoreClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                onExportLogsError = { titleRes, message ->
                    viewModel.reportOperationError(titleRes, message)
                }
            )

            Spacer(Modifier.height(12.dp))
            CollapsibleSettingsSection(
                titleRes = R.string.ai_schema_settings_title,
                expanded = aiSchemaExpanded,
                onExpandedChange = { aiSchemaExpanded = it },
                testTag = "settings-section-ai-schema"
            ) {
                Text(
                    stringResource(R.string.ai_schema_settings_help),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { navController.navigate("ai-schemas") },
                    modifier = Modifier.testTag("settings-open-ai-schemas")
                ) {
                    Text(stringResource(R.string.ai_schema_open))
                }
            }

            Spacer(Modifier.height(12.dp))
            AboutSection(
                expanded = aboutExpanded,
                onExpandedChange = { aboutExpanded = it }
            )
        }
    }

    if (showRestoreConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showRestoreConfirm = false
                viewModel.clearRestorePreview()
            },
            title = { Text(stringResource(R.string.restore_backup)) },
            text = {
                Column {
                    Text(stringResource(R.string.restore_backup_confirm))
                    Spacer(Modifier.height(8.dp))
                    val preview = restorePreview
                    if (preview == null) {
                        Text(
                            stringResource(R.string.measuring),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            stringResource(R.string.data_honesty_restore_file, preview.fileName),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(
                                R.string.data_honesty_restore_manifest,
                                formatBackupDate(preview.exportedAt),
                                preview.papers, preview.questions, preview.reviews
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(
                                R.string.data_honesty_restore_current,
                                preview.currentPapers, preview.currentQuestions, preview.currentReviews
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.data_honesty_restore_settings_note),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = restorePreview != null,
                    onClick = {
                        showRestoreConfirm = false
                        viewModel.clearRestorePreview()
                        restoreUri?.let { uri -> viewModel.restoreBackup(uri) }
                    }
                ) { Text(stringResource(R.string.restore_replace_everything)) }
            },
            dismissButton = {
                Column {
                    TextButton(
                        modifier = Modifier.testTag("restore-backup-first"),
                        onClick = {
                            backupBeforeRestoreLauncher.launch(
                                stampedFileName("mcqapp-backup", "zip")
                            )
                        }
                    ) { Text(stringResource(R.string.data_honesty_restore_backup_first)) }
                    TextButton(onClick = {
                        showRestoreConfirm = false
                        viewModel.clearRestorePreview()
                    }) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }
    restoreReport?.let { report ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissRestoreReport() },
            title = { Text(stringResource(R.string.restore_backup)) },
            text = { Text(report) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.dismissRestoreReport() }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                CompletionActions(
                    paperId = identifiableRestoredPaper(restoredPapers.map { it.id }),
                    onStudy = { id ->
                        viewModel.dismissRestoreReport()
                        navController.navigate(com.mcqapp.ui.navigation.studyRoute(id))
                    },
                    onTest = { id ->
                        viewModel.dismissRestoreReport()
                        navController.navigate(com.mcqapp.ui.navigation.testRoute(id))
                    },
                    onOpenPaper = { id ->
                        viewModel.dismissRestoreReport()
                        navController.navigate(com.mcqapp.ui.navigation.browseRoute(id))
                    },
                    onOpenLibrary = {
                        viewModel.dismissRestoreReport()
                        navController.navigate("library") {
                            popUpTo("library") { inclusive = true }
                        }
                    }
                )
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
    operationError?.let { error ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissOperationError() },
            title = {
                Text(
                    stringResource(
                        if (error.titleRes != 0) {
                            error.titleRes
                        } else {
                            R.string.export_failed
                        }
                    )
                )
            },
            text = { Text(error.message) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.dismissOperationError() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

/**
 * A failed manifest read must close the confirm and drop the picked uri; other
 * errors (or a ready preview) must leave it alone.
 */
internal fun restoreConfirmShouldClose(
    confirmVisible: Boolean,
    manifestReady: Boolean,
    failureTitleRes: Int?,
    expectedFailureTitleRes: Int
): Boolean =
    confirmVisible && !manifestReady && failureTitleRes == expectedFailureTitleRes

