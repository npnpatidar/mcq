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

    /**
     * No per-attempt override was chosen: the paper's own duration rules.
     * Distinct from 0, which is a deliberate "untimed" pick.
     */
    const val USE_PAPER_DURATION: Int = -1

    /**
     * Seconds a new test session runs for.
     *
     * Precedence: a drill's own minutes (its dialog always asks for them),
     * then the per-attempt override the duration picker produced (0 = an
     * intentional "untimed" run), then the paper's stored duration (0 =
     * untimed there too). Pure so the picker's contract is testable on the
     * JVM.
     */
    fun sessionSeconds(
        isDrill: Boolean,
        drillMinutes: Int,
        overrideMinutes: Int,
        paperMinutes: Int
    ): Long {
        val minutes = when {
            isDrill && drillMinutes > 0 -> drillMinutes
            overrideMinutes >= 0 -> overrideMinutes
            else -> paperMinutes
        }
        return secondsFrom(minutes)
    }
}
