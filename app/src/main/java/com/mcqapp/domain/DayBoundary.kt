package com.mcqapp.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone
import kotlin.math.abs

/**
 * The study-day boundary.
 *
 * Anki treats a day as starting at a configurable hour — 04:00 by default,
 * relative to the device's time zone — rather than at midnight. A card answered
 * at 23:00 is therefore due at the following morning's boundary, not 24 hours
 * later, which is why a late sitting does not silently push the whole day's
 * reviews into tomorrow's small hours.
 *
 * The day is a **calendar day in the local zone**, not a fixed 24-hour tick.
 * Both the boundary instant and the day bucket are resolved at the *target*
 * date, using that date's offset, so a DST jump shifts neither a due time nor a
 * bucket: across spring-forward the boundary is 23 real hours away, across
 * fall-back it is 25. (The 04:00 boundary sits after the usual 02:00–03:00
 * transition, so it is never itself inside the jump.)
 *
 * The zone is a parameter rather than a read of the system default where the
 * caller can inject one, so the arithmetic stays testable at any instant. The
 * long-offset overloads are compatibility shims for callers that only have an
 * offset (Anki's day-number mapping and the historical tests); they build a
 * fixed-offset zone and therefore keep the old, exact behaviour.
 */
object DayBoundary {

    const val DAY_MS = 86_400_000L

    /** Anki's default, and this app's. */
    const val DEFAULT_DAY_START_HOUR = 4

    private const val HOUR_MS = 3_600_000L

    /** The device's zone, with its DST rules, for callers that do not inject one. */
    fun defaultZone(): TimeZone = TimeZone.getDefault()

    /** A fixed UTC zone, for callers that want offset-free arithmetic. */
    fun utc(): TimeZone = TimeZone.getTimeZone("UTC")

    /**
     * A zone with [offsetMillis] and no DST rules, for callers/tests that hold
     * only an offset. Uses the `GMT±HH:MM` ids whose `toZoneId()` is a fixed
     * offset — a `SimpleTimeZone` with a made-up id cannot be converted.
     */
    fun fixedOffsetZone(offsetMillis: Long): TimeZone {
        val sign = if (offsetMillis < 0) '-' else '+'
        val total = abs(offsetMillis)
        val hours = total / HOUR_MS
        val minutes = (total % HOUR_MS) / 60_000L
        val seconds = (total % 60_000L) / 1_000L
        val id = buildString {
            append("GMT").append(sign)
            append(hours.toString().padStart(2, '0'))
            if (minutes > 0 || seconds > 0) {
                append(':').append(minutes.toString().padStart(2, '0'))
                if (seconds > 0) append(':').append(seconds.toString().padStart(2, '0'))
            }
        }
        return TimeZone.getTimeZone(id)
    }

    /**
     * Study-day index: the epoch day of the *calendar date* whose 04:00 (or
     * [dayStartHour]) boundary contains [millis]. An instant before the boundary
     * belongs to the previous date. This is the one day-bucket helper the
     * scheduler and the statistics accumulators share.
     */
    fun studyDayIndex(millis: Long, dayStartHour: Int, zone: TimeZone): Long =
        studyDayIndexOf(millis, dayStartHour, zone.toZoneId())

    /**
     * The instant at which the study day numbered [dayIndex] starts: local
     * [dayStartHour] on that calendar date, resolved with that date's offset.
     * A 1-day interval is the *next index*, which is why a DST day is 23 or 25
     * real hours long rather than 24.
     */
    fun boundaryOf(dayIndex: Long, dayStartHour: Int, zone: TimeZone): Long =
        boundaryOfDate(dayIndex, dayStartHour, zone.toZoneId())

    /** The instant of the study-day boundary containing [millis]. */
    fun startOfDay(millis: Long, dayStartHour: Int, zone: TimeZone): Long =
        boundaryOf(studyDayIndex(millis, dayStartHour, zone), dayStartHour, zone)

    /**
     * When a card graded at [millis] with a whole-day [intervalDays] comes back:
     * the [intervalDays]-th study-day boundary after the one containing [millis].
     * Whole days only, which is all this scheduler produces; the sub-day
     * relearn delay is deliberately not passed through here.
     */
    fun dueAfter(millis: Long, intervalDays: Int, dayStartHour: Int, zone: TimeZone): Long =
        boundaryOf(
            studyDayIndex(millis, dayStartHour, zone) + intervalDays.coerceAtLeast(0),
            dayStartHour,
            zone
        )

    /** The zone's UTC offset at [millis], for hour-of-day charts. */
    fun zoneOffsetAt(millis: Long, zone: TimeZone): Long = zone.getOffset(millis).toLong()

    // --- fixed-offset compatibility shims ---

    fun startOfDay(
        millis: Long,
        dayStartHour: Int = DEFAULT_DAY_START_HOUR,
        zoneOffsetMillis: Long = 0L
    ): Long = startOfDay(millis, dayStartHour, fixedOffsetZone(zoneOffsetMillis))

    fun dueAfter(
        millis: Long,
        intervalDays: Int,
        dayStartHour: Int = DEFAULT_DAY_START_HOUR,
        zoneOffsetMillis: Long = 0L
    ): Long = dueAfter(millis, intervalDays, dayStartHour, fixedOffsetZone(zoneOffsetMillis))

    /** The device's UTC offset at [millis], in milliseconds. */
    fun zoneOffset(millis: Long): Long = TimeZone.getDefault().getOffset(millis).toLong()

    // --- pure calendar arithmetic ---

    private fun studyDayIndexOf(millis: Long, dayStartHour: Int, zone: ZoneId): Long {
        val hour = dayStartHour.coerceIn(0, 23)
        val local = Instant.ofEpochMilli(millis).atZone(zone)
        val date = local.toLocalDate()
        return (if (local.hour < hour) date.minusDays(1) else date).toEpochDay()
    }

    private fun boundaryOfDate(dayIndex: Long, dayStartHour: Int, zone: ZoneId): Long {
        val local = LocalDateTime.of(
            LocalDate.ofEpochDay(dayIndex),
            LocalTime.of(dayStartHour.coerceIn(0, 23), 0)
        )
        return local.atZone(zone).toInstant().toEpochMilli()
    }
}
