package com.mcqapp.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.AttemptEntity
import com.mcqapp.data.local.BookmarkEntity
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.CorrectAnswerEntity
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.QuestionResultEntity
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.QuestionResult
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

    private val progressKey = stringPreferencesKey("in_progress_test")

    suspend fun saveTestProgress(json: String) {
        context.dataStore.edit { it[progressKey] = json }
    }

    suspend fun loadTestProgress(): String? =
        context.dataStore.data.map { it[progressKey] }.first()

    suspend fun clearTestProgress() {
        context.dataStore.edit { it.remove(progressKey) }
    }

    /** Cross-paper text/tag search with paper provenance attached. */
    suspend fun searchGlobal(query: String): List<com.mcqapp.domain.QuestionSearch.Hit> {
        if (query.isBlank()) return emptyList()
        val questions = searchQuestions(query)
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
            db.optionDao().getForQuestions(allQuestions.map { it.id }).groupBy { it.questionId }
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

    private suspend fun PaperEntity.toDomain(countMap: Map<String, Int> = emptyMap()): Paper {
        val categories = db.categoryDao().getByPaper(id)
        return Paper(
            id = id,
            title = title,
            description = description,
            durationMinutes = durationMinutes,
            negativeMarking = negativeMarking,
            categories = buildTree(categories, countMap)
        )
    }

    private suspend fun buildTree(
        categories: List<CategoryEntity>,
        countMap: Map<String, Int> = emptyMap()
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
        db.questionDao().observeCategoryCounts().map {
            val started = android.os.SystemClock.elapsedRealtime()
            val categoryIds = db.categoryDao().getByPaper(paperId).map { it.id }
            val entities = categoryIds.flatMap { db.questionDao().getByCategory(it) }
            val result = entities.toDomainBulk()
            Logger.d("REPO", "observeQuestionsForPaper($paperId): " +
                "mapped ${result.size} questions in " +
                "${android.os.SystemClock.elapsedRealtime() - started}ms")
            result
        }.flowOn(Dispatchers.IO)

    fun observeQuestion(questionId: String): Flow<Question?> =
        db.questionDao().observeAll().map { list -> list.find { it.id == questionId } }
            .map { entity ->
                entity?.let {
                    val options = db.optionDao().getByQuestion(it.id)
                    val correctIds = db.correctAnswerDao().getCorrectIds(it.id).toSet()
                    Question(
                        id = it.id,
                        categoryId = it.categoryId,
                        text = it.text,
                        image = it.image,
                        options = options.map { opt -> QuestionOption(opt.id, opt.text, opt.image) },
                        correctOptionIds = correctIds,
                        explanation = it.explanation,
                        explanationImage = it.explanationImage,
                        difficulty = Difficulty.fromLabel(it.difficulty),
                        marks = it.marks,
                        tags = it.tags.split(",").filter { t -> t.isNotBlank() }
                    )
                }
            }

    suspend fun getQuestionsForCategories(categoryIds: List<String>): List<Question> {
        Logger.d("REPO", "getQuestionsForCategories(${categoryIds.size} categories)")
        val entities = categoryIds.flatMap { db.questionDao().getByCategory(it) }
        val result = entities.toDomainBulk()
        Logger.d("REPO", "getQuestionsForCategories returned ${result.size} questions")
        return result
    }

    private suspend fun List<QuestionEntity>.toDomainBulk(): List<Question> {
        if (isEmpty()) return emptyList()
        val ids = map { it.id }
        val optionsByQuestion = db.optionDao().getForQuestions(ids).groupBy { it.questionId }
        val correctByQuestion = db.correctAnswerDao().getForQuestions(ids).groupBy { it.questionId }
        return map { entity ->
            val options = optionsByQuestion[entity.id] ?: emptyList()
            val correctIds = correctByQuestion[entity.id]?.map { it.optionId }?.toSet() ?: emptySet()
            Question(
                id = entity.id,
                categoryId = entity.categoryId,
                text = entity.text,
                image = entity.image,
                options = options.map { QuestionOption(it.id, it.text, it.image) },
                correctOptionIds = correctIds,
                explanation = entity.explanation,
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

    private suspend fun QuestionEntity.toDomain(): Question {
        val options = db.optionDao().getByQuestion(id)
        val correctIds = db.correctAnswerDao().getCorrectIds(id).toSet()
        return Question(
            id = id,
            categoryId = categoryId,
            text = text,
            image = image,
            options = options.map { QuestionOption(it.id, it.text, it.image) },
            correctOptionIds = correctIds,
            explanation = explanation,
            explanationImage = explanationImage,
            difficulty = Difficulty.fromLabel(difficulty),
            marks = marks,
            tags = tags.split(",").filter { it.isNotBlank() }
        )
    }

    private fun computeContentHash(text: String, optionTexts: List<String>, optionImages: List<String?>): String {
        val raw = text + "|" + optionTexts.joinToString(",") + "|" + optionImages.joinToString(",")
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

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
            "text='${question.text.take(60)}')")
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
                text = question.text,
                image = question.image,
                explanation = question.explanation,
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
                    text = o.text,
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

    suspend fun deleteQuestion(questionId: String) {
        Logger.d("REPO", "deleteQuestion($questionId)")
        db.questionDao().deleteById(questionId)
    }

    suspend fun deleteQuestions(questionIds: Collection<String>) {
        Logger.i("REPO", "deleteQuestions(${questionIds.size} ids)")
        questionIds.forEach { db.questionDao().deleteById(it) }
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
        questionIds.forEach { id ->
            getQuestion(id)?.let { saveQuestion(it.copy(categoryId = targetCategoryId)) }
        }
    }

    /** Deep-copies questions into another category (appended after siblings). */
    suspend fun copyQuestionsToCategory(questionIds: Collection<String>, targetCategoryId: String) {
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

    /** Bulk-sets marks/difficulty/tags on questions; null fields are kept. */
    suspend fun bulkUpdateQuestions(
        questionIds: Collection<String>,
        marks: Double?,
        difficulty: Difficulty?,
        tags: List<String>?
    ) {
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

    /** Swaps the positions of two same-category questions. */
    suspend fun swapQuestionOrder(firstId: String, secondId: String): Boolean {
        val a = db.questionDao().getById(firstId) ?: return false
        val b = db.questionDao().getById(secondId) ?: return false
        if (a.categoryId != b.categoryId) return false
        db.questionDao().updateSortOrder(a.id, b.sortOrder)
        db.questionDao().updateSortOrder(b.id, a.sortOrder)
        Logger.i("REPO", "swapQuestionOrder(${a.id} <-> ${b.id})")
        return true
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

    suspend fun deletePaper(paperId: String) {
        Logger.d("REPO", "deletePaper($paperId)")
        db.paperDao().deleteById(paperId)
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
        db.categoryDao().deleteById(categoryId)
    }

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

    suspend fun searchQuestions(query: String): List<Question> {
        Logger.d("REPO", "searchQuestions('$query')")
        return db.questionDao().search(query).map { it.toDomain() }
    }

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
        for (q in questions) {
            val selected = selections[q.id].orEmpty()
            if (q.correctOptionIds.isEmpty()) {
                // No answer key: excluded from scoring entirely (no credit, no penalty).
                ungraded++
            } else {
                maxScore += q.marks
                val isCorrect = selected.isNotEmpty() && selected == q.correctOptionIds
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
            val isCorrect = selected.isNotEmpty() && selected == q.correctOptionIds
            results.add(
                QuestionResultEntity(
                    attemptId = 0,
                    questionId = q.id,
                    categoryTitle = categoryTitleOf(q.categoryId),
                    text = q.text,
                    optionsJson = json.encodeToString(
                        ListSerializer(QuestionOptionDto.serializer()),
                        q.options.map { QuestionOptionDto(it.id, it.text, it.image) }
                    ),
                    correctOptionIds = q.correctOptionIds.joinToString(","),
                    selectedOptionIds = selected.joinToString(","),
                    isCorrect = isCorrect,
                    explanation = q.explanation,
                    explanationImage = q.explanationImage,
                    dwellSeconds = dwellSeconds[q.id] ?: 0L
                )
            )
        }
        Logger.d("REPO", "saveAttempt(paper=$paperId, questions=${questions.size}, " +
            "correct=$correct, wrong=$wrong, skipped=$skipped, ungraded=$ungraded, " +
            "score=$score/maxScore=$maxScore)")
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
        return attemptId
    }

    private suspend fun categoryTitleOf(categoryId: String): String =
        db.categoryDao().getById(categoryId)?.title ?: ""

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
        val questions = ids.mapNotNull { getQuestion(it) }
        if (questions.isEmpty()) return null
        val titles = mutableMapOf<String, String>()
        for (categoryId in questions.map { it.categoryId }.toSet()) {
            val category = db.categoryDao().getById(categoryId) ?: continue
            val paper = db.paperDao().getById(category.paperId) ?: continue
            titles[categoryId] = paper.title
        }
        return com.mcqapp.data.io.BookmarkExport.paperDto(questions, titles)
    }

    /** Ever-missed questions of one paper, most-recently-missed first. */
    suspend fun getMistakenQuestions(paperId: String): List<Question> {
        val ids = com.mcqapp.domain.Mistakes.mistakenIdsByPaper(getAttempts(), getAllQuestionResults())[paperId]
            ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        val byId = db.questionDao().getByIds(ids).toDomainBulk().associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    private fun QuestionResultEntity.toDomainResult(): QuestionResult {
        val options = json.decodeFromString(ListSerializer(QuestionOptionDto.serializer()), optionsJson)
        return QuestionResult(
            attemptId = attemptId,
            dwellSeconds = dwellSeconds,
            questionId = questionId,
            categoryTitle = categoryTitle,
            text = text,
            options = options.map { QuestionOption(it.id, it.text, it.image) },
            correctOptionIds = correctOptionIds.split(",").filter { it.isNotBlank() }.toSet(),
            selectedOptionIds = selectedOptionIds.split(",").filter { it.isNotBlank() }.toSet(),
            isCorrect = isCorrect,
            explanation = explanation,
            explanationImage = explanationImage
        )
    }

    suspend fun deleteAttempt(attemptId: Long) {
        db.attemptDao().deleteById(attemptId)
    }
}

@kotlinx.serialization.Serializable
private data class QuestionOptionDto(val id: String, val text: String, val image: String? = null)
