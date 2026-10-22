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
 * The tree is `cP` => `cA` (q1, with an answer key) and `cB` (q2, no key), so
 * the ungraded count must be exactly 1 for either selection. That is the only
 * version of this test that proves the count follows the scope: 0 is also what
 * a scope that lost the filter reports, so a graded-only seed could not tell
 * "correct" from "silently lost the filter". `saveQuestion` accepts an empty
 * key set — imported questions land the same way — so q2 is a real ungraded
 * question, not a stub.
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

    private fun question(
        id: String,
        categoryId: String,
        correct: Set<String> = setOf("$id-a")
    ) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id?",
        options = listOf(
            QuestionOption("$id-a", "A"),
            QuestionOption("$id-b", "B")
        ),
        correctOptionIds = correct
    )

    /**
     * `p1` nested: `cP` with children `cA` (q1, graded) and `cB` (q2, no
     * answer key). Ticking only `cP` must serve both and count exactly one of
     * them ungraded.
     */
    private fun seedParentWithChild() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "cP", "Parent")
        listOf("cA" to 0, "cB" to 1).forEach { (id, order) ->
            repository.db().categoryDao().upsert(
                CategoryEntity(
                    id = id,
                    paperId = "p1",
                    title = "Child $id",
                    parentId = "cP",
                    sortOrder = order
                )
            )
        }
        repository.saveQuestion(question("q1", "cA"))
        repository.saveQuestion(question("q2", "cB", correct = emptySet()))
    }

    @Test
    fun aTickedParentServesItsChildQuestions() {
        seedParentWithChild()
        val viewModel = TestViewModel(app, "p1", listOf("cP"))
        assertTrue(
            "the session never loaded: state=${viewModel.state.value}",
            awaitCondition { !viewModel.state.value.loading }
        )

        val ids = viewModel.state.value.order.idsInOrder()
        assertEquals(
            "a parent selection must expand to its children: state=${viewModel.state.value}",
            setOf("q1", "q2"),
            ids.toSet()
        )
        // The same expanded scope drives the ungraded counter (:276). Exactly
        // q2 has no key: counting every served question would say 2, and a
        // scope that lost the filter would say 0.
        assertEquals(
            "exactly one served question has no key: state=${viewModel.state.value}",
            1,
            viewModel.state.value.ungradedTotal
        )
    }

    @Test
    fun aTickedChildStillServesItsOwnQuestions() {
        // The expansion must not change what a leaf selection already did.
        seedParentWithChild()
        val viewModel = TestViewModel(app, "p1", listOf("cB"))
        assertTrue(
            "the session never loaded: state=${viewModel.state.value}",
            awaitCondition { !viewModel.state.value.loading }
        )

        assertEquals(
            "state=${viewModel.state.value}",
            listOf("q2"),
            viewModel.state.value.order.idsInOrder()
        )
        assertEquals(
            "the leaf's ungraded question must be counted: state=${viewModel.state.value}",
            1,
            viewModel.state.value.ungradedTotal
        )
    }
}
