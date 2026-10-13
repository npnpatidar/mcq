package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pull runs after every graded card, so a 30-card sitting on a
 * 500-question paper must not pay a full-paper price each time (audit
 * finding 2). Like the badge before it, the pull reads ids and content
 * hashes from the questions/options rows and never touches the answer key.
 * Counts are measured through Room's query callback, reset after seeding.
 * Robolectric needs a real SQLite driver, so this test only runs on x86_64 CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyPullQueryCountTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext<Context>()
    }

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Q $id",
        options = listOf(
            QuestionOption("a", "right"),
            QuestionOption("b", "wrong")
        ),
        correctOptionIds = setOf("a"),
        difficulty = Difficulty.HARD,
        marks = 1.0
    )

    private class PullStats(val selects: Int, val answerKeySelects: Int)

    /**
     * Seeds [count] questions, then measures the queries the next-card pull
     * issues: total SELECTs, and how many of them read `correct_answers`.
     */
    private fun statsFor(count: Int): PullStats {
        val selects = AtomicInteger()
        val answerKeySelects = AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            // Inline executor, so the counters are updated before the call returns.
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (sql.trimStart().startsWith("SELECT")) selects.incrementAndGet()
                    if (sql.contains("correct_answers")) answerKeySelects.incrementAndGet()
                },
                { it.run() }
            )
            .allowMainThreadQueries()
            .build()
        try {
            return runBlocking {
                val repository = McqRepository(db, appContext)
                repository.ensurePaperAndCategory("p1", "Paper 1", "c1", "Cat 1")
                (1..count).forEach { repository.saveQuestion(question("q$it")) }
                selects.set(0)
                answerKeySelects.set(0)
                // Seconds at 90 days, past every interval: nothing is due, so
                // the pull walks the whole paper and serves the first new card.
                repository.getNextStudyCard("p1", reviewsSinceNew = 0, now = 90L * 86_400_000L)
                PullStats(selects.get(), answerKeySelects.get())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun pullingNextCardDoesNotGrowWithThePaper() {
        val atTen = statsFor(10)
        val atHundred = statsFor(100)
        println("PULL-COUNT next at10=${atTen.selects} at100=${atHundred.selects}")
        assertTrue(
            "ninety more questions added ${atHundred.selects - atTen.selects} selects " +
                "(at 10 it was ${atTen.selects}), which is per-paper behaviour",
            atHundred.selects - atTen.selects <= 3
        )
    }

    @Test
    fun thePullNeverReadsTheAnswerKey() {
        // The pull only needs ids, content hashes and card states; loading
        // the key was the main table the old path dragged in per pull.
        assertEquals(
            0,
            statsFor(100).answerKeySelects
        )
    }
}
