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
