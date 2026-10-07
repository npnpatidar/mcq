package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Duplicating a paper or copying a selection reads the questions it clones
 * once, then writes the clones. The write side used to run a
 * getQuestion/saveQuestion round-trip per clone — a read of the row, a read of
 * the category's tail and four writes inside one transaction — so the reads
 * grew with the selection. Counts are measured through Room's query callback
 * with the counter reset after seeding: the same operation on ten questions
 * and on a hundred must cost the same handful of reads. Robolectric needs a
 * real SQLite driver, so this test only runs on x86_64 CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CopyQueryCountTest {

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
        explanation = "why",
        difficulty = Difficulty.HARD,
        marks = 2.0,
        tags = listOf("t")
    )

    /** Seeds [count] questions, then counts the SELECTs [operation] issues. */
    private fun selectsFor(count: Int, operation: suspend (McqRepository, List<String>) -> Unit): Int {
        val selects = AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            // Inline executor, so the counter is updated before the call returns.
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (sql.trimStart().startsWith("SELECT")) selects.incrementAndGet()
                },
                { it.run() }
            )
            .allowMainThreadQueries()
            .build()
        try {
            return runBlocking {
                val repository = McqRepository(db, appContext)
                repository.ensurePaperAndCategory("p1", "Paper 1", "c1", "Cat 1")
                // ensurePaperAndCategory REPLACE-upserts the paper row, which
                // cascades away its categories; a second category goes direct.
                db.categoryDao().upsert(CategoryEntity(id = "c1b", paperId = "p1", title = "Cat 1b"))
                val ids = (1..count).map { "q$it" }
                ids.forEach { repository.saveQuestion(question(it)) }
                selects.set(0)
                operation(repository, ids)
                selects.get()
            }
        } finally {
            db.close()
        }
    }

    /**
     * A per-question read-back would add two selects per clone (the row, then
     * the category's tail), so ninety more questions would add about 180;
     * reading the rows once adds the same handful at either size. Room and the
     * SQLite driver may each issue one or two catalog reads of their own
     * depending on warm-up, so the bound allows a couple and nothing near 180.
     */
    @Test
    fun duplicatingTenOrAHundredQuestionsReadsTheSame() {
        val atTen = selectsFor(10) { repository, _ -> repository.duplicatePaper("p1") }
        val atHundred = selectsFor(100) { repository, _ -> repository.duplicatePaper("p1") }
        println("COPY-COUNT duplicate at10=$atTen at100=$atHundred")
        assertTrue(
            "ninety more questions added ${atHundred - atTen} selects " +
                "(at 10 it was $atTen), which is per-question behaviour",
            atHundred - atTen <= 5
        )
    }

    @Test
    fun copyingTenOrAHundredQuestionsReadsTheSame() {
        val atTen = selectsFor(10) { repository, ids -> repository.copyQuestionsToCategory(ids, "c1b") }
        val atHundred = selectsFor(100) { repository, ids -> repository.copyQuestionsToCategory(ids, "c1b") }
        println("COPY-COUNT copy at10=$atTen at100=$atHundred")
        assertTrue(
            "ninety more questions added ${atHundred - atTen} selects " +
                "(at 10 it was $atTen), which is per-question behaviour",
            atHundred - atTen <= 5
        )
    }
}
