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
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportUiState(
    val loading: Boolean = true,
    val paperTitle: String = "",
    val paperDescription: String = "",
    val durationMinutes: Int = 0,
    val negativeMarking: Double = 0.0,
    val categoryName: String = "Uncategorized",
    val questions: List<QuestionDto> = emptyList(),
    val importing: Boolean = false,
    val importDone: Boolean = false
)

class ImportViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun loadJson(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                if (text == null) {
                    Logger.e("IMPORTVM", "Could not read file")
                    return@launch
                }
                val file = LegacyParser.parse(text)
                val paper = file.papers.firstOrNull()
                if (paper == null) {
                    Logger.e("IMPORTVM", "No papers found in JSON")
                    return@launch
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
                    questions = allQuestions
                )
                Logger.i("IMPORTVM", "Loaded ${allQuestions.size} questions for import preview")
            } catch (e: Exception) {
                Logger.e("IMPORTVM", "Failed to parse JSON", e)
            }
        }
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
                val paperId = "paper-" + System.currentTimeMillis().toString(36)
                val categoryId = paperId + "-cat"

                val paperDto = PaperDto(
                    id = paperId,
                    title = s.paperTitle.trim(),
                    description = s.paperDescription.trim(),
                    durationMinutes = s.durationMinutes,
                    negativeMarking = s.negativeMarking,
                    categories = listOf(
                        CategoryDto(
                            id = categoryId,
                            title = s.categoryName.trim().ifBlank { "Uncategorized" },
                            questions = s.questions
                        )
                    )
                )

                val file = McqFileDto(version = 1, papers = listOf(paperDto))
                com.mcqapp.data.io.Importer(repository.db()).import(file)
                Logger.i("IMPORTVM", "Imported ${s.questions.size} questions as '${s.paperTitle}'")
                _state.update { it.copy(importing = false, importDone = true) }
                onDone()
            } catch (e: Exception) {
                Logger.e("IMPORTVM", "Import failed", e)
                _state.update { it.copy(importing = false) }
            }
        }
    }
}
