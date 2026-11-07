package com.mcqapp.data.io

import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PassageEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Passage
import com.mcqapp.domain.Question
import com.mcqapp.domain.parseContentElements
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * The full content a card was learned from, as one comparable fingerprint.
 *
 * [ContentHash] answers "is this the same question?" for duplicate detection
 * and deliberately ignores the answer key, question images, tables and
 * formulas. This answers a different question — "did anything the learner
 * studied change?" — and therefore includes every block (text, image, table,
 * math), both scalar images, the option order and images, the answer key, and
 * the passage assignment.
 *
 * Explanation, marks, difficulty, tags and sort order are deliberately
 * absent: changing those must keep the interval the card earned. A passage's
 * own body is not folded in here either; [passageChanged] compares it once
 * and resets the members, so one passage edit reads as "its context changed"
 * rather than "every member's text changed".
 *
 * The fingerprint is compared at write time by the repository and the two
 * importers. There is no stored column and no backfill: from the build that
 * introduced this rule onward, every covered change is detected when it
 * happens, and the old [ContentHash] mismatch stays as the backstop for rows
 * that predate it.
 */
object CardContent {

    private val json = Json { ignoreUnknownKeys = true }

    /** Record separator between blocks; unit separator inside one block. */
    private const val BLOCK = '\u001e'
    private const val FIELD = '\u001f'
    private const val OPTION = '\u001d'

    fun of(question: Question): String = fingerprint(
        elements = question.elements.ifEmpty { listOf(ContentElement.TextElement("")) },
        image = question.image,
        options = question.options.map { option(it.elements, it.image) },
        optionIds = question.options.map { it.id },
        correctIds = question.correctOptionIds,
        passageId = question.passageId
    )

    fun of(dto: QuestionDto): String = fingerprint(
        elements = dto.elements.ifEmpty { listOf(ContentElement.TextElement(dto.text)) },
        image = dto.image,
        options = dto.options.map {
            option(it.elements.ifEmpty { listOf(ContentElement.TextElement(it.text)) }, it.image)
        },
        optionIds = dto.options.map { it.id },
        correctIds = dto.correctOptionIds.toSet(),
        passageId = dto.passageId
    )

    fun of(
        question: QuestionEntity,
        options: List<OptionEntity>,
        correctIds: Set<String>
    ): String = fingerprint(
        elements = question.text.parseContentElements(json).orBlankElement(),
        image = question.image,
        options = options.map { option(it.text.parseContentElements(json).orBlankElement(), it.image) },
        optionIds = options.map { it.id },
        correctIds = correctIds,
        passageId = question.passageId
    )

    /** A passage's body, image and title as its members studied them. */
    fun of(passage: PassageEntity): String = passageFingerprint(
        title = passage.title,
        elements = passage.text.parseContentElements(json).orBlankElement(),
        image = passage.image
    )

    fun of(passage: Passage): String = passageFingerprint(
        title = passage.title,
        elements = passage.elements.orBlankElement(),
        image = passage.image
    )

    fun of(dto: PassageDto): String = passageFingerprint(
        title = dto.title,
        elements = dto.elements.orBlankElement(),
        image = dto.image
    )

    fun passageChanged(stored: PassageEntity, incoming: PassageDto): Boolean =
        of(stored) != of(incoming)

    fun passageChanged(stored: PassageEntity, incoming: PassageEntity): Boolean =
        of(stored) != of(incoming)

    private fun fingerprint(
        elements: List<ContentElement>,
        image: String?,
        options: List<String>,
        optionIds: List<String>,
        correctIds: Set<String>,
        passageId: String?
    ): String {
        val raw = buildString {
            append("q").append(FIELD)
            append(canonical(elements.orBlankElement())).append(FIELD)
            append(image ?: "").append(FIELD)
            options.forEach { append(it).append(OPTION) }
            append(FIELD)
            // Which positions are correct, not which option ids: an exporter
            // that re-mints ids for identical options must not look edited.
            correctIds.let { ids ->
                optionIds.withIndex()
                    .filter { (_, id) -> id in ids }
                    .joinTo(this, ",") { (index, _) -> index.toString() }
            }
            append(FIELD)
            append(passageId ?: "")
        }
        return sha256(raw)
    }

    private fun passageFingerprint(
        title: String,
        elements: List<ContentElement>,
        image: String?
    ): String = sha256(
        "p" + FIELD + title + FIELD + canonical(elements.orBlankElement()) + FIELD + (image ?: "")
    )

    /**
     * One option: its non-image blocks in order, then its picture sources.
     *
     * The image split mirrors [ContentHash]: an export folds an option's
     * scalar picture into a trailing image element, while the database and
     * the editor keep it in the scalar. Both render identically, so both must
     * fingerprint identically.
     */
    private fun option(elements: List<ContentElement>, image: String?): String {
        val normalized = elements.orBlankElement()
        val text = canonical(normalized.filterNot { it is ContentElement.ImageElement })
        val images = normalized.filterIsInstance<ContentElement.ImageElement>().map { it.src } +
            listOfNotNull(image)
        return text + FIELD + images.joinToString(FIELD.toString())
    }

    private fun canonical(elements: List<ContentElement>): String =
        elements.joinToString(BLOCK.toString()) { element ->
            when (element) {
                is ContentElement.TextElement -> "t" + element.text
                is ContentElement.ImageElement -> "i" + element.src
                is ContentElement.TableElement ->
                    "b" + element.rows.joinToString(BLOCK.toString()) { row ->
                        row.joinToString(FIELD.toString())
                    }
                is ContentElement.MathElement -> "m" + element.mathml
            }
        }

    /**
     * An empty body and one blank text block are the same content: the editor
     * stores `[]`, while the import writer normalizes an empty DTO to one
     * blank element. Without this, exporting a body-less passage and importing
     * it back would read as an edit and reset every member.
     */
    private fun List<ContentElement>.orBlankElement(): List<ContentElement> =
        ifEmpty { listOf(ContentElement.TextElement("")) }

    private fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
