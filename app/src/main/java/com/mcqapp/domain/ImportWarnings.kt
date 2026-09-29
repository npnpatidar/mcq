package com.mcqapp.domain

import com.mcqapp.data.io.QuestionDto

/**
 * Advisory import validation: flags questions that import cleanly but will
 * misbehave later (ungraded, unanswerable, bloated). Pure over DTOs so the
 * preview can recompute live as rows are edited, and tests pin every rule.
 */
object ImportWarnings {

    data class Warning(
        val questionId: String,
        val label: String,
        val message: String
    )

    /** Image payloads above this (chars ≈ bytes for base64) slow imports down. */
    const val LARGE_IMAGE_CHARS = 256 * 1024

    fun forFile(questions: List<QuestionDto>): List<Warning> =
        questions.flatMapIndexed { index, q -> forQuestion(q, "Q${index + 1}") }

    fun forQuestion(q: QuestionDto, label: String): List<Warning> = buildList {
        if (q.text.isBlank()) {
            add(Warning(q.id, label, "Blank text — imports with empty text"))
        }
        if (q.options.isEmpty()) {
            add(Warning(q.id, label, "No options — cannot be answered"))
        } else if (q.options.size == 1) {
            add(Warning(q.id, label, "Only one option"))
        }
        if (q.correctOptionIds.isEmpty()) {
            add(Warning(q.id, label, "No answer key — will be ungraded, never scored"))
        } else {
            val optionIds = q.options.map { it.id }.toSet()
            val dangling = q.correctOptionIds.filter { it !in optionIds }
            if (dangling.isNotEmpty()) {
                add(Warning(q.id, label, "Answer key references missing options: ${dangling.joinToString(",")}"))
            }
        }
        if (q.marks == 0.0) {
            add(Warning(q.id, label, "Worth 0 marks — never affects the score"))
        }
        val imageChars = (q.image?.length ?: 0) +
            q.options.sumOf { it.image?.length ?: 0 } +
            (q.explanationImage?.length ?: 0)
        if (imageChars > LARGE_IMAGE_CHARS) {
            add(Warning(q.id, label, "Large images (~${imageChars / 1024} KB) — slows import"))
        }
    }
}
