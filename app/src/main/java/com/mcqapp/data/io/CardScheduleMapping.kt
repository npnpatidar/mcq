package com.mcqapp.data.io

import com.mcqapp.data.local.CardStateEntity

/**
 * One stored card as the schedule the wire format carries.
 *
 * Three callers need this exact conversion — the streamed whole-bank export,
 * the in-memory DTO builder and the Anki package's per-batch provider — and a
 * field left out of one of them is a review that silently restarts. Whether a
 * row counts as studied is the caller's rule, not this mapper's.
 */
/**
 * Separator of the JSON export's composite schedule key. A NUL cannot occur in
 * a generated id and keeps the key unambiguous, unlike `/` or `:`.
 */
internal const val CARD_SCHEDULE_KEY_SEPARATOR = '\u0000'

/**
 * The plain JSON export's key for one card's schedule: `paperId` NUL
 * `questionId`.
 *
 * The map used to be keyed by question id alone, so two papers holding the
 * same question collapsed into one entry — and the survivor was then applied
 * to every occurrence on import. Readers still accept a bare question id
 * (files written before this format, and the Anki path, whose map is already
 * scoped to one paper).
 */
internal fun cardScheduleKey(paperId: String, questionId: String): String =
    "$paperId$CARD_SCHEDULE_KEY_SEPARATOR$questionId"

internal fun CardStateEntity.toCardScheduleDto(): CardScheduleDto = CardScheduleDto(
    ease = ease,
    intervalDays = intervalDays,
    dueAt = dueAt,
    reps = reps,
    lapses = lapses,
    leech = leech,
    lastReviewedAt = lastReviewedAt,
    difficulty = difficulty,
    stability = stability,
    stabilityFast = stabilityFast,
    cardState = cardState.name,
    reviewCount = reviewCount
)
