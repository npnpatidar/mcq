package com.mcqapp.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.AttemptEntity
import com.mcqapp.data.local.BookmarkEntity
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.CorrectAnswerEntity
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.QuestionResultEntity
import com.mcqapp.data.local.getByCategoriesChunked
import com.mcqapp.data.local.getGradedResultsForQuestionsChunked
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.QuestionResult
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.textContent
import com.mcqapp.data.io.ContentHash
import com.mcqapp.domain.toContentJson
import com.mcqapp.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Spaced repetition badges for a paper in the library list. */
data class StudyCounts(
    val due: Int = 0,
    val leeches: Int = 0,
    val fresh: Int = 0
) {
    val isEmpty: Boolean get() = due == 0 && leeches == 0 && fresh == 0
}

class McqRepository(private val db: AppDatabase, private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    fun db(): AppDatabase = db

    private val themeKey = stringPreferencesKey("theme_mode")

    fun themeMode(): Flow<String> =
        context.dataStore.data.map { it[themeKey] ?: "system" }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[themeKey] = mode }
    }

    private val shuffleQuestionsKey = booleanPreferencesKey("shuffle_questions")
    private val shuffleOptionsKey = booleanPreferencesKey("shuffle_options")

    fun shuffleQuestions(): Flow<Boolean> =
        context.dataStore.data.map { it[shuffleQuestionsKey] ?: false }

    suspend fun setShuffleQuestions(enabled: Boolean) {
        context.dataStore.edit { it[shuffleQuestionsKey] = enabled }
    }

    fun shuffleOptions(): Flow<Boolean> =
        context.dataStore.data.map { it[shuffleOptionsKey] ?: false }
    suspend fun setShuffleOptions(enabled: Boolean) {
        context.dataStore.edit { it[shuffleOptionsKey] = enabled }
    }

    private val pdfTwoColumnKey = booleanPreferencesKey("pdf_two_column")

    fun pdfTwoColumn(): Flow<Boolean> =
        context.dataStore.data.map { it[pdfTwoColumnKey] ?: false }

    suspend fun setPdfTwoColumn(enabled: Boolean) {
        context.dataStore.edit { it[pdfTwoColumnKey] = enabled }
    }

    private val practiceModeKey = booleanPreferencesKey("practice_mode")

    fun practiceMode(): Flow<Boolean> =
        context.dataStore.data.map { it[practiceModeKey] ?: false }

    suspend fun setPracticeMode(enabled: Boolean) {
        context.dataStore.edit { it[practiceModeKey] = enabled }
    }

    private val strictModeKey = booleanPreferencesKey("strict_mode")

    fun strictMode(): Flow<Boolean> =
        context.dataStore.data.map { it[strictModeKey] ?: false }

    suspend fun setStrictMode(enabled: Boolean) {
        context.dataStore.edit { it[strictModeKey] = enabled }
    }

    /**
     * When on, re-importing a file whose questions have the same text and
     * options but a corrected answer key refreshes the stored answers instead
     * of reporting them as duplicates. Off by default, which preserves the
     * long-standing behaviour.
     */
    private val updateAnswersKey = booleanPreferencesKey("update_answers_on_duplicate")

    fun updateAnswersOnDuplicate(): Flow<Boolean> =
        context.dataStore.data.map { it[updateAnswersKey] ?: false }

    suspend fun setUpdateAnswersOnDuplicate(enabled: Boolean) {
        context.dataStore.edit { it[updateAnswersKey] = enabled }
    }

    private val autoAdvanceKey = booleanPreferencesKey("auto_advance")

    fun autoAdvance(): Flow<Boolean> =
        context.dataStore.data.map { it[autoAdvanceKey] ?: false }

    suspend fun setAutoAdvance(enabled: Boolean) {
        context.dataStore.edit { it[autoAdvanceKey] = enabled }
    }

    private val fontScaleKey = floatPreferencesKey("font_scale")

    fun fontScale(): Flow<Float> =
        context.dataStore.data.map {
            com.mcqapp.util.FontScale.coerce(it[fontScaleKey] ?: com.mcqapp.util.FontScale.DEFAULT)
        }

    suspend fun setFontScale(scale: Float) {
        context.dataStore.edit { it[fontScaleKey] = scale }
    }

    // --- Scheduler (Anki-parity) settings ---
    // Stored as doubles keyed by field name so a newly added option defaults
    // cleanly on an old install instead of reading back as 0.

    private fun schedKey(name: String) = doublePreferencesKey("anki_$name")

    fun schedulerConfig(): Flow<com.mcqapp.domain.SchedulerConfig> =
        context.dataStore.data.map { prefs ->
            val d = com.mcqapp.domain.SchedulerConfig()
            com.mcqapp.domain.SchedulerConfig(
                defaultEase = prefs[schedKey("default_ease")] ?: d.defaultEase,
                minEase = prefs[schedKey("min_ease")] ?: d.minEase,
                maxEase = prefs[schedKey("max_ease")] ?: d.maxEase,
                againEaseFactor = prefs[schedKey("again_ease")] ?: d.againEaseFactor,
                hardEaseFactor = prefs[schedKey("hard_ease")] ?: d.hardEaseFactor,
                easyEaseFactor = prefs[schedKey("easy_ease")] ?: d.easyEaseFactor,
                firstIntervalDays = (prefs[schedKey("first_interval")] ?: d.firstIntervalDays.toDouble()).toInt(),
                secondIntervalDays = (prefs[schedKey("second_interval")] ?: d.secondIntervalDays.toDouble()).toInt(),
                easyFirstIntervalDays = (prefs[schedKey("easy_first_interval")] ?: d.easyFirstIntervalDays.toDouble()).toInt(),
                hardIntervalMultiplier = prefs[schedKey("hard_multiplier")] ?: d.hardIntervalMultiplier,
                easyBonus = prefs[schedKey("easy_bonus")] ?: d.easyBonus,
                minimumIntervalDays = (prefs[schedKey("min_interval")] ?: d.minimumIntervalDays.toDouble()).toInt(),
                maxIntervalDays = (prefs[schedKey("max_interval")] ?: d.maxIntervalDays.toDouble()).toInt(),
                relearnMs = (prefs[schedKey("relearn_ms")] ?: d.relearnMs.toDouble()).toLong(),
                leechThreshold = (prefs[schedKey("leech_threshold")] ?: d.leechThreshold.toDouble()).toInt(),
                newLimit = (prefs[schedKey("new_limit")] ?: d.newLimit.toDouble()).toInt(),
                reviewLimit = (prefs[schedKey("review_limit")] ?: d.reviewLimit.toDouble()).toInt(),
                fastSeconds = (prefs[schedKey("fast_seconds")] ?: d.fastSeconds.toDouble()).toLong(),
                slowSeconds = (prefs[schedKey("slow_seconds")] ?: d.slowSeconds.toDouble()).toLong()
            ).sanitized()
        }

    suspend fun schedulerConfigNow(): com.mcqapp.domain.SchedulerConfig =
        schedulerConfig().first()

    suspend fun setSchedulerConfig(config: com.mcqapp.domain.SchedulerConfig) {
        val c = config.sanitized()
        context.dataStore.edit { prefs ->
            prefs[schedKey("default_ease")] = c.defaultEase
            prefs[schedKey("min_ease")] = c.minEase
            prefs[schedKey("max_ease")] = c.maxEase
            prefs[schedKey("again_ease")] = c.againEaseFactor
            prefs[schedKey("hard_ease")] = c.hardEaseFactor
            prefs[schedKey("easy_ease")] = c.easyEaseFactor
            prefs[schedKey("first_interval")] = c.firstIntervalDays.toDouble()
            prefs[schedKey("second_interval")] = c.secondIntervalDays.toDouble()
            prefs[schedKey("easy_first_interval")] = c.easyFirstIntervalDays.toDouble()
            prefs[schedKey("hard_multiplier")] = c.hardIntervalMultiplier
            prefs[schedKey("easy_bonus")] = c.easyBonus
            prefs[schedKey("min_interval")] = c.minimumIntervalDays.toDouble()
            prefs[schedKey("max_interval")] = c.maxIntervalDays.toDouble()
            prefs[schedKey("relearn_ms")] = c.relearnMs.toDouble()
            prefs[schedKey("leech_threshold")] = c.leechThreshold.toDouble()
            prefs[schedKey("new_limit")] = c.newLimit.toDouble()
            prefs[schedKey("review_limit")] = c.reviewLimit.toDouble()
            prefs[schedKey("fast_seconds")] = c.fastSeconds.toDouble()
            prefs[schedKey("slow_seconds")] = c.slowSeconds.toDouble()
        }
    }

    suspend fun resetSchedulerConfig() {
        context.dataStore.edit { prefs ->
            com.mcqapp.domain.SchedulerConfig()
                .sanitized()
                .let { c ->
                    listOf(
                        "default_ease" to c.defaultEase,
                        "min_ease" to c.minEase,
                        "max_ease" to c.maxEase,
                        "again_ease" to c.againEaseFactor,
                        "hard_ease" to c.hardEaseFactor,
                        "easy_ease" to c.easyEaseFactor,
                        "first_interval" to c.firstIntervalDays.toDouble(),
                        "second_interval" to c.secondIntervalDays.toDouble(),
                        "easy_first_interval" to c.easyFirstIntervalDays.toDouble(),
                        "hard_multiplier" to c.hardIntervalMultiplier,
                        "easy_bonus" to c.easyBonus,
                        "min_interval" to c.minimumIntervalDays.toDouble(),
                        "max_interval" to c.maxIntervalDays.toDouble(),
                        "relearn_ms" to c.relearnMs.toDouble(),
                        "leech_threshold" to c.leechThreshold.toDouble(),
                        "new_limit" to c.newLimit.toDouble(),
                        "review_limit" to c.reviewLimit.toDouble(),
                        "fast_seconds" to c.fastSeconds.toDouble(),
                        "slow_seconds" to c.slowSeconds.toDouble()
                    ).forEach { (name, value) -> prefs[schedKey(name)] = value }
                }
        }
    }

    private val progressKey = stringPreferencesKey("in_progress_test")

    suspend fun saveTestProgress(json: String) {
        context.dataStore.edit { it[progressKey] = json }
    }

    suspend fun loadTestProgress(): String? =
        context.dataStore.data.map { it[progressKey] }.first()

    suspend fun clearTestProgress() {
        context.dataStore.edit { it.remove(progressKey) }
    }

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
        val questions = prefiltered.toDomainBulk()
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

    fun observePapers(): Flow<List<Paper>> =
        combine(
            db.paperDao().observeAll(),
            db.categoryDao().observeAll(),
            db.questionDao().observeCategoryCounts()
        ) { papers, categories, counts ->
            val countMap = counts.associate { it.categoryId to it.cnt }
            papers.map { it.toDomain(countMap) }
        }

    suspend fun getPaper(paperId: String): Paper? {
        Logger.d("REPO", "getPaper($paperId)")
        return db.paperDao().getById(paperId)?.toDomain()
    }

    /**
     * `countMap` lets the library pass the counts it already has; null means
     * fetch them here. The previous default of an empty map produced a Paper
     * whose every category reported zero questions — a silent trap for any
     * caller that asked `totalQuestions`, which is exactly what the drill
     * dialog and the Study screen do.
     */
    private suspend fun PaperEntity.toDomain(countMap: Map<String, Int>? = null): Paper {
        val categories = db.categoryDao().getByPaper(id)
        val counts = countMap
            ?: db.questionDao().getCategoryCounts().associate { it.categoryId to it.cnt }
        return Paper(
            id = id,
            title = title,
            description = description,
            durationMinutes = durationMinutes,
            negativeMarking = negativeMarking,
            categories = buildTree(categories, counts)
        )
    }

    private suspend fun buildTree(
        categories: List<CategoryEntity>,
        countMap: Map<String, Int>
    ): List<CategoryNode> {
        val byParent = categories.groupBy { it.parentId }
        suspend fun build(parentId: String?, counts: Map<String, Int>): List<CategoryNode> =
            (byParent[parentId] ?: emptyList()).map { category ->
                CategoryNode(
                    id = category.id,
                    paperId = category.paperId,
                    title = category.title,
                    parentId = category.parentId,
                    children = build(category.id, counts),
                    questionCount = counts[category.id] ?: 0
                )
            }
        return build(null, countMap)
    }

    fun observeQuestionsForPaper(paperId: String): Flow<List<Question>> =
        // Bulk load (one options + one answers query for all rows) and map off
        // the main thread: the per-row re-fetch version stalled Browse badly.
        // One IN query replaces the per-category fetch; the counts trigger is
        // global so any question write refreshes this paper's list.
        db.questionDao().observeCategoryCounts().map {
            val started = android.os.SystemClock.elapsedRealtime()
            val categoryIds = db.categoryDao().getByPaper(paperId).map { it.id }
            // An empty IN list is invalid SQL; a paper with no categories has
            // no questions to load.
            if (categoryIds.isEmpty()) return@map emptyList()
            val entities = db.questionDao().getByCategoriesChunked(categoryIds)
            val result = entities.toDomainBulk()
            Logger.d("REPO", "observeQuestionsForPaper($paperId): " +
                "mapped ${result.size} questions in " +
                "${android.os.SystemClock.elapsedRealtime() - started}ms")
            result
        }.flowOn(Dispatchers.IO)

    suspend fun getQuestionsForCategories(categoryIds: List<String>): List<Question> {
        Logger.d("REPO", "getQuestionsForCategories(${categoryIds.size} categories)")
        // An empty IN list is invalid SQL; nothing can match anyway.
        if (categoryIds.isEmpty()) return emptyList()
        val entities = db.questionDao().getByCategoriesChunked(categoryIds)
        val result = entities.toDomainBulk()
        Logger.d("REPO", "getQuestionsForCategories returned ${result.size} questions")
        return result
    }

    private suspend fun List<QuestionEntity>.toDomainBulk(): List<Question> {
        if (isEmpty()) return emptyList()
        val ids = map { it.id }
        val optionsByQuestion = db.optionDao().getForQuestionsChunked(ids).groupBy { it.questionId }
        val correctByQuestion = db.correctAnswerDao().getForQuestionsChunked(ids).groupBy { it.questionId }
        return map { entity ->
            val options = optionsByQuestion[entity.id] ?: emptyList()
            val correctIds = correctByQuestion[entity.id]?.map { it.optionId }?.toSet() ?: emptySet()
            Question(
                id = entity.id,
                categoryId = entity.categoryId,
                elements = entity.text.parseContentElements(json),
                image = entity.image,
                options = options.map { QuestionOption(it.id, it.text.parseContentElements(json), it.image) },
                correctOptionIds = correctIds,
                explanationElements = entity.explanation.parseContentElements(json),
                difficulty = Difficulty.fromLabel(entity.difficulty),
                marks = entity.marks,
                tags = entity.tags.split(",").filter { it.isNotBlank() }
            )
        }
    }

    suspend fun getQuestionsForPaper(paperId: String): List<Question> {
        Logger.d("REPO", "getQuestionsForPaper($paperId)")
        val categoryIds = db.categoryDao().getByPaper(paperId).map { it.id }
        return getQuestionsForCategories(categoryIds)
    }

    suspend fun getQuestion(questionId: String): Question? {
        Logger.d("REPO", "getQuestion($questionId)")
        return db.questionDao().getById(questionId)?.toDomain()
    }

    /**
     * Many questions by id in three queries instead of three per question.
     * Results follow the order of [ids], and unknown ids are dropped, so this
     * replaces a `mapNotNull { getQuestion(it) }` loop.
     */
    suspend fun getQuestionsByIds(ids: List<String>): List<Question> {
        if (ids.isEmpty()) return emptyList()
        val byId = db.questionDao().getByIdsChunked(ids).toDomainBulk().associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** Category title per category id, in one query instead of one per id. */
    private suspend fun categoryTitlesOf(categoryIds: Set<String>): Map<String, String> {
        if (categoryIds.isEmpty()) return emptyMap()
        return db.categoryDao().getByIds(categoryIds.toList())
            .associate { it.id to it.title }
    }

    /** Bookmark rows paired with their owning paper, for the bookmarks screen. */
    suspend fun getBookmarkedQuestions(): List<com.mcqapp.domain.BookmarkedQuestion> {
        val ids = db.bookmarkDao().getAll()
        if (ids.isEmpty()) return emptyList()
        val questions = getQuestionsByIds(ids)
        if (questions.isEmpty()) return emptyList()
        val paperIdByCategory = categoryPaperIdsOf(questions.map { it.categoryId }.toSet())
        return questions.mapNotNull { question ->
            paperIdByCategory[question.categoryId]?.let {
                com.mcqapp.domain.BookmarkedQuestion(it, question)
            }
        }
    }

    /** Owning paper id per category id, in one query instead of one per id. */
    private suspend fun categoryPaperIdsOf(categoryIds: Set<String>): Map<String, String> {
        if (categoryIds.isEmpty()) return emptyMap()
        return db.categoryDao().getByIds(categoryIds.toList()).associate { it.id to it.paperId }
    }

    /** Owning paper's title per category id, in two queries instead of two per id. */
    private suspend fun paperTitlesForCategories(categoryIds: Set<String>): Map<String, String> {
        if (categoryIds.isEmpty()) return emptyMap()
        val categories = db.categoryDao().getByIds(categoryIds.toList())
        val papers = db.paperDao().getByIds(categories.map { it.paperId }.toSet().toList())
            .associateBy { it.id }
        return categories.mapNotNull { category ->
            papers[category.paperId]?.let { category.id to it.title }
        }.toMap()
    }

    private suspend fun QuestionEntity.toDomain(): Question {
        val options = db.optionDao().getByQuestion(id)
        val correctIds = db.correctAnswerDao().getCorrectIds(id).toSet()
        return Question(
            id = id,
            categoryId = categoryId,
            elements = text.parseContentElements(json),
            image = image,
            options = options.map { QuestionOption(it.id, it.text.parseContentElements(json), it.image) },
            correctOptionIds = correctIds,
            explanationElements = explanation.parseContentElements(json),
            explanationImage = explanationImage,
            difficulty = Difficulty.fromLabel(difficulty),
            marks = marks,
            tags = tags.split(",").filter { it.isNotBlank() }
        )
    }

    private fun computeContentHash(text: String, optionTexts: List<String>, optionImages: List<String?>): String =
        // Single source of truth lives in ContentHash; this wrapper keeps the
        // existing call site readable.
        com.mcqapp.data.io.ContentHash.of(text, optionTexts, optionImages)

    suspend fun ensurePaperAndCategory(paperId: String, paperTitle: String, categoryId: String, categoryTitle: String) {
        db.paperDao().upsert(PaperEntity(id = paperId, title = paperTitle.ifBlank { "Imported Questions" }))
        db.categoryDao().upsert(
            CategoryEntity(
                id = categoryId,
                paperId = paperId,
                title = categoryTitle.ifBlank { "Uncategorized" }
            )
        )
    }

    suspend fun saveQuestion(question: Question) {
        Logger.d("REPO", "saveQuestion(id=${question.id}, category=${question.categoryId}, " +
            "options=${question.options.size}, correct=${question.correctOptionIds}, " +
            "textLength=${question.text.length})")
        // One transaction: the question row, its options and its answer key
        // must land together, so a crash can never leave options without a key.
        db.withTransaction {
            val existing = db.questionDao().getById(question.id)
            val sortOrder = existing?.sortOrder
                ?: ((db.questionDao().getMaxSortOrder(question.categoryId) ?: -1) + 1)
            Logger.d("REPO", "saveQuestion(id=${question.id}): existing=${existing != null}, " +
                "existingSortOrder=${existing?.sortOrder}, resolvedSortOrder=$sortOrder")
            val contentHash = computeContentHash(question.text, question.options.map { it.text }, question.options.map { it.image })
            Logger.d("REPO", "saveQuestion(id=${question.id}): contentHash=${contentHash.take(12)}")
            db.questionDao().upsert(
                QuestionEntity(
                    id = question.id,
                    categoryId = question.categoryId,
                    text = question.elements.toContentJson(json),
                    image = question.image,
                    explanation = question.explanationElements.toContentJson(json),
                    explanationImage = question.explanationImage,
                    difficulty = question.difficulty.label,
                    marks = question.marks,
                    tags = question.tags.joinToString(","),
                    sortOrder = sortOrder,
                    contentHash = contentHash
                )
            )
            Logger.d("REPO", "saveQuestion(id=${question.id}): question row upserted")
            db.optionDao().deleteByQuestion(question.id)
            db.optionDao().upsertAll(
                question.options.mapIndexed { index, o ->
                    OptionEntity(
                        id = o.id,
                        questionId = question.id,
                        text = o.elements.toContentJson(json),
                        image = o.image,
                        sortOrder = index
                    )
                }
            )
            Logger.d("REPO", "saveQuestion(id=${question.id}): ${question.options.size} options written")
            db.correctAnswerDao().deleteByQuestion(question.id)
            db.correctAnswerDao().upsertAll(
                question.correctOptionIds.map {
                    CorrectAnswerEntity(question.id, it)
                }
            )
            Logger.d("REPO", "saveQuestion(id=${question.id}): ${question.correctOptionIds.size} correct answers written - done")
        }
    }

    suspend fun deleteQuestion(questionId: String) {
        Logger.d("REPO", "deleteQuestion($questionId)")
        // One transaction: the question row, its schedule and its bookmark
        // must disappear together.
        db.withTransaction {
            db.questionDao().deleteById(questionId)
            db.cardStateDao().deleteByQuestion(questionId)
            // No FK on bookmarks: backup restores tolerate dangling bookmark ids,
            // so stale ones are removed here instead of by cascade.
            db.bookmarkDao().remove(questionId)
        }
    }

    suspend fun deleteQuestions(questionIds: Collection<String>) {
        Logger.i("REPO", "deleteQuestions(${questionIds.size} ids)")
        // One transaction: a partial bulk delete must not strand rows.
        db.withTransaction {
            questionIds.forEach {
                db.questionDao().deleteById(it)
                db.cardStateDao().deleteByQuestion(it)
            }
            if (questionIds.isNotEmpty()) db.bookmarkDao().removeAll(questionIds.toList())
        }
    }

    /** Deep-copies a question (options, key, explanation, marks) after siblings. */
    suspend fun duplicateQuestion(questionId: String): String? {
        val source = getQuestion(questionId) ?: return null
        val existing = db.questionDao().getAll().map { it.id }.toHashSet()
        val newId = com.mcqapp.domain.BulkOps.copyId(existing, questionId)
        saveQuestion(source.copy(id = newId))
        Logger.i("REPO", "duplicateQuestion($questionId -> $newId)")
        return newId
    }

    /** Moves questions into another category, appended after its siblings. */
    suspend fun moveQuestionsToCategory(questionIds: Collection<String>, targetCategoryId: String) {
        Logger.i("REPO", "moveQuestionsToCategory(${questionIds.size} ids -> $targetCategoryId)")
        // One transaction: a partial move must not strand questions, and the
        // cross-paper schedule reset below belongs to the same move.
        db.withTransaction {
            val targetPaperId = db.categoryDao().getById(targetCategoryId)?.paperId
            questionIds.forEach { id ->
                getQuestion(id)?.let { question ->
                    val sourcePaperId = db.categoryDao().getById(question.categoryId)?.paperId
                    saveQuestion(question.copy(categoryId = targetCategoryId))
                    // A schedule is keyed to a paper. Moving a question into another
                    // paper makes the old row an orphan that would still inflate the
                    // source paper's due count, so start the card over instead.
                    if (targetPaperId != null && targetPaperId != sourcePaperId) {
                        db.cardStateDao().deleteByQuestion(id)
                    }
                }
            }
        }
    }

    /** Deep-copies questions into another category (appended after siblings). */
    suspend fun copyQuestionsToCategory(questionIds: Collection<String>, targetCategoryId: String) {
        // One transaction: a partial copy must not leave half the selection behind.
        db.withTransaction {
            val existing = db.questionDao().getAll().map { it.id }.toHashSet()
            var count = 0
            questionIds.forEach { id ->
                getQuestion(id)?.let { q ->
                    val newId = com.mcqapp.domain.BulkOps.copyId(existing, id)
                    existing.add(newId)
                    saveQuestion(q.copy(id = newId, categoryId = targetCategoryId))
                    count++
                }
            }
            Logger.i("REPO", "copyQuestionsToCategory($count ids -> $targetCategoryId)")
        }
    }

    /** Moves a category up/down among same-paper, same-parent siblings. */
    suspend fun moveCategory(categoryId: String, delta: Int): Boolean {
        // One transaction: the reorder writes every sibling's position, so a
        // crash must not leave two of them sharing one.
        return db.withTransaction {
            val category = db.categoryDao().getById(categoryId) ?: return@withTransaction false
            val siblings = db.categoryDao().getByPaper(category.paperId)
                .filter { it.parentId == category.parentId }
            val fromIndex = siblings.indexOfFirst { it.id == categoryId }
            if (fromIndex < 0) return@withTransaction false
            val target = (fromIndex + delta).coerceIn(siblings.indices)
            if (target == fromIndex) return@withTransaction false
            val order = com.mcqapp.domain.Reorder.normalizedOrder(
                siblings.map { it.id }, fromIndex, delta
            )
            order.forEach { (id, sortOrder) -> db.categoryDao().updateSortOrder(id, sortOrder) }
            Logger.i("REPO", "moveCategory($categoryId by $delta)")
            true
        }
    }

    /** Deep-clones a paper (categories, questions, options, keys, marks). */
    suspend fun duplicatePaper(paperId: String): String? {
        // One transaction: a partial clone must not leave a paper shell with
        // half its questions.
        return db.withTransaction {
            val paper = db.paperDao().getById(paperId) ?: return@withTransaction null
            val existingPaperIds = db.paperDao().getAll().map { it.id }.toHashSet()
            val newPaperId = com.mcqapp.data.io.PaperClone.copyPaperId(existingPaperIds, paperId)
            db.paperDao().insertIgnore(
                paper.copy(
                    id = newPaperId,
                    title = "${paper.title} (copy)",
                    createdAt = System.currentTimeMillis()
                )
            )
            val remapped = com.mcqapp.data.io.PaperClone.remapCategories(
                db.categoryDao().getByPaper(paperId),
                newPaperId
            )
            remapped.values.forEach { db.categoryDao().insertIgnore(it) }
            val existingQ = db.questionDao().getAll().map { it.id }.toHashSet()
            for ((oldCatId, newCat) in remapped) {
                for (q in getQuestionsForCategories(listOf(oldCatId))) {
                    val newQId = com.mcqapp.domain.BulkOps.copyId(existingQ, q.id)
                    existingQ.add(newQId)
                    saveQuestion(q.copy(id = newQId, categoryId = newCat.id))
                }
            }
            Logger.i("REPO", "duplicatePaper($paperId -> $newPaperId)")
            newPaperId
        }
    }

    /** Bulk-sets marks/difficulty/tags on questions; null fields are kept. */
    suspend fun bulkUpdateQuestions(
        questionIds: Collection<String>,
        marks: Double?,
        difficulty: Difficulty?,
        tags: List<String>?
    ) {
        // One transaction: a partial bulk edit must not apply to half the selection.
        db.withTransaction {
            var count = 0
            questionIds.forEach { id ->
                getQuestion(id)?.let { q ->
                    saveQuestion(
                        q.copy(
                            marks = marks ?: q.marks,
                            difficulty = difficulty ?: q.difficulty,
                            tags = tags ?: q.tags
                        )
                    )
                    count++
                }
            }
            Logger.i("REPO", "bulkUpdateQuestions($count ids)")
        }
    }

    /** Swaps the positions of two same-category questions. */
    suspend fun swapQuestionOrder(firstId: String, secondId: String): Boolean {
        // One transaction: the two position writes are a single swap, so a
        // crash must not commit the first without the second.
        return db.withTransaction {
            val a = db.questionDao().getById(firstId) ?: return@withTransaction false
            val b = db.questionDao().getById(secondId) ?: return@withTransaction false
            if (a.categoryId != b.categoryId) return@withTransaction false
            db.questionDao().updateSortOrder(a.id, b.sortOrder)
            db.questionDao().updateSortOrder(b.id, a.sortOrder)
            Logger.i("REPO", "swapQuestionOrder(${a.id} <-> ${b.id})")
            true
        }
    }

    suspend fun savePaper(paper: Paper) {
        Logger.d("REPO", "savePaper(${paper.id}, '${paper.title}')")
        db.paperDao().upsert(
            PaperEntity(
                id = paper.id,
                title = paper.title,
                description = paper.description,
                durationMinutes = paper.durationMinutes,
                negativeMarking = paper.negativeMarking
            )
        )
    }

    /**
     * What deleting [paperId] would take with it, so the confirmation can say so
     * rather than just naming the paper. Deletion is thorough by design — it
     * also drops history, bookmarks, schedules and any resume snapshot — which
     * makes a single unconfirmed tap expensive.
     */
    suspend fun deleteImpact(paperId: String): DeleteImpact {
        val questionIds = db.questionDao().getIdsByPaper(paperId)
        return DeleteImpact(
            questions = questionIds.size,
            attempts = db.attemptDao().countByPaper(paperId),
            bookmarks = if (questionIds.isEmpty()) 0
            else db.bookmarkDao().countForQuestions(questionIds),
            schedules = db.cardStateDao().countByPaper(paperId)
        )
    }

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

    suspend fun deletePaper(paperId: String) {
        Logger.d("REPO", "deletePaper($paperId)")
        // One transaction: bookmark cleanup, history, schedules and the paper
        // delete (which cascades categories/questions/options/correct answers)
        // must land together, or a crash midway leaves the database claiming a
        // test was taken against questions that no longer exist.
        db.withTransaction {
            // Collect first: deleting the paper cascades its questions away.
            val questionIds = db.questionDao().getIdsByPaper(paperId)
            if (questionIds.isNotEmpty()) db.bookmarkDao().removeAll(questionIds)
            // History and SM-2 cards have no path to the paper except this
            // paperId column, so a plain cascade leaves them behind as orphans:
            // attempts would still show up in History under a title that no
            // longer exists, and question_results still reference dead questions.
            db.attemptDao().deleteByPaper(paperId)
            db.cardStateDao().deleteByPaper(paperId)
            db.paperDao().deleteById(paperId)
        }
        // A snapshot is one global slot, so it has to be dropped by hand — and
        // only when it belongs to the paper that just went: resuming a test
        // whose questions are gone would present an empty paper.
        discardProgressFor(paperId)
    }

    /** Clears the resume snapshot if it is for [paperId]. */
    private suspend fun discardProgressFor(paperId: String) {
        val raw = loadTestProgress() ?: return
        val snapshot = com.mcqapp.domain.TestSnapshot.fromJson(raw)
        // Unparseable snapshots are dead weight either way.
        if (snapshot == null || snapshot.paperId == paperId) {
            Logger.i("REPO", "Discarding in-progress snapshot for $paperId")
            clearTestProgress()
        }
    }

    suspend fun addCategory(paperId: String, title: String, parentId: String?): String {
        val id = "cat-" + System.currentTimeMillis().toString(36) + "-" + (0..9999).random()
        Logger.d("REPO", "addCategory($paperId, '$title', parent=$parentId) -> $id")
        db.categoryDao().upsert(
            CategoryEntity(
                id = id,
                paperId = paperId,
                title = title,
                parentId = parentId
            )
        )
        return id
    }

    suspend fun deleteCategory(categoryId: String) {
        // One transaction: bookmark cleanup, the reparent and the category
        // delete (which cascades its own questions) must land together.
        db.withTransaction {
            val category = db.categoryDao().getById(categoryId) ?: return@withTransaction
            // `categories` has no self-referencing FK on parentId, so a plain
            // delete leaves every descendant pointing at a row that no longer
            // exists: still returned by getByPaper and counted in the UI, but
            // unreachable from the tree. The user asked to delete this
            // category, not its subtree, so promote the children one level.
            db.categoryDao().reparentChildren(
                fromParentId = categoryId,
                toParentId = category.parentId
            )
            val questionIds = db.questionDao().getIdsByCategory(categoryId)
            if (questionIds.isNotEmpty()) db.bookmarkDao().removeAll(questionIds)
            db.categoryDao().deleteById(categoryId)
        }
    }

    // --- spaced repetition ---

    private fun CardStateEntity.toDomain() = com.mcqapp.domain.CardState(
        questionId = questionId,
        ease = ease,
        intervalDays = intervalDays,
        dueAt = dueAt,
        reps = reps,
        lapses = lapses,
        leech = leech,
        lastReviewedAt = lastReviewedAt
    )

    private fun com.mcqapp.domain.CardState.toEntity(paperId: String, hash: String) =
        CardStateEntity(
            paperId = paperId,
            questionId = questionId,
            ease = ease,
            intervalDays = intervalDays,
            dueAt = dueAt,
            reps = reps,
            lapses = lapses,
            leech = leech,
            lastReviewedAt = lastReviewedAt,
            contentHash = hash
        )

    /**
     * Today's study queue for a paper, seeding any card that has never been
     * studied from existing attempt history so an existing install starts warm
     * instead of treating every question as new.
     */
    suspend fun getStudyQueue(
        paperId: String,
        now: Long = System.currentTimeMillis(),
        newLimit: Int? = null,
        leechesOnly: Boolean = false
    ): List<com.mcqapp.domain.StudyCard> {
        val questions = getQuestionsForPaper(paperId)
        if (questions.isEmpty()) return emptyList()
        val config = schedulerConfigNow()
        val states = resolveStudyStates(
            paperId,
            questions.map { StudyInput(it.id, contentHashOf(it)) },
            config
        )
        Logger.i(
            "REPO",
            "getStudyQueue($paperId): ${questions.size} questions, " +
                "due=${com.mcqapp.domain.Study.dueCount(states.values, now)}"
        )
        val queue = com.mcqapp.domain.Study.queue(
            com.mcqapp.domain.Sm2Scheduler(config),
            questions.map { it.id },
            states,
            now,
            newLimit ?: config.newLimit
        )
        return if (leechesOnly) com.mcqapp.domain.Study.onlyLeeches(queue) else queue
    }

    /**
     * Resolves every question's card state, seeding anything missing from
     * attempt history and resetting questions whose text has changed. Both the
     * queue and the library badges go through here, so the badge can never
     * disagree with what the queue would actually offer.
     */
    /**
     * What scheduling actually needs about a question: its id and the hash of
     * its content. Both are derived from the stored elements, so the counts on
     * the library screen no longer have to load every option and every answer
     * key just to count them.
     */
    private data class StudyInput(val id: String, val contentHash: String)

    /**
     * Ids and content hashes for a paper's questions, nested categories
     * included, from the questions and options rows alone.
     */
    private suspend fun studyInputs(paperId: String): List<StudyInput> {
        val categoryIds = db.categoryDao().getByPaper(paperId).map { it.id }
        if (categoryIds.isEmpty()) return emptyList()
        val questions = db.questionDao().getByCategoriesChunked(categoryIds)
        if (questions.isEmpty()) return emptyList()
        val optionsByQuestion = db.optionDao()
            .getForQuestions(questions.map { it.id })
            .groupBy { it.questionId }
        return questions.map { entity ->
            val options = optionsByQuestion[entity.id].orEmpty()
            StudyInput(
                id = entity.id,
                contentHash = ContentHash.of(
                    entity.text.parseContentElements(json).textContent,
                    options.map { it.text.parseContentElements(json).textContent },
                    options.map { it.image }
                )
            )
        }
    }

    private suspend fun resolveStudyStates(
        paperId: String,
        questions: List<StudyInput>,
        config: com.mcqapp.domain.SchedulerConfig
    ): Map<String, com.mcqapp.domain.CardState> {
        val scheduler = com.mcqapp.domain.Sm2Scheduler(config)
        val stored = db.cardStateDao().getByPaper(paperId).associate { it.questionId to it }
        val history = historySignalsFor(paperId, questions.map { it.id }, config)
        val states = mutableMapOf<String, com.mcqapp.domain.CardState>()
        val seeded = mutableListOf<CardStateEntity>()
        for (q in questions) {
            val existing = stored[q.id]
            if (existing != null) {
                // A question whose text or options changed is scheduled again
                // from scratch: the old interval describes memory of other text.
                if (existing.contentHash != q.contentHash) {
                    val reset = scheduler.initial(q.id)
                    states[q.id] = reset
                    // Persist the new hash, otherwise the edit looks stale again
                    // on the next load and the card is reset every time.
                    seeded += reset.toEntity(paperId, q.contentHash)
                } else {
                    states[q.id] = existing.toDomain()
                }
            } else {
                val rebuilt = history[q.id]?.let {
                    com.mcqapp.domain.Study.rebuild(scheduler, q.id, it)
                } ?: scheduler.initial(q.id)
                states[q.id] = rebuilt
                seeded += rebuilt.toEntity(paperId, q.contentHash)
            }
        }
        if (seeded.isNotEmpty()) {
            // One transaction: this runs from a badge read as well as from
            // getStudyQueue, and without it the seeding was N separate
            // transactions that two coroutines could interleave.
            db.withTransaction { seeded.forEach { db.cardStateDao().upsert(it) } }
            Logger.i("REPO", "seeded ${seeded.size} card_state rows for $paperId")
        }
        return states
    }

    /** Due / new / leech counts for a paper's library badge. */
    suspend fun getStudyCounts(
        paperId: String,
        now: Long = System.currentTimeMillis()
    ): StudyCounts {
        // Counted from ids and content hashes only. The library re-reads these
        // on every resume, and loading every question with its options and
        // answer key just to add up three numbers made a large library crawl.
        val inputs = studyInputs(paperId)
        if (inputs.isEmpty()) return StudyCounts()
        val states = resolveStudyStates(paperId, inputs, schedulerConfigNow())
        return StudyCounts(
            due = com.mcqapp.domain.Study.dueCount(states.values, now),
            leeches = com.mcqapp.domain.Study.leechCount(states.values),
            fresh = com.mcqapp.domain.Study.newCount(inputs.map { it.id }, states)
        )
    }

    /** The stored schedule for a card, or null if it has never been studied. */
    suspend fun cardState(
        paperId: String,
        questionId: String
    ): com.mcqapp.domain.CardState? = db.cardStateDao().get(paperId, questionId)?.toDomain()

    /** Applies one grade to a question's card and persists the new schedule. */
    suspend fun recordStudyReview(
        paperId: String,
        questionId: String,
        grade: com.mcqapp.domain.ReviewGrade,
        now: Long = System.currentTimeMillis()
    ): com.mcqapp.domain.CardState {
        val question = getQuestion(questionId)
        val scheduler = com.mcqapp.domain.Sm2Scheduler(schedulerConfigNow())
        val existing = db.cardStateDao().get(paperId, questionId)?.toDomain()
            ?: scheduler.initial(questionId)
        val next = scheduler.next(existing, grade, now)
        db.cardStateDao().upsert(
            next.toEntity(paperId, question?.let { contentHashOf(it) } ?: "")
        )
        Logger.i(
            "REPO",
            "recordStudyReview($questionId, $grade): interval=${next.intervalDays}d, " +
                "reps=${next.reps}, lapses=${next.lapses}, leech=${next.leech}"
        )
        return next
    }

    /**
     * Graded history for the given questions, newest last. Rows with no
     * selection or no answer key carry no memory signal, so they are skipped.
     */
    private suspend fun historySignalsFor(
        paperId: String,
        questionIds: List<String>,
        config: com.mcqapp.domain.SchedulerConfig = com.mcqapp.domain.SchedulerConfig()
    ): Map<String, List<com.mcqapp.domain.ReviewSignal>> {
        if (questionIds.isEmpty()) return emptyMap()
        val attemptsById = db.attemptDao().getByPaper(paperId).associateBy { it.id }
        if (attemptsById.isEmpty()) return emptyMap()
        val signals = mutableMapOf<String, MutableList<com.mcqapp.domain.ReviewSignal>>()
        // Scoped to this paper and these question ids in SQL, rather than
        // loading every attempt and every result row in the database.
        val rows = db.attemptDao().getGradedResultsForQuestionsChunked(paperId, questionIds)
        for (row in rows) {
            val attempt = attemptsById[row.attemptId] ?: continue
            val grade = com.mcqapp.domain.Study.inferGrade(
                row.isCorrect, row.dwellSeconds, config = config
            )
            signals.getOrPut(row.questionId) { mutableListOf() }
                .add(com.mcqapp.domain.ReviewSignal(row.questionId, grade, attempt.finishedAt))
        }
        return signals
    }

    /**
     * Mirrors the hash written by [saveQuestion] so a stored card can be
     * compared against the question it was scheduled for.
     */
    private fun contentHashOf(question: Question): String = computeContentHash(
        question.text,
        question.options.map { it.text },
        question.options.map { it.image }
    )

    fun observeBookmarks(): Flow<List<String>> = db.bookmarkDao().observeAll()

    suspend fun toggleBookmark(questionId: String) {
        val wasBookmarked = db.bookmarkDao().isBookmarked(questionId)
        Logger.d("REPO", "toggleBookmark($questionId) wasBookmarked=$wasBookmarked")
        if (wasBookmarked) {
            db.bookmarkDao().remove(questionId)
        } else {
            db.bookmarkDao().add(BookmarkEntity(questionId))
        }
    }

    suspend fun isBookmarked(questionId: String): Boolean =
        db.bookmarkDao().isBookmarked(questionId)

    suspend fun saveAttempt(
        paperId: String,
        paperTitle: String,
        questions: List<Question>,
        selections: Map<String, Set<String>>,
        negativeMarking: Double,
        durationSeconds: Long,
        finishedAt: Long,
        dwellSeconds: Map<String, Long> = emptyMap()
    ): Long {
        var correct = 0
        var wrong = 0
        var skipped = 0
        var ungraded = 0
        var score = 0.0
        var maxScore = 0.0
        val results = mutableListOf<QuestionResultEntity>()
        // Resolved once for the whole attempt: was two lookups per question.
        val categoryTitles = categoryTitlesOf(questions.map { it.categoryId }.toSet())
        for (q in questions) {
            val selected = selections[q.id].orEmpty()
            // Computed once and shared by the aggregate counters and the
            // stored row below: for an ungraded question (no answer key) this
            // is always false, matching its exclusion from scoring — every
            // reader of the stored row guards on the empty key first.
            val isCorrect = selected.isNotEmpty() && selected == q.correctOptionIds
            if (q.correctOptionIds.isEmpty()) {
                // No answer key: excluded from scoring entirely (no credit, no penalty).
                ungraded++
            } else {
                maxScore += q.marks
                when {
                    selected.isEmpty() -> skipped++
                    isCorrect -> {
                        correct++
                        score += q.marks
                    }
                    else -> {
                        wrong++
                        score -= q.marks * negativeMarking
                    }
                }
            }
            results.add(
                QuestionResultEntity(
                    attemptId = 0,
                    questionId = q.id,
                    categoryTitle = categoryTitles[q.categoryId] ?: "",
                    text = q.elements.toContentJson(json),
                    optionsJson = json.encodeToString(
                        ListSerializer(QuestionOptionDto.serializer()),
                        q.options.map { QuestionOptionDto(it.id, it.text, it.elements, it.image) }
                    ),
                    correctOptionIds = q.correctOptionIds.joinToString(","),
                    selectedOptionIds = selected.joinToString(","),
                    isCorrect = isCorrect,
                    explanation = q.explanationElements.toContentJson(json),
                    explanationImage = q.explanationImage,
                    dwellSeconds = dwellSeconds[q.id] ?: 0L
                )
            )
        }
        Logger.d("REPO", "saveAttempt(paper=$paperId, questions=${questions.size}, " +
            "correct=$correct, wrong=$wrong, skipped=$skipped, ungraded=$ungraded, " +
            "score=$score/maxScore=$maxScore)")
        // One transaction: the attempt header and its per-question rows must
        // land together, so history never shows an attempt without results.
        return db.withTransaction {
            val attemptId = db.attemptDao().insertAttempt(
                AttemptEntity(
                    paperId = paperId,
                    title = paperTitle,
                    totalQuestions = questions.size,
                    correctCount = correct,
                    wrongCount = wrong,
                    skippedCount = skipped,
                    score = score,
                    maxScore = maxScore,
                    durationSeconds = durationSeconds,
                    finishedAt = finishedAt
                )
            )
            db.attemptDao().insertResults(results.map { it.copy(attemptId = attemptId) })
            Logger.d("REPO", "saveAttempt stored attemptId=$attemptId with ${results.size} results")
            attemptId
        }
    }

    fun observeAttempts(): Flow<List<Attempt>> =
        db.attemptDao().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAttempt(attemptId: Long): Attempt? {
        Logger.d("REPO", "getAttempt($attemptId)")
        return db.attemptDao().getById(attemptId)?.toDomain()
    }

    private fun AttemptEntity.toDomain(): Attempt = Attempt(
        id = id,
        paperId = paperId,
        title = title,
        totalQuestions = totalQuestions,
        correctCount = correctCount,
        wrongCount = wrongCount,
        skippedCount = skippedCount,
        score = score,
        maxScore = maxScore,
        durationSeconds = durationSeconds,
        finishedAt = finishedAt
    )

    suspend fun getAttemptResults(attemptId: Long): List<QuestionResult> {
        Logger.d("REPO", "getAttemptResults($attemptId)")
        return db.attemptDao().getResults(attemptId).map { it.toDomainResult() }
    }

    suspend fun getAllQuestionResults(): List<QuestionResult> {
        Logger.d("REPO", "getAllQuestionResults()")
        return db.attemptDao().getAllResults().map { it.toDomainResult() }
    }

    suspend fun getAttempts(): List<Attempt> {
        Logger.d("REPO", "getAttempts()")
        return db.attemptDao().getAllAttempts().map { it.toDomain() }
    }

    /** Paper id -> count of distinct ever-missed questions (for badges). */
    suspend fun getMistakeCounts(): Map<String, Int> =
        com.mcqapp.domain.Mistakes.mistakenIdsByPaper(getAttempts(), getAllQuestionResults())
            .mapValues { it.value.size }

    /** Export DTO of all bookmarked questions, grouped by source paper. */
    suspend fun getBookmarkExportDto(): com.mcqapp.data.io.PaperDto? {
        val ids = db.bookmarkDao().getAll()
        if (ids.isEmpty()) return null
        // Three queries, not three per bookmark.
        val questions = getQuestionsByIds(ids)
        if (questions.isEmpty()) return null
        val titles = paperTitlesForCategories(questions.map { it.categoryId }.toSet())
        return com.mcqapp.data.io.BookmarkExport.paperDto(questions, titles)
    }

    /** Ever-missed questions of one paper, most-recently-missed first. */
    suspend fun getMistakenQuestions(paperId: String): List<Question> {
        val ids = com.mcqapp.domain.Mistakes.mistakenIdsByPaper(getAttempts(), getAllQuestionResults())[paperId]
            ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        val byId = db.questionDao().getByIdsChunked(ids).toDomainBulk().associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    private fun QuestionResultEntity.toDomainResult(): QuestionResult {
        // optionsJson arrives from an imported backup and is stored verbatim,
        // so it cannot be trusted to decode. An unguarded throw here made one
        // bad row permanently empty the History screen and mistake badges,
        // with no way to clear it from the UI.
        val options = try {
            json.decodeFromString(ListSerializer(QuestionOptionDto.serializer()), optionsJson)
        } catch (e: Exception) {
            Logger.w("REPO", "Unreadable optionsJson on result $id: ${e.message}")
            emptyList()
        }
        return QuestionResult(
            attemptId = attemptId,
            dwellSeconds = dwellSeconds,
            questionId = questionId,
            categoryTitle = categoryTitle,
            elements = text.parseContentElements(json),
            options = options.map {
                QuestionOption(it.id, elements = it.elements, image = it.image)
            },
            correctOptionIds = correctOptionIds.split(",").filter { it.isNotBlank() }.toSet(),
            selectedOptionIds = selectedOptionIds.split(",").filter { it.isNotBlank() }.toSet(),
            isCorrect = isCorrect,
            explanationElements = explanation.parseContentElements(json),
            explanationImage = explanationImage
        )
    }

    suspend fun deleteAttempt(attemptId: Long) {
        db.attemptDao().deleteById(attemptId)
    }
}

@kotlinx.serialization.Serializable
private data class QuestionOptionDto(
    val id: String,
    val text: String = "",
    @kotlinx.serialization.Serializable(with = com.mcqapp.domain.ContentElementListJson::class)
    val elements: List<com.mcqapp.domain.ContentElement> = emptyList(),
    val image: String? = null
)