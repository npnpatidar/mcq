package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.history.HistoryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The history screen's two top-lists are read when the screen is created and
 * were never read again: the backstack entry keeps its ViewModel, so the
 * lists showed the first read for the entry's whole lifetime — a study
 * session finished elsewhere never reached them, unlike Stats, which re-reads
 * on every resume.
 *
 * Robolectric + the real repository + the real database, so like every
 * DB-backed test it runs on CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class HistoryViewModelRefreshTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("p1")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Q $id",
        options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
        correctOptionIds = setOf("a")
    )

    private suspend fun attempt(
        questions: List<Question>,
        selections: Map<String, Set<String>>,
        finishedAt: Long
    ) = repository.saveAttempt(
        paperId = "p1",
        paperTitle = "Paper",
        questions = questions,
        selections = selections,
        negativeMarking = 0.0,
        durationSeconds = 30,
        finishedAt = finishedAt
    )

    /** Idles the main looper until [condition] holds, or gives up. */
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun refreshReReadsBothTopLists() {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            repository.saveQuestion(question("q2"))
            attempt(
                listOf(question("q1"), question("q2")),
                mapOf("q1" to setOf("b"), "q2" to setOf("a")),
                1000L
            )
        }
        val viewModel = HistoryViewModel(app)
        assertTrue(awaitCondition { viewModel.hardest.value.isNotEmpty() })
        assertTrue(awaitCondition { viewModel.weakest.value.isNotEmpty() })
        assertTrue(
            "the first read sees the attempt",
            viewModel.hardest.value.any { it.questionId == "q1" }
        )
        assertEquals(2, viewModel.weakest.value.first().graded)

        // The library moves under the screen: a second attempt adds a
        // question that was answered wrong, and grades the category again.
        runBlocking {
            repository.saveQuestion(question("q3"))
            attempt(
                listOf(question("q1"), question("q2"), question("q3")),
                mapOf("q1" to setOf("a"), "q2" to setOf("a"), "q3" to setOf("b")),
                2000L
            )
        }
        // Without a refresh the lists keep the first read: q3 is not in the
        // hardest list, and the category still counts two graded answers.
        viewModel.refresh()
        assertTrue(
            "the refreshed hardest list must see the new attempt",
            awaitCondition { viewModel.hardest.value.any { it.questionId == "q3" } }
        )
        assertTrue(
            "the refreshed weakest list must see the new attempt",
            awaitCondition { viewModel.weakest.value.first().graded == 5 }
        )
    }
}
