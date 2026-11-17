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

/**
 * Whether a parse/log warning reports content that was actually dropped, or
 * is only advisory. This is data produced alongside the message, not something
 * inferred from the message's wording: rewording a message must never move it
 * between the two panels.
 */
enum class ImportWarningSeverity { DROPPED, ADVISORY }

/** A warning plus the severity its producer assigned it. */
data class ParserWarning(val message: String, val severity: ImportWarningSeverity)

/**
 * Parser warnings split by whether anything was actually dropped.
 *
 * They used to share one panel headed "Skipped rows", in error red, right
 * beside a second red panel saying the import was fine. Most of these are
 * advisory — an unreadable answer key, a root that is not a paper file — so
 * the heading told the user content had been lost when it had not.
 */
data class ImportWarningGroups(
    val skipped: List<String>,
    val notes: List<String>
) {
    val isEmpty: Boolean get() = skipped.isEmpty() && notes.isEmpty()
}

/** The authoritative split: severity decides, never the message text. */
fun splitTypedImportWarnings(warnings: List<ParserWarning>): ImportWarningGroups =
    ImportWarningGroups(
        skipped = warnings.filter { it.severity == ImportWarningSeverity.DROPPED }
            .map { it.message },
        notes = warnings.filter { it.severity == ImportWarningSeverity.ADVISORY }
            .map { it.message }
    )

/**
 * Legacy adapter for callers that still hand us bare strings. The parsers'
 * current drop messages announce themselves with the word "skipped"; new
 * producers should call [splitTypedImportWarnings] with an explicit severity.
 */
fun splitImportWarnings(warnings: List<String>): ImportWarningGroups =
    splitTypedImportWarnings(warnings.map { ParserWarning(it, severityOfParserWarning(it)) })

/** Severity for the legacy bare-string warnings emitted by the parsers. */
fun severityOfParserWarning(message: String): ImportWarningSeverity =
    if (message.contains("skipped", ignoreCase = true)) {
        ImportWarningSeverity.DROPPED
    } else {
        ImportWarningSeverity.ADVISORY
    }
