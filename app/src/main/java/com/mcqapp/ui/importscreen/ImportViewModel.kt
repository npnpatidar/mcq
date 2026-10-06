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
import com.mcqapp.data.local.getForQuestionsChunked
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

object ImportDataHolder {
    var pendingJsonText: String? = null
    var pendingDocxBytes: ByteArray? = null
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
            bookmarks = emptyList(),
            attempts = emptyList()
        )
    }
    val firstPaper = buildPreviewPaperDto(s, original)
    return McqFileDto(
        version = 1,
        papers = listOf(firstPaper) + original.papers.drop(1),
        bookmarks = original.bookmarks,
        attempts = original.attempts,
        scheduling = original.scheduling
    )
}

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
                val existing = repository.db().questionDao().getAll()
                // Hashing tens of thousands of questions blocks Main: classify off-thread.
                val questions = _state.value.questions
                val (dups, changed) = withContext(Dispatchers.Default) {
                    val hashes = existing.map { it.contentHash }.toHashSet()
                    val ids = existing.map { it.id }.toHashSet()
                    val byId = existing.associateBy { it.id }
                    val correctById = if (questions.isEmpty()) {
                        emptyMap()
                    } else {
                        repository.db().correctAnswerDao()
                            .getForQuestionsChunked(questions.map { it.id })
                            .groupBy({ it.questionId }, { it.optionId })
                    }
                    val d = HashSet<String>()
                    val c = HashSet<String>()
                    // Every candidate a hash matched, so a collision between two
                    // genuinely different questions can be told apart here and
                    // in the Importer, which must agree.
                    val storedOptionsByQuestion = repository.db().optionDao()
                        .getForQuestionsChunked(questions.map { it.id })
                        .groupBy { it.questionId }
                    questions.forEach { q ->
                        val hash = com.mcqapp.data.io.ContentHash.of(q)
                        val sameContent = byId[q.id]?.let { stored ->
                            com.mcqapp.data.io.ContentHash.sameQuestionContent(
                                stored,
                                storedOptionsByQuestion[q.id].orEmpty(),
                                q
                            )
                        } == true
                        if (hash in hashes && (sameContent || q.id in byId)) {
                            // The hash ignores the answer key and metadata: the
                            // same id with a fixed key reads as changed, exactly
                            // as the Importer will treat it.
                            val changedAnswer = byId[q.id]?.let { stored ->
                                com.mcqapp.data.io.ContentHash.nonHashedFieldsDiffer(
                                    stored,
                                    correctById[q.id].orEmpty().toSet(),
                                    q
                                )
                            } == true
                            if (changedAnswer) c.add(q.id) else d.add(q.id)
                        } else if (q.id in ids) {
                            c.add(q.id)
                        }
                    }
                    d to c
                }
                _state.update { it.copy(duplicateIds = dups, changedIds = changed) }
                Logger.d("IMPORTVM", "refreshDuplicates: ${questions.size} preview = " +
                    "${questions.size - dups.size - changed.size} new, ${changed.size} changed, " +
                    "${dups.size} duplicates")
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

    private fun fingerprint(text: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun loadJsonText(text: String) {
        viewModelScope.launch {
            // Fingerprint + parse off the main thread: multi-MB files froze it.
            val fp = withContext(Dispatchers.Default) { fingerprint(text) }
            if (fp == loadedDirectFp && _state.value.questions.isNotEmpty()) {
                Logger.d("IMPORTVM", "loadJsonText: same text already loaded " +
                    "(${_state.value.questions.size} questions), skipping reload to preserve in-memory edits")
                return@launch
            }
            loadedDirectFp = fp
            try {
                withContext(Dispatchers.Default) { parseAndLoad(text) }
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
     * warnings ride alongside the parse warnings.
     */
    fun loadDocx(bytes: ByteArray) {
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) {
                    com.mcqapp.data.docx.parseDocx(bytes)
                }
                Logger.i("IMPORTVM", "Converted Word file (${bytes.size} bytes) " +
                    "with ${parsed.warnings.size} docx warnings")
                loadJsonTextWithWarnings(parsed.json, parsed.warnings)
            } catch (e: Exception) {
                Logger.e("IMPORTVM", "Failed to parse Word file", e)
                _state.update { it.copy(loading = false, error = "Could not parse the Word file: ${e.message}") }
            }
        }
    }

    private suspend fun loadJsonTextWithWarnings(text: String, extraWarnings: List<String>) {
        val fp = withContext(Dispatchers.Default) { fingerprint(text) }
        if (fp == loadedDirectFp && _state.value.questions.isNotEmpty()) return
        loadedDirectFp = fp
        try {
            withContext(Dispatchers.Default) { parseAndLoad(text, extraWarnings) }
        } catch (e: Exception) {
            loadedDirectFp = null
            throw e
        }
    }

    private suspend fun parseAndLoad(text: String, extraWarnings: List<String> = emptyList()) {
        val file = LegacyParser.parse(text)
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
            paperTitle = paper.title,
            paperDescription = paper.description,
            durationMinutes = paper.durationMinutes,
            negativeMarking = paper.negativeMarking,
            categoryName = effectiveCategory,
            questions = allQuestions,
            parseWarnings = extraWarnings + backupNotice + file.warnings,
            originalFile = file
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
