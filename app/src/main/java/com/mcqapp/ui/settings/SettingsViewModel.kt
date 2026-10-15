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
import com.mcqapp.util.Logger
import com.mcqapp.util.readBounded
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The FSRS optimizer's progress on the settings screen: idle, mid-fit with a
 * step count, the finished fit, or a failure the learner should see.
 */
sealed interface FsrsOptimizeState {
    data object Idle : FsrsOptimizeState
    data class Running(val step: Int, val total: Int) : FsrsOptimizeState
    data class Done(val result: com.mcqapp.domain.FsrsFitResult) : FsrsOptimizeState
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

    private val _updateAnswersOnDuplicate = MutableStateFlow(false)
    val updateAnswersOnDuplicate: StateFlow<Boolean> = _updateAnswersOnDuplicate.asStateFlow()

    private val _shuffleQuestions = MutableStateFlow(false)
    val shuffleQuestions: StateFlow<Boolean> = _shuffleQuestions.asStateFlow()

    private val _simplifiedStudy = MutableStateFlow(true)
    val simplifiedStudy: StateFlow<Boolean> = _simplifiedStudy.asStateFlow()

    private val _shuffleOptions = MutableStateFlow(false)
    val shuffleOptions: StateFlow<Boolean> = _shuffleOptions.asStateFlow()

    init {
        viewModelScope.launch {
            repository.shuffleQuestions().collect { _shuffleQuestions.value = it }
        }
        viewModelScope.launch {
            repository.simplifiedStudy().collect { _simplifiedStudy.value = it }
        }
        viewModelScope.launch {
            repository.shuffleOptions().collect { _shuffleOptions.value = it }
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

    fun setShuffleOptions(enabled: Boolean) {
        viewModelScope.launch { repository.setShuffleOptions(enabled) }
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
     */
    fun updateSchedulerConfig(config: com.mcqapp.domain.SchedulerConfig) {
        _schedulerConfig.value = config.sanitized()
        viewModelScope.launch { repository.setSchedulerConfig(config) }
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

    fun exportAll(uri: Uri, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val json = Exporter(repository.db()).exportAll()
                val outputStream = getApplication<Application>().contentResolver.openOutputStream(uri)
                if (outputStream == null) {
                    onError("Could not open file for writing")
                } else {
                    outputStream.use { it.write(json.toByteArray()) }
                    Logger.i("SETTINGS", "Exported all data (${json.length} chars)")
                }
            } catch (e: Exception) {
                Logger.e("SETTINGS", "Export failed", e)
                onError("Export failed: ${e.message}")
            }
        }
    }

    /** Writes the full backup zip; assembly and the file write stay off Main. */
    fun backupAll(uri: Uri, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val appVersion = try {
                        app.packageManager.getPackageInfo(app.packageName, 0).versionName
                    } catch (_: Exception) {
                        null
                    }
                    val backup = BackupSource(repository.db())
                        .collect(appVersion, repository.loadTestProgress())
                        .copy(settings = repository.settingsSnapshot())
                    McqBackupFormat.write(
                        manifest = backup.manifest,
                        content = backup.content,
                        study = backup.study,
                        settings = backup.settings
                    )
                }
                val written = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val outputStream = app.contentResolver.openOutputStream(uri)
                    if (outputStream == null) {
                        -1
                    } else {
                        outputStream.use { it.write(bytes) }
                        bytes.size
                    }
                }
                if (written < 0) {
                    onError("Could not open file for writing")
                } else {
                    Logger.i("SETTINGS", "Backup written ($written bytes)")
                }
            } catch (e: Exception) {
                Logger.e("SETTINGS", "Backup failed", e)
                onError("Backup failed: ${e.message}")
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
                // A backup legitimately holds inline data-URI images, so it
                // gets the zip expansion cap rather than the 64 MB bank
                // import limit — and a message that talks about backups.
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { stream ->
                        readBounded(stream, limit = com.mcqapp.data.SafeZip.MAX_TOTAL_BYTES)
                    } ?: throw IllegalStateException("Could not read the file.")
                }
                val backup = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    McqBackupFormat.read(bytes)
                }
                val updateAnswers = repository.updateAnswersOnDuplicate().first()
                val report = BackupRestorer(repository.db()).restore(
                    backup,
                    updateAnswersOnDuplicate = updateAnswers,
                    applySettings = { repository.settingsRestore(it) },
                    applyInProgress = { snapshot ->
                        if (snapshot != null) repository.saveTestProgress(snapshot)
                        else repository.clearTestProgress()
                    }
                )
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
