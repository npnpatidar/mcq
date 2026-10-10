package com.mcqapp.data.io

import com.mcqapp.domain.Question

/**
 * The one place a domain [Question] becomes a [QuestionDto].
 *
 * Both export builders assemble a paper out of questions that are already in
 * the database rather than loading a paper, and they must copy every field.
 * Two of them getting it subtly wrong is how an option that is a formula or a
 * picture ends up exported as its bare text: `OptionDto(id, text, image)` is a
 * convenience constructor that rebuilds a single plain-text element, so leaving
 * `elements` out loses the content silently.
 */
internal fun Question.toQuestionDto() = QuestionDto(
    id = id,
    text = text,
    elements = elements,
    image = image,
    options = options.map { option ->
        OptionDto(
            id = option.id,
            text = option.text,
            elements = option.elements,
            image = option.image
        )
    },
    correctOptionIds = correctOptionIds.toList(),
    explanation = explanation,
    explanationElements = explanationElements,
    explanationImage = explanationImage,
    difficulty = difficulty.label,
    marks = marks,
    tags = tags,
    passageId = passageId
)
