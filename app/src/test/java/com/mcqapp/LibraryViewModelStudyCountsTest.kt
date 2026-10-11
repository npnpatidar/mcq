package com.mcqapp

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.util.Logger
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
 * The Library screen's badge collector must survive the ViewModel's own
 * construction: `viewModelScope` dispatches with Main.immediate, so a
 * StateFlow's first emission runs synchronously inside the constructor,
 * and `_studyCounts` used to be declared after the init block — still null
 * at that moment, every app start logged a swallowed
 * "getStudyCounts failed" NullPointerException (visible in logs/app.log
 * from the 0.0.16-era debug build).
 *
 * The write's loss was invisible to assertions until Logger grew a
 * recentErrors sink, which is what these tests pin: constructing the
 * ViewModel on an empty library, and on a seeded one, logs nothing at
 * error level from the badge collectors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class LibraryViewModelStudyCountsTest {

    @Before
    fun reset() = Logger.clearRecentErrors()

    @After
    fun cleanup() = runBlocking {
        (ApplicationProvider.getApplicationContext<McqApplication>()).repository.deletePaper("p1")
        Logger.clearRecentErrors()
    }

    @Test
    fun constructingOnAnEmptyLibraryDoesNotLogAnError() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = com.mcqapp.ui.library.LibraryViewModel(app)
        // The synchronous first emission (empty papers -> no suspension)
        // is exactly the path that used to NPE before the property existed.
        assertTrue(vm.studyCounts.value.isEmpty())
        assertEquals(
            "the badge collectors must not log errors on construction",
            emptyList<String>(),
            Logger.recentErrors()
        )
    }

    @Test
    fun constructingOnASeededLibraryPopulatesCountsQuietly() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<McqApplication>()
        val repository = (app as McqApplication).repository
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(
            com.mcqapp.domain.Question(
                id = "q1",
                categoryId = "c1",
                elements = listOf(com.mcqapp.domain.ContentElement.TextElement("Q?")),
                options = listOf(
                    com.mcqapp.domain.QuestionOption("a", "A"),
                    com.mcqapp.domain.QuestionOption("b", "B")
                ),
                correctOptionIds = setOf("a")
            )
        )

        val vm = com.mcqapp.ui.library.LibraryViewModel(app)
        // Non-empty papers make the collector suspend into Room and resume
        // on the main looper; pump it rather than sleeping (the test method
        // itself runs on Main, so a sleep would starve the collector).
        val looper = android.os.Looper.getMainLooper()
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.studyCounts.value.isEmpty() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(looper).runToEndOfTasks()
            Thread.sleep(20)
        }
        assertTrue("study counts never arrived", vm.studyCounts.value.isNotEmpty())
        assertEquals(emptyList<String>(), Logger.recentErrors())
    }
}
