package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.ui.results.ResultsViewModel
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
    fun theStateCarriesNoErrorBeforeAnythingIsLoaded() {
        val viewModel = ResultsViewModel(app, 1L)
        assertNull(viewModel.state.value.loadError)
        assertTrue(viewModel.state.value.results.isEmpty())
    }

    @Test
    fun ungradedQuestionsStayOutOfTheCategoryBreakdown() {
        val state = com.mcqapp.ui.results.ResultsUiState(
            loading = false,
            results = listOf(
                result("q1", "Cat", correct = true, graded = true),
                result("q2", "Cat", correct = false, graded = true),
                // No answer key: excluded from the denominator entirely.
                result("q3", "Cat", correct = false, graded = false)
            )
        )
        assertEquals(listOf("Cat" to (1 to 2)), state.categoryBreakdown)
    }

    private fun result(
        id: String,
        category: String,
        correct: Boolean,
        graded: Boolean
    ) = com.mcqapp.domain.QuestionResult(
        attemptId = 1L,
        questionId = id,
        categoryTitle = category,
        elements = listOf(com.mcqapp.domain.ContentElement.TextElement("Q $id")),
        options = emptyList(),
        correctOptionIds = if (graded) setOf("a") else emptySet(),
        selectedOptionIds = if (graded) setOf("a") else emptySet(),
        isCorrect = correct,
        explanationElements = emptyList()
    )
}
