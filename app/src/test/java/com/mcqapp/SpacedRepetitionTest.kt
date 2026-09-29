package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.ReviewSignal
import com.mcqapp.domain.Sm2Scheduler
import com.mcqapp.domain.Study
import com.mcqapp.domain.StudyReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedRepetitionTest {

    private val now = 1_700_000_000_000L
    private val day = Sm2Scheduler.DAY_MS

    private fun graduate(
        grades: List<ReviewGrade>,
        start: Long = now
    ): CardState = grades.fold(Sm2Scheduler.initial("q1")) { state, grade ->
        Sm2Scheduler.next(state, grade, start)
    }

    // --- basic interval progression ---

    @Test
    fun `new card is new and unscheduled`() {
        val card = Sm2Scheduler.initial("q1")
        assertTrue(card.isNew)
        assertFalse(card.isScheduled)
        assertEquals(0, card.intervalDays)
        assertEquals(Sm2Scheduler.DEFAULT_EASE, card.ease, 0.0001)
    }

    @Test
    fun `good on new card graduates to one day`() {
        val card = Sm2Scheduler.next(Sm2Scheduler.initial("q1"), ReviewGrade.GOOD, now)
        assertEquals(Sm2Scheduler.FIRST_INTERVAL_DAYS, card.intervalDays)
        assertEquals(now + day, card.dueAt)
        assertEquals(1, card.reps)
    }

    @Test
    fun `second good graduates to six days`() {
        val card = graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD))
        assertEquals(Sm2Scheduler.SECOND_INTERVAL_DAYS, card.intervalDays)
        assertEquals(2, card.reps)
    }

    @Test
    fun `third good multiplies by ease`() {
        val card = graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD))
        assertEquals(15, card.intervalDays)
        assertEquals(3, card.reps)
    }

    @Test
    fun `easy on new card starts at four days`() {
        val card = Sm2Scheduler.next(Sm2Scheduler.initial("q1"), ReviewGrade.EASY, now)
        assertEquals(Sm2Scheduler.EASY_FIRST_INTERVAL_DAYS, card.intervalDays)
        assertEquals(1, card.reps)
    }

    @Test
    fun `intervals grow monotonically on repeated good`() {
        val intervals = (1..6).map { graduate(List(it) { ReviewGrade.GOOD }).intervalDays }
        assertEquals(intervals, intervals.sorted())
        assertTrue(intervals.last() > intervals.first())
    }

    @Test
    fun `graduated card is scheduled and no longer new`() {
        val card = graduate(listOf(ReviewGrade.GOOD))
        assertFalse(card.isNew)
        assertTrue(card.isScheduled)
    }

    // --- failure path ---

    @Test
    fun `again on a graduated card returns to relearning today`() {
        val graduated = graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD))
        val failed = Sm2Scheduler.next(graduated, ReviewGrade.AGAIN, now)
        assertEquals(0, failed.intervalDays)
        assertEquals(0, failed.reps)
        assertEquals(now + Sm2Scheduler.RELEARN_MS, failed.dueAt)
        assertEquals(1, failed.lapses)
    }

    @Test
    fun `again on a new card is not counted as a lapse`() {
        val card = Sm2Scheduler.next(Sm2Scheduler.initial("q1"), ReviewGrade.AGAIN, now)
        assertEquals(0, card.lapses)
        assertEquals(0, card.reps)
    }

    @Test
    fun `again never drives ease below the floor`() {
        var card = graduate(listOf(ReviewGrade.GOOD))
        repeat(10) { card = Sm2Scheduler.next(card, ReviewGrade.AGAIN, now) }
        assertEquals(Sm2Scheduler.MIN_EASE, card.ease, 0.0001)
    }

    @Test
    fun `hard and good recover a lapsed card to one day`() {
        val lapsed = Sm2Scheduler.next(
            graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD)),
            ReviewGrade.AGAIN,
            now
        )
        val hard = Sm2Scheduler.next(lapsed, ReviewGrade.HARD, now)
        val good = Sm2Scheduler.next(lapsed, ReviewGrade.GOOD, now)
        assertEquals(1, hard.intervalDays)
        assertEquals(1, good.intervalDays)
        assertTrue("hard must cost more ease than good", hard.ease < good.ease)
    }

    @Test
    fun `hard grows more slowly than good`() {
        val base = graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD))
        val hard = Sm2Scheduler.next(base, ReviewGrade.HARD, now)
        val good = Sm2Scheduler.next(base, ReviewGrade.GOOD, now)
        assertTrue(hard.intervalDays < good.intervalDays)
    }

    @Test
    fun `easy grows fastest and raises ease`() {
        val base = graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD))
        val easy = Sm2Scheduler.next(base, ReviewGrade.EASY, now)
        val good = Sm2Scheduler.next(base, ReviewGrade.GOOD, now)
        assertTrue(easy.intervalDays > good.intervalDays)
        assertTrue(easy.ease > base.ease)
    }

    @Test
    fun `ease is capped at the ceiling`() {
        var card = graduate(List(20) { ReviewGrade.EASY })
        repeat(20) { card = Sm2Scheduler.next(card, ReviewGrade.EASY, now) }
        assertEquals(Sm2Scheduler.MAX_EASE, card.ease, 0.0001)
    }

    @Test
    fun `difficulty means a card still earns growing intervals`() {
        val mediocre = graduate(List(8) { ReviewGrade.HARD })
        val strong = graduate(List(8) { ReviewGrade.EASY })
        assertTrue(mediocre.intervalDays > 0)
        assertTrue(strong.intervalDays > mediocre.intervalDays)
    }

    // --- leeches ---

    @Test
    fun `repeated lapses flag a leech at the threshold`() {
        var card = graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD))
        repeat(Sm2Scheduler.leechThreshold - 1) {
            card = Sm2Scheduler.next(card, ReviewGrade.AGAIN, now)
            card = Sm2Scheduler.next(card, ReviewGrade.GOOD, now)
        }
        assertFalse("not a leech before the threshold", card.leech)
        card = Sm2Scheduler.next(card, ReviewGrade.AGAIN, now)
        assertTrue(card.leech)
        assertEquals(Sm2Scheduler.leechThreshold, card.lapses)
    }

    @Test
    fun `a clean card is never a leech`() {
        val card = graduate(List(30) { ReviewGrade.GOOD })
        assertFalse(card.leech)
        assertEquals(0, card.lapses)
    }

    // --- grade inference ---

    @Test
    fun `skips and wrong answers infer again`() {
        assertEquals(ReviewGrade.AGAIN, Study.inferGrade(false, 3))
        assertEquals(ReviewGrade.AGAIN, Study.inferGrade(true, 5, skipped = true))
    }

    @Test
    fun `fast correct answers infer easy`() {
        assertEquals(ReviewGrade.EASY, Study.inferGrade(true, 2))
    }

    @Test
    fun `slow correct answers infer hard`() {
        assertEquals(ReviewGrade.HARD, Study.inferGrade(true, Study.SLOW_SECONDS))
    }

    @Test
    fun `untracked timing is good, not easy`() {
        assertEquals(ReviewGrade.GOOD, Study.inferGrade(true, 0))
    }

    @Test
    fun `normal correct answers infer good`() {
        assertEquals(ReviewGrade.GOOD, Study.inferGrade(true, 15))
    }

    // --- rebuild from history ---

    @Test
    fun `rebuild replays history in time order`() {
        val signals = listOf(
            ReviewSignal("q1", ReviewGrade.GOOD, now + 3000),
            ReviewSignal("q1", ReviewGrade.GOOD, now + 1000),
            ReviewSignal("q1", ReviewGrade.GOOD, now + 2000)
        )
        val card = Study.rebuild(Sm2Scheduler, "q1", signals)
        val expected = graduate(
            listOf(ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD),
            start = now + 1000
        )
        assertEquals(expected.intervalDays, card.intervalDays)
        assertEquals(expected.reps, card.reps)
        // The last review happened at now+3000, so the due date is measured
        // from that review, not from the first one.
        assertEquals(now + 3000 + 15 * day, card.dueAt)
    }

    @Test
    fun `rebuild ignores other questions`() {
        val signals = listOf(
            ReviewSignal("q1", ReviewGrade.GOOD, now),
            ReviewSignal("q2", ReviewGrade.AGAIN, now)
        )
        assertEquals(0, Study.rebuild(Sm2Scheduler, "q1", signals).lapses)
        assertEquals(1, Study.rebuild(Sm2Scheduler, "q1", signals).reps)
    }

    @Test
    fun `rebuild with no history yields a new card`() {
        assertTrue(Study.rebuild(Sm2Scheduler, "q1", emptyList()).isNew)
    }

    @Test
    fun `rebuild reflects a lapse in existing history`() {
        val signals = listOf(
            ReviewSignal("q1", ReviewGrade.GOOD, now),
            ReviewSignal("q1", ReviewGrade.AGAIN, now + 1000),
            ReviewSignal("q1", ReviewGrade.GOOD, now + 2000)
        )
        assertEquals(1, Study.rebuild(Sm2Scheduler, "q1", signals).lapses)
    }

    // --- queue ---

    @Test
    fun `queue returns nothing for an empty paper`() {
        assertTrue(Study.queue(Sm2Scheduler, emptyList(), emptyMap(), now).isEmpty())
    }

    @Test
    fun `queue caps new cards at the daily limit`() {
        val ids = (1..50).map { "q$it" }
        val queue = Study.queue(Sm2Scheduler, ids, emptyMap(), now, newLimit = 5)
        assertEquals(5, queue.size)
        assertTrue(queue.all { it.reason == StudyReason.NEW })
    }

    @Test
    fun `queue includes due cards ahead of new cards`() {
        val queue = Study.queue(
            Sm2Scheduler,
            listOf("new1", "new2", "due1"),
            mapOf("due1" to CardState(questionId = "due1", intervalDays = 3, dueAt = now - day, reps = 2)),
            now
        )
        assertEquals("due1", queue.first().questionId)
        assertEquals(StudyReason.DUE, queue.first().reason)
        // due1 + both new cards (default limit of 20 covers both)
        assertEquals(3, queue.size)
    }

    @Test
    fun `queue excludes cards that are not yet due`() {
        val states = mapOf(
            "later" to CardState(questionId = "later", intervalDays = 3, dueAt = now + day, reps = 2)
        )
        assertTrue(Study.queue(Sm2Scheduler, listOf("later"), states, now).isEmpty())
    }

    @Test
    fun `queue sorts due cards oldest first`() {
        val states = mapOf(
            "recent" to CardState(questionId = "recent", intervalDays = 2, dueAt = now - day, reps = 1),
            "oldest" to CardState(questionId = "oldest", intervalDays = 9, dueAt = now - 9 * day, reps = 3),
            "middle" to CardState(questionId = "middle", intervalDays = 4, dueAt = now - 4 * day, reps = 2)
        )
        val queue = Study.queue(
            Sm2Scheduler,
            listOf("recent", "oldest", "middle"),
            states,
            now
        )
        assertEquals(listOf("oldest", "middle", "recent"), queue.map { it.questionId })
    }

    @Test
    fun `due leeches are labelled as leeches`() {
        val states = mapOf(
            "leech1" to CardState(
                questionId = "leech1",
                intervalDays = 2,
                dueAt = now - day,
                reps = 3,
                lapses = 8,
                leech = true
            )
        )
        val queue = Study.queue(Sm2Scheduler, listOf("leech1"), states, now)
        assertEquals(StudyReason.LEECH, queue.first().reason)
    }

    @Test
    fun `new limit does not suppress due cards`() {
        val states = mapOf(
            "due1" to CardState(questionId = "due1", intervalDays = 2, dueAt = now - day, reps = 1),
            "due2" to CardState(questionId = "due2", intervalDays = 2, dueAt = now - day, reps = 1)
        )
        val queue = Study.queue(
            Sm2Scheduler,
            listOf("due1", "due2", "new1"),
            states,
            now,
            newLimit = 0
        )
        assertEquals(2, queue.size)
        assertTrue(queue.none { it.reason == StudyReason.NEW })
    }

    @Test
    fun `queue order is stable across repeated calls`() {
        val ids = (1..10).map { "q$it" }
        val states = mapOf(
            "q3" to CardState(questionId = "q3", intervalDays = 2, dueAt = now - day, reps = 1)
        )
        assertEquals(
            Study.queue(Sm2Scheduler, ids, states, now).map { it.questionId },
            Study.queue(Sm2Scheduler, ids, states, now).map { it.questionId }
        )
    }

    // --- counts ---

    @Test
    fun `due count ignores new and not-yet-due cards`() {
        val states = listOf(
            CardState(questionId = "new"),
            CardState(questionId = "due", intervalDays = 2, dueAt = now - day, reps = 2),
            CardState(questionId = "later", intervalDays = 2, dueAt = now + day, reps = 2)
        )
        assertEquals(1, Study.dueCount(states, now))
    }

    @Test
    fun `new count counts missing and unreviewed cards`() {
        val states = mapOf(
            "b" to CardState(questionId = "b", intervalDays = 1, dueAt = now, reps = 1)
        )
        assertEquals(2, Study.newCount(listOf("a", "b", "c"), states))
    }

    @Test
    fun `leech count counts flagged cards`() {
        val states = listOf(
            CardState(questionId = "a", leech = true),
            CardState(questionId = "b", leech = true),
            CardState(questionId = "c")
        )
        assertEquals(2, Study.leechCount(states))
    }

    // --- retention ---

    @Test
    fun `retention is full before the due date`() {
        val card = CardState(questionId = "q", intervalDays = 5, dueAt = now + 2 * day, reps = 2)
        assertEquals(1.0, Sm2Scheduler.retention(card, now), 0.0001)
    }

    @Test
    fun `retention decays as the due date passes`() {
        val card = CardState(questionId = "q", intervalDays = 5, dueAt = now, reps = 2)
        val late = Sm2Scheduler.retention(card, now + 5 * day)
        assertTrue(late < 1.0)
        assertTrue(late in 0.0..1.0)
    }

    @Test
    fun `retention is zero for a card still learning`() {
        assertEquals(0.0, Sm2Scheduler.retention(Sm2Scheduler.initial("q"), now), 0.0001)
        val relearning = Sm2Scheduler.next(
            graduate(listOf(ReviewGrade.GOOD)),
            ReviewGrade.AGAIN,
            now
        )
        assertEquals(0.0, Sm2Scheduler.retention(relearning, now), 0.0001)
    }

    // --- reset ---

    @Test
    fun `reset returns a card to new and keeps only its id`() {
        val card = graduate(List(6) { ReviewGrade.EASY })
        val reset = Sm2Scheduler.reset(card)
        assertTrue(reset.isNew)
        assertEquals(0, reset.reps)
        assertEquals(0, reset.lapses)
        assertFalse(reset.leech)
        assertEquals("q1", reset.questionId)
    }

    @Test
    fun `reset clears a leech`() {
        var card = Sm2Scheduler.initial("q1")
        repeat(Sm2Scheduler.leechThreshold) {
            card = Sm2Scheduler.next(card, ReviewGrade.GOOD, now)
            card = Sm2Scheduler.next(card, ReviewGrade.AGAIN, now)
        }
        assertTrue(card.leech)
        assertFalse(Sm2Scheduler.reset(card).leech)
    }
}
