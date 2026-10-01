package com.mcqapp.data.io

import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.parseContentElements
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