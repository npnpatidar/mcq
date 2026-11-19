package com.mcqapp.ui.results

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mcqapp.McqApplication
import com.mcqapp.R
import com.mcqapp.data.export.ReportCsv
import com.mcqapp.data.export.ReportCsvHeader
import com.mcqapp.data.export.ReportCsvRow
import com.mcqapp.data.export.ReportPdf
import com.mcqapp.data.local.QuestionResultEntity
import com.mcqapp.data.local.ResultAggregates
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.ResultPageKey
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.NumberedResult
import com.mcqapp.domain.ReviewFilters
import com.mcqapp.domain.TestReport
import com.mcqapp.domain.TestReportBuilder
import com.mcqapp.domain.TestReportInput
import com.mcqapp.ui.Verdict
import com.mcqapp.util.AppStrings
import com.mcqapp.util.Logger
import androidx.paging.PagingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

data class ResultsUiState(
    val loading: Boolean = true,
    /** Set when the attempt could not be loaded, so the screen can say so. */
    val loadError: String? = null,
    val attempt: Attempt? = null,
    val bookmarked: Set<String> = emptySet(),
    /** Header tallies, one row from SQL instead of the whole result list. */
    val aggregates: ResultAggregates = ResultAggregates(),
    /** The derived report (section 11.14); null until the attempt loads. */
    val report: TestReport? = null,
    val filter: String = ReviewFilters.ALL,
    /** True while one export is running; the screen disables and shows it. */
    val exporting: Boolean = false
)

/**
 * A review of one attempt. The rows stream in as a paged list, so a 250k
 * attempt no longer maps every `QuestionResult` (element JSON and option JSON
 * included) into memory before showing the first card; only the header
 * tallies are aggregated, in SQL.
 *
 * The filter is persisted in the [savedStateHandle] supplied by the screen's
 * SavedState factory. That is the public production way to obtain a handle —
 * the no-arg `SavedStateHandle()` constructor is @VisibleForTesting and is not
 * bound to a registry owner, so it would not restore anything after process
 * death. It is optional only so plain callers (tests and the legacy factory)
 * can still build this ViewModel directly; on the real screen it is always
 * present, so the filter survives process death as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResultsViewModel(
    application: Application,
    private val attemptId: Long,
    /** Survives process death; rotation keeps the same ViewModel anyway. */
    private val savedStateHandle: SavedStateHandle? = null
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    /** Friendly, localized copy for the learner; exception text goes to the log only. */
    private fun msg(resId: Int): String = AppStrings.getString(getApplication<Application>(), resId)

    /** Only known keys are restored from saved state; a stale key falls back to All. */
    private val initialFilter: String =
        savedStateHandle?.get<String>(KEY_FILTER)?.takeIf { it in KNOWN_FILTERS }
            ?: ReviewFilters.ALL

    private val _state = MutableStateFlow(ResultsUiState(filter = initialFilter))
    val state: StateFlow<ResultsUiState> = _state.asStateFlow()

    private val request = MutableStateFlow(ResultsRequest(initialFilter))

    /** The live paging source; bookmark writes invalidate it in place (F14). */
    internal var liveSource: PagingSource<ResultPageKey, NumberedResult>? = null

    val pagingData: Flow<PagingData<NumberedResult>> = request
        .flatMapLatest { req ->
            // The pager is keyed on the filter alone: the saved set is read at
            // load time, and the bookmarks change signal invalidates the live
            // source, so a toggle refreshes the Saved chip in place instead of
            // rebuilding the pager and its scroll (consolidated_bugs.md F14).
            Pager(
                PagingConfig(
                    pageSize = RESULTS_PAGE,
                    initialLoadSize = RESULTS_PAGE,
                    enablePlaceholders = false
                )
            ) {
                repository.resultPagingSource(
                    attemptId,
                    req.filter,
                    {
                        _state.value.report?.timing?.slowThresholdSeconds
                            ?: ReviewFilters.DEFAULT_SLOW_SECONDS
                    },
                    { _state.value.bookmarked }
                ).also { liveSource = it }
            }.flow
        }
        .cachedIn(viewModelScope)

    /** Monotonic load counter so a stale in-flight load cannot overwrite a newer result. */
    private var loadGeneration = 0

    init {
        reload()
        // The bookmark SET is the single source of truth for the Saved chip.
        // The paging source reads it at load time, so we must update the set
        // BEFORE invalidating — a bare table-change signal can arrive first and
        // would reload against the previous set, showing a stale Saved list.
        // There is deliberately no `observeBookmarkChanges()` collector: that
        // is exactly the out-of-order signal this ordering removes.
        viewModelScope.launch {
            repository.observeBookmarks().collect { applyBookmarks(it) }
        }
    }

    /** Applies a fresh bookmark set, then (only then) refreshes the Saved list. */
    internal fun applyBookmarks(ids: List<String>) {
        val saved = ids.toSet()
        val leavingSaved = _state.value.filter == ReviewFilters.SAVED && saved.isEmpty()
        _state.update { it.copy(bookmarked = saved) }
        if (leavingSaved) {
            // An emptied Saved chip falls back to the whole attempt. The
            // request change rebuilds the pager against the fresh set.
            updateFilter(ReviewFilters.ALL)
            return
        }
        liveSource?.invalidate()
    }

    /** Loads the attempt. Exposed so a failed load can be retried. */
    fun reload() {
        val generation = ++loadGeneration
        viewModelScope.launch {
            try {
                _state.update { it.copy(loading = true, loadError = null) }
                val attempt = repository.getAttempt(attemptId)
                val aggregates = repository.getResultAggregates(attemptId)
                // One bounded attempt is at most 150 rows, so the report loads
                // one light projection and computes medians, the error mix and
                // the topics in Kotlin. The question review stays paged.
                val report = attempt?.let {
                    val rows = repository.getReportRows(attemptId)
                    val paper = repository.getPaper(it.paperId)
                    TestReportBuilder.build(
                        TestReportInput(
                            attempt = it,
                            rows = rows,
                            paper = paper,
                            comparableAttempts = repository.getComparableAttempts(it),
                            targetTopicIds = it.targetTopicIds
                                .split(",")
                                .filter { id -> id.isNotBlank() },
                            evidence = repository.getReportEvidence(it, rows, paper),
                            practiceHistory = repository.getPracticeHistory(it),
                            discrimination = repository.getItemDiscrimination(it)
                        )
                    )
                }
                Logger.i(
                    "RESULTVM",
                    "Loaded attempt '${attempt?.title}' with ${aggregates.total} results"
                )
                _state.update {
                    if (generation != loadGeneration) it
                    else it.copy(
                        loading = false,
                        attempt = attempt,
                        aggregates = aggregates,
                        report = report
                    )
                }
            } catch (e: Exception) {
                // Same defect A6 fixed for the test and study screens: leaving
                // `loading` true showed "Loading…" forever with no retry.
                Logger.e("RESULTVM", "Failed to load attempt $attemptId", e)
                _state.update {
                    if (generation != loadGeneration) it
                    else it.copy(loading = false, loadError = msg(R.string.final_result_load_failed))
                }
            }
        }
    }

    fun updateFilter(filter: String) {
        savedStateHandle?.set(KEY_FILTER, filter)
        _state.update { it.copy(filter = filter) }
        request.update { it.copy(filter = filter) }
    }

    fun toggleBookmark(questionId: String) {
        viewModelScope.launch {
            try {
                repository.toggleBookmark(questionId)
            } catch (e: Exception) {
                Logger.e("RESULTVM", "toggleBookmark($questionId) failed", e)
            }
        }
    }

    /** The only two things the screen may do after an export completes. */
    sealed interface ExportEvent {
        data class Share(val intent: Intent) : ExportEvent
        data object Failed : ExportEvent
    }

    /**
     * R1: the chooser is a one-shot UI side effect. The render used to run in
     * the screen's `rememberCoroutineScope`, which Compose cancels when the
     * composition leaves — rotation/Back mid-render killed the startActivity
     * line and left a partial file. The render now belongs to [viewModelScope]
     * and the screen only starts the activity it is handed here.
     */
    val exportEvents = OneShotEvents<ExportEvent>()

    /**
     * Only one export may run at a time. A double-tap on the menu (or a CSV and
     * a PDF tap in quick succession) used to write the same file from two
     * coroutines; [ExportGate] rejects every caller but the first, and the
     * screen disables the controls while [ResultsUiState.exporting] is set.
     */
    private val exportGate = ExportGate()

    fun exportCsv() = launchExport(::renderCsvIntent)

    fun exportPdf() = launchExport(::renderPdfIntent)

    private fun launchExport(render: suspend () -> Intent?) {
        viewModelScope.launch {
            if (!exportGate.tryAcquire()) {
                // Always answer the screen so its synchronous tap guard clears,
                // even in the rare race where the UI guard missed a second tap.
                exportEvents.send(ExportEvent.Failed)
                return@launch
            }
            _state.update { it.copy(exporting = true) }
            val intent = try {
                withContext(Dispatchers.IO) { render() }
            } catch (e: Exception) {
                Logger.e("RESULTVM", "Export failed", e)
                null
            } finally {
                _state.update { it.copy(exporting = false) }
                exportGate.release()
            }
            exportEvents.send(if (intent != null) ExportEvent.Share(intent) else ExportEvent.Failed)
        }
    }

    /** Slice 5: export the report as CSV and return a share intent, or null. */
    private suspend fun renderCsvIntent(): Intent? {
        val report = _state.value.report ?: return null
        return try {
            val entities = repository.getAttemptResultEntities(attemptId)
            val tagsById = repository.getQuestionTagsByIds(entities.map { it.questionId }.distinct())
            val rows = buildCsvRows(entities, tagsById)
            val header = ReportCsvHeader(
                attemptId = report.attempt.id,
                paperTitle = report.attempt.title,
                testType = report.type?.name ?: "LEGACY",
                family = report.family.name,
                exportedAtEpochMs = System.currentTimeMillis(),
                guessingCorrected = report.guessing?.correctedAccuracy
            )
            val file = reportFile("csv")
            FileOutputStream(file).use { fos ->
                OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                    ReportCsv.write(writer, header, rows)
                }
            }
            shareIntent(file, "text/csv")
        } catch (e: Exception) {
            Logger.e("RESULTVM", "CSV export failed", e)
            null
        }
    }

    /** Slice 5: export the report as a simple PDF and return a share intent. */
    private suspend fun renderPdfIntent(): Intent? {
        val report = _state.value.report ?: return null
        return try {
            val file = reportFile("pdf")
            FileOutputStream(file).use { out -> ReportPdf.write(report, out) }
            shareIntent(file, "application/pdf")
        } catch (e: Exception) {
            Logger.e("RESULTVM", "PDF export failed", e)
            null
        }
    }

    private fun reportFile(ext: String): File {
        val dir = File(getApplication<Application>().cacheDir, "reports").apply { mkdirs() }
        val attempt = _state.value.attempt
        val safeTitle = (attempt?.title ?: "report").replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
        return File(dir, "mcq-report-${safeTitle}-${attemptId}.$ext")
    }

    private fun shareIntent(file: File, mime: String): Intent {
        val app = getApplication<Application>()
        val uri: Uri = FileProvider.getUriForFile(
            app,
            app.packageName + ".fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private data class ResultsRequest(val filter: String)

    companion object {
        private const val RESULTS_PAGE = 40
        private const val KEY_FILTER = "results_filter"
        private val KNOWN_FILTERS = setOf(
            ReviewFilters.ALL,
            ReviewFilters.CORRECT,
            ReviewFilters.WRONG,
            ReviewFilters.SKIPPED,
            ReviewFilters.UNGRADED,
            ReviewFilters.SLOW,
            ReviewFilters.SAVED
        )
    }
}

/**
 * A one-slot latch for exports. Kept as a small pure class so the double-tap
 * guard can be tested without a ViewModel, a database or a share intent.
 */
internal class ExportGate {
    private val busy = AtomicBoolean(false)

    val isBusy: Boolean get() = busy.get()

    /** True only for the first caller until [release]. */
    fun tryAcquire(): Boolean = busy.compareAndSet(false, true)

    fun release() {
        busy.set(false)
    }
}

/**
 * Maps stored result rows to CSV verdicts. Kept as a pure function so the
 * verdict mapping the export writes can be pinned by a test without going
 * through FileProvider and a share intent.
 *
 * An ungraded question (no answer key at the time of the attempt) is reported
 * as "ungraded" even when the learner answered it — the result screen shows
 * the same "Not scored" tag for it, so calling it "wrong" in the export would
 * contradict what the learner saw. The ungraded check comes first so a
 * selected option on an ungraded question cannot be mislabelled.
 */
internal fun buildCsvRows(
    entities: List<QuestionResultEntity>,
    tagsById: Map<String, String>
): List<ReportCsvRow> = entities.mapIndexed { index, entity ->
    // Same predicate as the on-screen verdict and the report: one place decides
    // what ungraded / skipped / correct / wrong mean.
    val result = Verdict.of(
        entity.correctOptionIds,
        entity.selectedOptionIds,
        entity.isCorrect
    ).csvValue
    ReportCsvRow(
        number = index + 1,
        questionId = entity.questionId,
        category = entity.categoryTitle,
        result = result,
        dwellSeconds = entity.dwellSeconds,
        tags = tagsById[entity.questionId].orEmpty()
    )
}
