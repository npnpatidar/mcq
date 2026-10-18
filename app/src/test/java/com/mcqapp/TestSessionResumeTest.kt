package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.test.TestViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A killed test session is offered back with the answers it had.
 *
 * The answers are `test_progress` rows rather than part of the snapshot
 * document now, so this walks the whole relaunch path: answer, let the autosave
 * land, build a fresh view model for the same paper, and check both the offer
 * and what resuming it restores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class TestSessionResumeTest {

    private lateinit var app: McqApplication

    @get:Rule
    val watchdog = HangWatchdogRule(seconds = 90)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    }

    @After
    fun tearDown() {
        try {
            clearProgressWithoutBlockingMain()
            AppDatabase::class.java
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .set(null, null)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /**
     * Clears the snapshot without blocking the main looper: the autosaves run in
     * `viewModelScope` (Dispatchers.Main) and DataStore dispatches its write back
     * onto it, so a plain `runBlocking` on the main thread would wait for a
     * looper that can never run. Same dance as [TestSessionPrefetchTest].
     */
    private fun clearProgressWithoutBlockingMain() {
        val done = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.repository.clearTestProgress()
            } finally {
                done.complete(Unit)
            }
        }
        repeat(600) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done.isCompleted) return
            Thread.sleep(5)
        }
        assertTrue("clearTestProgress() never completed", done.isCompleted)
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
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

    /** The answer rows of a session. Room reads are safe to block on here. */
    private fun answerRows(paperId: String = "p1"): List<String> = runBlocking {
        app.repository.db().testProgressDao().getForPaper(paperId).map { it.questionId }
    }

    @Test
    fun aRelaunchedSessionOffersTheAnswersItHad() {
        seed(5)
        val first = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(awaitCondition { first.state.value.current?.id == "q0" })

        first.toggleOption("a")
        first.next()
        assertTrue(awaitCondition { first.state.value.current?.id == "q1" })
        first.toggleOption("b")
        assertTrue("the answers never reached the store", awaitCondition { answerRows().size == 2 })

        // A relaunch: a fresh view model over the same paper and scope.
        val relaunched = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(
            "no resume offer was made",
            awaitCondition { relaunched.state.value.resumeOffer != null }
        )
        val offer = relaunched.state.value.resumeOffer!!
        assertEquals(setOf("q0", "q1"), offer.selections.keys)
        assertEquals(listOf("a"), offer.selections["q0"])
        assertEquals(listOf("b"), offer.selections["q1"])

        relaunched.resume()
        assertEquals(setOf("q0", "q1"), relaunched.state.value.selections.keys)
        assertTrue(awaitCondition { relaunched.state.value.current?.id == "q1" })
        assertEquals(listOf("a"), relaunched.state.value.selections["q0"]?.toList())
    }

    @Test
    fun staleAnswersOfAPaperAreNotOfferedIntoAFreshSession() {
        seed(3)
        runBlocking {
            app.repository.ensurePaperAndCategory("p2", "Other", "c2", "Cat")
            // A session of p1 that got its answers written...
            app.repository.saveTestProgress(
                com.mcqapp.domain.TestSnapshot(paperId = "p1", categoryIds = listOf("c1")),
                listOf(com.mcqapp.domain.TestAnswer("q0", selection = setOf("a")))
            )
            // ...and then lost the one global slot to another paper's session.
            app.repository.saveTestProgress(
                com.mcqapp.domain.TestSnapshot(paperId = "p2", categoryIds = listOf("c2")),
                emptyList()
            )
            assertEquals(listOf("q0"), answerRows())
        }

        val vm = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(awaitCondition { vm.state.value.current?.id == "q0" })
        assertTrue(
            "stale answers were left for the next session",
            awaitCondition { answerRows().isEmpty() }
        )
        assertTrue(vm.state.value.selections.isEmpty())
        assertTrue(vm.state.value.resumeOffer == null)
    }

    @Test
    fun openingAnotherScopeOfTheSamePaperKeepsTheSavedAnswers() {
        seed(3)
        runBlocking {
            // A saved session of this paper whose scope is not the one opened
            // below — a whole-paper sitting and a narrower one, say.
            app.repository.saveTestProgress(
                com.mcqapp.domain.TestSnapshot(
                    paperId = "p1",
                    categoryIds = listOf("c1", "c2"),
                    currentIndex = 1,
                    questionSeed = 3L,
                    questionShuffle = true
                ),
                listOf(com.mcqapp.domain.TestAnswer("q0", selection = setOf("a"), dwellSeconds = 4L))
            )
        }

        // Looking at another scope must not destroy what was saved: only a
        // session whose document is gone leaves harmless leftovers behind.
        val vm = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(awaitCondition { vm.state.value.current?.id == "q0" })
        assertTrue(vm.state.value.resumeOffer == null)
        assertEquals(
            "the saved session's answers were deleted by opening a screen",
            listOf("q0"),
            answerRows()
        )
    }

    @Test
    fun aLegacySnapshotIsAdoptedIntoRowsSoTheAnswersSurvive() {
        seed(4)
        // What an older build — or a restored backup — leaves behind: one
        // document with the answers inlined and no rows.
        runBlocking {
            app.repository.restoreTestProgress(
                com.mcqapp.domain.TestSnapshot(
                    paperId = "p1",
                    categoryIds = listOf("c1"),
                    selections = mapOf("q0" to listOf("a"), "q2" to listOf("b")),
                    currentIndex = 2,
                    questionSeed = 11L,
                    questionShuffle = true
                ).toJson()
            )
        }

        val vm = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(
            "no resume offer was made",
            awaitCondition { vm.state.value.resumeOffer != null }
        )
        assertEquals(setOf("q0", "q2"), vm.state.value.resumeOffer!!.selections.keys)

        vm.resume()
        assertEquals(setOf("q0", "q2"), vm.state.value.selections.keys)
        // Off the document and into rows, so the next answer cannot drop them.
        assertTrue(
            "legacy answers were not adopted as rows",
            awaitCondition { answerRows().toSet() == setOf("q0", "q2") }
        )
    }
}
