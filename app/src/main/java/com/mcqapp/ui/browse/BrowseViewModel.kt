package com.mcqapp.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val loading: Boolean = true,
    val paper: Paper? = null,
    val questions: List<Question> = emptyList()
)

class BrowseViewModel(
    application: Application,
    private val paperId: String
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(BrowseUiState())
    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    init {
        Logger.i("BROWSEVM", "BrowseViewModel created: paperId=$paperId")
        val created = android.os.SystemClock.elapsedRealtime()
        var firstLoad = true
        viewModelScope.launch {
            val paper = repository.getPaper(paperId)
            _state.value = _state.value.copy(paper = paper)
        }
        viewModelScope.launch {
            try {
                repository.observeQuestionsForPaper(paperId).collect { questions ->
                    val ids = questions.map { it.id }
                    Logger.d("BROWSEVM", "Observed ${questions.size} questions for paper $paperId: " +
                        "order=[${ids.take(5).joinToString(",")}${if (ids.size > 5) ",... +${ids.size - 5} more" else ""}]")
                    if (firstLoad) {
                        firstLoad = false
                        val runtime = Runtime.getRuntime()
                        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
                        val maxMb = runtime.maxMemory() / 1048576
                        Logger.i("BROWSEVM", "first load of ${questions.size} questions took " +
                            "${android.os.SystemClock.elapsedRealtime() - created}ms, " +
                            "heap ${usedMb}MB/${maxMb}MB")
                    }
                    _state.update { it.copy(loading = false, questions = questions) }
                }
            } catch (e: Exception) {
                Logger.e("BROWSEVM", "Observation of questions for paper $paperId FAILED", e)
            }
        }
    }

    fun deleteQuestion(questionId: String) {
        viewModelScope.launch {
            repository.deleteQuestion(questionId)
            Logger.i("BROWSEVM", "Deleted question $questionId")
        }
    }

    fun duplicateQuestion(questionId: String) {
        viewModelScope.launch {
            repository.duplicateQuestion(questionId)
        }
    }

    fun deleteQuestions(questionIds: Set<String>) {
        viewModelScope.launch {
            repository.deleteQuestions(questionIds)
            Logger.i("BROWSEVM", "Deleted ${questionIds.size} questions")
        }
    }

    fun moveQuestions(questionIds: Set<String>, targetCategoryId: String) {
        viewModelScope.launch {
            repository.moveQuestionsToCategory(questionIds, targetCategoryId)
            Logger.i("BROWSEVM", "Moved ${questionIds.size} questions to $targetCategoryId")
        }
    }

    fun swapQuestions(firstId: String, secondId: String) {
        viewModelScope.launch {
            repository.swapQuestionOrder(firstId, secondId)
        }
    }

    val papers: StateFlow<List<Paper>> = repository.observePapers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun copyQuestions(questionIds: Set<String>, targetCategoryId: String) {
        viewModelScope.launch {
            repository.copyQuestionsToCategory(questionIds, targetCategoryId)
            Logger.i("BROWSEVM", "Copied ${questionIds.size} questions to $targetCategoryId")
        }
    }

    /**
     * Writes just the selected questions out as a paper of their own, so a
     * subset can leave the app without a temporary paper being created.
     */
    fun exportSelection(
        uri: android.net.Uri,
        questionIds: Set<String>,
        title: String,
        format: com.mcqapp.data.export.ExportFormat
    ) {
        viewModelScope.launch {
            try {
                val selected = _state.value.questions.filter { it.id in questionIds }
                if (selected.isEmpty()) throw IllegalStateException("Nothing selected")
                val dto = com.mcqapp.data.io.SelectionExport.paperDto(
                    title = title,
                    questions = selected,
                    categoryTitleById = categoryTitles()
                )
                val result = com.mcqapp.data.export.PaperExporter(repository.db())
                    .exportDto(dto, title, format, twoColumnPdf = repository.pdfTwoColumn().first())
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    it.write(result.bytes)
                } ?: throw IllegalStateException("Could not open output stream")
                Logger.i("BROWSEVM", "Exported ${selected.size} questions as ${result.fileName}")
                _exportError.value = null
            } catch (e: Exception) {
                Logger.e("BROWSEVM", "Export of selection failed", e)
                _exportError.value = "Export failed: ${e.message}"
            }
        }
    }

    /** Every category in this paper, flattened to id -> title for the export. */
    private fun categoryTitles(): Map<String, String> = buildMap {
        fun walk(nodes: List<com.mcqapp.domain.CategoryNode>) {
            for (node in nodes) {
                put(node.id, node.title)
                walk(node.children)
            }
        }
        walk(_state.value.paper?.categories.orEmpty())
    }

    fun dismissExportError() {
        _exportError.value = null
    }

    fun bulkEdit(
        questionIds: Set<String>,
        marksText: String,
        difficulty: Difficulty?,
        tagsText: String
    ) {
        viewModelScope.launch {
            val marks = marksText.trim().takeIf { it.isNotEmpty() }?.let { raw ->
                raw.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            }
            val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                .takeIf { tagsText.isNotBlank() }
            repository.bulkUpdateQuestions(questionIds, marks, difficulty, tags)
            Logger.i("BROWSEVM", "Bulk-edited ${questionIds.size} questions")
        }
    }
}
