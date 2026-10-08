package com.mcqapp.data.repository

import android.content.Context
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
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
    private val paperStore = PaperStore(db, questionStore, settings)
    private val studyStore = StudyStore(db, mapper, questionStore, settings)
    private val historyStore = HistoryStore(db, mapper, questionStore)

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

    suspend fun saveTestProgress(json: String) = settings.saveTestProgress(json)
    suspend fun loadTestProgress(): String? = settings.loadTestProgress()
    suspend fun clearTestProgress() = settings.clearTestProgress()

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
        val questions = mapper.toDomainBulk(prefiltered)
            .let { com.mcqapp.domain.QuestionSearch.filter(it, query, scope) }
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

    // --- papers & categories ---

    fun observePapers(): Flow<List<Paper>> = paperStore.observePapers()
    suspend fun getPaper(paperId: String): Paper? = paperStore.getPaper(paperId)
    suspend fun savePaper(paper: Paper) = paperStore.savePaper(paper)
    suspend fun deleteImpact(paperId: String): DeleteImpact = paperStore.deleteImpact(paperId)
    suspend fun deletePaper(paperId: String) = paperStore.deletePaper(paperId)
    suspend fun addCategory(paperId: String, title: String, parentId: String?): String =
        paperStore.addCategory(paperId, title, parentId)
    suspend fun deleteCategory(categoryId: String) = paperStore.deleteCategory(categoryId)
    suspend fun moveCategory(categoryId: String, delta: Int): Boolean =
        paperStore.moveCategory(categoryId, delta)
    suspend fun duplicatePaper(paperId: String): String? = paperStore.duplicatePaper(paperId)

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
        newLimit: Int? = null,
        leechesOnly: Boolean = false
    ): List<com.mcqapp.domain.StudyCard> =
        studyStore.getStudyQueue(paperId, now, newLimit, leechesOnly)
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
        now: Long = System.currentTimeMillis()
    ): com.mcqapp.domain.CardState =
        studyStore.recordStudyReview(paperId, questionId, grade, now)

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
    fun observeAttempts(): Flow<List<com.mcqapp.domain.Attempt>> = historyStore.observeAttempts()
    suspend fun getAttempt(attemptId: Long): com.mcqapp.domain.Attempt? =
        historyStore.getAttempt(attemptId)
    suspend fun getAttemptResults(attemptId: Long): List<com.mcqapp.domain.QuestionResult> =
        historyStore.getAttemptResults(attemptId)
    suspend fun getAllQuestionResults(): List<com.mcqapp.domain.QuestionResult> =
        historyStore.getAllQuestionResults()
    suspend fun getAttempts(): List<com.mcqapp.domain.Attempt> = historyStore.getAttempts()
    suspend fun getMistakeCounts(): Map<String, Int> = historyStore.getMistakeCounts()
    suspend fun getMistakenQuestions(paperId: String): List<Question> =
        historyStore.getMistakenQuestions(paperId)
    suspend fun getBookmarkExportDto(): com.mcqapp.data.io.PaperDto? =
        historyStore.getBookmarkExportDto()
    suspend fun deleteAttempt(attemptId: Long) = historyStore.deleteAttempt(attemptId)
}
