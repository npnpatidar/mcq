package com.mcqapp.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _shuffleQuestions = MutableStateFlow(false)
    val shuffleQuestions: StateFlow<Boolean> = _shuffleQuestions.asStateFlow()

    private val _shuffleOptions = MutableStateFlow(false)
    val shuffleOptions: StateFlow<Boolean> = _shuffleOptions.asStateFlow()

    init {
        viewModelScope.launch {
            repository.shuffleQuestions().collect { _shuffleQuestions.value = it }
        }
        viewModelScope.launch {
            repository.shuffleOptions().collect { _shuffleOptions.value = it }
        }
    }

    fun setShuffleQuestions(enabled: Boolean) {
        viewModelScope.launch { repository.setShuffleQuestions(enabled) }
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
}
