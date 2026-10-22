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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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

    // Three CI runs wedged right here; a 120s cap turns the next one into a
    // named failure plus a thread dump instead of a dead worker.
    @get:Rule
    val watchdog = HangWatchdogRule(seconds = 120)

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext<Context>()
    }

    private fun question(id: String, categoryId: String = "c1") = Question(
        id = id,
        categoryId = categoryId,
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

    /**
     * The scoped pull has an analytic ceiling: one `staleForPaper` read, then a
     * learning read, a review-head read and a new-card read per chunk of 500
     * ids — `1 + 3 * ceil(scope / 500)`, so 7 for 501 categories. The count is
     * measured, not derived: the print below is the observed value, and CI is
     * where this executes (Robolectric needs x86_64).
     */
    @Test
    fun aScopedPullStaysWithinOneChunkBound() = runBlocking {
        val selects = AtomicInteger()
        val answerKeySelects = AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
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
            val repository = McqRepository(db, appContext)
            repository.ensurePaperAndCategory("p1", "Paper 1", "c1", "Cat 1")
            // 501 categories, one fresh question each: the scope spans two
            // chunks, and every chunk has to be consulted for all three pools.
            val ids = (1..501).map { "c$it" }
            ids.forEach { id ->
                repository.ensurePaperAndCategory("p1", "Paper 1", id, "Cat $id")
                repository.saveQuestion(question("q-$id", id))
            }
            selects.set(0)
            answerKeySelects.set(0)
            val card = repository.getNextStudyCard(
                "p1",
                reviewsSinceNew = 0,
                // Past every interval, so nothing is due and the pull walks the
                // whole scope to the first new card.
                now = 90L * 86_400_000L,
                categoryIds = ids
            )
            val scopedSelects = selects.get()
            val scopedKeySelects = answerKeySelects.get()
            println("PULL-COUNT scoped501=$scopedSelects (bound 7, chunks=2)")
            assertNotNull("a fresh card from the 501-category scope is served", card)
            assertTrue(
                "a 501-category scope cost $scopedSelects selects, " +
                    "ceiling is 1 + 3*ceil(501/500) = 7",
                scopedSelects <= 7
            )
            assertEquals(
                "the scoped pull must not read the key table",
                0,
                scopedKeySelects
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun theDuePullReadsOneRowPerPool() = runBlocking {
        // With due schedules the pull used to select every one of them and rank
        // them in memory. Each pool's head is now a `LIMIT 1` read whose ORDER
        // BY carries the same risk comparator, so the row count cannot grow
        // with the backlog.
        val dueSelects = mutableListOf<String>()
        val db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    val trimmed = sql.trimStart()
                    if (trimmed.startsWith("SELECT cs.*") && sql.contains("FROM card_state cs")) {
                        dueSelects += sql
                    }
                },
                { it.run() }
            )
            .allowMainThreadQueries()
            .build()
        try {
            val repository = McqRepository(db, appContext)
            repository.ensurePaperAndCategory("p1", "Paper 1", "c1", "Cat 1")
            (1..200).forEach { repository.saveQuestion(question("q$it")) }
            // Graduate every card, so all 200 have a review schedule.
            (1..200).forEach {
                repository.recordStudyReview("p1", "q$it", com.mcqapp.domain.ReviewGrade.GOOD, 5)
            }
            dueSelects.clear()
            val now = System.currentTimeMillis() + 400L * 86_400_000L
            val card = repository.getNextStudyCard("p1", reviewsSinceNew = 0, now = now)
            assertNotNull("a long-overdue card is served", card)
            assertEquals(
                "one bounded read per due pool, not one row per due card: $dueSelects",
                2,
                dueSelects.size
            )
            assertTrue(
                "a due pool was read without LIMIT 1: $dueSelects",
                dueSelects.all { it.contains("LIMIT 1") }
            )
        } finally {
            db.close()
        }
    }
}
