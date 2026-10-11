package com.mcqapp

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.editor.EditorSession
import com.mcqapp.ui.editor.EditorViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
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
@Config(sdk = [34], application = McqApplication::class)
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
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertEquals("q1", vm.state.value.questionId)
    }
}
