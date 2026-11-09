package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.AttemptMetadata
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.stats.ExamGroup
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
 * The Exam aggregate reads against a real Room database (plan §3.2/§3.4).
 * CI x86_64 is authoritative: the ARM64 hosts cannot run Robolectric's SQLite
 * runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExamStatsDbTest {

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
        repository.saveQuestion(question("q1"))
        repository.saveQuestion(question("q2"))
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

    private fun metadata(type: TestType, exclusion: String = ExclusionMode.NONE.name) =
        AttemptMetadata(
            testType = type.name,
            requestedCount = 2,
            scopeKey = ScopeKey.wholePaper("p1"),
            samplingVersion = TestPlan.SAMPLING_VERSION,
            exclusionMode = exclusion
        )

    private suspend fun saveAttempt(
        type: TestType,
        selections: Map<String, Set<String>>,
        dwell: Map<String, Long>,
        finishedAt: Long
    ): Long {
        val questions = repository.getQuestionsForPaper("p1")
        return repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = selections,
            negativeMarking = 0.0,
            durationSeconds = 60,
            finishedAt = finishedAt,
            dwellSeconds = dwell,
            metadata = metadata(type)
        )
    }

    @Test
    fun representativeAndAllAttemptCountersStaySeparate() = runBlocking {
        val mock = saveAttempt(
            TestType.RANDOM,
            selections = mapOf("q1" to setOf("q1-a"), "q2" to setOf("nope")),
            dwell = mapOf("q1" to 10L, "q2" to 20L),
            finishedAt = 1_000L
        )
        val practice = saveAttempt(
            TestType.WEAKEST_TOPICS,
            selections = mapOf("q1" to setOf("nope"), "q2" to setOf("q2-a")),
            dwell = mapOf("q1" to 30L, "q2" to 40L),
            finishedAt = 2_000L
        )

        val counters = db.questionStatsDao().getExamCounterTotals("p1")
        assertEquals(2, counters.correct)
        assertEquals(2, counters.wrong)
        assertEquals(1, counters.repCorrect)
        assertEquals(1, counters.repWrong)

        val mockCategories = db.attemptDao().getExamCategoryTotals(listOf(mock))
        assertEquals(1, mockCategories.size)
        assertEquals(2, mockCategories.single().asked)
        assertEquals(1, mockCategories.single().correct)
        assertEquals("c1", mockCategories.single().currentCategoryId)

        val practiceCategories = db.attemptDao().getExamCategoryTotals(listOf(practice))
        assertEquals(1, practiceCategories.single().correct)

        val mockDwell = db.attemptDao().getExamDwellTotals(listOf(mock))
        assertEquals(30L, mockDwell.totalSeconds)
        assertEquals(2, mockDwell.timedRows)
    }

    @Test
    fun getTestProgressBuildsEachExamGroupFromItsOwnAttempts() = runBlocking {
        // A family is only a mock/practice measurement at or above
        // TestPlan.MIN_LENGTH, so the paper needs a full-length attempt.
        for (i in 3..20) repository.saveQuestion(question("q$i"))
        val ids = repository.getQuestionsForPaper("p1").map { it.id }
        val firstHalf = ids.take(10).toSet()
        val secondHalf = ids.drop(10).toSet()
        fun answers(correct: Set<String>) =
            ids.associateWith { id -> setOf(if (id in correct) "$id-a" else "$id-b") }

        saveAttempt(
            TestType.RANDOM,
            selections = answers(firstHalf),
            dwell = mapOf("q1" to 10L, "q2" to 20L),
            finishedAt = 1_000L
        )
        saveAttempt(
            TestType.WEAKEST_TOPICS,
            selections = answers(secondHalf),
            dwell = mapOf("q1" to 30L, "q2" to 40L),
            finishedAt = 2_000L
        )

        val progress = repository.getTestProgress("p1", now = 3_000L)
        val mock = progress.exams.getValue(ExamGroup.MOCK_STRICT)
        val practice = progress.exams.getValue(ExamGroup.PRACTICE)

        assertEquals(1, mock.attempts)
        assertEquals(50.0, mock.latestPercent!!, 0.0001)
        assertEquals(20, mock.shown)
        assertTrue(mock.lifetimeIsRepresentative)
        // The mock reads the representative (rep*) counters only.
        assertEquals(10, mock.lifetime.correct)
        assertEquals(15L, mock.averageSecondsPerQuestion!!.toLong())

        assertEquals(1, practice.attempts)
        assertEquals(50.0, practice.latestPercent!!, 0.0001)
        assertFalse(practice.lifetimeIsRepresentative)
        // Practice reads every attempt's counters.
        assertEquals(20, practice.lifetime.correct)
    }

    @Test
    fun deletingAQuestionLeavesItsAsOfAttemptCategoryRowsUnresolved() = runBlocking {
        val attempt = saveAttempt(
            TestType.RANDOM,
            selections = mapOf("q1" to setOf("q1-a"), "q2" to setOf("nope")),
            dwell = mapOf("q1" to 10L, "q2" to 20L),
            finishedAt = 1_000L
        )
        repository.deleteQuestion("q2")

        val row = db.attemptDao().getExamCategoryTotals(listOf(attempt)).single()
        assertEquals(2, row.asked)
        assertEquals(1, row.correct)
        assertEquals(1, row.unresolved)
    }
}
