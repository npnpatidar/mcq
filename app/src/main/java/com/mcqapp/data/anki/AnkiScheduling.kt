package com.mcqapp.data.anki

import com.mcqapp.data.io.CardScheduleDto
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Translates between this app's card schedule and Anki's `cards` row.
 *
 * Anki stores `due` in one of two units depending on the card's state, which
 * is the only genuinely tricky part of reading a package. From Anki's own
 * importer (`rslib/src/import_export/package/apkg/import/cards.rs`):
 *
 *  - `queue` 2 (Review) and 3 (DayLearn), and any card of `type` 2 (Review):
 *    `due` is a number of days since the collection was created, so the
 *    collection's `crt` timestamp is the epoch it counts from.
 *  - `queue` 1 (Learn) and 4 (PreviewRepeat): `due` is a plain unix timestamp
 *    in seconds, with no reference to the collection.
 *  - `queue` 0 (New): `due` is the position the card takes in the new queue,
 *    not a date at all.
 *  - `queue` -1, -2, -3 (Suspended, SchedBuried, UserBuried): not due.
 *
 * Everything here is pure arithmetic so the conversion can be tested without a
 * database, and so both directions share one description of the format.
 */
object AnkiScheduling {

    // Anki's CardQueue
    const val QUEUE_NEW = 0
    const val QUEUE_LEARN = 1
    const val QUEUE_REVIEW = 2
    const val QUEUE_DAY_LEARN = 3
    const val QUEUE_PREVIEW_REPEAT = 4

    // Anki's CardType
    const val TYPE_NEW = 0
    const val TYPE_LEARN = 1
    const val TYPE_REVIEW = 2
    const val TYPE_RELEARN = 3

    private const val DAY_MS = 86_400_000L

    /** Anki's study-day boundary. See [com.mcqapp.domain.DayBoundary]. */
    private const val DAY_START_HOUR =
        com.mcqapp.domain.DayBoundary.DEFAULT_DAY_START_HOUR
    private const val MIN_EASE = 1.3
    private const val MAX_EASE = 3.0

    /**
     * One card's scheduling as Anki stores it, ready for an `cards` row.
     *
     * [due] is left in Anki's own unit, which only has meaning together with
     * the `crt` the package was written with.
     */
    data class AnkiCardSchedule(
        val type: Int,
        val queue: Int,
        val due: Int,
        val ivl: Int,
        val factor: Int,
        val reps: Int,
        val lapses: Int
    )

    /**
     * Anki's `cards` row to this app's schedule, or null when there is nothing
     * to carry: a card Anki has never studied is already new here, and this app
     * has no suspended or buried state to reproduce.
     *
     * [lastReviewedAtMillis] comes from the review log, which a package may
     * omit; when it is absent the last review is inferred from the interval.
     */
    fun fromAnki(
        type: Int,
        queue: Int,
        due: Int,
        ivl: Int,
        factor: Int,
        reps: Int,
        lapses: Int,
        crtSeconds: Long,
        lastReviewedAtMillis: Long = 0L,
        nowMillis: Long = System.currentTimeMillis()
    ): CardScheduleDto? {
        // Suspended and buried cards are not due, and this app has no such
        // state; a new card is already new here, so there is nothing to carry.
        if (queue < 0 || queue == QUEUE_NEW) return null

        val dueInDaysSinceCreation = queue == QUEUE_REVIEW || queue == QUEUE_DAY_LEARN ||
            type == TYPE_REVIEW
        // A learning card's due is a timestamp; a review card's is a day count
        // measured from the collection's creation. Anchoring the day count to
        // the start of that day means a card Anki shows as due today lands
        // today here too, instead of hours into the future on a collection
        // created this afternoon.
        val dueAt = if (dueInDaysSinceCreation) {
            startOfDay(crtSeconds * 1000L + due * DAY_MS)
        } else {
            due * 1000L
        }
        if (dueAt <= 0L) return null

        // `ivl` is only in days for a review card. A learning card's is the
        // current step in minutes, and this app schedules in whole days, so the
        // interval is left at 0 and the card comes back when its due time
        // arrives.
        val intervalDays = if (dueInDaysSinceCreation) max(0, ivl) else 0
        val inferred = if (lastReviewedAtMillis > 0L) {
            lastReviewedAtMillis
        } else {
            max(0L, dueAt - intervalDays * DAY_MS).coerceAtMost(nowMillis)
        }
        return CardScheduleDto(
            ease = (factor / 1000.0).coerceIn(MIN_EASE, MAX_EASE),
            intervalDays = intervalDays,
            dueAt = dueAt,
            reps = max(0, reps),
            lapses = max(0, lapses),
            leech = lapses >= LEECH_THRESHOLD,
            lastReviewedAt = inferred
        )
    }

    /**
     * This app's schedule to an Anki `cards` row, or null for a card that is
     * new here, which Anki represents as a new card with a queue position.
     *
     * The package is written with `crt` = export time, so a review card's due
     * day is simply its interval from today: 0 means due today, and a card that
     * is already overdue is also 0, which is what Anki shows for an overdue
     * card. Days are rounded, not truncated, because Anki's own day boundary
     * rolls over at 04:00 and an exact multiple of 24 hours is not a
     * reconstruction of that.
     */
    fun toAnki(state: CardScheduleDto, crtSeconds: Long, nowMillis: Long = System.currentTimeMillis()): AnkiCardSchedule {
        val factor = (state.ease * 1000).roundToInt().coerceIn(
            (MIN_EASE * 1000).toInt(), (MAX_EASE * 1000).toInt()
        )
        val reps = max(0, state.reps)
        val lapses = max(0, state.lapses)

        // Never studied but already has a due time: the same shape as this
        // app's "learning" card, and Anki's Learn queue takes a timestamp.
        if (reps <= 0) {
            if (state.dueAt <= 0L) return NEW_CARD
            return AnkiCardSchedule(
                type = TYPE_LEARN,
                queue = QUEUE_LEARN,
                due = (state.dueAt / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                // Anki's learning interval is a step in minutes.
                ivl = ((state.dueAt - nowMillis).coerceAtLeast(0L) / 60_000L)
                    .toInt().coerceIn(1, 60 * 24 * 7),
                factor = factor,
                reps = reps,
                lapses = lapses
            )
        }

        val daysFromExport = ((state.dueAt - crtSeconds * 1000L).toDouble() / DAY_MS)
            .roundToInt().coerceAtLeast(0)
        return AnkiCardSchedule(
            type = TYPE_REVIEW,
            queue = QUEUE_REVIEW,
            due = daysFromExport,
            ivl = max(0, state.intervalDays),
            factor = factor,
            reps = reps,
            lapses = lapses
        )
    }

    /** Placeholder for a card that has never been studied in this app. */
    val NEW_CARD = AnkiCardSchedule(
        type = TYPE_NEW,
        queue = QUEUE_NEW,
        due = 0,
        ivl = 0,
        factor = 0,
        reps = 0,
        lapses = 0
    )

    /** Anki's default leech threshold, and this app's. */
    const val LEECH_THRESHOLD = 8

    /**
     * Truncates to the start of the study day so a due *day* means the start of
     * that day. Anki rolls its day over at 04:00, and this app now schedules to
     * the same boundary ([com.mcqapp.domain.DayBoundary]), so the anchor has to
     * match or every card would shift by four hours across an export.
     *
     * The boundary hour is a parameter rather than read from settings: this is
     * the interchange format, and it defaults to Anki's own hour.
     */
    private fun startOfDay(millis: Long, dayStartHour: Int = DAY_START_HOUR): Long =
        com.mcqapp.domain.DayBoundary.startOfDay(
            millis, dayStartHour, com.mcqapp.domain.DayBoundary.zoneOffset(millis)
        )
}
