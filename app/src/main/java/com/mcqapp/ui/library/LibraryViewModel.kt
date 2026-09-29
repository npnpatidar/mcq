package com.mcqapp.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Paper
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    val papers: StateFlow<List<Paper>> = repository.observePapers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _mistakeCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val mistakeCounts: StateFlow<Map<String, Int>> = _mistakeCounts.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAttempts().collect {
                try {
                    _mistakeCounts.value = repository.getMistakeCounts()
                } catch (e: Exception) {
                    Logger.e("LIBVM", "getMistakeCounts failed", e)
                }
            }
        }
    }

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    fun exportAll(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = Exporter(repository.db()).exportAll()
                writeUri(uri, json)
                _exportError.value = null
            } catch (e: Exception) {
                _exportError.value = "Export failed: ${e.message}"
            }
        }
    }

    fun exportPaper(uri: Uri, paperId: String) {
        exportPaperAs(uri, paperId, com.mcqapp.data.export.ExportFormat.JSON_INLINE)
    }

    fun exportPaperAs(uri: Uri, paperId: String, format: com.mcqapp.data.export.ExportFormat) {
        viewModelScope.launch {
            try {
                val result = com.mcqapp.data.export.PaperExporter(repository.db())
                    .exportPaper(paperId, format)
                Logger.i("LIBVM", "Exported ${result.fileName} (${result.bytes.size} bytes)")
                writeUriBytes(uri, result.bytes)
                _exportError.value = null
            } catch (e: Exception) {
                Logger.e("LIBVM", "Export failed", e)
                _exportError.value = "Export failed: ${e.message}"
            }
        }
    }

    fun exportCategoryAs(
        uri: Uri,
        paperId: String,
        categoryId: String,
        format: com.mcqapp.data.export.ExportFormat
    ) {
        viewModelScope.launch {
            try {
                val result = com.mcqapp.data.export.PaperExporter(repository.db())
                    .exportCategory(paperId, categoryId, format)
                Logger.i("LIBVM", "Exported ${result.fileName} (${result.bytes.size} bytes)")
                writeUriBytes(uri, result.bytes)
                _exportError.value = null
            } catch (e: Exception) {
                Logger.e("LIBVM", "Export failed", e)
                _exportError.value = "Export failed: ${e.message}"
            }
        }
    }

    private suspend fun writeUriBytes(uri: Uri, bytes: ByteArray) {
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
            it.write(bytes)
        } ?: throw IllegalStateException("Could not open output stream")
    }

    private suspend fun writeUri(uri: Uri, text: String) {
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
            it.write(text.toByteArray())
        } ?: throw IllegalStateException("Could not open output stream")
    }

    fun dismissError() {
        _exportError.value = null
    }

    fun duplicatePaper(paperId: String) {
        viewModelScope.launch {
            try {
                repository.duplicatePaper(paperId)
                Logger.i("LIBVM", "Duplicated paper $paperId")
            } catch (e: Exception) {
                Logger.e("LIBVM", "Duplicate failed", e)
                _exportError.value = "Duplicate failed: ${e.message}"
            }
        }
    }

    fun deletePaper(paperId: String) {
        viewModelScope.launch { repository.deletePaper(paperId) }
    }

    fun addPaper(title: String, description: String, durationMinutes: Int, negativeMarking: Double) {
        viewModelScope.launch {
            val id = "paper-" + System.currentTimeMillis().toString(36)
            repository.savePaper(
                Paper(
                    id = id,
                    title = title,
                    description = description,
                    durationMinutes = durationMinutes,
                    negativeMarking = negativeMarking
                )
            )
        }
    }

    fun addCategory(paperId: String, title: String, parentId: String?) {
        viewModelScope.launch { repository.addCategory(paperId, title, parentId) }
    }

    fun moveCategory(categoryId: String, delta: Int) {
        viewModelScope.launch {
            try {
                repository.moveCategory(categoryId, delta)
            } catch (e: Exception) {
                Logger.e("LIBVM", "Move category failed", e)
                _exportError.value = "Move failed: ${e.message}"
            }
        }
    }

    fun deleteCategory(categoryId: String) {
        Logger.i("LIBVM", "deleteCategory($categoryId)")
        viewModelScope.launch { repository.deleteCategory(categoryId) }
    }

    fun loadSampleData() {
        Logger.i("LIBVM", "Loading sample data from assets")
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val text = context.assets.open("sample_paper.json").bufferedReader().use { it.readText() }
                Logger.d("LIBVM", "Read sample JSON (${text.length} chars), importing")
                val file = LegacyParser.parse(text)
                val report = Importer(repository.db()).import(file)
                Logger.i("LIBVM", "Sample data loaded: ${report.newPapers} papers, ${report.newQuestions} questions")
            } catch (e: Exception) {
                Logger.e("LIBVM", "Sample load failed", e)
                _exportError.value = "Sample load failed: ${e.message}"
            }
        }
    }
}
