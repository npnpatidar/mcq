package com.mcqapp.data.repository

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.chunks
import com.mcqapp.data.local.countUngradedForCategoriesChunked
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.local.minBlockSizeForCategoriesChunked
import com.mcqapp.data.local.minBlockSizeForPaper
import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.local.TagCount
import com.mcqapp.data.local.PanelEntity
import com.mcqapp.data.local.PanelQuestionEntity
import com.mcqapp.data.local.BlueprintEntity
import com.mcqapp.data.local.BlueprintSectionEntity
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.getQuestionIdsWithKeysChunked
import com.mcqapp.data.local.getSessionRefsByIdsChunked
import com.mcqapp.data.local.getTagsByIdsChunked
import com.mcqapp.data.local.bookmarkedIdsInScopeChunked
import com.mcqapp.data.local.dwellSamplesInScopeChunked
import com.mcqapp.data.local.questionIdsForTagChunked
import com.mcqapp.data.local.tagCountsChunked
import com.mcqapp.domain.FsrsFitResult
import com.mcqapp.domain.Blueprint
import com.mcqapp.domain.BlueprintSection
import com.mcqapp.domain.BlueprintSectionView
import com.mcqapp.domain.BlueprintSummary
import com.mcqapp.domain.PlanBlueprint
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Panel
import com.mcqapp.domain.PanelSummary
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.domain.SessionOrder
import com.mcqapp.domain.TestAnswer
import com.mcqapp.domain.TestSnapshot
import com.mcqapp.domain.signals.Signals
import com.mcqapp.domain.signals.TestSelector
import com.mcqapp.domain.signals.Composition
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.searchableText
import com.mcqapp.domain.stats.CardsData
import com.mcqapp.domain.stats.ExamCategoryTotal
import com.mcqapp.domain.stats.ExamCounterTotals
import com.mcqapp.domain.stats.ExamDwellTotals
import com.mcqapp.domain.stats.ExamGroup
import com.mcqapp.domain.stats.ExamComparisonKey
import com.mcqapp.domain.stats.ExamStats
import com.mcqapp.domain.stats.ExamStatsBuilder
import com.mcqapp.domain.stats.GuessingData
import com.mcqapp.domain.stats.MemoryData
import com.mcqapp.domain.stats.OverviewData
import com.mcqapp.domain.stats.ProgressData
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.RetentionData
import com.mcqapp.domain.stats.SessionsData
import com.mcqapp.domain.stats.StatsAggregator
import com.mcqapp.domain.stats.WorkloadData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How many times a config read retries when it observes `gradingVersion == 0`.
 * That state only exists between a Reset's id removal and its replacement
 * row, both of which the retrying call itself writes, so three attempts is
 * already generous; more would only hide a real write failure.
 */
private const val SCHEDULER_CONFIG_READ_ATTEMPTS = 3

/**
 * Facade over the repository's collaborators, keeping the public API every
 * ViewModel (and the JVM tests) already programs against stable:
 *
 * - [SettingsStore] — DataStore preferences, scheduler options, resume snapshot
 * - [TestProgressStore] — the resume snapshot's answers, one row per answer
 * - [QuestionContentMapper] — entity <-> domain mapping and the content hash
 * - [QuestionStore] — question CRUD and bulk operations
 * - [PaperStore] — papers, categories, the library tree, paper deletion
 * - [StudyStore] — SM-2 card state, study queue, badge counts
 * - [HistoryStore] — bookmarks, attempts, mistakes
 *
 * Every method here is a one-line forward unless it belongs to no collaborator
 * (cross-paper search, the storage report), so this file stays a table of
 * contents rather than a second implementation.
 *
 * Freshness for the first-start grading-rule seed is always the database's
 * answer: [InstallFreshness] probes `AppDatabase.hasNoPapersOrReviews` and
 * persists its one-shot decision. There is deliberately no constructor that
 * silently means "never seed"; tests that need a forced answer use the
 * [VisibleForTesting] boolean or [InstallFreshness] seam.
 */
class McqRepository internal constructor(
    private val db: AppDatabase,
    private val context: Context,
    internal val installFreshness: InstallFreshness
) {

    /** The production wiring: a new install is one whose database is empty. */
    constructor(db: AppDatabase, context: Context) : this(
        db,
        context,
        InstallFreshness(context) { db.hasNoPapersOrReviews() }
    )

    /**
     * Pins the first-start answer for a test without a database (or one whose
     * content cannot express the desired answer). Production must not use it.
     */
    @VisibleForTesting
    internal constructor(db: AppDatabase, context: Context, freshInstall: Boolean) : this(
        db,
        context,
        InstallFreshness.fixed(context, freshInstall)
    )

    private val settings = SettingsStore(context, installFreshness)
    private val gradingRules = GradingRulesStore(db, settings)
    private val mapper = QuestionContentMapper(db)
    private val questionStore = QuestionStore(db, mapper)
    private val passageStore = PassageStore(db, mapper, questionStore)
    private val paperStore = PaperStore(db, settings)
    private val studyStore = StudyStore(db, mapper, questionStore, settings)
    private val historyStore = HistoryStore(db, mapper, questionStore)
    private val signalStore = SignalStore(db)
    private val fsrsTuner = FsrsTuner(db, settings)
    private val statsStore = StatsStore(db, settings, gradingRules)
    private val testProgress = TestProgressStore(db, settings)

    fun db(): AppDatabase = db

    // --- settings ---

    fun themeMode(): Flow<String> = settings.themeMode()
    suspend fun setThemeMode(mode: String) = settings.setThemeMode(mode)
    fun themePalette(): Flow<String> = settings.themePalette()
    suspend fun setThemePalette(key: String) = settings.setThemePalette(key)
    fun customThemesJson(): Flow<String?> = settings.customThemesJson()
    suspend fun updateCustomThemesJson(update: (String?) -> String) =
        settings.updateCustomThemesJson(update)

    fun shuffleQuestions(): Flow<Boolean> = settings.shuffleQuestions()
    suspend fun setShuffleQuestions(enabled: Boolean) = settings.setShuffleQuestions(enabled)

    fun simplifiedStudy(): Flow<Boolean> = settings.simplifiedStudy()
    suspend fun setSimplifiedStudy(enabled: Boolean) = settings.setSimplifiedStudy(enabled)

    fun shuffleOptions(): Flow<Boolean> = settings.shuffleOptions()
    suspend fun setShuffleOptions(enabled: Boolean) = settings.setShuffleOptions(enabled)

    fun hapticsEnabled(): Flow<Boolean> = settings.hapticsEnabled()
    suspend fun setHapticsEnabled(enabled: Boolean) = settings.setHapticsEnabled(enabled)

    fun pdfTwoColumn(): Flow<Boolean> = settings.pdfTwoColumn()
    suspend fun setPdfTwoColumn(enabled: Boolean) = settings.setPdfTwoColumn(enabled)

    /** The last length the learner started a test with (plan §10). */
    fun lastTestLength(): Flow<Int> = settings.lastTestLength()
    suspend fun setLastTestLength(length: Int) = settings.setLastTestLength(length)

    fun practiceMode(): Flow<Boolean> = settings.practiceMode()
    suspend fun setPracticeMode(enabled: Boolean) = settings.setPracticeMode(enabled)

    fun strictMode(): Flow<Boolean> = settings.strictMode()
    suspend fun setStrictMode(enabled: Boolean) = settings.setStrictMode(enabled)

    fun updateAnswersOnDuplicate(): Flow<Boolean> = settings.updateAnswersOnDuplicate()
    suspend fun setUpdateAnswersOnDuplicate(enabled: Boolean) =
        settings.setUpdateAnswersOnDuplicate(enabled)

    fun autoAdvance(): Flow<Boolean> = settings.autoAdvance()
    suspend fun setAutoAdvance(enabled: Boolean) = settings.setAutoAdvance(enabled)

    /** Whether Random tests avoid questions from the last three comparable attempts. */
    fun avoidRecentQuestions(): Flow<Boolean> = settings.avoidRecentQuestions()
    suspend fun setAvoidRecentQuestions(enabled: Boolean) =
        settings.setAvoidRecentQuestions(enabled)

    fun loadRemoteImages(): Flow<Boolean> = settings.loadRemoteImages()
    suspend fun setLoadRemoteImages(enabled: Boolean) = settings.setLoadRemoteImages(enabled)

    fun fontScale(): Flow<Float> = settings.fontScale()
    suspend fun setFontScale(scale: Float) = settings.setFontScale(scale)

    fun schedulerConfig(): Flow<com.mcqapp.domain.SchedulerConfig> = settings.schedulerConfig()

    /**
     * The config a session grades under. Reading it also seeds the grading
     * version if this install has never had one, so the very first review is
     * stamped with a version that has a history row behind it.
     *
     * Invariant: a review's `gradingVersion` must name the row whose cuts
     * graded it. `writeCuts` puts the pair and its id in one DataStore edit,
     * so the returned config trusts the id stored *with* the cuts. Re-reading
     * the id separately let a concurrent Reset or cut save interleave, so new
     * cuts could wear the old id and every stats epoch derived from it would
     * start at the wrong `effectiveFrom`.
     *
     * A config whose id reads as 0 is that interleaving: a Reset has removed
     * the id but not yet written the replacement row. Pairing the id seeded
     * above with those cuts would be exactly the mismatch this method exists
     * to prevent, so the read is retried — `currentVersion()` re-seeds the id
     * and its cuts atomically — and only a store that keeps answering 0 fails.
     */
    suspend fun schedulerConfigNow(): com.mcqapp.domain.SchedulerConfig {
        repeat(SCHEDULER_CONFIG_READ_ATTEMPTS) {
            gradingRules.currentVersion()
            val config = settings.schedulerConfigNow()
            if (config.gradingVersion > 0) return config
        }
        throw GradingRuleUnavailable()
    }

    /**
     * Decides the first-start grading rule before any screen asks for the
     * config. [McqApplication] calls this on an IO scope in `onCreate` so an
     * import in the first run cannot turn a new install into an upgrade;
     * every config read awaits the same decision.
     */
    internal suspend fun decideInstallFreshness(): Boolean {
        // The seed is a write, so it belongs here (a job) and in
        // `schedulerConfigNow`, never inside a settings flow collection.
        settings.ensureInstallSeed()
        return installFreshness.isNewInstall()
    }

    /**
     * Writes the whole scheduler config. A changed cut goes through
     * [GradingRulesStore] first, so the new value always owns a version row;
     * the remaining fields are the settings store's business.
     *
     * [schedulerWriteMutex] serialises this against [resetSchedulerConfig] and
     * [settingsRestore]: those read the stored pair, write a new one and mint a
     * version row, and two of them interleaving could file new cuts under an id
     * that named the pair they replaced.
     */
    suspend fun setSchedulerConfig(config: com.mcqapp.domain.SchedulerConfig) {
        schedulerWriteMutex.withLock {
            writeSchedulerConfigLocked(config)
        }
    }

    /**
     * Reads the stored config, applies [transform] and writes the result, all
     * under [schedulerWriteMutex]. The Settings screen's per-field handlers use
     * this instead of reading [schedulerConfigNow] and then calling
     * [setSchedulerConfig]: those are two steps, so two overlapping edits (a
     * cut typed while a rule switch is landing) would each start from the same
     * stored value and the later write would revert the earlier field.
     *
     * Returns the config as written, including the version id
     * [GradingRulesStore.recordConfigCuts] chose, so the caller can publish it
     * without waiting for the DataStore flow to re-emit.
     */
    suspend fun updateSchedulerConfig(
        transform: (com.mcqapp.domain.SchedulerConfig) -> com.mcqapp.domain.SchedulerConfig
    ): com.mcqapp.domain.SchedulerConfig = schedulerWriteMutex.withLock {
        val stored = schedulerConfigNow()
        val edited = transform(stored).sanitized()
        writeSchedulerConfigLocked(edited)
    }

    /** Must run under [schedulerWriteMutex]. Returns the config actually stored. */
    private suspend fun writeSchedulerConfigLocked(
        config: com.mcqapp.domain.SchedulerConfig
    ): com.mcqapp.domain.SchedulerConfig {
        val version = gradingRules.recordConfigCuts(config)
        settings.setSchedulerConfig(config)
        return config.copy(gradingVersion = version)
    }

    /** Serialises the three whole-config writers (save, reset, restore). */
    private val schedulerWriteMutex = Mutex()

    /**
     * Resets the pacing knobs without touching the grading rule. The store
     * decides from its single edit whether the effective cut pair changed: an
     * unchanged pair keeps its version (and therefore the calibration epoch),
     * while a changed pair gets one `manual` row for the defaults.
     *
     * Holding [schedulerWriteMutex] for the whole sequence is what makes that
     * decision trustworthy: a concurrent save cannot move the pair between the
     * reset's write and its follow-up read, so "unchanged" cannot mint a row
     * and two confirms cannot write two rows.
     */
    suspend fun resetSchedulerConfig() = schedulerWriteMutex.withLock {
        if (settings.resetSchedulerConfig()) {
            gradingRules.recordResetCuts(settings.schedulerConfigNow())
        }
    }

    /** The current grading version; a manual Settings save of a cut writes one. */
    internal suspend fun gradingVersion(): Int = gradingRules.currentVersion()

    // --- FSRS parameter optimization ---

    /** Reviews logged across every paper: the optimizer's unlock gauge. */
    fun fsrsReviewCount(): Flow<Int> = fsrsTuner.observeReviewCount()

    /**
     * Fits the 21 weights to the whole review log and stores them; nothing
     * else is touched. @throws com.mcqapp.domain.FsrsFitInsufficientData
     * when the log is too thin to fit.
     */
    suspend fun optimizeFsrsParameters(
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): FsrsFitResult = fsrsTuner.optimize(onProgress)

    /** Drops fitted weights; scheduling returns to the official defaults. */
    suspend fun restoreFsrsDefaults() = fsrsTuner.restoreDefaults()

    // --- statistics ---

    /**
     * Everything the list-based tabs need except the log: each card's schedule,
     * the paper titles and the scheduler config. The stats ViewModel slices
     * this in memory per range and paper scope.
     */
    internal suspend fun statsLibrary(): StatsStore.Library = statsStore.loadLibrary()

    /**
     * The replayed review log, all of it in memory. No production path may call
     * this: every tab streams (`statsRetention`, `statsOverview`, `statsMemory`,
     * `statsCards`, `statsWorkload`), and NoDeadCodeTest holds the line. It is
     * kept as the stats tests' oracle — the full-log build the streamed
     * aggregates are asserted equal to, the same shape as
     * `getStudyQueue(leechesOnly = false)` for the study tests.
     */
    internal suspend fun statsReviews(): List<ReplayReview> = statsStore.loadReviews()

    /**
     * The retention tab alone, aggregated while the log streams past it. Unlike
     * [statsReviews] this never holds the log.
     */
    internal suspend fun statsRetention(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): RetentionData = statsStore.loadRetention(scopePaperId, range, now)

    /**
     * The overview tab alone, aggregated while the log and the cards stream
     * past it. [now] is the build's clock, so every tab on the page agrees on
     * what "today" is.
     */
    internal suspend fun statsOverview(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): OverviewData = statsStore.loadOverview(scopePaperId, range, now)

    /**
     * The workload tab alone, streamed. Its per-question response-time roll-ups
     * are the replay's own working set; the log and the cards are never listed.
     */
    internal suspend fun statsWorkload(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): WorkloadData = statsStore.loadWorkload(scopePaperId, range, now)

    /**
     * The memory tab alone, streamed: one replay feeds both the overview
     * accumulator's KPIs and the memory accumulator's curves and histograms.
     */
    internal suspend fun statsMemory(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): MemoryData = statsStore.loadMemory(scopePaperId, range, now)

    /** The cards tab alone, streamed: the cards first, then the log for leeches. */
    internal suspend fun statsCards(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): CardsData = statsStore.loadCards(scopePaperId, range, now)

    /**
     * The progress tab alone, streamed: introductions, graduations, relapses
     * and the Again rate, plus the previous-window comparison.
     */
    internal suspend fun statsProgress(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): ProgressData = statsStore.loadProgress(scopePaperId, range, now)

    /**
     * The guessing tab alone, streamed: flagged reviews into counters, then the
     * option/key counts of the guessed questions for the chance baseline.
     */
    internal suspend fun statsGuessing(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): GuessingData = statsStore.loadGuessing(scopePaperId, range, now)

    /**
     * The sessions tab alone, streamed: chart counters only. [sessionHistory]
     * is the sidebar's variant, which also keeps every sitting and reads the
     * topics those sittings touched.
     */
    internal suspend fun statsSessions(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): SessionsData =
        statsStore.loadSessions(scopePaperId, range, now, collectEntries = false)

    /** The sidebar history: every sitting in range, newest first. */
    internal suspend fun sessionHistory(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): SessionsData =
        statsStore.loadSessions(scopePaperId, range, now, collectEntries = true)

    /** One question's logged reviews, oldest first, for the drill-down sheet. */
    suspend fun reviewHistory(questionId: String): List<com.mcqapp.domain.stats.ReviewHistoryEntry> =
        statsStore.reviewHistory(questionId)

    // --- in-progress test session (header in DataStore, answers in Room) ---

    /** The header a killed test session left behind, or null. */
    suspend fun loadTestSnapshot(): TestSnapshot? = testProgress.snapshot()

    /** The header joined with its stored answers: what a resume would restore. */
    suspend fun resumeOffer(paperId: String): TestSnapshot? = testProgress.offer(paperId)

    /**
     * Persists the header and the questions whose answer just changed.
     * [takeOver] drops the previous session's answer rows when this save is a
     * new bounded session replacing a different one in the single slot.
     */
    suspend fun saveTestProgress(
        snapshot: TestSnapshot,
        changed: List<TestAnswer>,
        takeOver: Boolean = false
    ) = testProgress.save(snapshot, changed, takeOver)

    /** Writes a legacy snapshot's inline answers as rows, so they are not dropped. */
    suspend fun adoptTestAnswers(snapshot: TestSnapshot) = testProgress.adopt(snapshot)

    /** Drops the snapshot and the answers of the paper it names. */
    suspend fun clearTestProgress() = testProgress.clear()

    /** Drops answers left behind by a session whose slot another paper took. */
    suspend fun clearStaleTestAnswers(paperId: String) = testProgress.discardStale(paperId)

    /** The backup's copy of the in-progress session, answers inlined. */
    suspend fun testProgressForBackup(): String? = testProgress.forBackup()

    /**
     * Restores a backup's in-progress session: its document verbatim, with any
     * rows it supersedes dropped. The answers inside it are adopted as rows the
     * next time the session is offered.
     */
    suspend fun restoreTestProgress(document: String?) = testProgress.restore(document)

    /** Every preference, typed, for the backup's settings.json. */
    suspend fun settingsSnapshot() = settings.snapshot()

    /**
     * Full replace of every preference from a backup. Restored cuts get a
     * fresh `prior` grading version continuing the restored numbering, so each
     * review row still points at the pair that graded it.
     *
     * The `finally` invalidates the first-start answer even if the settings
     * or version write throws: the database half of a full restore has
     * already committed by the time this runs, so the pre-restore answer no
     * longer describes the install.
     */
    suspend fun settingsRestore(snapshot: com.mcqapp.data.backup.McqBackupFormat.SettingsFile) =
        schedulerWriteMutex.withLock {
            try {
                settings.restore(snapshot)
                gradingRules.seedAfterRestore()
            } finally {
                installFreshness.invalidate()
            }
        }

    // --- search & storage (no collaborator: they span everything) ---

    /**
     * One window of cross-paper search. Phase 1 fetches a bounded window of
     * SQL `LIKE` prefilter candidates (a superset, never a veto); phase 2 runs
     * the real in-memory [com.mcqapp.domain.QuestionSearch.filter] over just
     * that window and attaches provenance. Only the window is ever
     * materialised, so a query matching a quarter of a million questions never
     * loads them all — the old prefilter returned `questions.*` for every
     * match, element JSON and base64 images included.
     *
     * [GlobalSearchPage.nextOffset] is non-null while more candidate windows
     * remain.
     */
    suspend fun searchPage(
        query: String,
        scope: com.mcqapp.domain.QuestionSearch.Scope =
            com.mcqapp.domain.QuestionSearch.Scope.ALL,
        offset: Int = 0,
        limit: Int = SEARCH_WINDOW
    ): GlobalSearchPage {
        if (query.isBlank()) return GlobalSearchPage(emptyList(), null)
        // DAO prefilter must cover option texts whenever the scope needs them.
        // The prefilter is a SQL LIKE, so the user query is escaped first;
        // the in-memory post-filter below keeps matching the raw query. The
        // bound value is also Kotlin-lowercased: the prefilter matches the
        // Kotlin-folded column, so a non-ASCII literal folds the way the
        // in-memory authority folds instead of the way SQL's A-Z folds.
        val like = com.mcqapp.domain.QuestionSearch.escapeLike(query.lowercase())
        // The SQL pass is a prefilter, not the answer: `questions.text` holds the
        // elements JSON with markup in it, so a phrase straddling a tag or two
        // table cells is not a literal substring of it. Or in the longest single
        // word, which is contiguous within a tag, so the prefilter can no longer
        // veto a row the real filter would have matched.
        val fragment = com.mcqapp.domain.QuestionSearch.escapeLike(
            com.mcqapp.domain.QuestionSearch.prefilterKey(query).lowercase()
        )
        val raw = when (scope) {
            com.mcqapp.domain.QuestionSearch.Scope.QUESTION ->
                db.questionDao().search(like, fragment, limit, offset)
            // Options only: the union query walks every text/tag candidate
            // the authority rejects for this scope (consolidated_bugs.md B2).
            com.mcqapp.domain.QuestionSearch.Scope.OPTIONS ->
                db.questionDao().searchOptionsOnly(like, fragment, limit, offset)
            else ->
                db.questionDao().searchIncludingOptions(like, fragment, limit, offset)
        }
        if (raw.isEmpty()) return GlobalSearchPage(emptyList(), null)
        // Passage context rides into the post-filter so the DAO's passage
        // union survives it: a member found through its passage's text or
        // title must not be vetoed because that text is not on the question.
        val passageBodies = db.passageDao().getByIdsChunked(
            raw.mapNotNull { it.passageId }.distinct()
        ).associate { passage ->
            // The STORED column (passageSearchText: title + " " + body,
            // trimmed), not a rebuilt "title + newline + body": the SQL
            // prefilter matches the stored shape, and a phrase straddling
            // the title-to-body boundary ("cycle rain") was vetoed here by
            // a separator the stored column never had
            // (consolidated_bugs.md B1).
            passage.id to passage.searchText
        }
        val questions = com.mcqapp.domain.QuestionSearch.filter(
            mapper.toDomainBulk(raw),
            query,
            scope,
            passageBodies
        )
        val next = if (raw.size == limit) offset + raw.size else null
        return GlobalSearchPage(attachProvenance(questions), next)
    }

    /**
     * Resolves paper titles for one page's categories only, in two chunked
     * queries. The old path loaded the whole category and paper tables.
     */
    private suspend fun attachProvenance(
        questions: List<Question>
    ): List<com.mcqapp.domain.QuestionSearch.Hit> {
        if (questions.isEmpty()) return emptyList()
        val categories = db.categoryDao()
            .getByIdsChunked(questions.map { it.categoryId }.distinct())
        val papersById = db.paperDao()
            .getByIdsChunked(categories.map { it.paperId }.distinct())
            .associate { it.id to it.title }
        val paperByCategory = categories.associate { cat ->
            cat.id to (cat.paperId to (papersById[cat.paperId] ?: "Paper"))
        }
        return com.mcqapp.domain.QuestionSearch.attach(questions, paperByCategory)
    }

    /**
     * Cross-paper search as a [androidx.paging.PagingSource]. The two-phase
     * pipeline (SQL candidate window, then the in-memory filter) cannot map
     * one-to-one onto a SQL page, so this walks candidate windows until it has
     * enough post-filtered hits — the key is the candidate offset, never an
     * index into the filtered result, so no hit is skipped.
     */
    fun searchPagingSource(
        query: String,
        scope: com.mcqapp.domain.QuestionSearch.Scope =
            com.mcqapp.domain.QuestionSearch.Scope.ALL
    ): androidx.paging.PagingSource<Int, com.mcqapp.domain.QuestionSearch.Hit> =
        SearchPagingSource(
            window = SEARCH_WINDOW,
            fetch = { offset, limit -> searchPage(query, scope, offset, limit) }
        )

    /**
     * The whole result set at once. Kept for tests and small callers; pages
     * internally so the prefilter never materialises every match in one go.
     */
    suspend fun searchGlobal(
        query: String,
        scope: com.mcqapp.domain.QuestionSearch.Scope =
            com.mcqapp.domain.QuestionSearch.Scope.ALL
    ): List<com.mcqapp.domain.QuestionSearch.Hit> {
        if (query.isBlank()) return emptyList()
        val out = mutableListOf<com.mcqapp.domain.QuestionSearch.Hit>()
        var offset = 0
        while (true) {
            val page = searchPage(query, scope, offset)
            out += page.hits
            offset = page.nextOffset ?: break
        }
        return out
    }

    /** Storage breakdown for Settings: file size, row counts, per-paper weight. */
    suspend fun storageReport(): com.mcqapp.domain.StorageInfo.Report {
        // The report labels this as the database size, so it must include the
        // write-ahead log and shared-memory file: in WAL mode most recent
        // writes live in mcq.db-wal until a checkpoint, and counting only the
        // main file understates the database after a session.
        val dbBytes = try {
            val main = context.getDatabasePath(AppDatabase.NAME)
            listOf(main, java.io.File("${main.path}-wal"), java.io.File("${main.path}-shm"))
                .filter { it.exists() }
                .sumOf { it.length() }
        } catch (e: Exception) {
            -1L
        }
        // Every rollup comes from SQL: the old path materialised every
        // question and every option just to size them per paper.
        val papers = db.paperDao().getAll()
        val counts = db.questionDao().questionCountsByPaper()
        val questionChars = db.questionDao().questionImageCharsByPaper()
        val optionChars = db.questionDao().optionImageCharsByPaper()
        val perPaper = papers.map { paper ->
            com.mcqapp.domain.StorageInfo.PaperUsage(
                paperId = paper.id,
                title = paper.title,
                questions = counts[paper.id] ?: 0,
                imageChars = (questionChars[paper.id] ?: 0L) + (optionChars[paper.id] ?: 0L)
            )
        }.sortedByDescending { it.imageChars }
        return com.mcqapp.domain.StorageInfo.Report(
            dbBytes = dbBytes,
            papers = papers.size,
            questions = db.questionDao().countQuestions(),
            attempts = db.attemptDao().countAll(),
            bookmarks = db.bookmarkDao().countAll(),
            perPaper = perPaper
        )
    }

    // --- passages ---

    fun observePassages(): Flow<List<com.mcqapp.domain.Passage>> = passageStore.observePassages()
    suspend fun getPassage(passageId: String): com.mcqapp.domain.Passage? =
        passageStore.getPassage(passageId)
    suspend fun getPassagesByIds(ids: List<String>): List<com.mcqapp.domain.Passage> =
        passageStore.getPassagesByIds(ids)
    suspend fun getPassagesForPaper(paperId: String): List<com.mcqapp.domain.Passage> =
        passageStore.getPassagesForPaper(paperId)
    suspend fun savePassage(passage: com.mcqapp.domain.Passage): String =
        passageStore.savePassage(passage)
    suspend fun passageMemberCount(passageId: String): Int =
        passageStore.memberCount(passageId)
    suspend fun passageMemberCounts(): Map<String, Int> =
        passageStore.memberCounts()
    suspend fun deletePassage(passageId: String): Boolean =
        passageStore.deletePassage(passageId)
    suspend fun renumberQuestions(categoryId: String, orderedIds: List<String>): Boolean =
        questionStore.renumberQuestions(categoryId, orderedIds)

    /** The passage's members in their own category order, for its detail list. */
    suspend fun getPassageMembers(passageId: String): List<Question> =
        mapper.toDomainBulk(db.questionDao().getMembersByPassage(passageId))

    /** A fresh pager over a passage's member rows (id + stem only). */
    fun passageMembersSource(passageId: String): androidx.paging.PagingSource<Int, QuestionRef> =
        db.questionDao().pagingRefsByPassage(passageId)

    /** A passage's member ids in list order, for the editor's Prev/Next queue. */
    suspend fun getPassageMemberIds(passageId: String): List<String> =
        db.questionDao().getMemberIdsByPassage(passageId)

    suspend fun assignQuestionsToPassage(questionIds: Collection<String>, passageId: String) =
        passageStore.assignQuestions(questionIds, passageId)
    suspend fun unassignQuestionsFromPassage(questionIds: Collection<String>) =
        passageStore.unassignQuestions(questionIds)
    suspend fun unassignAllFromPassage(passageId: String) =
        passageStore.unassignAll(passageId)
    suspend fun movePassage(passageId: String, delta: Int): Boolean =
        passageStore.movePassage(passageId, delta)
    suspend fun buildPassageBlocks(questions: List<Question>): List<PassageBlock> =
        passageStore.buildBlocks(questions)

    // --- papers & categories ---

    fun observePapers(): Flow<List<Paper>> = paperStore.observePapers()
    suspend fun getPaper(paperId: String): Paper? = paperStore.getPaper(paperId)
    suspend fun savePaper(paper: Paper) = paperStore.savePaper(paper)
    suspend fun deleteImpact(paperId: String): DeleteImpact = paperStore.deleteImpact(paperId)
    suspend fun deletePaper(paperId: String) = paperStore.deletePaper(paperId)
    suspend fun movePaper(paperId: String, delta: Int): Boolean =
        paperStore.movePaper(paperId, delta)
    suspend fun addCategory(paperId: String, title: String, parentId: String?): String =
        paperStore.addCategory(paperId, title, parentId)
    suspend fun deleteCategory(categoryId: String) = paperStore.deleteCategory(categoryId)
    suspend fun nestCategoryIntoSibling(categoryId: String): Boolean =
        paperStore.nestCategoryIntoSibling(categoryId)
    suspend fun promoteCategoryOutOfParent(categoryId: String): Boolean =
        paperStore.promoteCategoryOutOfParent(categoryId)
    suspend fun reparentCategory(categoryId: String, newParentId: String?): Boolean =
        paperStore.reparentCategory(categoryId, newParentId)
    suspend fun renameCategory(categoryId: String, title: String): Boolean =
        paperStore.renameCategory(categoryId, title)
    suspend fun moveCategory(categoryId: String, delta: Int): Boolean =
        paperStore.moveCategory(categoryId, delta)

    /** Existing names, for the import screen's title/category suggestions. */
    suspend fun existingPaperTitles(): List<String> = paperStore.allPaperTitles()
    suspend fun existingCategoryNames(): List<String> = paperStore.allCategoryTitles()

    /** Counts shown in the delete confirmation. */
    data class DeleteImpact(
        val questions: Int = 0,
        val attempts: Int = 0,
        val bookmarks: Int = 0,
        val schedules: Int = 0
    ) {
        /** True when anything beyond the questions themselves would be lost. */
        val hasMoreThanQuestions: Boolean
            get() = attempts > 0 || bookmarks > 0 || schedules > 0
    }

    // --- questions ---

    fun observeQuestionsForPaper(paperId: String): Flow<List<Question>> =
        questionStore.observeQuestionsForPaper(paperId)
    suspend fun getQuestionsForCategories(categoryIds: List<String>): List<Question> =
        questionStore.getQuestionsForCategories(categoryIds)
    suspend fun getQuestionsForPaper(paperId: String): List<Question> =
        questionStore.getQuestionsForPaper(paperId)

    /**
     * A session's order for a whole paper: ids and passage membership only,
     * streamed out of the database, never a list of question content.
     */
    suspend fun getSessionOrderForPaper(paperId: String): SessionOrder =
        questionStore.getOrderForPaper(paperId)

    /** [getSessionOrderForPaper] over an explicit category selection. */
    suspend fun getSessionOrderForCategories(
        paperId: String,
        categoryIds: List<String>
    ): SessionOrder =
        questionStore.getOrderForCategories(paperId, categoryIds)

    /**
     * The order named by [ids], in that order. A focus selector chooses ids
     * first; resolving their passage membership here keeps the frozen sample
     * and its resume identity identical.
     */
    suspend fun getSessionOrderForIds(ids: List<String>): SessionOrder {
        if (ids.isEmpty()) return SessionOrder.empty()
        val byId = db.questionDao()
            .getSessionRefsByIdsChunked(ids)
            .associateBy { it.id }
        return SessionOrder.of(ids.mapNotNull { byId[it] })
    }

    /**
     * The per-question signals a focus selector ranks (stats.md §9.2).
     * Read-only: it never touches `card_state` or `review_log`.
     */
    suspend fun getQuestionSignals(
        paperId: String,
        scopeIds: List<String>,
        now: Long = System.currentTimeMillis()
    ): List<Signals> = signalStore.signals(paperId, scopeIds, now)

    /**
     * The scope's distinct tags with their counts (phase 3), most used first.
     * [categoryIds] are the ticked branches; empty means the whole paper.
     */
    suspend fun tagCountsForScope(
        paperId: String,
        categoryIds: List<String>
    ): List<TagCount> = db.questionTagDao().tagCountsChunked(
        paperId = paperId,
        categoryIds = categoryIds
    )

    /**
     * The ids of the scope's questions carrying exactly a tag (phase 3): the
     * Tag selector's pool. [foldedTag] is [TagNormalizer.fold]'s output.
     */
    suspend fun questionIdsForTagInScope(
        paperId: String,
        categoryIds: List<String>,
        foldedTag: String
    ): List<String> = db.questionTagDao().questionIdsForTagChunked(
        paperId = paperId,
        categoryIds = categoryIds,
        tagFolded = foldedTag
    )

    /** The scope's bookmarked question ids, most recently bookmarked first. */
    suspend fun bookmarkedIdsInScope(
        paperId: String,
        categoryIds: List<String>
    ): List<String> = db.questionTagDao().bookmarkedIdsInScopeChunked(
        paperId = paperId,
        categoryIds = categoryIds
    )

    /**
     * The paper's fixed panels, oldest first (phase 3). The size comes from a
     * count join, so listing panels never materialises their ids.
     */
    suspend fun panelsForPaper(paperId: String): List<PanelSummary> =
        db.panelDao().panelsWithCountsForPaper(paperId).map { row ->
            PanelSummary(
                id = row.panel.id,
                paperId = row.panel.paperId,
                name = row.panel.name,
                size = row.questionCount
            )
        }

    /** The paper's fixed panels with their real, frozen question ids. */
    suspend fun panelsWithQuestions(paperId: String): List<Panel> =
        db.panelDao().panelsForPaper(paperId).map { entity ->
            Panel(
                id = entity.id,
                paperId = entity.paperId,
                name = entity.name,
                questionIds = db.panelDao().questionIds(entity.id)
            )
        }

    /** Every paper's panels, for the whole-library Statistics scope. */
    suspend fun allPanels(): List<PanelSummary> =
        db.panelDao().allPanelsWithCounts().map { row ->
            PanelSummary(
                id = row.panel.id,
                paperId = row.panel.paperId,
                name = row.panel.name,
                size = row.questionCount
            )
        }

    /** One panel with its frozen ids, or null after it was deleted. */
    suspend fun panel(panelId: Long): Panel? {
        val entity = db.panelDao().panel(panelId) ?: return null
        return Panel(
            id = entity.id,
            paperId = entity.paperId,
            name = entity.name,
            questionIds = db.panelDao().questionIds(panelId)
        )
    }

    /**
     * Creates a panel from [questionIds], in the given order, and returns its
     * id. Blank ids are dropped and duplicates keep their first position: a
     * panel is a set, and the order is the order it asks.
     */
    suspend fun createPanel(paperId: String, name: String, questionIds: List<String>): Long {
        val ids = questionIds.filter { it.isNotBlank() }.distinct()
        return db.withTransaction {
            val panelId = db.panelDao().insertPanel(
                PanelEntity(paperId = paperId, name = name.trim())
            )
            if (ids.isNotEmpty()) {
                db.panelDao().insertQuestions(
                    ids.mapIndexed { index, questionId ->
                        PanelQuestionEntity(panelId = panelId, questionId = questionId, position = index)
                    }
                )
            }
            panelId
        }
    }

    /** Deletes a panel and its rows; the questions are untouched. */
    suspend fun deletePanel(panelId: Long) = db.panelDao().deletePanel(panelId)

    // --- blueprints (exam_blueprint §6) ---

    suspend fun blueprintsForPaper(paperId: String): List<BlueprintSummary> =
        db.blueprintDao().forPaper(paperId).map { entity ->
            BlueprintSummary(
                id = entity.id,
                name = entity.name,
                sectionCount = db.blueprintDao().sections(entity.id).size
            )
        }

    suspend fun blueprint(id: Long): Blueprint? {
        val entity = db.blueprintDao().getById(id) ?: return null
        val rows = db.blueprintDao().sections(id)
        val categories = db.categoryDao().getByPaper(entity.paperId)
        val sortOrder = mutableMapOf<String, Int>()
        categories.forEach { sortOrder[it.id] = it.sortOrder }
        rows.map { it.categoryId }.distinct().filter { it !in sortOrder }.forEach { cid ->
            db.categoryDao().getById(cid)?.let { sortOrder[it.id] = it.sortOrder }
        }
        return buildBlueprint(entity, rows, sortOrder)
    }

    private fun buildBlueprint(
        entity: BlueprintEntity,
        rows: List<BlueprintSectionEntity>,
        sortOrder: Map<String, Int>
    ): Blueprint {
        val byParent: Map<Long?, List<BlueprintSectionEntity>> =
            rows.groupBy { it.parentSectionId }
        fun build(rowId: Long): BlueprintSection {
            val row = rows.first { it.id == rowId }
            val children = (byParent[rowId] ?: emptyList())
                .sortedBy { sortOrder[it.categoryId] ?: Int.MAX_VALUE }
                .map { build(it.id) }
            return BlueprintSection(
                categoryId = row.categoryId,
                title = "",
                parts = row.parts,
                children = children
            )
        }
        val top = (byParent[null] ?: emptyList())
            .sortedBy { sortOrder[it.categoryId] ?: Int.MAX_VALUE }
            .map { build(it.id) }
        return Blueprint(
            id = entity.id,
            paperId = entity.paperId,
            name = entity.name,
            sections = top,
            createdAt = entity.createdAt
        )
    }

    private fun validateBlueprintTree(
        paperId: String,
        name: String,
        sections: List<BlueprintSection>,
        categories: List<CategoryEntity>
    ) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Blueprint name must not be blank" }
        require(trimmed.length <= 60) { "Blueprint name must be at most 60 characters" }
        require(sections.isNotEmpty()) { "Blueprint must have at least one section" }
        val parentOf = categories.associate { it.id to it.parentId }
        val idsInPaper = categories.mapTo(HashSet()) { it.id }
        fun isStrictAncestor(ancestor: String, descendant: String): Boolean {
            var cur: String? = parentOf[descendant]
            val seen = HashSet<String>()
            while (cur != null) {
                if (cur == ancestor) return true
                if (!seen.add(cur)) return false
                cur = parentOf[cur]
            }
            return false
        }
        val seenIds = HashSet<String>()
        fun walk(list: List<BlueprintSection>, parentCategoryId: String?) {
            for (node in list) {
                require(node.parts >= 1) {
                    "Blueprint section parts must be at least 1"
                }
                require(node.categoryId in idsInPaper) {
                    "Blueprint section category is not in this paper"
                }
                require(seenIds.add(node.categoryId)) {
                    "Blueprint section category ids must be unique"
                }
                if (parentCategoryId != null) {
                    require(isStrictAncestor(parentCategoryId, node.categoryId)) {
                        "Blueprint section must be a strict descendant of its parent"
                    }
                }
                walk(node.children, node.categoryId)
            }
            // sibling (and top-level) subtrees must be disjoint
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i].categoryId
                    val b = list[j].categoryId
                    require(!isStrictAncestor(a, b) && !isStrictAncestor(b, a)) {
                        "Blueprint sibling sections must not overlap"
                    }
                }
            }
        }
        walk(sections, null)
    }

    private suspend fun insertSectionTree(
        blueprintId: Long,
        sections: List<BlueprintSection>,
        parentSectionId: Long?,
        categoriesForOrder: List<CategoryEntity>
    ) {
        // Keep the input order; the read path re-sorts by sortOrder.
        for (section in sections) {
            val ids = db.blueprintDao().insertSections(
                listOf(
                    BlueprintSectionEntity(
                        blueprintId = blueprintId,
                        categoryId = section.categoryId,
                        parentSectionId = parentSectionId,
                        parts = section.parts
                    )
                )
            )
            val newId = ids.single()
            if (section.children.isNotEmpty()) {
                insertSectionTree(blueprintId, section.children, newId, categoriesForOrder)
            }
        }
    }

    /**
     * The unique (paperId, name) index would surface as a raw
     * `SQLiteConstraintException`; check first so the editor can show a
     * sentence a learner can act on. Case-insensitive: two chips reading the
     * same name in different case are just as indistinguishable.
     */
    private suspend fun requireUniqueBlueprintName(
        paperId: String,
        name: String,
        exceptId: Long? = null
    ) {
        val trimmed = name.trim()
        val taken = db.blueprintDao().forPaper(paperId).any { entity ->
            entity.id != exceptId && entity.name.equals(trimmed, ignoreCase = true)
        }
        require(!taken) { "A blueprint named \"$trimmed\" already exists in this paper." }
    }

    suspend fun createBlueprint(
        paperId: String,
        name: String,
        sections: List<BlueprintSection>
    ): Long {
        val categories = db.categoryDao().getByPaper(paperId)
        validateBlueprintTree(paperId, name, sections, categories)
        requireUniqueBlueprintName(paperId, name)
        return db.withTransaction {
            val id = db.blueprintDao().insertBlueprint(
                BlueprintEntity(paperId = paperId, name = name.trim())
            )
            if (sections.isNotEmpty()) {
                insertSectionTree(id, sections, null, categories)
            }
            id
        }
    }

    suspend fun replaceBlueprint(
        id: Long,
        name: String,
        sections: List<BlueprintSection>
    ) {
        val existing = db.blueprintDao().getById(id)
            ?: throw IllegalArgumentException("Blueprint not found")
        val categories = db.categoryDao().getByPaper(existing.paperId)
        validateBlueprintTree(existing.paperId, name, sections, categories)
        requireUniqueBlueprintName(existing.paperId, name, exceptId = id)
        db.withTransaction {
            db.blueprintDao().updateName(id, name.trim())
            db.blueprintDao().clearSections(id)
            if (sections.isNotEmpty()) {
                insertSectionTree(id, sections, null, categories)
            }
        }
    }

    suspend fun deleteBlueprint(id: Long) = db.blueprintDao().delete(id)

    private fun categoryNodesOf(
        categories: List<CategoryEntity>,
        counts: Map<String, Int>
    ): List<CategoryNode> {
        val ids = categories.mapTo(HashSet()) { it.id }
        val byParent = categories.groupBy { it.parentId?.takeIf { p -> p in ids } }
        fun build(parentId: String?): List<CategoryNode> =
            (byParent[parentId] ?: emptyList()).map { category ->
                CategoryNode(
                    id = category.id,
                    paperId = category.paperId,
                    title = category.title,
                    parentId = category.parentId,
                    children = build(category.id),
                    questionCount = counts[category.id] ?: 0
                )
            }
        return build(null)
    }

    suspend fun blueprintSectionViews(blueprint: Blueprint): List<BlueprintSectionView> {
        val categories = db.categoryDao().getByPaper(blueprint.paperId)
        val counts = if (categories.isEmpty()) emptyMap()
        else db.questionDao().categoryCountsForPaper(blueprint.paperId)
            .associate { it.categoryId to it.cnt }
        // subtree pool per paper category id, post-order
        val childrenOfCat: Map<String, List<CategoryEntity>> =
            categories.groupBy { it.parentId ?: "" }
        val poolByCategory = HashMap<String, Int>()
        fun poolOfCategory(catId: String, visited: MutableSet<String>): Int {
            if (catId in poolByCategory) return poolByCategory.getValue(catId)
            if (!visited.add(catId)) return 0
            var total = counts[catId] ?: 0
            for (child in (childrenOfCat[catId] ?: emptyList())) {
                total += poolOfCategory(child.id, visited)
            }
            visited.remove(catId)
            poolByCategory[catId] = total
            return total
        }
        categories.forEach { poolOfCategory(it.id, HashSet()) }
        val catById = categories.associateBy { it.id }
        // Categories referenced by the blueprint but not in this paper (moved)
        val missingIds = blueprint.allCategoryIds.filter { it !in catById }.distinct()
        val extraById = HashMap<String, CategoryEntity>()
        for (mid in missingIds) {
            db.categoryDao().getById(mid)?.let { extraById[mid] = it }
        }
        fun titleOf(id: String, fallback: String): String =
            catById[id]?.title ?: extraById[id]?.title ?: fallback
        fun view(section: BlueprintSection, parentAvailable: Boolean): BlueprintSectionView {
            val cat = catById[section.categoryId] ?: extraById[section.categoryId]
            val ownAvailable = cat != null && cat.paperId == blueprint.paperId
            val available = ownAvailable && parentAvailable
            val title = titleOf(section.categoryId, section.title)
            if (section.children.isNotEmpty()) {
                val kids = section.children.map { view(it, available) }
                // internal pool is sum of children's pools
                val pool = kids.sumOf { it.pool }
                return BlueprintSectionView(
                    categoryId = section.categoryId,
                    title = title,
                    parts = section.parts,
                    pool = pool,
                    available = available,
                    children = kids
                )
            }
            val pool = if (ownAvailable) (poolByCategory[section.categoryId] ?: 0) else 0
            return BlueprintSectionView(
                categoryId = section.categoryId,
                title = title,
                parts = section.parts,
                pool = pool,
                available = available,
                children = emptyList()
            )
        }
        return blueprint.sections.map { view(it, true) }
    }

    suspend fun buildPlanBlueprint(blueprint: Blueprint): PlanBlueprint? {
        val views = blueprintSectionViews(blueprint)
        val categories = db.categoryDao().getByPaper(blueprint.paperId)
        val counts = if (categories.isEmpty()) emptyMap()
        else db.questionDao().categoryCountsForPaper(blueprint.paperId)
            .associate { it.categoryId to it.cnt }
        val nodes = categoryNodesOf(categories, counts)
        fun convert(section: BlueprintSection, view: BlueprintSectionView): BlueprintSection? {
            if (!view.available) return null
            val pairs = section.children.zip(view.children)
            val convertedChildren = pairs.mapNotNull { (s, v) -> convert(s, v) }
            if (convertedChildren.isEmpty()) {
                val scope = CategorySelection.orderedIds(nodes, listOf(section.categoryId))
                if (scope.isEmpty()) return null
                return section.copy(
                    title = view.title,
                    children = emptyList(),
                    scopeCategoryIds = scope
                )
            }
            return section.copy(
                title = view.title,
                children = convertedChildren,
                scopeCategoryIds = emptyList()
            )
        }
        val converted = blueprint.sections.zip(views).mapNotNull { (s, v) -> convert(s, v) }
        if (converted.isEmpty()) return null
        val plan = PlanBlueprint(label = blueprint.name, sections = converted)
        if (plan.categoryIds.isEmpty()) return null
        return plan
    }

    /**
     * The median dwell seconds of every scope question with at least
     * [TestSelector.SLOWEST_MIN_SAMPLES] timed answers (phase 3): the Slowest
     * selector's input. A median of an even sample list is the lower median,
     * so the values are exact stored seconds, never a blended number.
     */
    suspend fun medianDwellSecondsInScope(
        paperId: String,
        categoryIds: List<String>
    ): Map<String, Long> =
        db.questionTagDao()
            .dwellSamplesInScopeChunked(
                paperId = paperId,
                categoryIds = categoryIds
            )
            .groupBy { it.questionId }
            .mapValues { (_, samples) -> samples.map { it.dwellSeconds }.sorted() }
            .filter { it.value.size >= TestSelector.SLOWEST_MIN_SAMPLES }
            .mapValues { (_, samples) -> samples[(samples.size - 1) / 2] }

    /**
     * Stored test appearances per question in [paperId] (plan §10), for the
     * exposure-weighted Coverage mode: how many stored result rows included
     * it, answered or skipped. A question no stored test included is absent
     * and reads as zero exposure.
     */
    suspend fun testExposureByPaper(paperId: String): Map<String, Int> =
        db.attemptDao().getExposureByPaper(paperId).associate { it.questionId to it.count }

    /**
     * The smallest passage block in a scope (section 9.6). The setup sheet's
     * sampler pre-check uses it before it emits a route; a standalone question
     * makes the answer 1.
     */
    suspend fun getMinBlockSize(paperId: String, scopeIds: List<String>): Int =
        if (scopeIds.isEmpty()) {
            db.questionDao().minBlockSizeForPaper(paperId)
        } else {
            db.questionDao().minBlockSizeForCategoriesChunked(scopeIds)
        }

    /**
     * The smallest passage block among the mistakes the scope would serve.
     * Mistakes are a separate pool, so the scope's block size can be smaller
     * than the block the sampler will actually see.
     */
    suspend fun getMistakenMinBlockSize(paperId: String, scopeIds: List<String>): Int {
        val order = if (scopeIds.isEmpty()) {
            getMistakenQuestionOrder(paperId)
        } else {
            getMistakenQuestionOrder(paperId, scopeIds)
        }
        return order.minBlockSize()
    }

    /**
     * The scope's category ids in the flat `(sortOrder, title)` order the
     * session order and every SQL list surface serve. A scoped study screen
     * calls this once on the `orderedIds` expansion it just built (the
     * expansion decides the scope's *membership* only), then chunks its pool
     * queries on the result — inside a chunk the pool SQL re-sorts by this
     * order regardless of the passed order, so an unordered chunk boundary is
     * the one place a scoped study session could walk categories in a
     * different relative order than the scoped test session next door.
     *
     * One read per scope construction, not per pull: the per-card pull's
     * query budget (StudyPullQueryCountTest) must not grow for alignment.
     */
    suspend fun categoryFlatOrder(paperId: String, categoryIds: List<String>): List<String> =
        questionStore.flatCategoryOrder(paperId, categoryIds)

    /** Observed refs for browse: the list re-emits on any write. */
    fun observeQuestionRefsForPaper(paperId: String): kotlinx.coroutines.flow.Flow<List<QuestionRef>> =
        questionStore.observeRefsForPaper(paperId)

    /** Domain questions for already-loaded entities (batch surfaces only). */
    suspend fun questionsOf(
        entities: List<com.mcqapp.data.local.QuestionEntity>
    ): List<Question> = questionStore.toDomain(entities)

    // --- browse list paging ---

    /**
     * The browse filter set mapped once to its SQL form. The paging source,
     * the count and the editor's cursor all describe the same scope through
     * this, so they can never drift.
     *
     * Every field is one bind variable: the paper is named by id (its category
     * list used to be bound one id at a time — a 1000-category paper is more
     * than SQLite accepts in a single statement on Android 11 and below), and
     * the category filter travels as a CSV the query matches with `INSTR`.
     */
    data class BrowseScope(
        val paperId: String,
        val query: String,
        val noAnswer: Int,
        val noExplanation: Int,
        val uncategorized: Int,
        val categoryFilter: Int,
        /** The SELECTED subtree's category ids; the SQL binds their rowids. */
        val categoryFilterIds: Set<String>?
    ) {
        companion object {
            fun of(
                paperId: String,
                query: String,
                filter: BrowseListFilter,
                categoryFilterIds: Set<String>?
            ): BrowseScope = BrowseScope(
                paperId = paperId,
                // Folded like the search prefilter: the browse queries match
                // a Kotlin-lowercased value against the Kotlin-folded
                // columns, so a non-ASCII literal (`CAFÉ` vs `café`) folds
                // the way the in-memory authority folds instead of the way
                // SQL's A-Z case-folding folds.
                query = com.mcqapp.domain.QuestionSearch
                    .escapeLike(query.trim().lowercase()),
                noAnswer = if (filter == BrowseListFilter.NO_ANSWER) 1 else 0,
                noExplanation = if (filter == BrowseListFilter.NO_EXPLANATION) 1 else 0,
                uncategorized = if (filter == BrowseListFilter.UNCATEGORIZED) 1 else 0,
                categoryFilter = if (categoryFilterIds.isNullOrEmpty()) 0 else 1,
                categoryFilterIds = categoryFilterIds
            )
        }
    }

    /**
     * Fires when a question or passage write could change Browse's on-demand
     * reorder contexts and passage-header lookups; the list itself pages with
     * Room's own invalidation. Category-only writes (a sibling reorder or a
     * rename) leave every context valid, so they are deliberately not observed:
     * clearing on them dropped the section-move buttons mid-gesture.
     *
     * Both sides are one-row probes. Watching the `GROUP BY categoryId` counts
     * and the whole passages table instead made every question write re-scan
     * the paper (and load every passage) just to learn that something changed.
     */
    fun observeBrowseInvalidation(): Flow<Unit> = kotlinx.coroutines.flow.combine(
        db.questionDao().observeAnyQuestion(),
        db.passageDao().observeAnyPassage()
    ) { _, _ -> Unit }

    /**
     * The category filter as ONE bound CSV of the selected categories' ROWIDS,
     * resolved once per read in Kotlin (chunked, so a big subtree never hits
     * the 999-variable wall, and too big to split for the page query itself —
     * LIMIT/OFFSET pagination needs the `:categoryFilter` flag constants, and
     * a bound IN list cannot survive inside a LIMIT page). Rowids, not the ids
     * themselves: a categoryId is an arbitrary imported string, and an id of
     * `a,b` made the old `INSTR` split a lie in both directions — a selection
     * of {a, b} matched the row whose id was literally `a,b`, and selecting
     * `a,b` alone leaked its neighbour `a`. A rowid is a number and cannot
     * contain the separating comma.
     */
    private suspend fun categoryRowIdCsv(scope: BrowseScope): String {
        val ids = scope.categoryFilterIds ?: return ","
        if (ids.isEmpty()) return ","
        val rowIds = ids.chunked(900).flatMap { chunk -> db.categoryDao().rowIdsByIds(chunk) }
        return rowIds.distinct().joinToString(separator = ",", prefix = ",", postfix = ",")
    }

    /**
     * The browse list's csv is resolved OUTSIDE [browseRefsSource]: Pager's
     * factory is not a suspend context, and one resolved csv belongs to the
     * filter change that produced the scope (the list's refreshes re-bind
     * that one value; category writes deliberately do not invalidate, see
     * the scope's comments). The count and cursor calls resolve fresh per
     * call; the SQL that consumes it is shared verbatim by all of them, so
     * list, count and Prev/Next can never drift.
     */
    suspend fun browseCategoryRowIdCsv(scope: BrowseScope): String = categoryRowIdCsv(scope)

    /**
     * A fresh browse [androidx.paging.PagingSource] for one filter set. Room
     * invalidates it on any question/category/answer write, the same
     * refetch-on-write behaviour the observe-based list had. Bind the csv
     * [browseCategoryRowIdCsv] resolved for this scope.
     */
    fun browseRefsSource(
        scope: BrowseScope,
        categoryFilterRowIdCsv: String
    ): androidx.paging.PagingSource<Int, QuestionRef> =
        db.questionDao().pagingRefsByCategories(
            paperId = scope.paperId,
            query = scope.query,
            noAnswer = scope.noAnswer,
            noExplanation = scope.noExplanation,
            uncategorized = scope.uncategorized,
            categoryFilter = scope.categoryFilter,
            categoryFilterRowIdCsv = categoryFilterRowIdCsv
        )

    /** One category's refs in order. Reorder math works per category. */
    suspend fun browseCategoryRefs(categoryId: String): List<QuestionRef> =
        db.questionDao().getRefsByCategoryOrdered(categoryId)

    /** The category's first and last question ids, for its section-edge buttons. */
    suspend fun browseCategoryEdges(categoryId: String): Pair<String?, String?> =
        db.questionDao().getFirstRefIdByCategory(categoryId) to
            db.questionDao().getLastRefIdByCategory(categoryId)

    /** The passage's first member in its own order (headers key off it). */
    suspend fun browsePassageFirstRefId(passageId: String): String? =
        db.questionDao().getFirstRefIdByPassage(passageId)

    /** The scope's size, for the editor's "N of M" without listing every id. */
    suspend fun browseFilteredCount(scope: BrowseScope): Int =
        db.questionDao().countBrowseIds(
            scope.paperId, scope.query, scope.noAnswer, scope.noExplanation,
            scope.uncategorized, scope.categoryFilter, categoryRowIdCsv(scope)
        )

    /**
     * Every id in the scope, in browse order, for Select all. Same SQL scope
     * as the paging source, so it cannot drift from what the list shows.
     */
    suspend fun browseFilteredIds(scope: BrowseScope): List<String> =
        db.questionDao().browseIdsByCategories(
            scope.paperId, scope.query, scope.noAnswer, scope.noExplanation,
            scope.uncategorized, scope.categoryFilter, categoryRowIdCsv(scope)
        )

    /** The id at one position in the scope, for the editor's Prev/Next. */
    suspend fun browseFilteredIdAt(offset: Int, scope: BrowseScope): String? =
        db.questionDao().browseIdAtIndex(
            offset, scope.paperId, scope.query, scope.noAnswer, scope.noExplanation,
            scope.uncategorized, scope.categoryFilter, categoryRowIdCsv(scope)
        )

    /** The anchor's index in the scope, for the editor's "N of M". */
    suspend fun browseFilteredIndexOf(anchorId: String, scope: BrowseScope): Int =
        db.questionDao().browseIndexBefore(
            anchorId, scope.paperId, scope.query, scope.noAnswer, scope.noExplanation,
            scope.uncategorized, scope.categoryFilter, categoryRowIdCsv(scope)
        )
    suspend fun getQuestion(questionId: String): Question? = questionStore.getQuestion(questionId)
    suspend fun getQuestionsByIds(ids: List<String>): List<Question> =
        questionStore.getQuestionsByIds(ids)
    suspend fun ensurePaperAndCategory(
        paperId: String,
        paperTitle: String,
        categoryId: String,
        categoryTitle: String
    ) = questionStore.ensurePaperAndCategory(paperId, paperTitle, categoryId, categoryTitle)
    suspend fun saveQuestion(question: Question) = questionStore.saveQuestion(question)
    suspend fun deleteQuestion(questionId: String) = questionStore.deleteQuestion(questionId)
    suspend fun deleteQuestions(questionIds: Collection<String>) =
        questionStore.deleteQuestions(questionIds)
    suspend fun duplicateQuestion(questionId: String): String? =
        questionStore.duplicateQuestion(questionId)
    suspend fun moveQuestionsToCategory(questionIds: Collection<String>, targetCategoryId: String) =
        questionStore.moveQuestionsToCategory(questionIds, targetCategoryId)
    suspend fun copyQuestionsToCategory(questionIds: Collection<String>, targetCategoryId: String) =
        questionStore.copyQuestionsToCategory(questionIds, targetCategoryId)
    suspend fun bulkUpdateQuestions(
        questionIds: Collection<String>,
        marks: Double?,
        difficulty: com.mcqapp.domain.Difficulty?,
        tags: List<String>?
    ) = questionStore.bulkUpdateQuestions(questionIds, marks, difficulty, tags)
    suspend fun swapQuestionOrder(firstId: String, secondId: String): Boolean =
        questionStore.swapQuestionOrder(firstId, secondId)

    // --- spaced repetition ---

    suspend fun getStudyQueue(
        paperId: String,
        now: Long = System.currentTimeMillis(),
        leechesOnly: Boolean = false,
        categoryIds: List<String> = emptyList()
    ): List<com.mcqapp.domain.StudyCard> =
        studyStore.getStudyQueue(paperId, now, leechesOnly, categoryIds)
    /**
     * The card a pull-based session serves next, or null when nothing is due.
     * [categoryIds] empty means the whole paper; a non-empty list scopes both
     * the learning and the review head to it.
     */
    suspend fun getNextStudyCard(
        paperId: String,
        reviewsSinceNew: Int = 0,
        now: Long = System.currentTimeMillis(),
        categoryIds: List<String> = emptyList()
    ): com.mcqapp.domain.StudyCard? =
        studyStore.getNextStudyCard(paperId, reviewsSinceNew, now, categoryIds)
    /** Per-pool eligibility: what a session would serve right now, and why. */
    suspend fun getStudyEligibility(
        paperId: String,
        now: Long = System.currentTimeMillis(),
        /** Overrides the stored introduce-new-cards toggle; see [StudyStore]. */
        introduceNewCards: Boolean? = null,
        categoryIds: List<String> = emptyList()
    ): com.mcqapp.domain.StudyEligibility =
        studyStore.getStudyEligibility(paperId, now, introduceNewCards, categoryIds)
    suspend fun getStudyCounts(
        paperId: String,
        now: Long = System.currentTimeMillis()
    ): StudyCounts = studyStore.getStudyCounts(paperId, now)
    suspend fun categoryCounts(paperId: String, now: Long = System.currentTimeMillis()):
        Map<String, com.mcqapp.domain.CategoryCounts> = studyStore.categoryCounts(paperId, now)
    suspend fun cardState(paperId: String, questionId: String): com.mcqapp.domain.CardState? =
        studyStore.cardState(paperId, questionId)
    suspend fun recordStudyReview(
        paperId: String,
        questionId: String,
        grade: com.mcqapp.domain.ReviewGrade,
        dwellSeconds: Long? = null,
        now: Long = System.currentTimeMillis(),
        wasGuess: Boolean? = null,
        wasCorrect: Boolean? = null,
        proposedGrade: com.mcqapp.domain.ReviewGrade? = null,
        gradeSource: com.mcqapp.domain.GradeSource =
            com.mcqapp.domain.GradeProvenance.source(proposedGrade, edited = false),
        wasSkipped: Boolean? = null,
        /** The session's own config snapshot; null resolves the stored one. */
        config: com.mcqapp.domain.SchedulerConfig? = null
    ): com.mcqapp.domain.CardState =
        studyStore.recordStudyReview(
            paperId = paperId,
            questionId = questionId,
            grade = grade,
            config = config ?: schedulerConfigNow(),
            dwellSeconds = dwellSeconds,
            now = now,
            wasGuess = wasGuess,
            wasCorrect = wasCorrect,
            proposedGrade = proposedGrade,
            gradeSource = gradeSource,
            wasSkipped = wasSkipped
        )

    /** Records one finished sitting's real study time (skips empty sittings). */
    suspend fun recordStudySession(
        paperId: String,
        startedAt: Long,
        endedAt: Long,
        activeSeconds: Long
    ): Long = studyStore.recordStudySession(paperId, startedAt, endedAt, activeSeconds)

    // --- bookmarks, attempts, mistakes ---

    fun observeBookmarks(): Flow<List<String>> = historyStore.observeBookmarks()

    /** A signal that the bookmark set changed, for paging the bookmarks screen. */

    /** How many bookmarks there are, for the header count and the empty state. */
    fun observeBookmarkCount(): Flow<Int> = historyStore.observeBookmarkCount()
    fun observeBookmarkChanges(): Flow<Unit> = historyStore.observeBookmarkChanges()
    suspend fun toggleBookmark(questionId: String) = historyStore.toggleBookmark(questionId)
    /** Idempotent: removing an already-removed bookmark is a no-op. */
    suspend fun deleteBookmark(questionId: String) = historyStore.deleteBookmark(questionId)
    suspend fun isBookmarked(questionId: String): Boolean = historyStore.isBookmarked(questionId)

    /**
     * Every bookmarked question, with its paper. Linear in the bank, so it is
     * the tests' oracle — the screen pages its rows with
     * [bookmarkedPagingSource] — and `NoDeadCodeTest` refuses a production call.
     */
    suspend fun getBookmarkedQuestions(): List<com.mcqapp.domain.BookmarkedQuestion> =
        historyStore.getBookmarkedQuestions()
    fun bookmarkedPagingSource(): androidx.paging.PagingSource<com.mcqapp.data.repository.BookmarkPageKey, com.mcqapp.domain.BookmarkedQuestion> =
        historyStore.bookmarkedPagingSource()
    suspend fun saveAttempt(
        paperId: String,
        paperTitle: String,
        questions: List<Question>,
        selections: Map<String, Set<String>>,
        negativeMarking: Double,
        durationSeconds: Long,
        finishedAt: Long,
        dwellSeconds: Map<String, Long> = emptyMap(),
        metadata: com.mcqapp.domain.AttemptMetadata =
            com.mcqapp.domain.AttemptMetadata()
    ): Long = historyStore.saveAttempt(
        paperId, paperTitle, questions, selections, negativeMarking,
        durationSeconds, finishedAt, dwellSeconds, metadata
    )

    /**
     * Same save, but every question is resolved from the database in batches,
     * straight off the session's order: a full-paper test of a huge paper never
     * holds every question at once, and never materialises its id list either.
     */
    suspend fun saveAttemptStreamed(
        paperId: String,
        paperTitle: String,
        order: SessionOrder,
        selections: Map<String, Set<String>>,
        negativeMarking: Double,
        durationSeconds: Long,
        finishedAt: Long,
        dwellSeconds: Map<String, Long> = emptyMap(),
        metadata: com.mcqapp.domain.AttemptMetadata =
            com.mcqapp.domain.AttemptMetadata(),
        onProgress: (stored: Int, total: Int) -> Unit = { _, _ -> }
    ): Long = historyStore.saveAttemptStreamed(
        paperId, paperTitle, order, selections, negativeMarking,
        durationSeconds, finishedAt, dwellSeconds, metadata, onProgress
    )
    fun observeAttempts(): Flow<List<com.mcqapp.domain.Attempt>> = historyStore.observeAttempts()
    suspend fun getAttempt(attemptId: Long): com.mcqapp.domain.Attempt? =
        historyStore.getAttempt(attemptId)
    /**
     * Every result row of an attempt, in one list. Linear in the attempt, so it
     * is the tests' oracle — the screen pages its rows with
     * [resultPagingSource] and reads the header from the SQL aggregates — and
     * `NoDeadCodeTest` refuses a production call.
     */
    suspend fun getAttemptResults(attemptId: Long): List<com.mcqapp.domain.QuestionResult> =
        historyStore.getAttemptResults(attemptId)
    fun resultPagingSource(
        attemptId: Long,
        filter: String,
        slowSeconds: () -> Long = { com.mcqapp.domain.ReviewFilters.DEFAULT_SLOW_SECONDS },
        bookmarked: () -> Set<String>
    ): androidx.paging.PagingSource<ResultPageKey, com.mcqapp.domain.NumberedResult> =
        historyStore.resultPagingSource(attemptId, filter, slowSeconds, bookmarked)
    suspend fun getResultAggregates(attemptId: Long) =
        historyStore.getResultAggregates(attemptId)
    suspend fun getResultCategoryBreakdown(attemptId: Long) =
        historyStore.getResultCategoryBreakdown(attemptId)
    /** The result report's light per-question evidence (section 11.14). */
    suspend fun getReportRows(attemptId: Long): List<com.mcqapp.domain.ReportRow> =
        historyStore.getReportRows(attemptId)
    /** Raw result entities for report export. */
    suspend fun getAttemptResultEntities(attemptId: Long) =
        historyStore.getAttemptResultEntities(attemptId)
    /** Question tags by id, for report export. */
    suspend fun getQuestionTagsByIds(ids: List<String>): Map<String, String> =
        db.questionDao().getTagsByIdsChunked(ids)
    /** Same-key attempts older than [attempt], newest first (section 11.5). */
    suspend fun getComparableAttempts(
        attempt: com.mcqapp.domain.Attempt,
        limit: Int = 5
    ): List<com.mcqapp.domain.Attempt> = historyStore.getComparableAttempts(attempt, limit)

    /**
     * Per-question discrimination across same-key attempts (slice 4). Only a
     * representative attempt gets one: a practice set is chosen on the
     * dependent variable, so its item statistics are biased by design. Below
     * [com.mcqapp.domain.stats.Reliability.MIN_DISCRIMINATION_ATTEMPTS] the map
     * is empty rather than a noisy r.
     */
    suspend fun getItemDiscrimination(
        attempt: com.mcqapp.domain.Attempt,
        limit: Int = 30
    ): Map<String, com.mcqapp.domain.stats.Reliability.ItemDiscrimination> {
        val family = com.mcqapp.domain.TestReportBuilder.familyOf(attempt)
        if (family != com.mcqapp.domain.ReportFamily.MOCK &&
            family != com.mcqapp.domain.ReportFamily.FRESH_SAMPLE
        ) {
            return emptyMap()
        }
        val attempts = listOf(attempt) + getComparableAttempts(attempt, limit)
        if (attempts.size < com.mcqapp.domain.stats.Reliability.MIN_DISCRIMINATION_ATTEMPTS) {
            return emptyMap()
        }
        val vectors = attempts.map { candidate ->
            val rows = historyStore.getAttemptResultEntities(candidate.id)
                .filter { it.correctOptionIds.isNotBlank() }
            com.mcqapp.domain.stats.Reliability.AttemptVector(
                questionIds = rows.map { it.questionId },
                scores = rows.map { if (it.isCorrect) 1.0 else 0.0 }
            )
        }
        return com.mcqapp.domain.stats.Reliability.discriminationByQuestion(vectors)
    }

    /**
     * The phase-2 report evidence (sections 11.8, 11.10). Read-only: it reads
     * `card_state`, `question_stats` and prior results, and never writes Study
     * state.
     */
    suspend fun getReportEvidence(
        attempt: com.mcqapp.domain.Attempt,
        rows: List<com.mcqapp.domain.ReportRow>,
        paper: com.mcqapp.domain.Paper?,
        now: Long = System.currentTimeMillis()
    ): com.mcqapp.domain.ReportEvidence {
        val config = schedulerConfigNow()
        val scheduler = com.mcqapp.domain.FsrsScheduler(
            desiredRetention = config.desiredRetention,
            config = config
        )
        return historyStore.getReportEvidence(attempt, rows, paper, scheduler, now)
    }

    /**
     * Earlier practice runs on the same recorded target set, oldest first
     * (stats.md §11.5). Practice is never compared with a mock, so this line
     * exists only for the practice trend and reads the light result rows of at
     * most five prior runs.
     */
    suspend fun getPracticeHistory(
        attempt: com.mcqapp.domain.Attempt
    ): List<com.mcqapp.domain.PracticeTrendPoint> {
        val targets = attempt.targetTopicIds
            .split(",")
            .filter { it.isNotBlank() }
            .sorted()
        if (targets.isEmpty()) return emptyList()
        val type = com.mcqapp.domain.TestType.fromStorage(attempt.testType) ?: return emptyList()
        if (type.isRepresentative) return emptyList()
        if (com.mcqapp.domain.TestReportBuilder.familyOf(attempt) !=
            com.mcqapp.domain.ReportFamily.PRACTICE
        ) {
            return emptyList()
        }
        val targetSet = targets.toHashSet()
        val prior = getComparableAttempts(attempt, limit = 5)
            .filter {
                com.mcqapp.domain.TestReportBuilder.familyOf(it) ==
                    com.mcqapp.domain.ReportFamily.PRACTICE &&
                    it.targetTopicIds.split(",")
                        .filter { id -> id.isNotBlank() }
                        .sorted() == targets
            }
            .sortedBy { it.finishedAt }
        return prior.map { earlier ->
            val rows = getReportRows(earlier.id)
            com.mcqapp.domain.PracticeTrendPoint(
                attemptId = earlier.id,
                finishedAt = earlier.finishedAt,
                asked = rows.count { it.currentCategoryId in targetSet },
                correct = rows.count {
                    it.currentCategoryId in targetSet && it.graded && it.answered && it.isCorrect
                }
            )
        }
    }

    /**
     * Questions asked in the last three comparable representative attempts of
     * this paper, scope and length (section 9.8). The session excludes their
     * blocks only when enough alternatives remain.
     */
    suspend fun recentComparableQuestionIds(
        paperId: String,
        testTypes: List<String>,
        scopeKey: String,
        requestedCount: Int,
        limit: Int = 3
    ): Set<String> = historyStore.recentComparableQuestionIds(
        paperId = paperId,
        testTypes = testTypes,
        scopeKey = scopeKey,
        requestedCount = requestedCount,
        limit = limit
    )
    suspend fun getAllQuestionResults(): List<com.mcqapp.domain.QuestionResult> =
        historyStore.getAllQuestionResults()
    suspend fun getHardestQuestions(limit: Int = 5): List<com.mcqapp.domain.QuestionStats.Stat> =
        historyStore.getHardestQuestions(limit)
    suspend fun getWeakestCategories(limit: Int = 5): List<com.mcqapp.domain.Mastery.CategoryMastery> =
        historyStore.getWeakestCategories(limit)
    suspend fun getAttempts(): List<com.mcqapp.domain.Attempt> = historyStore.getAttempts()

    /**
     * The Statistics "Tests" tab (stats.md §13 phase 2): attempt families,
     * the last three focus-practice runs and the scope's current leech,
     * at-risk and unseen counts. The practice runs read their own bounded
     * result rows, never a whole-paper result list.
     */
    suspend fun getTestProgress(
        paperId: String?,
        now: Long = System.currentTimeMillis()
    ): com.mcqapp.domain.TestProgressData {
        val attempts = getAttempts().filter { paperId == null || it.paperId == paperId }
        val practicePairs = com.mcqapp.domain.TestProgress.practiceRuns(attempts).map { attempt ->
            val rows = historyStore.getReportRows(attempt.id)
            val paper = getPaper(attempt.paperId)
            val evidence = getReportEvidence(attempt, rows, paper, now)
            val report = com.mcqapp.domain.TestReportBuilder.build(
                com.mcqapp.domain.TestReportInput(
                    attempt = attempt,
                    rows = rows,
                    paper = paper,
                    targetTopicIds = attempt.targetTopicIds
                        .split(",")
                        .filter { it.isNotBlank() },
                    evidence = evidence
                )
            )
            report to com.mcqapp.domain.PracticeRun(
                attemptId = attempt.id,
                type = com.mcqapp.domain.TestType.fromStorage(attempt.testType),
                finishedAt = attempt.finishedAt,
                targetedAsked = report.practice?.targetedAsked ?: 0,
                targetedCorrect = report.practice?.targetedCorrect ?: 0,
                overallAccuracy = report.score.accuracy,
                mistakesCleared = report.mistakes?.cleared ?: 0,
                targets = attempt.targetTopicIds.split(",").filter { it.isNotBlank() }
            )
        }
        return com.mcqapp.domain.TestProgressData(
            families = com.mcqapp.domain.TestProgress.families(attempts),
            practiceRuns = practicePairs.map { it.second },
            practiceTopics = com.mcqapp.domain.TestProgress.practiceTopics(
                practicePairs.map { it.first }
            ),
            leeches = db.cardStateDao().countLeechesInScope(paperId),
            atRisk = db.cardStateDao().countAtRisk(paperId, now),
            unseen = db.questionDao().countUnseenInScope(paperId),
            scopeSize = db.questionDao().countQuestionsInScope(paperId),
            scopeIsWholeLibrary = paperId == null,
            reliability = computeReliabilitySummary(attempts),
            panels = com.mcqapp.domain.TestProgress.panels(
                panels = if (paperId == null) allPanels() else panelsForPaper(paperId),
                attempts = attempts
            ),
            exams = buildExamStats(attempts, paperId)
        )
    }

    /**
     * The Exam view's three comparable groups (plan §3). Attempt headers are
     * already small; the expensive reads are aggregate-only (category tallies,
     * dwell) and are restricted to the exact attempt ids behind each group's
     * headline figures. Each group carries its profiles latest-first, capped
     * at 8, each built from its own comparison key.
     */
    private suspend fun buildExamStats(
        attempts: List<com.mcqapp.domain.Attempt>,
        paperId: String?
    ): Map<ExamGroup, List<ExamStats>> {
        val counters = db.questionStatsDao().getExamCounterTotals(paperId)
        // Global recent, exactly as ExamStatsBuilder builds it (all attempts).
        val globalRecent = attempts
            .sortedWith(compareByDescending<com.mcqapp.domain.Attempt> { it.finishedAt }.thenByDescending { it.id })
            .take(5)
            .map {
                com.mcqapp.domain.stats.ExamRecentAttempt(
                    attemptId = it.id,
                    title = it.title,
                    testType = it.testType,
                    percent = it.percentage,
                    correct = it.correctCount,
                    shown = it.totalQuestions,
                    finishedAt = it.finishedAt,
                    family = com.mcqapp.domain.TestReportBuilder.familyOf(it)
                )
            }
        return ExamGroup.entries.associateWith { group ->
            val family = attempts
                .filter { com.mcqapp.domain.TestReportBuilder.familyOf(it) == group.family }
                .sortedWith(compareBy({ it.finishedAt }, { it.id }))
            val lifetime = if (group == ExamGroup.PRACTICE) {
                ExamCounterTotals(
                    correct = counters.correct,
                    wrong = counters.wrong,
                    skip = counters.skip
                )
            } else {
                ExamCounterTotals(
                    correct = counters.repCorrect,
                    wrong = counters.repWrong,
                    skip = counters.repSkip
                )
            }
            if (family.isEmpty()) {
                // Keep the old empty shape: one empty profile so the tab can
                // show its empty state and the global recent list.
                val empty = ExamStatsBuilder.build(
                    attempts = attempts,
                    group = group,
                    categoryTotals = emptyList(),
                    dwell = ExamDwellTotals(),
                    lifetime = lifetime,
                    population = 0
                )
                return@associateWith listOf(empty.copy(recent = globalRecent))
            }
            // Distinct keys latest-first.
            val latestByKey = family
                .groupBy { ExamComparisonKey.of(it) }
                .mapValues { (_, runs) ->
                    runs.sortedWith(compareBy({ it.finishedAt }, { it.id })).last()
                }
            val keys = latestByKey.entries
                .sortedWith(
                    compareByDescending<Map.Entry<ExamComparisonKey, com.mcqapp.domain.Attempt>> { it.value.finishedAt }
                        .thenByDescending { it.value.id }
                )
                .map { it.key }
                .take(8)
            keys.map { key ->
                val comparable = family.filter { ExamComparisonKey.of(it) == key }
                val ids = comparable.map { it.id }
                val population = comparable.lastOrNull()
                    ?.let { db.questionDao().countQuestionsInScope(it.paperId) }
                    ?: 0
                val base = ExamStatsBuilder.build(
                    attempts = comparable,
                    group = group,
                    categoryTotals = examCategoryTotals(ids),
                    dwell = examDwellTotals(ids),
                    lifetime = lifetime,
                    population = population
                )
                val latest = comparable.last()
                base.copy(
                    label = compositionLabel(latest.compositionJson),
                    familyAttempts = family.size,
                    recent = globalRecent
                )
            }
        }
    }

    /**
     * The label a profile shows: the blueprint name recorded in the latest
     * attempt's composition.
     */
    private fun compositionLabel(raw: String?): String? {
        return Composition.fromJson(raw)?.label?.takeIf { it.isNotBlank() }
    }

    /** Category tallies over [attemptIds], merged across bind-safe chunks. */
    private suspend fun examCategoryTotals(attemptIds: List<Long>): List<ExamCategoryTotal> {
        if (attemptIds.isEmpty()) return emptyList()
        val merged = LinkedHashMap<String, MutableCategory>()
        for (chunk in attemptIds.chunks()) {
            for (row in db.attemptDao().getExamCategoryTotals(chunk)) {
                val total = merged.getOrPut(row.title) { MutableCategory(row.title) }
                total.asked += row.asked
                total.correct += row.correct
                total.unresolved += row.unresolved
                if (total.currentCategoryId == null) total.currentCategoryId = row.currentCategoryId
            }
        }
        return merged.values.map {
            ExamCategoryTotal(
                title = it.title,
                asked = it.asked,
                correct = it.correct,
                currentCategoryId = it.currentCategoryId,
                unresolved = it.unresolved
            )
        }
    }

    /** Measured dwell over [attemptIds]; chunks are disjoint, so sums add. */
    private suspend fun examDwellTotals(attemptIds: List<Long>): ExamDwellTotals {
        if (attemptIds.isEmpty()) return ExamDwellTotals()
        var totalSeconds = 0L
        var timedRows = 0
        for (chunk in attemptIds.chunks()) {
            val row = db.attemptDao().getExamDwellTotals(chunk)
            totalSeconds += row.totalSeconds
            timedRows += row.timedRows
        }
        return ExamDwellTotals(totalSeconds, timedRows)
    }

    private class MutableCategory(val title: String) {
        var asked = 0
        var correct = 0
        var unresolved = 0
        var currentCategoryId: String? = null
    }

    private suspend fun computeReliabilitySummary(
        attempts: List<com.mcqapp.domain.Attempt>
    ): com.mcqapp.domain.ReliabilitySummary? {
        val comparable = attempts.filter {
            val family = com.mcqapp.domain.TestReportBuilder.familyOf(it)
            family == com.mcqapp.domain.ReportFamily.MOCK ||
                family == com.mcqapp.domain.ReportFamily.FRESH_SAMPLE
        }
        if (comparable.size < 2) return null
        val group = comparable
            .groupBy { listOf(it.paperId, it.testType, it.scopeKey, it.requestedCount.toString()) }
            .values
            .maxByOrNull { it.size }
            ?: return null
        if (group.size < 2) return null
        val sample = group.sortedByDescending { it.finishedAt }.take(30)
        val vectors = sample.map { attempt ->
            val rows = historyStore.getAttemptResultEntities(attempt.id)
            val ids = rows.map { it.questionId }
            val scores = rows.map { if (it.isCorrect) 1.0 else 0.0 }
            ids to scores
        }
        if (vectors.isEmpty()) return null
        val n = vectors.first().second.size
        if (n < com.mcqapp.domain.stats.Reliability.MIN_ATTEMPTED) return null
        val sameForm = vectors.all { it.first == vectors.first().first && it.second.size == n }
        val scoreMatrix = vectors.map { it.second }
        val splitRels = scoreMatrix.mapNotNull { scores ->
            when (val r = com.mcqapp.domain.stats.Reliability.splitHalf(scores)) {
                is com.mcqapp.domain.stats.Reliability.SplitHalfResult.Value -> r.reliability
                is com.mcqapp.domain.stats.Reliability.SplitHalfResult.TooSmall -> null
            }
        }
        // With one form, reliability is the correlation of half-scores across
        // attempts (the standard split-half estimator, and the one comparable
        // to KR-20). Averaging each attempt's within-attempt odd/even
        // correlation measures the sitting, not the test. Different forms fall
        // back to that per-attempt mean because there is one vector per form.
        val acrossAttempts = if (sameForm) {
            com.mcqapp.domain.stats.Reliability.splitHalfAcrossAttempts(scoreMatrix)
        } else {
            null
        }
        if (acrossAttempts == null && splitRels.isEmpty()) return null
        val meanRel = acrossAttempts ?: splitRels.average()
        val totals = scoreMatrix.map { it.sum() }
        val sd = if (totals.size >= 2) {
            val mean = totals.average()
            kotlin.math.sqrt(totals.sumOf { (it - mean) * (it - mean) } / totals.size)
        } else 0.0
        val sem = com.mcqapp.domain.stats.Reliability.sem(sd, meanRel)?.semPoints ?: 0.0
        val kr20 = if (sameForm) com.mcqapp.domain.stats.Reliability.kr20(scoreMatrix) else null
        return com.mcqapp.domain.ReliabilitySummary(
            reliability = if (meanRel.isFinite()) meanRel else 0.0,
            semPoints = if (sem.isFinite()) sem else 0.0,
            kr20 = kr20,
            attempted = n
        )
    }
    /** Wall-clock finish of the paper's latest attempt, or null when it has none. */
    suspend fun latestAttemptFinishedAt(paperId: String): Long? =
        db.attemptDao().getLatestFinishedAt(paperId)
    suspend fun getMistakeCounts(): Map<String, Int> = historyStore.getMistakeCounts()
    /**
     * Counts the mistakes inside a scoped selection. The paper-level badge
     * counts every mistake in the paper, which would overstate a scoped
     * pool, so the scope needs its own count.
     */
    suspend fun getScopedMistakeCount(paperId: String, scopeIds: List<String>): Int =
        historyStore.getScopedMistakeCount(paperId, scopeIds)
    /** Mistaken questions as a session order: content is fetched when one is opened. */
    suspend fun getMistakenQuestionOrder(paperId: String): SessionOrder =
        SessionOrder.of(historyStore.getMistakenQuestionRefs(paperId))

    /**
     * The same order, restricted to an expanded category scope. The filter runs
     * in SQL: a [SessionOrder] holds question ids, not categories.
     */
    suspend fun getMistakenQuestionOrder(
        paperId: String,
        scopeIds: List<String>
    ): SessionOrder =
        SessionOrder.of(historyStore.getScopedMistakenQuestionRefs(paperId, scopeIds))

    /**
     * How many of these questions have no answer key — excluded from scoring.
     * Ids only: never loads question content to count them. Only the sampled
     * sessions (a drill, mistakes-only) count by id, where the session's
     * questions are bounded by what was asked for; whole-paper and category
     * sessions use the aggregates below.
     */
    suspend fun countUngraded(questionIds: List<String>): Int {
        if (questionIds.isEmpty()) return 0
        val withKeys = db.correctAnswerDao().getQuestionIdsWithKeysChunked(questionIds)
        return questionIds.count { it !in withKeys }
    }

    /** Ungraded questions of a whole paper: one aggregate, no ids read. */
    suspend fun countUngradedForPaper(paperId: String): Int =
        db.questionDao().countUngradedForPaper(paperId)

    /** [countUngradedForPaper] over an explicit category selection. */
    suspend fun countUngradedForCategories(categoryIds: List<String>): Int =
        if (categoryIds.isEmpty()) 0
        else db.questionDao().countUngradedForCategoriesChunked(categoryIds)

suspend fun getMistakenQuestions(paperId: String): List<Question> =
        historyStore.getMistakenQuestions(paperId)
    suspend fun getBookmarkExportDto(): com.mcqapp.data.io.PaperDto? =
        historyStore.getBookmarkExportDto()
    suspend fun deleteAttempt(attemptId: Long) = historyStore.deleteAttempt(attemptId)
}

/** One window of [McqRepository.searchPage]. */
data class GlobalSearchPage(
    val hits: List<com.mcqapp.domain.QuestionSearch.Hit>,
    /** Next prefilter offset, or null once the candidate set is exhausted. */
    val nextOffset: Int?
)

/**
 * Candidate questions fetched (and then post-filtered) per search window.
 * Small enough that a page of matches is never heavy, large enough that a
 * typical query needs one read.
 */
private const val SEARCH_WINDOW = 200

/** Browse's chip filter, translated to the paging query's flags. */
enum class BrowseListFilter { ALL, NO_ANSWER, NO_EXPLANATION, UNCATEGORIZED }

/**
 * Pages [McqRepository.searchPage]: walks candidate windows until a page holds
 * `loadSize` post-filtered hits (or the candidates run out). The returned key
 * is the next candidate offset, so the phase-2 filter can drop rows without
 * shifting any later hit's position.
 */
private class SearchPagingSource(
    private val window: Int,
    private val fetch: suspend (offset: Int, limit: Int) -> GlobalSearchPage
) : androidx.paging.PagingSource<Int, com.mcqapp.domain.QuestionSearch.Hit>() {

    override suspend fun load(
        params: androidx.paging.PagingSource.LoadParams<Int>
    ): androidx.paging.PagingSource.LoadResult<Int, com.mcqapp.domain.QuestionSearch.Hit> {
        var offset = params.key ?: 0
        val target = params.loadSize.coerceAtLeast(1)
        val out = mutableListOf<com.mcqapp.domain.QuestionSearch.Hit>()
        var nextKey: Int? = null
        return try {
            while (out.size < target) {
                val page = fetch(offset, window)
                out += page.hits
                // Assigned before the null check: a terminal window has to
                // clear the key, or the next load re-fetches the window it
                // just consumed and repeats those hits.
                val advanced = page.nextOffset
                nextKey = advanced
                if (advanced == null) break
                offset = advanced
            }
            androidx.paging.PagingSource.LoadResult.Page(
                data = out,
                prevKey = null,
                nextKey = nextKey
            )
        } catch (e: Exception) {
            androidx.paging.PagingSource.LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(
        state: androidx.paging.PagingState<Int, com.mcqapp.domain.QuestionSearch.Hit>
    ): Int? = null
}
