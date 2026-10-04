package com.mcqapp.data.io

import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.textContent
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * Single source of truth for duplicate detection. Both the import preview
 * (ImportViewModel) and the actual import (Importer) must agree on what
 * counts as a duplicate, so they share this hash: question text + option
 * texts + option images. Correct answers / explanations do not affect it.
 */
object ContentHash {

    private val json = Json { ignoreUnknownKeys = true }

    fun of(text: String, optionTexts: List<String>, optionImages: List<String?>): String {
        val raw = text + "|" + optionTexts.joinToString(",") + "|" + optionImages.joinToString(",")
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun of(dto: QuestionDto): String =
        of(dto.text, dto.options.map { it.text }, dto.options.map { it.image })

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
        return storedOptions.zip(dto.options).all { (option, incoming) ->
            option.image == incoming.image &&
                option.text.parseContentElements(json) == incoming.elements.ifEmpty {
                    listOf(ContentElement.TextElement(incoming.text))
                }
        }
    }

    /**
     * The same comparison between two incoming questions, used for a question
     * inserted earlier in the same import: its content is the DTO that was
     * written, so there is no row to read back.
     */
    fun sameQuestionContent(a: QuestionDto, b: QuestionDto): Boolean {
        if (a.elements.isNotEmpty() && b.elements.isNotEmpty() && a.elements != b.elements) return false
        if (a.image != b.image) return false
        if (a.options.size != b.options.size) return false
        return a.options.zip(b.options).all { (first, second) ->
            first.image == second.image &&
                (first.elements.isEmpty() || second.elements.isEmpty() || first.elements == second.elements)
        }
    }

    /**
     * Whether a re-imported question with a matching content hash still needs
     * an update: the hash deliberately ignores the answer key, question image,
     * explanation, marks, difficulty and tags, so a file that fixes only those
     * is an update to the same question id, not a duplicate to skip.
     *
     * The stored explanation is elements JSON while the DTO carries plain text
     * (or elements), so both are compared as their text content.
     */
    fun nonHashedFieldsDiffer(
        stored: QuestionEntity,
        storedCorrectIds: Set<String>,
        dto: QuestionDto
    ): Boolean {
        val storedExplanation = stored.explanation.parseContentElements(json).textContent
        val dtoExplanation = dto.explanationElements.ifEmpty {
            listOf(ContentElement.TextElement(dto.explanation))
        }.textContent
        return stored.image != dto.image ||
            storedExplanation != dtoExplanation ||
            stored.explanationImage != dto.explanationImage ||
            stored.marks != dto.marks ||
            stored.difficulty != dto.difficulty ||
            stored.tags != dto.tags.joinToString(",") ||
            storedCorrectIds != dto.correctOptionIds.toSet()
    }
}