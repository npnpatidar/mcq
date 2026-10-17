package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.AttemptEntity
import com.mcqapp.data.local.QuestionResultEntity
import com.mcqapp.ui.results.ResultsViewModel
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
 * ResultsViewModel had no test, and it carried the same defect A6 found in the
 * test and study screens: a failed load left `loading` true, so the screen
 * showed a spinner forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class ResultsViewModelTest {

    private lateinit var app: McqApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    }

    @After
    fun tearDown() {
        try {
            // The database is a process-wide singleton; a failure test closes it.
            AppDatabase::class.java
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .set(null, null)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun awaitIdle(condition: () -> Boolean): Boolean {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun aFailedLoadStopsLoadingAndExplainsWhy() {
        val viewModel = ResultsViewModel(app, 4242L)
        app.repository.db().close()
        // The initial load may already have run against an open database.
        viewModel.reload()

        assertTrue("loading must not stay true", awaitIdle { !viewModel.state.value.loading })

        val error = viewModel.state.value.loadError
        assertNotNull("a failed load must say so", error)
        assertTrue(error!!.startsWith("Could not load this result"))
        assertNull("no attempt could have been read", viewModel.state.value.attempt)
    }

    @Test
    fun aStaleInitialLoadCannotWipeTheFailedReloadError() {
        // The constructor's load may still be in flight when the database is
        // closed and reload() starts. A stale success from that first load
        // must not clear the newer failure's error.
        val viewModel = ResultsViewModel(app, 4242L)
        app.repository.db().close()
        viewModel.reload()

        assertTrue("loading must not stay true", awaitIdle { !viewModel.state.value.loading })
        assertNotNull(
            "the failed reload's error must survive a stale initial load",
            viewModel.state.value.loadError
        )
    }

    @Test
    fun theStateCarriesNoErrorBeforeAnythingIsLoaded() {
        val viewModel = ResultsViewModel(app, 1L)
        assertNull(viewModel.state.value.loadError)
        assertEquals(0, viewModel.state.value.aggregates.total)
    }

    @Test
    fun ungradedQuestionsStayOutOfTheCategoryBreakdown() = runBlocking {
        val dao = app.repository.db().attemptDao()
        val attemptId = dao.insertAttempt(
            AttemptEntity(
                paperId = "p1",
                title = "Paper",
                totalQuestions = 3,
                correctCount = 1,
                wrongCount = 1,
                skippedCount = 0,
                score = 1.0,
                maxScore = 2.0,
                durationSeconds = 60,
                finishedAt = 0L
            )
        )
        dao.insertResults(
            listOf(
                resultEntity(attemptId, "q1", "Cat", correct = true, graded = true),
                resultEntity(attemptId, "q2", "Cat", correct = false, graded = true),
                // No answer key: excluded from the denominator entirely.
                resultEntity(attemptId, "q3", "Cat", correct = false, graded = false)
            )
        )
        val rows = app.repository.getResultCategoryBreakdown(attemptId)
        assertEquals(listOf("Cat" to (1 to 2)), rows.map { it.title to (it.correct to it.graded) })
    }

    private fun resultEntity(
        attemptId: Long,
        id: String,
        category: String,
        correct: Boolean,
        graded: Boolean
    ) = QuestionResultEntity(
        attemptId = attemptId,
        questionId = id,
        categoryTitle = category,
        text = "Q $id",
        optionsJson = "[]",
        correctOptionIds = if (graded) "a" else "",
        selectedOptionIds = if (graded) "a" else "",
        isCorrect = correct,
        explanation = ""
    )
}
