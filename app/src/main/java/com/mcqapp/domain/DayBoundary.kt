package com.mcqapp.domain

/**
 * The study-day boundary.
 *
 * Anki treats a day as starting at a configurable hour — 04:00 by default,
 * relative to the device's time zone — rather than at midnight. A card answered
 * at 23:00 is therefore due at the following morning's boundary, not 24 hours
 * later, which is why a late sitting does not silently push the whole day's
 * reviews into tomorrow's small hours.
 *
 * Everything here is pure. The zone offset is a parameter rather than a read of
 * the system default, so the scheduler stays clock-free and the arithmetic is
 * testable at any instant.
 */
object DayBoundary {

    const val DAY_MS = 86_400_000L

    /** Anki's default, and this app's. */
    const val DEFAULT_DAY_START_HOUR = 4

    private const val HOUR_MS = 3_600_000L

    /**
     * The start of the study day containing [millis].
     *
     * The instant is shifted so that the boundary hour lands on midnight, then
     * floored to a day and shifted back. A flooring remainder rather than `%`
     * so instants before the epoch floor downwards instead of towards zero.
     */
    fun startOfDay(
        millis: Long,
        dayStartHour: Int = DEFAULT_DAY_START_HOUR,
        zoneOffsetMillis: Long = 0L
    ): Long {
        val shift = dayStartHour.coerceIn(0, 23) * HOUR_MS
        val local = millis + zoneOffsetMillis - shift
        val floored = local - Math.floorMod(local, DAY_MS)
        return floored - zoneOffsetMillis + shift
    }

    /**
     * When a card graded at [millis] with a whole-day [intervalDays] comes back.
     *
     * Whole days only, which is all this scheduler produces; the sub-day
     * relearn delay is deliberately not passed through here.
     */
    fun dueAfter(
        millis: Long,
        intervalDays: Int,
        dayStartHour: Int = DEFAULT_DAY_START_HOUR,
        zoneOffsetMillis: Long = 0L
    ): Long =
        startOfDay(millis, dayStartHour, zoneOffsetMillis) +
            intervalDays.coerceAtLeast(0) * DAY_MS

    /** The device's UTC offset at [millis], in milliseconds. */
    fun zoneOffset(millis: Long): Long =
        java.util.TimeZone.getDefault().getOffset(millis).toLong()
}