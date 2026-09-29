package com.mcqapp.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
        finishedAt: Long
    ): Long {
        var correct = 0
        var wrong = 0
        var skipped = 0
        var ungraded = 0
        var score = 0.0
        val results = mutableListOf<QuestionResultEntity>()
        for (q in questions) {
            val selected = selections[q.id].orEmpty()
            if (q.correctOptionIds.isEmpty()) {
                // No answer key: excluded from scoring entirely (no credit, no penalty).
                ungraded++
            } else {
                val isCorrect = selected.isNotEmpty() && selected == q.correctOptionIds
                when {
                    selected.isEmpty() -> skipped++
                    isCorrect -> {
                        correct++
                        score += 1.0
                    }
                    else -> {
                        wrong++
                        score -= negativeMarking
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
                    explanationImage = q.explanationImage
                )
            )
        }
        Logger.d("REPO", "saveAttempt(paper=$paperId, questions=${questions.size}, " +
            "correct=$correct, wrong=$wrong, skipped=$skipped, ungraded=$ungraded, score=$score)")
        val attemptId = db.attemptDao().insertAttempt(
            AttemptEntity(
                paperId = paperId,
                title = paperTitle,
                totalQuestions = questions.size,
                correctCount = correct,
                wrongCount = wrong,
                skippedCount = skipped,
                score = score,
                maxScore = (correct + wrong + skipped).toDouble(),
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
        return db.attemptDao().getResults(attemptId).map { entity ->
            val options = json.decodeFromString(ListSerializer(QuestionOptionDto.serializer()), entity.optionsJson)
            QuestionResult(
                questionId = entity.questionId,
                categoryTitle = entity.categoryTitle,
                text = entity.text,
                options = options.map { QuestionOption(it.id, it.text, it.image) },
                correctOptionIds = entity.correctOptionIds.split(",").filter { it.isNotBlank() }.toSet(),
                selectedOptionIds = entity.selectedOptionIds.split(",").filter { it.isNotBlank() }.toSet(),
                isCorrect = entity.isCorrect,
                explanation = entity.explanation,
                explanationImage = entity.explanationImage
            )
        }
    }

    suspend fun deleteAttempt(attemptId: Long) {
        db.attemptDao().deleteById(attemptId)
    }
}

@kotlinx.serialization.Serializable
private data class QuestionOptionDto(val id: String, val text: String, val image: String? = null)
