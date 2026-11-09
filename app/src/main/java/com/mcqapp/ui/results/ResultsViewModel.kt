package com.mcqapp.ui.results

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mcqapp.McqApplication
import com.mcqapp.data.export.ReportCsv
import com.mcqapp.data.export.ReportCsvHeader
import com.mcqapp.data.export.ReportCsvRow
import com.mcqapp.data.export.ReportPdf
import com.mcqapp.data.local.ResultAggregates
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.ResultPageKey
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.NumberedResult
import com.mcqapp.domain.ReviewFilters
import com.mcqapp.domain.TestReport
import com.mcqapp.domain.TestReportBuilder
import com.mcqapp.domain.TestReportInput
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

data class ResultsUiState(
    val loading: Boolean = true,
    /** Set when the attempt could not be loaded, so the screen can say so. */
    val loadError: String? = null,
    val attempt: Attempt? = null,
    val bookmarked: Set<String> = emptySet(),
    /** Header tallies, one row from SQL instead of the whole result list. */
    val aggregates: ResultAggregates = ResultAggregates(),
    val categoryBreakdown: List<Pair<String, Pair<Int, Int>>> = emptyList(),
    /** The derived report (section 11.14); null until the attempt loads. */
    val report: TestReport? = null,
    val filter: String = ReviewFilters.ALL
)

/**
 * A review of one attempt. The rows stream in as a paged list, so a 250k
 * attempt no longer maps every `QuestionResult` (element JSON and option JSON
 * included) into memory before showing the first card; only the header
 * tallies and category breakdown are aggregated, in SQL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResultsViewModel(
    application: Application,
    private val attemptId: Long
) : AndroidViewModel(application) {

    private val repository: McqRepository = (application as McqApplication).repository

    private val _state = MutableStateFlow(ResultsUiState())
    val state: StateFlow<ResultsUiState> = _state.asStateFlow()

    private val request = MutableStateFlow(ResultsRequest(ReviewFilters.ALL))

    /** The live paging source; bookmark writes invalidate it in place (F14). */
    private var liveSource: PagingSource<ResultPageKey, NumberedResult>? = null

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
        viewModelScope.launch {
            repository.observeBookmarkChanges().collect { liveSource?.invalidate() }
        }
        viewModelScope.launch {
            repository.observeBookmarks().collect { ids ->
                val saved = ids.toSet()
                _state.update { it.copy(bookmarked = saved) }
                // The paging source re-reads the set per load and invalidates
                // on this very table, so nothing needs re-keying here; only
                // an emptied Saved chip falls back to the whole attempt.
                if (saved.isEmpty() && _state.value.filter == ReviewFilters.SAVED) {
                    updateFilter(ReviewFilters.ALL)
                }
            }
        }
    }

    /** Loads the attempt. Exposed so a failed load can be retried. */
    fun reload() {
        val generation = ++loadGeneration
        viewModelScope.launch {
            try {
                _state.update { it.copy(loading = true, loadError = null) }
                val attempt = repository.getAttempt(attemptId)
                val aggregates = repository.getResultAggregates(attemptId)
                val breakdown = repository.getResultCategoryBreakdown(attemptId)
                    .map { it.title to (it.correct to it.graded) }
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
                        categoryBreakdown = breakdown,
                        report = report
                    )
                }
            } catch (e: Exception) {
                // Same defect A6 fixed for the test and study screens: leaving
                // `loading` true showed "Loading…" forever with no retry.
                Logger.e("RESULTVM", "Failed to load attempt $attemptId", e)
                _state.update {
                    if (generation != loadGeneration) it
                    else it.copy(loading = false, loadError = "Could not load this result: ${e.message}")
                }
            }
        }
    }

    fun updateFilter(filter: String) {
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

    /** Slice 5: export the report as CSV and return a share intent, or null. */
    suspend fun exportCsvIntent(): Intent? = withContext(Dispatchers.IO) {
        val report = _state.value.report ?: return@withContext null
        try {
            val entities = repository.getAttemptResultEntities(attemptId)
            val tagsById = repository.getQuestionTagsByIds(entities.map { it.questionId }.distinct())
            val rows = entities.mapIndexed { index, entity ->
                val result = when {
                    entity.selectedOptionIds.isBlank() -> "skipped"
                    entity.isCorrect -> "correct"
                    else -> "wrong"
                }
                ReportCsvRow(
                    number = index + 1,
                    questionId = entity.questionId,
                    category = entity.categoryTitle,
                    result = result,
                    dwellSeconds = entity.dwellSeconds,
                    tags = tagsById[entity.questionId].orEmpty()
                )
            }
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
    suspend fun exportPdfIntent(): Intent? = withContext(Dispatchers.IO) {
        val report = _state.value.report ?: return@withContext null
        try {
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
    }
}
