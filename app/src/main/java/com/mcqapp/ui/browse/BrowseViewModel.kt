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
    /**
     * The paper's questions as refs: ids, categories, passage membership and
     * preview stems. Question content is fetched for the rows on screen, so
     * a 169k-question paper lists without loading its whole content.
     */
    val refs: List<com.mcqapp.data.local.QuestionRef> = emptyList()
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
            // Observed, not fetched once: a category moved in the drawer or
            // inline rewrites the tree this screen's sibling math and section
            // labels read — a stale snapshot left the second move computed
            // against the pre-move order.
            repository.observePapers().collect { papers ->
                papers.firstOrNull { it.id == paperId }?.let { paper ->
                    _state.value = _state.value.copy(paper = paper)
                }
            }
        }
        viewModelScope.launch {
            try {
                repository.observeQuestionRefsForPaper(paperId).collect { refs ->
                    val ids = refs.map { it.id }
                    Logger.d("BROWSEVM", "Observed ${refs.size} question refs for paper $paperId: " +
                        "order=[${ids.take(5).joinToString(",")}${if (ids.size > 5) ",... +${ids.size - 5} more" else ""}]")
                    if (firstLoad) {
                        firstLoad = false
                        val runtime = Runtime.getRuntime()
                        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
                        val maxMb = runtime.maxMemory() / 1048576
                        Logger.i("BROWSEVM", "first load of ${refs.size} refs took " +
                            "${android.os.SystemClock.elapsedRealtime() - created}ms, " +
                            "heap ${usedMb}MB/${maxMb}MB")
                    }
                    _state.update { it.copy(loading = false, refs = refs) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Navigating away cancels the collector; that is the flow of
                // things, not a failure worth an error line.
                throw e
            } catch (e: Exception) {
                Logger.e("BROWSEVM", "Observation of questions for paper $paperId FAILED", e)
            }
        }
    }

    /** The row's full content, fetched when the row composes. */
    suspend fun questionContent(questionId: String): Question? =
        repository.getQuestion(questionId)

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

    /**
     * Applies a computed category order (a block-aware move from
     * PassageReorder: a row jump over a passage group, or a whole group
     * swapping with its neighbour). The id list is the category's complete
     * new order; the store refuses a mismatched set.
     */
    fun renumberCategory(categoryId: String, orderedIds: List<String>) {
        viewModelScope.launch {
            if (!repository.renumberQuestions(categoryId, orderedIds)) {
                Logger.w("BROWSEVM", "renumberCategory($categoryId) refused: stale order?")
            }
        }
    }

    /**
     * Moves a whole category among its siblings — the double-arrow buttons
     * on a section's first and last row. Same semantics as the drawer's
     * category reorder.
     */
    fun moveCategory(categoryId: String, delta: Int) {
        viewModelScope.launch {
            if (!repository.moveCategory(categoryId, delta)) {
                Logger.i("BROWSEVM", "moveCategory($categoryId, $delta) refused: no sibling that way")
            }
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
                // Sessions and the list hold refs; content comes back by id.
                // Contained in questionIds, so a stale ref still exports the
                // question the user selected.
                val selected = repository.getQuestionsByIds(questionIds.toList())
                    .sortedBy { q -> questionIds.indexOf(q.id) }
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
