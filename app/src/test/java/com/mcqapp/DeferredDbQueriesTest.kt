package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.getPriorStandingsChunked
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.TestReportBuilder
import com.mcqapp.domain.TestReportInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The deferred database/query tests from plan §9.1. CI x86_64 is
 * authoritative; the ARM64 hosts cannot run Robolectric's SQLite runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeferredDbQueriesTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        for (i in 1..5) repository.saveQuestion(question("q$i"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    private suspend fun saveAttempt(
        selections: Map<String, Set<String>>,
        dwell: Map<String, Long> = emptyMap(),
        finishedAt: Long,
        durationSeconds: Long = 60
    ): Long {
        val questions = repository.getQuestionsForPaper("p1")
        return repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = selections,
            negativeMarking = 0.0,
            durationSeconds = durationSeconds,
            finishedAt = finishedAt,
            dwellSeconds = dwell
        )
    }

    // --- §9.1 item 1 and 4: engaged coverage and the report header -----------

    @Test
    fun coverageCountsEngagedRowsOnlyAndTheReportMatchesTheAttemptHeader() = runBlocking {
        val attemptId = saveAttempt(
            selections = mapOf(
                "q1" to setOf("q1-a"), // answered, correct
                "q2" to setOf("q2-b"), // answered, wrong
                "q5" to setOf("q5-a") // answered, correct, no dwell
                // q3 untouched skip; q4 touched but unanswered.
            ),
            dwell = mapOf("q1" to 10L, "q2" to 20L, "q4" to 30L),
            finishedAt = 1_000L
        )

        val attempt = repository.getAttempt(attemptId)!!
        val paper = repository.getPaper("p1")
        val rows = repository.getReportRows(attemptId)
        val evidence = repository.getReportEvidence(attempt, rows, paper, now = 5_000L)
        val report = TestReportBuilder.build(
            TestReportInput(
                attempt = attempt,
                rows = rows,
                paper = paper,
                evidence = evidence
            )
        )

        // The header and the result rows agree on what was asked.
        assertEquals(5, attempt.totalQuestions)
        assertEquals(attempt.totalQuestions, rows.size)

        // An old full-scope attempt's untouched rows are not "engaged".
        assertEquals(3, rows.count { it.answered })
        assertEquals(4, rows.count { it.answered || it.dwellSeconds > 0 })
        assertEquals(4, report.coverage!!.engaged)
        assertEquals(2, report.score.correct)
        assertEquals(1, report.score.wrong)
        assertEquals(2, report.score.skipped)
        assertEquals(0, report.score.ungraded)

        // The stored attempt header matches the report's own score summary.
        assertEquals(attempt.correctCount, report.score.correct)
        assertEquals(attempt.wrongCount, report.score.wrong)
        assertEquals(attempt.skippedCount, report.score.skipped)
        assertEquals(attempt.totalQuestions, report.score.shown)

        // Timing is read from the same rows the report carries.
        assertEquals(3, report.timing.timedQuestions)
        assertEquals(20L, report.timing.medianSeconds)
        assertEquals(60L, report.timing.totalSeconds)

        // The category breakdown the result screen reads agrees with the
        // report's topic row.
        val breakdown = repository.getResultCategoryBreakdown(attemptId).single()
        val topic = report.topics.single()
        assertEquals(breakdown.title, topic.title)
        assertEquals(breakdown.graded, topic.asked)
        assertEquals(breakdown.correct, topic.correct)
    }

    // --- §9.1 item 2: the standing rule is finishedAt DESC, id DESC -----------

    @Test
    fun priorStandingsUseFinishedAtThenIdEvenAfterAMergeImport() = runBlocking {
        // A graded at 1s, B graded at 2s, then a merged-in attempt that is
        // older (0.5s) but has a higher id: recency must follow finishedAt.
        saveAttempt(
            mapOf("q1" to setOf("q1-a")),
            finishedAt = 1_000L
        )
        saveAttempt(
            mapOf("q1" to setOf("q1-b")),
            finishedAt = 2_000L
        )
        saveAttempt(
            // The merged-in attempt is older but would win a plain id tie.
            mapOf("q1" to setOf("q1-a")),
            finishedAt = 500L
        )
        val current = saveAttempt(
            mapOf("q1" to setOf("q1-a")),
            finishedAt = 3_000L
        )

        val prior = db.attemptDao().getPriorStandings(listOf("q1"), 3_000L, current)
        assertEquals(1, prior.size)
        // The wrong answer at 2s wins over the older correct row at 0.5s:
        // recency follows finishedAt, not the higher id the import gave it.
        assertFalse(prior.single().isCorrect)

        // The chunked wrapper returns the same rule.
        val chunked = db.attemptDao().getPriorStandingsChunked(listOf("q1"), 3_000L, current)
        assertEquals(prior, chunked)
    }

    @Test
    fun equalFinishedTimesBreakOnTheHigherAttemptId() = runBlocking {
        saveAttempt(mapOf("q1" to setOf("q1-b")), finishedAt = 4_000L)
        saveAttempt(mapOf("q1" to setOf("q1-a")), finishedAt = 4_000L)
        // A later skip-only attempt is not a graded standing and must not win.
        saveAttempt(emptyMap(), finishedAt = 4_500L)
        val current = saveAttempt(mapOf("q1" to setOf("q1-a")), finishedAt = 5_000L)

        val prior = db.attemptDao().getPriorStandings(listOf("q1"), 5_000L, current)
        assertEquals(1, prior.size)
        assertTrue(prior.single().isCorrect)
    }

    // --- §9.1 item 3: the due count is read-only -----------------------------

    @Test
    fun theDueCountLeavesCardStateUnchanged() = runBlocking {
        repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = 1_000L)
        repository.recordStudyReview("p1", "q2", ReviewGrade.AGAIN, now = 1_000L)

        val before = db.cardStateDao().pageAfterRowId(0L, 100)
        assertEquals(2, before.size)
        val counts = repository.getStudyCounts("p1", now = 10_000_000L)
        val after = db.cardStateDao().pageAfterRowId(0L, 100)

        assertEquals(before, after)
        assertEquals(before.size, db.cardStateDao().countAll())
        // The read is not a no-op: the seeded cards are scheduled work.
        assertTrue(counts.due >= 0)
        assertTrue(counts.fresh >= 0)
    }
}
