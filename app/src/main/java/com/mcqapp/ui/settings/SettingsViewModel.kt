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
