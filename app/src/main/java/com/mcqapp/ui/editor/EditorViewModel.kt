package com.mcqapp.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.io.QuestionDto
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
    val explanationImage: String = "",
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val marks: String = "1",
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

    private var fromImportSession: Boolean = false

    /**
     * Signature of the last persisted state (load or save), normalized the
     * same way buildDto() normalizes. A Prev/Next move with a matching
     * signature writes nothing.
     */
    private var cleanSignature: String = ""

    private fun signatureOf(s: EditorUiState): String {
        fun normImage(v: String) = v.trim().ifBlank { "<null>" }
        val opts = s.options.map { o ->
            "${o.id}|${o.text.trim()}|${normImage(o.image)}|${o.isCorrect}"
        }
        val tags = s.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
            .joinToString(",")
        return listOf(
            s.text.trim(), normImage(s.image), s.explanation.trim(),
            normImage(s.explanationImage), s.difficulty.label,
            s.marks.trim(), tags, s.categoryId
        ).joinToString("\n") + "\n" + opts.joinToString("\n")
    }

    private fun snapshotClean() {
        cleanSignature = signatureOf(_state.value)
    }

    init {
        Logger.i("EDITORVM", "EditorViewModel created: questionId='$questionId', paperId='$paperId', categoryId='$categoryId'")
        val holderFlag = com.mcqapp.ui.importscreen.ImportDataHolder.editingFromImport
        val pendingEdit = com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion
        com.mcqapp.ui.importscreen.ImportDataHolder.editingFromImport = false
        com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion = null
        Logger.d("EDITORVM", "init: holderPresent=${pendingEdit != null}, holderId=${pendingEdit?.id}, " +
            "holderFlag=$holderFlag (both consumed atomically)")
        fromImportSession = holderFlag && pendingEdit != null && pendingEdit.id == questionId
        Logger.d("EDITORVM", "init: fromImportSession=$fromImportSession")
        if (fromImportSession && pendingEdit != null) {
            com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion = null
            _state.value = _state.value.copy(
                loading = false,
                text = pendingEdit.text,
                image = pendingEdit.image.orEmpty(),
                explanation = pendingEdit.explanation,
                explanationImage = pendingEdit.explanationImage.orEmpty(),
                difficulty = Difficulty.fromLabel(pendingEdit.difficulty),
                marks = formatMarks(pendingEdit.marks),
                tags = pendingEdit.tags.joinToString(", "),
                options = pendingEdit.options.map { o ->
                    OptionEditorState(
                        id = o.id,
                        text = o.text,
                        image = o.image.orEmpty(),
                        isCorrect = o.id in pendingEdit.correctOptionIds
                    )
                },
                categories = emptyList(),
                categoryId = ""
            )
            snapshotClean()
            Logger.i("EDITORVM", "Editor ready: editing from import screen (in-memory)")
        } else {
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
                        explanationImage = question.explanationImage.orEmpty(),
                        difficulty = question.difficulty,
                        marks = formatMarks(question.marks),
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
                    snapshotClean()
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
            snapshotClean()
            val readyMsg = if (_state.value.isNew) "new question" else "editing $questionId"
            Logger.i("EDITORVM", "Editor ready: $readyMsg, ${categories.size} categories available")
        } catch (e: Exception) {
            Logger.e("EDITORVM", "Failed to initialize editor", e)
        }
        }
        }
    }

    fun updateText(value: String) = updateState { it.copy(text = value) }
    fun updateImage(value: String) = updateState { it.copy(image = value) }
    fun updateExplanation(value: String) = updateState { it.copy(explanation = value) }
    fun updateExplanationImage(value: String) = updateState { it.copy(explanationImage = value) }
    fun updateTags(value: String) = updateState { it.copy(tags = value) }
    fun updateDifficulty(value: Difficulty) = updateState { it.copy(difficulty = value) }
    fun updateMarks(value: String) = updateState { it.copy(marks = value) }
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

    companion object {
        /** Display whole marks without a decimal point ("2", not "2.0"). */
        fun formatMarks(marks: Double): String = com.mcqapp.domain.Marks.format(marks)

        /** Invalid, non-finite or negative input falls back to 1 mark. */
        fun parseMarks(raw: String): Double = com.mcqapp.domain.Marks.parse(raw)
    }

    private fun buildDto(): QuestionDto? {
        val s = _state.value
        if (s.text.isBlank() || s.options.size < 2 || s.options.any { it.text.isBlank() }) return null
        val edited = s.options.map { QuestionOption(it.id, listOf(com.mcqapp.domain.ContentElement.TextElement(it.text.trim())), it.image.trim().ifBlank { null }) }
        return QuestionDto(
            id = s.questionId.ifBlank { "q-" + System.currentTimeMillis().toString(36) },
            text = s.text.trim(),
            elements = listOf(com.mcqapp.domain.ContentElement.TextElement(s.text.trim())),
            image = s.image.trim().ifBlank { null },
            options = edited.map { com.mcqapp.data.io.OptionDto(it.id, it.text, it.elements, it.image) },
            correctOptionIds = s.options.filter { it.isCorrect }.map { it.id }.toList(),
            explanation = s.explanation.trim(),
            explanationElements = listOf(com.mcqapp.domain.ContentElement.TextElement(s.explanation.trim())),
            explanationImage = s.explanationImage.trim().ifBlank { null },
            difficulty = s.difficulty.label,
            marks = parseMarks(s.marks),
            tags = s.tags.split(",").map { t -> t.trim() }.filter { t -> t.isNotBlank() }
        )
    }

    private fun domainFrom(dto: QuestionDto, categoryId: String): Question {
        return Question(
            id = dto.id,
            categoryId = categoryId,
            elements = dto.elements.ifEmpty { listOf(com.mcqapp.domain.ContentElement.TextElement(dto.text)) },
            image = dto.image,
            options = dto.options.map { QuestionOption(
                it.id,
                it.elements.ifEmpty { listOf(com.mcqapp.domain.ContentElement.TextElement(it.text)) },
                it.image
            ) },
            correctOptionIds = dto.correctOptionIds.toSet(),
            explanationElements = dto.explanationElements.ifEmpty {
                listOf(com.mcqapp.domain.ContentElement.TextElement(dto.explanation))
            },
            explanationImage = dto.explanationImage,
            difficulty = Difficulty.fromLabel(dto.difficulty),
            marks = dto.marks,
            tags = dto.tags
        )
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        Logger.d("EDITORVM", "save() called: questionId='${s.questionId}', textBlank=${s.text.isBlank()}, " +
            "options=${s.options.size}, blankOptions=${s.options.count { it.text.isBlank() }}, " +
            "correctCount=${s.options.count { it.isCorrect }}, editingFromImport=" +
            "${com.mcqapp.ui.importscreen.ImportDataHolder.editingFromImport}")
        val dto = buildDto()
        if (dto == null) {
            Logger.w("EDITORVM", "save() validation FAILED - not saving")
            return
        }
        Logger.d("EDITORVM", "save(): fromImportSession=$fromImportSession, " +
            "dtoId=${dto.id}, dtoText='${dto.text.take(60)}', dtoOptions=${dto.options.size}, " +
            "dtoCorrect=${dto.correctOptionIds}")
        // Signature of exactly what is being persisted (state may keep
        // changing under an async save; snapshotting later would mark
        // unpersisted keystrokes as clean).
        val savedSig = signatureOf(s)
        if (fromImportSession) {
            // Live write-through as well: if the user moved Prev/Next before
            // saving, the holder alone would be discarded on return (id
            // mismatch with the original mark), so update the list directly.
            EditorSession.importWriter?.invoke(dto)
            com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion = dto
            cleanSignature = savedSig
            Logger.i("EDITORVM", "Saved question ${dto.id} to import holder (in-memory)")
            onDone()
            return
        }
        val question = domainFrom(dto, s.categoryId)
        viewModelScope.launch {
            repository.saveQuestion(question)
            cleanSignature = savedSig
            onDone()
        }
    }

    /**
     * Persists current edits through the same channel as save() — but only if
     * anything actually changed — then loads the target question from the
     * [EditorSession] queue. Stays on this screen: no navigation, so Back
     * still returns to the originating list. [onMoved] reports whether a
     * write happened (for user feedback).
     */
    fun moveToQuestion(targetId: String, onMoved: (saved: Boolean) -> Unit = {}) {
        val dto = buildDto()
        if (dto == null) {
            Logger.w("EDITORVM", "moveToQuestion($targetId) blocked: current edits invalid")
            return
        }
        val targetIndex = EditorSession.ids.indexOf(targetId)
        if (targetIndex < 0) {
            Logger.w("EDITORVM", "moveToQuestion($targetId): not in session queue")
            return
        }
        val dirty = signatureOf(_state.value) != cleanSignature
        val savedSig = signatureOf(_state.value)
        Logger.i("EDITORVM", "moveToQuestion: ${if (dirty) "saving" else "no changes, skipping save for"} " +
            "${dto.id}, loading $targetId (fromImportSession=$fromImportSession)")
        if (fromImportSession) {
            if (dirty) {
                val writer = EditorSession.importWriter
                if (writer != null) {
                    writer(dto)
                } else {
                    com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion = dto
                }
                cleanSignature = savedSig
            }
            val next = EditorSession.importReader?.invoke(targetId)
            if (next == null) {
                Logger.w("EDITORVM", "moveToQuestion($targetId): not found via import reader")
                return
            }
            EditorSession.go(targetIndex)
            _state.update {
                it.copy(
                    questionId = next.id,
                    text = next.text,
                    image = next.image.orEmpty(),
                    explanation = next.explanation,
                    explanationImage = next.explanationImage.orEmpty(),
                    difficulty = Difficulty.fromLabel(next.difficulty),
                    marks = formatMarks(next.marks),
                    tags = next.tags.joinToString(", "),
                    options = next.options.map { o ->
                        OptionEditorState(
                            id = o.id,
                            text = o.text,
                            image = o.image.orEmpty(),
                            isCorrect = o.id in next.correctOptionIds
                        )
                    },
                    categoryId = ""
                )
            }
            snapshotClean()
            Logger.i("EDITORVM", "moveToQuestion: now editing $targetId (in-memory)")
            onMoved(dirty)
        } else {
            viewModelScope.launch {
                try {
                    if (dirty) {
                        val categoryId = _state.value.categoryId
                        repository.saveQuestion(domainFrom(dto, categoryId))
                        cleanSignature = savedSig
                    }
                    val next = repository.getQuestion(targetId)
                    if (next == null) {
                        Logger.w("EDITORVM", "moveToQuestion($targetId): not found in DB")
                        return@launch
                    }
                    EditorSession.go(targetIndex)
                    _state.update {
                        it.copy(
                            questionId = next.id,
                            text = next.text,
                            image = next.image.orEmpty(),
                            explanation = next.explanation,
                            explanationImage = next.explanationImage.orEmpty(),
                            difficulty = next.difficulty,
                            marks = formatMarks(next.marks),
                            tags = next.tags.joinToString(", "),
                            options = next.options.map { o ->
                                OptionEditorState(
                                    id = o.id,
                                    text = o.text,
                                    image = o.image.orEmpty(),
                                    isCorrect = o.id in next.correctOptionIds
                                )
                            },
                            categoryId = next.categoryId
                        )
                    }
                    snapshotClean()
                    Logger.i("EDITORVM", "moveToQuestion: now editing $targetId (DB)")
                    onMoved(dirty)
                } catch (e: Exception) {
                    Logger.e("EDITORVM", "moveToQuestion($targetId) failed", e)
                }
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteQuestion(questionId)
            onDone()
        }
    }
}
