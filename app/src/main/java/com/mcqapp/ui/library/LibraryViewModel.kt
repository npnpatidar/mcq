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
        viewModelScope.launch {
            try {
                val json = Exporter(repository.db()).exportPaper(paperId)
                writeUri(uri, json)
                _exportError.value = null
            } catch (e: Exception) {
                _exportError.value = "Export failed: ${e.message}"
            }
        }
    }

    private suspend fun writeUri(uri: Uri, text: String) {
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
            it.write(text.toByteArray())
        } ?: throw IllegalStateException("Could not open output stream")
    }

    fun dismissError() {
        _exportError.value = null
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
