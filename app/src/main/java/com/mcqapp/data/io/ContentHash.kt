package com.mcqapp.data.io

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
}
