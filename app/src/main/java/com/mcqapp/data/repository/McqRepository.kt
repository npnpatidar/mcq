package com.mcqapp.data.repository

import android.content.Context
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.countUngradedForCategoriesChunked
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.local.getQuestionIdsWithKeysChunked
import com.mcqapp.domain.FsrsFitResult
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.domain.SessionOrder
import com.mcqapp.domain.TestAnswer
import com.mcqapp.domain.TestSnapshot
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.searchableText
import com.mcqapp.domain.stats.CardsData
import com.mcqapp.domain.stats.MemoryData
import com.mcqapp.domain.stats.OverviewData
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.RetentionData
import com.mcqapp.domain.stats.StatsAggregator
import com.mcqapp.domain.stats.WorkloadData
import kotlinx.coroutines.flow.Flow

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
 */
class McqRepository(private val db: AppDatabase, private val context: Context) {

    private val settings = SettingsStore(context)
    private val mapper = QuestionContentMapper(db)
    private val questionStore = QuestionStore(db, mapper)
    private val passageStore = PassageStore(db, mapper, questionStore)
    private val paperStore = PaperStore(db, settings)
    private val studyStore = StudyStore(db, mapper, questionStore, settings)
    private val historyStore = HistoryStore(db, mapper, questionStore)
    private val fsrsTuner = FsrsTuner(db, settings)
    private val statsStore = StatsStore(db, settings)
    private val testProgress = TestProgressStore(db, settings)

    fun db(): AppDatabase = db

    // --- settings ---

    fun themeMode(): Flow<String> = settings.themeMode()
    suspend fun setThemeMode(mode: String) = settings.setThemeMode(mode)

    fun shuffleQuestions(): Flow<Boolean> = settings.shuffleQuestions()
    suspend fun setShuffleQuestions(enabled: Boolean) = settings.setShuffleQuestions(enabled)

    fun simplifiedStudy(): Flow<Boolean> = settings.simplifiedStudy()
    suspend fun setSimplifiedStudy(enabled: Boolean) = settings.setSimplifiedStudy(enabled)

    fun shuffleOptions(): Flow<Boolean> = settings.shuffleOptions()
    suspend fun setShuffleOptions(enabled: Boolean) = settings.setShuffleOptions(enabled)

    fun pdfTwoColumn(): Flow<Boolean> = settings.pdfTwoColumn()
    suspend fun setPdfTwoColumn(enabled: Boolean) = settings.setPdfTwoColumn(enabled)

    fun practiceMode(): Flow<Boolean> = settings.practiceMode()
    suspend fun setPracticeMode(enabled: Boolean) = settings.setPracticeMode(enabled)

    fun strictMode(): Flow<Boolean> = settings.strictMode()
    suspend fun setStrictMode(enabled: Boolean) = settings.setStrictMode(enabled)

    fun updateAnswersOnDuplicate(): Flow<Boolean> = settings.updateAnswersOnDuplicate()
    suspend fun setUpdateAnswersOnDuplicate(enabled: Boolean) =
        settings.setUpdateAnswersOnDuplicate(enabled)

    fun autoAdvance(): Flow<Boolean> = settings.autoAdvance()
    suspend fun setAutoAdvance(enabled: Boolean) = settings.setAutoAdvance(enabled)

    fun loadRemoteImages(): Flow<Boolean> = settings.loadRemoteImages()
    suspend fun setLoadRemoteImages(enabled: Boolean) = settings.setLoadRemoteImages(enabled)

    fun fontScale(): Flow<Float> = settings.fontScale()
    suspend fun setFontScale(scale: Float) = settings.setFontScale(scale)

    fun schedulerConfig(): Flow<com.mcqapp.domain.SchedulerConfig> = settings.schedulerConfig()
    suspend fun schedulerConfigNow(): com.mcqapp.domain.SchedulerConfig =
        settings.schedulerConfigNow()
    suspend fun setSchedulerConfig(config: com.mcqapp.domain.SchedulerConfig) =
        settings.setSchedulerConfig(config)
    suspend fun resetSchedulerConfig() = settings.resetSchedulerConfig()

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

    /** The replayed review log, for the tabs that still slice it in memory. */
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

    /** One question's logged reviews, oldest first, for the drill-down sheet. */
    suspend fun reviewHistory(questionId: String): List<com.mcqapp.domain.stats.ReviewHistoryEntry> =
        statsStore.reviewHistory(questionId)

    // --- in-progress test session (header in DataStore, answers in Room) ---

    /** The header a killed test session left behind, or null. */
    suspend fun loadTestSnapshot(): TestSnapshot? = testProgress.snapshot()

    /** The header joined with its stored answers: what a resume would restore. */
    suspend fun resumeOffer(paperId: String): TestSnapshot? = testProgress.offer(paperId)

    /** Persists the header and the questions whose answer just changed. */
    suspend fun saveTestProgress(snapshot: TestSnapshot, changed: List<TestAnswer>) =
        testProgress.save(snapshot, changed)

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

    /** Full replace of every preference from a backup. */
    suspend fun settingsRestore(snapshot: com.mcqapp.data.backup.McqBackupFormat.SettingsFile) =
        settings.restore(snapshot)

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
            passage.id to (passage.title + "\n" + passage.text.parseContentElements(mapper.json).searchableText)
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
        val dbBytes = try {
            context.getDatabasePath("mcq.db").length()
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
    suspend fun getSessionOrderForCategories(categoryIds: List<String>): SessionOrder =
        questionStore.getOrderForCategories(categoryIds)

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
        return ids.joinToString(separator = ",", prefix = ",", postfix = ",")
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
        leechesOnly: Boolean = false
    ): List<com.mcqapp.domain.StudyCard> =
        studyStore.getStudyQueue(paperId, now, leechesOnly)
    /** The card a pull-based session serves next, or null when nothing is due. */
    suspend fun getNextStudyCard(
        paperId: String,
        reviewsSinceNew: Int = 0,
        now: Long = System.currentTimeMillis()
    ): com.mcqapp.domain.StudyCard? =
        studyStore.getNextStudyCard(paperId, reviewsSinceNew, now)
    /** Per-pool eligibility: what a session would serve right now, and why. */
    suspend fun getStudyEligibility(
        paperId: String,
        now: Long = System.currentTimeMillis(),
        /** Overrides the stored introduce-new-cards toggle; see [StudyStore]. */
        introduceNewCards: Boolean? = null
    ): com.mcqapp.domain.StudyEligibility =
        studyStore.getStudyEligibility(paperId, now, introduceNewCards)
    suspend fun getStudyCounts(
        paperId: String,
        now: Long = System.currentTimeMillis()
    ): StudyCounts = studyStore.getStudyCounts(paperId, now)
    suspend fun cardState(paperId: String, questionId: String): com.mcqapp.domain.CardState? =
        studyStore.cardState(paperId, questionId)
    suspend fun recordStudyReview(
        paperId: String,
        questionId: String,
        grade: com.mcqapp.domain.ReviewGrade,
        dwellSeconds: Long? = null,
        now: Long = System.currentTimeMillis()
    ): com.mcqapp.domain.CardState =
        studyStore.recordStudyReview(paperId, questionId, grade, dwellSeconds, now)

    // --- bookmarks, attempts, mistakes ---

    fun observeBookmarks(): Flow<List<String>> = historyStore.observeBookmarks()

    /** A signal that the bookmark set changed, for paging the bookmarks screen. */
    fun observeBookmarkChanges(): Flow<Unit> = historyStore.observeBookmarkChanges()

    /** How many bookmarks there are, for the header count and the empty state. */
    fun observeBookmarkCount(): Flow<Int> = historyStore.observeBookmarkCount()
    suspend fun toggleBookmark(questionId: String) = historyStore.toggleBookmark(questionId)
    suspend fun isBookmarked(questionId: String): Boolean = historyStore.isBookmarked(questionId)

    /**
     * Every bookmarked question, with its paper. Linear in the bank, so it is
     * the tests' oracle — the screen pages its rows with
     * [bookmarkedPagingSource] — and `NoDeadCodeTest` refuses a production call.
     */
    suspend fun getBookmarkedQuestions(): List<com.mcqapp.domain.BookmarkedQuestion> =
        historyStore.getBookmarkedQuestions()
    fun bookmarkedPagingSource(): androidx.paging.PagingSource<Int, com.mcqapp.domain.BookmarkedQuestion> =
        historyStore.bookmarkedPagingSource()
    suspend fun saveAttempt(
        paperId: String,
        paperTitle: String,
        questions: List<Question>,
        selections: Map<String, Set<String>>,
        negativeMarking: Double,
        durationSeconds: Long,
        finishedAt: Long,
        dwellSeconds: Map<String, Long> = emptyMap()
    ): Long = historyStore.saveAttempt(
        paperId, paperTitle, questions, selections, negativeMarking,
        durationSeconds, finishedAt, dwellSeconds
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
        onProgress: (stored: Int, total: Int) -> Unit = { _, _ -> }
    ): Long = historyStore.saveAttemptStreamed(
        paperId, paperTitle, order, selections, negativeMarking,
        durationSeconds, finishedAt, dwellSeconds, onProgress
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
        bookmarked: Set<String>
    ): androidx.paging.PagingSource<ResultPageKey, com.mcqapp.domain.NumberedResult> =
        historyStore.resultPagingSource(attemptId, filter, bookmarked)
    suspend fun getResultAggregates(attemptId: Long) =
        historyStore.getResultAggregates(attemptId)
    suspend fun getResultCategoryBreakdown(attemptId: Long) =
        historyStore.getResultCategoryBreakdown(attemptId)
    suspend fun getAllQuestionResults(): List<com.mcqapp.domain.QuestionResult> =
        historyStore.getAllQuestionResults()
    suspend fun getHardestQuestions(limit: Int = 5): List<com.mcqapp.domain.QuestionStats.Stat> =
        historyStore.getHardestQuestions(limit)
    suspend fun getWeakestCategories(limit: Int = 5): List<com.mcqapp.domain.Mastery.CategoryMastery> =
        historyStore.getWeakestCategories(limit)
    suspend fun getAttempts(): List<com.mcqapp.domain.Attempt> = historyStore.getAttempts()
    suspend fun getMistakeCounts(): Map<String, Int> = historyStore.getMistakeCounts()
    /** Mistaken questions as a session order: content is fetched when one is opened. */
    suspend fun getMistakenQuestionOrder(paperId: String): SessionOrder =
        SessionOrder.of(historyStore.getMistakenQuestionRefs(paperId))

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
