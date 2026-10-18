package com.mcqapp.data.anki

import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption

/**
 * Turns one wire DTO into the domain objects an Anki card is built from.
 *
 * The apkg writer walks a paper a question at a time ([PaperStream]), so it
 * never holds the flattened list this mapper used to build: the walk already
 * carries the paper's order, and one question is mapped on the way past.
 */
object AnkiDtoMapper {

    /**
     * [categoryId] is the id of the category the question came from, or blank
     * for a paper's uncategorised questions. The exporter uses it to choose the
     * Anki subdeck.
     */
    fun toQuestion(dto: QuestionDto, categoryId: String): Question = Question(
        id = dto.id,
        categoryId = categoryId,
        elements = dto.elements.ifEmpty {
            listOf(com.mcqapp.domain.ContentElement.TextElement(dto.text))
        },
        image = dto.image,
        options = dto.options.map {
            QuestionOption(
                it.id,
                it.elements.ifEmpty {
                    listOf(com.mcqapp.domain.ContentElement.TextElement(it.text))
                },
                it.image
            )
        },
        correctOptionIds = dto.correctOptionIds.toSet(),
        explanationElements = dto.explanationElements.ifEmpty {
            listOf(com.mcqapp.domain.ContentElement.TextElement(dto.explanation))
        },
        explanationImage = dto.explanationImage,
        difficulty = Difficulty.fromLabel(dto.difficulty),
        marks = dto.marks,
        tags = dto.tags,
        // The membership rides along, or the apkg writer could not find the
        // passage to prepend and the payload would lose the grouping.
        passageId = dto.passageId
    )

    /** One passage as a domain object, for the same one-at-a-time walk. */
    fun toPassage(dto: PassageDto): com.mcqapp.domain.Passage = com.mcqapp.domain.Passage(
        id = dto.id,
        categoryId = "",
        title = dto.title,
        elements = dto.elements,
        image = dto.image,
        sortOrder = 0
    )
}
