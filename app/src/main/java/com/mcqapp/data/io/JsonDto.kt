package com.mcqapp.data.io

import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.ContentElementListJson
import com.mcqapp.domain.textContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class OptionDto(
    val id: String,
    val text: String = "",
    @Serializable(with = ContentElementListJson::class)
    val elements: List<ContentElement> = emptyList(),
    val image: String? = null
)

@Serializable
data class QuestionDto(
    val id: String,
    val text: String = "",
    @SerialName("question_elements")
    @Serializable(with = ContentElementListJson::class)
    val elements: List<ContentElement> = emptyList(),
    val image: String? = null,
    @SerialName("options_elements")
    @Serializable(with = OptionListJson::class)
    val options: List<OptionDto> = emptyList(),
    val correctOptionIds: List<String> = emptyList(),
    val explanation: String = "",
    @SerialName("explanation_elements")
    @Serializable(with = ContentElementListJson::class)
    val explanationElements: List<ContentElement> = emptyList(),
    val explanationImage: String? = null,
    val difficulty: String = "medium",
    val marks: Double = 1.0,
    val tags: List<String> = emptyList(),
    /** The passage (shared reading context) this question belongs to, if any. */
    val passageId: String? = null
)

/**
 * Serializes the options as the structured `options_elements` object the
 * example format uses: `{optionId: [elements]}`. An option's separate image
 * field has no place in that shape, so it is appended as an image element
 * when the elements do not already carry one.
 */
object OptionListJson : KSerializer<List<OptionDto>> {

    private val json = Json { ignoreUnknownKeys = true }

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("OptionList") {
        element("options_elements", String.serializer().descriptor)
    }

    override fun serialize(encoder: Encoder, value: List<OptionDto>) {
        val obj = buildJsonObject {
            value.forEach { option ->
                val elements = option.elements.let { els ->
                    if (option.image != null && els.none { it is ContentElement.ImageElement }) {
                        els + ContentElement.ImageElement(option.image)
                    } else {
                        els
                    }
                }
                put(option.id, json.encodeToJsonElement(ContentElementListJson, elements))
            }
        }
        encoder.encodeSerializableValue(JsonElement.serializer(), obj)
    }

    override fun deserialize(decoder: Decoder): List<OptionDto> {
        val obj = decoder.decodeSerializableValue(JsonElement.serializer()).jsonObject
        return obj.entries.map { (id, el) ->
            val elements = json.decodeFromJsonElement(ContentElementListJson, el.jsonArray)
            OptionDto(id = id, text = elements.textContent, elements = elements)
        }
    }
}

@Serializable
data class CategoryDto(
    val id: String,
    val title: String,
    val parentId: String? = null,
    val questions: List<QuestionDto> = emptyList()
)

@Serializable
data class PaperDto(
    val id: String,
    val title: String,
    val description: String = "",
    val durationMinutes: Int = 0,
    val negativeMarking: Double = 0.0,
    val categories: List<CategoryDto> = emptyList(),
    val questions: List<QuestionDto> = emptyList(),
    /** Reading passages referenced by this paper's questions' passageId. */
    val passages: List<PassageDto> = emptyList()
) {
    fun topLevelQuestions(): List<QuestionDto> = questions
}


/**
 * A reading passage on the wire. Lives alongside a paper's questions; members
 * point at it by [QuestionDto.passageId].
 */
@Serializable
data class PassageDto(
    val id: String,
    val title: String,
    @Serializable(with = ContentElementListJson::class)
    val elements: List<ContentElement> = emptyList(),
    val image: String? = null,
    /** Which category the passage sits in ("Uncategorized" fallback on import). */
    val categoryId: String? = null
)

@Serializable
data class AttemptResultDto(
    val questionId: String,
    val categoryTitle: String = "",
    val text: String = "",
    val optionsJson: String = "[]",
    val correctOptionIds: String = "",
    val selectedOptionIds: String = "",
    val isCorrect: Boolean = false,
    val explanation: String = "",
    val explanationImage: String? = null,
    val dwellSeconds: Long = 0
)

@Serializable
data class AttemptDto(
    val paperId: String,
    val title: String,
    val totalQuestions: Int = 0,
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val skippedCount: Int = 0,
    val score: Double = 0.0,
    val maxScore: Double = 0.0,
    val durationSeconds: Long = 0,
    val finishedAt: Long = 0,
    val results: List<AttemptResultDto> = emptyList()
)

@Serializable
data class McqFileDto(
    val version: Int = 1,
    val papers: List<PaperDto> = emptyList(),
    val bookmarks: List<String> = emptyList(),
    val attempts: List<AttemptDto> = emptyList(),
    /**
     * Row-level diagnostics from parsing a foreign file (malformed papers,
     * categories, or questions that were skipped, not fatal). Empty for files
     * the app itself exported; surfaced on the import preview so a partially
     * dropped import is never silent.
     */
    val warnings: List<String> = emptyList(),
    /**
     * Review progress keyed by question id. Present in files this app
     * exported, so a backup restores schedules rather than silently
     * resetting them. Foreign files omit it and import exactly as before.
     */
    val scheduling: Map<String, CardScheduleDto> = emptyMap(),
    /**
     * Reading passages referenced by `QuestionDto.passageId` across all
     * papers. Written by this app's exports; foreign files have none and
     * import exactly as before.
     */
    val passages: List<PassageDto> = emptyList()
)

/**
 * A card's review schedule.
 *
 * Deliberately not part of [QuestionDto]: a question is content, while how far
 * that content has been learned is per-device progress. It travels in
 * [McqFileDto.scheduling] as a questionId-keyed map — which is also the shape
 * the Anki importer passes alongside the file — so one restore path serves both
 * JSON backups and `.apkg` files.
 *
 * Field names match `card_state` so the importer is a copy rather than a
 * translation. Milliseconds, days, and Anki's own ease scale (1.3-3.0).
 */
@Serializable
data class CardScheduleDto(
    val ease: Double = 2.5,
    val intervalDays: Int = 0,
    val dueAt: Long = 0L,
    val reps: Int = 0,
    val lapses: Int = 0,
    val leech: Boolean = false,
    val lastReviewedAt: Long = 0L
)
