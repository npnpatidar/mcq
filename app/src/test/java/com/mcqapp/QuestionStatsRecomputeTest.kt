package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.AttemptEntity
import com.mcqapp.data.local.QuestionResultEntity
import com.mcqapp.data.local.applyQuestionStats
import com.mcqapp.data.repository.McqRepository
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
 * `question_stats` means "currently stored" (section 3.1, section 12.3).
 * These pin the write rule for the representative counters and the recompute
 * that deleting an attempt runs: a practice or legacy attempt never moves
 * `rep*`, deleting a representative attempt subtracts by recomputation, a
 * skip-only row survives, and a row whose last result is deleted is removed.
 *
 * Robolectric because the rules are SQL; CI (x86_64) is authoritative for
 * these, as for every SQLite-backed test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestionStatsRecomputeTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun tearDown() = db.close()

    private fun result(
        questionId: String,
        correct: Boolean = true,
        skip: Boolean = false,
        graded: Boolean = true,
        text: String = "Q $questionId"
    ) = QuestionResultEntity(
        attemptId = 0,
        questionId = questionId,
        categoryTitle = "Cat",
        text = text,
        optionsJson = "[]",
        correctOptionIds = if (graded) "a" else "",
        selectedOptionIds = when {
            skip -> ""
            !graded -> "b"
            correct -> "a"
            else -> "b"
        },
        isCorrect = graded && !skip && correct,
        explanation = ""
    )

    /** The production write path: insert the header, its rows, then the fold. */
    private suspend fun save(
        finishedAt: Long,
        rows: List<QuestionResultEntity>,
        testType: String? = null,
        samplingVersion: Int = 1
    ): Long {
        val attempt = AttemptEntity(
            paperId = "p1",
            title = "Paper",
            totalQuestions = rows.size,
            correctCount = rows.count { it.correctOptionIds.isNotEmpty() && it.isCorrect },
            wrongCount = rows.count {
                it.correctOptionIds.isNotEmpty() && it.selectedOptionIds.isNotEmpty() && !it.isCorrect
            },
            skippedCount = rows.count {
                it.correctOptionIds.isNotEmpty() && it.selectedOptionIds.isEmpty()
            },
            score = 0.0,
            maxScore = 0.0,
            durationSeconds = 60,
            finishedAt = finishedAt,
            testType = testType,
            samplingVersion = samplingVersion
        )
        val id = db.attemptDao().insertAttempt(attempt)
        val withId = rows.map { it.copy(attemptId = id) }
        db.attemptDao().insertResults(withId)
        db.questionStatsDao().applyQuestionStats(
            "p1", "Paper", finishedAt, withId, testType, samplingVersion
        )
        return id
    }

    private data class Row(
        val total: Int,
        val graded: Int,
        val correct: Int,
        val wrong: Int,
        val skip: Int,
        val ungraded: Int,
        val repCorrect: Int,
        val repWrong: Int,
        val repSkip: Int,
        val lastCorrect: Boolean,
        val lastFinishedAt: Long
    )

    private fun stats(questionId: String): Row? =
        db.openHelper.readableDatabase.query(
            "SELECT totalAttempts,gradedCount,correctCount,wrongCount,skipCount," +
                "ungradedCount,repCorrect,repWrong,repSkip,lastResultIsCorrect," +
                "lastFinishedAt FROM question_stats WHERE questionId = ?",
            arrayOf(questionId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            Row(
                total = c.getInt(0),
                graded = c.getInt(1),
                correct = c.getInt(2),
                wrong = c.getInt(3),
                skip = c.getInt(4),
                ungraded = c.getInt(5),
                repCorrect = c.getInt(6),
                repWrong = c.getInt(7),
                repSkip = c.getInt(8),
                lastCorrect = c.getInt(9) != 0,
                lastFinishedAt = c.getLong(10)
            )
        }

    @Test
    fun practiceAndLegacyAttemptsNeverMoveTheRepresentativeCounters() = runBlocking {
        save(
            1000L,
            listOf(result("q1"), result("q2", correct = false), result("q3", skip = true)),
            testType = "MISTAKES",
            samplingVersion = 2
        )
        save(
            2000L,
            listOf(result("q1")),
            testType = null,
            samplingVersion = 1
        )
        save(
            3000L,
            listOf(result("q2", correct = false)),
            testType = "RANDOM",
            samplingVersion = 1
        )

        val q1 = stats("q1")!!
        assertEquals(2, q1.total)
        assertEquals(2, q1.correct)
        assertEquals(0, q1.repCorrect)

        val q2 = stats("q2")!!
        assertEquals(2, q2.total)
        assertEquals(2, q2.wrong)
        assertEquals(0, q2.repWrong)

        val q3 = stats("q3")!!
        assertEquals(1, q3.skip)
        assertEquals(0, q3.repSkip)
    }

    @Test
    fun aRepresentativeAttemptMovesBothCounterSets() = runBlocking {
        save(
            1000L,
            listOf(
                result("q1"),
                result("q2", correct = false),
                result("q3", skip = true),
                result("q4", graded = false)
            ),
            testType = "RANDOM",
            samplingVersion = 2
        )
        save(2000L, listOf(result("q1", correct = false)), testType = "BALANCED", samplingVersion = 2)

        val q1 = stats("q1")!!
        assertEquals(2, q1.total)
        assertEquals(1, q1.correct)
        assertEquals(1, q1.wrong)
        assertEquals(1, q1.repCorrect)
        assertEquals(1, q1.repWrong)
        assertFalse(q1.lastCorrect)

        assertEquals(1, stats("q2")!!.repWrong)
        assertEquals(1, stats("q3")!!.repSkip)
        // An ungraded result is exposure only; it never enters rep*.
        val q4 = stats("q4")!!
        assertEquals(1, q4.ungraded)
        assertEquals(0, q4.repCorrect + q4.repWrong + q4.repSkip)
    }

    @Test
    fun deletingARepresentativeAttemptRecomputesInsteadOfDecrementing() = runBlocking {
        val first = save(1000L, listOf(result("q1")), "RANDOM", 2)
        val second = save(2000L, listOf(result("q1", correct = false)), "RANDOM", 2)

        assertEquals(1, stats("q1")!!.repWrong)
        repository.deleteAttempt(second)

        val after = stats("q1")!!
        assertEquals(1, after.total)
        assertEquals(1, after.correct)
        assertEquals(0, after.wrong)
        assertEquals(1, after.repCorrect)
        assertEquals(0, after.repWrong)
        assertTrue("the surviving result is the latest standing", after.lastCorrect)
        assertEquals(1000L, after.lastFinishedAt)

        repository.deleteAttempt(first)
        assertNull("the last result is gone, so the row is too", stats("q1"))
    }

    @Test
    fun deletingAPracticeAttemptLeavesTheRepresentativeCountersAlone() = runBlocking {
        save(1000L, listOf(result("q1")), "RANDOM", 2)
        val practice = save(2000L, listOf(result("q1", correct = false)), "MISTAKES", 2)

        repository.deleteAttempt(practice)

        val after = stats("q1")!!
        assertEquals(1, after.total)
        assertEquals(1, after.repCorrect)
        assertEquals(0, after.repWrong)
    }

    @Test
    fun aSkipOnlyRowIsKeptAndCountsAsARepresentativeSkip() = runBlocking {
        save(1000L, listOf(result("q1", skip = true)), "RANDOM", 2)

        val row = stats("q1")!!
        assertEquals(1, row.total)
        assertEquals(1, row.graded)
        assertEquals(1, row.skip)
        assertEquals(1, row.repSkip)
        assertFalse(row.lastCorrect)
        assertEquals(1000L, row.lastFinishedAt)
    }

    @Test
    fun aRowWhoseLastResultIsDeletedIsRecomputedNotDropped() = runBlocking {
        val first = save(1000L, listOf(result("q1", skip = true)), "RANDOM", 2)
        val second = save(2000L, listOf(result("q1")), "RANDOM", 2)

        repository.deleteAttempt(second)

        val after = stats("q1")!!
        assertEquals(1, after.total)
        assertEquals(1, after.skip)
        assertEquals(0, after.correct)
        assertEquals(1, after.repSkip)
        assertEquals(0, after.repCorrect)
        assertFalse("a skip is not a correct result", after.lastCorrect)
        assertEquals(1000L, after.lastFinishedAt)

        repository.deleteAttempt(first)
        assertNull(stats("q1"))
    }

    /**
     * The exact shape the `NOT EXISTS` guard exists for: q1 has a survivor
     * and must be recomputed, while q2 existed only in the deleted attempt
     * and must be dropped. Without the `r.attemptId != :attemptId` filter in
     * the guard, q2's row would survive as an all-zero aggregate.
     */
    @Test
    fun deletingAnAttemptDropsOnlyTheQuestionsWithNoSurvivor() = runBlocking {
        save(1000L, listOf(result("q1")), "RANDOM", 2)
        val deleted = save(
            2000L,
            listOf(result("q1", correct = false), result("q2")),
            "RANDOM",
            2
        )

        repository.deleteAttempt(deleted)

        val q1 = stats("q1")!!
        assertEquals(1, q1.total)
        assertEquals(1, q1.correct)
        assertEquals(0, q1.wrong)
        assertEquals(1, q1.repCorrect)
        assertEquals(0, q1.repWrong)
        assertEquals(1000L, q1.lastFinishedAt)
        assertNull("q2 had no result outside the deleted attempt", stats("q2"))
    }

    /**
     * The last-standing pair comes from the latest *graded, answered* result,
     * so a later skip must not replace an earlier correct answer.
     */
    @Test
    fun aLaterSkipDoesNotReplaceTheLastGradedStanding() = runBlocking {
        save(1000L, listOf(result("q1")), "RANDOM", 2)
        save(2000L, listOf(result("q1", skip = true)), "RANDOM", 2)
        val later = save(3000L, listOf(result("q1", correct = false)), "RANDOM", 2)

        repository.deleteAttempt(later)

        val after = stats("q1")!!
        assertEquals(2, after.total)
        assertEquals(1, after.correct)
        assertEquals(1, after.skip)
        assertTrue("the latest graded answer was correct", after.lastCorrect)
        assertEquals(
            "the later skip must not replace the graded standing",
            1000L,
            after.lastFinishedAt
        )
    }
}
