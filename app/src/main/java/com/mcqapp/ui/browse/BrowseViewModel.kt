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
    /**
     * Reorder refs for the categories currently around the viewport; drives
     * reorder math only. Scoped to the window rather than kept per category
     * forever: Browse used to hold every category it had ever scrolled past, so
     * a 1000-category paper ended up resident after one pass down the list.
     */
    val categoryContexts: Map<String, CategoryContext> = emptyMap(),
    /** passageId -> its first member's id in paper order (null: not loaded). */
    val passageFirstIds: Map<String, String?> = emptyMap(),
    /** Full passages for the visible group headers, loaded on demand. */
    val passages: Map<String, com.mcqapp.domain.Passage> = emptyMap(),
    /**
     * Full cards for the rows around the viewport. Read ahead of the row being
     * visible so a card does not grow after it has been laid out; scoped to the
     * same window as the maps above.
     */
    val contentById: Map<String, Question> = emptyMap(),
    /**
     * Bumped whenever the on-demand loads above are dropped, so the window
     * loader re-reads them; without it a cleared map stayed empty for rows the
     * list already held and the section-move buttons disappeared after their
     * own move.
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

    /** In-flight loads, guarding against a window update re-reading the same row. */
    private val contextsLoading = mutableSetOf<String>()
    private val firstIdsLoading = mutableSetOf<String>()
    private val passagesByIdLoading = mutableSetOf<String>()
    private val contentLoading = mutableSetOf<String>()

    /**
     * Content hashes of the loaded cards, so a card whose question was edited
     * elsewhere is re-read when the list's ref reports a new hash. Held outside
     * [BrowseUiState] because it is bookkeeping, not something a row renders.
     */
    private val contentHashes = mutableMapOf<String, String>()

    /** The rows the last window covered; loaded cards outside it are dropped. */
    private var windowIds: Set<String> = emptySet()

    /** The same for passages and categories, so a late load cannot resurrect
     *  rows (or evict rows) from a window that has already scrolled past. */
    private var windowPassageIds: Set<String> = emptySet()
    private var windowCategoryIds: Set<String> = emptySet()

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
            Pager(
                PagingConfig(
                    pageSize = BROWSE_PAGE,
                    initialLoadSize = BROWSE_PAGE * 2,
                    prefetchDistance = BROWSE_PAGE / 2,
                    // On, so the list knows the paper's whole length: a
                    // deep link has to be able to jump to a question that
                    // no scroll has reached yet, and without placeholders
                    // the list holds only the pages it has loaded, so that
                    // index does not exist to jump to. It also lets a fling
                    // reach the end of a long paper instead of stopping at
                    // whatever page the scroll has pulled so far.
                    enablePlaceholders = true
                )
            ) {
                repository.browseRefsSource(scopeFor(request.state))
            }.flow
        }
        .cachedIn(viewModelScope)

    private fun scopeFor(state: BrowseFilterState): McqRepository.BrowseScope =
        McqRepository.BrowseScope.of(
            paperId = paperId,
            query = state.query,
            filter = state.filter,
            categoryFilterIds = subtreeIds(state.categoryFilterId)
        )

    private fun currentScope(): McqRepository.BrowseScope = scopeFor(filters.value.state)

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

    /**
     * The rows around the viewport, handed in as the list scrolls. Everything a
     * row needs is read here, in one pass, *ahead* of the row becoming visible:
     * the card's content, its passage (header or membership label), the
     * passage's first member, and the visible categories' reorder refs. Reading
     * these from the row composable instead let each one land after its row had
     * already been laid out — the card grew under the finger and a passage
     * header appeared late and pushed the rows below it down — and left every
     * category ever scrolled past resident in the state.
     */
    fun onVisibleRows(window: List<QuestionRef>) {
        if (window.isEmpty()) return
        windowIds = window.mapTo(mutableSetOf()) { it.id }
        windowPassageIds = window.mapNotNullTo(mutableSetOf()) { it.passageId }
        windowCategoryIds = window.mapTo(mutableSetOf()) { it.categoryId }
        loadContent(window)
        loadPassages(windowPassageIds.toList())
        loadPassageFirstIds(windowPassageIds.toList())
        loadCategoryContexts(windowCategoryIds.toList())
    }

    /**
     * One row asking for what it renders, because it is on screen without it.
     *
     * The window above is the path; this is the exception it cannot cover. A
     * jump can land between two windows whose row *sets* are equal — the paged
     * refs come back in a different order, or a placeholder is still null, so
     * the window reads the same and emits nothing — and a row whose card was
     * pruned before that landing is then left asking nobody. Reading the single
     * row is enough and is safe to do here: it deliberately does not rewrite the
     * window, so a late answer for one row cannot re-prune the rows around it.
     * The caches are presence-checked, so a row that is already complete costs
     * one lookup and no query.
     */
    fun requestRow(ref: QuestionRef) {
        // The row is on screen, so the window should cover it already; the case
        // this exists for is a landing that emitted nothing new, which leaves it
        // out. Hold it for the duration of its own read, or the prune above
        // drops the card the moment it lands. Bounded by the rows actually on
        // screen, and released by the next window.
        windowIds = windowIds + ref.id
        loadContent(listOf(ref))
        listOfNotNull(ref.passageId).let { passageIds ->
            loadPassages(passageIds)
            loadPassageFirstIds(passageIds)
        }
        loadCategoryContexts(listOf(ref.categoryId))
    }

    private fun loadContent(window: List<QuestionRef>) {
        // A row needs its card read for either of two reasons: it is not held at
        // all (the window read it once, moved on, and the prune below dropped
        // it), or the ref's hash moved on, meaning the question was edited
        // elsewhere and the cached card is stale.
        //
        // Presence has to be checked separately from the hash. The hash table is
        // bookkeeping for the cards held, but the prune used to drop only the
        // cards — the hashes stayed. A row returning into the window then
        // answered "hash matches, nothing to read" while its card was gone, so
        // the read never happened: the row fell back to its stem-only preview
        // and stayed there, which is what "scrolled away and back, no options"
        // was.
        val held = _state.value.contentById
        val stale = window.filter { it.id !in held || contentHashes[it.id] != it.contentHash }
        val toRead = stale.filter { contentLoading.add(it.id) }
        if (toRead.isEmpty()) return
        viewModelScope.launch {
            try {
                val loaded = repository.getQuestionsByIds(toRead.map { it.id }).associateBy { it.id }
                toRead.forEach { contentHashes[it.id] = it.contentHash }
                _state.update { current ->
                    // Only what the current window covers is kept: a card
                    // scrolled far away is read again if it comes back, where
                    // holding every card ever read would grow with the paper.
                    //
                    // "Current", not the window this read started from. Scrolling
                    // moves the window more than once, so reads land out of
                    // order, and pruning by its own older window made a read that
                    // finished last drop the cards a newer read had just landed —
                    // the rows on screen fell back to their stand-in and nothing
                    // asked for them again, since the window that covered them had
                    // already been collected. The other loaders prune by the live
                    // window for the same reason; this one held a copy.
                    current.copy(
                        contentById = current.contentById.filterKeys { it in windowIds } +
                            loaded.filterKeys { it in windowIds }
                    )
                }
                // Pruned with the cards they describe, so the table stays the
                // size of the window instead of one entry per row ever read.
                contentHashes.keys.retainAll(windowIds)
            } catch (e: Exception) {
                Logger.e("BROWSEVM", "card content for ${toRead.size} rows failed", e)
            } finally {
                contentLoading.removeAll(toRead.map { it.id })
            }
        }
    }

    private fun loadPassages(passageIds: List<String>) {
        if (passageIds.isEmpty()) return
        val missing = passageIds.filter { it !in _state.value.passages && passagesByIdLoading.add(it) }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            try {
                val loaded = repository.getPassagesByIds(missing).associateBy { it.id }
                _state.update {
                    it.copy(passages = it.passages.filterKeys { id -> id in windowPassageIds } + loaded)
                }
            } catch (e: Exception) {
                Logger.e("BROWSEVM", "passages for ${missing.size} rows failed", e)
            } finally {
                passagesByIdLoading.removeAll(missing)
            }
        }
    }

    private fun loadPassageFirstIds(passageIds: List<String>) {
        if (passageIds.isEmpty()) return
        val missing = passageIds.filter {
            !_state.value.passageFirstIds.containsKey(it) && firstIdsLoading.add(it)
        }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            try {
                val firsts = LinkedHashMap<String, String?>()
                for (passageId in missing) {
                    firsts[passageId] = repository.browsePassageFirstRefId(passageId)
                }
                _state.update {
                    it.copy(
                        passageFirstIds = it.passageFirstIds.filterKeys { id -> id in windowPassageIds } + firsts
                    )
                }
            } catch (e: Exception) {
                Logger.e("BROWSEVM", "passage first ids for ${missing.size} rows failed", e)
            } finally {
                firstIdsLoading.removeAll(missing)
            }
        }
    }

    private fun loadCategoryContexts(categoryIds: List<String>) {
        if (categoryIds.isEmpty()) return
        val missing = categoryIds.filter {
            it !in _state.value.categoryContexts && contextsLoading.add(it)
        }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            try {
                val loaded = LinkedHashMap<String, CategoryContext>()
                for (categoryId in missing) {
                    val refs = repository.browseCategoryRefs(categoryId)
                    loaded[categoryId] =
                        CategoryContext(refs, refs.firstOrNull()?.id, refs.lastOrNull()?.id)
                }
                _state.update {
                    it.copy(
                        categoryContexts = it.categoryContexts.filterKeys { id -> id in windowCategoryIds } + loaded
                    )
                }
            } catch (e: Exception) {
                Logger.e("BROWSEVM", "reorder context for ${missing.size} categories failed", e)
            } finally {
                contextsLoading.removeAll(missing)
            }
        }
    }

    private fun invalidateContexts() {
        _state.update {
            it.copy(
                categoryContexts = emptyMap(),
                passageFirstIds = emptyMap(),
                passages = emptyMap(),
                contentById = emptyMap(),
                contextsVersion = it.contextsVersion + 1
            )
        }
        contextsLoading.clear()
        firstIdsLoading.clear()
        passagesByIdLoading.clear()
        contentLoading.clear()
        contentHashes.clear()
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
                val file = (
                    getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Could not open output stream")
                    ).use { sink ->
                    com.mcqapp.data.export.PaperExporter(repository.db())
                        .exportDto(dto, title, format, sink, twoColumnPdf = repository.pdfTwoColumn().first())
                }
                Logger.i("BROWSEVM", "Exported ${selected.size} questions as ${file.fileName}")
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
