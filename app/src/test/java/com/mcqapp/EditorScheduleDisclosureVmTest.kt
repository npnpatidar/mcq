package com.mcqapp

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.editor.EditorOperation
import com.mcqapp.ui.editor.EditorSession
import com.mcqapp.ui.editor.EditorSource
import com.mcqapp.ui.editor.EditorUiState
import com.mcqapp.ui.editor.EditorViewModel
import com.mcqapp.ui.editor.OptionEditorState
import com.mcqapp.ui.editor.editorCardFingerprint
import com.mcqapp.ui.importscreen.ImportDataHolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An import edit has no stored row and no `card_state`, so it never discloses a reset. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorScheduleDisclosureVmTest {

    private fun dto() = QuestionDto(
        id = "q-import",
        elements = listOf(ContentElement.TextElement("Stem?")),
        options = listOf(
            OptionDto("a", "Alpha", listOf(ContentElement.TextElement("Alpha"))),
            OptionDto("b", "Beta", listOf(ContentElement.TextElement("Beta")))
        ),
        correctOptionIds = listOf("a"),
        passageId = "pass-1"
    )

    @After
    fun tearDown() {
        ImportDataHolder.pendingEditQuestion = null
        EditorSession.clear()
    }

    @Test
    fun importEditsNeverShowTheResetNote() {
        ImportDataHolder.pendingEditQuestion = dto()
        val vm = EditorViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            questionId = "q-import",
            paperId = "",
            categoryId = "",
            source = EditorSource.IMPORT
        )
        assertFalse("an import draft has no study schedule", vm.state.value.willResetSchedule)
        vm.updateBlock(0, ContentElement.TextElement("Edited stem"))
        assertFalse(vm.state.value.willResetSchedule)
        vm.save { }
        assertFalse(vm.state.value.willResetSchedule)
    }
}

/**
 * F1: the note must track `saveQuestion`'s real `changed` decision. That method
 * trims the incoming image (buildDto) but compares it to the *raw* stored src,
 * so a whitespace-padded stored image is a reset even with no body edit. The
 * load baseline is therefore fingerprinted raw while the form on screen is
 * fingerprinted trimmed. Pure state logic — no Room, runs on every host.
 */
class EditorScheduleDisclosureImageBaselineTest {

    private fun paddedForm() = EditorUiState(
        loading = false,
        questionId = "q-pad",
        categoryId = "c-1",
        isNew = false,
        elements = listOf(ContentElement.TextElement("Stem?")),
        image = "  q.png  ",
        options = listOf(
            OptionEditorState(
                "a",
                listOf(ContentElement.TextElement("Alpha")),
                image = "  opt.png  ",
                isCorrect = true
            ),
            OptionEditorState("b", listOf(ContentElement.TextElement("Beta")))
        )
    )

    @Test
    fun paddedStoredImageWarnsOfResetUntilTheSaveRebaselines() {
        val stored = paddedForm()
        // What load() does: fingerprint the raw stored side as the baseline.
        val loaded = stored.copy(
            baselineCardFingerprint = editorCardFingerprint(stored, rawImages = true)
        )
        assertTrue(
            "saveQuestion trims the incoming src but compares it to the raw stored one",
            loaded.willResetSchedule
        )

        // What save() leaves behind: the row now holds the trimmed src, so the
        // (trimmed) baseline equals the form and the note clears.
        val afterSave = loaded.copy(
            baselineCardFingerprint = editorCardFingerprint(loaded)
        )
        assertFalse("the reset note clears after a successful save", afterSave.willResetSchedule)
    }

    @Test
    fun unpaddedStoredImageShowsNoNoteUntilARealContentEdit() {
        val padded = paddedForm()
        val stored = padded.copy(
            image = "q.png",
            options = listOf(
                padded.options[0].copy(image = "opt.png"),
                padded.options[1]
            )
        )
        val loaded = stored.copy(
            baselineCardFingerprint = editorCardFingerprint(stored, rawImages = true)
        )
        assertFalse("an already-trimmed stored src matches the incoming side", loaded.willResetSchedule)
        assertTrue(
            "a genuine content edit still warns",
            loaded.copy(elements = listOf(ContentElement.TextElement("Edited"))).willResetSchedule
        )
    }
}

/**
 * F2: the disclosure must be driven by the real [EditorViewModel] `setCardBaseline`
 * calls on the stored-row path (load, save and Prev-Next) — the pure tests only
 * simulated those. Room-backed, so CI (x86_64) is authoritative.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class EditorScheduleDisclosureStoredVmTest {

    private lateinit var repository: McqRepository

    @Before
    fun seed() = runBlocking {
        repository = ApplicationProvider.getApplicationContext<McqApplication>().repository
        repository.ensurePaperAndCategory("p-sched", "Schedule Paper", "c-sched", "Cat")
        repository.saveQuestion(storedQuestion("sched-q1"))
        repository.saveQuestion(storedQuestion("sched-q2"))
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("p-sched")
        EditorSession.clear()
        ImportDataHolder.pendingEditQuestion = null
    }

    private fun storedQuestion(id: String) = Question(
        id = id,
        categoryId = "c-sched",
        elements = listOf(ContentElement.TextElement("Stem $id")),
        options = listOf(
            QuestionOption("a", listOf(ContentElement.TextElement("Alpha"))),
            QuestionOption("b", listOf(ContentElement.TextElement("Beta")))
        ),
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
    fun storedRowDrivesTheResetNoteThroughLoadEditSaveAndMove() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        EditorSession.start(listOf("sched-q1", "sched-q2"), index = 0)
        val vm = EditorViewModel(app, "sched-q1", "p-sched", "c-sched")
        pumpUntil { !vm.state.value.loading }

        assertNotNull("a stored baseline was captured", vm.state.value.baselineCardFingerprint)
        assertFalse("a clean load shows no reset note", vm.state.value.willResetSchedule)

        // A body edit changes the studied content -> the note appears.
        vm.updateBlock(0, ContentElement.TextElement("Edited stem"))
        assertTrue("a body edit shows the reset note", vm.state.value.willResetSchedule)

        // A successful save rebaselines -> the note clears and the row is updated.
        vm.save { }
        pumpUntil { vm.state.value.operation == EditorOperation.IDLE && !vm.state.value.isDirty }
        assertFalse("the note clears after save", vm.state.value.willResetSchedule)
        assertEquals(
            listOf(ContentElement.TextElement("Edited stem")),
            runBlocking { repository.getQuestion("sched-q1") }?.elements
        )

        // Prev-Next to another stored row: the note is re-anchored to it.
        vm.moveToQuestion("sched-q2")
        pumpUntil { vm.state.value.questionId == "sched-q2" }
        assertFalse("the freshly loaded neighbour has no note", vm.state.value.willResetSchedule)

        // ...and a fresh edit on that neighbour shows it again.
        vm.updateBlock(0, ContentElement.TextElement("Edited q2 stem"))
        assertTrue("the moved-to row gets its own baseline", vm.state.value.willResetSchedule)
    }
}
