package com.mcqapp.data.repository

import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.domain.CardStateKind

/**
 * Rebuilds a card's schedule row after its content changed, keeping only the
 * two v30 hide flags. A content edit means the learner must study the new
 * question from scratch (the old memory and counters are meaningless), but a
 * suspend or a bury is the learner's own decision and must survive the edit —
 * otherwise the card quietly reappears in Study and disappears from the
 * unsuspend list.
 *
 * Pure so the "memory goes, flags stay" rule is directly testable.
 */
internal fun CardStateEntity.resetForNewContent(newContentHash: String): CardStateEntity = copy(
    ease = 2.5,
    intervalDays = 0,
    dueAt = 0L,
    reps = 0,
    lapses = 0,
    leech = false,
    lastReviewedAt = 0L,
    contentHash = newContentHash,
    difficulty = 0.0,
    stability = 0.0,
    stabilityFast = 0.0,
    cardState = CardStateKind.NEW,
    reviewCount = 0
    // suspended and buriedUntil are deliberately not touched.
)
