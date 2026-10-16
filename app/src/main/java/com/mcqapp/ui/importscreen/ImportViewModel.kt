package com.mcqapp.ui.importscreen

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.io.ImportOverrides
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.StreamingImporter
import com.mcqapp.data.io.scanImportPreview
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.local.getExistingIdsChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.local.getMatchesByContentHashesChunked
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Files above this size are previewed and imported by streaming instead of
 * being parsed into a whole DTO tree. Small files keep the exact whole-file
 * path, so nothing about the common case changes.
 */
const val JSON_STREAM_THRESHOLD_BYTES: Int = 4 * 1024 * 1024

/** How many questions of a large bank the preview keeps on the heap. */
const val PREVIEW_SAMPLE_SIZE: Int = 500

object ImportDataHolder {
    /**
     * Raw bytes of a picked JSON bank. Held as bytes, never as a String: a
     * 48 MB bank became a 96 MB Java String on top of the byte array the
     * picker already held, which alone could exhaust the heap before the
     * parser even started.
     */
    var pendingJsonBytes: ByteArray? = null
    var pendingDocxBytes: ByteArray? = null
    /** Display name of the picked Word file; becomes the default paper title. */
    var pendingDocxName: String? = null
    var pendingEditQuestion: QuestionDto? = null
    var editingFromImport: Boolean = false
}

/**
 * The paper title a picked Word file should default to: its file name
 * without the extension. Word banks rarely name the paper inside the
 * document, so the title used to arrive as whatever the parser invented.
 */
internal fun docxPaperTitle(fileName: String?): String? =
    fileName
        ?.substringAfterLast('/')
        ?.substringBeforeLast('.')
        ?.trim()
        ?.takeIf { it.isNotBlank() }

/**
 * Existing names that contain the typed text, closest matches first.
 * The field keeps whatever the user typed — this only feeds the dropdown,
 * so a name the library has never seen just becomes a new one.
 */
internal fun suggestNames(query: String, existing: List<String>, limit: Int = 8): List<String> {
    val q = query.trim()
    val needle = q.lowercase()
    return existing
        .asSequence()
        .filter { it.isNotBlank() }
        .filter { needle.isEmpty() || it.lowercase().contains(needle) }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase().startsWith(needle).not() }
        .take(limit)
        .toList()
}

data class ImportUiState(
    val loading: Boolean = true,
    val paperTitle: String = "",
    val paperDescription: String = "",
    val durationMinutes: Int = 0,
    val negativeMarking: Double = 0.0,
    val categoryName: String = "Uncategorized",
    /** Existing library paper titles, for the title field's suggestions. */
    val paperTitleSuggestions: List<String> = emptyList(),
    /** Existing library category names, for the category field's suggestions. */
    val categorySuggestions: List<String> = emptyList(),
    val questions: List<QuestionDto> = emptyList(),
    /** Row-level parse diagnostics (skipped malformed rows); empty for clean files. */
    val parseWarnings: List<String> = emptyList(),
    /** Ids of preview questions already in the library (same content hash). */
    val duplicateIds: Set<String> = emptySet(),
    /** Ids whose content changed vs the library row with the same id (will UPDATE). */
    val changedIds: Set<String> = emptySet(),
    val importReport: com.mcqapp.data.io.ImportReport? = null,
    val originalFile: McqFileDto? = null,
    val importing: Boolean = false,
    val importDone: Boolean = false,
    /** Questions the file actually holds; larger than [questions] when a large bank was sampled. */
    val totalQuestionCount: Int = 0,
    /** True when only the first [PREVIEW_SAMPLE_SIZE] questions are held for preview. */
    val previewTruncated: Boolean = false,
    /** The file's first paper has exactly one category, so the preview may rename it. */
    val singleCategoryPaper: Boolean = false,
    /** Ids the preview sample started with, to tell a preview deletion from an absent question. */
    val previewSampleIds: Set<String> = emptySet(),
    /** Fatal load failure (unreadable/corrupt file, no papers): shown as a dialog. */
    val error: String? = null
)

internal fun buildPreviewPaperDto(
    s: ImportUiState,
    original: McqFileDto?
): PaperDto {
    if (original == null || original.papers.isEmpty()) {
        // Preview-built paper: no stable id exists, so mint an ephemeral
        // one that the Importer may match by title.
        val paperId = LegacyParser.EPHEMERAL_PAPER_ID_PREFIX +
            System.currentTimeMillis().toString(36)
        return PaperDto(
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
    val orig = original.papers.first()
    val origTotal = orig.categories.sumOf { it.questions.size }
    val origIds = orig.categories.flatMap { it.questions }.map { it.id }
    val stateIds = s.questions.map { it.id }
    // Counts + head only: a full 25k id list once blew up a log line.
    Logger.d("IMPORTVM", "import(): orig cats=${orig.categories.size} " +
        "origQs=$origTotal stateQs=${s.questions.size} " +
        "origHead=${origIds.take(5)} stateHead=${stateIds.take(5)}")
    // Index once: per-question find() was O(n^2) and froze Main on 25k rows.
    val byId = s.questions.associateBy { it.id }
    val matchedIds = HashSet<String>()
    val mapped = orig.categories.map { cat ->
        val matched = cat.questions.mapNotNull { origQ ->
            byId[origQ.id]?.also { matchedIds.add(it.id) }
        }
        if (matched.size != cat.questions.size) {
            Logger.w("IMPORTVM", "import(): category '${cat.title}': " +
                "orig=${cat.questions.size} questions but only " +
                "${matched.size} matched state by id; missing " +
                (cat.questions.map { it.id } - matched.map { it.id }.toSet())
                    .take(10))
        }
        cat.copy(
            title = if (orig.categories.size == 1) {
                s.categoryName.trim().ifBlank { cat.title }
            } else {
                cat.title
            },
            questions = matched
        )
    }
    // Safety net: every preview question must reach the Importer.
    // If IDs diverged, appending beats silently dropping; the
    // DB content-hash dedup still decides what is actually new.
    val unmatched = s.questions.filter { it.id !in matchedIds }
    val categories = if (unmatched.isNotEmpty() && mapped.isNotEmpty()) {
        Logger.w("IMPORTVM", "import(): appending ${unmatched.size} unmatched " +
            "state questions to last category: ${unmatched.take(10).map { it.id }}")
        mapped.dropLast(1) + mapped.last().copy(
            questions = mapped.last().questions + unmatched
        )
    } else {
        mapped
    }
    return orig.copy(
        title = s.paperTitle.trim(),
        description = s.paperDescription.trim(),
        durationMinutes = s.durationMinutes,
        negativeMarking = s.negativeMarking,
        categories = categories
    )
}

/**
 * Whether a picked file is a backup restore rather than a single-paper import.
 * Backups carry several papers and/or review progress; the single-paper
 * preview can only show the first paper, so without special handling the rest
 * would be silently dropped on import.
 */
internal fun isBackupFile(file: McqFileDto): Boolean =
    file.papers.size > 1 || file.scheduling.isNotEmpty()

/**
 * Classifies preview questions exactly like the importer will: a hash already
 * in the database is a duplicate (skipped); the same id with changed content
 * or a fixed answer key is changed (updates in place); else new. Pure, so the
 * rule can be tested without an Android harness; the stored maps need only
 * cover the ids the caller deemed interesting.
 */
internal fun classifyPreview(
    hashed: List<Pair<QuestionDto, String>>,
    existingHashes: Set<String>,
    existingIds: Set<String>,
    storedById: Map<String, com.mcqapp.data.local.QuestionEntity>,
    correctById: Map<String, Set<String>>,
    storedOptionsByQuestion: Map<String, List<com.mcqapp.data.local.OptionEntity>>
): Pair<Set<String>, Set<String>> {
    val duplicates = HashSet<String>()
    val changed = HashSet<String>()
    hashed.forEach { (q, hash) ->
        val sameContent = storedById[q.id]?.let { stored ->
            com.mcqapp.data.io.ContentHash.sameQuestionContent(
                stored,
                storedOptionsByQuestion[q.id].orEmpty(),
                q
            )
        } == true
        if (hash in existingHashes && (sameContent || q.id in existingIds)) {
            // The hash ignores the answer key and metadata: the same id with a
            // fixed key reads as changed, exactly as the importer treats it.
            val changedAnswer = storedById[q.id]?.let { stored ->
                com.mcqapp.data.io.ContentHash.nonHashedFieldsDiffer(
                    stored,
                    correctById[q.id].orEmpty(),
                    q
                )
            } == true
            if (changedAnswer) changed.add(q.id) else duplicates.add(q.id)
        } else if (q.id in existingIds) {
            changed.add(q.id)
        }
    }
    return duplicates to changed
}

/**
 * The file the Importer actually writes, extracted so the backup decision is
 * unit-testable without an Android harness: it is pure DTO shuffling.
 *
 * Single-paper files take the preview-edited paper exactly as before. Backups
 * keep the edited first paper (preview deletions/renames still apply there)
 * and append the remaining papers untouched, with review progress attached —
 * so a restore can neither lose papers nor reset schedules.
 */
internal fun buildImportFile(
    s: ImportUiState,
    original: McqFileDto?
): McqFileDto {
    if (original == null || original.papers.isEmpty()) {
        return McqFileDto(
            version = 1,
            papers = listOf(buildPreviewPaperDto(s, null)),
            // Passages are not previewed, but member questions keep their
            // passageId, so the parsed block must ride along or the import
            // would write members whose context never resolves.
            passages = original?.passages ?: emptyList(),
            bookmarks = emptyList(),
            attempts = emptyList()
        )
    }
    val firstPaper = buildPreviewPaperDto(s, original)
    return McqFileDto(
        version = 1,
        papers = listOf(firstPaper) + original.papers.drop(1),
        passages = original.passages,
        bookmarks = original.bookmarks,
        attempts = original.attempts,
        scheduling = original.scheduling
    )
}

class ImportViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    init {
        // Suggestions for the paper-title and category fields: what the
        // library already calls things, so a re-import files itself next to
        // the earlier one instead of spawning a near-duplicate name.
        viewModelScope.launch {
            try {
                val titles = repository.existingPaperTitles()
                val categoryNames = repository.existingCategoryNames()
                _state.update {
                    it.copy(
                        paperTitleSuggestions = titles,
                        categorySuggestions = categoryNames
                    )
                }
            } catch (e: Exception) {
                Logger.e("IMPORTVM", "loading name suggestions failed", e)
            }
        }
    }

    var lastEditedQuestionId: String? = null
        private set

    fun markEditing(questionId: String) {
        lastEditedQuestionId = questionId
        Logger.d("IMPORTVM", "markEditing(id=$questionId): holder pendingEdit set by screen next")
    }

    fun updateQuestionFromImport(dto: QuestionDto) {
        val before = _state.value.questions.find { it.id == dto.id }
        Logger.d("IMPORTVM", "updateQuestionFromImport(id=${dto.id}): foundInState=${before != null}, " +
            "beforeTextLength=${before?.text?.length}, afterTextLength=${dto.text.length}, " +
            "afterOptions=${dto.options.size}, afterCorrect=${dto.correctOptionIds}")
        _state.update { s ->
            s.copy(questions = s.questions.map { if (it.id == dto.id) dto else it })
        }
        Logger.i("IMPORTVM", "Updated question ${dto.id} from in-memory edit")
        refreshDuplicates()
    }

    /**
     * Classifies preview questions exactly like Importer will: same content
     * hash in DB -> duplicate (skipped); same id but different hash ->
     * changed (updates the existing row in place); else brand new (added).
     * Advisory only; Importer re-evaluates at import.
     */
    fun refreshDuplicates() {
        viewModelScope.launch {
            try {
                // Hashing tens of thousands of questions blocks Main: classify off-thread.
                val questions = _state.value.questions
                var matchedCount = 0
                val (dups, changed) = withContext(Dispatchers.Default) {
                    val hashed = questions.map { q ->
                        q to com.mcqapp.data.io.ContentHash.of(q)
                    }
                    // Probe only the preview's ids and hashes, chunked, instead
                    // of snapshotting every id and hash in the database: a large
                    // library built two whole-table sets on the heap exactly
                    // while the parsed bank was live. Column-narrow, and only
                    // matching rows come back.
                    val hashes = repository.db().questionDao()
                        .getMatchesByContentHashesChunked(hashed.map { it.second })
                        .mapTo(HashSet()) { it.contentHash }
                    val ids = repository.db().questionDao()
                        .getExistingIdsChunked(hashed.map { it.first.id })
                        .toHashSet()
                    val interesting = hashed
                        .filter { (q, hash) -> q.id in ids || hash in hashes }
                        .map { it.first.id }
                        .distinct()
                    matchedCount = interesting.size
                    if (interesting.isEmpty()) {
                        emptySet<String>() to emptySet()
                    } else {
                        val storedById = repository.db().questionDao()
                            .getByIdsChunked(interesting)
                            .associateBy { it.id }
                        val correctById = repository.db().correctAnswerDao()
                            .getForQuestionsChunked(interesting)
                            .groupBy({ it.questionId }, { it.optionId })
                            .mapValues { (_, optionIds) -> optionIds.toSet() }
                        val storedOptionsByQuestion = repository.db().optionDao()
                            .getForQuestionsChunked(interesting)
                            .groupBy { it.questionId }
                        classifyPreview(
                            hashed, hashes, ids, storedById, correctById, storedOptionsByQuestion
                        )
                    }
                }
                _state.update { it.copy(duplicateIds = dups, changedIds = changed) }
                Logger.d("IMPORTVM", "refreshDuplicates: ${questions.size} preview = " +
                    "${questions.size - dups.size - changed.size} new, ${changed.size} changed, " +
                    "${dups.size} duplicates; stored rows fetched for $matchedCount")
            } catch (e: Exception) {
                Logger.e("IMPORTVM", "refreshDuplicates failed", e)
            }
        }
    }

    fun clearLastEdited() {
        lastEditedQuestionId = null
    }

    // Fingerprint (not the full text: a 25k-question file is ~9MB and must
    // not be retained twice) of the last directly-loaded JSON.
    private var loadedDirectFp: String? = null

    // Set only for a large single-paper JSON: how the streaming import re-opens
    // the file. Null means the whole-file path (small JSON, docx, backup).
    private var streamSource: (() -> InputStream)? = null

    private fun fingerprint(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * The JSON a picker handed over. Parsed straight from the bytes: the
     * old text path held the file twice (String + byte array) and then a
     * whole-document DOM, which is how a 48 MB bank died in an OOM.
     *
     * A bank above [JSON_STREAM_THRESHOLD_BYTES] is previewed and imported by
     * streaming so the whole DTO tree never exists; smaller files keep the
     * exact whole-file path.
     */
    fun loadJsonBytes(bytes: ByteArray) {
        viewModelScope.launch {
            val fp = withContext(Dispatchers.Default) { fingerprint(bytes) }
            if (fp == loadedDirectFp &&
                (_state.value.questions.isNotEmpty() || _state.value.totalQuestionCount > 0)
            ) {
                Logger.d("IMPORTVM", "loadJsonBytes: same file already loaded " +
                    "(${_state.value.questions.size} questions), skipping reload to preserve in-memory edits")
                return@launch
            }
            loadedDirectFp = fp
            try {
                withContext(Dispatchers.Default) {
                    if (bytes.size > JSON_STREAM_THRESHOLD_BYTES) {
                        loadLargeJson { ByteArrayInputStream(bytes) }
                    } else {
                        streamSource = null
                        parseAndLoad(parse = {
                            LegacyParser.parse(ByteArrayInputStream(bytes))
                        })
                    }
                }
            } catch (e: Exception) {
                loadedDirectFp = null
                Logger.e("IMPORTVM", "Failed to parse JSON", e)
                _state.update { it.copy(loading = false, error = "Could not parse the file: ${e.message}") }
            }
        }
    }

    /**
     * Bounded preview of a large single-paper bank. One pass keeps the header,
     * a sample of questions and the counts; the import re-opens the same source
     * and streams it, applying the paper-level edits made here.
     */
    private suspend fun loadLargeJson(open: () -> InputStream) {
        val preview = scanImportPreview(open, PREVIEW_SAMPLE_SIZE)
        if (preview.isBackup) {
            // Backups carry other papers and review progress the bounded
            // preview cannot represent; fall back to the whole-file path.
            Logger.i("IMPORTVM", "loadLargeJson: backup (${preview.paperCount} papers), " +
                "using whole-file parse")
            streamSource = null
            parseAndLoad(parse = { LegacyParser.parse(open()) })
            return
        }
        val header = preview.header
        if (header == null) {
            val detail = preview.warnings.joinToString("\n")
            _state.update {
                it.copy(
                    loading = false,
                    error = "No papers found in this file." + if (detail.isNotEmpty()) "\n$detail" else ""
                )
            }
            return
        }
        val sampleQuestions = preview.sample.map { it.dto }
        _state.value = ImportUiState(
            loading = false,
            paperTitle = header.title,
            paperDescription = header.description,
            durationMinutes = header.durationMinutes,
            negativeMarking = header.negativeMarking,
            categoryName = preview.effectiveCategoryTitle,
            questions = sampleQuestions,
            parseWarnings = preview.warnings,
            originalFile = null,
            totalQuestionCount = preview.totalQuestions,
            previewTruncated = sampleQuestions.size < preview.totalQuestions,
            singleCategoryPaper = preview.firstPaperCategoryCount == 1,
            previewSampleIds = sampleQuestions.mapTo(HashSet()) { it.id },
            paperTitleSuggestions = _state.value.paperTitleSuggestions,
            categorySuggestions = _state.value.categorySuggestions
        )
        streamSource = open
        Logger.i("IMPORTVM", "Loaded bounded preview: ${sampleQuestions.size} of " +
            "${preview.totalQuestions} questions across ${preview.firstPaperCategoryCount} categories")
        refreshDuplicates()
    }

    fun loadJsonText(text: String) {
        viewModelScope.launch {
            val fp = withContext(Dispatchers.Default) { fingerprint(text.toByteArray()) }
            if (fp == loadedDirectFp && _state.value.questions.isNotEmpty()) {
                Logger.d("IMPORTVM", "loadJsonText: same text already loaded " +
                    "(${_state.value.questions.size} questions), skipping reload to preserve in-memory edits")
                return@launch
            }
            loadedDirectFp = fp
            try {
                withContext(Dispatchers.Default) { parseAndLoad(parse = { LegacyParser.parse(text) }) }
            } catch (e: Exception) {
                loadedDirectFp = null
                Logger.e("IMPORTVM", "Failed to parse JSON", e)
                _state.update { it.copy(loading = false, error = "Could not parse the file: ${e.message}") }
            }
        }
    }

    /**
     * Word banks: convert to the labeled JSON our importer reads, then
     * follow the exact JSON path (preview, warnings, dedup). Docx-stage
     * warnings ride alongside the parse warnings. The picked file's name
     * becomes the paper title — the document rarely names its own paper.
     */
    fun loadDocx(bytes: ByteArray, fileName: String? = null) {
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) {
                    com.mcqapp.data.docx.parseDocx(bytes)
                }
                Logger.i("IMPORTVM", "Converted Word file (${bytes.size} bytes) " +
                    "with ${parsed.warnings.size} docx warnings, title from '$fileName'")
                // A document that names its own paper (Title/Heading 1) wins
                // over the file name; the preview stays editable either way.
                loadJsonTextWithWarnings(
                    parsed.json,
                    parsed.warnings,
                    parsed.paperTitle ?: docxPaperTitle(fileName)
                )
            } catch (e: Exception) {
                Logger.e("IMPORTVM", "Failed to parse Word file", e)
                _state.update { it.copy(loading = false, error = "Could not parse the Word file: ${e.message}") }
            }
        }
    }

    private suspend fun loadJsonTextWithWarnings(
        text: String,
        extraWarnings: List<String>,
        titleOverride: String? = null
    ) {
        val fp = withContext(Dispatchers.Default) { fingerprint(text.toByteArray()) }
        if (fp == loadedDirectFp && _state.value.questions.isNotEmpty()) return
        loadedDirectFp = fp
        try {
            withContext(Dispatchers.Default) {
                parseAndLoad(parse = { LegacyParser.parse(text) }, extraWarnings, titleOverride)
            }
        } catch (e: Exception) {
            loadedDirectFp = null
            throw e
        }
    }

    private suspend fun parseAndLoad(
        parse: () -> McqFileDto,
        extraWarnings: List<String> = emptyList(),
        titleOverride: String? = null
    ) {
        streamSource = null
        val file = parse()
        val paper = file.papers.firstOrNull()
        if (paper == null) {
            Logger.e("IMPORTVM", "No papers found in JSON")
            val detail = file.warnings.joinToString("\n")
            _state.update {
                it.copy(
                    loading = false,
                    error = "No papers found in this file." + if (detail.isNotEmpty()) "\n$detail" else ""
                )
            }
            return
        }

        val allQuestions = paper.categories.flatMap { it.questions }
        val effectiveCategory = if (paper.categories.isEmpty() || paper.categories.all { it.questions.isEmpty() }) {
            "Uncategorized"
        } else {
            paper.categories.firstOrNull { it.questions.isNotEmpty() }?.title ?: "Uncategorized"
        }

        // Backups preview only the first paper for editing, but import
        // everything: say so upfront or the preview understates the restore.
        val backupNotice = if (isBackupFile(file)) {
            val totalQs = file.papers.sumOf { p ->
                p.categories.sumOf { it.questions.size }
            }
            listOf(
                "Backup restore: ${file.papers.size} papers " +
                    "(${totalQs} questions) will be restored, " +
                    "including review schedules" +
                    (if (file.attempts.isNotEmpty()) " and ${file.attempts.size} attempts" else "") +
                    ". Only this first paper is previewed for editing."
            )
        } else {
            emptyList()
        }
        _state.value = ImportUiState(
            loading = false,
            paperTitle = titleOverride ?: paper.title,
            paperDescription = paper.description,
            durationMinutes = paper.durationMinutes,
            negativeMarking = paper.negativeMarking,
            categoryName = effectiveCategory,
            questions = allQuestions,
            parseWarnings = extraWarnings + backupNotice + file.warnings,
            originalFile = file,
            paperTitleSuggestions = _state.value.paperTitleSuggestions,
            categorySuggestions = _state.value.categorySuggestions
        )
        Logger.i("IMPORTVM", "Loaded ${allQuestions.size} questions for import preview")
        refreshDuplicates()
    }

    /**
     * Process death on the Import screen: the static holder is empty and no
     * text was passed, so nothing will ever flip loading off. Show an error
     * instead of an infinite spinner.
     */
    fun setNoSourceError() {
        _state.update {
            it.copy(
                loading = false,
                error = "Could not read the file. Please pick it again."
            )
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

    fun import() {
        val s = _state.value
        // Large single-paper banks stream: the file is re-opened and written a
        // batch at a time, with only the previewed sample's edits applied.
        val source = streamSource
        if (source != null) {
            if (s.paperTitle.isBlank()) return
            _state.update { it.copy(importing = true, importDone = false, importReport = null) }
            viewModelScope.launch {
                try {
                    val currentIds = s.questions.mapTo(HashSet()) { it.id }
                    val overrides = ImportOverrides(
                        paperTitle = s.paperTitle.trim(),
                        paperDescription = s.paperDescription.trim(),
                        durationMinutes = s.durationMinutes,
                        negativeMarking = s.negativeMarking,
                        categoryTitle = if (s.singleCategoryPaper) {
                            s.categoryName.trim().ifBlank { null }
                        } else {
                            null
                        },
                        deletedIds = s.previewSampleIds - currentIds,
                        editedQuestions = s.questions.associateBy { it.id }
                    )
                    Logger.i("IMPORTVM", "import(): streaming ${s.totalQuestionCount} questions " +
                        "with a ${s.questions.size}-question preview " +
                        "(${overrides.deletedIds.size} deleted in preview)")
                    val report = withContext(Dispatchers.Default) {
                        StreamingImporter(
                            repository.db(),
                            updateAnswersOnDuplicate = repository.updateAnswersOnDuplicate().first(),
                            overrides = overrides
                        ).import(source)
                    }
                    Logger.i("IMPORTVM", "Imported (streamed): ${report.newQuestions} new, " +
                        "${report.updatedQuestions} updated, ${report.duplicateQuestions} duplicates")
                    _state.update { it.copy(importing = false, importDone = true, importReport = report) }
                } catch (e: Exception) {
                    Logger.e("IMPORTVM", "Streaming import failed", e)
                    _state.update { it.copy(importing = false, error = "Import failed: ${e.message}") }
                }
            }
            return
        }
        val original = s.originalFile
        val backup = original != null && isBackupFile(original)
        // Single-paper imports need something to import; a backup still carries
        // papers 2+ (and schedules) even if paper 1 was emptied in preview.
        if (!backup && (s.paperTitle.isBlank() || s.questions.isEmpty())) return
        _state.update { it.copy(importing = true, importDone = false, importReport = null) }

        viewModelScope.launch {
            try {
                // File assembly is O(n) CPU work (25k rows froze Main as
                // O(n^2)); build it off-thread. Only the final state update
                // below needs the main thread (and update() is thread-safe).
                val file: McqFileDto = withContext(Dispatchers.Default) {
                    buildImportFile(s, original)
                }
                if (backup) {
                    Logger.i("IMPORTVM", "import(): backup restore, " +
                        "${file.papers.size} papers, " +
                        "${file.scheduling.size} scheduled cards, " +
                        "${file.attempts.size} attempts")
                }
                Logger.d("IMPORTVM", "import(): using ${s.questions.size} edited state questions " +
                    "(correct set on ${s.questions.count { it.correctOptionIds.isNotEmpty() }})")
                // The whole DB import (hashing + writes for every row) stays
                // off Main; 25k rows froze it for tens of seconds.
                val report = withContext(Dispatchers.Default) {
                    com.mcqapp.data.io.Importer(
                        repository.db(),
                        updateAnswersOnDuplicate = repository.updateAnswersOnDuplicate().first()
                    ).import(file)
                }
                Logger.i("IMPORTVM", "Imported: ${report.newPapers} new papers, ${report.updatedPapers} updated, " +
                    "${report.newQuestions} new questions, ${report.updatedQuestions} updated")
                _state.update { it.copy(importing = false, importDone = true, importReport = report) }
            } catch (e: Exception) {
                // Every sibling catch surfaces `error`, which ImportScreen
                // renders as a dialog. Swallowing it here left the spinner
                // stopping with no explanation, so a failed import was
                // indistinguishable from a successful one.
                Logger.e("IMPORTVM", "Import failed", e)
                _state.update { it.copy(importing = false, error = "Import failed: ${e.message}") }
            }
        }
    }

    fun consumeImportResult() {
        _state.update { it.copy(importDone = false, importReport = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }
}
