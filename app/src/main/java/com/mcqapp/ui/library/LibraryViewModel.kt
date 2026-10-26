package com.mcqapp.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.anki.AnkiPackageException
import com.mcqapp.data.io.AnkiApkgImporter
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.io.ImportReport
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CategoryCounts
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.Paper
import com.mcqapp.util.Logger
import com.mcqapp.util.displayName
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    /**
     * The export the format dialog and the system save dialog are working on.
     *
     * Held in the VM: the save dialog recreates the activity, and composition
     * state came back all-null, so the re-delivered URI fell through to
     * [exportAll] — the whole library written to the file the user picked for
     * one paper or selection (consolidated_bugs.md F2).
     */
    data class PendingExportTarget(
        val paperId: String? = null,
        val selection: Triple<String, List<String>, Int>? = null,
        val format: com.mcqapp.data.export.ExportFormat? = null
    )

    private val _pendingExport = MutableStateFlow<PendingExportTarget?>(null)
    val pendingExport: StateFlow<PendingExportTarget?> = _pendingExport.asStateFlow()

    fun setPendingExport(target: PendingExportTarget?) {
        _pendingExport.value = target
    }

    fun consumePendingExport(): PendingExportTarget? {
        val target = _pendingExport.value
        _pendingExport.value = null
        return target
    }

    /**
     * Classifies a picked import file and hands the result to the callbacks.
     *
     * This runs in [viewModelScope]: the composition scope the picker callback
     * used to run in is cancelled by the recreation a picker can itself
     * trigger, silently dropping the pick with no error and no navigation
     * (consolidated_bugs.md F13).
     *
     * Off the main thread and size-capped: the pick used to be read with
     * readBytes() on the UI thread, which ANR'd or died on OOM — a failure
     * `catch (Exception)` does not cover.
     */
    fun onImportPicked(
        picked: Uri,
        contentResolver: android.content.ContentResolver,
        onDocx: (bytes: ByteArray, name: String) -> Unit,
        onJsonUri: (Uri) -> Unit,
        onJsonBytes: (ByteArray) -> Unit
    ) {
        viewModelScope.launch {
            // Read a bounded prefix first. It is enough to tell a zip from
            // JSON and to keep a small bank in memory, but it never allocates
            // the whole large bank: a bigger JSON is streamed from the Uri.
            val prefix = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contentResolver.openInputStream(picked)?.use { stream ->
                        com.mcqapp.util.readPrefix(
                            stream,
                            com.mcqapp.ui.importscreen.JSON_STREAM_THRESHOLD_BYTES.toLong()
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.e("LIB", "Failed to read import file", e)
                showError("Could not read the file: ${e.message}")
                return@launch
            } ?: run {
                showError("Could not read the file.")
                return@launch
            }

            // Route by content, not by name or MIME type (pickers report
            // both inconsistently). Both .apkg and .docx are zips, so a
            // .docx is told apart by its word/document.xml entry; our
            // JSON can arrive under any extension and stays on the text
            // path below.
            val isZip = prefix.bytes.size >= 2 && prefix.bytes[0] == 'P'.code.toByte() &&
                prefix.bytes[1] == 'K'.code.toByte()

            if (isZip) {
                // Classify by streaming the archive's entry names, so the file
                // is never read whole. Both .apkg and .docx are zips; a Word
                // file is told apart by its word/document.xml entry, a backup
                // by its manifest. A backup with images in it is often over the
                // old size cap — "too large to import" would be the wrong thing
                // to tell someone who picked a file that was never meant to be
                // imported at all.
                val kind = try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        contentResolver.openInputStream(picked)?.use { stream ->
                            com.mcqapp.ui.library.classifyPickedArchive(stream)
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("LIB", "Failed to read import file", e)
                    showError("Could not read the file: ${e.message}")
                    return@launch
                } ?: run {
                    showError("Could not read the file.")
                    return@launch
                }
                when (kind) {
                    com.mcqapp.ui.library.PickedArchive.DOCX -> {
                        // A Word bank is small and the parser wants its bytes,
                        // so read it now, under the same cap as before.
                        val bytes = try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                contentResolver.openInputStream(picked)?.use { stream ->
                                    com.mcqapp.util.readBounded(stream)
                                }
                            }
                        } catch (e: Exception) {
                            Logger.e("LIB", "Failed to read import file", e)
                            showError(
                                if (e is com.mcqapp.util.ImportTooLargeException) {
                                    com.mcqapp.util.tooLargeMessage(
                                        e.atLeastBytes,
                                        com.mcqapp.util.MAX_IMPORT_BYTES
                                    )
                                } else {
                                    "Could not read the file: ${e.message}"
                                }
                            )
                            return@launch
                        } ?: run {
                            showError("Could not read the file.")
                            return@launch
                        }
                        Logger.i("LIB", "picked Word file: bytes=${bytes.size}")
                        // The file name becomes the paper title (the document
                        // rarely names its own paper). The provider's display
                        // name, not the opaque row id a content Uri carries in
                        // lastPathSegment.
                        onDocx(bytes, contentResolver.displayName(picked) ?: "")
                    }
                    com.mcqapp.ui.library.PickedArchive.BACKUP -> {
                        // Restore is the reverse of export: it replaces the
                        // whole library, while import only adds papers.
                        Logger.i("LIB", "picked library backup: not an import")
                        showError(backupNotABankMessage())
                    }
                    com.mcqapp.ui.library.PickedArchive.OTHER -> {
                        // The deck is streamed straight from the Uri by the
                        // importer, so the archive is never held as bytes.
                        val title = contentResolver.displayName(picked) ?: "deck.apkg"
                        importAnkiPackage(title) {
                            contentResolver.openInputStream(picked)
                        }
                    }
                }
                return@launch
            }

            if (prefix.truncated) {
                // Too large to hold as bytes: the import screen re-opens the
                // Uri, previews a bounded sample and streams the rest.
                Logger.i("LIB", "picked large import file: streaming from uri")
                onJsonUri(picked)
            } else {
                // Bytes stay bytes: the String copy that used to be made here
                // doubled the bank in memory before the parser ever saw it.
                val bytes = prefix.bytes
                val fingerprint = try {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                    digest.joinToString("") { "%02x".format(it) }.take(12)
                } catch (e: Exception) {
                    "unknown"
                }
                Logger.i("LIB", "picked import file: bytes=${bytes.size}, sha=$fingerprint")
                onJsonBytes(bytes)
            }
        }
    }

    /** The "picked a backup" message, shared with the import path. */
    private fun backupNotABankMessage(): String =
        com.mcqapp.ui.library.backupNotABankMessage()

    val papers: StateFlow<List<Paper>> = repository.observePapers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _mistakeCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val mistakeCounts: StateFlow<Map<String, Int>> = _mistakeCounts.asStateFlow()

    // Due counts move whenever a study session rewrites a schedule, so they
    // are recomputed with the rest of the badges. Declared BEFORE the init
    // block: viewModelScope dispatches with Main.immediate, so the papers
    // StateFlow's first emission runs synchronously inside the constructor
    // and a later declaration would still be null at that point (the
    // every-startup "getStudyCounts failed" NPE).
    private val _studyCounts = MutableStateFlow<Map<String, com.mcqapp.data.repository.StudyCounts>>(
        emptyMap()
    )
    val studyCounts: StateFlow<Map<String, com.mcqapp.data.repository.StudyCounts>> =
        _studyCounts.asStateFlow()

    // One selection per paper, owned here (D2): the drawer's own remember map
    // died on rotation and could not be shared with the card, and two owners
    // for one paper is the bug class category_study.md §7.1.1 fixed.
    private val _selection = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val selection: StateFlow<Map<String, Set<String>>> = _selection.asStateFlow()

    // Per-category counts, loaded lazily per expanded paper (D4): the library
    // must not pay two grouped queries per paper on every startup, only when
    // a card or drawer tree is actually opened.
    private val _categoryCounts =
        MutableStateFlow<Map<String, Map<String, CategoryCounts>>>(emptyMap())
    val categoryCounts: StateFlow<Map<String, Map<String, CategoryCounts>>> =
        _categoryCounts.asStateFlow()

    fun selectedFor(paperId: String): Set<String> = _selection.value[paperId].orEmpty()

    fun toggleCategory(paperId: String, categoryId: String) {
        val paper = papers.value.firstOrNull { it.id == paperId } ?: return
        val next = CategorySelection.toggle(paper.categories, selectedFor(paperId), categoryId)
        _selection.value = _selection.value + (paperId to next)
    }

    /** Loads a paper's per-category counts once; a second expand is free. */
    fun ensureCategoryCounts(paperId: String) {
        if (_categoryCounts.value.containsKey(paperId)) return
        viewModelScope.launch {
            try {
                _categoryCounts.value =
                    _categoryCounts.value + (paperId to repository.categoryCounts(paperId))
            } catch (e: Exception) {
                Logger.e("LIBVM", "categoryCounts failed", e)
            }
        }
    }

    init {
        viewModelScope.launch {
            repository.observeAttempts().collect {
                try {
                    _mistakeCounts.value = repository.getMistakeCounts()
                } catch (e: Exception) {
                    Logger.e("LIBVM", "getMistakeCounts failed", e)
                }
            }
        }
        viewModelScope.launch {
            // Due counts move whenever a study session rewrites a schedule,
            // so they are recomputed with the rest of the badges.
            papers.collect { list ->
                try {
                    val counts = list.associate { paper ->
                        paper.id to repository.getStudyCounts(paper.id)
                    }
                    _studyCounts.value = counts
                } catch (e: Exception) {
                    Logger.e("LIBVM", "getStudyCounts failed", e)
                }
            }
        }
    }

    /** Re-reads due counts, e.g. after returning from a study session. */
    fun refreshStudyCounts() {
        viewModelScope.launch {
            try {
                _studyCounts.value = papers.value.associate { paper ->
                    paper.id to repository.getStudyCounts(paper.id)
                }
                // The papers whose trees are open show per-category counts too:
                // re-read only those, so an unexpanded library pays nothing.
                val cached = _categoryCounts.value.keys
                if (cached.isNotEmpty()) {
                    _categoryCounts.value = _categoryCounts.value + cached.associate { paperId ->
                        paperId to repository.categoryCounts(paperId)
                    }
                }
            } catch (e: Exception) {
                Logger.e("LIBVM", "refreshStudyCounts failed", e)
            }
        }
    }

    /** Paper awaiting delete confirmation, with what deleting it would cost. */
    data class PendingDelete(
        val paperId: String,
        val title: String,
        val impact: com.mcqapp.data.repository.McqRepository.DeleteImpact
    )

    private val _pendingDelete = MutableStateFlow<PendingDelete?>(null)
    val pendingDelete: StateFlow<PendingDelete?> = _pendingDelete.asStateFlow()

    /** Loads the impact and asks for confirmation. Deletion never happens here. */
    fun requestDelete(paperId: String, title: String) {
        viewModelScope.launch {
            try {
                _pendingDelete.value = PendingDelete(
                    paperId = paperId,
                    title = title,
                    impact = repository.deleteImpact(paperId)
                )
            } catch (e: Exception) {
                Logger.e("LIBVM", "deleteImpact failed", e)
                _exportError.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    /** Surfaces a failure in the library screen's error dialog. */
    fun showError(message: String) {
        _exportError.value = message
    }

    fun exportAll(uri: Uri) {
        viewModelScope.launch {
            try {
                // Streamed: the whole bank never lives in memory as a String.
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    Exporter(repository.db()).exportAllTo(
                        java.io.BufferedWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8), 1 shl 14)
                    )
                } ?: throw IllegalStateException("Could not open output stream")
                _exportError.value = null
            } catch (e: Exception) {
                _exportError.value = "Export failed: ${e.message}"
            }
        }
    }

    fun exportPaper(uri: Uri, paperId: String) {
        exportPaperAs(uri, paperId, com.mcqapp.data.export.ExportFormat.JSON_INLINE)
    }

    fun exportPaperAs(uri: Uri, paperId: String, format: com.mcqapp.data.export.ExportFormat) {
        viewModelScope.launch {
            try {
                val file = writeExport(uri) { sink ->
                    com.mcqapp.data.export.PaperExporter(repository.db())
                        .exportPaper(paperId, format, sink, twoColumnPdf = repository.pdfTwoColumn().first())
                }
                Logger.i("LIBVM", "Exported ${file.fileName}")
                _exportError.value = null
            } catch (e: Exception) {
                Logger.e("LIBVM", "Export failed", e)
                _exportError.value = "Export failed: ${e.message}"
            }
        }
    }

    fun exportSelectionAs(
        uri: Uri,
        paperId: String,
        categoryIds: List<String>,
        format: com.mcqapp.data.export.ExportFormat
    ) {
        viewModelScope.launch {
            try {
                val file = writeExport(uri) { sink ->
                    com.mcqapp.data.export.PaperExporter(repository.db())
                        .exportSelection(paperId, categoryIds, format, sink, twoColumnPdf = repository.pdfTwoColumn().first())
                }
                Logger.i("LIBVM", "Exported ${file.fileName}")
                _exportError.value = null
            } catch (e: Exception) {
                Logger.e("LIBVM", "Export failed", e)
                _exportError.value = "Export failed: ${e.message}"
            }
        }
    }

    /**
     * Streams one export straight into [uri]: the exporter renders into the
     * resolver's stream, so the finished file is never held in memory here.
     */
    private suspend fun writeExport(
        uri: Uri,
        export: suspend (java.io.OutputStream) -> com.mcqapp.data.export.ExportFile
    ): com.mcqapp.data.export.ExportFile =
        (
            getApplication<Application>().contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open output stream")
            ).use { export(it) }

    private suspend fun writeUri(uri: Uri, text: String) {
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
            it.write(text.toByteArray())
        } ?: throw IllegalStateException("Could not open output stream")
    }

    fun dismissError() {
        _exportError.value = null
    }

    fun deletePaper(paperId: String) {
        viewModelScope.launch {
            try {
                repository.deletePaper(paperId)
                // A deleted paper's selection and counts are dead weight, and a
                // stale count map would resurrect them on the next expansion.
                _selection.value = _selection.value - paperId
                _categoryCounts.value = _categoryCounts.value - paperId
            } catch (e: Exception) {
                // Unguarded, a database error here escaped viewModelScope and
                // took the app down on an ordinary tap.
                Logger.e("LIBVM", "Delete paper failed", e)
                _exportError.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun addPaper(title: String, description: String, durationMinutes: Int, negativeMarking: Double) {
        viewModelScope.launch {
            try {
                val id = "paper-" + System.currentTimeMillis().toString(36)
                repository.savePaper(
                    Paper(
                        id = id,
                        title = title,
                        description = description,
                        // Clamped at the point of entry too, not just on import.
                        durationMinutes = com.mcqapp.domain.ExamTiming.minutesFrom(durationMinutes),
                        negativeMarking = negativeMarking
                    )
                )
            } catch (e: Exception) {
                Logger.e("LIBVM", "Add paper failed", e)
                _exportError.value = "Could not add the paper: ${e.message}"
            }
        }
    }

    /**
     * Rewrites a paper's details (title, description, duration, negative
     * marking) in place. [savePaper] upserts while keeping the paper's
     * categories and questions, so an edit never orphans a bank's content.
     */
    fun updatePaper(
        paperId: String,
        title: String,
        description: String,
        durationMinutes: Int,
        negativeMarking: Double
    ) {
        viewModelScope.launch {
            try {
                val current = repository.getPaper(paperId)
                if (current == null) {
                    _exportError.value = "Could not update the paper: it no longer exists"
                    return@launch
                }
                repository.savePaper(
                    current.copy(
                        title = title,
                        description = description,
                        durationMinutes = com.mcqapp.domain.ExamTiming.minutesFrom(durationMinutes),
                        negativeMarking = negativeMarking
                    )
                )
            } catch (e: Exception) {
                Logger.e("LIBVM", "Update paper failed", e)
                _exportError.value = "Could not update the paper: ${e.message}"
            }
        }
    }

    fun addCategory(paperId: String, title: String, parentId: String?) {
        viewModelScope.launch {
            try {
                repository.addCategory(paperId, title, parentId)
            } catch (e: Exception) {
                Logger.e("LIBVM", "Add category failed", e)
                _exportError.value = "Could not add the category: ${e.message}"
            }
        }
    }

    /** Nests a category into the sibling above it (the outline -> gesture). */
    fun nestCategory(categoryId: String) {
        viewModelScope.launch {
            try {
                repository.nestCategoryIntoSibling(categoryId)
            } catch (e: Exception) {
                Logger.e("LIBVM", "Nest category failed", e)
                _exportError.value = "Nest failed: ${e.message}"
            }
        }
    }

    /** Promotes a category out of its parent (the outline <- gesture). */
    fun promoteCategory(categoryId: String) {
        viewModelScope.launch {
            try {
                repository.promoteCategoryOutOfParent(categoryId)
            } catch (e: Exception) {
                Logger.e("LIBVM", "Promote category failed", e)
                _exportError.value = "Promote failed: ${e.message}"
            }
        }
    }

    /** The edit dialog's save: rename, and re-parent when it changed. */
    fun editCategory(categoryId: String, title: String, parentId: String?) {
        viewModelScope.launch {
            try {
                repository.renameCategory(categoryId, title)
                repository.reparentCategory(categoryId, parentId)
            } catch (e: Exception) {
                Logger.e("LIBVM", "Edit category failed", e)
                _exportError.value = "Edit failed: ${e.message}"
            }
        }
    }

    fun moveCategory(categoryId: String, delta: Int) {
        viewModelScope.launch {
            try {
                repository.moveCategory(categoryId, delta)
            } catch (e: Exception) {
                Logger.e("LIBVM", "Move category failed", e)
                _exportError.value = "Move failed: ${e.message}"
            }
        }
    }

    fun deleteCategory(categoryId: String) {
        Logger.i("LIBVM", "deleteCategory($categoryId)")
        viewModelScope.launch {
            try {
                repository.deleteCategory(categoryId)
            } catch (e: Exception) {
                Logger.e("LIBVM", "Delete category failed", e)
                _exportError.value = "Delete failed: ${e.message}"
            }
        }
    }

    private val _importReport = MutableStateFlow<ImportReport?>(null)
    val importReport: StateFlow<ImportReport?> = _importReport.asStateFlow()

    private val _importReportTitle = MutableStateFlow<String?>(null)
    val importReportTitle: StateFlow<String?> = _importReportTitle.asStateFlow()

    fun dismissImportReport() {
        _importReport.value = null
        _importReportTitle.value = null
    }

    /**
     * Imports an Anki `.apkg` straight into the database. The package is read
     * from [source] as it streams — neither the archive nor the bank is held
     * whole — so a deck of any size within the archive caps imports the same
     * way. Merge decisions are the same ones the JSON path makes: papers match
     * by id then title, questions by content hash.
     */
    fun importAnkiPackage(uri: Uri) {
        importAnkiPackage(uri.lastPathSegment?.substringAfterLast('/') ?: "deck.apkg") {
            getApplication<Application>().contentResolver.openInputStream(uri)
        }
    }

    /**
     * Imports a package read from [source], which is opened once (on the IO
     * dispatcher) and must return a fresh stream over the file.
     */
    fun importAnkiPackage(title: String, source: () -> InputStream?) {
        viewModelScope.launch {
            try {
                Logger.i("LIBVM", "Reading Anki package '$title'")
                val report = AnkiApkgImporter(
                    repository.db(),
                    repository.updateAnswersOnDuplicate().first()
                ).import({
                    source() ?: throw AnkiPackageException("Could not open $title.")
                })
                Logger.i(
                    "LIBVM",
                    "Anki import done: ${report.newPapers} new, ${report.updatedPapers} updated papers, " +
                        "${report.newQuestions} new, ${report.updatedQuestions} updated, " +
                        "${report.duplicateQuestions} duplicate questions, " +
                        "${report.restoredSchedules} schedules"
                )
                _importReport.value = report
                _importReportTitle.value = title
            } catch (e: AnkiPackageException) {
                // Already phrased for the user; these are format problems a user can act on
                // (export the deck as .apkg), not a bug.
                Logger.e("LIBVM", "Anki package rejected", e)
                _exportError.value = e.message
            } catch (e: Exception) {
                Logger.e("LIBVM", "Anki import failed", e)
                _exportError.value = "Import failed: ${e.message}"
            }
        }
    }

    fun loadSampleData() {
        Logger.i("LIBVM", "Loading sample data from assets")
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val text = context.assets.open("sample_paper.json").bufferedReader().use { it.readText() }
                Logger.d("LIBVM", "Read sample JSON (${text.length} chars), importing")
                val file = LegacyParser.parse(text)
                val report = Importer(repository.db(), repository.updateAnswersOnDuplicate().first()).import(file)
                Logger.i("LIBVM", "Sample data loaded: ${report.newPapers} papers, ${report.newQuestions} questions")
            } catch (e: Exception) {
                Logger.e("LIBVM", "Sample load failed", e)
                _exportError.value = "Sample load failed: ${e.message}"
            }
        }
    }
}
