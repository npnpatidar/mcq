package com.mcqapp.data.anki

import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.ContentElementListJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The MCQ payload stored in a note's third field.
 *
 * Anki notefields are HTML strings, so the readable back field (`✓`/`✗` markers
 * plus the explanation) is not a lossless container: an option containing a
 * line break is indistinguishable from two options. Keeping the structured
 * question in a field of its own makes a round trip exact, and Anki tolerates
 * it because the card template only renders Front and Back.
 *
 * Import falls back to parsing the readable back field when this field is
 * absent, which is what any deck not written by us looks like.
 */
@Serializable
data class AnkiMcqPayload(
    /**
     * The question's own image, as a data URI.
     *
     * The front field carries it too, but there it is only the first `<img>`
     * anywhere in the field, which for a question whose images all belong to
     * its options is an option's image. Reading it from here instead of
     * guessing keeps the round trip exact, and older packages without the field
     * still fall back to the front.
     */
    val image: String? = null,
    val options: List<AnkiMcqOption> = emptyList(),
    val correct: List<String> = emptyList(),
    val explanation: String = "",
    val explanationImage: String? = null,
    val difficulty: String = "medium",
    val marks: Double = 1.0,
    val tags: List<String> = emptyList(),
    /** Rich content; see [com.mcqapp.domain.ContentElement]. */
    @SerialName("question_elements")
    @Serializable(with = ContentElementListJson::class)
    val elements: List<ContentElement> = emptyList(),
    @SerialName("explanation_elements")
    @Serializable(with = ContentElementListJson::class)
    val explanationElements: List<ContentElement> = emptyList()
)

@Serializable
data class AnkiMcqOption(
    val id: String,
    val text: String = "",
    @Serializable(with = ContentElementListJson::class)
    val elements: List<ContentElement> = emptyList(),
    val image: String? = null
)

internal val ankiPayloadJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun AnkiMcqPayload.toField(): String = ankiPayloadJson.encodeToString(
    AnkiMcqPayload.serializer(), this
)

/** Parses a third field, or returns null when it is not one of our payloads. */
internal fun payloadFromField(field: String?): AnkiMcqPayload? {
    if (field.isNullOrBlank()) return null
    return try {
        val element = ankiPayloadJson.parseToJsonElement(field) as? JsonObject ?: return null
        // A question may have no options at all, and such a payload must still
        // be honoured, so the test is the shape of the two arrays rather than
        // the options inside them. That still keeps a stray object in a
        // foreign third field from being read as an empty question.
        if (element["options"] !is JsonArray || element["correct"] !is JsonArray) return null
        ankiPayloadJson.decodeFromJsonElement(AnkiMcqPayload.serializer(), element)
    } catch (e: Exception) {
        null
    }
}

internal fun payloadOf(question: com.mcqapp.domain.Question): AnkiMcqPayload =
    AnkiMcqPayload(
        image = question.image,
        options = question.options.map { AnkiMcqOption(it.id, it.text, it.elements, it.image) },
        correct = question.correctOptionIds.toList(),
        explanation = question.explanation,
        explanationElements = question.explanationElements,
        explanationImage = question.explanationImage,
        difficulty = question.difficulty.label.lowercase(),
        marks = question.marks,
        tags = question.tags,
        elements = question.elements
    )