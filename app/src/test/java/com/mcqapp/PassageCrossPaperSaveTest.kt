package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Passage
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.editor.PassageSaveDecision
import com.mcqapp.ui.editor.PassagesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
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
 * H6 regression plus the reviewer follow-ups. The cross-paper confirmation
 * before a whole-group passage move must use a freshly read member count.
 * Adding members from another screen writes the question table only, so
 * `observePassages()` never emits and the screen's cached counts stay stale
 * (often at zero). The decision and the pending state live on the ViewModel
 * so rotation cannot lose the edit mid-query, and staging is single-shot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class PassageCrossPaperSaveTest {

    private lateinit var app: McqApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
        runBlocking {
            app.repository.ensurePaperAndCategory("p1", "Paper 1", "c1", "Cat 1")
            app.repository.ensurePaperAndCategory("p2", "Paper 2", "c2", "Cat 2")
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

    private fun newPassage(title: String, categoryId: String = "c1"): String = runBlocking {
        app.repository.savePassage(Passage(id = "", categoryId = categoryId, title = title))
    }

    private fun member(id: String, categoryId: String, passageId: String) = Question(
        id = id,
        categoryId = categoryId,
        elements = listOf(ContentElement.TextElement("Q $id")),
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a"),
        passageId = passageId
    )

    @Test
    fun offScreenMembershipChangeStillTriggersTheCrossPaperWarning() {
        val pid = newPassage("Group")
        val viewModel = PassagesViewModel(app)

        // The screen collects the passage list while the group is still empty.
        assertTrue(awaitCondition {
            viewModel.state.value.papers.isNotEmpty() &&
                (viewModel.state.value.memberCounts[pid] ?: 0) == 0
        })

        // Membership changes off-screen: only the question table is written,
        // which does not emit through observePassages(). The cached count on
        // the screen is therefore still zero.
        runBlocking {
            app.repository.saveQuestion(member("q1", "c1", pid))
            app.repository.saveQuestion(member("q2", "c1", pid))
        }
        assertTrue("screen's cached count is stale by design", awaitCondition {
            (viewModel.state.value.memberCounts[pid] ?: 0) == 0
        })
        assertEquals(2, runBlocking { app.repository.passageMemberCount(pid) })

        val edited = Passage(pid, "c2", "Group")
        // The fresh-count decision still reports a cross-paper move. Assert it
        // before staging so it cannot race the confirm call below.
        val decision = runOffMainWhilePumping(condition = "fresh cross-paper decision") {
            viewModel.decideSave(edited, "p1", "p2")
        }
        assertEquals(PassageSaveDecision.ConfirmCrossPaper, decision)

        val completions = CopyOnWriteArrayList<Unit>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.saveCompleted.collect { completions.add(it) }
        }
        viewModel.stageSave(edited, "p1", "p2")

        // F1/F3: the ViewModel publishes the confirmation and writes nothing
        // while it is pending.
        assertTrue(awaitCondition { viewModel.pendingCrossPaperSave.value?.id == pid })
        assertEquals("c1", runBlocking { app.repository.getPassage(pid) }?.categoryId)

        // Confirm immediately: it must not be swallowed while the stage job
        // is still unwinding, and must write exactly once.
        viewModel.confirmPending()
        assertTrue(awaitCondition {
            runBlocking { app.repository.getPassage(pid) }?.categoryId == "c2" &&
                viewModel.pendingCrossPaperSave.value == null
        })
        assertEquals("c2", runBlocking { app.repository.getPassage(pid) }?.categoryId)
        assertEquals(1, completions.size)
        collector.cancel()
    }

    @Test
    fun cancelPendingWritesNothingAndCannotBeConfirmedAfterwards() {
        val pid = newPassage("Group")
        runBlocking { app.repository.saveQuestion(member("q1", "c1", pid)) }
        val viewModel = PassagesViewModel(app)

        viewModel.stageSave(Passage(pid, "c2", "Group"), "p1", "p2")
        assertTrue(awaitCondition { viewModel.pendingCrossPaperSave.value?.id == pid })

        viewModel.cancelPending()
        assertEquals(null, viewModel.pendingCrossPaperSave.value)
        assertEquals("c1", runBlocking { app.repository.getPassage(pid) }?.categoryId)
        // A confirm after Cancel has no pending passage to write.
        viewModel.confirmPending()
        assertEquals("c1", runBlocking { app.repository.getPassage(pid) }?.categoryId)
    }

    @Test
    fun secondStageCallWhileInFlightDoesNotDuplicate() {
        val first = newPassage("First")
        val second = newPassage("Second")
        val viewModel = PassagesViewModel(app)

        // Both calls are made before the main looper is pumped, so the first
        // claims the in-flight flag synchronously and the second is ignored.
        viewModel.stageSave(Passage(first, "c1", "First-changed"), "p1", "p1")
        viewModel.stageSave(Passage(second, "c1", "Second-changed"), "p1", "p1")

        assertTrue(awaitCondition {
            runBlocking { app.repository.getPassage(first) }?.title == "First-changed"
        })
        assertEquals("Second", runBlocking { app.repository.getPassage(second) }?.title)
    }

    @Test
    fun cancelDuringInFlightDecisionWritesNothingAndShowsNoWarning() {
        // Cross-paper edit: if the cancelled decision were allowed to finish it
        // would publish a pending warning.
        val cross = newPassage("Cross")
        runBlocking { app.repository.saveQuestion(member("cq1", "c1", cross)) }
        val crossVm = PassagesViewModel(app)
        crossVm.stageSave(Passage(cross, "c2", "Cross"), "p1", "p2")
        crossVm.cancelPending()

        // Same-paper edit: if allowed to finish it would write the abandoned
        // change straight to the repository.
        val same = newPassage("Same")
        runBlocking { app.repository.saveQuestion(member("sq1", "c1", same)) }
        val sameVm = PassagesViewModel(app)
        sameVm.stageSave(Passage(same, "c1", "Same-changed"), "p1", "p1")
        sameVm.cancelPending()

        // Give both staged jobs every chance to run; cancellation must hold.
        repeat(10) { shadowOf(Looper.getMainLooper()).idle() }
        assertEquals(null, crossVm.pendingCrossPaperSave.value)
        assertEquals("c1", runBlocking { app.repository.getPassage(cross) }?.categoryId)
        assertEquals("Same", runBlocking { app.repository.getPassage(same) }?.title)
        assertEquals(false, crossVm.isDeciding.value)
        assertEquals(false, sameVm.isDeciding.value)
    }

    @Test
    fun cancelDuringInFlightConfirmWritesNothing() {
        val pid = newPassage("Group")
        runBlocking { app.repository.saveQuestion(member("q1", "c1", pid)) }
        val viewModel = PassagesViewModel(app)
        viewModel.stageSave(Passage(pid, "c2", "Group"), "p1", "p2")
        assertTrue(awaitCondition { viewModel.pendingCrossPaperSave.value?.id == pid })

        viewModel.confirmPending()
        viewModel.cancelPending() // back/dismiss while the confirmed write is in flight

        repeat(10) { shadowOf(Looper.getMainLooper()).idle() }
        assertEquals(null, viewModel.pendingCrossPaperSave.value)
        assertEquals(false, viewModel.isConfirming.value)
        assertEquals("c1", runBlocking { app.repository.getPassage(pid) }?.categoryId)
    }

    @Test
    fun samePaperSaveIsNotBlockedEvenWithMembers() {
        val pid = newPassage("Group")
        runBlocking { app.repository.saveQuestion(member("q1", "c1", pid)) }
        val viewModel = PassagesViewModel(app)

        val decision = runOffMainWhilePumping(condition = "same-paper save decision") {
            viewModel.decideSave(
                Passage(pid, "c1", "Group"),
                originalPaperId = "p1",
                newPaperId = "p1"
            )
        }
        assertEquals(PassageSaveDecision.Save, decision)
    }
}
