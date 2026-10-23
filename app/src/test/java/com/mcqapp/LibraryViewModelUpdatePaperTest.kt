package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.Paper
import com.mcqapp.ui.library.LibraryViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/**
 * Manage → Edit paper must rewrite the paper's details in place without
 * touching its categories or questions, and must say so when the paper is
 * already gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class LibraryViewModelUpdatePaperTest {

    private lateinit var app: McqApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
        runBlocking {
            app.repository.savePaper(
                Paper(
                    id = "p1",
                    title = "Old title",
                    description = "old",
                    durationMinutes = 20,
                    negativeMarking = 0.25
                )
            )
        }
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

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun updatePaperRewritesDetailsAndKeepsTheIdentity() {
        val viewModel = LibraryViewModel(app)
        viewModel.updatePaper("p1", "New title", "new description", 45, 0.5)

        assertTrue(awaitCondition {
            val paper = runBlocking { app.repository.getPaper("p1") }
            paper?.title == "New title"
        })
        val paper = runBlocking { app.repository.getPaper("p1") }
        assertNotNull(paper)
        assertEquals("p1", paper!!.id)
        assertEquals("new description", paper.description)
        assertEquals(45, paper.durationMinutes)
        assertEquals(0.5, paper.negativeMarking, 0.0)
    }

    @Test
    fun anOversizedDurationIsClampedLikeAnImport() {
        val viewModel = LibraryViewModel(app)
        viewModel.updatePaper("p1", "Clamped", "", 10_000_000, 0.0)

        assertTrue(awaitCondition {
            runBlocking { app.repository.getPaper("p1") }?.durationMinutes == 10_080
        })
    }

    @Test
    fun updatingAMissingPaperReportsAnErrorInsteadOfThrowing() {
        val viewModel = LibraryViewModel(app)
        viewModel.updatePaper("gone", "whatever", "", 30, 0.0)

        assertTrue(awaitCondition {
            viewModel.exportError.value?.contains("no longer exists") == true
        })
        // The stored paper is untouched.
        assertEquals("Old title", runBlocking { app.repository.getPaper("p1") }?.title)
    }
}
