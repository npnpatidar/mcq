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
import com.mcqapp.data.repository.SuspendStore
import com.mcqapp.domain.CardStateKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Both import reset paths used to delete the whole `card_state` row, so an
 * edited or answer-refreshed hidden card came back in Study and vanished from
 * the unsuspend list. The schedule memory must still reset; only the two v30
 * hide flags survive. CI-only: Robolectric opening SQLite cannot run on ARM64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportHiddenFlagTest {

    private lateinit var db: AppDatabase
    private val now = 1_700_000_000_000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun file(text: String, correct: List<String>) = McqFileDto(
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
                                id = "q1",
                                text = text,
                                options = listOf(OptionDto("a", "4"), OptionDto("b", "five")),
                                correctOptionIds = correct,
                                explanation = "Arithmetic."
                            )
                        )
                    )
                )
            )
        )
    )

    private fun studied() = CardScheduleDto(
        ease = 2.6,
        intervalDays = 5,
        dueAt = now + 5 * 86_400_000L,
        reps = 7,
        lapses = 1,
        lastReviewedAt = now,
        difficulty = 4.2,
        stability = 5.0,
        cardState = CardStateKind.REVIEW.name,
        reviewCount = 7
    )

    @Test
    fun changedContentImportKeepsTheSuspendButResetsTheSchedule() = runBlocking {
        Importer(db).import(file("2 + 2?", listOf("a")), mapOf("q1" to studied()))
        assertEquals(CardStateKind.REVIEW, db.cardStateDao().get("p1", "q1")!!.cardState)
        SuspendStore(db).suspend("p1", "q1")
        assertTrue(db.cardStateDao().get("p1", "q1")!!.suspended)

        // Same id, changed text: the content-changed reset path.
        Importer(db).import(file("3 + 3?", listOf("a")))

        val after = db.cardStateDao().get("p1", "q1")
        assertNotNull("the hide flag needs a home after the edit", after)
        assertTrue("suspend survives a content-changing import", after!!.suspended)
        assertEquals("schedule memory is reset", CardStateKind.NEW, after.cardState)
        assertEquals(0, after.reps)
        assertEquals(0L, after.dueAt)
        assertEquals(0.0, after.stability, 0.0)
        assertEquals(0.0, after.difficulty, 0.0)
        assertEquals(0, after.reviewCount)
        assertEquals(
            "the flag-only row carries the new content hash",
            db.questionDao().getHashById("q1"),
            after.contentHash
        )
    }

    @Test
    fun answerRefreshImportKeepsTheSuspendButResetsTheSchedule() = runBlocking {
        Importer(db).import(file("2 + 2?", listOf("a")), mapOf("q1" to studied()))
        val hashBefore = db.questionDao().getHashById("q1")
        SuspendStore(db).suspend("p1", "q1")
        assertTrue(db.cardStateDao().get("p1", "q1")!!.suspended)

        // Same text and options, corrected key with refresh enabled: the dedup
        // "answer refreshed" path that used to drop the whole flag row.
        Importer(db, updateAnswersOnDuplicate = true).import(file("2 + 2?", listOf("b")))

        val after = db.cardStateDao().get("p1", "q1")
        assertNotNull("the hide flag needs a home after the refresh", after)
        assertTrue("suspend survives an answer-refresh import", after!!.suspended)
        assertEquals("schedule memory is reset", CardStateKind.NEW, after.cardState)
        assertEquals(0, after.reps)
        assertEquals(0L, after.dueAt)
        assertEquals(0.0, after.stability, 0.0)
        assertEquals("the dedup path keeps the unchanged hash", hashBefore, after.contentHash)
    }
}
