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
import org.junit.Assert.assertFalse
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

    /**
     * Runs a suspend call off the main thread, pumping the looper until it
     * finishes.
     *
     * A snapshot's write goes through DataStore, whose actor runs the transform
     * in the *caller's* context. From `runBlocking` on this thread that context
     * is the blocked looper's own event loop, so it only works while nothing
     * else is waiting on the looper - and a session left behind by an earlier
     * test is. Seeding the restore through here keeps the looper free, the same
     * way [clearProgressWithoutBlockingMain] does for the teardown.
     */
    private fun <T> offMain(block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                done.complete(block())
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }
        var waited = 0
        while (!done.isCompleted && waited < 600) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
            waited++
        }
        assertTrue("the seeded snapshot never landed", done.isCompleted)
        return runBlocking { done.await() }
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
        offMain {
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
        offMain {
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
        offMain {
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

    /**
     * The fresh session's countdown must not run behind the resume dialog.
     * The dialog demands an explicit choice, and the count used to go on
     * behind it: reaching zero auto-submitted an empty attempt, and submit()
     * clears the snapshot — destroying the answers the dialog was offering —
     * while the choice was still open.
     */
    @Test
    fun theFreshCountdownDoesNotRunBehindTheResumeDialog() {
        seed(3)
        // The first session runs untimed: only the relaunch's countdown is
        // under test, and a second timer on the same looper would reach zero
        // on its own once the virtual clock advances.
        val first = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(awaitCondition { first.state.value.current?.id == "q0" })
        first.toggleOption("a")
        first.next()
        assertTrue(awaitCondition { first.state.value.current?.id == "q1" })
        first.toggleOption("b")
        assertTrue("the answers never reached the store", awaitCondition { answerRows().size == 2 })

        // The same paper, timed from here on: the relaunch now builds a
        // one-minute countdown that used to tick away behind the offer.
        offMain {
            // updateFields, not upsert: a REPLACE upsert deletes the p1 row
            // and cascades its categories, questions and the test_progress
            // answers away, leaving the relaunch a question-less paper whose
            // load clears the slot — no offer, ever.
            app.repository.db().paperDao().updateFields(
                "p1", "Paper", "", 1, 0.0
            )
        }
        val relaunched = TestViewModel(app, "p1", listOf("c1"))
        assertTrue("no resume offer was made", awaitCondition { relaunched.state.value.resumeOffer != null })
        assertEquals("the fresh session must open with a full countdown", 60, relaunched.state.value.remainingSeconds)

        // A whole timed sitting behind the dialog: virtual time, so free.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(61))

        assertTrue("the offer must still be up", relaunched.state.value.resumeOffer != null)
        assertEquals(
            "the countdown must not have ticked behind the dialog",
            60, relaunched.state.value.remainingSeconds
        )
        assertFalse("no empty attempt may be submitted behind the dialog", relaunched.state.value.submitted)
        assertEquals("the offered answers must not be cleared", 2, answerRows().size)

        // Resolving the dialog is what starts the fresh countdown.
        relaunched.discardResume()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertTrue(
            "the fresh countdown must run once the offer resolves: "
                + "remaining=${relaunched.state.value.remainingSeconds}s",
            relaunched.state.value.remainingSeconds < 60
        )
    }

    /**
     * The screen stays interactive while the attempt saves: submit() sets
     * `submitted`/`saving` synchronously and then writes rows for seconds on a
     * big paper. toggleOption/toggleFlag/goTo used to keep working through
     * that window - and after it, since the result stays on screen - changing
     * what the user saw without changing either the scored map or the
     * persisted rows (the progress saver bails on `submitted`), so the score
     * silently disagreed with the last thing on screen. Every mutating
     * entry point now refuses once the save started.
     */
    @Test
    fun aSubmittedAttemptRefusesFurtherEdits() {
        seed(3)
        val vm = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(awaitCondition { vm.state.value.current?.id == "q0" })

        vm.toggleOption("a")
        assertEquals(setOf("a"), vm.state.value.selections["q0"])
        vm.toggleFlag()
        assertTrue("q0" in vm.state.value.flagged)

        vm.submit()
        // The save runs for real on the looper: wait for it to land, so the
        // refusals under test cover the post-save window too, not just the
        // in-flight one.
        assertTrue(
            "the save never landed",
            awaitCondition { vm.state.value.attemptId != null && !vm.state.value.saving }
        )
        assertTrue("the attempt must stay submitted", vm.state.value.submitted)

        val selections = vm.state.value.selections
        val flagged = vm.state.value.flagged
        val currentIndex = vm.state.value.currentIndex

        vm.toggleOption("b")
        vm.toggleFlag()
        vm.goTo(2)

        assertEquals(
            "a post-submit answer edit changed the scored map",
            selections, vm.state.value.selections
        )
        assertEquals(
            "a post-submit flag flip was taken",
            flagged, vm.state.value.flagged
        )
        assertEquals(
            "a post-submit navigation was taken",
            currentIndex, vm.state.value.currentIndex
        )
    }

    @Test
    fun aLegacyOrderSurvivesASecondResume() {
        seed(5)
        // Pre-seed snapshots carried their order in `questionIds` and had no
        // seed. Resuming used to materialise that order and then persist an
        // empty `questionIds` with no seed, so the *second* relaunch fell back
        // to authored order and silently re-shuffled the sitting.
        offMain {
            app.repository.restoreTestProgress(
                com.mcqapp.domain.TestSnapshot(
                    paperId = "p1",
                    categoryIds = listOf("c1"),
                    questionIds = listOf("q3", "q0", "q4", "q1", "q2"),
                    currentIndex = 1,
                    questionSeed = null
                ).toJson()
            )
        }

        val first = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(awaitCondition { first.state.value.resumeOffer != null })
        first.resume()
        assertEquals(
            "the legacy order is restored",
            listOf("q3", "q0", "q4", "q1", "q2"),
            first.state.value.order.idsInOrder()
        )
        // An answer persists the session for the next relaunch.
        first.toggleOption("a")
        first.next()

        val second = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(awaitCondition { second.state.value.resumeOffer != null })
        second.resume()
        assertEquals(
            "the second resume kept the legacy order",
            listOf("q3", "q0", "q4", "q1", "q2"),
            second.state.value.order.idsInOrder()
        )
    }
}
