package com.mcqapp.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.backup.BackupRestorer
import com.mcqapp.data.backup.BackupSource
import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.FsrsFitResult
import com.mcqapp.ui.theme.AppTheme
import com.mcqapp.ui.theme.CustomTheme
import com.mcqapp.ui.theme.CustomThemeCodec
import com.mcqapp.ui.theme.InvalidThemeException
import com.mcqapp.ui.theme.MAX_CUSTOM_THEMES
import com.mcqapp.ui.theme.MAX_THEME_FILE_BYTES
import com.mcqapp.util.Logger
import com.mcqapp.util.boundedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * The FSRS optimizer's progress on the settings screen: idle, mid-fit with a
 * step count, the finished fit, or a failure the learner should see.
 */
sealed interface FsrsOptimizeState {
    data object Idle : FsrsOptimizeState
    data class Running(val step: Int, val total: Int) : FsrsOptimizeState
    data class Done(val result: FsrsFitResult) : FsrsOptimizeState
    data class Failed(val message: String) : FsrsOptimizeState
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: McqRepository = (application as McqApplication).repository

    private val _themeMode = MutableStateFlow("system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    init {
        viewModelScope.launch {
            repository.themeMode().collect { _themeMode.value = it }
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    private val _themePalette = MutableStateFlow(AppTheme.MATERIAL.key)
    val themePalette: StateFlow<String> = _themePalette.asStateFlow()

    private val _customThemes = MutableStateFlow<List<CustomTheme>>(emptyList())
    internal val customThemes: StateFlow<List<CustomTheme>> = _customThemes.asStateFlow()

    init {
        viewModelScope.launch {
            repository.themePalette().collect { _themePalette.value = it }
        }
        viewModelScope.launch {
            repository.customThemesJson().collect {
                _customThemes.value = CustomThemeCodec.decodeAll(it)
            }
        }
    }

    fun setThemePalette(key: String) {
        viewModelScope.launch { repository.setThemePalette(key) }
    }

    /**
     * Imports a theme file picked by the user and selects it. The read is
     * capped at [MAX_THEME_FILE_BYTES]: the picker also
     * offers "any file" on some devices, and a bank or a backup must fail
     * with a sentence, not fill the heap.
     */
    fun importTheme(uri: Uri, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val stream = app.contentResolver.openInputStream(uri)
                        ?: throw InvalidThemeException(
                            "Could not open the theme file."
                        )
                    stream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_THEME_FILE_BYTES) {
                                throw InvalidThemeException(
                                    "That file is too large for a theme " +
                                        "(over ${MAX_THEME_FILE_BYTES / 1024} KB)."
                                )
                            }
                            out.write(buffer, 0, read)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                }
                importThemeText(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidThemeException) {
                onError(e.message ?: "Invalid theme file.")
            } catch (e: Exception) {
                Logger.e("SETTINGS", "Theme import failed", e)
                onError(e.message ?: "Could not import the theme.")
            }
        }
    }

    /**
     * Validates and stores [source], then selects it. Same-name imports
     * replace the old theme instead of stacking duplicates, and the cap is
     * checked inside the edit so a failed import writes nothing.
     */
    internal suspend fun importThemeText(source: String): String {
        val parsed = CustomThemeCodec.parse(source)
        var imported: CustomTheme? = null
        repository.updateCustomThemesJson { raw ->
            val existing = CustomThemeCodec.decodeAll(raw)
            val key = existing.firstOrNull { it.name.equals(parsed.name, ignoreCase = true) }?.key
                ?: "custom-" + System.currentTimeMillis().toString(36)
            if (existing.none { it.key == key } &&
                existing.size >= MAX_CUSTOM_THEMES
            ) {
                throw InvalidThemeException(
                    "You can keep up to ${MAX_CUSTOM_THEMES} imported themes."
                )
            }
            val theme = CustomTheme(key, parsed.name, parsed.spec)
            imported = theme
            CustomThemeCodec.encodeAll(
                existing.filterNot { it.key == key } + theme
            )
        }
        val theme = imported
            ?: throw InvalidThemeException("The theme was not stored.")
        repository.setThemePalette(theme.key)
        return theme.name
    }

    fun removeCustomTheme(key: String) {
        viewModelScope.launch {
            try {
                repository.updateCustomThemesJson { raw ->
                    CustomThemeCodec.encodeAll(
                        CustomThemeCodec.decodeAll(raw)
                            .filterNot { it.key == key }
                    )
                }
                if (_themePalette.value == key) {
                    repository.setThemePalette(AppTheme.MATERIAL.key)
                }
            } catch (e: Exception) {
                Logger.e("SETTINGS", "Removing an imported theme failed", e)
            }
        }
    }

    private val _updateAnswersOnDuplicate = MutableStateFlow(false)
    val updateAnswersOnDuplicate: StateFlow<Boolean> = _updateAnswersOnDuplicate.asStateFlow()

    private val _shuffleQuestions = MutableStateFlow(false)
    val shuffleQuestions: StateFlow<Boolean> = _shuffleQuestions.asStateFlow()

    private val _simplifiedStudy = MutableStateFlow(true)
    val simplifiedStudy: StateFlow<Boolean> = _simplifiedStudy.asStateFlow()

    private val _automaticLadderAdaptation = MutableStateFlow(false)
    val automaticLadderAdaptation: StateFlow<Boolean> =
        _automaticLadderAdaptation.asStateFlow()

    private val _shuffleOptions = MutableStateFlow(false)
    val shuffleOptions: StateFlow<Boolean> = _shuffleOptions.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            repository.shuffleQuestions().collect { _shuffleQuestions.value = it }
        }
        viewModelScope.launch {
            repository.simplifiedStudy().collect { _simplifiedStudy.value = it }
        }
        viewModelScope.launch {
            repository.automaticLadderAdaptation()
                .collect { _automaticLadderAdaptation.value = it }
        }
        viewModelScope.launch {
            repository.shuffleOptions().collect { _shuffleOptions.value = it }
        }
        viewModelScope.launch {
            repository.hapticsEnabled().collect { _hapticsEnabled.value = it }
        }
    }

    init {
        viewModelScope.launch {
            repository.updateAnswersOnDuplicate()
                .collect { _updateAnswersOnDuplicate.value = it }
        }
    }

    fun setUpdateAnswersOnDuplicate(enabled: Boolean) {
        viewModelScope.launch { repository.setUpdateAnswersOnDuplicate(enabled) }
    }

    fun setShuffleQuestions(enabled: Boolean) {
        viewModelScope.launch { repository.setShuffleQuestions(enabled) }
    }

    fun setSimplifiedStudy(enabled: Boolean) {
        viewModelScope.launch { repository.setSimplifiedStudy(enabled) }
    }

    fun setAutomaticLadderAdaptation(enabled: Boolean) {
        viewModelScope.launch { repository.setAutomaticLadderAdaptation(enabled) }
    }

    fun setShuffleOptions(enabled: Boolean) {
        viewModelScope.launch { repository.setShuffleOptions(enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setHapticsEnabled(enabled) }
    }

    private val _practiceMode = MutableStateFlow(false)
    val practiceMode: StateFlow<Boolean> = _practiceMode.asStateFlow()

    init {
        viewModelScope.launch {
            repository.practiceMode().collect { _practiceMode.value = it }
        }
    }

    fun setPracticeMode(enabled: Boolean) {
        viewModelScope.launch { repository.setPracticeMode(enabled) }
    }

    private val _strictMode = MutableStateFlow(false)
    val strictMode: StateFlow<Boolean> = _strictMode.asStateFlow()

    init {
        viewModelScope.launch {
            repository.strictMode().collect { _strictMode.value = it }
        }
    }

    fun setStrictMode(enabled: Boolean) {
        viewModelScope.launch { repository.setStrictMode(enabled) }
    }

    private val _autoAdvance = MutableStateFlow(false)
    val autoAdvance: StateFlow<Boolean> = _autoAdvance.asStateFlow()

    init {
        viewModelScope.launch {
            repository.autoAdvance().collect { _autoAdvance.value = it }
        }
    }

    fun setAutoAdvance(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoAdvance(enabled) }
    }

    private val _avoidRecentQuestions = MutableStateFlow(true)
    val avoidRecentQuestions: StateFlow<Boolean> = _avoidRecentQuestions.asStateFlow()

    init {
        viewModelScope.launch {
            repository.avoidRecentQuestions().collect { _avoidRecentQuestions.value = it }
        }
    }

    fun setAvoidRecentQuestions(enabled: Boolean) {
        viewModelScope.launch { repository.setAvoidRecentQuestions(enabled) }
    }

    private val _loadRemoteImages = MutableStateFlow(false)
    val loadRemoteImages: StateFlow<Boolean> = _loadRemoteImages.asStateFlow()

    init {
        viewModelScope.launch {
            repository.loadRemoteImages().collect { _loadRemoteImages.value = it }
        }
    }

    fun setLoadRemoteImages(enabled: Boolean) {
        viewModelScope.launch { repository.setLoadRemoteImages(enabled) }
    }

    private val _fontScale = MutableStateFlow(com.mcqapp.util.FontScale.DEFAULT)
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    init {
        viewModelScope.launch {
            repository.fontScale().collect { _fontScale.value = it }
        }
    }

    fun setFontScale(scale: Float) {
        viewModelScope.launch { repository.setFontScale(scale) }
    }

    private val _pdfTwoColumn = MutableStateFlow(false)
    val pdfTwoColumn: StateFlow<Boolean> = _pdfTwoColumn.asStateFlow()

    init {
        viewModelScope.launch {
            repository.pdfTwoColumn().collect { _pdfTwoColumn.value = it }
        }
    }

    fun setPdfTwoColumn(enabled: Boolean) {
        viewModelScope.launch { repository.setPdfTwoColumn(enabled) }
    }

    private val _schedulerConfig = MutableStateFlow(com.mcqapp.domain.SchedulerConfig())
    val schedulerConfig: StateFlow<com.mcqapp.domain.SchedulerConfig> = _schedulerConfig.asStateFlow()

    init {
        viewModelScope.launch {
            repository.schedulerConfig().collect { _schedulerConfig.value = it }
        }
    }

    /**
     * Applies a single change. Each field edit writes the whole config, so the
     * in-progress value and the stored value never drift apart.
     *
     * The weights are re-based on the STORED config before the write: the
     * composition's snapshot can be a re-emit behind a fresh fit (the tuner
     * persists with its own single-key write), and writing the snapshot's
     * stale weights over it reverted the fit while the success banner showed
     * (consolidated_bugs.md H4). Only the tuner's own path changes weights,
     * so the stored copy is always the newest.
     */
    fun updateSchedulerConfig(config: com.mcqapp.domain.SchedulerConfig) {
        val sanitized = config.sanitized()
        viewModelScope.launch {
            val stored = try {
                repository.schedulerConfigNow()
            } catch (_: Exception) {
                sanitized
            }
            repository.setSchedulerConfig(sanitized.copy(fsrsParameters = stored.fsrsParameters))
        }
        _schedulerConfig.value = sanitized
    }

    fun resetSchedulerConfig() {
        _schedulerConfig.value = com.mcqapp.domain.SchedulerConfig().sanitized()
        viewModelScope.launch { repository.resetSchedulerConfig() }
    }

    // --- FSRS parameter optimization ---

    private val _fsrsReviewCount = MutableStateFlow(0)
    val fsrsReviewCount: StateFlow<Int> = _fsrsReviewCount.asStateFlow()

    private val _fsrsOptimize = MutableStateFlow<FsrsOptimizeState>(FsrsOptimizeState.Idle)
    val fsrsOptimize: StateFlow<FsrsOptimizeState> = _fsrsOptimize.asStateFlow()

    /** The one-shot FSRS-7 migration notice, until the learner dismisses it. */
    private val _fsrs7Notice = MutableStateFlow(false)
    val fsrs7Notice: StateFlow<Boolean> = _fsrs7Notice.asStateFlow()

    init {
        viewModelScope.launch {
            repository.fsrs7Notice().collect { _fsrs7Notice.value = it }
        }
    }

    fun dismissFsrs7Notice() {
        viewModelScope.launch { repository.dismissFsrs7Notice() }
    }

    init {
        viewModelScope.launch {
            repository.fsrsReviewCount().collect { _fsrsReviewCount.value = it }
        }
    }

    /**
     * Runs the fit; the scheduler config flow delivers the stored weights
     * back through [schedulerConfig] once the tuner persists them, so the
     * screen never applies them by hand.
     */
    fun optimizeFsrsParameters() {
        if (_fsrsOptimize.value is FsrsOptimizeState.Running) return
        _fsrsOptimize.value = FsrsOptimizeState.Running(step = 0, total = 0)
        viewModelScope.launch {
            try {
                val result = repository.optimizeFsrsParameters { step, total ->
                    _fsrsOptimize.value = FsrsOptimizeState.Running(step, total)
                }
                _fsrsOptimize.value = FsrsOptimizeState.Done(result)
            } catch (e: CancellationException) {
                // Leaving Settings cancels the fit; nothing was persisted
                // (the tuner writes only after a successful fit), so let the
                // cancellation propagate instead of logging it as a failure
                // and showing a JobCancellationException to the learner.
                throw e
            } catch (e: Exception) {
                Logger.e("SETTINGS", "FSRS optimize failed", e)
                _fsrsOptimize.value = FsrsOptimizeState.Failed(
                    e.message ?: "Optimization failed"
                )
            }
        }
    }

    fun dismissFsrsOptimize() {
        _fsrsOptimize.value = FsrsOptimizeState.Idle
    }

    fun restoreFsrsDefaults() {
        viewModelScope.launch {
            repository.restoreFsrsDefaults()
            _fsrsOptimize.value = FsrsOptimizeState.Idle
        }
    }

    private val _storage = MutableStateFlow<com.mcqapp.domain.StorageInfo.Report?>(null)
    val storage: StateFlow<com.mcqapp.domain.StorageInfo.Report?> = _storage.asStateFlow()

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch {
            try {
                _storage.value = repository.storageReport()
            } catch (e: Exception) {
                Logger.e("SETTINGS", "storageReport failed", e)
            }
        }
    }

    fun exportAll(uri: Uri, onError: (String) -> Unit, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            exportAllOn(uri, onError = onError)
            onDone()
        }
    }

    /**
     * The whole bank's serialization plus the file write runs here, so it
     * happens off the calling dispatcher — the JSON export was the one sibling
     * that did not hop: `backupAll` and `restoreBackup` both wrap in
     * `Dispatchers.IO`, and this is what made "Export all data (JSON)" a
     * jank/ANR paragraph-frame producer on a few-thousand-question bank.
     *
     * The dispatcher is a seam so a test can prove the body actually runs on
     * it: the public [exportAll] passes `Dispatchers.IO` like its siblings
     * (launch always arrives on `Main.immediate`), and the open hook is
     * invoked inside [withContext] so the stream is opened on the same
     * thread that writes it.
     */
    internal suspend fun exportAllOn(
        uri: Uri,
        onError: (String) -> Unit,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        open: () -> OutputStream? = { getApplication<Application>().contentResolver.openOutputStream(uri) }
    ) {
        withContext(dispatcher) {
            try {
                // Streamed: the whole bank never lives in memory as a String.
                val outputStream = open()
                if (outputStream == null) {
                    onError("Could not open file for writing")
                } else {
                    outputStream.use { out ->
                        Exporter(repository.db()).exportAllTo(
                            java.io.BufferedWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8), 1 shl 14)
                        )
                    }
                    Logger.i("SETTINGS", "Exported all data (streamed)")
                }
            } catch (e: Exception) {
                Logger.e("SETTINGS", "Export failed", e)
                onError("Export failed: ${e.message}")
            }
        }
    }

    /** Writes the full backup zip; every entry streams, nothing is held whole. */
    fun backupAll(uri: Uri, onError: (String) -> Unit, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val appVersion = try {
                        app.packageManager.getPackageInfo(app.packageName, 0).versionName
                    } catch (_: Exception) {
                        null
                    }
                    val db = repository.db()
                    val paperRows = db.paperDao().getAll()
                    val paperCreated = paperRows.map {
                        // Carry the drawer order: a restore recreates every paper
                        // as new (each takes the top slot), so without it the
                        // library comes back reversed and any custom order is
                        // lost. The restore side already honours this field.
                        McqBackupFormat.PaperCreated(it.id, it.createdAt, it.sortOrder)
                    }
                    // The session's answers are rows; the backup carries them
                    // inlined in the one document the format has always had.
                    val inProgressTest = repository.testProgressForBackup()
                    val manifest = McqBackupFormat.Manifest(
                        dbVersion = db.openHelper.readableDatabase.version,
                        appVersion = appVersion,
                        exportedAt = System.currentTimeMillis(),
                        papers = paperRows.size,
                        questions = db.questionDao().countQuestions(),
                        attempts = db.attemptDao().countAll(),
                        // Counted in SQL: the rows are streamed below, never listed.
                        reviews = db.reviewLogDao().countAll()
                    )
                    val settings = McqBackupFormat.SettingsFile(
                        preferences = repository.settingsSnapshot().preferences
                    )
                    val output = app.contentResolver.openOutputStream(uri)
                    if (output == null) {
                        onError("Could not open file for writing")
                        return@withContext
                    }
                    output.use { out ->
                        McqBackupFormat.writeTo(
                            output = out,
                            manifest = manifest,
                            content = { w ->
                                // The zip carries the rows verbatim in
                                // study.json, so the question-keyed map would
                                // only duplicate them.
                                com.mcqapp.data.io.Exporter(db)
                                    .exportAllTo(w, withScheduling = false)
                            },
                            study = { w ->
                                val source = com.mcqapp.data.backup.BackupSource(db)
                                McqBackupFormat.writeStudyRows(
                                    w = w,
                                    cardState = { emit -> source.emitCards(emit) },
                                    reviewLog = { emit -> source.emitReviews(emit) },
                                    paperCreated = paperCreated,
                                    inProgressTest = inProgressTest,
                                    panels = source.panels(),
                                    blueprints = source.backupBlueprints(),
                                    ladderVersions = source.ladderVersions(),
                                    ladderBlocks = source.ladderBlocks(),
                                    studySessions = source.studySessions()
                                )
                            },
                            settings = settings
                        )
                    }
                    Logger.i("SETTINGS", "Backup written (streamed)")
                }
            } catch (e: Exception) {
                Logger.e("SETTINGS", "Backup failed", e)
                onError("Backup failed: ${e.message}")
            } finally {
                onDone()
            }
        }
    }

    /**
     * Renders the whole bank JSON into `cacheDir/shared` and returns the raw
     * `ACTION_SEND` intent (the screen wraps it in a chooser). Null on
     * failure; the screen shows the failure dialog it owns.
     */
    suspend fun shareAllIntent(): android.content.Intent? {
        return withContext(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val file = com.mcqapp.util.nextSharedFile(app.cacheDir, "mcq-export.json")
                java.io.FileOutputStream(file).use { out ->
                    Exporter(repository.db()).exportAllTo(
                        java.io.BufferedWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8), 1 shl 14)
                    )
                }
                Logger.i("SETTINGS", "Shared all data '${file.name}' (${file.length()} bytes)")
                com.mcqapp.util.shareIntent(app, file, "application/json")
            } catch (e: Exception) {
                Logger.e("SETTINGS", "Share failed", e)
                null
            }
        }
    }

    /**
     * Renders the full backup zip into `cacheDir/shared` and returns the raw
     * `ACTION_SEND` intent. Null on failure; the screen shows the failure
     * dialog it owns.
     */
    suspend fun shareBackupIntent(): android.content.Intent? {
        return withContext(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val file = com.mcqapp.util.nextSharedFile(app.cacheDir, "mcqapp-backup.zip")
                val appVersion = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                } catch (_: Exception) {
                    null
                }
                val db = repository.db()
                val paperRows = db.paperDao().getAll()
                val paperCreated = paperRows.map {
                    McqBackupFormat.PaperCreated(it.id, it.createdAt, it.sortOrder)
                }
                val inProgressTest = repository.testProgressForBackup()
                val manifest = McqBackupFormat.Manifest(
                    dbVersion = db.openHelper.readableDatabase.version,
                    appVersion = appVersion,
                    exportedAt = System.currentTimeMillis(),
                    papers = paperRows.size,
                    questions = db.questionDao().countQuestions(),
                    attempts = db.attemptDao().countAll(),
                    reviews = db.reviewLogDao().countAll()
                )
                val settings = McqBackupFormat.SettingsFile(
                    preferences = repository.settingsSnapshot().preferences
                )
                java.io.FileOutputStream(file).use { out ->
                    McqBackupFormat.writeTo(
                        output = out,
                        manifest = manifest,
                        content = { w ->
                            com.mcqapp.data.io.Exporter(db).exportAllTo(w, withScheduling = false)
                        },
                        study = { w ->
                            val source = com.mcqapp.data.backup.BackupSource(db)
                            McqBackupFormat.writeStudyRows(
                                w = w,
                                cardState = { emit -> source.emitCards(emit) },
                                reviewLog = { emit -> source.emitReviews(emit) },
                                paperCreated = paperCreated,
                                inProgressTest = inProgressTest,
                                panels = source.panels(),
                                blueprints = source.backupBlueprints()
                            )
                        },
                        settings = settings
                    )
                }
                Logger.i("SETTINGS", "Shared backup '${file.name}' (${file.length()} bytes)")
                com.mcqapp.util.shareIntent(app, file, "application/zip")
            } catch (e: Exception) {
                Logger.e("SETTINGS", "Share backup failed", e)
                null
            }
        }
    }

    /**
     * Reads and restores a backup zip. The wipe-and-replace is atomic in
     * the database; settings flip after. [onDone] reports the summary the
     * screen shows.
     */
    fun restoreBackup(uri: Uri, onError: (String) -> Unit, onDone: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val updateAnswers = repository.updateAnswersOnDuplicate().first()
                // The backup is restored as it is read: the zip, the bank and
                // the study table are never held, so a backup bigger than the
                // heap restores instead of dying. The zip expansion cap still
                // applies, as does a cap on the archive's own bytes.
                val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val stream = app.contentResolver.openInputStream(uri)
                        ?.let { boundedStream(it, limit = com.mcqapp.data.SafeZip.MAX_TOTAL_BYTES) }
                        ?: throw IllegalStateException("Could not read the file.")
                    stream.use { input ->
                        BackupRestorer(repository.db()).restoreStreaming(
                            input,
                            updateAnswersOnDuplicate = updateAnswers,
                            applySettings = { repository.settingsRestore(it) },
                            applyInProgress = { snapshot ->
                                repository.restoreTestProgress(snapshot)
                            }
                        )
                    }
                }
                refreshStorage()
                Logger.i("SETTINGS", "Backup restored: $report")
                onDone(report)
            } catch (e: com.mcqapp.util.ImportTooLargeException) {
                Logger.e("SETTINGS", "Restore failed", e)
                val limitMb = com.mcqapp.data.SafeZip.MAX_TOTAL_BYTES / (1024 * 1024)
                onError("That backup is over the $limitMb MB restore limit — it may be corrupt.")
            } catch (e: Exception) {
                Logger.e("SETTINGS", "Restore failed", e)
                onError("Restore failed: ${e.message}")
            }
        }
    }
}
