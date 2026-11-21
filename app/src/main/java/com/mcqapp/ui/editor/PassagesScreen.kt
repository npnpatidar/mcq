package com.mcqapp.ui.editor

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Passage
import com.mcqapp.util.ContentElements
import com.mcqapp.R
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class PassagesUiState(
    val loading: Boolean = true,
    /** memberCount per passage id, refreshed with the list. */
    val memberCounts: Map<String, Int> = emptyMap(),
    /** The one passage whose member list is open; null when none is. */
    val expandedId: String? = null,
    val papers: List<com.mcqapp.domain.Paper> = emptyList()
)

/**
 * The edit/create form, owned by the ViewModel rather than the dialog. When a
 * cross-paper warning unmounts the dialog, plain `remember` fields were
 * destroyed, so Cancel reopened a form seeded from the *unchanged* passage and
 * every typed edit was gone (round-2 E1). Keeping the one open form here also
 * makes it survive rotation (E2).
 *
 * [original] is the passage as loaded: it decides new-vs-edit and lets a no-op
 * save preserve rich elements. There is only ever one open dialog, so a single
 * value is enough — no keyed map is needed to find the right draft.
 */
data class PassageForm(
    val original: com.mcqapp.domain.Passage? = null,
    val title: String = "",
    val text: String = "",
    val categoryId: String = ""
) {
    val isNew: Boolean get() = original == null

    /** Save-time fallback for a dialog opened before papers had loaded (E7). */
    fun categoryOr(fallback: String?): String = categoryId.ifBlank { fallback.orEmpty() }
}

/**
 * Pure holder behind the ViewModel's form StateFlow. Hoisting the draft here
 * (rather than leaving it in the dialog's composition) is what makes Cancel
 * restore the exact edits; this small class is the seam the JVM tests drive.
 */
class PassageFormStore {
    var form: PassageForm? = null
        private set

    fun open(original: com.mcqapp.domain.Passage?) {
        form = PassageForm(
            original = original,
            title = original?.title.orEmpty(),
            text = original?.text.orEmpty(),
            categoryId = original?.categoryId.orEmpty()
        )
    }

    fun setTitle(value: String) { form = form?.copy(title = value) }
    fun setText(value: String) { form = form?.copy(text = value) }
    fun setCategory(value: String) { form = form?.copy(categoryId = value) }

    fun seedCategory(fallback: String) {
        val current = form ?: return
        if (fallback.isNotBlank() && current.categoryId.isBlank()) {
            form = current.copy(categoryId = fallback)
        }
    }

    fun close() { form = null }
}

/**
 * The pre-save check for a passage edit. [ConfirmCrossPaper] means the whole
 * member group would follow the passage into another paper and the save must
 * be held until the user confirms.
 */
enum class PassageSaveDecision { Save, ConfirmCrossPaper }

@OptIn(ExperimentalCoroutinesApi::class)
class PassagesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(PassagesUiState())
    val state: StateFlow<PassagesUiState> = _state.asStateFlow()

    private val expandedId = MutableStateFlow<String?>(null)

    // Save staging lives on the ViewModel, not a composable scope: a rotation
    // or back navigation must not cancel the fresh count query mid-flight and
    // lose the user's edit. These survive configuration changes.
    private val _pendingCrossPaperSave = MutableStateFlow<Passage?>(null)
    /** The passage waiting for the cross-paper confirmation; null when none. */
    val pendingCrossPaperSave: StateFlow<Passage?> = _pendingCrossPaperSave.asStateFlow()

    private val _isDeciding = MutableStateFlow(false)
    /** True while the pre-save count query runs, so the UI can ignore taps. */
    val isDeciding: StateFlow<Boolean> = _isDeciding.asStateFlow()

    private val _isConfirming = MutableStateFlow(false)
    /** True while a confirmed cross-paper write runs; Cancel is unsafe then. */
    val isConfirming: StateFlow<Boolean> = _isConfirming.asStateFlow()

    // The open edit/create form lives here, not in the dialog's composition.
    // The dialog unmounts while the cross-paper warning is up; VM ownership is
    // what lets Cancel restore the exact title/body/category the user typed.
    private val formStore = PassageFormStore()
    private val _form = MutableStateFlow<PassageForm?>(null)
    val form: StateFlow<PassageForm?> = _form.asStateFlow()

    private fun publishForm() { _form.value = formStore.form }

    /** Opens a fresh form for [original] (null for a new passage). */
    fun openForm(original: com.mcqapp.domain.Passage?) {
        formStore.open(original)
        publishForm()
    }

    fun setFormTitle(value: String) { formStore.setTitle(value); publishForm() }
    fun setFormText(value: String) { formStore.setText(value); publishForm() }
    fun setFormCategory(value: String) { formStore.setCategory(value); publishForm() }

    /** Seeds the first available category only while none is chosen (E7). */
    fun seedFormCategory(fallback: String) {
        formStore.seedCategory(fallback)
        publishForm()
    }

    /** Closes the form after a completed save or an explicit dismiss. */
    fun closeForm() { formStore.close(); publishForm() }

    private val _saveCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted after each completed write so the screen can close its dialogs. */
    val saveCompleted: Flow<Unit> = _saveCompleted.asSharedFlow()

    // Stage and confirm own separate jobs and generations. They overlap for a
    // moment: the stage publishes the pending warning, then an immediate
    // confirm starts while the stage is still unwinding. Sharing one job would
    // let that confirm be ignored (or leave isDeciding stuck true), so each
    // phase clears only its own state. A dismiss/back cancels both jobs and
    // bumps both generations, so a stale continuation can neither write nor
    // leave a pending warning.
    private var stageJob: Job? = null
    private var confirmJob: Job? = null
    private var stageGeneration = 0
    private var confirmGeneration = 0

    /** The open passage's members, paged; empty while nothing is open. */
    val memberPaging: Flow<PagingData<QuestionRef>> = expandedId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(PagingData.empty())
            } else {
                Pager(
                    config = PagingConfig(
                        pageSize = MEMBER_PAGE,
                        initialLoadSize = MEMBER_PAGE,
                        enablePlaceholders = false
                    ),
                    pagingSourceFactory = { repository.passageMembersSource(id) }
                ).flow
            }
        }
        .cachedIn(viewModelScope)

    val passages: StateFlow<List<Passage>> = repository.observePassages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.observePapers().collect { papers ->
                _state.update { it.copy(loading = false, papers = papers) }
            }
        }
        viewModelScope.launch {
            passages.collect {
                // One grouped COUNT for every passage, not one query per row.
                val counts = repository.passageMemberCounts()
                _state.update { it.copy(memberCounts = counts, loading = false) }
            }
        }
    }

    /**
     * Decides at the moment of save whether [passage]'s members will follow it
     * across a paper boundary. The count is read fresh from the repository:
     * [PassagesUiState.memberCounts] only refresh when the passages table emits,
     * so adding members from another screen (a question edit) writes only the
     * question table and leaves those cached counts stale — often at zero,
     * which would otherwise suppress this mandatory confirmation.
     */
    suspend fun decideSave(
        passage: Passage,
        originalPaperId: String?,
        newPaperId: String?
    ): PassageSaveDecision {
        val members = repository.passageMemberCount(passage.id)
        val crossesPaper = members > 0 &&
            originalPaperId != null && newPaperId != null &&
            originalPaperId != newPaperId
        return if (crossesPaper) PassageSaveDecision.ConfirmCrossPaper else PassageSaveDecision.Save
    }

    /**
     * Stages an edit's save on [viewModelScope]. Runs the fresh count, then
     * either publishes [pendingCrossPaperSave] (and writes nothing) or saves
     * straight away. Only one job is active at a time: a fast double tap is
     * ignored, and [cancelPending] can cancel this job before it writes or
     * raises a warning when the user abandons the edit.
     */
    fun stageSave(passage: Passage, originalPaperId: String?, newPaperId: String?) {
        if (stageJob?.isActive == true || confirmJob?.isActive == true) return
        val gen = ++stageGeneration
        _isDeciding.value = true
        stageJob = viewModelScope.launch {
            try {
                // A dismiss/back right after stageSave must be able to cancel
                // this job before any count query or write. yield() gives that
                // synchronous cancel a dispatch point even when Main.immediate
                // would otherwise run the whole stage inline.
                yield()
                val decision = decideSave(passage, originalPaperId, newPaperId)
                coroutineContext.ensureActive()
                if (gen != stageGeneration) return@launch
                when (decision) {
                    PassageSaveDecision.ConfirmCrossPaper -> _pendingCrossPaperSave.value = passage
                    PassageSaveDecision.Save -> {
                        repository.savePassage(passage)
                        coroutineContext.ensureActive()
                        if (gen == stageGeneration) _saveCompleted.emit(Unit)
                    }
                }
            } finally {
                // A cancelled stage was already cleared by cancelPending();
                // never clobber a newer stage that replaced it. A confirm
                // bumps only confirmGeneration, so this still clears normally.
                if (gen == stageGeneration) {
                    _isDeciding.value = false
                    stageJob = null
                }
            }
        }
    }

    /**
     * Abandons the edit: cancels any in-flight stage or confirm and clears the
     * warning. Safe from any dismiss/back path — a cancelled job writes nothing
     * and leaves no pending warning behind.
     */
    fun cancelPending() {
        stageGeneration++
        confirmGeneration++
        stageJob?.cancel()
        confirmJob?.cancel()
        stageJob = null
        confirmJob = null
        _isDeciding.value = false
        _isConfirming.value = false
        _pendingCrossPaperSave.value = null
    }

    /** Writes the pending passage exactly once, then clears the warning. */
    fun confirmPending() {
        // Gate on the dedicated flag, not on a stage job: the stage publishes
        // the pending warning just before its finally clears that job, so an
        // immediate confirm must not be swallowed by that window.
        if (_isConfirming.value) return
        val passage = _pendingCrossPaperSave.value ?: return
        val gen = ++confirmGeneration
        _isConfirming.value = true
        confirmJob = viewModelScope.launch {
            try {
                // Mirror the stage path: give a synchronous back/dismiss a
                // dispatch point to cancel before any DB work starts, even
                // when Main.immediate would otherwise run the write inline.
                yield()
                coroutineContext.ensureActive()
                // A back/dismiss may have cancelled and bumped the generation
                // while this continuation was posted; never start the write.
                if (gen != confirmGeneration || confirmJob?.isActive == false) return@launch
                repository.savePassage(passage)
                coroutineContext.ensureActive()
                if (gen != confirmGeneration) return@launch
                _pendingCrossPaperSave.value = null
                _saveCompleted.emit(Unit)
            } finally {
                if (gen == confirmGeneration) {
                    _isConfirming.value = false
                    confirmJob = null
                }
            }
        }
    }

    /**
     * Opens (or closes) one passage's member list. Only one is open at a time
     * so a single pager can back the rows.
     */
    fun setExpanded(passageId: String?) {
        _state.update { it.copy(expandedId = passageId) }
        expandedId.value = passageId
    }

    fun delete(passageId: String, onRefused: (Int) -> Unit) {
        viewModelScope.launch {
            val members = repository.passageMemberCount(passageId)
            if (members > 0) {
                onRefused(members)
            } else {
                repository.deletePassage(passageId)
            }
        }
    }

    fun move(passageId: String, delta: Int) {
        viewModelScope.launch { repository.movePassage(passageId, delta) }
    }

    companion object {
        private const val MEMBER_PAGE = 50
    }
}

/**
 * Passage management: list with member counts, create/edit dialogs, guarded
 * delete (D5) and reorder. Reached from the library; editing a question's
 * membership lives in the question editor's passage picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassagesScreen(
    repository: McqRepository,
    navController: androidx.navigation.NavController
) {
    val viewModel: PassagesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val passages by viewModel.passages.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The edit form lives in the ViewModel (E1/E2); these two are just local
    // dialog flags. saveable so rotation does not drop the delete refusal (E2).
    var deleteRefusal by rememberSaveable { mutableStateOf<Pair<String, Int>?>(null) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    // The pending confirmation lives in the ViewModel so it survives rotation.
    val pendingCrossPaperSave by viewModel.pendingCrossPaperSave.collectAsStateWithLifecycle()
    val isDeciding by viewModel.isDeciding.collectAsStateWithLifecycle()
    val isConfirming by viewModel.isConfirming.collectAsStateWithLifecycle()
    // The one open passage's member rows, paged in as they scroll into view.
    val memberItems: LazyPagingItems<QuestionRef> = viewModel.memberPaging.collectAsLazyPagingItems()

    LaunchedEffect(Unit) {
        viewModel.saveCompleted.collect {
            viewModel.closeForm()
        }
    }

    // categoryId -> owning paper, hoisted: the per-row firstOrNull below ran
    // once per scroll frame for every visible row.
    val paperByCategoryId = remember(state.papers) {
        buildMap {
            state.papers.forEach { paper ->
                fun walk(nodes: List<com.mcqapp.domain.CategoryNode>) {
                    nodes.forEach { node ->
                        put(node.id, paper)
                        walk(node.children)
                    }
                }
                walk(paper.categories)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.passages)) },
                navigationIcon = {
                    IconButton(onClick = {
                        // Back abandons the edit: cancel any in-flight write
                        // first so it cannot land after we navigate away.
                        viewModel.cancelPending()
                        navController.popBackStack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.openForm(null) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_passage))
            }
        }
    ) { padding ->
        if (passages.isEmpty() && !state.loading) {
            Text(
                stringResource(R.string.passages_empty),
                modifier = Modifier.padding(padding).padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                passages.forEach { passage ->
                    val paperTitle = paperByCategoryId[passage.categoryId]
                    val paperId = paperTitle?.id.orEmpty()
                    val expanded = passage.id == state.expandedId
                    val memberCount = state.memberCounts[passage.id] ?: 0
                    item(key = "p:" + passage.id) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        passage.title.ifBlank { stringResource(R.string.passage) },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (paperTitle != null && paperTitle.title.isNotBlank()) {
                                        Text(
                                            paperTitle.title,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Text(
                                        stringResource(R.string.passage_member_count, memberCount),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                IconButton(onClick = {
                                    viewModel.move(passage.id, -1)
                                }) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp,
                                        contentDescription = stringResource(R.string.move_up)
                                    )
                                }
                                IconButton(onClick = {
                                    viewModel.move(passage.id, +1)
                                }) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.move_down)
                                    )
                                }
                                IconButton(onClick = { viewModel.openForm(passage) }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.edit_passage)
                                    )
                                }
                                IconButton(onClick = {
                                    viewModel.delete(passage.id) { members ->
                                        deleteRefusal = passage.id to members
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.delete_passage)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            ContentElements(
                                passage.elements,
                                textStyle = MaterialTheme.typography.bodySmall,
                                maxLines = 4
                            )
                            TextButton(onClick = {
                                viewModel.setExpanded(if (expanded) null else passage.id)
                            }) {
                                Text(
                                    stringResource(
                                        if (expanded) R.string.hide_passage_questions
                                        else R.string.show_passage_questions,
                                        memberCount
                                    ),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                    }
                    // The member questions, on demand: rows below the passage
                    // are the natural place to see what it holds and jump into
                    // one to edit. Paged, so a big passage stays smooth.
                    if (expanded) {
                        items(
                            count = memberItems.itemCount,
                            key = { index -> memberItems.itemKey { it.id }(index) },
                            contentType = { "member" }
                        ) { index ->
                            val member = memberItems[index]
                            if (member != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // The question in its paper, focused:
                                            // the same arrival a search hit gets.
                                            // The member's own paper, not the
                                            // passage's, in case a question ever
                                            // sits in another category's paper.
                                            val memberPaperId =
                                                paperByCategoryId[member.categoryId]?.id ?: paperId
                                            navController.navigate(
                                                com.mcqapp.ui.navigation.browseRoute(
                                                    memberPaperId, member.id
                                                )
                                            )
                                        }
                                        .padding(vertical = 6.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResource(R.string.editor_index, index + 1),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        member.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    // A zero-option question is a flashcard; the
                                    // ref's answer-key count cannot say so (a
                                    // keyless MCQ has none either), hence the
                                    // option count. Rendered text, not a
                                    // description only.
                                    if (member.optionCount == 0) {
                                        Text(
                                            stringResource(R.string.r2_flashcard_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (memberItems.itemCount == 0) {
                            item(key = "p-empty:" + passage.id) {
                                // An error used to fall through to "no passages
                                // yet", hiding a failed load with no way out
                                // (round-2 E6).
                                when (val refresh = memberItems.loadState.refresh) {
                                    is LoadState.Loading -> CircularProgressIndicator(
                                        modifier = Modifier.padding(12.dp).width(20.dp)
                                    )
                                    is LoadState.Error -> Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    ) {
                                        Text(
                                            stringResource(R.string.r2_editor_members_load_error),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { memberItems.retry() }) {
                                            Text(stringResource(R.string.r2_editor_retry))
                                        }
                                    }
                                    else -> Text(
                                        stringResource(R.string.passages_empty),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    )
                                }
                            }
                        }
                        if (memberItems.loadState.append is LoadState.Loading) {
                            item(key = "p-append:" + passage.id) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(12.dp).width(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (form != null && pendingCrossPaperSave == null) {
            PassageEditDialog(
                form = form!!,
                papers = state.papers,
                saveEnabled = !isDeciding,
                dismissEnabled = !isDeciding,
                onTitle = viewModel::setFormTitle,
                onText = viewModel::setFormText,
                onCategory = viewModel::setFormCategory,
                onSeedCategory = viewModel::seedFormCategory,
                onSave = { passage ->
                    val original = form?.original
                    val originalPaper = original?.let { paperByCategoryId[it.categoryId] }
                    val newPaper = paperByCategoryId[passage.categoryId]
                    // The ViewModel re-reads the member count fresh, on its own
                    // scope, so rotation cannot lose the edit mid-decision.
                    viewModel.stageSave(passage, originalPaper?.id, newPaper?.id)
                },
                onDismiss = {
                    // Abandoning the edit must cancel an in-flight decision,
                    // otherwise its late result writes or pops a stray warning.
                    // The form is left intact only for the cross-paper Cancel
                    // path, which clears the warning without calling this.
                    viewModel.cancelPending()
                    viewModel.closeForm()
                }
            )
        }

        // Never stack the delete refusal over the cross-paper confirmation:
        // a second dialog would hide the choice and steal the back/dismiss.
        if (pendingCrossPaperSave == null) {
            deleteRefusal?.let { refusal ->
                val members = refusal.second
                AlertDialog(
                    onDismissRequest = { deleteRefusal = null },
                    title = { Text(stringResource(R.string.delete_passage)) },
                    text = { Text(stringResource(R.string.passage_has_members_warning, members)) },
                    confirmButton = {
                        TextButton(onClick = { deleteRefusal = null }) {
                            Text(stringResource(R.string.ok))
                        }
                    }
                )
            }
        }

        pendingCrossPaperSave?.let { _ ->
            AlertDialog(
                onDismissRequest = { if (!isConfirming) viewModel.cancelPending() },
                title = { Text(stringResource(R.string.move)) },
                text = { Text(stringResource(R.string.move_cross_paper_warning)) },
                confirmButton = {
                    TextButton(
                        modifier = Modifier.testTag("cross-paper-move-confirm"),
                        enabled = !isConfirming,
                        onClick = { viewModel.confirmPending() }
                    ) {
                        Text(stringResource(R.string.move))
                    }
                },
                dismissButton = {
                    TextButton(
                        modifier = Modifier.testTag("cross-paper-move-cancel"),
                        enabled = !isConfirming,
                        onClick = { viewModel.cancelPending() }
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

/** Create/edit dialog: title, body and the paper category it lives in. */
@Composable
private fun PassageEditDialog(
    form: PassageForm,
    papers: List<com.mcqapp.domain.Paper>,
    saveEnabled: Boolean,
    dismissEnabled: Boolean,
    onTitle: (String) -> Unit,
    onText: (String) -> Unit,
    onCategory: (String) -> Unit,
    onSeedCategory: (String) -> Unit,
    onSave: (Passage) -> Unit,
    onDismiss: () -> Unit
) {
    // Every field comes from the ViewModel-owned form: unmounting this dialog
    // for the cross-paper warning no longer discards what the user typed (E1),
    // and rotation keeps it (E2).
    val title = form.title
    val text = form.text
    val original = form.original

    val categories = papers.flatMap { paper ->
        fun flatten(nodes: List<com.mcqapp.domain.CategoryNode>): List<com.mcqapp.domain.CategoryNode> =
            nodes.flatMap { listOf(it) + flatten(it.children) }
        flatten(paper.categories)
    }
    // Seed when the papers first arrive. The old `LaunchedEffect(Unit)` ran
    // before papers loaded, left categoryId blank forever and disabled Save
    // (E7); keying on availability re-runs once they do. A save-time fallback
    // below covers the gap even before this effect runs.
    LaunchedEffect(categories.isNotEmpty()) {
        if (categories.isNotEmpty()) onSeedCategory(categories.first().id)
    }
    val effectiveCategory = form.categoryOr(categories.firstOrNull()?.id)

    AlertDialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        title = {
            Text(
                if (form.isNew) stringResource(R.string.new_passage)
                else stringResource(R.string.edit_passage)
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitle,
                    label = { Text(stringResource(R.string.passage_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    label = { Text(stringResource(R.string.passage_text)) },
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )
                Spacer(Modifier.height(8.dp))
                CategoryPicker(
                    categories = categories,
                    selectedId = effectiveCategory,
                    onSelect = onCategory
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // The text field is seeded from `original.text`, which is
                    // the flattened `textContent` — it drops tables, formulas
                    // and images. Rewriting elements from that flattened string
                    // would destroy a rich passage on a no-op save, so keep the
                    // original elements unless the user actually edited the
                    // text (and then accept that rich markup is lost, as it is
                    // for questions).
                    val edited = text.trim()
                    // Compare like with like. `original.text` is the flattened
                    // textContent and may carry edge whitespace; comparing the
                    // trimmed field to the untrimmed text made an untouched
                    // rich passage look edited, flattening its tables, formulas
                    // and images on a no-op save. A whitespace-only difference
                    // is not a content edit, so keep the original elements.
                    val elements = if (original != null && edited == original.text.trim()) {
                        original.elements
                    } else {
                        listOf(com.mcqapp.domain.ContentElement.TextElement(edited))
                    }
                    onSave(
                        Passage(
                            id = original?.id.orEmpty(),
                            categoryId = effectiveCategory,
                            title = title.trim(),
                            elements = elements,
                            // The dialog edits text only: dropping the image
                            // here deleted it on every save, untouched or not
                            // (consolidated_bugs.md C4).
                            image = original?.image
                        )
                    )
                },
                enabled = saveEnabled && title.isNotBlank() && effectiveCategory.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = dismissEnabled) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(
    categories: List<com.mcqapp.domain.CategoryNode>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected?.title ?: stringResource(R.string.category),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.category)) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            categories.forEach { node ->
                DropdownMenuItem(
                    text = { Text(node.title) },
                    onClick = {
                        onSelect(node.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
