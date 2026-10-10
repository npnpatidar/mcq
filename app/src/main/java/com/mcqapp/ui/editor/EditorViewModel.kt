package com.mcqapp.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.textContent
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Block kinds the editor can append to an element list. */
enum class EditorBlockType {
    TEXT,
    IMAGE,
    TABLE,
    MATH;

    fun defaultElement(): ContentElement = when (this) {
        TEXT -> ContentElement.TextElement("")
        IMAGE -> ContentElement.ImageElement("")
        TABLE -> ContentElement.TableElement(listOf(listOf("")))
        MATH -> ContentElement.MathElement("<math><mi></mi></math>")
    }
}

/**
 * Whether a block counts as content for validation. Whitespace-only text
 * and empty tables do not; any image src or formula does (even a
 * placeholder one — clearing the field is what removes it).
 */
internal fun ContentElement.isSignificant(): Boolean = when (this) {
    is ContentElement.TextElement -> text.isNotBlank()
    is ContentElement.ImageElement -> src.isNotBlank()
    is ContentElement.TableElement -> rows.any { row -> row.any { it.isNotBlank() } }
    is ContentElement.MathElement -> mathml.isNotBlank()
}

data class EditorUiState(
    val loading: Boolean = true,
    val questionId: String = "",
    val paperId: String = "",
    val categoryId: String = "",
    val elements: List<ContentElement> = emptyList(),
    val image: String = "",
    val explanationElements: List<ContentElement> = emptyList(),
    val explanationImage: String = "",
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val marks: String = "1",
    val tags: String = "",
    val options: List<OptionEditorState> = emptyList(),
    val categories: List<CategoryNode> = emptyList(),
    /** Passages of this paper for the assign picker; empty when none exist. */
    val passages: List<com.mcqapp.domain.Passage> = emptyList(),
    val isNew: Boolean = true,
    /** The passage this question belongs to, editable in the picker. */
    val passageId: String? = null
) {
    /** Backward-compat text for labels and previews. */
    val text: String get() = elements.textContent
    val explanation: String get() = explanationElements.textContent

    /**
     * A question saves when it holds at least one significant block of any
     * kind and every one of at least two options holds content. Plain text
     * is no longer required: an image-only or formula-only question is
     * valid.
     *
     * This is a property of the state object so screens derive it from the
     * *collected* state. Reading it through the ViewModel instead
     * (`viewModel.canProceed()`) left the calling composable with no state
     * read of its own, so the value was computed once while the form was
     * still loading and never again — the Save button never enabled.
     */
    val canSave: Boolean
        get() = elements.any { it.isSignificant() } &&
            options.size >= 2 &&
            options.all { o -> o.elements.any { it.isSignificant() } }
}

data class OptionEditorState(
    val id: String,
    val elements: List<ContentElement> = emptyList(),
    val image: String = "",
    val isCorrect: Boolean = false
) {
    val text: String get() = elements.textContent
}

class EditorViewModel(
    application: Application,
    private val questionId: String,
    private val paperId: String,
    private val categoryId: String
) : AndroidViewModel(application) {

    // Resolved lazily: import-session editing never touches the database,
    // so tests can construct this with a plain Application.
    private val repository: McqRepository by lazy { (application as McqApplication).repository }

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
        fun normElements(elements: List<ContentElement>) = elements.joinToString("|") { el ->
            when (el) {
                is ContentElement.TextElement -> "t:${el.text.trim()}"
                is ContentElement.ImageElement -> "i:${el.src.trim()}"
                is ContentElement.TableElement ->
                    "b:" + el.rows.joinToString(";") { row -> row.joinToString(",") { it.trim() } }
                is ContentElement.MathElement -> "m:${el.mathml.trim()}"
            }
        }
        val opts = s.options.map { o ->
            "${o.id}|${normElements(o.elements)}|${normImage(o.image)}|${o.isCorrect}"
        }
        val tags = s.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
            .joinToString(",")
        return listOf(
            normElements(s.elements), normImage(s.image), normElements(s.explanationElements),
            normImage(s.explanationImage), s.difficulty.label,
            s.marks.trim(), tags, s.categoryId, s.passageId.orEmpty()
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
                elements = pendingEdit.elements,
                image = pendingEdit.image.orEmpty(),
                explanationElements = pendingEdit.explanationElements,
                explanationImage = pendingEdit.explanationImage.orEmpty(),
                difficulty = Difficulty.fromLabel(pendingEdit.difficulty),
                marks = formatMarks(pendingEdit.marks),
                tags = pendingEdit.tags.joinToString(", "),
                options = pendingEdit.options.map { o ->
                    OptionEditorState(
                        id = o.id,
                        elements = o.elements,
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
                        elements = question.elements,
                        image = question.image.orEmpty(),
                        explanationElements = question.explanationElements,
                        explanationImage = question.explanationImage.orEmpty(),
                        difficulty = question.difficulty,
                        marks = formatMarks(question.marks),
                        tags = question.tags.joinToString(", "),
                        options = question.options.map { o ->
                            OptionEditorState(
                                id = o.id,
                                elements = o.elements,
                                image = o.image.orEmpty(),
                                isCorrect = o.id in question.correctOptionIds
                            )
                        },
                        categories = categories,
                        categoryId = question.categoryId,
                        passages = repository.getPassagesForPaper(paperId),
                        passageId = question.passageId
                    )
                    snapshotClean()
                    return@launch
                }
            }
            _state.value = _state.value.copy(
                loading = false,
                categories = categories,
                passages = if (paperId.isNotBlank()) repository.getPassagesForPaper(paperId) else emptyList(),
                elements = listOf(ContentElement.TextElement("")),
                options = listOf(
                    OptionEditorState(id = "a", elements = listOf(ContentElement.TextElement("")), isCorrect = true),
                    OptionEditorState(id = "b", elements = listOf(ContentElement.TextElement("")))
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

    /** The form's save/readiness rule; see [EditorUiState.canSave]. */
    fun canProceed(): Boolean = _state.value.canSave

    fun updateImage(value: String) = updateState { it.copy(image = value) }
    fun updateExplanationImage(value: String) = updateState { it.copy(explanationImage = value) }
    fun updateTags(value: String) = updateState { it.copy(tags = value) }
    fun updateDifficulty(value: Difficulty) = updateState { it.copy(difficulty = value) }
    fun updateMarks(value: String) = updateState { it.copy(marks = value) }
    fun updateCategory(value: String) = updateState { it.copy(categoryId = value) }

    /** Assigns this question to a passage (null clears membership). */
    fun updatePassage(value: String?) = updateState { it.copy(passageId = value) }

    // ---- question-body blocks ----

    fun addBlock(type: EditorBlockType) = updateState {
        it.copy(elements = it.elements + type.defaultElement())
    }

    fun updateBlock(index: Int, element: ContentElement) = updateState {
        if (index !in it.elements.indices) return@updateState it
        it.copy(elements = it.elements.toMutableList().also { list -> list[index] = element })
    }

    fun removeBlock(index: Int) = updateState {
        if (index !in it.elements.indices) return@updateState it
        it.copy(elements = it.elements.toMutableList().also { list -> list.removeAt(index) })
    }

    fun moveBlockUp(index: Int) = updateState {
        if (index <= 0 || index >= it.elements.size) return@updateState it
        it.copy(elements = it.elements.toMutableList().also { list ->
            val item = list.removeAt(index)
            list.add(index - 1, item)
        })
    }

    fun moveBlockDown(index: Int) = updateState {
        if (index < 0 || index >= it.elements.size - 1) return@updateState it
        it.copy(elements = it.elements.toMutableList().also { list ->
            val item = list.removeAt(index)
            list.add(index + 1, item)
        })
    }

    // ---- table cells (index addresses a TableElement in the body) ----

    private fun updateTable(index: Int, transform: (ContentElement.TableElement) -> ContentElement.TableElement) =
        updateState {
            val current = it.elements.getOrNull(index) as? ContentElement.TableElement
                ?: return@updateState it
            it.copy(elements = it.elements.toMutableList().also { list -> list[index] = transform(current) })
        }

    fun updateTableCell(index: Int, row: Int, col: Int, value: String) = updateTable(index) { table ->
        if (row !in table.rows.indices) return@updateTable table
        val newRows = table.rows.mapIndexed { r, cells ->
            if (r != row || col !in cells.indices) cells
            else cells.toMutableList().also { it[col] = value }
        }
        table.copy(rows = newRows)
    }

    fun addTableRow(index: Int) = updateTable(index) { table ->
        val width = table.rows.firstOrNull()?.size ?: 1
        table.copy(rows = table.rows + listOf(List(width) { "" }))
    }

    fun removeTableRow(index: Int, row: Int) = updateState {
        val table = it.elements.getOrNull(index) as? ContentElement.TableElement
            ?: return@updateState it
        if (table.rows.size <= 1 || row !in table.rows.indices) return@updateState it
        val rows = table.rows.toMutableList().also { rows -> rows.removeAt(row) }
        it.copy(elements = it.elements.toMutableList().also { list -> list[index] = table.copy(rows = rows) })
    }

    fun addTableColumn(index: Int) = updateTable(index) { table ->
        table.copy(rows = table.rows.map { it + "" })
    }

    fun removeTableColumn(index: Int, col: Int) = updateState {
        val table = it.elements.getOrNull(index) as? ContentElement.TableElement
            ?: return@updateState it
        if ((table.rows.firstOrNull()?.size ?: 0) <= 1) return@updateState it
        if (table.rows.any { col !in it.indices }) return@updateState it
        val rows = table.rows.map { row -> row.toMutableList().also { it.removeAt(col) } }
        it.copy(elements = it.elements.toMutableList().also { list -> list[index] = table.copy(rows = rows) })
    }

    // ---- options ----

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
        it.copy(options = it.options + OptionEditorState(
            id = nextLetter,
            elements = listOf(ContentElement.TextElement(""))
        ))
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

    private fun updateOptionElements(
        optionId: String,
        transform: (List<ContentElement>) -> List<ContentElement>
    ) = updateState {
        it.copy(options = it.options.map { o ->
            if (o.id == optionId) o.copy(elements = transform(o.elements)) else o
        })
    }

    fun addOptionBlock(optionId: String, type: EditorBlockType) =
        updateOptionElements(optionId) { it + type.defaultElement() }

    fun updateOptionBlock(optionId: String, index: Int, element: ContentElement) =
        updateOptionElements(optionId) { elements ->
            if (index !in elements.indices) elements
            else elements.toMutableList().also { list -> list[index] = element }
        }

    fun removeOptionBlock(optionId: String, index: Int) =
        updateOptionElements(optionId) { elements ->
            if (index !in elements.indices) elements
            else elements.toMutableList().also { list -> list.removeAt(index) }
        }

    fun moveOptionBlockUp(optionId: String, index: Int) =
        updateOptionElements(optionId) { elements ->
            if (index <= 0 || index >= elements.size) elements
            else elements.toMutableList().also { list ->
                val item = list.removeAt(index)
                list.add(index - 1, item)
            }
        }

    fun moveOptionBlockDown(optionId: String, index: Int) =
        updateOptionElements(optionId) { elements ->
            if (index < 0 || index >= elements.size - 1) elements
            else elements.toMutableList().also { list ->
                val item = list.removeAt(index)
                list.add(index + 1, item)
            }
        }

    // ---- explanation blocks ----

    fun addExplanationBlock(type: EditorBlockType) = updateState {
        it.copy(explanationElements = it.explanationElements + type.defaultElement())
    }

    fun updateExplanationBlock(index: Int, element: ContentElement) = updateState {
        if (index !in it.explanationElements.indices) return@updateState it
        it.copy(explanationElements = it.explanationElements.toMutableList().also { list -> list[index] = element })
    }

    fun removeExplanationBlock(index: Int) = updateState {
        if (index !in it.explanationElements.indices) return@updateState it
        it.copy(explanationElements = it.explanationElements.toMutableList().also { list -> list.removeAt(index) })
    }

    fun moveExplanationBlockUp(index: Int) = updateState {
        if (index <= 0 || index >= it.explanationElements.size) return@updateState it
        it.copy(explanationElements = it.explanationElements.toMutableList().also { list ->
            val item = list.removeAt(index)
            list.add(index - 1, item)
        })
    }

    fun moveExplanationBlockDown(index: Int) = updateState {
        if (index < 0 || index >= it.explanationElements.size - 1) return@updateState it
        it.copy(explanationElements = it.explanationElements.toMutableList().also { list ->
            val item = list.removeAt(index)
            list.add(index + 1, item)
        })
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
        if (!canProceed()) return null
        val edited = s.options.map { QuestionOption(it.id, it.elements, it.image.trim().ifBlank { null }) }
        return QuestionDto(
            id = s.questionId.ifBlank { "q-" + System.currentTimeMillis().toString(36) },
            text = s.elements.textContent,
            elements = s.elements,
            image = s.image.trim().ifBlank { null },
            options = edited.map { com.mcqapp.data.io.OptionDto(it.id, it.text, it.elements, it.image) },
            correctOptionIds = s.options.filter { it.isCorrect }.map { it.id }.toList(),
            explanation = s.explanationElements.textContent,
            explanationElements = s.explanationElements,
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
            elements = dto.elements.ifEmpty { listOf(ContentElement.TextElement(dto.text)) },
            image = dto.image,
            options = dto.options.map { QuestionOption(
                it.id,
                it.elements.ifEmpty { listOf(ContentElement.TextElement(it.text)) },
                it.image
            ) },
            correctOptionIds = dto.correctOptionIds.toSet(),
            explanationElements = dto.explanationElements.ifEmpty {
                listOf(ContentElement.TextElement(dto.explanation))
            },
            explanationImage = dto.explanationImage,
            difficulty = Difficulty.fromLabel(dto.difficulty),
            marks = dto.marks,
            tags = dto.tags,
            passageId = _state.value.passageId?.takeIf { it.isNotBlank() }
        )
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        Logger.d("EDITORVM", "save() called: questionId='${s.questionId}', valid=${canProceed()}, " +
            "options=${s.options.size}, " +
            "correctCount=${s.options.count { it.isCorrect }}, editingFromImport=" +
            "${com.mcqapp.ui.importscreen.ImportDataHolder.editingFromImport}")
        val dto = buildDto()
        if (dto == null) {
            Logger.w("EDITORVM", "save() validation FAILED - not saving")
            return
        }
        Logger.d("EDITORVM", "save(): fromImportSession=$fromImportSession, " +
            "dtoId=${dto.id}, dtoTextLength=${dto.text.length}, dtoOptions=${dto.options.size}, " +
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
                    elements = next.elements,
                    image = next.image.orEmpty(),
                    explanationElements = next.explanationElements,
                    explanationImage = next.explanationImage.orEmpty(),
                    difficulty = Difficulty.fromLabel(next.difficulty),
                    marks = formatMarks(next.marks),
                    tags = next.tags.joinToString(", "),
                    options = next.options.map { o ->
                        OptionEditorState(
                            id = o.id,
                            elements = o.elements,
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
                            elements = next.elements,
                            image = next.image.orEmpty(),
                            explanationElements = next.explanationElements,
                            explanationImage = next.explanationImage.orEmpty(),
                            difficulty = next.difficulty,
                            marks = formatMarks(next.marks),
                            tags = next.tags.joinToString(", "),
                            options = next.options.map { o ->
                                OptionEditorState(
                                    id = o.id,
                                    elements = o.elements,
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
