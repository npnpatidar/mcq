package com.mcqapp.ui.importscreen

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object ImportDataHolder {
    var pendingJsonText: String? = null
    var pendingEditQuestion: QuestionDto? = null
    var editingFromImport: Boolean = false
}

data class ImportUiState(
    val loading: Boolean = true,
    val paperTitle: String = "",
    val paperDescription: String = "",
    val durationMinutes: Int = 0,
    val negativeMarking: Double = 0.0,
    val categoryName: String = "Uncategorized",
    val questions: List<QuestionDto> = emptyList(),
    val originalFile: McqFileDto? = null,
    val importing: Boolean = false,
    val importDone: Boolean = false
)

class ImportViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    var lastEditedQuestionId: String? = null
        private set

    fun markEditing(questionId: String) {
        lastEditedQuestionId = questionId
        Logger.d("IMPORTVM", "markEditing(id=$questionId): holder pendingEdit set by screen next")
    }

    fun updateQuestionFromImport(dto: QuestionDto) {
        val before = _state.value.questions.find { it.id == dto.id }
        Logger.d("IMPORTVM", "updateQuestionFromImport(id=${dto.id}): foundInState=${before != null}, " +
            "beforeText='${before?.text?.take(60)}', afterText='${dto.text.take(60)}', " +
            "afterOptions=${dto.options.size}, afterCorrect=${dto.correctOptionIds}")
        _state.update { s ->
            s.copy(questions = s.questions.map { if (it.id == dto.id) dto else it })
        }
        Logger.i("IMPORTVM", "Updated question ${dto.id} from in-memory edit")
    }

    fun clearLastEdited() {
        lastEditedQuestionId = null
    }

    fun loadJson(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = ImportDataHolder.pendingJsonText
                    ?: getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                if (text == null) {
                    Logger.e("IMPORTVM", "Could not read file")
                    return@launch
                }
                ImportDataHolder.pendingJsonText = null
                parseAndLoad(text)
            } catch (e: Exception) {
                Logger.e("IMPORTVM", "Failed to parse JSON", e)
            }
        }
    }

    private var loadedDirectText: String? = null

    fun loadJsonText(text: String) {
        if (text == loadedDirectText && _state.value.questions.isNotEmpty()) {
            Logger.d("IMPORTVM", "loadJsonText: same text already loaded " +
                "(${_state.value.questions.size} questions), skipping reload to preserve in-memory edits")
            return
        }
        loadedDirectText = text
        viewModelScope.launch {
            try {
                parseAndLoad(text)
            } catch (e: Exception) {
                loadedDirectText = null
                Logger.e("IMPORTVM", "Failed to parse JSON", e)
            }
        }
    }

    private suspend fun parseAndLoad(text: String) {
        val file = LegacyParser.parse(text)
        val paper = file.papers.firstOrNull()
        if (paper == null) {
            Logger.e("IMPORTVM", "No papers found in JSON")
            return
        }

        val allQuestions = paper.categories.flatMap { it.questions }
        val effectiveCategory = if (paper.categories.isEmpty() || paper.categories.all { it.questions.isEmpty() }) {
            "Uncategorized"
        } else {
            paper.categories.firstOrNull { it.questions.isNotEmpty() }?.title ?: "Uncategorized"
        }

        _state.value = ImportUiState(
            loading = false,
            paperTitle = paper.title,
            paperDescription = paper.description,
            durationMinutes = paper.durationMinutes,
            negativeMarking = paper.negativeMarking,
            categoryName = effectiveCategory,
            questions = allQuestions,
            originalFile = file
        )
        Logger.i("IMPORTVM", "Loaded ${allQuestions.size} questions for import preview")
    }

    fun updatePaperTitle(value: String) = update { it.copy(paperTitle = value) }
    fun updatePaperDescription(value: String) = update { it.copy(paperDescription = value) }
    fun updateDuration(value: Int) = update { it.copy(durationMinutes = value) }
    fun updateNegativeMarking(value: Double) = update { it.copy(negativeMarking = value) }
    fun updateCategoryName(value: String) = update { it.copy(categoryName = value) }

    fun deleteQuestion(questionId: String) = update {
        it.copy(questions = it.questions.filter { q -> q.id != questionId })
    }

    private fun update(block: (ImportUiState) -> ImportUiState) {
        _state.update(block)
    }

    fun import(onDone: () -> Unit) {
        val s = _state.value
        if (s.paperTitle.isBlank() || s.questions.isEmpty()) return
        _state.update { it.copy(importing = true) }

        viewModelScope.launch {
            try {
                val original = s.originalFile
                val paperDto: PaperDto = if (original != null && original.papers.isNotEmpty()) {
                    val orig = original.papers.first()
                    orig.copy(
                        title = s.paperTitle.trim(),
                        description = s.paperDescription.trim(),
                        durationMinutes = s.durationMinutes,
                        negativeMarking = s.negativeMarking,
                        categories = orig.categories.map { cat ->
                            cat.copy(
                                title = if (orig.categories.size == 1) {
                                    s.categoryName.trim().ifBlank { cat.title }
                                } else {
                                    cat.title
                                },
                                questions = cat.questions.mapNotNull { origQ ->
                                    s.questions.find { it.id == origQ.id }
                                }
                            )
                        }
                    )
                } else {
                    val paperId = "paper-" + System.currentTimeMillis().toString(36)
                    PaperDto(
                        id = paperId,
                        title = s.paperTitle.trim(),
                        description = s.paperDescription.trim(),
                        durationMinutes = s.durationMinutes,
                        negativeMarking = s.negativeMarking,
                        categories = listOf(
                            CategoryDto(
                                id = paperId + "-cat",
                                title = s.categoryName.trim().ifBlank { "Uncategorized" },
                                questions = s.questions
                            )
                        )
                    )
                }

                val file = McqFileDto(version = 1, papers = listOf(paperDto))
                Logger.d("IMPORTVM", "import(): using ${s.questions.size} edited state questions " +
                    "(correct set on ${s.questions.count { it.correctOptionIds.isNotEmpty() }})")
                val report = com.mcqapp.data.io.Importer(repository.db()).import(file)
                Logger.i("IMPORTVM", "Imported: ${report.newPapers} new papers, ${report.updatedPapers} updated, " +
                    "${report.newQuestions} new questions, ${report.updatedQuestions} updated")
                _state.update { it.copy(importing = false, importDone = true) }
                onDone()
            } catch (e: Exception) {
                Logger.e("IMPORTVM", "Import failed", e)
                _state.update { it.copy(importing = false) }
            }
        }
    }
}
