package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
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
 * An imported schedule has to reach `card_state` *and* survive being read back
 * by a study session. The second half is the one that matters: the repository
 * compares a stored card's content hash against the question as stored and
 * resets anything that looks edited, so a hash written from the wrong bytes
 * would silently wipe the very schedule that was just imported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportSchedulingTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository
    private val now = 1_700_000_000_000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun file(questionId: String = "q1") = McqFileDto(
        version = 1,
        papers = listOf(
            PaperDto(
                id = "p1",
                title = "Deck",
                categories = listOf(
                    CategoryDto(
                        id = "c1",
                        title = "Cat",
                        questions = listOf(
                            QuestionDto(
                                id = questionId,
                                text = "2 + 2?",
                                options = listOf(OptionDto("a", "4"), OptionDto("b", "five")),
                                correctOptionIds = listOf("a"),
                                explanation = "Arithmetic."
                            )
                        )
                    )
                )
            )
        )
    )

    private fun schedule() = CardScheduleDto(
        ease = 2.6,
        intervalDays = 5,
        dueAt = now + 5 * 86_400_000L,
        reps = 7,
        lapses = 1,
        leech = false,
        lastReviewedAt = now
    )

    @Test
    fun importedScheduleIsStoredAndCounted() = runBlocking {
        val report = Importer(db).import(file(), mapOf("q1" to schedule()))

        assertEquals(1, report.restoredSchedules)
        val stored = db.cardStateDao().get("p1", "q1")
        assertEquals(5, stored!!.intervalDays)
        assertEquals(7, stored.reps)
        assertEquals(1, stored.lapses)
        assertEquals(2.6, stored.ease, 0.0001)
    }

    @Test
    fun importedScheduleSurvivesAStudySessionLoad() = runBlocking {
        Importer(db).import(file(), mapOf("q1" to schedule()))

        // Opening the study queue seeds or resets every card it looks at, and
        // the library badge goes through the same path. A content hash that does
        // not match the stored question reads as an edit, and the card is reset
        // to new here.
        repository.getStudyQueue("p1", now = now + 10 * 86_400_000L)
        repository.getStudyCounts("p1", now = now + 10 * 86_400_000L)

        val state = db.cardStateDao().get("p1", "q1")!!
        assertEquals("the imported interval must not be reset", 5, state.intervalDays)
        assertEquals(7, state.reps)
        assertEquals(1, state.lapses)
        assertEquals(now + 5 * 86_400_000L, state.dueAt)
    }

    @Test
    fun aPaperWithoutSchedulingImportsCardsAsNew() = runBlocking {
        val report = Importer(db).import(file())

        assertEquals(0, report.restoredSchedules)
        val stored = db.cardStateDao().get("p1", "q1")
        // No row yet: the study session seeds a new card on first use.
        assertEquals(null, stored)
    }

    @Test
    fun reimportingKeepsTheScheduleRatherThanDuplicatingQuestions() = runBlocking {
        Importer(db).import(file(), mapOf("q1" to schedule()))
        val moved = schedule().copy(intervalDays = 12, dueAt = now + 12 * 86_400_000L)

        val report = Importer(db).import(file(), mapOf("q1" to moved))

        assertEquals(1, report.duplicateQuestions)
        assertEquals(0, report.restoredSchedules)
        // The schedule a user has since moved on with is not overwritten.
        assertEquals(5, db.cardStateDao().get("p1", "q1")!!.intervalDays)
    }

    @Test
    fun anEditedQuestionStillTakesThePackagesSchedule() = runBlocking {
        Importer(db).import(file(), mapOf("q1" to schedule()))

        // Same id, different text: an update, not a duplicate.
        val edited = file().let { f ->
            f.copy(
                papers = f.papers.map { p ->
                    p.copy(
                        categories = p.categories.map { c ->
                            c.copy(questions = c.questions.map { it.copy(text = "2 + 2 again?") })
                        }
                    )
                }
            )
        }
        val report = Importer(db).import(edited, mapOf("q1" to schedule()))

        assertEquals(1, report.updatedQuestions)
        assertEquals(1, report.restoredSchedules)
        assertTrue(db.questionDao().getById("q1")!!.text.contains("again"))
        assertEquals(5, db.cardStateDao().get("p1", "q1")!!.intervalDays)
    }
}
