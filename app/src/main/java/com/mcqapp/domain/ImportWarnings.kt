package com.mcqapp.domain

import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.normalizedFlashcard

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
        // A one-option question is converted to a flashcard before it is
        // written, so every question is judged in the shape the import stores:
        // options present means a multiple-choice card, options absent means
        // the back is what the learner will see on reveal.
        val stored = q.normalizedFlashcard()
        if (stored.options.isEmpty()) {
            if (!stored.hasBack()) {
                add(Warning(q.id, label, "No answer yet — will show nothing on reveal"))
            }
        } else {
            if (stored.correctOptionIds.isEmpty()) {
                add(Warning(q.id, label, "No answer key — will be ungraded, never scored"))
            } else {
                val optionIds = stored.options.map { it.id }.toSet()
                val dangling = stored.correctOptionIds.filter { it !in optionIds }
                if (dangling.isNotEmpty()) {
                    add(Warning(q.id, label, "Answer key references missing options: ${dangling.joinToString(",")}"))
                }
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

    /**
     * Whether a flashcard will show anything on reveal: a picture, a table, a
     * formula or real prose. A blank text run is not a back.
     */
    private fun QuestionDto.hasBack(): Boolean =
        !explanationImage.isNullOrBlank() ||
            explanationElements.hasVisibleContent ||
            explanation.isNotBlank()
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
 * Legacy adapter for hand-written bare strings (tests, old callers). It is the
 * only remaining consumer of the deprecated wording classifier; the live import
 * path uses [splitTypedImportWarnings] with producer-assigned severities.
 */
@Suppress("DEPRECATION")
fun splitImportWarnings(warnings: List<String>): ImportWarningGroups =
    splitTypedImportWarnings(warnings.map { ParserWarning(it, severityOfParserWarning(it)) })

/**
 * Producer-assigned severity for a parser message that must travel through a
 * legacy `List<String>` channel.
 *
 * The parsers tag a message when they emit it (where it is known whether bytes
 * were actually dropped); the UI strips the tag and reads the severity from it.
 * Nothing downstream infers severity from the wording, so rewording a notice —
 * or mentioning "skipped" in an advisory — can never move it between panels.
 * The tag is a control-character prefix that is never shown to the learner.
 */
internal const val PARSER_DROPPED_TAG = "\u0002r2-dropped\u0002"

/** Marks a message as reported content loss at the point the producer writes it. */
fun droppedParserMessage(message: String): String = PARSER_DROPPED_TAG + message

/** The severity the producer assigned; an untagged message is a benign notice. */
fun parserWarningSeverity(message: String): ImportWarningSeverity =
    if (message.startsWith(PARSER_DROPPED_TAG)) {
        ImportWarningSeverity.DROPPED
    } else {
        ImportWarningSeverity.ADVISORY
    }

/** The learner-facing text, with any producer tag removed. */
fun parserWarningText(message: String): String = message.removePrefix(PARSER_DROPPED_TAG)

/**
 * Legacy substring classifier, kept only for the bare-string adapter and the
 * tests that still feed it hand-written examples. The import screen no longer
 * uses it: live parser messages carry [PARSER_DROPPED_TAG] from their producer.
 */
@Deprecated(
    "Severity comes from the producer via droppedParserMessage/parserWarningSeverity. " +
        "Only the legacy splitImportWarnings adapter uses this."
)
fun severityOfParserWarning(message: String): ImportWarningSeverity =
    if (message.contains("skipped", ignoreCase = true)) {
        ImportWarningSeverity.DROPPED
    } else {
        ImportWarningSeverity.ADVISORY
    }
