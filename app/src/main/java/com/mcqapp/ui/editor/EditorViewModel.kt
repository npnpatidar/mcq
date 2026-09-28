package com.mcqapp.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    val loading: Boolean = true,
    val questionId: String = "",
    val paperId: String = "",
    val categoryId: String = "",
    val text: String = "",
    val image: String = "",
    val explanation: String = "",
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val tags: String = "",
    val options: List<OptionEditorState> = emptyList(),
    val categories: List<CategoryNode> = emptyList(),
    val isNew: Boolean = true
)

data class OptionEditorState(
    val id: String,
    val text: String,
    val image: String = "",
    val isCorrect: Boolean = false
)

class EditorViewModel(
    application: Application,
    private val questionId: String,
    private val paperId: String,
    private val categoryId: String
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(
        EditorUiState(
            questionId = questionId,
            paperId = paperId,
            categoryId = categoryId,
            isNew = questionId.isBlank()
        )
    )
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
        Logger.i("EDITORVM", "EditorViewModel created: questionId='$questionId', paperId='$paperId', categoryId='$categoryId'")
        viewModelScope.launch {
            try {
                val paper = if (paperId.isNotBlank()) repository.getPaper(paperId) else null
            val categories = paper?.categories ?: emptyList()
            if (questionId.isNotBlank()) {
                val question = repository.getQuestion(questionId)
                if (question != null) {
                    _state.value = _state.value.copy(
                        loading = false,
                        text = question.text,
                        image = question.image.orEmpty(),
                        explanation = question.explanation,
                        difficulty = question.difficulty,
                        tags = question.tags.joinToString(", "),
                        options = question.options.map { o ->
                            OptionEditorState(
                                id = o.id,
                                text = o.text,
                                image = o.image.orEmpty(),
                                isCorrect = o.id in question.correctOptionIds
                            )
                        },
                        categories = categories,
                        categoryId = question.categoryId
                    )
                    return@launch
                }
            }
            _state.value = _state.value.copy(
                loading = false,
                categories = categories,
                options = listOf(
                    OptionEditorState(id = "a", text = "", isCorrect = true),
                    OptionEditorState(id = "b", text = "")
                )
            )
            val readyMsg = if (_state.value.isNew) "new question" else "editing $questionId"
            Logger.i("EDITORVM", "Editor ready: $readyMsg, ${categories.size} categories available")
        } catch (e: Exception) {
            Logger.e("EDITORVM", "Failed to initialize editor", e)
        }
        }
    }

    fun updateText(value: String) = updateState { it.copy(text = value) }
    fun updateImage(value: String) = updateState { it.copy(image = value) }
    fun updateExplanation(value: String) = updateState { it.copy(explanation = value) }
    fun updateTags(value: String) = updateState { it.copy(tags = value) }
    fun updateDifficulty(value: Difficulty) = updateState { it.copy(difficulty = value) }
    fun updateCategory(value: String) = updateState { it.copy(categoryId = value) }

    fun updateOptionText(optionId: String, value: String) = updateState {
        it.copy(options = it.options.map { o -> if (o.id == optionId) o.copy(text = value) else o })
    }

    fun updateOptionImage(optionId: String, value: String) = updateState {
        it.copy(options = it.options.map { o -> if (o.id == optionId) o.copy(image = value) else o })
    }

    fun toggleCorrect(optionId: String) = updateState {
        it.copy(options = it.options.map { o ->
            if (o.id == optionId) o.copy(isCorrect = !o.isCorrect) else o
        })
    }

    fun addOption() = updateState {
        val nextLetter = ('a' + it.options.size).toString()
        it.copy(options = it.options + OptionEditorState(id = nextLetter, text = ""))
    }

    fun removeOption(optionId: String) = updateState {
        if (it.options.size <= 2) return@updateState it
        it.copy(options = it.options.filter { o -> o.id != optionId })
    }

    fun moveOptionUp(optionId: String) = updateState {
        val index = it.options.indexOfFirst { o -> o.id == optionId }
        if (index <= 0) return@updateState it
        val list = it.options.toMutableList()
        val item = list.removeAt(index)
        list.add(index - 1, item)
        it.copy(options = list)
    }

    fun moveOptionDown(optionId: String) = updateState {
        val index = it.options.indexOfFirst { o -> o.id == optionId }
        if (index < 0 || index >= it.options.size - 1) return@updateState it
        val list = it.options.toMutableList()
        val item = list.removeAt(index)
        list.add(index + 1, item)
        it.copy(options = list)
    }

    private fun updateState(block: (EditorUiState) -> EditorUiState) {
        _state.update(block)
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.text.isBlank() || s.options.any { it.text.isBlank() } || s.options.none { it.isCorrect }) return
        val question = Question(
            id = s.questionId.ifBlank { "q-" + System.currentTimeMillis().toString(36) },
            categoryId = s.categoryId,
            text = s.text.trim(),
            image = s.image.trim().ifBlank { null },
            options = s.options.map { QuestionOption(it.id, it.text.trim(), it.image.trim().ifBlank { null }) },
            correctOptionIds = s.options.filter { it.isCorrect }.map { it.id }.toSet(),
            explanation = s.explanation.trim(),
            difficulty = s.difficulty,
            tags = s.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        )
        viewModelScope.launch {
            repository.saveQuestion(question)
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteQuestion(questionId)
            onDone()
        }
    }
}
