package com.mcqapp.util

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Renders a scheduling delay the way Anki writes it on the answer buttons:
 * `10m`, `8h`, `3d`, `2.5mo`, `1.2y`.
 *
 * The unit is picked by magnitude, not rounded to a fixed grid, because the
 * whole point of the label is to let a learner compare the four options at a
 * glance. Sub-minute delays still show as seconds rather than rounding to `0m`,
 * which would be indistinguishable from no delay at all.
 *
 * Anki's own month and year lengths are approximate; 30-day months and 365-day
 * years match what its buttons show closely enough for a label.
 */
object IntervalFormat {

    private const val SECOND_MS = 1_000L
    private const val MINUTE_MS = 60 * SECOND_MS
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS
    private const val MONTH_DAYS = 30.0
    private const val YEAR_DAYS = 365.0

    /**
     * Formats [millis] as a delay. A non-positive delay means "not scheduled",
     * which is reported as [NEVER] rather than as a zero-length wait.
     */
    fun format(millis: Long): String {
        if (millis <= 0L) return NEVER
        if (millis < MINUTE_MS) return "${max(1L, round(millis, SECOND_MS))}s"
        if (millis < HOUR_MS) return "${max(1L, round(millis, MINUTE_MS))}m"
        if (millis < DAY_MS) return "${decimal(millis, HOUR_MS)}h"

        val days = millis.toDouble() / DAY_MS
        if (days < MONTH_DAYS) return "${max(1L, round(millis, DAY_MS))}d"
        if (days < YEAR_DAYS) return "${decimal(millis, DAY_MS * MONTH_DAYS.toLong())}mo"
        return "${decimal(millis, DAY_MS * YEAR_DAYS.toLong())}y"
    }

    /** Shown when a grade would not schedule the card at all. */
    const val NEVER = "--"

    private fun round(millis: Long, unit: Long): Long =
        max(1L, (millis.toDouble() / unit).roundToLong())

    /** One decimal place, with a bare integer when the fraction rounds away. */
    private fun decimal(millis: Long, unit: Long): String {
        val tenths = (millis.toDouble() / unit * 10).roundToInt()
        val whole = tenths / 10
        val fraction = tenths % 10
        // Rounding 9.96 up yields 100 tenths, which reads as a clean "10"
        // rather than "9.10" or "9.9".
        return if (fraction == 0) "$whole" else "$whole.$fraction"
    }
}