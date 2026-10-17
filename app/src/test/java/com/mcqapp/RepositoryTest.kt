package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository behavior against a real (in-memory) Room database under
 * Robolectric: grading weights, bookmark persistence and bulk duplication.
 * Pure scoring math lives in ScoringTest; this pins the DB wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun teardown() = runBlocking {
        // Robolectric's DataStore delegate is one JVM-wide singleton with a
        // file pinned to the first accessor: scheduler settings written here
        // would leak into every later test process in the run. Reset before
        // the database closes so test order cannot change what a pull serves.
        repository.resetSchedulerConfig()
        db.close()
    }

    private fun question(
        id: String,
        correct: Set<String>,
        marks: Double = 1.0,
        categoryId: String = "c1"
    ) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = correct,
        marks = marks
    )

    @Test
    fun bookmarkTogglePersists() = runBlocking {
        assertFalse(repository.isBookmarked("q1"))
        repository.toggleBookmark("q1")
        assertTrue(repository.isBookmarked("q1"))
        repository.toggleBookmark("q1")
        assertFalse(repository.isBookmarked("q1"))
    }

    @Test
    fun streamedSaveReportsProgressUpToTheTotal() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        val ids = (1..5).map { "q$it" }
        ids.forEach { repository.saveQuestion(question(it, setOf("$it-a"))) }
        val progress = mutableListOf<Pair<Int, Int>>()
        repository.saveAttemptStreamed(
            paperId = "p1",
            paperTitle = "Paper",
            questionIdsInOrder = ids,
            selections = ids.associateWith { setOf("$it-a") },
            negativeMarking = 0.0,
            durationSeconds = 10,
            finishedAt = System.currentTimeMillis(),
            onProgress = { stored, total -> progress += stored to total }
        )
        // One batch here (BATCH = 500): a single report, already at the total.
        assertEquals(listOf(ids.size to ids.size), progress)
    }

    @Test
    fun saveAttemptWeightsMarksAndExcludesUngraded() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a"), marks = 2.0))
        repository.saveQuestion(question("q2", setOf("q2-a"), marks = 3.0))
        repository.saveQuestion(question("q3", emptySet(), marks = 5.0))
        val questions = repository.getQuestionsForPaper("p1")
        assertEquals(3, questions.size)
        val attemptId = repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = mapOf("q1" to setOf("q1-a"), "q2" to setOf("nope"), "q3" to setOf("x")),
            negativeMarking = 0.25,
            durationSeconds = 60,
            finishedAt = System.currentTimeMillis(),
            dwellSeconds = mapOf("q1" to 30L, "q2" to 90L)
        )
        val attempt = repository.getAttempt(attemptId)!!
        assertEquals(1, attempt.correctCount)
        assertEquals(1, attempt.wrongCount)
        assertEquals(0, attempt.skippedCount)
        assertEquals(2.0 - 0.75, attempt.score, 0.0001)
        assertEquals(5.0, attempt.maxScore, 0.0001)
        val results = repository.getAttemptResults(attemptId)
        assertEquals(3, results.size)
        val dwellById = results.associate { it.questionId to it.dwellSeconds }
        assertEquals(30L, dwellById["q1"])
        assertEquals(90L, dwellById["q2"])
        assertEquals(0L, dwellById["q3"])
        // The ungraded row stores the same single isCorrect computation the
        // counters use: false, matching its exclusion from the score above.
        val correctById = results.associate { it.questionId to it.isCorrect }
        assertEquals(true, correctById["q1"])
        assertEquals(false, correctById["q2"])
        assertEquals(false, correctById["q3"])
    }

    @Test
    fun duplicateQuestionCopiesContentWithFreshId() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a"), marks = 2.0))
        val copyId = repository.duplicateQuestion("q1")
        assertEquals("q1-copy", copyId)
        val copy = repository.getQuestion(copyId!!)!!
        assertEquals("Q q1", copy.text)
        assertEquals(setOf("q1-a"), copy.correctOptionIds)
        assertEquals(2.0, copy.marks, 0.0001)
        assertEquals(2, repository.getQuestionsForPaper("p1").size)
    }

    // --- spaced repetition ---

    @Test
    fun studyQueueIsAllNewForAFreshPaper() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        val queue = repository.getStudyQueue("p1")
        assertEquals(2, queue.size)
        assertTrue(queue.all { it.reason == com.mcqapp.domain.StudyReason.NEW })
    }

    @Test
    fun studyQueueIsEmptyForAnUnknownPaper() = runBlocking {
        assertTrue(repository.getStudyQueue("nope").isEmpty())
    }

    @Test
    fun getNextStudyCardServesTheDueCardBeforeTheNewOne() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        // Before q1's interval has passed, the new stream is all there is.
        val fresh = repository.getNextStudyCard("p1", now = 2000L)
        assertEquals("q2", fresh?.questionId)
        assertEquals(com.mcqapp.domain.StudyReason.NEW, fresh?.reason)
        // Once q1 is overdue it wins, and its risk is visible in the reason.
        val overdue = repository.getNextStudyCard("p1", now = 90L * 86_400_000L)
        assertEquals("q1", overdue?.questionId)
        assertEquals(com.mcqapp.domain.StudyReason.LOW_RECALL_RISK, overdue?.reason)
    }

    @Test
    fun midSessionModeSwitchChangesTheNextPull() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q0", setOf("q0-a")))
        repository.saveQuestion(question("q1", setOf("q1-a")))
        // q0 graduated and then came due; q1 has never been studied.
        repository.recordStudyReview("p1", "q0", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        val later = 90L * 86_400_000L

        // Memory-first: the due card wins however far into the session we are.
        repository.setSchedulerConfig(
            repository.schedulerConfigNow().copy(studyMode = com.mcqapp.domain.StudyMode.MEMORY_FIRST)
        )
        assertEquals("q0", repository.getNextStudyCard("p1", reviewsSinceNew = 99, now = later)?.questionId)

        // Explore mode surfaces new material ahead of that same backlog.
        repository.setSchedulerConfig(
            repository.schedulerConfigNow().copy(studyMode = com.mcqapp.domain.StudyMode.EXPLORE_NEW)
        )
        val explored = repository.getNextStudyCard(
            "p1", reviewsSinceNew = com.mcqapp.domain.Study.EXPLORE_NEW_EVERY, now = later
        )
        assertEquals("q1", explored?.questionId)
        assertEquals(com.mcqapp.domain.StudyReason.NEW, explored?.reason)
        // Below its pacing interval, explore still serves the backlog first —
        // the switch paces new cards, it does not reorder memory work away.
        assertEquals("q0", repository.getNextStudyCard("p1", reviewsSinceNew = 0, now = later)?.questionId)
    }

    @Test
    fun thePullClearsTheBacklogBeforeNewMaterialThenServesIt() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q0", setOf("q0-a")))
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.recordStudyReview("p1", "q0", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        val later = 90L * 86_400_000L
        assertEquals("q0", repository.getNextStudyCard("p1", now = later)?.questionId)
        // Grading the overdue card pushes its interval far past the sitting, so
        // with the backlog cleared the session goes and gets the new card.
        repository.recordStudyReview("p1", "q0", com.mcqapp.domain.ReviewGrade.GOOD, now = later)
        val fresh = repository.getNextStudyCard("p1", now = later + 60_000L)
        assertEquals("q1", fresh?.questionId)
        assertEquals(com.mcqapp.domain.StudyReason.NEW, fresh?.reason)
    }

    @Test
    fun switchingNewCardsOffMakesTheSessionReviewOnly() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q0", setOf("q0-a")))
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        repository.recordStudyReview("p1", "q0", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        val later = 90L * 86_400_000L
        repository.setSchedulerConfig(
            repository.schedulerConfigNow().copy(introduceNewCards = false)
        )
        // The overload serves the due backlog and nothing else.
        assertEquals("q0", repository.getNextStudyCard("p1", now = later)?.questionId)
        // Once the backlog is cleared there is nothing at all — no new cards
        // are smuggled in by the pull.
        repository.recordStudyReview("p1", "q0", com.mcqapp.domain.ReviewGrade.GOOD, now = later)
        val drained = later + 60_000L
        assertNull(repository.getNextStudyCard("p1", now = drained))
        assertEquals(0, repository.getStudyEligibility("p1", now = drained).fresh)
        // But the empty state asks with the toggle overridden on, so it can
        // offer exactly the flip that would unstick the session.
        val asIfOn = repository.getStudyEligibility("p1", now = drained, introduceNewCards = true)
        assertEquals(2, asIfOn.fresh)
        // The badge keeps honoring the toggle: no phantom "new" to click.
        assertEquals(0, repository.getStudyCounts("p1", now = drained).fresh)
    }

    @Test
    fun theReviewLogKeepsOrderedHistoryAcrossSittings() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        val day = 86_400_000L
        // First exposure, two later good recalls, then a miss a sitting apart.
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, dwellSeconds = 12L, now = 1000L)
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L + 40 * day)
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.AGAIN, now = 1000L + 80 * day)

        val history = db.reviewLogDao().historyForQuestion("q1")
        assertEquals(3, history.size)
        assertEquals(
            listOf(1000L, 1000L + 40 * day, 1000L + 80 * day),
            history.map { it.reviewedAt }
        )
        assertEquals(3, history[0].rating)
        assertEquals(3, history[1].rating)
        assertEquals(1, history[2].rating)
        // The numbers the optimizer will need are captured per row: success
        // grew stability, the lapse collapsed it, and the before-state is the
        // graduated REVIEW kind, which is what makes it a lapse at all.
        assertTrue(history[1].stabilityAfter!! > history[0].stabilityAfter!!)
        assertTrue(history[2].stabilityAfter!! < history[1].stabilityAfter!!)
        assertEquals("REVIEW", history[2].stateBefore)
        assertEquals(com.mcqapp.domain.CardStateKind.RELEARNING, db.cardStateDao().get("p1", "q1")!!.cardState)
        // Append-only: the earlier rows still describe the same reviews.
        assertEquals(3, history[1].rating)
        assertEquals(3, db.reviewLogDao().countByPaper("p1"))
    }

    /**
     * The optimizer's unlock gauge reads the same append-only log: every
     * graded review across every paper counts, new exposures included, and
     * the flow reports the live total.
     */
    @Test
    fun theOptimizerGaugeCountsEveryLoggedReview() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        assertEquals(0, repository.fsrsReviewCount().first())

        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        repository.recordStudyReview("p1", "q2", com.mcqapp.domain.ReviewGrade.AGAIN, now = 2000L)
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 3000L)
        assertEquals(3, repository.fsrsReviewCount().first())
    }

    @Test
    fun theBadgeAndThePullReadTheSameEligibility() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repeat(4) { repository.saveQuestion(question("q$it", setOf("q$it-a"))) }
        // q0 becomes a due review, q1 a due learning step, q2/q3 stay new.
        repository.recordStudyReview("p1", "q0", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.AGAIN, now = 1000L)
        val later = 90L * 86_400_000L
        val eligibility = repository.getStudyEligibility("p1", now = later)
        val queue = repository.getStudyQueue("p1", now = later)
        // Every counted card is served, in the same decomposition.
        assertEquals(eligibility.total, queue.size)
        assertEquals(eligibility.learning, queue.count { it.reason == com.mcqapp.domain.StudyReason.LEARNING })
        assertEquals(eligibility.fresh, queue.count { it.reason == com.mcqapp.domain.StudyReason.NEW })
        // And the pull starts where the badge says the work is heaviest: the
        // learning step due right now.
        assertEquals(queue.first().questionId, repository.getNextStudyCard("p1", now = later)?.questionId)
        val counts = repository.getStudyCounts("p1", now = later)
        assertEquals(eligibility.learning + eligibility.due, counts.due)
        assertEquals(eligibility.fresh, counts.fresh)
    }

    @Test
    fun reviewingAQuestionPersistsItsSchedule() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        val next = repository.recordStudyReview(
            "p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, dwellSeconds = 5L, now = 1000L
        )
        assertEquals(1, next.reps)
        // FSRS S₀(Good) = 2.31 days at the default 0.9 retention → 2 days.
        assertEquals(2, next.intervalDays)
        assertEquals(com.mcqapp.domain.DayBoundary.dueAfter(1000L, 2, zoneOffsetMillis = com.mcqapp.domain.DayBoundary.zoneOffset(1000L)), next.dueAt)
        val stored = db.cardStateDao().get("p1", "q1")
        assertEquals(1, stored!!.reps)
        assertEquals(1, stored.reviewCount)
        assertEquals(com.mcqapp.domain.CardStateKind.REVIEW, stored.cardState)
        assertTrue(stored.stability > 0.0)
        // ease mirrors difficulty onto SM-2's scale for export — it is not
        // SM-2's default any more, and must equal what FSRS derives.
        val config = repository.schedulerConfigNow()
        val expectedEase = com.mcqapp.domain.FsrsScheduler(
            desiredRetention = config.desiredRetention,
            config = config
        ).next(
            com.mcqapp.domain.CardState("q1"), com.mcqapp.domain.ReviewGrade.GOOD, 1000L
        ).ease
        assertEquals(expectedEase, stored.ease, 0.0001)

        // The review is logged in the same write: schedule and history can
        // never disagree.
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 2000L)
        val logs = db.reviewLogDao().historyForQuestion("q1")
        assertEquals(2, logs.size)
        val first = logs.first()
        assertEquals(3, first.rating)
        assertEquals(1000L, first.reviewedAt)
        assertEquals(5L, first.elapsedSeconds!!)
        assertEquals(2, first.intervalDaysAfter!!)
        assertEquals("no state exists before a first review", null, first.stateBefore)
        assertEquals("REVIEW", logs[1].stateBefore)
    }

    @Test
    fun deletingAQuestionRemovesItsLoggedReviews() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        assertEquals(1, db.reviewLogDao().countByPaper("p1"))

        repository.deleteQuestion("q1")
        // The log has no FK to questions, so it is swept explicitly: history
        // must not outlive the card it describes, or an id reuse would
        // inherit a stranger's reviews.
        assertTrue(db.reviewLogDao().historyForQuestion("q1").isEmpty())
        assertEquals(0, db.reviewLogDao().countByPaper("p1"))
    }

    @Test
    fun bulkDeletingQuestionsRemovesTheirLoggedReviews() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        repository.recordStudyReview("p1", "q2", com.mcqapp.domain.ReviewGrade.HARD, now = 1000L)
        assertEquals(2, db.reviewLogDao().countByPaper("p1"))

        repository.deleteQuestions(listOf("q1", "q2"))
        assertTrue(db.reviewLogDao().historyForQuestion("q1").isEmpty())
        assertTrue(db.reviewLogDao().historyForQuestion("q2").isEmpty())
        assertEquals(0, db.reviewLogDao().countByPaper("p1"))
    }

    @Test
    fun movingAQuestionAcrossPapersResetsItsHistoryHereToo() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper 1", "c1", "Cat 1")
        db.paperDao().upsert(
            com.mcqapp.data.local.PaperEntity(id = "p2", title = "Paper 2", createdAt = 1L)
        )
        db.categoryDao().upsert(
            com.mcqapp.data.local.CategoryEntity(id = "c2", paperId = "p2", title = "Cat 2")
        )
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        assertEquals(1, db.reviewLogDao().countByPaper("p1"))

        // The move resets the schedule (card_state goes with the old paper);
        // the rows that described it go with it, not into "p2"'s log.
        repository.moveQuestionsToCategory(listOf("q1"), "c2")
        assertTrue(db.reviewLogDao().historyForQuestion("q1").isEmpty())
        assertEquals(0, db.reviewLogDao().countByPaper("p1"))
        assertEquals(0, db.reviewLogDao().countByPaper("p2"))
    }

    @Test
    fun theButtonPreviewMatchesWhatGradingPersists() = runBlocking {
        // The answer buttons advertise a delay per grade. If that came from any
        // other arithmetic than the grading path it would be a lie the learner
        // acts on, so every grade is checked against the persisted schedule.
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        val schedulerConfig = repository.schedulerConfigNow()
        val scheduler = com.mcqapp.domain.FsrsScheduler(
            desiredRetention = schedulerConfig.desiredRetention,
            config = schedulerConfig
        )
        val gradedAt = 1_000_000L
        val grades = listOf(
            com.mcqapp.domain.ReviewGrade.AGAIN,
            com.mcqapp.domain.ReviewGrade.HARD,
            com.mcqapp.domain.ReviewGrade.GOOD,
            com.mcqapp.domain.ReviewGrade.EASY
        )
        grades.forEachIndexed { index, grade ->
            val id = "q$index"
            repository.saveQuestion(question(id, setOf("$id-a")))
            val before = com.mcqapp.domain.CardState(id)
            val preview = com.mcqapp.domain.Study.previewDelays(scheduler, before, gradedAt)
            val persisted = repository.recordStudyReview("p1", id, grade, now = gradedAt)
            assertEquals(
                "$grade preview must match the persisted schedule",
                persisted.dueAt - gradedAt,
                preview[grade]
            )
            assertTrue("$grade must have a preview", (preview[grade] ?: 0L) > 0L)
        }
    }

    @Test
    fun theQueueServesEveryEligibleCardWithoutCaps() = runBlocking {
        // Daily limits are gone: the badge and the queue both describe every
        // eligible card, so nothing is withheld and nothing waits behind a cap.
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repeat(7) { repository.saveQuestion(question("q$it", setOf("q$it-a"))) }

        val fresh = repository.getStudyQueue("p1", now = 1_000_000L)
        assertEquals(7, fresh.size)
        assertTrue(fresh.all { it.reason == com.mcqapp.domain.StudyReason.NEW })

        // Six reviewed, so six become due; the seventh stays new. Both
        // streams are served in full once the due ones come due.
        repeat(6) { index ->
            repository.recordStudyReview(
                "p1", "q$index", com.mcqapp.domain.ReviewGrade.GOOD, now = 1_000_000L
            )
        }
        val later = 90L * 86_400_000L
        val queue = repository.getStudyQueue("p1", now = later)
        // Ninety days overdue, every one of them is a likely-to-be-forgotten pull.
        assertEquals(6, queue.count { it.reason == com.mcqapp.domain.StudyReason.LOW_RECALL_RISK })
        assertEquals(1, queue.count { it.reason == com.mcqapp.domain.StudyReason.NEW })
        assertEquals(7, queue.size)
        // The badge agrees with that queue, with no waiting remainder.
        val counts = repository.getStudyCounts("p1", now = later)
        assertEquals(6, counts.due)
        assertEquals(1, counts.fresh)
        assertEquals(0, counts.leeches)
    }

    @Test
    fun aReviewedQuestionStopsBeingNew() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        val counts = repository.getStudyCounts("p1", now = 2000L)
        assertEquals(0, counts.due)
    }

    @Test
    fun badgeCountsAgreeWithWhatTheQueueOffers() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        // The badge and the queue read the same resolve path, so a review
        // recorded straight away must show up in both, before any session runs.
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 5000L)
        repository.recordStudyReview("p1", "q2", com.mcqapp.domain.ReviewGrade.AGAIN, now = 5000L)
        val counts = repository.getStudyCounts("p1", now = 6000L)
        // q1 graded correct -> scheduled into the future, so not new and not due.
        // q2 graded wrong -> relearning later today, so also not due yet.
        assertEquals(0, counts.due)
        assertEquals(0, counts.fresh)

        // Once the relearning window opens, the badge and the queue agree.
        val dueAt = 5000L + com.mcqapp.domain.SchedulerConfig().relearnMs
        val later = repository.getStudyCounts("p1", now = dueAt)
        assertEquals(1, later.due)
        val queue = repository.getStudyQueue("p1", now = dueAt)
        assertEquals(later.due, queue.count { it.reason != com.mcqapp.domain.StudyReason.NEW })
    }

    @Test
    fun aFailedReviewLearnsInPlaceWithoutLapsing() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        val again = repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.AGAIN, now = 1000L)
        assertEquals(0, again.intervalDays)
        assertEquals(0, again.lapses)
        assertEquals(com.mcqapp.domain.CardStateKind.LEARNING, again.state)
        // Due again minutes later, not as a day-scheduled card.
        val counts = repository.getStudyCounts("p1", now = 1000L)
        assertEquals(0, counts.due)
        // Even a failure is history.
        assertEquals(1, db.reviewLogDao().historyForQuestion("q1").size)
    }

    @Test
    fun editingAQuestionResetsItsSchedule() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 2000L)
        val before = db.cardStateDao().get("p1", "q1")!!
        assertTrue(before.reps >= 2)
        // Change the text, so the stored hash no longer matches.
        repository.saveQuestion(question("q1", setOf("q1-a")).copy(elements = listOf(com.mcqapp.domain.ContentElement.TextElement("Rewritten"))))
        repository.getStudyQueue("p1")
        val after = db.cardStateDao().get("p1", "q1")!!
        assertEquals(0, after.reps)
        assertEquals(0, after.lapses)
    }

    @Test
    fun `a rewritten question is reset once, not on every load`() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q1", setOf("q1-a")).copy(elements = listOf(com.mcqapp.domain.ContentElement.TextElement("Rewritten"))))
        repository.getStudyQueue("p1")
        // The reset must store the new hash, otherwise the next load sees a
        // stale hash again and wipes any progress made since.
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 5000L)
        val progress = db.cardStateDao().get("p1", "q1")!!
        assertTrue(progress.reps >= 1)
        repository.getStudyQueue("p1")
        assertEquals(progress, db.cardStateDao().get("p1", "q1"))
    }

    @Test
    fun attemptHistoryDoesNotSeedCards() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        val questions = repository.getQuestionsForPaper("p1")
        repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = mapOf("q1" to setOf("q1-a")),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = 5000L,
            dwellSeconds = mapOf("q1" to 15L)
        )
        // The FSRS switch wiped SM-2 schedules, and attempt history must not
        // resurrect them: its inferred grades were a guess at the learner's
        // memory, not a record of it. The card stays new until it is reviewed
        // under FSRS (or warmed by a JSON/Anki import).
        val card = repository.getStudyQueue("p1", now = 6000L).first()
        assertEquals(com.mcqapp.domain.StudyReason.NEW, card.reason)
        assertEquals(0, card.state.reps)
        assertEquals(0, card.state.reviewCount)
    }

    @Test
    fun skippedAnswersDoNotSeedMemory() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        val questions = repository.getQuestionsForPaper("p1")
        repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = emptyMap(),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = 5000L
        )
        val card = repository.getStudyQueue("p1", now = 6000L).first()
        assertEquals(com.mcqapp.domain.StudyReason.NEW, card.reason)
        assertEquals(0, card.state.reps)
    }

    @Test
    fun ungradedQuestionsDoNotSeedMemory() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", emptySet()))
        val questions = repository.getQuestionsForPaper("p1")
        repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = mapOf("q1" to setOf("q1-a")),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = 5000L
        )
        val card = repository.getStudyQueue("p1", now = 6000L).first()
        assertEquals(0, card.state.reps)
    }

    @Test
    fun historyFromAnotherPaperIsIgnored() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.ensurePaperAndCategory("p2", "Other", "c2", "Cat2")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        val questions = repository.getQuestionsForPaper("p2")
        repository.saveAttempt(
            paperId = "p2",
            paperTitle = "Other",
            questions = questions,
            selections = mapOf("q1" to setOf("q1-a")),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = 5000L
        )
        val card = repository.getStudyQueue("p1", now = 6000L).first()
        assertEquals(0, card.state.reps)
    }

    @Test
    fun bulkDeleteClearsSchedules() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        repository.recordStudyReview("p1", "q1", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        repository.recordStudyReview("p1", "q2", com.mcqapp.domain.ReviewGrade.GOOD, now = 1000L)
        repository.deleteQuestions(listOf("q1", "q2"))
        assertTrue(db.cardStateDao().getByPaper("p1").isEmpty())
    }

    @Test
    fun deletingAQuestionClearsItsAnswerKeyAndBookmark() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.toggleBookmark("q1")
        assertEquals(listOf("q1-a"), db.correctAnswerDao().getCorrectIds("q1"))
        repository.deleteQuestion("q1")
        assertTrue(db.correctAnswerDao().getCorrectIds("q1").isEmpty())
        assertTrue(db.bookmarkDao().getAll().isEmpty())
    }

    @Test
    fun deletingAPaperClearsItsQuestionsBookmarks() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.toggleBookmark("q1")
        repository.deletePaper("p1")
        assertTrue(db.correctAnswerDao().getCorrectIds("q1").isEmpty())
        assertTrue(db.bookmarkDao().getAll().isEmpty())
    }

    @Test
    fun deletingACategoryClearsItsQuestionsBookmarks() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.toggleBookmark("q1")
        repository.deleteCategory("c1")
        assertTrue(db.correctAnswerDao().getCorrectIds("q1").isEmpty())
        assertTrue(db.bookmarkDao().getAll().isEmpty())
    }

    @Test
    fun deletingACategoryPromotesItsChildrenInsteadOfOrphaningThem() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Algebra")
        db.categoryDao().upsert(CategoryEntity(id = "c2", paperId = "p1", title = "Quadratics", parentId = "c1"))
        db.categoryDao().upsert(CategoryEntity(id = "c3", paperId = "p1", title = "Polynomials", parentId = "c2"))
        repository.saveQuestion(question("q1", setOf("q1-a"), categoryId = "c1"))
        repository.saveQuestion(question("q2", setOf("q2-a"), categoryId = "c2"))
        repository.saveQuestion(question("q3", setOf("q3-a"), categoryId = "c3"))

        repository.deleteCategory("c1")

        // The deleted category and its own questions are gone.
        assertNull(db.categoryDao().getById("c1"))
        assertTrue(db.questionDao().getByCategory("c1").isEmpty())
        // The child moved up to the deleted node's parent (here: the root), so
        // it is reachable from the tree again rather than dangling.
        assertNull(db.categoryDao().getById("c2")?.parentId)
        // The grandchild keeps its own parent, and no question is stranded:
        // every surviving question still belongs to a category that exists.
        assertEquals("c2", db.categoryDao().getById("c3")?.parentId)
        val surviving = repository.getQuestionsForPaper("p1").map { it.categoryId }.toSet()
        val existing = db.categoryDao().getByPaper("p1").map { it.id }.toSet()
        assertEquals(existing, surviving)
        assertEquals(setOf("c2", "c3"), surviving)
    }

    @Test
    fun deletingANestedCategoryPromotesChildrenToItsParent() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Algebra")
        db.categoryDao().upsert(CategoryEntity(id = "c2", paperId = "p1", title = "Quadratics", parentId = "c1"))
        db.categoryDao().upsert(CategoryEntity(id = "c3", paperId = "p1", title = "Roots", parentId = "c2"))
        repository.saveQuestion(question("q3", setOf("q3-a"), categoryId = "c3"))

        repository.deleteCategory("c2")

        // Promotion targets the deleted node's own parent, not the root, so
        // the hierarchy is preserved instead of flattened.
        assertEquals("c1", db.categoryDao().getById("c3")?.parentId)
        assertEquals(setOf("c3"), repository.getQuestionsForPaper("p1").map { it.categoryId }.toSet())
    }

    @Test
    fun deletingACategoryKeepsChildBookmarks() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Algebra")
        db.categoryDao().upsert(CategoryEntity(id = "c2", paperId = "p1", title = "Quadratics", parentId = "c1"))
        repository.saveQuestion(question("q1", setOf("q1-a"), categoryId = "c1"))
        repository.saveQuestion(question("q2", setOf("q2-a"), categoryId = "c2"))
        repository.toggleBookmark("q1")
        repository.toggleBookmark("q2")

        repository.deleteCategory("c1")

        // Only the deleted category's own bookmark goes; the promoted child's
        // question and bookmark survive.
        assertEquals(listOf("q2"), db.bookmarkDao().getAll())
    }


    @Test
    fun movingAManuallyAddedCategoryUpReordersThePaper() = runBlocking {
        // addCategory leaves sortOrder at 0, so sibling order rests on the
        // (sortOrder, title) tiebreak — exactly the seeded shape the browse
        // UI test uses. The move must still rewrite the paper's order.
        repository.ensurePaperAndCategory("mp", "Move Paper", "mc1", "First Cat")
        val c2 = repository.addCategory("mp", "Second Cat", parentId = null)
        repository.saveQuestion(
            com.mcqapp.domain.Question(
                id = "mq1", categoryId = "mc1",
                elements = listOf(com.mcqapp.domain.ContentElement.TextElement("First?")),
                options = listOf(
                    com.mcqapp.domain.QuestionOption("a", "A"),
                    com.mcqapp.domain.QuestionOption("b", "B")
                ),
                correctOptionIds = setOf("a")
            )
        )
        repository.saveQuestion(
            com.mcqapp.domain.Question(
                id = "mq2", categoryId = c2,
                elements = listOf(com.mcqapp.domain.ContentElement.TextElement("Second?")),
                options = listOf(
                    com.mcqapp.domain.QuestionOption("a", "A"),
                    com.mcqapp.domain.QuestionOption("b", "B")
                ),
                correctOptionIds = setOf("a")
            )
        )
        assertEquals(listOf("mq1", "mq2"), repository.getQuestionsForPaper("mp").map { it.id })
        assertTrue(repository.moveCategory(c2, -1))
        assertEquals(listOf("mq2", "mq1"), repository.getQuestionsForPaper("mp").map { it.id })
        assertTrue(repository.moveCategory(c2, +1))
        assertEquals(listOf("mq1", "mq2"), repository.getQuestionsForPaper("mp").map { it.id })
    }

    @Test
    fun movingTheOnlySiblingThatWayIsRefused() = runBlocking {
        repository.ensurePaperAndCategory("mp", "Move Paper", "mc1", "First Cat")
        val c2 = repository.addCategory("mp", "Second Cat", parentId = null)
        assertTrue(!repository.moveCategory(c2, +1))
        assertTrue(!repository.moveCategory("mc1", -1))
    }


    @Test
    fun movingARootPastANestedParentKeepsTheSubtreeTogether() = runBlocking {
        // The demo paper's shape: Chemistry and Physics are children of
        // Science, and the category sortOrders form one global pre-order
        // sequence. Moving Mathematics up must not leave the roots sharing
        // sortOrders with Science's children (which surfaced as
        // "maths, chemistry, science, physics" through the title tiebreak).
        repository.ensurePaperAndCategory("np", "Nested Paper", "ns", "Science")
        val nc = repository.addCategory("np", "Chemistry", parentId = "ns")
        val np2 = repository.addCategory("np", "Physics", parentId = "ns")
        val nm = repository.addCategory("np", "Mathematics", parentId = null)
        fun q(id: String, cat: String) = com.mcqapp.domain.Question(
            id = id, categoryId = cat,
            elements = listOf(com.mcqapp.domain.ContentElement.TextElement("$id?")),
            options = listOf(
                com.mcqapp.domain.QuestionOption("a", "A"),
                com.mcqapp.domain.QuestionOption("b", "B")
            ),
            correctOptionIds = setOf("a")
        )
        repository.saveQuestion(q("nq-s", "ns"))
        repository.saveQuestion(q("nq-c", nc))
        repository.saveQuestion(q("nq-p", np2))
        repository.saveQuestion(q("nq-m", nm))

        // Roots: Science, then Mathematics above nobody but Science.
        assertTrue(repository.moveCategory(nm, -1))
        assertEquals(
            "Mathematics goes above Science's whole subtree, in tree order",
            listOf("nq-m", "nq-s", "nq-c", "nq-p"),
            repository.getQuestionsForPaper("np").map { it.id }
        )
        // Every category still holds a unique slot — no tiebreak games.
        assertEquals(
            4,
            db.categoryDao().getByPaper("np").map { it.sortOrder }.distinct().size
        )
        // And Science moves back up with its subtree following it.
        assertTrue(repository.moveCategory("ns", -1))
        assertEquals(
            listOf("nq-s", "nq-c", "nq-p", "nq-m"),
            repository.getQuestionsForPaper("np").map { it.id }
        )
        // The pinned direction is refused: Mathematics is the last root.
        assertTrue(!repository.moveCategory(nm, +1))
    }

    @Test
    fun aFreshCategoryTakesTheNextSortSlot() = runBlocking {
        repository.ensurePaperAndCategory("np", "Nested Paper", "ns", "Science")
        val added = repository.addCategory("np", "Added", parentId = null)
        // 0 belongs to the ensured category; the new one must not collide.
        val slots = db.categoryDao().getByPaper("np").map { it.sortOrder }
        assertEquals(2, slots.distinct().size)
        assertTrue(added.let { id -> db.categoryDao().getById(id)!!.sortOrder > 0 })
    }


    // ---- outline gestures: nest into, promote out, reparent, rename ----

    private fun nestedPaper(): Unit = runBlocking {
        repository.ensurePaperAndCategory("og", "Outline Paper", "og-x", "X")
        val c2 = repository.addCategory("og", "Y", parentId = null)
        repository.saveQuestion(
            com.mcqapp.domain.Question(
                id = "og-qx", categoryId = "og-x",
                elements = listOf(com.mcqapp.domain.ContentElement.TextElement("X?")),
                options = listOf(
                    com.mcqapp.domain.QuestionOption("a", "A"),
                    com.mcqapp.domain.QuestionOption("b", "B")
                ),
                correctOptionIds = setOf("a")
            )
        )
        repository.saveQuestion(
            com.mcqapp.domain.Question(
                id = "og-qy", categoryId = c2,
                elements = listOf(com.mcqapp.domain.ContentElement.TextElement("Y?")),
                options = listOf(
                    com.mcqapp.domain.QuestionOption("a", "A"),
                    com.mcqapp.domain.QuestionOption("b", "B")
                ),
                correctOptionIds = setOf("a")
            )
        )
    }

    @Test
    fun nestingIntoTheSiblingAboveKeepsTheQuestionOrder() = runBlocking {
        nestedPaper()
        val yId = db.categoryDao().getByPaper("og").first { it.title == "Y" }.id
        assertTrue(repository.nestCategoryIntoSibling(yId))
        // Hierarchy changed, position did not: the outline -> gesture.
        assertEquals(listOf("og-qx", "og-qy"), repository.getQuestionsForPaper("og").map { it.id })
        val yRow = db.categoryDao().getByPaper("og").first { it.title == "Y" }
        assertEquals("og-x", yRow.parentId)
        // Every slot still unique.
        assertEquals(2, db.categoryDao().getByPaper("og").map { it.sortOrder }.distinct().size)
        // Promote back out: still no visual movement.
        assertTrue(repository.promoteCategoryOutOfParent(yId))
        assertEquals(null, db.categoryDao().getByPaper("og").first { it.title == "Y" }.parentId)
        assertEquals(listOf("og-qx", "og-qy"), repository.getQuestionsForPaper("og").map { it.id })
    }

    @Test
    fun promoteIsRefusedForRoots() = runBlocking {
        nestedPaper()
        val xId = db.categoryDao().getByPaper("og").first { it.title == "X" }.id
        assertTrue(!repository.promoteCategoryOutOfParent(xId))
    }

    @Test
    fun renamingACategoryKeepsItsQuestions() = runBlocking {
        nestedPaper()
        val xId = db.categoryDao().getByPaper("og").first { it.title == "X" }.id
        assertTrue(repository.renameCategory(xId, "Ex"))
        assertEquals("Ex", db.categoryDao().getById(xId)!!.title)
        // A REPLACE-based upsert here would cascade-delete og-qx.
        assertEquals(listOf("og-qx", "og-qy"), repository.getQuestionsForPaper("og").map { it.id })
    }

    @Test
    fun reparentRefusesCyclesAndRenamesWork() = runBlocking {
        nestedPaper()
        val xId = db.categoryDao().getByPaper("og").first { it.title == "X" }.id
        val yId = db.categoryDao().getByPaper("og").first { it.title == "Y" }.id
        // Nest Y under X, then X cannot become a child of its own child.
        assertTrue(repository.nestCategoryIntoSibling(yId))
        assertTrue(!repository.reparentCategory(xId, yId))
        // Rename works.
        assertTrue(repository.renameCategory(yId, "Yed"))
        assertEquals("Yed", db.categoryDao().getById(yId)!!.title)
        // And the top level is reachable again.
        assertTrue(repository.reparentCategory(yId, null))
        assertEquals(null, db.categoryDao().getById(yId)!!.parentId)
    }

    @Test
    fun getByCategoriesMatchesThePerCategoryFetch() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        // A second ensurePaperAndCategory would REPLACE the paper row and
        // cascade-delete c1 (categories.paperId FK), so insert c2 directly.
        db.categoryDao().upsert(CategoryEntity(id = "c2", paperId = "p1", title = "Cat2"))
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a"), categoryId = "c2"))
        repository.saveQuestion(question("q3", setOf("q3-a")))
        val viaPerCategory = listOf("c1", "c2").flatMap { db.questionDao().getByCategory(it) }.map { it.id }
        val viaIn = db.questionDao().getByCategories(listOf("c1", "c2")).map { it.id }
        assertEquals(viaPerCategory, viaIn)
        assertEquals(listOf("q2"), db.questionDao().getByCategories(listOf("c2")).map { it.id })
        // The repository guards the empty-IN case (invalid SQL) before the DAO.
        assertTrue(repository.getQuestionsForCategories(emptyList()).isEmpty())
    }

    @Test
    fun getQuestionsForCategoriesReturnsEveryQuestionOnce() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        // Insert c2 directly: re-upserting the paper would cascade-delete c1.
        db.categoryDao().upsert(CategoryEntity(id = "c2", paperId = "p1", title = "Cat2"))
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        val questions = repository.getQuestionsForCategories(listOf("c1", "c2"))
        assertEquals(setOf("q1", "q2"), questions.map { it.id }.toSet())
        assertTrue(repository.getQuestionsForCategories(emptyList()).isEmpty())
    }

    @Test
    fun questionElementsRoundTripThroughTheDatabase() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        val elements = listOf(
            com.mcqapp.domain.ContentElement.TextElement("What is 2+2?"),
            com.mcqapp.domain.ContentElement.TableElement(listOf(listOf("a", "b"), listOf("1", "2")))
        )
        repository.saveQuestion(question("q1", setOf("q1-a")).copy(elements = elements))

        val stored = repository.getQuestion("q1")!!
        assertEquals(elements, stored.elements)
        assertEquals("What is 2+2?", stored.text)
    }

    @Test
    fun optionElementsRoundTripThroughTheDatabase() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        val optionElements = listOf(
            com.mcqapp.domain.ContentElement.TextElement("Four"),
            com.mcqapp.domain.ContentElement.MathElement("<math><mi>x</mi></math>")
        )
        val question = question("q1", setOf("q1-a")).copy(
            options = listOf(
                com.mcqapp.domain.QuestionOption("q1-a", elements = optionElements),
                com.mcqapp.domain.QuestionOption("q1-b", text = "Five")
            )
        )
        repository.saveQuestion(question)

        val stored = repository.getQuestion("q1")!!
        assertEquals(optionElements, stored.options[0].elements)
        assertEquals("Four", stored.options[0].text)
        assertEquals("Five", stored.options[1].text)
    }

    @Test
    fun observeByIdEmitsOnlyTheTargetQuestion() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a")))
        repository.saveQuestion(question("q2", setOf("q2-a")))
        val emission = db.questionDao().observeById("q1").first()
        assertEquals("q1", emission?.id)
        assertEquals(listOf("q1-a"), emission?.let { db.correctAnswerDao().getCorrectIds(it.id) })
    }
}
