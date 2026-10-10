package com.mcqapp.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One block of a question's content: plain text, an image, a table, or a
 * MathML formula. A question, option, or explanation is a list of these, so
 * rich content (a table inside a question, a formula inside an option)
 * round-trips through import, storage, and export.
 */
sealed interface ContentElement {
    /** Discriminator for the flat `{"type": ..., "content": ...}` wire format. */
    val type: String

    data class TextElement(val text: String) : ContentElement {
        override val type: String get() = "text"
    }

    data class ImageElement(val src: String) : ContentElement {
        override val type: String get() = "image"
    }

    data class TableElement(val rows: List<List<String>>) : ContentElement {
        override val type: String get() = "table"
    }

    data class MathElement(val mathml: String) : ContentElement {
        override val type: String get() = "math"
    }
}

/**
 * Serializes a [ContentElement] as the flat `{"type": ..., "content": ...}`
 * shape used by the structured import format and the database columns (the
 * default polymorphic shape would nest each subclass's fields instead).
 */
object ContentElementJson : KSerializer<ContentElement> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ContentElement") {
        element("type", String.serializer().descriptor)
        element("content", JsonElement.serializer().descriptor)
    }

    override fun serialize(encoder: Encoder, value: ContentElement) {
        val content = when (value) {
            is ContentElement.TextElement -> JsonPrimitive(value.text)
            is ContentElement.ImageElement -> JsonPrimitive(value.src)
            is ContentElement.TableElement -> buildJsonArray {
                value.rows.forEach { row ->
                    add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
                }
            }
            is ContentElement.MathElement -> JsonPrimitive(value.mathml)
        }
        encoder.encodeSerializableValue(JsonElement.serializer(), buildJsonObject {
            put("type", JsonPrimitive(value.type))
            put("content", content)
        })
    }

    override fun deserialize(decoder: Decoder): ContentElement {
        val obj = decoder.decodeSerializableValue(JsonElement.serializer()).jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
            ?: throw SerializationException("Content element without a type")
        val content = obj["content"] ?: throw SerializationException("Content element without content")
        return when (type) {
            "text" -> ContentElement.TextElement(content.jsonPrimitive.content)
            "image" -> ContentElement.ImageElement(content.jsonPrimitive.content)
            "table" -> ContentElement.TableElement(
                content.jsonArray.map { row -> row.jsonArray.map { cell -> cell.jsonPrimitive.content } }
            )
            "math" -> ContentElement.MathElement(content.jsonPrimitive.content)
            else -> throw SerializationException("Unknown content element type: $type")
        }
    }
}

/** [ContentElementJson] for a list, so DTO properties can use `@Serializable(with = ...)`. */
object ContentElementListJson : KSerializer<List<ContentElement>> {

    private val delegate = ListSerializer(ContentElementJson)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<ContentElement>) =
        delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<ContentElement> =
        delegate.deserialize(decoder)
}

/** Concatenated text of the [ContentElement.TextElement]s — the backward-compat
 *  `text` used by scoring, stats, and search. */
val List<ContentElement>.textContent: String
    get() = filterIsInstance<ContentElement.TextElement>().joinToString("") { it.text }

/**
 * Everything a reader can actually see, flattened for searching.
 *
 * [textContent] is the wrong basis for search: it drops tables and formulas
 * entirely, so a question whose only mention of a name sits in a table cell or
 * inside a formula could not be found at all. It also keeps inline markup, so
 * searching "strong" used to match questions merely containing a `<strong>`
 * tag. Deliberately separate from [textContent], which duplicate detection and
 * the explanation comparison both rely on.
 *
 * A picture contributes nothing: there is no text to match.
 */
val List<ContentElement>.searchableText: String
    get() = mapNotNull { element ->
        when (element) {
            is ContentElement.TextElement -> stripMarkup(element.text)
            is ContentElement.TableElement ->
                element.rows.flatten().joinToString(" ") { stripMarkup(it) }
            // No space between tokens, so a formula reads the way it is typed:
            // stripping tags with a space would give "v = u + a t", which a
            // search for "v=u+at" would miss.
            is ContentElement.MathElement -> element.mathml.replace(TAG_OR_ENTITY, "")
            is ContentElement.ImageElement -> null
        }
    }.joinToString(" ").replace(WHITESPACE_RUNS, " ").trim()

private val WHITESPACE_RUNS = Regex("\\s+")

/** Drops tags and entities, leaving the text between them. */
private fun stripMarkup(raw: String): String = if ('<' in raw || '&' in raw) {
    TAG_OR_ENTITY.replace(raw, " ")
} else {
    raw
}

private val TAG_OR_ENTITY = Regex("<[^>]*>|&[a-zA-Z#0-9]+;")

/** Serializes elements to the flat JSON stored in the text/explanation columns. */
fun List<ContentElement>.toContentJson(json: Json): String =
    json.encodeToString(ContentElementListJson, this)

/**
 * Parses a column value back into elements. Legacy rows hold plain text, not
 * JSON, so anything that is not a JSON array becomes a single TextElement.
 */
fun String.parseContentElements(json: Json): List<ContentElement> {
    val trimmed = trim()
    if (!trimmed.startsWith("[")) return listOf(ContentElement.TextElement(this))
    return try {
        json.decodeFromString(ContentElementListJson, trimmed)
    } catch (_: Exception) {
        listOf(ContentElement.TextElement(this))
    }
}

data class QuestionOption(
    val id: String,
    val elements: List<ContentElement>,
    val image: String? = null
) {
    /** Backward-compat: the concatenated text. */
    val text: String get() = elements.textContent

    /** Backward-compat constructor: plain text becomes one TextElement. */
    constructor(id: String, text: String, image: String? = null) : this(
        id,
        listOf(ContentElement.TextElement(text)),
        image
    )
}

enum class Difficulty(val label: String) {
    EASY("Easy"),
    MEDIUM("Medium"),
    HARD("Hard");

    companion object {
        fun fromLabel(label: String): Difficulty =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: MEDIUM
    }
}

data class Question(
    val id: String,
    val categoryId: String,
    val elements: List<ContentElement>,
    val image: String? = null,
    val options: List<QuestionOption>,
    val correctOptionIds: Set<String>,
    val explanationElements: List<ContentElement> = emptyList(),
    val explanationImage: String? = null,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val marks: Double = 1.0,
    val tags: List<String> = emptyList(),
    /** The passage (shared reading context) this question belongs to, if any. */
    val passageId: String? = null
) {
    val isMultiCorrect: Boolean get() = correctOptionIds.size > 1

    /** Backward-compat: the concatenated text, for scoring/stats/search. */
    val text: String get() = elements.textContent

    /** Backward-compat: the concatenated explanation text. */
    val explanation: String get() = explanationElements.textContent

    /** Backward-compat constructor: plain text becomes one TextElement. */
    constructor(
        id: String,
        categoryId: String,
        text: String,
        image: String? = null,
        options: List<QuestionOption>,
        correctOptionIds: Set<String>,
        explanation: String = "",
        explanationImage: String? = null,
        difficulty: Difficulty = Difficulty.MEDIUM,
        marks: Double = 1.0,
        tags: List<String> = emptyList()
    ) : this(
        id,
        categoryId,
        listOf(ContentElement.TextElement(text)),
        image,
        options,
        correctOptionIds,
        listOf(ContentElement.TextElement(explanation)),
        explanationImage,
        difficulty,
        marks,
        tags,
        passageId = null
    )
}

/**
 * A reading passage: shared context for a group of questions.
 *
 * Members live as ordinary [Question]s pointing at this id through
 * `passageId`; the passage itself only carries the context (title, body,
 * image) and its category, which gives it a home in the paper structure.
 *
 * Scheduling, bookmarks, attempts and stats stay per question — the passage
 * is not schedulable content, only what surrounds the question on screen.
 */
data class Passage(
    val id: String,
    val categoryId: String,
    val title: String,
    val elements: List<ContentElement> = emptyList(),
    val image: String? = null,
    val sortOrder: Int = 0,
    /** member count filled in by the repository read, 0 when not requested. */
    val memberCount: Int = 0
) {
    /** Backward-compat concatenated body text. */
    val text: String get() = elements.textContent
}

data class CategoryNode(
    val id: String,
    val paperId: String,
    val title: String,
    val parentId: String?,
    val children: List<CategoryNode> = emptyList(),
    val questionCount: Int = 0
) {
    val totalQuestionCount: Int get() = questionCount + children.sumOf { it.totalQuestionCount }
}

data class Paper(
    val id: String,
    val title: String,
    val description: String = "",
    val durationMinutes: Int = 0,
    val negativeMarking: Double = 0.0,
    val categories: List<CategoryNode> = emptyList()
) {
    val totalQuestions: Int get() = categories.sumOf { it.totalQuestionCount }
}

data class Attempt(
    val id: Long,
    val paperId: String,
    val title: String,
    val totalQuestions: Int,
    val correctCount: Int,
    val wrongCount: Int,
    val skippedCount: Int,
    val score: Double,
    val maxScore: Double,
    val durationSeconds: Long,
    val finishedAt: Long
) {
    val percentage: Double get() = if (maxScore > 0) score / maxScore * 100.0 else 0.0
}

data class QuestionResult(
    val attemptId: Long = 0,
    val dwellSeconds: Long = 0,
    val questionId: String,
    val categoryTitle: String,
    val elements: List<ContentElement>,
    val options: List<QuestionOption>,
    val correctOptionIds: Set<String>,
    val selectedOptionIds: Set<String>,
    val isCorrect: Boolean,
    val explanationElements: List<ContentElement> = emptyList(),
    val explanationImage: String? = null
) {
    /** Backward-compat: the concatenated text. */
    val text: String get() = elements.textContent

    /** Backward-compat: the concatenated explanation text. */
    val explanation: String get() = explanationElements.textContent

    /** Backward-compat constructor: plain text becomes one TextElement. */
    constructor(
        attemptId: Long = 0,
        dwellSeconds: Long = 0,
        questionId: String,
        categoryTitle: String,
        text: String,
        options: List<QuestionOption>,
        correctOptionIds: Set<String>,
        selectedOptionIds: Set<String>,
        isCorrect: Boolean,
        explanation: String = "",
        explanationImage: String? = null
    ) : this(
        attemptId,
        dwellSeconds,
        questionId,
        categoryTitle,
        listOf(ContentElement.TextElement(text)),
        options,
        correctOptionIds,
        selectedOptionIds,
        isCorrect,
        listOf(ContentElement.TextElement(explanation)),
        explanationImage
    )
}

/**
 * A bookmarked question together with the paper it came from.
 *
 * The editor route needs the paper id: without it the editor loads no
 * categories and silently hides the category picker.
 */
data class BookmarkedQuestion(val paperId: String, val question: Question)
