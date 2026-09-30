package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.ReviewSignal
import com.mcqapp.domain.SchedulerConfig
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
    private val config = SchedulerConfig()
    private val sched = Sm2Scheduler(config)

    private fun graduate(
        grades: List<ReviewGrade>,
        start: Long = now
    ): CardState = grades.fold(sched.initial("q1")) { state, grade ->
        sched.next(state, grade, start)
    }

    // --- basic interval progression ---

    @Test
    fun `new card is new and unscheduled`() {
        val card = sched.initial("q1")
        assertTrue(card.isNew)
        assertFalse(card.isScheduled)
        assertEquals(0, card.intervalDays)
        assertEquals(SchedulerConfig().defaultEase, card.ease, 0.0001)
    }

    @Test
    fun `good on new card graduates to one day`() {
        val card = sched.next(sched.initial("q1"), ReviewGrade.GOOD, now)
        assertEquals(SchedulerConfig().firstIntervalDays, card.intervalDays)
        assertEquals(now + day, card.dueAt)
        assertEquals(1, card.reps)
    }

    @Test
    fun `second good graduates to six days`() {
        val card = graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD))
        assertEquals(SchedulerConfig().secondIntervalDays, card.intervalDays)
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
        val card = sched.next(sched.initial("q1"), ReviewGrade.EASY, now)
        assertEquals(SchedulerConfig().easyFirstIntervalDays, card.intervalDays)
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

    // --- relearning is not a new card ---

    @Test
    fun `a failed card is relearning rather than new`() {
        val graduated = graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD))
        val failed = sched.next(graduated, ReviewGrade.AGAIN, now)
        assertFalse(failed.isNew)
        assertTrue(failed.isLearning)
    }

    @Test
    fun `a relearning card is hidden until its due time`() {
        val failed = sched.next(
            graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD)), ReviewGrade.AGAIN, now
        )
        val early = Study.queue(
            sched, listOf("q1"), mapOf("q1" to failed), now = now
        )
        assertTrue(early.isEmpty())
    }

    @Test
    fun `a relearning card comes back the same day once due`() {
        val failed = sched.next(
            graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD)), ReviewGrade.AGAIN, now
        )
        val later = now + SchedulerConfig().relearnMs
        val queue = Study.queue(
            sched, listOf("q1"), mapOf("q1" to failed), now = later
        )
        assertEquals(1, queue.size)
        assertEquals(StudyReason.DUE, queue.first().reason)
    }

    @Test
    fun `the new limit does not hide a relearning card`() {
        val failed = sched.next(
            graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD)), ReviewGrade.AGAIN, now
        )
        val later = now + SchedulerConfig().relearnMs
        val queue = Study.queue(
            sched,
            listOf("q1", "q2", "q3"),
            mapOf("q1" to failed, "q2" to sched.initial("q2")),
            now = later,
            newLimit = 0
        )
        assertEquals(1, queue.size)
        assertEquals("q1", queue.first().questionId)
    }

    // --- failure path ---

    @Test
    fun `again on a graduated card returns to relearning today`() {
        val graduated = graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD))
        val failed = sched.next(graduated, ReviewGrade.AGAIN, now)
        assertEquals(0, failed.intervalDays)
        assertEquals(0, failed.reps)
        assertEquals(now + SchedulerConfig().relearnMs, failed.dueAt)
        assertEquals(1, failed.lapses)
    }

    @Test
    fun `again on a new card is not counted as a lapse`() {
        val card = sched.next(sched.initial("q1"), ReviewGrade.AGAIN, now)
        assertEquals(0, card.lapses)
        assertEquals(0, card.reps)
    }

    @Test
    fun `again never drives ease below the floor`() {
        var card = graduate(listOf(ReviewGrade.GOOD))
        repeat(10) { card = sched.next(card, ReviewGrade.AGAIN, now) }
        assertEquals(SchedulerConfig().minEase, card.ease, 0.0001)
    }

    @Test
    fun `hard and good recover a lapsed card to one day`() {
        val lapsed = sched.next(
            graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD)),
            ReviewGrade.AGAIN,
            now
        )
        val hard = sched.next(lapsed, ReviewGrade.HARD, now)
        val good = sched.next(lapsed, ReviewGrade.GOOD, now)
        assertEquals(1, hard.intervalDays)
        assertEquals(1, good.intervalDays)
        assertTrue("hard must cost more ease than good", hard.ease < good.ease)
    }

    @Test
    fun `hard grows more slowly than good`() {
        val base = graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD))
        val hard = sched.next(base, ReviewGrade.HARD, now)
        val good = sched.next(base, ReviewGrade.GOOD, now)
        assertTrue(hard.intervalDays < good.intervalDays)
    }

    @Test
    fun `easy grows fastest and raises ease`() {
        val base = graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD))
        val easy = sched.next(base, ReviewGrade.EASY, now)
        val good = sched.next(base, ReviewGrade.GOOD, now)
        assertTrue(easy.intervalDays > good.intervalDays)
        assertTrue(easy.ease > base.ease)
    }

    @Test
    fun `ease is capped at the ceiling`() {
        var card = graduate(List(20) { ReviewGrade.EASY })
        repeat(20) { card = sched.next(card, ReviewGrade.EASY, now) }
        assertEquals(SchedulerConfig().maxEase, card.ease, 0.0001)
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
        repeat(SchedulerConfig().leechThreshold - 1) {
            card = sched.next(card, ReviewGrade.AGAIN, now)
            card = sched.next(card, ReviewGrade.GOOD, now)
        }
        assertFalse("not a leech before the threshold", card.leech)
        card = sched.next(card, ReviewGrade.AGAIN, now)
        assertTrue(card.leech)
        assertEquals(SchedulerConfig().leechThreshold, card.lapses)
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
        val card = Study.rebuild(sched, "q1", signals)
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
        assertEquals(0, Study.rebuild(sched, "q1", signals).lapses)
        assertEquals(1, Study.rebuild(sched, "q1", signals).reps)
    }

    @Test
    fun `rebuild with no history yields a new card`() {
        assertTrue(Study.rebuild(sched, "q1", emptyList()).isNew)
    }

    @Test
    fun `rebuild reflects a lapse in existing history`() {
        val signals = listOf(
            ReviewSignal("q1", ReviewGrade.GOOD, now),
            ReviewSignal("q1", ReviewGrade.AGAIN, now + 1000),
            ReviewSignal("q1", ReviewGrade.GOOD, now + 2000)
        )
        assertEquals(1, Study.rebuild(sched, "q1", signals).lapses)
    }

    // --- queue ---

    @Test
    fun `queue returns nothing for an empty paper`() {
        assertTrue(Study.queue(sched, emptyList(), emptyMap(), now).isEmpty())
    }

    @Test
    fun `queue caps new cards at the daily limit`() {
        val ids = (1..50).map { "q$it" }
        val queue = Study.queue(sched, ids, emptyMap(), now, newLimit = 5)
        assertEquals(5, queue.size)
        assertTrue(queue.all { it.reason == StudyReason.NEW })
    }

    @Test
    fun `queue includes due cards ahead of new cards`() {
        val queue = Study.queue(
            sched,
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
        assertTrue(Study.queue(sched, listOf("later"), states, now).isEmpty())
    }

    @Test
    fun `queue sorts due cards oldest first`() {
        val states = mapOf(
            "recent" to CardState(questionId = "recent", intervalDays = 2, dueAt = now - day, reps = 1),
            "oldest" to CardState(questionId = "oldest", intervalDays = 9, dueAt = now - 9 * day, reps = 3),
            "middle" to CardState(questionId = "middle", intervalDays = 4, dueAt = now - 4 * day, reps = 2)
        )
        val queue = Study.queue(
            sched,
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
        val queue = Study.queue(sched, listOf("leech1"), states, now)
        assertEquals(StudyReason.LEECH, queue.first().reason)
    }

    @Test
    fun `new limit does not suppress due cards`() {
        val states = mapOf(
            "due1" to CardState(questionId = "due1", intervalDays = 2, dueAt = now - day, reps = 1),
            "due2" to CardState(questionId = "due2", intervalDays = 2, dueAt = now - day, reps = 1)
        )
        val queue = Study.queue(
            sched,
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
            Study.queue(sched, ids, states, now).map { it.questionId },
            Study.queue(sched, ids, states, now).map { it.questionId }
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
        assertEquals(1.0, sched.retention(card, now), 0.0001)
    }

    @Test
    fun `retention decays as the due date passes`() {
        val card = CardState(questionId = "q", intervalDays = 5, dueAt = now, reps = 2)
        val late = sched.retention(card, now + 5 * day)
        assertTrue(late < 1.0)
        assertTrue(late in 0.0..1.0)
    }

    @Test
    fun `retention is zero for a card still learning`() {
        assertEquals(0.0, sched.retention(sched.initial("q"), now), 0.0001)
        val relearning = sched.next(
            graduate(listOf(ReviewGrade.GOOD)),
            ReviewGrade.AGAIN,
            now
        )
        assertEquals(0.0, sched.retention(relearning, now), 0.0001)
    }

    // --- reset ---

    @Test
    fun `reset returns a card to new and keeps only its id`() {
        val card = graduate(List(6) { ReviewGrade.EASY })
        val reset = sched.reset(card)
        assertTrue(reset.isNew)
        assertEquals(0, reset.reps)
        assertEquals(0, reset.lapses)
        assertFalse(reset.leech)
        assertEquals("q1", reset.questionId)
    }

    @Test
    fun `reset clears a leech`() {
        var card = sched.initial("q1")
        repeat(SchedulerConfig().leechThreshold) {
            card = sched.next(card, ReviewGrade.GOOD, now)
            card = sched.next(card, ReviewGrade.AGAIN, now)
        }
        assertTrue(card.leech)
        assertFalse(sched.reset(card).leech)
    }

    // --- configurable parameters ---
    // The point of SchedulerConfig is that a user can retune the scheduler, so
    // each test here changes one setting and asserts only that setting's effect.

    @Test
    fun `starting ease is configurable`() {
        val custom = Sm2Scheduler(SchedulerConfig(defaultEase = 1.8))
        assertEquals(1.8, custom.initial("q1").ease, 0.0001)
    }

    @Test
    fun `ease penalties are configurable`() {
        val custom = Sm2Scheduler(
            SchedulerConfig(againEaseFactor = 0.5, hardEaseFactor = 0.4, easyEaseFactor = 0.3)
        )
        val base = custom.initial("q1")
        assertEquals(2.0, custom.next(base, ReviewGrade.AGAIN, now).ease, 0.0001)
        assertEquals(2.1, custom.next(base, ReviewGrade.HARD, now).ease, 0.0001)
        assertEquals(2.8, custom.next(base, ReviewGrade.EASY, now).ease, 0.0001)
    }

    @Test
    fun `ease is clamped to the configured bounds`() {
        val custom = Sm2Scheduler(SchedulerConfig(minEase = 2.0, maxEase = 2.2))
        var card = custom.initial("q1")
        repeat(5) { card = custom.next(card, ReviewGrade.AGAIN, now) }
        assertEquals(2.0, card.ease, 0.0001)
        repeat(10) { card = custom.next(card, ReviewGrade.EASY, now) }
        assertEquals(2.2, card.ease, 0.0001)
    }

    @Test
    fun `graduating intervals are configurable`() {
        val custom = Sm2Scheduler(
            SchedulerConfig(
                firstIntervalDays = 2,
                secondIntervalDays = 11,
                easyFirstIntervalDays = 7
            )
        )
        assertEquals(2, custom.next(custom.initial("q"), ReviewGrade.GOOD, now).intervalDays)
        assertEquals(7, custom.next(custom.initial("q"), ReviewGrade.EASY, now).intervalDays)
        val second = custom.next(
            custom.next(custom.initial("q"), ReviewGrade.GOOD, now), ReviewGrade.GOOD, now
        )
        assertEquals(11, second.intervalDays)
    }

    @Test
    fun `relearn delay is configurable`() {
        val custom = Sm2Scheduler(SchedulerConfig(relearnMs = 5 * 60 * 1000L))
        val graduated = custom.next(
            custom.next(custom.initial("q"), ReviewGrade.GOOD, now), ReviewGrade.GOOD, now
        )
        val failed = custom.next(graduated, ReviewGrade.AGAIN, now)
        assertEquals(now + 5 * 60 * 1000L, failed.dueAt)
    }

    @Test
    fun `leech threshold is configurable`() {
        val custom = Sm2Scheduler(SchedulerConfig(leechThreshold = 2))
        var card = custom.initial("q1")
        repeat(2) {
            card = custom.next(card, ReviewGrade.GOOD, now)
            card = custom.next(card, ReviewGrade.AGAIN, now)
        }
        assertTrue(card.leech)
    }

    @Test
    fun `interval is clamped to the configured maximum`() {
        val custom = Sm2Scheduler(SchedulerConfig(maxIntervalDays = 10, minimumIntervalDays = 1))
        var card = custom.initial("q1")
        repeat(8) { card = custom.next(card, ReviewGrade.EASY, now) }
        assertEquals(10, card.intervalDays)
    }

    @Test
    fun `interval is clamped to the configured minimum`() {
        val custom = Sm2Scheduler(SchedulerConfig(minimumIntervalDays = 5))
        var card = custom.initial("q1")
        repeat(6) { card = custom.next(card, ReviewGrade.GOOD, now) }
        card = custom.next(card, ReviewGrade.GOOD, now)
        // A minimum above the natural interval must win over interval + 1.
        assertTrue(card.intervalDays >= 5)
    }

    @Test
    fun `hard multiplier is configurable`() {
        val gentle = Sm2Scheduler(SchedulerConfig(hardIntervalMultiplier = 1.2))
        val aggressive = Sm2Scheduler(SchedulerConfig(hardIntervalMultiplier = 3.0))
        val base = gentle.next(gentle.next(gentle.initial("q"), ReviewGrade.GOOD, now), ReviewGrade.GOOD, now)
        val gentleNext = gentle.next(base, ReviewGrade.HARD, now).intervalDays
        val aggressiveNext = aggressive.next(base, ReviewGrade.HARD, now).intervalDays
        assertTrue("3x should beat 1.2x", aggressiveNext > gentleNext)
    }

    @Test
    fun `easy bonus is configurable`() {
        val plain = Sm2Scheduler(SchedulerConfig(easyBonus = 1.0))
        val boosted = Sm2Scheduler(SchedulerConfig(easyBonus = 2.0))
        val base = plain.next(plain.next(plain.initial("q"), ReviewGrade.GOOD, now), ReviewGrade.GOOD, now)
        val plainNext = plain.next(base, ReviewGrade.EASY, now).intervalDays
        val boostedNext = boosted.next(base, ReviewGrade.EASY, now).intervalDays
        assertTrue("2x bonus should beat none", boostedNext > plainNext)
    }

    @Test
    fun `new limit is configurable through the queue`() {
        val config = SchedulerConfig(newLimit = 3)
        val custom = Sm2Scheduler(config)
        val ids = (1..10).map { "q$it" }
        val queue = Study.queue(custom, ids, emptyMap(), now, newLimit = config.newLimit)
        assertEquals(3, queue.size)
    }

    @Test
    fun `infer grade uses the configured time thresholds`() {
        val slow = SchedulerConfig(fastSeconds = 1, slowSeconds = 60)
        assertEquals(ReviewGrade.GOOD, Study.inferGrade(true, 30, config = slow))
        val default = SchedulerConfig()
        assertEquals(ReviewGrade.HARD, Study.inferGrade(true, 30, config = default))
    }

    // --- sanitization ---

    @Test
    fun `sanitized repairs values that would break scheduling`() {
        val broken = SchedulerConfig(
            minEase = 9.0,
            maxEase = 0.5,
            firstIntervalDays = 0,
            relearnMs = 1L,
            leechThreshold = 0,
            newLimit = -5,
            slowSeconds = 0
        ).sanitized()
        assertTrue(broken.minEase <= broken.maxEase)
        assertTrue(broken.firstIntervalDays >= 1)
        assertTrue(broken.relearnMs >= 60_000L)
        assertTrue(broken.leechThreshold >= 1)
        assertTrue(broken.newLimit >= 0)
        assertTrue(broken.slowSeconds >= broken.fastSeconds)
    }

    @Test
    fun `default config is already sanitized`() {
        assertEquals(SchedulerConfig(), SchedulerConfig().sanitized())
    }
}
