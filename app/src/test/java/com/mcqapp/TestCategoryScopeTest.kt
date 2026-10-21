package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.test.TestViewModel
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
 * Test mode expands the route's ticked ids before it queries (§6.4). The route
 * carries `CategorySelection.tickedIds` — one entry per branch the learner
 * ticked — so ticking a parent sends the parent's id, not its children's. Both
 * the session order and the ungraded count filter `questions.categoryId`, and
 * no question sits directly in a parent, so without the expansion a parent
 * selection serves zero questions and counts none of them.
 *
 * This has to construct the ViewModel: the defect is the wiring between the
 * route and the repository, and a repository-level test calls below it.
 *
 * Robolectric + the real repository, so it runs on CI's x86_64 runners and not
 * on the ARM64 host (where every Robolectric class aborts with SIGABRT).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class TestCategoryScopeTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @After
    fun cleanup() = runBlocking {
        repository.resetSchedulerConfig()
        repository.deletePaper("p1")
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Idles the main looper until [condition] holds, or gives up. */
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun question(id: String, categoryId: String) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id?",
        options = listOf(
            QuestionOption("$id-a", "A"),
            QuestionOption("$id-b", "B")
        ),
        correctOptionIds = setOf("$id-a")
    )

    /**
     * `p1` nested: `cP` with child `cA`, and q1 in `cA`. Ticking only `cP`
     * must serve q1 and count it ungraded.
     */
    private fun seedParentWithChild() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "cP", "Parent")
        repository.db().categoryDao().upsert(
            CategoryEntity(
                id = "cA",
                paperId = "p1",
                title = "Child",
                parentId = "cP",
                sortOrder = 0
            )
        )
        repository.saveQuestion(question("q1", "cA"))
    }

    @Test
    fun aTickedParentServesItsChildQuestions() {
        seedParentWithChild()
        val viewModel = TestViewModel(app, "p1", listOf("cP"))
        assertTrue(awaitCondition { !viewModel.state.value.loading })

        val ids = viewModel.state.value.order.idsInOrder()
        assertEquals("a parent selection must expand to its children", 1, ids.size)
        assertEquals("q1", ids.single())
        // The same expanded scope drives the ungraded counter (:264).
        assertEquals(1, viewModel.state.value.ungradedTotal)
    }

    @Test
    fun aTickedChildStillServesItsOwnQuestions() {
        // The expansion must not change what a leaf selection already did.
        seedParentWithChild()
        val viewModel = TestViewModel(app, "p1", listOf("cA"))
        assertTrue(awaitCondition { !viewModel.state.value.loading })

        assertEquals(listOf("q1"), viewModel.state.value.order.idsInOrder())
        assertEquals(1, viewModel.state.value.ungradedTotal)
    }
}
