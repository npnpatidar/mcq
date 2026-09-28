package com.mcqapp.data.repository

import android.content.Context
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
import kotlinx.coroutines.flow.Flow
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

    fun observePapers(): Flow<List<Paper>> =
        db.paperDao().observeAll().map { papers ->
            Logger.d("REPO", "observePapers emitted ${papers.size} papers")
            papers.map { it.toDomain() }
        }

    suspend fun getPaper(paperId: String): Paper? {
        Logger.d("REPO", "getPaper($paperId)")
        return db.paperDao().getById(paperId)?.toDomain()
    }

    private suspend fun PaperEntity.toDomain(): Paper {
        val categories = db.categoryDao().getByPaper(id)
        return Paper(
            id = id,
            title = title,
            description = description,
            durationMinutes = durationMinutes,
            negativeMarking = negativeMarking,
            categories = buildTree(categories)
        )
    }

    private suspend fun buildTree(categories: List<CategoryEntity>): List<CategoryNode> {
        val byParent = categories.groupBy { it.parentId }
        suspend fun build(parentId: String?): List<CategoryNode> =
            (byParent[parentId] ?: emptyList()).map { category ->
                CategoryNode(
                    id = category.id,
                    paperId = category.paperId,
                    title = category.title,
                    parentId = category.parentId,
                    children = build(category.id),
                    questionCount = db.questionDao().countByCategory(category.id)
                )
            }
        return build(null)
    }

    suspend fun getQuestionsForCategories(categoryIds: List<String>): List<Question> {
        Logger.d("REPO", "getQuestionsForCategories(${categoryIds.size} categories)")
        val result = mutableListOf<Question>()
        for (categoryId in categoryIds) {
            result.addAll(db.questionDao().getByCategory(categoryId).map { it.toDomain() })
        }
        Logger.d("REPO", "getQuestionsForCategories returned ${result.size} questions")
        return result
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
            difficulty = Difficulty.fromLabel(difficulty),
            tags = tags.split(",").filter { it.isNotBlank() }
        )
    }

    suspend fun saveQuestion(question: Question) {
        Logger.d("REPO", "saveQuestion(${question.id}, category=${question.categoryId}, " +
            "${question.options.size} options, correct=${question.correctOptionIds})")
        db.questionDao().upsert(
            QuestionEntity(
                id = question.id,
                categoryId = question.categoryId,
                text = question.text,
                image = question.image,
                explanation = question.explanation,
                difficulty = question.difficulty.label,
                tags = question.tags.joinToString(",")
            )
        )
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
        db.correctAnswerDao().deleteByQuestion(question.id)
        db.correctAnswerDao().upsertAll(
            question.correctOptionIds.map {
                CorrectAnswerEntity(question.id, it)
            }
        )
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
        var score = 0.0
        val results = mutableListOf<QuestionResultEntity>()
        for (q in questions) {
            val selected = selections[q.id].orEmpty()
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
                    explanation = q.explanation
                )
            )
        }
        Logger.d("REPO", "saveAttempt(paper=$paperId, questions=${questions.size}, " +
            "correct=$correct, wrong=$wrong, skipped=$skipped, score=$score)")
        val attemptId = db.attemptDao().insertAttempt(
            AttemptEntity(
                paperId = paperId,
                title = paperTitle,
                totalQuestions = questions.size,
                correctCount = correct,
                wrongCount = wrong,
                skippedCount = skipped,
                score = score,
                maxScore = questions.size.toDouble(),
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
                explanation = entity.explanation
            )
        }
    }

    suspend fun deleteAttempt(attemptId: Long) {
        db.attemptDao().deleteById(attemptId)
    }
}

@kotlinx.serialization.Serializable
private data class QuestionOptionDto(val id: String, val text: String, val image: String? = null)
