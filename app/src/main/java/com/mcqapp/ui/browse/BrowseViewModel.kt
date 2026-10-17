package com.mcqapp.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mcqapp.McqApplication
import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.repository.BrowseListFilter
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.ui.editor.EditorSession
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One category's reorder context: its questions in order (id + passageId are
 * all [com.mcqapp.domain.PassageReorder] reads) plus the first and last id,
 * which mark the section-edge rows that carry the category-move buttons.
 */
data class CategoryContext(
    val refs: List<QuestionRef> = emptyList(),
    val firstId: String? = null,
    val lastId: String? = null
)

data class BrowseUiState(
    val paper: Paper? = null,
    /** Loaded on demand per visible category; drives reorder math only. */
    val categoryContexts: Map<String, CategoryContext> = emptyMap(),
    /** passageId -> its first member's id in paper order (null: not loaded). */
    val passageFirstIds: Map<String, String?> = emptyMap(),
    /** Full passages for the visible group headers, loaded on demand. */
    val passages: Map<String, com.mcqapp.domain.Passage> = emptyMap(),
    /**
     * Bumped whenever the on-demand loads above are dropped. Rows key their
     * load effects on it so a dropped context is re-fetched; without it a
     * cleared map stayed empty for already-composed rows and the section-move
     * buttons disappeared after their own move.
     */
    val contextsVersion: Long = 0L
)

/** The list's live filter, read by the screen for its move-enable contract. */
data class BrowseFilterState(
    val query: String = "",
    val filter: BrowseListFilter = BrowseListFilter.ALL,
    val categoryFilterId: String? = null
)

private data class BrowseFilterRequest(
    val state: BrowseFilterState = BrowseFilterState(),
    val categoriesVersion: Long = 0L
)

/**
 * Browse's list pages through Room: opening a 250k-question paper streams a
 * window instead of materialising every ref. Filters are SQL predicates in the
 * paging query (the same observable order as before). The only whole-category
 * reads left are the on-demand reorder contexts and passage-header lookups,
 * both bounded by one category/passage.
 */
@kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowseViewModel(
    application: Application,
    private val paperId: String
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(BrowseUiState())
    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    private val filters = MutableStateFlow(BrowseFilterRequest())
    val filterState: StateFlow<BrowseFilterState> =
        filters.map { it.state }.stateIn(viewModelScope, SharingStarted.Eagerly, BrowseFilterState())

    /** In-flight loads, guarding against a row recomposing into a duplicate read. */
    private val contextsLoading = mutableSetOf<String>()
    private val firstIdsLoading = mutableSetOf<String>()
    private val passagesByIdLoading = mutableSetOf<String>()

    init {
        Logger.i("BROWSEVM", "BrowseViewModel created: paperId=$paperId")
        viewModelScope.launch {
            repository.observePapers().collect { papers ->
                papers.firstOrNull { it.id == paperId }?.let { paper ->
                    _state.update { it.copy(paper = paper) }
                    bumpCategories()
                }
            }
        }
        // Drop stale reorder contexts / passage headers on any question write.
        viewModelScope.launch {
            repository.observeBrowseInvalidation().collect { invalidateContexts() }
        }
    }

    private fun bumpCategories() {
        filters.update { it.copy(categoriesVersion = it.categoriesVersion + 1) }
    }

    val pagingData: Flow<PagingData<QuestionRef>> = filters
        .flatMapLatest { request ->
            val categoryIds = repository.browseCategoryIds(paperId)
            if (categoryIds.isEmpty()) {
                flowOf(PagingData.empty<QuestionRef>())
            } else {
                val scope = scopeFor(request.state, categoryIds)
                Pager(
                    PagingConfig(
                        pageSize = BROWSE_PAGE,
                        initialLoadSize = BROWSE_PAGE * 2,
                        prefetchDistance = BROWSE_PAGE / 2,
                        enablePlaceholders = false
                    )
                ) {
                    repository.browseRefsSource(scope)
                }.flow
            }
        }
        .cachedIn(viewModelScope)

    private suspend fun scopeFor(
        state: BrowseFilterState,
        categoryIds: List<String>
    ): McqRepository.BrowseScope {
        val query = state.query
        val passageIds = if (query.isBlank()) listOf("")
        else repository.browseMatchingPassageIds(paperId, query)
        return McqRepository.BrowseScope.of(
            categoryIds = categoryIds,
            query = query,
            matchingPassageIds = passageIds,
            filter = state.filter,
            categoryFilterIds = subtreeIds(state.categoryFilterId)
        )
    }

    private suspend fun currentScope(): McqRepository.BrowseScope =
        scopeFor(filters.value.state, repository.browseCategoryIds(paperId))

    fun updateQuery(query: String) {
        filters.update { it.copy(state = it.state.copy(query = query)) }
    }

    fun updateFilter(filter: BrowseListFilter) {
        filters.update { it.copy(state = it.state.copy(filter = filter)) }
    }

    fun updateCategoryFilter(categoryId: String?) {
        filters.update { it.copy(state = it.state.copy(categoryFilterId = categoryId)) }
    }

    private fun subtreeIds(rootId: String?): Set<String>? {
        if (rootId == null) return null
        val flat = flattenCategories(_state.value.paper?.categories.orEmpty())
        return com.mcqapp.ui.browse.subtreeIdsOf(flat, rootId)
    }

    /** Loads a visible category's reorder context once. */
    fun ensureCategoryContext(categoryId: String) {
        if (_state.value.categoryContexts.containsKey(categoryId) || !contextsLoading.add(categoryId)) return
        viewModelScope.launch {
            try {
                val refs = repository.browseCategoryRefs(categoryId)
                val context = CategoryContext(
                    refs = refs,
                    firstId = refs.firstOrNull()?.id,
                    lastId = refs.lastOrNull()?.id
                )
                _state.update { it.copy(categoryContexts = it.categoryContexts + (categoryId to context)) }
            } catch (e: Exception) {
                Logger.e("BROWSEVM", "reorder context for $categoryId failed", e)
            }
        }
    }

    /** Loads a visible passage header's first-member id once. */
    fun ensurePassageFirstId(passageId: String) {
        if (_state.value.passageFirstIds.containsKey(passageId) || !firstIdsLoading.add(passageId)) return
        viewModelScope.launch {
            try {
                val first = repository.browsePassageFirstRefId(passageId)
                _state.update { it.copy(passageFirstIds = it.passageFirstIds + (passageId to first)) }
            } catch (e: Exception) {
                Logger.e("BROWSEVM", "passage first id for $passageId failed", e)
            }
        }
    }

    /** Loads a visible passage's body once, for its group header/label. */
    fun ensurePassage(passageId: String) {
        if (_state.value.passages.containsKey(passageId) || !passagesByIdLoading.add(passageId)) return
        viewModelScope.launch {
            try {
                val passage = repository.getPassagesByIds(listOf(passageId)).firstOrNull() ?: return@launch
                _state.update { it.copy(passages = it.passages + (passageId to passage)) }
            } catch (e: Exception) {
                Logger.e("BROWSEVM", "passage $passageId load failed", e)
            }
        }
    }

    private fun invalidateContexts() {
        _state.update {
            it.copy(
                categoryContexts = emptyMap(),
                passageFirstIds = emptyMap(),
                passages = emptyMap(),
                contextsVersion = it.contextsVersion + 1
            )
        }
        contextsLoading.clear()
        firstIdsLoading.clear()
        passagesByIdLoading.clear()
    }

    /**
     * Resolves the filtered list as bare ids, so callers can seed the editor's
     * Prev/Next queue or locate a deep-link target with exactly the order the
     * user is looking at. Ids only: a 250k-question list costs a few MB, not
     * the full ref set.
     */
    /**
     * The anchor's index in the current filter, for the deep-link scroll.
     * Returns -1 when the question is not in scope, matching the old
     * `indexOf` result. The cursor's count-based index cannot tell "first"
     * from "absent", so the id at that index is checked.
     */
    fun browseIndexOf(anchorId: String, onResult: suspend (Int) -> Unit) {
        viewModelScope.launch {
            val scope = currentScope()
            val index = repository.browseFilteredIndexOf(anchorId, scope)
            val at = repository.browseFilteredIdAt(index, scope)
            onResult(if (at == anchorId) index else -1)
        }
    }

    /**
     * Seeds the editor's Prev/Next with a cursor over the current filter,
     * without materialising the filtered list: a 250k-question paper never
     * holds every id just to support two buttons.
     */
    fun openEditor(anchorId: String, onReady: suspend (EditorSession.Cursor) -> Unit) {
        viewModelScope.launch {
            val scope = currentScope()
            val size = repository.browseFilteredCount(scope)
            if (size == 0) return@launch
            val index = repository.browseFilteredIndexOf(anchorId, scope).coerceIn(0, size - 1)
            onReady(
                EditorSession.Cursor(size, index) { offset ->
                    repository.browseFilteredIdAt(offset, scope)
                }
            )
        }
    }

    /** The row's full content, fetched when the row composes. */
    suspend fun questionContent(questionId: String): Question? =
        repository.getQuestion(questionId)

    fun deleteQuestion(questionId: String) {
        viewModelScope.launch {
            repository.deleteQuestion(questionId)
            invalidateContexts()
            Logger.i("BROWSEVM", "Deleted question $questionId")
        }
    }

    fun duplicateQuestion(questionId: String) {
        viewModelScope.launch {
            repository.duplicateQuestion(questionId)
            invalidateContexts()
        }
    }

    fun deleteQuestions(questionIds: Set<String>) {
        viewModelScope.launch {
            repository.deleteQuestions(questionIds)
            invalidateContexts()
            Logger.i("BROWSEVM", "Deleted ${questionIds.size} questions")
        }
    }

    fun moveQuestions(questionIds: Set<String>, targetCategoryId: String) {
        viewModelScope.launch {
            repository.moveQuestionsToCategory(questionIds, targetCategoryId)
            invalidateContexts()
            Logger.i("BROWSEVM", "Moved ${questionIds.size} questions to $targetCategoryId")
        }
    }

    fun swapQuestions(firstId: String, secondId: String) {
        viewModelScope.launch {
            repository.swapQuestionOrder(firstId, secondId)
            invalidateContexts()
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
            invalidateContexts()
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
            invalidateContexts()
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
        fun walk(nodes: List<CategoryNode>) {
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
            invalidateContexts()
            Logger.i("BROWSEVM", "Bulk-edited ${questionIds.size} questions")
        }
    }

    companion object {
        /** Window size: ~a screenful and a half, so flings stay ahead of IO. */
        const val BROWSE_PAGE = 60

        private fun flattenCategories(
            nodes: List<CategoryNode>,
            depth: Int = 0
        ): List<Pair<CategoryNode, Int>> = buildList {
            for (node in nodes) {
                add(node to depth)
                addAll(flattenCategories(node.children, depth + 1))
            }
        }
    }
}
