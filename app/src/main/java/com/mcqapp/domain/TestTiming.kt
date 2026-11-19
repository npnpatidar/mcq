package com.mcqapp.domain

/**
 * Pure timing helpers for test sessions.
 *
 * Warnings fire once per threshold while the countdown crosses below it.
 * Dwell tracks seconds spent per question from navigation timestamps, so it
 * works for timed and untimed papers alike.
 */
object TimerWarnings {
    val thresholdsSeconds = listOf(300, 60)

    /** Thresholds crossed when moving from [previous] to [current] remaining. */
    fun newlyDue(previous: Int, current: Int): List<Int> =
        thresholdsSeconds.filter { t -> previous > t && current <= t }

    fun message(thresholdSeconds: Int): String = when (thresholdSeconds) {
        300 -> "5 minutes left"
        60 -> "1 minute left — the test auto-submits at zero"
        else -> "$thresholdSeconds seconds left"
    }

    /**
     * The localizable identity of a threshold. The clock owns the *when*
     * ([newlyDue]) and the UI owns the *wording*: mapping to a [TimerWarningKind]
     * keeps this object pure (no Android resources) while letting the ViewModel
     * resolve the warning through `<plurals>` resources in both locales.
     */
    enum class TimerWarningKind { FIVE_MINUTES, ONE_MINUTE, SECONDS }

    fun warningKind(thresholdSeconds: Int): TimerWarningKind = when (thresholdSeconds) {
        300 -> TimerWarningKind.FIVE_MINUTES
        60 -> TimerWarningKind.ONE_MINUTE
        else -> TimerWarningKind.SECONDS
    }

    /**
     * The count a warning reports: minutes for the minute thresholds, raw
     * seconds otherwise. The ViewModel passes this same value as both the plural
     * quantity and the `%1$d` argument, so "1 minute" and "5 minutes" each pick
     * the right form. Pure so that quantity/argument contract is JVM-testable.
     */
    fun warningCount(thresholdSeconds: Int): Int = when (warningKind(thresholdSeconds)) {
        TimerWarningKind.FIVE_MINUTES -> thresholdSeconds / 60
        TimerWarningKind.ONE_MINUTE -> 1
        TimerWarningKind.SECONDS -> thresholdSeconds
    }
}

/**
 * The countdown as a wall-clock compensation instead of a decrement.
 *
 * Ticking a `delay(1000)` loop by one count per iteration ran continuously
 * behind real time: every iteration costs the delay's scheduling slack plus
 * the update itself, so a long paper reached zero late against the learner's
 * own watch, and the *grading* clock — the auto-submit deadline — was the
 * thing that drifted. Anchoring on elapsed real time and recomputing from the
 * anchor every tick holds the countdown to the paper's true elapsed time.
 */
object Countdown {

    /**
     * The remaining seconds a countdown anchored with [anchorRemaining] seconds
     * at [anchorElapsedMillis] should show at [nowElapsedMillis], saturated at
     * zero. Pure so the anchor arithmetic is testable without a coroutine.
     */
    fun remainingAt(anchorRemaining: Int, anchorElapsedMillis: Long, nowElapsedMillis: Long): Int {
        val elapsedSeconds = (nowElapsedMillis - anchorElapsedMillis).coerceAtLeast(0L) / 1000L
        val remaining = anchorRemaining.toLong() - elapsedSeconds
        return when {
            remaining <= 0L -> 0
            else -> remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
}

object Dwell {
    fun add(dwell: Map<String, Long>, questionId: String, seconds: Long): Map<String, Long> {
        if (seconds <= 0) return dwell
        return dwell + (questionId to ((dwell[questionId] ?: 0L) + seconds))
    }

    fun format(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
