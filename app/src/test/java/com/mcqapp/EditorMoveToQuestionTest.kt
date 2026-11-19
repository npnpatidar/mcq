package com.mcqapp

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.editor.EditorOperation
import com.mcqapp.ui.editor.EditorSession
import com.mcqapp.ui.editor.EditorSource
import com.mcqapp.ui.editor.EditorViewModel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * moveToQuestion keeps the editor honest between queue entries: the loaded
 * question's passage membership and new-flag replace the previous
 * question's (they used to leak across the move, silently reassigning the
 * next question's passage on save), and a clean-but-unsavable form can
 * always be left. Robolectric + Room run on the x86_64 CI (and locally
 * under the qemu environment).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class EditorMoveToQuestionTest {

    private lateinit var repository: com.mcqapp.data.repository.McqRepository

    @Before
    fun seed() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<McqApplication>()
        repository = app.repository
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1").copy(passageId = "P"))
        repository.saveQuestion(question("q2"))
        repository.saveQuestion(question("q3"))
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("p1")
        EditorSession.clear()
    }

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        elements = listOf(ContentElement.TextElement("Q $id")),
        options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
        correctOptionIds = setOf("a")
    )

    /** Pumps Robolectric's main looper until [cond] holds (or times out). */
    private fun pumpUntil(cond: () -> Boolean) {
        val looper = Looper.getMainLooper()
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            assertTrue("timed out waiting for the editor", System.currentTimeMillis() < deadline)
            shadowOf(looper).runToEndOfTasks()
            Thread.sleep(20)
        }
    }

    /**
     * Drains the main looper for a bounded window so a suspended Room read,
     * resumed from a background executor, completes before an assertion. Used
     * where the expected result is the absence of a state change (so there is
     * no condition for [pumpUntil] to wait on).
     */
    private fun drainMainLooperForRoom() {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            looper.runToEndOfTasks()
            Thread.sleep(25)
        }
    }

    /**
     * Process death on an import preview leaves the route saying `import` but
     * the in-memory draft gone. The editor must fail closed: it may not load
     * the same-id database row, and save/delete/move must leave it alone
     * (question_editor_audit.md H-04).
     */
    @Test
    fun anImportEditWithoutItsDraftNeverTouchesAStoredRow() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull("the fixture row exists", repository.getQuestion("q1"))

        val vm = EditorViewModel(app, "q1", "p1", "c1", EditorSource.IMPORT)
        val s = vm.state.value
        assertFalse("init must not stay loading", s.loading)
        assertTrue("the failure is explicit", s.importContextMissing)
        assertNotNull("the screen explains why", s.loadError)
        assertEquals("q1", s.questionId)
        assertTrue("no draft content was loaded", s.elements.isEmpty())
        assertTrue("nothing can be saved", !s.canSave)

        var navigated = false
        vm.save { navigated = true }
        vm.delete { navigated = true }
        vm.moveToQuestion("q2") { navigated = true }
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertFalse("no write path fired", navigated)

        // The unrelated stored row is intact.
        assertNotNull(repository.getQuestion("q1"))
    }

    @Test
    fun movingTakesEachQuestionsOwnPassageAndNewFlag() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        EditorSession.start(listOf("q1", "q2"), index = 1)
        val vm = EditorViewModel(app, "q2", "p1", "c1")
        pumpUntil { !vm.state.value.loading }
        assertEquals("q2", vm.state.value.questionId)

        vm.moveToQuestion("q1")
        pumpUntil { vm.state.value.questionId == "q1" }
        assertEquals("P", vm.state.value.passageId)
        assertFalse("an existing question is never new", vm.state.value.isNew)

        // And back: the standalone question's membership must not be the
        // member's leftover — saving it would silently reassign it.
        vm.moveToQuestion("q2")
        pumpUntil { vm.state.value.questionId == "q2" }
        assertEquals(null, vm.state.value.passageId)
    }

    @Test
    fun aCleanButUnsavableFormCanStillBeLeft() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // A demo edge question with no options at all: not savable, but
        // clean — Prev/Next must work from it (they used to trap the reader).
        runBlocking {
            repository.saveQuestion(
                Question(
                    id = "qedge",
                    categoryId = "c1",
                    elements = listOf(ContentElement.TextElement("Edge?")),
                    options = emptyList(),
                    correctOptionIds = emptySet()
                )
            )
        }
        EditorSession.start(listOf("qedge", "q2"), index = 1)
        val vm = EditorViewModel(app, "q2", "p1", "c1")
        pumpUntil { !vm.state.value.loading }

        vm.moveToQuestion("qedge")
        pumpUntil { vm.state.value.questionId == "qedge" }
        assertTrue("the edge form is not savable", !vm.state.value.canSave)
        // Still moves away: nothing was dirty, nothing is lost.
        vm.moveToQuestion("q2")
        pumpUntil { vm.state.value.questionId == "q2" }
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun dirtyInvalidEditsStillBlockTheMove() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        EditorSession.start(listOf("q1", "q2"), index = 0)
        val vm = EditorViewModel(app, "q1", "p1", "c1")
        pumpUntil { !vm.state.value.loading }
        // Blank an option: the form is now dirty and invalid (options cannot
        // be removed below two, but their content can be emptied).
        vm.updateOptionBlock("a", 0, ContentElement.TextElement(""))
        assertTrue(!vm.state.value.canSave)
        vm.moveToQuestion("q2")
        pumpUntil { vm.state.value.moveError != null }
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertEquals("q1", vm.state.value.questionId)
    }

    @Test
    fun switchingCategoryDropsAPassageThatNoLongerFits() = runBlocking {
        val repository = (ApplicationProvider.getApplicationContext<McqApplication>()).repository
        val pid = repository.savePassage(
            com.mcqapp.domain.Passage(id = "", categoryId = "c1", title = "P")
        )
        repository.assignQuestionsToPassage(listOf("q1"), pid)
        val otherCategory = repository.addCategory("p1", "Other", parentId = null)

        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app, "q1", "p1", "c1")
        pumpUntil { !vm.state.value.loading }
        assertEquals(pid, vm.state.value.passageId)

        // The picker only offers the question's category's passages, and a
        // category switch drops an assignment that no longer fits — a
        // member cannot stay in a passage of another category.
        vm.updateCategory(otherCategory)
        assertEquals(null, vm.state.value.passageId)
        assertEquals(otherCategory, vm.state.value.categoryId)

        // Switching back does not resurrect the dropped assignment…
        vm.updateCategory("c1")
        assertEquals(null, vm.state.value.passageId)
        // …but the passage of that category is still pickable. The pick
        // re-checks the passage against the repository before applying it.
        vm.updatePassage(pid)
        pumpUntil { vm.state.value.passageId == pid }
        assertEquals(pid, vm.state.value.passageId)
    }

    /**
     * A pick re-reads passages from Room asynchronously. If the user switches
     * category before that read resumes, the passage is still in its old
     * category and must not be attached to the question — members share one
     * category, so applying it would be a silent membership inconsistency.
     */
    @Test
    fun aPassagePickedBeforeACategorySwitchIsNotApplied() = runBlocking {
        val repository = (ApplicationProvider.getApplicationContext<McqApplication>()).repository
        repository.saveQuestion(question("qpick")) // no passage of its own
        val pid = repository.savePassage(
            com.mcqapp.domain.Passage(id = "", categoryId = "c1", title = "C1 passage")
        )
        val otherCategory = repository.addCategory("p1", "Other", parentId = null)

        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app, "qpick", "p1", "c1")
        pumpUntil { !vm.state.value.loading }

        // Tap the c1 passage, then switch category before the coroutine's Room
        // read resumes (the main looper has not been pumped since the tap).
        vm.updatePassage(pid)
        vm.updateCategory(otherCategory)
        assertEquals(otherCategory, vm.state.value.categoryId)
        assertNull("the pick is not applied synchronously", vm.state.value.passageId)

        // Let the re-check complete. The fresh passage is still a c1 passage,
        // so it no longer fits the question's current category and is dropped.
        drainMainLooperForRoom()
        assertNull("a stale-category passage must not be attached", vm.state.value.passageId)
        assertEquals(otherCategory, vm.state.value.categoryId)
    }

    // ---- review-fix batch (M1, M2, M5) ----

    /**
     * M1: the form stays editable while SAVING. A keystroke typed after the
     * snapshot was captured but before the write finished was marked clean and
     * silently lost on Back; it must remain dirty.
     */
    @Test
    fun anEditDuringAnInFlightWriteIsNotSilentlyDropped() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        EditorSession.start(listOf("q1", "q2"), index = 0)
        val vm = EditorViewModel(app, "q1", "p1", "c1")
        pumpUntil { !vm.state.value.loading }
        assertFalse("the loaded form is clean", vm.state.value.isDirty)

        var navigated = false
        vm.save { navigated = true }
        // save() sets SAVING synchronously and only then dispatches the
        // coroutine; the edit lands before the looper is pumped.
        assertEquals(EditorOperation.SAVING, vm.state.value.operation)
        vm.updateBlock(0, ContentElement.TextElement("typed during the write"))

        pumpUntil { vm.state.value.operation == EditorOperation.IDLE }
        assertTrue("the unpersisted keystroke must stay dirty", vm.state.value.isDirty)
        assertFalse("a dirty form must not be popped out from under the edit", navigated)
    }

    /**
     * The same rule on the move path: a keystroke typed while the pre-save is
     * in flight is not persisted, so the loaded question must not be published
     * over it (the form stays on the current question, still dirty).
     */
    @Test
    fun anEditDuringAMoveSaveStaysOnTheCurrentQuestion() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        EditorSession.start(listOf("q1", "q2"), index = 0)
        val vm = EditorViewModel(app, "q1", "p1", "c1")
        pumpUntil { !vm.state.value.loading }
        vm.updateBlock(0, ContentElement.TextElement("before the move"))
        assertTrue(vm.state.value.isDirty)

        var published = 0
        vm.moveToQuestion("q2") { published++ }
        assertEquals(EditorOperation.MOVING, vm.state.value.operation)
        vm.updateBlock(0, ContentElement.TextElement("typed during the write"))

        pumpUntil { vm.state.value.operation == EditorOperation.IDLE }
        assertEquals("the unpersisted keystroke is not discarded", "q1", vm.state.value.questionId)
        assertTrue(vm.state.value.isDirty)
        assertEquals("the loaded question was never published", 0, published)
    }

    /** M2: a cosmetic re-anchor read must never fail a committed write. */
    @Test
    fun aFailingReanchorStillCompletesTheSave() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val reanchorCalled = AtomicBoolean(false)
        EditorSession.startCursor(
            EditorSession.Cursor(
                size = 1,
                index = 0,
                idAt = { "q1" },
                count = {
                    reanchorCalled.set(true)
                    throw RuntimeException("reanchor read failed")
                },
                indexOf = { 0 }
            )
        )
        val vm = EditorViewModel(app, "q1", "p1", "c1")
        pumpUntil { !vm.state.value.loading }

        var done = false
        vm.save { done = true }
        pumpUntil { vm.state.value.operation == EditorOperation.IDLE }

        assertTrue("the throwing re-anchor was actually invoked", reanchorCalled.get())
        assertTrue("onDone must still run after the write succeeded", done)
        assertNull("a re-anchor failure must not show as a save failure", vm.state.value.saveError)
    }

    /** M5a: a second Prev/Next tap while a move is in flight is refused. */
    @Test
    fun aSecondMoveIsRefusedWhileTheFirstIsInFlight() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        EditorSession.start(listOf("q1", "q2", "q3"), index = 0)
        val vm = EditorViewModel(app, "q1", "p1", "c1")
        pumpUntil { !vm.state.value.loading }

        // The second tap must name the SAME valid neighbour as the first: with
        // "q3" (not a neighbour from index 0) captureTarget refuses even without
        // the busy guard, so the test proves nothing about in-flight refusal.
        var published = 0
        vm.moveToQuestion("q2") { published++ }
        assertEquals(EditorOperation.MOVING, vm.state.value.operation)
        vm.moveToQuestion("q2") { published++ } // same neighbour, still in flight
        pumpUntil { vm.state.value.operation == EditorOperation.IDLE }
        assertEquals("q2", vm.state.value.questionId)
        assertEquals("only the first move was published", 1, published)
        assertEquals("the session advanced exactly once", 1, EditorSession.index)
    }

    /** M5b: a failed DB load during a move keeps the current form. */
    @Test
    fun aMissingTargetKeepsTheFormAndShowsMoveError() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        EditorSession.start(listOf("q1", "missing"), index = 0)
        val vm = EditorViewModel(app, "q1", "p1", "c1")
        pumpUntil { !vm.state.value.loading }
        val before = vm.state.value

        vm.moveToQuestion("missing")
        pumpUntil { vm.state.value.moveError != null }

        assertEquals(before.questionId, vm.state.value.questionId)
        assertEquals(before.elements, vm.state.value.elements)
        assertEquals(app.getString(R.string.editor_question_unavailable), vm.state.value.moveError)
        assertEquals(EditorOperation.IDLE, vm.state.value.operation)
    }

    /**
     * M5d: after the pre-save write, a target that left the live cursor scope
     * must not be anchored by the index captured before the save.
     */
    @Test
    fun aTargetThatLeftTheLiveScopeIsNotAnchoredByThePreSaveIndex() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var ids = listOf("q1", "q2")
        EditorSession.startCursor(
            EditorSession.Cursor(
                size = ids.size,
                index = 0,
                idAt = { ids.getOrNull(it) },
                count = { ids.size },
                indexOf = { id -> ids.indexOf(id) }
            )
        )
        val vm = EditorViewModel(app, "q1", "p1", "c1")
        pumpUntil { !vm.state.value.loading }
        vm.updateBlock(0, ContentElement.TextElement("edited"))

        // The save's re-anchor now sees q2 gone from the result set.
        ids = listOf("q1")
        vm.moveToQuestion("q2")
        pumpUntil { vm.state.value.moveError != null || vm.state.value.operation == EditorOperation.IDLE }

        assertEquals("the departed target is not loaded", "q1", vm.state.value.questionId)
        assertNotNull(vm.state.value.moveError)
    }
}
