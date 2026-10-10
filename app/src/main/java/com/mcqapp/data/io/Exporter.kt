package com.mcqapp.data.io

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.PassageEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.textContent
import com.mcqapp.util.Logger
import kotlinx.serialization.json.Json

class Exporter(private val db: AppDatabase) {

    private val json = Json { prettyPrint = true }

    suspend fun exportAll(): String {
        Logger.i("EXPORT", "Exporting all papers")
        val papers = db.paperDao().getAll()
        val paperDtos = papers.map { paper -> exportPaperDto(paper) }
        val bookmarks = db.bookmarkDao().getAll()
        val attemptDtos = db.attemptDao().getAllAttempts().map { attempt ->
            val results = db.attemptDao().getResults(attempt.id)
            AttemptDto(
                paperId = attempt.paperId,
                title = attempt.title,
                totalQuestions = attempt.totalQuestions,
                correctCount = attempt.correctCount,
                wrongCount = attempt.wrongCount,
                skippedCount = attempt.skippedCount,
                score = attempt.score,
                maxScore = attempt.maxScore,
                durationSeconds = attempt.durationSeconds,
                finishedAt = attempt.finishedAt,
                results = results.map { r ->
                    AttemptResultDto(
                        questionId = r.questionId,
                        categoryTitle = r.categoryTitle,
                        text = r.text,
                        optionsJson = r.optionsJson,
                        correctOptionIds = r.correctOptionIds,
                        selectedOptionIds = r.selectedOptionIds,
                        isCorrect = r.isCorrect,
                        explanation = r.explanation,
                        explanationImage = r.explanationImage,
                        dwellSeconds = r.dwellSeconds
                    )
                }
            )
        }
        Logger.i("EXPORT", "Exported ${paperDtos.size} papers, ${bookmarks.size} bookmarks, " +
            "${attemptDtos.size} attempts")
        return json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(
                version = 1,
                papers = paperDtos,
                bookmarks = bookmarks,
                passages = db.passageDao().getAll().map { it.toPassageDto() },
                attempts = attemptDtos,
                // Review progress travels with the backup. It used to be
                // dropped, so restoring a backup silently reset every SM-2
                // schedule, due date and leech flag.
                scheduling = db.cardStateDao().getAll()
                    .filter { it.reps > 0 || it.dueAt > 0L }
                    .associate { state ->
                        state.questionId to CardScheduleDto(
                            ease = state.ease,
                            intervalDays = state.intervalDays,
                            dueAt = state.dueAt,
                            reps = state.reps,
                            lapses = state.lapses,
                            leech = state.leech,
                            lastReviewedAt = state.lastReviewedAt
                        )
                    }
            )
        )
    }

    suspend fun getPaperDto(paperId: String): PaperDto? {
        val paper = db.paperDao().getById(paperId) ?: return null
        return exportPaperDto(paper)
    }

    /** Domain passages by id, for the apkg writer and other callers. */
    suspend fun getPassagesByIds(ids: List<String>): List<com.mcqapp.domain.Passage> {
        if (ids.isEmpty()) return emptyList()
        return db.passageDao().getByIds(ids).map { entity ->
            val elements = entity.text.parseContentElements(json)
            com.mcqapp.domain.Passage(
                id = entity.id,
                categoryId = entity.categoryId,
                title = entity.title,
                elements = elements,
                image = entity.image,
                sortOrder = entity.sortOrder
            )
        }
    }

    suspend fun exportPaper(paperId: String): String {
        Logger.i("EXPORT", "Exporting paper $paperId")
        val paper = db.paperDao().getById(paperId) ?: return json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = emptyList())
        )
        val paperDto = exportPaperDto(paper)
        return json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = listOf(paperDto), passages = passagesFor(paperDto))
        )
    }

    suspend fun exportCategories(paperId: String, rootCategoryIds: Set<String>): String {
        val paperDto = getCategoriesDto(paperId, rootCategoryIds) ?: PaperDto(
            id = paperId,
            title = "Paper"
        )
        return json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = listOf(paperDto), passages = passagesFor(paperDto))
        )
    }

    /**
     * Every passage a category-subset export still needs: the passages of the
     * selected subtrees. A member question outside the subtrees must not pull
     * its passage in, or the export would leak questions the user excluded;
     * a passage whose members are all outside is dropped by the same rule.
     */
    private suspend fun passagesFor(paperDto: PaperDto): List<PassageDto> {
        val memberIds = paperDto.categories
            .flatMap { it.questions }
            .mapNotNull { it.passageId }
            .toSet()
        if (memberIds.isEmpty()) return emptyList()
        return db.passageDao().getAll()
            .filter { it.id in memberIds }
            .map { it.toPassageDto() }
    }


    /** Paper DTO restricted to the given category subtrees (descendants included). */
    suspend fun getCategoriesDto(paperId: String, rootCategoryIds: Set<String>): PaperDto? {
        val paper = db.paperDao().getById(paperId) ?: return null
        val allCategories = db.categoryDao().getByPaper(paperId)
        val selected = rootCategoryIds.flatMapTo(mutableSetOf()) { rootId ->
            CategoryFilter.subtreeIds(allCategories, rootId)
        }
        val selectedCategories = allCategories.filter { it.id in selected }.map { category ->
            exportCategoryDto(category)
        }
        val dto = PaperDto(
            id = paperId,
            title = paper.title,
            description = paper.description,
            durationMinutes = paper.durationMinutes,
            negativeMarking = paper.negativeMarking,
            categories = selectedCategories
        )
        return dto.copy(passages = passagesFor(dto))
    }

    private suspend fun exportPaperDto(paper: PaperEntity): PaperDto {
        val categories = db.categoryDao().getByPaper(paper.id)
        val dto = PaperDto(
            id = paper.id,
            title = paper.title,
            description = paper.description,
            durationMinutes = paper.durationMinutes,
            negativeMarking = paper.negativeMarking,
            categories = categories.map { exportCategoryDto(it) }
        )
        return dto.copy(passages = passagesFor(dto))
    }

    private suspend fun PassageEntity.toPassageDto(): PassageDto {
        val elements = text.parseContentElements(json)
        return PassageDto(
            id = id,
            title = title,
            elements = elements,
            image = image,
            categoryId = categoryId
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
        val optionsByQuestion = db.optionDao().getForQuestionsChunked(ids).groupBy { it.questionId }
        val correctByQuestion = db.correctAnswerDao().getForQuestionsChunked(ids).groupBy { it.questionId }
        return map { entity ->
            val options = optionsByQuestion[entity.id] ?: emptyList()
            val correctIds = correctByQuestion[entity.id]?.map { it.optionId } ?: emptyList()
            val elements = entity.text.parseContentElements(json)
            QuestionDto(
                id = entity.id,
                text = elements.textContent,
                elements = elements,
                image = entity.image,
                options = options.map { option ->
                    val optionElements = option.text.parseContentElements(json)
                    OptionDto(
                        id = option.id,
                        text = optionElements.textContent,
                        elements = optionElements,
                        image = option.image
                    )
                },
                correctOptionIds = correctIds,
                explanation = entity.explanation.parseContentElements(json).textContent,
                explanationElements = entity.explanation.parseContentElements(json),
                explanationImage = entity.explanationImage,
                difficulty = entity.difficulty,
                marks = entity.marks,
                tags = entity.tags.split(",").filter { it.isNotBlank() },
                passageId = entity.passageId
            )
        }
    }
}
