package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.library.LibraryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Deleting a paper takes its history, bookmarks and review schedules with it.
 * That is correct behaviour, but it made a single unconfirmed tap on a paper
 * card destructive in a way the user was never told about, so the tap now opens
 * a confirmation that names the cost.
 *
 * These tests cover the numbers in that confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class DeletePaperConfirmationTest {

    private lateinit var repository: McqRepository
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
        runBlocking {
            repository.ensurePaperAndCategory("p1", "History", "c1", "Cat")
            repository.saveQuestion(
                Question(
                    id = "q1", categoryId = "c1", text = "Q1",
                    options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
                    correctOptionIds = setOf("a")
                )
            )
        }
    }

    @After
    fun tearDown() {
        try {
            AppDatabase::class.java.getDeclaredField("INSTANCE")
                .apply { isAccessible = true }.set(null, null)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    @Test
    fun impactCountsWhatThePaperWouldTakeWithIt() = runBlocking {
        repository.toggleBookmark("q1")
        repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD)
        repository.saveAttempt(
            paperId = "p1",
            paperTitle = "History",
            questions = repository.getQuestionsForPaper("p1"),
            selections = mapOf("q1" to setOf("a")),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = System.currentTimeMillis()
        )

        val impact = repository.deleteImpact("p1")
        assertEquals(1, impact.questions)
        assertEquals(1, impact.attempts)
        assertEquals(1, impact.bookmarks)
        assertEquals(1, impact.schedules)
        assertTrue("there is more than questions to lose", impact.hasMoreThanQuestions)
    }

    @Test
    fun anUntouchedPaperReportsOnlyItsQuestions() = runBlocking {
        val impact = repository.deleteImpact("p1")
        assertEquals(1, impact.questions)
        assertEquals(0, impact.attempts)
        assertEquals(0, impact.bookmarks)
        assertEquals(0, impact.schedules)
        assertTrue("nothing beyond the questions is at stake", !impact.hasMoreThanQuestions)
    }

    @Test
    fun requestingDeleteOnlyAsksItDoesNotDelete() {
        val app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
        val viewModel = LibraryViewModel(app)

        viewModel.requestDelete("p1", "History")
        shadowOf(Looper.getMainLooper()).idle()
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (viewModel.pendingDelete.value != null) return@repeat
            Thread.sleep(5)
        }

        val pending = viewModel.pendingDelete.value
        assertNotNull("a confirmation must be raised, not a delete", pending)
        assertEquals("p1", pending!!.paperId)
        assertEquals("History", pending.title)

        // Cancelling must leave the paper alone.
        viewModel.cancelDelete()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(viewModel.pendingDelete.value)
        assertEquals(1, runBlocking { repository.getQuestionsForPaper("p1").size })
    }
}