package com.mcqapp.data.repository

import android.content.Context
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.local.getQuestionIdsWithKeysChunked
import com.mcqapp.domain.FsrsFitResult
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.searchableText
import kotlinx.coroutines.flow.Flow

/**
 * Facade over the repository's collaborators, keeping the public API every
 * ViewModel (and the JVM tests) already programs against stable:
 *
 * - [SettingsStore] — DataStore preferences, scheduler options, resume snapshot
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
    private val paperStore = PaperStore(db, questionStore, settings)
    private val studyStore = StudyStore(db, mapper, questionStore, settings)
    private val historyStore = HistoryStore(db, mapper, questionStore)
    private val fsrsTuner = FsrsTuner(db, settings)
    private val statsStore = StatsStore(db, settings)

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
     * The whole statistics input in one read: the replayed review log, every
     * card's schedule and the grouping titles. The stats ViewModel slices this
     * in memory per range and paper scope.
     */
    internal suspend fun statsRawData(): StatsStore.RawData = statsStore.load()

    /** One question's logged reviews, oldest first, for the drill-down sheet. */
    suspend fun reviewHistory(questionId: String): List<com.mcqapp.domain.stats.ReviewHistoryEntry> =
        statsStore.reviewHistory(questionId)

    suspend fun saveTestProgress(json: String) = settings.saveTestProgress(json)
    suspend fun loadTestProgress(): String? = settings.loadTestProgress()
    suspend fun clearTestProgress() = settings.clearTestProgress()

    /** Every preference, typed, for the backup's settings.json. */
    suspend fun settingsSnapshot() = settings.snapshot()

    /** Full replace of every preference from a backup. */
    suspend fun settingsRestore(snapshot: com.mcqapp.data.backup.McqBackupFormat.SettingsFile) =
        settings.restore(snapshot)

    // --- search & storage (no collaborator: they span everything) ---

    /** Cross-paper search with paper provenance attached. */
    suspend fun searchGlobal(
        query: String,
        scope: com.mcqapp.domain.QuestionSearch.Scope =
            com.mcqapp.domain.QuestionSearch.Scope.ALL
    ): List<com.mcqapp.domain.QuestionSearch.Hit> {
        if (query.isBlank()) return emptyList()
        // DAO prefilter must cover option texts whenever the scope needs them.
        // The prefilter is a SQL LIKE, so the user query is escaped first;
        // the in-memory post-filter below keeps matching the raw query.
        val like = com.mcqapp.domain.QuestionSearch.escapeLike(query)
        // The SQL pass is a prefilter, not the answer: `questions.text` holds the
        // elements JSON with markup in it, so a phrase straddling a tag or two
        // table cells is not a literal substring of it. Or in the longest single
        // word, which is contiguous within a tag, so the prefilter can no longer
        // veto a row the real filter would have matched.
        val fragment = com.mcqapp.domain.QuestionSearch.escapeLike(
            com.mcqapp.domain.QuestionSearch.prefilterKey(query)
        )
        val prefiltered = when (scope) {
            com.mcqapp.domain.QuestionSearch.Scope.QUESTION -> db.questionDao().search(like, fragment)
            else -> db.questionDao().searchIncludingOptions(like, fragment)
        }
        // Passage context rides into the post-filter so the DAO's passage
        // union survives it: a member found through its passage's text or
        // title must not be vetoed because that text is not on the question.
        val passageBodies = db.passageDao().getByIdsChunked(
            prefiltered.mapNotNull { it.passageId }.distinct()
        ).associate { passage ->
            passage.id to (passage.title + "\n" + passage.text.parseContentElements(mapper.json).searchableText)
        }
        val questions = mapper.toDomainBulk(prefiltered)
            .let {
                com.mcqapp.domain.QuestionSearch.filter(it, query, scope, passageBodies)
            }
        if (questions.isEmpty()) return emptyList()
        val papersById = db.paperDao().getAll().associateBy({ it.id }, { it.title })
        val paperByCategory = db.categoryDao().getAll().associate { cat ->
            cat.id to (cat.paperId to (papersById[cat.paperId] ?: "Paper"))
        }
        return com.mcqapp.domain.QuestionSearch.attach(questions, paperByCategory)
    }

    /** Storage breakdown for Settings: file size, row counts, per-paper weight. */
    suspend fun storageReport(): com.mcqapp.domain.StorageInfo.Report {
        val dbBytes = try {
            context.getDatabasePath("mcq.db").length()
        } catch (e: Exception) {
            -1L
        }
        val papers = db.paperDao().getAll()
        val allQuestions = db.questionDao().getAll()
        val optionsByQuestion = if (allQuestions.isEmpty()) {
            emptyMap()
        } else {
            db.optionDao().getForQuestionsChunked(allQuestions.map { it.id }).groupBy { it.questionId }
        }
        val attempts = db.attemptDao().getAllAttempts().size
        val bookmarks = db.bookmarkDao().getAll().size
        val perPaper = papers.map { paper ->
            val catIds = db.categoryDao().getByPaper(paper.id).map { it.id }.toHashSet()
            val questions = allQuestions.filter { it.categoryId in catIds }
            com.mcqapp.domain.StorageInfo.usageForPaper(paper.id, paper.title, questions, optionsByQuestion)
        }.sortedByDescending { it.imageChars }
        return com.mcqapp.domain.StorageInfo.Report(
            dbBytes = dbBytes,
            papers = papers.size,
            questions = allQuestions.size,
            attempts = attempts,
            bookmarks = bookmarks,
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
    suspend fun duplicatePaper(paperId: String): String? = paperStore.duplicatePaper(paperId)

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

    /** Scheduling-and-prefix columns only: no question content. */
    suspend fun getQuestionRefsForPaper(paperId: String): List<QuestionRef> =
        questionStore.getRefsForPaper(paperId)

    /** Observed refs for browse: the list re-emits on any write. */
    fun observeQuestionRefsForPaper(paperId: String): kotlinx.coroutines.flow.Flow<List<QuestionRef>> =
        questionStore.observeRefsForPaper(paperId)

    /** Domain questions for already-loaded entities (batch surfaces only). */
    suspend fun questionsOf(
        entities: List<com.mcqapp.data.local.QuestionEntity>
    ): List<Question> = questionStore.toDomain(entities)

    suspend fun getQuestionRefsForCategories(categoryIds: List<String>): List<QuestionRef> =
        questionStore.getRefsForCategories(categoryIds)
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
    suspend fun toggleBookmark(questionId: String) = historyStore.toggleBookmark(questionId)
    suspend fun isBookmarked(questionId: String): Boolean = historyStore.isBookmarked(questionId)
    suspend fun getBookmarkedQuestions(): List<com.mcqapp.domain.BookmarkedQuestion> =
        historyStore.getBookmarkedQuestions()
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
     * Same save, but the questions are ids resolved in batches: a full-paper
     * test of a huge paper never holds every question at once. The caller
     * passes the ordered ids it already holds.
     */
    suspend fun saveAttemptStreamed(
        paperId: String,
        paperTitle: String,
        questionIdsInOrder: List<String>,
        selections: Map<String, Set<String>>,
        negativeMarking: Double,
        durationSeconds: Long,
        finishedAt: Long,
        dwellSeconds: Map<String, Long> = emptyMap()
    ): Long = historyStore.saveAttemptStreamed(
        paperId, paperTitle, questionIdsInOrder, selections, negativeMarking,
        durationSeconds, finishedAt, dwellSeconds
    )
    fun observeAttempts(): Flow<List<com.mcqapp.domain.Attempt>> = historyStore.observeAttempts()
    suspend fun getAttempt(attemptId: Long): com.mcqapp.domain.Attempt? =
        historyStore.getAttempt(attemptId)
    suspend fun getAttemptResults(attemptId: Long): List<com.mcqapp.domain.QuestionResult> =
        historyStore.getAttemptResults(attemptId)
    suspend fun getAllQuestionResults(): List<com.mcqapp.domain.QuestionResult> =
        historyStore.getAllQuestionResults()
    suspend fun getAttempts(): List<com.mcqapp.domain.Attempt> = historyStore.getAttempts()
    suspend fun getMistakeCounts(): Map<String, Int> = historyStore.getMistakeCounts()
        /** Mistaken questions as refs: content is fetched when one is opened. */
    suspend fun getMistakenQuestionRefs(paperId: String): List<QuestionRef> =
        historyStore.getMistakenQuestionRefs(paperId)

    /**
     * How many of these questions have no answer key — excluded from
     * scoring. Ids only: never loads question content to count them.
     */
    suspend fun countUngraded(questionIds: List<String>): Int {
        if (questionIds.isEmpty()) return 0
        val withKeys = db.correctAnswerDao().getQuestionIdsWithKeysChunked(questionIds)
        return questionIds.count { it !in withKeys }
    }

suspend fun getMistakenQuestions(paperId: String): List<Question> =
        historyStore.getMistakenQuestions(paperId)
    suspend fun getBookmarkExportDto(): com.mcqapp.data.io.PaperDto? =
        historyStore.getBookmarkExportDto()
    suspend fun deleteAttempt(attemptId: Long) = historyStore.deleteAttempt(attemptId)
}
