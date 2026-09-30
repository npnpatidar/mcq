package com.mcqapp.data.io

import com.mcqapp.data.local.QuestionEntity
import java.security.MessageDigest

/**
 * Single source of truth for duplicate detection. Both the import preview
 * (ImportViewModel) and the actual import (Importer) must agree on what
 * counts as a duplicate, so they share this hash: question text + option
 * texts + option images. Correct answers / explanations do not affect it.
 */
object ContentHash {
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
     */
    fun nonHashedFieldsDiffer(
        stored: QuestionEntity,
        storedCorrectIds: Set<String>,
        dto: QuestionDto
    ): Boolean =
        stored.image != dto.image ||
            stored.explanation != dto.explanation ||
            stored.explanationImage != dto.explanationImage ||
            stored.marks != dto.marks ||
            stored.difficulty != dto.difficulty ||
            stored.tags != dto.tags.joinToString(",") ||
            storedCorrectIds != dto.correctOptionIds.toSet()
}
