package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A JSON backup used to carry no review progress at all, so restoring one
 * silently reset every SM-2 schedule, due date and leech flag while the UI
 * called the file a backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSchedulingTest {

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
        repository.saveQuestion(
            Question(
                id = "q1",
                categoryId = "c1",
                text = "2 + 2?",
                options = listOf(QuestionOption("a", "3"), QuestionOption("b", "4")),
                correctOptionIds = setOf("b")
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun studyOnce() {
        repository.recordStudyReview(
            paperId = "p1",
            questionId = "q1",
            grade = ReviewGrade.GOOD,
            now = 1_700_000_000_000L
        )
    }

    @Test
    fun theBackupCarriesReviewProgress() = runBlocking {
        studyOnce()
        val json = Exporter(db).exportAll()
        val file = LegacyParser.parse(json)
        assertTrue(
            "the backup should carry q1's schedule",
            file.scheduling.containsKey("q1")
        )
        val schedule = file.scheduling.getValue("q1")
        assertTrue("reps should have been exported", schedule.reps > 0)
    }

    @Test
    fun aRestoredBackupKeepsTheSchedule() = runBlocking {
        studyOnce()
        val before = repository.cardState("p1", "q1")!!
        val json = Exporter(db).exportAll()

        // Fresh database: nothing but the file.
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)

        Importer(db).import(LegacyParser.parse(json))

        val after = repository.cardState("p1", "q1")
        assertTrue("the card should exist after restore", after != null)
        assertEquals(before.reps, after!!.reps)
        assertEquals(before.ease, after.ease, 0.0001)
        assertEquals(before.intervalDays, after.intervalDays)
        assertEquals(before.lastReviewedAt, after.lastReviewedAt)
    }

    @Test
    fun anUntouchedQuestionIsNotGivenASchedule() = runBlocking {
        repository.saveQuestion(
            Question(
                id = "q2",
                categoryId = "c1",
                text = "3 + 3?",
                options = listOf(QuestionOption("a", "5"), QuestionOption("b", "6")),
                correctOptionIds = setOf("b")
            )
        )
        studyOnce()
        val file = LegacyParser.parse(Exporter(db).exportAll())
        assertEquals(setOf("q1"), file.scheduling.keys)
    }

    @Test
    fun aForeignFileWithoutSchedulingStillImports() = runBlocking {
        val json = """
            {"papers":[{"id":"p2","title":"Other","categories":[{"id":"c9","title":"Other",
            "questions":[{"id":"q9","text":"5 + 5?","options":[{"id":"a","text":"9"},{"id":"b","text":"10"}],
            "correct":"b"}]}]}]}
        """.trimIndent()
        val report = Importer(db).import(LegacyParser.parse(json))
        assertEquals(1, report.newQuestions)
        assertEquals(0, db.cardStateDao().getAll().size)
    }
}
