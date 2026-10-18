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
    cardState = cardState.name,
    reviewCount = reviewCount
)
