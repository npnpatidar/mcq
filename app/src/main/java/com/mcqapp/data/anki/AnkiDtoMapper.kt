package com.mcqapp.data.anki

import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption

/**
 * Flattens a [PaperDto] into domain [Question]s in document order.
 *
 * Category questions are visited depth-first in the order they appear, then any
 * top-level (uncategorised) questions. This preserves the paper's natural
 * reading order, which becomes the new-card order in Anki.
 */
object AnkiDtoMapper {

    fun flattenQuestions(paper: PaperDto): List<Question> {
        val result = ArrayList<Question>()
        paper.categories
            .filter { it.parentId == null }
            .forEach { root -> visit(root, paper.categories, result) }
        result.addAll(paper.topLevelQuestions().toQuestions(""))
        // One note per question id. A question that is both a paper's
        // uncategorised list and inside one of its categories would otherwise
        // become two notes with a single guid, and Anki drops the second.
        return result.distinctBy { it.id }
    }

    private fun visit(category: CategoryDto, all: List<CategoryDto>, out: MutableList<Question>) {
        out.addAll(category.questions.toQuestions(category.id))
        all.filter { it.parentId == category.id }.forEach { child -> visit(child, all, out) }
    }

    /**
     * [categoryId] is the id of the category the questions came from, or blank
     * for a paper's uncategorised questions. The exporter uses it to choose the
     * Anki subdeck.
     */
    private fun List<QuestionDto>.toQuestions(categoryId: String): List<Question> = map { dto ->
        Question(
            id = dto.id,
            categoryId = categoryId,
            elements = dto.elements,
            image = dto.image,
            options = dto.options.map { QuestionOption(it.id, it.elements, it.image) },
            correctOptionIds = dto.correctOptionIds.toSet(),
            explanationElements = dto.explanationElements,
            explanationImage = dto.explanationImage,
            difficulty = Difficulty.fromLabel(dto.difficulty),
            marks = dto.marks,
            tags = dto.tags
        )
    }
}