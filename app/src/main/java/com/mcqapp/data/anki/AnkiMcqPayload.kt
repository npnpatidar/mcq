package com.mcqapp.data.anki

import com.mcqapp.data.io.QuestionDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val options: List<AnkiMcqOption> = emptyList(),
    val correct: List<String> = emptyList(),
    val explanation: String = "",
    val explanationImage: String? = null,
    val difficulty: String = "medium",
    val marks: Double = 1.0,
    val tags: List<String> = emptyList()
)

@Serializable
data class AnkiMcqOption(
    val id: String,
    val text: String,
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
        ankiPayloadJson.decodeFromString(AnkiMcqPayload.serializer(), field)
            .takeIf { it.options.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

internal fun payloadOf(question: com.mcqapp.domain.Question): AnkiMcqPayload =
    AnkiMcqPayload(
        options = question.options.map { AnkiMcqOption(it.id, it.text, it.image) },
        correct = question.correctOptionIds.toList(),
        explanation = question.explanation,
        explanationImage = question.explanationImage,
        difficulty = question.difficulty.label.lowercase(),
        marks = question.marks,
        tags = question.tags
    )