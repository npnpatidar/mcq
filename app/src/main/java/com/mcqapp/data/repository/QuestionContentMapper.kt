package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PassageEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Passage
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.toContentJson
import kotlinx.serialization.json.Json

/**
 * Entity <-> domain mapping for questions, and the content hash, in one place.
 * Shared by the question, paper, study and history collaborators of
 * [McqRepository] so a schema or JSON change cannot leave one path mapping
 * differently from the others.
 */
internal class QuestionContentMapper(private val db: AppDatabase) {

    val json = Json { ignoreUnknownKeys = true }

    /**
     * The single content hash. [passageId] joined it on purpose: reassigning a
     * passage changes the hash, so that question's `card_state` resets —
     * exactly what an edit of its text does. Leaving the passage out would let
     * a question change passages without the schedule ever noticing.
     */
    fun computeContentHash(
        text: String,
        optionTexts: List<String>,
        optionImages: List<String?>,
        passageId: String? = null
    ): String =
        // Single source of truth lives in ContentHash; this wrapper keeps the
        // existing call sites readable.
        com.mcqapp.data.io.ContentHash.of(text, optionTexts, optionImages, passageId)

    /**
     * Mirrors the hash written by [QuestionStore.saveQuestion] so a stored card
     * can be compared against the question it was scheduled for.
     */
    fun contentHashOf(question: Question): String = computeContentHash(
        question.text,
        question.options.map { it.text },
        question.options.map { it.image },
        question.passageId
    )

    suspend fun toDomain(entity: QuestionEntity): Question {
        val options = db.optionDao().getByQuestion(entity.id)
        val correctIds = db.correctAnswerDao().getCorrectIds(entity.id).toSet()
        return Question(
            id = entity.id,
            categoryId = entity.categoryId,
            elements = entity.text.parseContentElements(json),
            image = entity.image,
            options = options.map { QuestionOption(it.id, it.text.parseContentElements(json), it.image) },
            correctOptionIds = correctIds,
            explanationElements = entity.explanation.parseContentElements(json),
            explanationImage = entity.explanationImage,
            difficulty = Difficulty.fromLabel(entity.difficulty),
            marks = entity.marks,
            tags = entity.tags.split(",").filter { it.isNotBlank() },
            passageId = entity.passageId
        )
    }

    suspend fun toDomainBulk(entities: List<QuestionEntity>): List<Question> {
        if (entities.isEmpty()) return emptyList()
        val ids = entities.map { it.id }
        val optionsByQuestion = db.optionDao().getForQuestionsChunked(ids).groupBy { it.questionId }
        val correctByQuestion = db.correctAnswerDao().getForQuestionsChunked(ids).groupBy { it.questionId }
        return entities.map { entity ->
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
                explanationImage = entity.explanationImage,
                difficulty = Difficulty.fromLabel(entity.difficulty),
                marks = entity.marks,
                tags = entity.tags.split(",").filter { it.isNotBlank() },
                passageId = entity.passageId
            )
        }
    }

    // ---- passages ----

    fun toPassage(entity: PassageEntity): Passage = Passage(
        id = entity.id,
        categoryId = entity.categoryId,
        title = entity.title,
        elements = entity.text.parseContentElements(json),
        image = entity.image,
        sortOrder = entity.sortOrder
    )

    fun toEntity(passage: Passage): PassageEntity = PassageEntity(
        id = passage.id,
        categoryId = passage.categoryId,
        title = passage.title,
        text = passage.elements.toContentJson(json),
        image = passage.image,
        sortOrder = passage.sortOrder
    )
}
