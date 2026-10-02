package com.mcqapp.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.data.anki.AnkiPackageException
import com.mcqapp.data.anki.AnkiPackageReader
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.io.ImportReport
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Paper
import com.mcqapp.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    val papers: StateFlow<List<Paper>> = repository.observePapers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _mistakeCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val mistakeCounts: StateFlow<Map<String, Int>> = _mistakeCounts.asStateFlow()

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

    private val _studyCounts = MutableStateFlow<Map<String, com.mcqapp.data.repository.StudyCounts>>(
        emptyMap()
    )
    val studyCounts: StateFlow<Map<String, com.mcqapp.data.repository.StudyCounts>> =
        _studyCounts.asStateFlow()

    /** Re-reads due counts, e.g. after returning from a study session. */
    fun refreshStudyCounts() {
        viewModelScope.launch {
            try {
                _studyCounts.value = papers.value.associate { paper ->
                    paper.id to repository.getStudyCounts(paper.id)
                }
            } catch (e: Exception) {
                Logger.e("LIBVM", "refreshStudyCounts failed", e)
            }
        }
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
                val json = Exporter(repository.db()).exportAll()
                writeUri(uri, json)
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
                val result = com.mcqapp.data.export.PaperExporter(repository.db())
                    .exportPaper(paperId, format, twoColumnPdf = repository.pdfTwoColumn().first())
                Logger.i("LIBVM", "Exported ${result.fileName} (${result.bytes.size} bytes)")
                writeUriBytes(uri, result.bytes)
                _exportError.value = null
            } catch (e: Exception) {
                Logger.e("LIBVM", "Export failed", e)
                _exportError.value = "Export failed: ${e.message}"
            }
        }
    }

    fun exportCategoryAs(
        uri: Uri,
        paperId: String,
        categoryId: String,
        format: com.mcqapp.data.export.ExportFormat
    ) {
        viewModelScope.launch {
            try {
                val result = com.mcqapp.data.export.PaperExporter(repository.db())
                    .exportCategory(paperId, categoryId, format, twoColumnPdf = repository.pdfTwoColumn().first())
                Logger.i("LIBVM", "Exported ${result.fileName} (${result.bytes.size} bytes)")
                writeUriBytes(uri, result.bytes)
                _exportError.value = null
            } catch (e: Exception) {
                Logger.e("LIBVM", "Export failed", e)
                _exportError.value = "Export failed: ${e.message}"
            }
        }
    }

    private suspend fun writeUriBytes(uri: Uri, bytes: ByteArray) {
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
            it.write(bytes)
        } ?: throw IllegalStateException("Could not open output stream")
    }

    private suspend fun writeUri(uri: Uri, text: String) {
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
            it.write(text.toByteArray())
        } ?: throw IllegalStateException("Could not open output stream")
    }

    fun dismissError() {
        _exportError.value = null
    }

    fun duplicatePaper(paperId: String) {
        viewModelScope.launch {
            try {
                repository.duplicatePaper(paperId)
                Logger.i("LIBVM", "Duplicated paper $paperId")
            } catch (e: Exception) {
                Logger.e("LIBVM", "Duplicate failed", e)
                _exportError.value = "Duplicate failed: ${e.message}"
            }
        }
    }

    fun deletePaper(paperId: String) {
        viewModelScope.launch {
            try {
                repository.deletePaper(paperId)
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
     * Imports an Anki `.apkg` straight into the database. Packages are read
     * whole — a deck can hold thousands of notes — so this skips the Import
     * screen's single-paper preview, which only makes sense for one hand-written
     * JSON file. Merge decisions are the same ones that path makes: papers match
     * by id then title, questions by content hash.
     */
    fun importAnkiPackage(uri: Uri) {
        importAnkiPackage(uri.lastPathSegment?.substringAfterLast('/') ?: "deck.apkg") {
            getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { it.readBytes() }
        }
    }

    /**
     * Imports a package whose bytes were already read while sniffing the
     * picked file's format, so the archive is not fetched twice. [read]
     * supplies the bytes lazily and may run on any dispatcher.
     */
    fun importAnkiPackage(title: String, read: suspend () -> ByteArray?) {
        viewModelScope.launch {
            try {
                Logger.i("LIBVM", "Reading Anki package '$title'")
                val bytes = withContext(Dispatchers.IO) { read() }
                    ?: throw AnkiPackageException("Could not open $title.")

                val read = withContext(Dispatchers.IO) { AnkiPackageReader.read(bytes) }
                Logger.i(
                    "LIBVM",
                    "Package '${title}': ${read.deckCount} decks, ${read.noteCount} notes, " +
                        "${read.recallCount} recall, ${read.file.papers.size} papers"
                )

                val report = Importer(repository.db(), repository.updateAnswersOnDuplicate().first())
                    .import(read.file, read.scheduling)
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
