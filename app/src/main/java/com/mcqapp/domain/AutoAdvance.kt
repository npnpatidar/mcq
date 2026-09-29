package com.mcqapp.domain

/**
 * Auto-advance rule: after answering, move on only when the setting is on,
 * the question takes a single answer (multi-correct needs several taps)
 * and there is a next question. Returns the target index or null to stay.
 */
object AutoAdvance {

    fun nextIndex(autoAdvance: Boolean, multiCorrect: Boolean, current: Int, total: Int): Int? {
        if (!autoAdvance || multiCorrect) return null
        val next = current + 1
        return if (next < total) next else null
    }
}
