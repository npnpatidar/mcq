package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.test.TestViewModel
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
 * Anki's reviewer reads one card ahead. In a big paper that is the difference
 * between Next being a frame and Next being a database read; this pins that
 * the card is resolved before it is asked for, so showing it needs no pump.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class TestSessionPrefetchTest {

    private lateinit var app: McqApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    }

    @After
    fun tearDown() {
        runBlocking { app.repository.clearTestProgress() }
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
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    /** Gives already-launched read-ahead coroutines time to land. */
    private fun settle() {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun seed(count: Int) = runBlocking {
        app.repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repeat(count) { i ->
            app.repository.saveQuestion(
                Question(
                    id = "q$i",
                    categoryId = "c1",
                    elements = listOf(ContentElement.TextElement("Q q$i")),
                    options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
                    correctOptionIds = setOf("a")
                )
            )
        }
    }

    @Test
    fun theNextCardIsResolvedBeforeItIsShown() {
        seed(3)
        val vm = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(awaitCondition { vm.state.value.current?.id == "q0" })
        settle()

        // No idle after Next: a prefetched card is already in the state.
        vm.next()
        assertEquals("q1", vm.state.value.current?.id)
        assertEquals(
            "Q q1",
            (vm.state.value.current?.elements?.firstOrNull() as? ContentElement.TextElement)?.text
        )

        // And the read-ahead keeps following the reader.
        settle()
        vm.next()
        assertEquals("q2", vm.state.value.current?.id)
    }
}
