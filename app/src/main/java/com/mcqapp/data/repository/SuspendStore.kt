package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import java.util.TimeZone

/** One card currently hidden from Study, for the unsuspend route. */
data class SuspendedCard(
    val questionId: String,
    val text: String,
    val suspended: Boolean,
    val buriedUntil: Long?
)

/**
 * The bury deadline is the next study-day boundary (04:00 local by default),
 * not a fixed 24 hours. "Next" mirrors [DayBoundary.dueAfter] with one whole
 * day from the study day containing [now]: an instant before the boundary
 * belongs to the *previous* study day, so a bury at 05:00 with a 06:00 start
 * returns at 06:00 the same calendar day, one at 23:00 returns at 06:00 the
 * following morning, and one exactly on the boundary waits a whole day.
 * Keeping the rule here as a pure function makes it JVM-testable.
 */
internal object SuspendSchedule {
    fun nextBoundary(
        now: Long,
        dayStartHour: Int = DayBoundary.DEFAULT_DAY_START_HOUR,
        zone: TimeZone = DayBoundary.defaultZone()
    ): Long = DayBoundary.dueAfter(now, 1, dayStartHour, zone)
}

/**
 * Suspend / bury writes. Deliberately separate from the grading path: none of
 * these calls touches the FSRS schedule or appends to `review_log`, and a card
 * with no schedule yet gets a minimal row so the flag has somewhere to live.
 *
 * This is a new store beside [McqRepository] rather than a method on it: the
 * Study screen owns these actions, and the repository's study surface is
 * shared with surfaces owned by other work. It takes the same [AppDatabase]
 * singleton the repository uses, so both see one database and one transaction
 * manager.
 */
internal class SuspendStore(private val db: AppDatabase) {

    suspend fun suspend(paperId: String, questionId: String) {
        if (db.cardStateDao().setSuspended(paperId, questionId) == 0) {
            // A new card has no schedule row: give the flag a home without
            // inventing a grade. The row stays new, so unsuspending returns
            // the card to the fresh pool.
            if (ensureRow(paperId, questionId)) {
                db.cardStateDao().setSuspended(paperId, questionId)
            }
        }
    }

    suspend fun bury(paperId: String, questionId: String, buriedUntil: Long) {
        if (db.cardStateDao().setBuriedUntil(paperId, questionId, buriedUntil) == 0) {
            if (ensureRow(paperId, questionId)) {
                db.cardStateDao().setBuriedUntil(paperId, questionId, buriedUntil)
            }
        }
    }

    /** One action clears both flags: the learner asked for the card back. */
    suspend fun unsuspend(paperId: String, questionId: String) {
        db.cardStateDao().clearSuspension(paperId, questionId)
    }

    suspend fun suspendedCards(paperId: String, now: Long): List<SuspendedCard> =
        db.cardStateDao().suspendedCards(paperId, now).map {
            SuspendedCard(
                questionId = it.questionId,
                text = it.text,
                suspended = it.suspended,
                buriedUntil = it.buriedUntil
            )
        }

    /**
     * Creates the schedule row an unseen card needs before a flag can be set.
     * False means the question is gone (a delete raced the tap), so the caller
     * simply leaves the card alone.
     */
    private suspend fun ensureRow(paperId: String, questionId: String): Boolean {
        if (db.cardStateDao().get(paperId, questionId) != null) return true
        val hash = db.questionDao().getHashById(questionId) ?: return false
        db.cardStateDao().upsert(
            CardStateEntity(
                paperId = paperId,
                questionId = questionId,
                ease = 2.5,
                intervalDays = 0,
                dueAt = 0L,
                reps = 0,
                lapses = 0,
                leech = false,
                lastReviewedAt = 0L,
                contentHash = hash,
                cardState = CardStateKind.NEW
            )
        )
        return true
    }
}
