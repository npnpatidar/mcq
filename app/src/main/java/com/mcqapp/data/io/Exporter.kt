package com.mcqapp.data.io

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.util.Logger
import kotlinx.serialization.json.Json

class Exporter(private val db: AppDatabase) {

    private val json = Json { prettyPrint = true }

    suspend fun exportAll(): String {
        Logger.i("EXPORT", "Exporting all papers")
        val paperDtos = db.paperDao().getAll().map { paper ->
            val categories = db.categoryDao().getByPaper(paper.id)
            val categoryDtos = categories.map { category ->
                val questions = db.questionDao().getByCategory(category.id).map { question ->
                    val options = db.optionDao().getByQuestion(question.id)
                    val correctIds = db.correctAnswerDao().getCorrectIds(question.id)
                    QuestionDto(
                        id = question.id,
                        text = question.text,
                        image = question.image,
                        options = options.map { OptionDto(it.id, it.text, it.image) },
                        correctOptionIds = correctIds,
                        explanation = question.explanation,
                        difficulty = question.difficulty,
                        tags = question.tags.split(",").filter { it.isNotBlank() }
                    )
                }
                CategoryDto(
                    id = category.id,
                    title = category.title,
                    parentId = category.parentId,
                    questions = questions
                )
            }
            PaperDto(
                id = paper.id,
                title = paper.title,
                description = paper.description,
                durationMinutes = paper.durationMinutes,
                negativeMarking = paper.negativeMarking,
                categories = categoryDtos
            )
        }
        Logger.i("EXPORT", "Exported ${paperDtos.size} papers total")
        return json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = paperDtos)
        )
    }

    suspend fun exportPaper(paperId: String): String {
        Logger.i("EXPORT", "Exporting paper $paperId")
        val paper = db.paperDao().getById(paperId) ?: return json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = emptyList())
        )
        val categories = db.categoryDao().getByPaper(paperId)
        val categoryDtos = categories.map { category ->
            val questions = db.questionDao().getByCategory(category.id).map { question ->
                val options = db.optionDao().getByQuestion(question.id)
                val correctIds = db.correctAnswerDao().getCorrectIds(question.id)
                QuestionDto(
                    id = question.id,
                    text = question.text,
                    image = question.image,
                    options = options.map { OptionDto(it.id, it.text, it.image) },
                    correctOptionIds = correctIds,
                    explanation = question.explanation,
                    difficulty = question.difficulty,
                    tags = question.tags.split(",").filter { it.isNotBlank() }
                )
            }
            CategoryDto(
                id = category.id,
                title = category.title,
                parentId = category.parentId,
                questions = questions
            )
        }
        val paperDto = PaperDto(
            id = paper.id,
            title = paper.title,
            description = paper.description,
            durationMinutes = paper.durationMinutes,
            negativeMarking = paper.negativeMarking,
            categories = categoryDtos
        )
        return json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = listOf(paperDto))
        )
    }

    suspend fun exportCategories(paperId: String, rootCategoryIds: Set<String>): String {
        val allCategories = db.categoryDao().getByPaper(paperId)
        val byId = allCategories.associateBy { it.id }
        val selected = mutableSetOf<String>()
        for (rootId in rootCategoryIds) {
            collectWithDescendants(rootId, byId, selected)
        }
        val categoryDtos = allCategories.filter { it.id in selected }.map { category ->
            val questions = db.questionDao().getByCategory(category.id).map { question ->
                val options = db.optionDao().getByQuestion(question.id)
                val correctIds = db.correctAnswerDao().getCorrectIds(question.id)
                QuestionDto(
                    id = question.id,
                    text = question.text,
                    image = question.image,
                    options = options.map { OptionDto(it.id, it.text, it.image) },
                    correctOptionIds = correctIds,
                    explanation = question.explanation,
                    difficulty = question.difficulty,
                    tags = question.tags.split(",").filter { it.isNotBlank() }
                )
            }
            CategoryDto(
                id = category.id,
                title = category.title,
                parentId = category.parentId,
                questions = questions
            )
        }
        val paper = db.paperDao().getById(paperId)
        val paperDto = PaperDto(
            id = paperId,
            title = paper?.title ?: "Paper",
            description = paper?.description ?: "",
            durationMinutes = paper?.durationMinutes ?: 0,
            negativeMarking = paper?.negativeMarking ?: 0.0,
            categories = categoryDtos
        )
        return json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = listOf(paperDto))
        )
    }

    private fun collectWithDescendants(
        categoryId: String,
        byId: Map<String, CategoryEntity>,
        sink: MutableSet<String>
    ) {
        if (!sink.add(categoryId)) return
        for ((id, category) in byId) {
            if (category.parentId == categoryId) {
                collectWithDescendants(id, byId, sink)
            }
        }
    }
}
