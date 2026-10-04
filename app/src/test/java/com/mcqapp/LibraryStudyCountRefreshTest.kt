package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.library.LibraryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The library's Study badge counts what is due, and a study session rewrites
 * those schedules while the library is off screen.
 *
 * `studyCounts` used to be recomputed only when the *paper list* re-emitted,
 * plus a refresh fired from the Study button's own click handler — which runs
 * on the way *in*, before the session has changed anything. The badge was
 * therefore one navigation stale, and only caught up after the user tapped
 * Study a second time and came back. The screen now re-reads the counts on
 * resume; this pins the half that actually holds the data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class LibraryStudyCountRefreshTest {

    private lateinit var viewModel: LibraryViewModel
    private lateinit var app: McqApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
        viewModel = LibraryViewModel(app)
        runBlocking {
            val repo = app.repository
            repo.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repo.saveQuestion(
                com.mcqapp.domain.Question(
                    id = "q1",
                    categoryId = "c1",
                    text = "Q1",
                    options = listOf(
                        com.mcqapp.domain.QuestionOption("a", "A"),
                        com.mcqapp.domain.QuestionOption("b", "B")
                    ),
                    correctOptionIds = setOf("a")
                )
            )
        }
        settle()
    }

    @After
    fun tearDown() {
        try {
            AppDatabase::class.java
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .set(null, null)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun settle() {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun refreshingPicksUpSchedulesRewrittenByAStudySession() {
        settle()
        viewModel.refreshStudyCounts()
        settle()
        val before = viewModel.studyCounts.value["p1"]
        assertTrue("expected the seeded paper to have counts", before != null)

        // A session grades the card, which moves its due date forward. The
        // library cannot see that unless it re-reads, which is the whole point
        // of refreshing on resume rather than on click.
        runBlocking { app.repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD) }
        settle()

        // Nothing else re-emits the paper list here, so the badge can only move
        // if refreshStudyCounts() really does re-read.
        viewModel.refreshStudyCounts()
        settle()

        val after = viewModel.studyCounts.value["p1"]
        assertTrue("expected counts after the refresh", after != null)
        assertTrue(
            "the badge did not change after a study session was recorded",
            after != before
        )
    }

    @Test
    fun refreshingIsIdempotentWhenNothingChanged() {
        settle()
        viewModel.refreshStudyCounts()
        settle()
        val first = viewModel.studyCounts.value
        viewModel.refreshStudyCounts()
        settle()
        assertEquals("a redundant refresh must not change the counts", first, viewModel.studyCounts.value)
    }
}