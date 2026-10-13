package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.Sm2Scheduler
import com.mcqapp.domain.Study
import com.mcqapp.domain.StudyMode
import com.mcqapp.domain.StudyReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedRepetitionTest {

    private val now = 1_700_000_000_000L
    private val day = Sm2Scheduler.DAY_MS
    private val config = SchedulerConfig()
    // The zone is pinned so the due instants asserted below are exact arithmetic
    // rather than a function of where the suite happens to run.
    private val sched = Sm2Scheduler(config, zoneOffset = { 0L })

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
        // Whole days are counted from the start of the study day, so this is no
        // longer exactly 24h after grading.
        assertEquals(DayBoundary.dueAfter(now, 1, zoneOffsetMillis = 0), card.dueAt)
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
        val pools = Study.pools(sched, listOf("q1"), mapOf("q1" to failed), now = now)
        assertTrue(pools.learning.isEmpty() && pools.reviews.isEmpty())
        assertNull(Study.selectNext(sched, listOf("q1"), mapOf("q1" to failed), now = now))
    }

    @Test
    fun `a relearning card comes back the same day once due`() {
        val failed = sched.next(
            graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD)), ReviewGrade.AGAIN, now
        )
        val later = now + SchedulerConfig().relearnMs
        val card = Study.selectNext(
            sched, listOf("q1"), mapOf("q1" to failed), now = later
        )
        assertEquals("q1", card?.questionId)
        assertEquals(StudyReason.LEARNING, card?.reason)
    }

    @Test
    fun `a due relearning step is served even with new cards switched off`() {
        val failed = sched.next(
            graduate(listOf(ReviewGrade.GOOD, ReviewGrade.GOOD)), ReviewGrade.AGAIN, now
        )
        val later = now + SchedulerConfig().relearnMs
        val states = mapOf(
            "q1" to failed,
            "q2" to sched.initial("q2"),
            "q3" to sched.initial("q3")
        )
        val card = Study.selectNext(
            sched, listOf("q1", "q2", "q3"), states, now = later,
            introduceNewCards = false
        )
        assertEquals("q1", card?.questionId)
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

    // --- the pull-based candidate pools ---

    @Test
    fun `pools are empty and the pull serves nothing for an empty paper`() {
        val pools = Study.pools(sched, emptyList(), emptyMap(), now)
        assertTrue(pools.learning.isEmpty() && pools.reviews.isEmpty() && pools.fresh.isEmpty())
        assertNull(Study.selectNext(sched, emptyList(), emptyMap(), now))
        assertEquals(0, Study.eligibility(sched, emptyList(), emptyMap(), now).total)
    }

    @Test
    fun `the pull serves a due review before a new card`() {
        // Due exactly at pull time: full recall, so the plain DUE reason. An
        // overdue card would already read as LOW_RECALL_RISK instead.
        val states = mapOf(
            "due1" to CardState(questionId = "due1", intervalDays = 3, dueAt = now, reps = 2)
        )
        val first = Study.selectNext(sched, listOf("new1", "due1"), states, now)
        assertEquals("due1", first?.questionId)
        assertEquals(StudyReason.DUE, first?.reason)
    }

    @Test
    fun `cards that are not yet due sit in no pool`() {
        val states = mapOf(
            "later" to CardState(questionId = "later", intervalDays = 3, dueAt = now + day, reps = 2)
        )
        val pools = Study.pools(sched, listOf("later"), states, now)
        assertTrue(pools.learning.isEmpty() && pools.reviews.isEmpty() && pools.fresh.isEmpty())
        assertNull(Study.selectNext(sched, listOf("later"), states, now))
    }

    @Test
    fun `reviews rank by recall risk, not by age alone`() {
        // A small-interval card four days overdue has lost more recall than a
        // long-interval card six days overdue, even though the long one became
        // due first. Risk, not age, is what the pull serves.
        val states = mapOf(
            "shallow" to CardState(
                questionId = "shallow", intervalDays = 2, dueAt = now - 4 * day, reps = 2
            ),
            "deep" to CardState(
                questionId = "deep", intervalDays = 30, dueAt = now - 6 * day, reps = 5
            )
        )
        val pools = Study.pools(sched, listOf("deep", "shallow"), states, now)
        assertEquals(listOf("shallow", "deep"), pools.reviews.map { it.questionId })
    }

    @Test
    fun `equal recall risk breaks the tie by oldest due date`() {
        // Both cards sit at exactly the same predicted recall (overdue divided
        // by interval is equal), so the one that has waited longer goes first.
        val states = mapOf(
            "a" to CardState(questionId = "a", intervalDays = 2, dueAt = now - 4 * day, reps = 2),
            "b" to CardState(questionId = "b", intervalDays = 4, dueAt = now - 8 * day, reps = 2)
        )
        val pools = Study.pools(sched, listOf("a", "b"), states, now)
        assertEquals(listOf("b", "a"), pools.reviews.map { it.questionId })
    }

    @Test
    fun `a due leech is an ordinary review plus the drill filter`() {
        val leech = CardState(
            questionId = "leech1", intervalDays = 2, dueAt = now - day,
            reps = 3, lapses = 8, leech = true
        )
        val pools = Study.pools(sched, listOf("leech1"), mapOf("leech1" to leech), now)
        // In the review pool with everyone else — no separate stream, no cap.
        assertEquals("leech1", pools.reviews.single().questionId)
        assertEquals(listOf("leech1"), Study.onlyLeeches(pools.reviews).map { it.questionId })
    }

    @Test
    fun `the pull order is learning, then risk-ranked reviews, then new cards`() {
        val states = mapOf(
            "learn" to CardState(
                questionId = "learn", intervalDays = 0, dueAt = now - 60_000L,
                reps = 1, state = CardStateKind.LEARNING
            ),
            "due" to CardState(questionId = "due", intervalDays = 2, dueAt = now - day, reps = 2)
        )
        val ids = listOf("new1", "due", "learn")
        assertEquals("learn", Study.selectNext(sched, ids, states, now)?.questionId)
        val pools = Study.pools(sched, ids, states, now)
        assertEquals(
            listOf("learn", "due", "new1"),
            (pools.learning + pools.reviews + pools.fresh).map { it.questionId }
        )
    }

    @Test
    fun `memory first serves new cards only once nothing is due`() {
        val ids = listOf("new1", "due1")
        val withDue = mapOf(
            "due1" to CardState(questionId = "due1", intervalDays = 2, dueAt = now - day, reps = 2)
        )
        assertEquals("due1", Study.selectNext(sched, ids, withDue, now)?.questionId)
        // Backlog cleared (the due card moved well past this sitting): the
        // new stream is next.
        val cleared = mapOf(
            "due1" to CardState(questionId = "due1", intervalDays = 2, dueAt = now + 5 * day, reps = 2)
        )
        assertEquals("new1", Study.selectNext(sched, ids, cleared, now + 3 * day)?.questionId)
    }

    @Test
    fun `explore mode surfaces a new card every few reviews`() {
        val ids = listOf("new1", "new2", "due1", "due2", "due3", "due4")
        val states = listOf("due1", "due2", "due3", "due4").associateWith {
            CardState(questionId = it, intervalDays = 2, dueAt = now - day, reps = 2)
        }
        // Below the cadence, the review backlog wins.
        assertEquals(
            "due1",
            Study.selectNext(
                sched, ids, states, now,
                studyMode = StudyMode.EXPLORE_NEW, reviewsSinceNew = 2
            )?.questionId
        )
        // Cadence reached: the new card jumps ahead of the backlog.
        assertEquals(
            "new1",
            Study.selectNext(
                sched, ids, states, now,
                studyMode = StudyMode.EXPLORE_NEW,
                reviewsSinceNew = Study.EXPLORE_NEW_EVERY
            )?.questionId
        )
        // Memory first ignores the counter entirely.
        assertEquals(
            "due1",
            Study.selectNext(
                sched, ids, states, now,
                studyMode = StudyMode.MEMORY_FIRST, reviewsSinceNew = 99
            )?.questionId
        )
    }

    @Test
    fun `introducing new cards can be switched off`() {
        val ids = listOf("new1", "due1")
        val states = mapOf(
            "due1" to CardState(questionId = "due1", intervalDays = 2, dueAt = now + day, reps = 2)
        )
        val pools = Study.pools(sched, ids, states, now, introduceNewCards = false)
        assertTrue(pools.fresh.isEmpty())
        assertNull(Study.selectNext(sched, ids, states, now, introduceNewCards = false))
        // With the toggle on, the same paper offers the new card once the
        // review is not yet due.
        assertEquals("new1", Study.selectNext(sched, ids, states, now)?.questionId)
    }

    @Test
    fun `eligibility counts what the pools serve`() {
        val states = mapOf(
            "learn" to CardState(
                questionId = "learn", intervalDays = 0, dueAt = now - 60_000L,
                reps = 1, state = CardStateKind.RELEARNING
            ),
            "due" to CardState(questionId = "due", intervalDays = 2, dueAt = now - day, reps = 2),
            "leech" to CardState(
                questionId = "leech", intervalDays = 2, dueAt = now - day,
                reps = 2, lapses = 8, leech = true
            ),
            "future" to CardState(questionId = "future", intervalDays = 2, dueAt = now + day, reps = 2)
        )
        val e = Study.eligibility(sched, states.keys.toList() + "missing", states, now)
        assertEquals(1, e.learning)
        assertEquals(1, e.due)
        assertEquals(1, e.leeches)
        assertEquals(1, e.fresh)
        // The badge's contract: every pool member is counted exactly once.
        val p = Study.pools(sched, states.keys.toList() + "missing", states, now)
        assertEquals(p.learning.size + p.reviews.size + p.fresh.size, e.total)
    }

    @Test
    fun `only leeches that are due are tricky`() {
        // A leech that is not due today is not in any pool, so counting it in
        // the badge would promise a card the drill cannot serve.
        val states = mapOf(
            "a" to CardState(
                questionId = "a", leech = true, intervalDays = 2, dueAt = now - day, reps = 2
            ),
            "b" to CardState(
                questionId = "b", leech = true, intervalDays = 2, dueAt = now + day, reps = 2
            )
        )
        assertEquals(1, Study.eligibility(sched, states.keys.toList(), states, now).leeches)
    }

    @Test
    fun `a leech that lapsed back into learning is still drilled`() {
        val states = mapOf(
            "relearn" to CardState(
                questionId = "relearn", intervalDays = 0, dueAt = now - 60_000L,
                reps = 3, lapses = 8, leech = true, state = CardStateKind.RELEARNING
            )
        )
        val e = Study.eligibility(sched, listOf("relearn"), states, now)
        assertEquals(1, e.leeches)
        assertEquals(0, e.learning)
        // And the drill filter over the whole pull order finds it.
        val pools = Study.pools(sched, listOf("relearn"), states, now)
        assertEquals(
            listOf("relearn"),
            Study.onlyLeeches(pools.learning + pools.reviews).map { it.questionId }
        )
    }

    @Test
    fun `pool order is stable across repeated calls`() {
        val ids = (1..10).map { "q$it" }
        val states = mapOf(
            "q3" to CardState(questionId = "q3", intervalDays = 2, dueAt = now - day, reps = 1)
        )
        val first = Study.pools(sched, ids, states, now)
        val second = Study.pools(sched, ids, states, now)
        assertEquals(
            (first.learning + first.reviews + first.fresh).map { it.questionId },
            (second.learning + second.reviews + second.fresh).map { it.questionId }
        )
    }

    // --- automatic grading ---
    //
    // Simplified mode grades from the answer instead of asking. Correctness is
    // exact match on the key; the dwell ladder supplies the difficulty axis;
    // a declared guess bypasses the ladder because deliberation time on a
    // random pick carries no information.

    private val autoConfig = SchedulerConfig()

    @Test
    fun `a quick correct answer is Easy`() {
        assertEquals(
            ReviewGrade.EASY,
            Study.autoGrade(setOf("a"), setOf("a"), 3, isGuess = false, config = autoConfig)
        )
    }

    @Test
    fun `the fast boundary is inclusive below and exclusive at`() {
        // fastSeconds = 8: dwell in 1 until 8 reads as effortless.
        assertEquals(
            ReviewGrade.EASY,
            Study.autoGrade(setOf("a"), setOf("a"), 1, isGuess = false, config = autoConfig)
        )
        assertEquals(
            ReviewGrade.EASY,
            Study.autoGrade(setOf("a"), setOf("a"), 7, isGuess = false, config = autoConfig)
        )
        assertEquals(
            ReviewGrade.GOOD,
            Study.autoGrade(setOf("a"), setOf("a"), 8, isGuess = false, config = autoConfig)
        )
    }

    @Test
    fun `a medium correct answer is Good`() {
        assertEquals(
            ReviewGrade.GOOD,
            Study.autoGrade(setOf("a"), setOf("a"), 15, isGuess = false, config = autoConfig)
        )
        assertEquals(
            ReviewGrade.GOOD,
            Study.autoGrade(setOf("a"), setOf("a"), 29, isGuess = false, config = autoConfig)
        )
    }

    @Test
    fun `a slow correct answer is Hard`() {
        assertEquals(
            ReviewGrade.HARD,
            Study.autoGrade(setOf("a"), setOf("a"), 30, isGuess = false, config = autoConfig)
        )
        assertEquals(
            ReviewGrade.HARD,
            Study.autoGrade(setOf("a"), setOf("a"), 120, isGuess = false, config = autoConfig)
        )
    }

    @Test
    fun `a correct guess is Hard at any dwell`() {
        listOf(0L, 1L, 7L, 15L, 120L).forEach { dwell ->
            assertEquals(
                "a guessed answer must not ride the dwell ladder (dwell=$dwell)",
                ReviewGrade.HARD,
                Study.autoGrade(setOf("a"), setOf("a"), dwell, isGuess = true, config = autoConfig)
            )
        }
    }

    @Test
    fun `a wrong answer is Again whether guessed or not`() {
        assertEquals(
            ReviewGrade.AGAIN,
            Study.autoGrade(setOf("a"), setOf("b"), 3, isGuess = false, config = autoConfig)
        )
        assertEquals(
            ReviewGrade.AGAIN,
            Study.autoGrade(setOf("a"), setOf("b"), 3, isGuess = true, config = autoConfig)
        )
    }

    @Test
    fun `an empty selection is a skip`() {
        assertEquals(
            ReviewGrade.AGAIN,
            Study.autoGrade(setOf("a"), emptySet(), 10, isGuess = false, config = autoConfig)
        )
        assertEquals(
            ReviewGrade.AGAIN,
            Study.autoGrade(setOf("a"), emptySet(), 10, isGuess = true, config = autoConfig)
        )
    }

    @Test
    fun `a multi-correct answer needs the exact key`() {
        val key = setOf("a", "c")
        assertEquals(
            ReviewGrade.GOOD,
            Study.autoGrade(key, setOf("a", "c"), 15, isGuess = false, config = autoConfig)
        )
        // A subset is not "almost right" under exam marking; it is wrong.
        assertEquals(
            ReviewGrade.AGAIN,
            Study.autoGrade(key, setOf("a"), 15, isGuess = false, config = autoConfig)
        )
        // Nor is a superset that happens to contain the key.
        assertEquals(
            ReviewGrade.AGAIN,
            Study.autoGrade(key, setOf("a", "b", "c"), 15, isGuess = false, config = autoConfig)
        )
    }

    @Test
    fun `a correct guess on a multi-correct key is still Hard`() {
        assertEquals(
            ReviewGrade.HARD,
            Study.autoGrade(
                setOf("a", "c"), setOf("a", "c"), 2, isGuess = true, config = autoConfig
            )
        )
    }

    // --- badge / eligibility agreement ---
    //
    // The library badge and the pull read the same pools, so the badge can
    // never advertise a card the session refuses to serve. These pin the two
    // together across mixed papers.

    private fun poolCases(): List<Pair<List<String>, Map<String, CardState>>> {
        val due = (1..8).map { "d$it" }
        val leeches = (1..3).map { "l$it" }
        val states = mutableMapOf<String, CardState>()
        due.forEachIndexed { i, id ->
            states[id] = CardState(
                questionId = id, intervalDays = 2, dueAt = now - day * (i + 1), reps = 2
            )
        }
        leeches.forEach { id ->
            states[id] = CardState(
                questionId = id, intervalDays = 2, dueAt = now - day, reps = 2, leech = true
            )
        }
        val fresh = (1..12).map { "n$it" }
        return listOf(
            Pair(due + leeches + fresh, states),
            Pair(due, states),
            Pair(fresh, emptyMap()),
            Pair(emptyList(), emptyMap())
        )
    }

    @Test
    fun `the eligibility total always equals the pools size`() {
        poolCases().forEach { (ids, states) ->
            listOf(true, false).forEach { introduce ->
                val pools = Study.pools(sched, ids, states, now, introduce)
                val e = Study.eligibility(sched, ids, states, now, introduce)
                assertEquals(
                    "badge/queue mismatch for introduceNewCards=$introduce",
                    pools.learning.size + pools.reviews.size + pools.fresh.size,
                    e.total
                )
                assertEquals(pools.fresh.size, e.fresh)
            }
        }
    }

    @Test
    fun `every question is either eligible or not, nothing in between`() {
        // No caps and no waiting list: a question is either served by the pull
        // or never offered, and the badge counts exactly the served set.
        poolCases().forEach { (ids, states) ->
            val e = Study.eligibility(sched, ids, states, now)
            val expected = ids.count { id ->
                val s = states[id]
                s == null || s.isNew || s.dueAt <= now
            }
            assertEquals("eligible count for ${ids.size} questions", expected, e.total)
        }
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
            desiredRetention = 0.5,
            slowSeconds = 0
        ).sanitized()
        assertTrue(broken.minEase <= broken.maxEase)
        assertTrue(broken.firstIntervalDays >= 1)
        assertTrue(broken.relearnMs >= 60_000L)
        assertTrue(broken.leechThreshold >= 1)
        assertTrue(broken.desiredRetention >= 0.75)
        assertTrue(broken.slowSeconds >= broken.fastSeconds)
    }

    @Test
    fun `default config is already sanitized`() {
        assertEquals(SchedulerConfig(), SchedulerConfig().sanitized())
    }

}
