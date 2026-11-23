package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.repository.SuspendStore
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Study again" must re-resolve a tag or mistake pool: both are snapshots taken
 * at load, so a card suspended mid-sitting or a question that is no longer
 * wrong must not silently replay. The mistake case also pins the category
 * scope — a paper-wide standing used to leak a card from another category.
 *
 * Robolectric + the real repository + the real database, so like every
 * DB-backed test it runs on CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class StudyScopedRestartTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val day = 86_400_000L

    @After
    fun tearDown() {
        AppDatabase::class.java
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .set(null, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Runs the main looper until [condition] holds, or gives up. */
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun question(id: String, categoryId: String, tags: List<String> = emptyList()) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id?",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a"),
        tags = tags
    )

    /** Makes the card due so the pool never depends on the new-cards toggle. */
    private suspend fun makeDue(id: String) {
        val then = System.currentTimeMillis() - 30 * day
        app.repository.recordStudyReview("p1", id, ReviewGrade.GOOD, now = then)
        app.repository.recordStudyReview("p1", id, ReviewGrade.AGAIN, now = then + 3_600_000L)
    }

    @Test
    fun aTagCardSuspendedMidSittingIsNotReplayedByStudyAgain() = runBlocking {
        app.repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        app.repository.saveQuestion(question("q1", "c1", tags = listOf("Physics")))
        makeDue("q1")

        val viewModel = StudyViewModel(app, "p1", tag = "physics")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        assertEquals("q1", viewModel.state.value.current?.questionId)

        SuspendStore(AppDatabase.get(app)).suspend("p1", "q1")

        viewModel.restart()
        assertTrue(
            "state=${viewModel.state.value} — the suspended card must not be replayed",
            awaitCondition { viewModel.state.value.finished && viewModel.state.value.current == null }
        )
    }

    @Test
    fun aMistakeThatIsNoLongerWrongIsNotReplayedAndStaysInItsCategory() = runBlocking {
        app.repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        app.repository.db().categoryDao().upsert(
            CategoryEntity(id = "c2", paperId = "p1", title = "Other", parentId = null, sortOrder = 1)
        )
        val inScope = question("q1", "c1")
        val other = question("q2", "c2")
        app.repository.saveQuestion(inScope)
        app.repository.saveQuestion(other)
        makeDue("q1")
        makeDue("q2")

        val base = System.currentTimeMillis()
        app.repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = listOf(inScope, other),
            selections = mapOf("q1" to setOf("q1-x"), "q2" to setOf("q2-x")),
            negativeMarking = 0.0,
            durationSeconds = 60,
            finishedAt = base
        )

        val viewModel = StudyViewModel(app, "p1", categoryIds = listOf("c1"), mistakes = true)
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        assertEquals(
            "a mistake sitting must stay inside its category scope",
            "q1",
            viewModel.state.value.current?.questionId
        )

        // A later correct answer clears q1's standing; the old snapshot would
        // still serve it on restart.
        app.repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = listOf(inScope, other),
            selections = mapOf("q1" to setOf("q1-a"), "q2" to setOf("q2-x")),
            negativeMarking = 0.0,
            durationSeconds = 60,
            finishedAt = base + 60_000L
        )

        viewModel.restart()
        assertTrue(
            "state=${viewModel.state.value} — a cured mistake must not be replayed",
            awaitCondition { viewModel.state.value.finished && viewModel.state.value.current == null }
        )
        assertNull(viewModel.state.value.current)
    }
}
