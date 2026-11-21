package com.mcqapp.data.io

import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.textContent
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * Single source of truth for duplicate detection. Both the import preview
 * (ImportViewModel) and the actual import (Importer) must agree on what
 * counts as a duplicate, so they share this hash: question text + option
 * texts + option images + passage id. Correct answers / explanations do not
 * affect it. A flashcard's identity is its prompt too — one prompt = one
 * answer, so a changed back is the *same* question with answer content that
 * [nonHashedFieldsDiffer] (and [CardContent]) compares, not a second card.
 *
 * [passageId] deliberately changed the signature (v9): a question that moved
 * between passages must read as edited content to the study layer, which
 * resets its card instead of keeping an interval earned on other context.
 * The passage TEXT never enters the hash — editing a passage is not editing
 * any of its members' content.
 */
object ContentHash {

    private val json = Json { ignoreUnknownKeys = true }

    fun of(
        text: String,
        optionTexts: List<String>,
        optionImages: List<String?>,
        passageId: String? = null
    ): String {
        val raw = text + "|" + optionTexts.joinToString(",") + "|" +
            optionImages.joinToString(",") + "|" + (passageId ?: "")
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * An option's picture, wherever it sits: the structured `options_elements`
     * payload shape has no separate image field, so the writer folds the
     * picture in as a trailing image element, while the database and the
     * editor shapes keep it in the scalar. Both render identically, so
     * duplicate detection reads them positionally — element srcs first, then
     * the scalar. Without this, an option whose picture moved between the
     * two shapes on an export → re-import cycle hashes differently and the
     * question reports as updated forever.
     */
    private fun optionImageSrcs(elements: List<ContentElement>, scalar: String?): List<String> =
        elements.filterIsInstance<ContentElement.ImageElement>().map { it.src } +
            listOfNotNull(scalar)

    /**
     * One option's picture as the hash sees it: [optionImageSrcs] joined by
     * commas, or the literal "null" when there is no picture — exactly what a
     * raw `List<String?>` rendered (Kotlin spells a null element "null"), so
     * hashes stored by earlier versions still match.
     */
    private fun optionImageValue(elements: List<ContentElement>, scalar: String?): String {
        val srcs = optionImageSrcs(elements, scalar)
        return if (srcs.isEmpty()) "null" else srcs.joinToString(",")
    }

    /**
     * The text a question or an option is *stored* with: the elements JSON when
     * there are elements, otherwise one text element holding the flat string —
     * exactly what the importer and [QuestionStore.saveQuestion] write. Reading
     * a row back yields `textContent` of that, so deriving the hash the same
     * way keeps a file DTO and a loaded question on one value.
     */
    private fun storedText(elements: List<ContentElement>, flat: String): String =
        elements.ifEmpty { listOf(ContentElement.TextElement(flat)) }.textContent

    /**
     * A flashcard's back as the answer comparison sees it: the structured
     * explanation blocks, with the flat text field standing in when a file
     * carries only that. Blank shapes are normalized (`[]` and one empty text
     * block are the same back), mirroring [CardContent].
     */
    private fun backElements(elements: List<ContentElement>, flat: String): List<ContentElement> =
        elements.ifEmpty { listOf(ContentElement.TextElement(flat)) }.orBlankElement()

    private fun List<ContentElement>.orBlankElement(): List<ContentElement> =
        ifEmpty { listOf(ContentElement.TextElement("")) }

    fun of(dto: QuestionDto): String =
        of(
            storedText(dto.elements, dto.text),
            dto.options.map { storedText(it.elements, it.text) },
            dto.options.map { optionImageValue(it.elements, it.image) },
            dto.passageId
        )

    /**
     * The same hash for a *stored* question, read back as a [Question].
     *
     * The importer and the import preview hash a file's [QuestionDto], while
     * the study layer compares that stored hash against the question it loads:
     * a difference means "the content was edited, so reset the card". Hashing
     * only the scalar image made the two disagree for every option whose
     * picture the importer had folded into `options_elements`, so such a card
     * was reset as edited and re-served on every pull — the review counter
     * climbed while the same question stayed on screen. Both shapes must hash
     * to one value, so both go through [optionImageValue].
     */
    fun of(question: Question): String =
        of(
            question.text,
            question.options.map { it.text },
            question.options.map { optionImageValue(it.elements, it.image) },
            question.passageId
        )

    /**
     * Whether [dto] is *genuinely* the same question as the stored row and its
     * options, judged on the content the hash cannot see.
     *
     * The hash is built from text content, and `textContent` counts only
     * [ContentElement.TextElement]s — so a table or a formula contributes
     * nothing. Two different questions that differ *only* in a table option
     * therefore share a hash, and treating a hash hit as a duplicate silently
     * dropped the second one. The hash stays as the cheap pre-filter; this is
     * the authority.
     *
     * [dto] must be the *scaled* twin the importer stores (see
     * `QuestionDto.withDownscaledImages`): the row holds the downscaled bytes,
     * so comparing them against the file's original ones reads as different
     * for any oversized picture.
     */
    fun sameQuestionContent(
        stored: QuestionEntity,
        storedOptions: List<OptionEntity>,
        dto: QuestionDto
    ): Boolean {
        val storedElements = stored.text.parseContentElements(json)
        val incomingElements = dto.elements.ifEmpty {
            listOf(ContentElement.TextElement(dto.text))
        }
        if (storedElements != incomingElements) return false
        if (stored.image != dto.image) return false
        if (storedOptions.size != dto.options.size) return false
        val optionsMatch = storedOptions.zip(dto.options).all { (option, incoming) ->
            val storedOptionElements = option.text.parseContentElements(json)
            val incomingOptionElements = incoming.elements.ifEmpty {
                listOf(ContentElement.TextElement(incoming.text))
            }
            optionImageSrcs(storedOptionElements, option.image) ==
                optionImageSrcs(incomingOptionElements, incoming.image) &&
                storedOptionElements.filterNot { it is ContentElement.ImageElement } ==
                    incomingOptionElements.filterNot { it is ContentElement.ImageElement }
        }
        // The back is deliberately absent: one prompt = one answer. A
        // flashcard re-imported with a different back is the same question;
        // the caller compares that answer content through
        // [nonHashedFieldsDiffer] and refreshes it in place.
        return optionsMatch
    }

    /**
     * The same comparison between two incoming questions, used for a question
     * inserted earlier in the same import: its content is the DTO that was
     * written, so there is no row to read back.
     */
    fun sameQuestionContent(a: QuestionDto, b: QuestionDto): Boolean {
        // Normalise the flat side first, exactly as the stored-row variant
        // does. Comparing elements only when *both* sides have them made the
        // two checks disagree: a flat question and a rich one carrying the same
        // visible text were "the same" here even when the rich one also held a
        // table or formula.
        val aElements = a.elements.ifEmpty { listOf(ContentElement.TextElement(a.text)) }
        val bElements = b.elements.ifEmpty { listOf(ContentElement.TextElement(b.text)) }
        if (aElements != bElements) return false
        if (a.image != b.image) return false
        if (a.options.size != b.options.size) return false
        val optionsMatch = a.options.zip(b.options).all { (first, second) ->
            val firstElements = first.elements.ifEmpty {
                listOf(ContentElement.TextElement(first.text))
            }
            val secondElements = second.elements.ifEmpty {
                listOf(ContentElement.TextElement(second.text))
            }
            optionImageSrcs(firstElements, first.image) ==
                optionImageSrcs(secondElements, second.image) &&
                firstElements.filterNot { it is ContentElement.ImageElement } ==
                    secondElements.filterNot { it is ContentElement.ImageElement }
        }
        // Same prompt is the same question even when the back differs: the
        // changed answer is refreshed in place, never inserted as a twin.
        return optionsMatch
    }

    /**
     * Whether a re-imported question with a matching content hash still needs
     * an update: the hash deliberately ignores the answer key, question image,
     * explanation, marks, difficulty and tags, so a file that fixes only those
     * is an update to the same question id, not a duplicate to skip.
     *
     * [dto] must be the *scaled* twin the importer stores (see
     * `QuestionDto.withDownscaledImages`): the row's `image`/`explanationImage`
     * hold the downscaled bytes, so comparing them against the file's original
     * bytes reads as a permanent difference for any oversized picture.
     *
     * The stored explanation is elements JSON while the DTO carries plain text
     * (or elements), so both are compared as their text content. For a
     * flashcard the explanation *is* the back, so it is additionally compared
     * as structured blocks: a table, formula or picture-only back change is an
     * answer update even though its flattened text is unchanged.
     */
    fun nonHashedFieldsDiffer(
        stored: QuestionEntity,
        storedCorrectIds: Set<String>,
        dto: QuestionDto
    ): Boolean = nonHashedFieldsDiffer(stored, dto) {
        storedCorrectIds != dto.correctOptionIds.toSet()
    }

    /**
     * The duplicate-refresh variant: the key is compared by the *positions*
     * marked correct, not by raw ids. The refresh rewrites only the key and
     * leaves the stored option rows (and their ids) in place, while a
     * regenerated bank re-mints those ids. Comparing raw ids would both
     * mistake an unchanged key for a correction and lose which stored option
     * the corrected key refers to. Positions are the same rule
     * [CardContent.of] fingerprints.
     */
    fun nonHashedFieldsDiffer(
        stored: QuestionEntity,
        storedOptions: List<OptionEntity>,
        storedCorrectIds: Set<String>,
        dto: QuestionDto
    ): Boolean = nonHashedFieldsDiffer(stored, dto) {
        correctPositions(storedOptions.map { it.id }, storedCorrectIds) != correctPositions(dto)
    }

    private fun nonHashedFieldsDiffer(
        stored: QuestionEntity,
        dto: QuestionDto,
        correctKeyDiffers: () -> Boolean
    ): Boolean {
        val storedExplanationElements = stored.explanation.parseContentElements(json)
        val dtoExplanationElements = dto.explanationElements.ifEmpty {
            listOf(ContentElement.TextElement(dto.explanation))
        }
        val storedExplanation = storedExplanationElements.textContent
        val dtoExplanation = dtoExplanationElements.textContent
        val flashcardBackDiffers = dto.options.isEmpty() &&
            backElements(storedExplanationElements, "") !=
            backElements(dto.explanationElements, dto.explanation)
        return stored.image != dto.image ||
            storedExplanation != dtoExplanation ||
            flashcardBackDiffers ||
            stored.explanationImage != dto.explanationImage ||
            stored.marks != dto.marks ||
            // The same difficulty can be spelled differently: a bookmark or
            // selection export writes the label ("Medium"), a whole-bank export
            // the stored value ("medium"), and Difficulty.fromLabel reads both
            // as one. Casing alone is not a content change.
            !stored.difficulty.equals(dto.difficulty, ignoreCase = true) ||
            stored.tags != dto.tags.joinToString(",") ||
            correctKeyDiffers()
    }

    /** Which positions in [optionIds] the key marks correct. */
    fun correctPositions(optionIds: List<String>, correctIds: Set<String>): Set<Int> =
        optionIds.withIndex()
            .filter { (_, id) -> id in correctIds }
            .mapTo(mutableSetOf<Int>()) { it.index }

    /** The positions [dto]'s key marks, judged against [dto]'s own options. */
    fun correctPositions(dto: QuestionDto): Set<Int> =
        correctPositions(dto.options.map { it.id }, dto.correctOptionIds.toSet())

    /**
     * The incoming correct-option ids translated onto the ids of
     * [storedOptions] by position — the same positional order
     * [sameQuestionContent] matched. The refresh keeps the stored option rows
     * but rewrites the key; writing the file's ids straight through leaves the
     * key pointing at options that do not exist, so the question imports
     * cleanly and then never grades.
     */
    fun remapCorrectIdsOntoStored(
        storedOptions: List<OptionEntity>,
        dto: QuestionDto
    ): List<String> {
        val positions = correctPositions(dto)
        return storedOptions.withIndex()
            .filter { (index, _) -> index in positions }
            .map { (_, option) -> option.id }
    }
}
