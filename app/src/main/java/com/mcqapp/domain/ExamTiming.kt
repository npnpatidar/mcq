package com.mcqapp.domain

/**
 * Clamps exam durations and converts them to seconds without overflowing.
 *
 * `durationMinutes` arrives from an imported file and was used unchecked as
 * `minutes * 60`. Values above ~35.8 million overflow an `Int` to a negative
 * number, so the timer never started and the header rendered a negative
 * clock — from a perfectly valid-looking paper.
 */
object ExamTiming {

    /** A week. Anything longer is a data error, not an exam. */
    const val MAX_MINUTES: Int = 7 * 24 * 60

    /** 0 means "no timer", which is a legitimate setting and is preserved. */
    fun minutesFrom(raw: Int?): Int {
        val value = raw ?: return 0
        if (value <= 0) return 0
        return minOf(value, MAX_MINUTES)
    }

    /** Seconds for [minutes], saturating rather than wrapping. */
    fun secondsFrom(minutes: Int): Long =
        if (minutes <= 0) 0L else minutes.coerceAtMost(MAX_MINUTES).toLong() * 60L
}
