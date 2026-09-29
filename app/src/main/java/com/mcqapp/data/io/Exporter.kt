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
        val papers = db.paperDao().getAll()
        val paperDtos = papers.map { paper -> exportPaperDto(paper) }
        Logger.i("EXPORT", "Exported ${paperDtos.size} papers total")
        return json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = paperDtos)
        )
    }

    suspend fun getPaperDto(paperId: String): PaperDto? {
        val paper = db.paperDao().getById(paperId) ?: return null
        return exportPaperDto(paper)
    }

    suspend fun exportPaper(paperId: String): String {
        Logger.i("EXPORT", "Exporting paper $paperId")
        val paper = db.paperDao().getById(paperId) ?: return json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = emptyList())
        )
        return json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = listOf(exportPaperDto(paper)))
        )
    }

    suspend fun exportCategories(paperId: String, rootCategoryIds: Set<String>): String {
        val allCategories = db.categoryDao().getByPaper(paperId)
        val byId = allCategories.associateBy { it.id }
        val selected = mutableSetOf<String>()
        for (rootId in rootCategoryIds) {
            collectWithDescendants(rootId, byId, selected)
        }
        val paper = db.paperDao().getById(paperId)
        val paperDto = PaperDto(
            id = paperId,
            title = paper?.title ?: "Paper",
            description = paper?.description ?: "",
            durationMinutes = paper?.durationMinutes ?: 0,
            negativeMarking = paper?.negativeMarking ?: 0.0,
            categories = allCategories.filter { it.id in selected }.map { category ->
                exportCategoryDto(category)
            }
        )
        return json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = listOf(paperDto))
        )
    }

    private suspend fun exportPaperDto(paper: PaperEntity): PaperDto {
        val categories = db.categoryDao().getByPaper(paper.id)
        return PaperDto(
            id = paper.id,
            title = paper.title,
            description = paper.description,
            durationMinutes = paper.durationMinutes,
            negativeMarking = paper.negativeMarking,
            categories = categories.map { exportCategoryDto(it) }
        )
    }

    private suspend fun exportCategoryDto(category: CategoryEntity): CategoryDto {
        val questions = db.questionDao().getByCategory(category.id)
        return CategoryDto(
            id = category.id,
            title = category.title,
            parentId = category.parentId,
            questions = questions.toDtoBulk()
        )
    }

    private suspend fun List<QuestionEntity>.toDtoBulk(): List<QuestionDto> {
        if (isEmpty()) return emptyList()
        val ids = map { it.id }
        val optionsByQuestion = db.optionDao().getForQuestions(ids).groupBy { it.questionId }
        val correctByQuestion = db.correctAnswerDao().getForQuestions(ids).groupBy { it.questionId }
        return map { entity ->
            val options = optionsByQuestion[entity.id] ?: emptyList()
            val correctIds = correctByQuestion[entity.id]?.map { it.optionId } ?: emptyList()
            QuestionDto(
                id = entity.id,
                text = entity.text,
                image = entity.image,
                options = options.map { OptionDto(it.id, it.text, it.image) },
                correctOptionIds = correctIds,
                explanation = entity.explanation,
                explanationImage = entity.explanationImage,
                difficulty = entity.difficulty,
                marks = entity.marks,
                tags = entity.tags.split(",").filter { it.isNotBlank() }
            )
        }
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
