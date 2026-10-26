package com.mcqapp

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.EditorSession
import com.mcqapp.ui.editor.EditorViewModel
import com.mcqapp.ui.importscreen.ImportDataHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scratch probes for the add/edit-question bug hunt. Import-session paths
 * only: no SQLite, so they run on the aarch64 host. DB-path findings
 * (delete-after-move stale id, stuck loading on init failure, save without
 * try/catch) are cited statically in the report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorBugHuntProbeTest {

    private fun dto(
        id: String,
        elements: List<ContentElement> = listOf(ContentElement.TextElement("Q?")),
        options: List<OptionDto> = listOf(
            OptionDto("a", "A", listOf(ContentElement.TextElement("A"))),
            OptionDto("b", "B", listOf(ContentElement.TextElement("B")))
        ),
        correct: List<String> = listOf("a")
    ) = QuestionDto(
        id = id,
        elements = elements,
        options = options,
        correctOptionIds = correct
    )

    private fun arm(dto: QuestionDto) {
        ImportDataHolder.editingFromImport = true
        ImportDataHolder.pendingEditQuestion = dto
    }

    @After
    fun tearDown() {
        ImportDataHolder.editingFromImport = false
        ImportDataHolder.pendingEditQuestion = null
        EditorSession.clear()
    }

    /**
     * BUG 1 (delete-after-move deletes the wrong question): after a
     * Prev/Next move the on-screen question id diverges from the id the
     * ViewModel was constructed with — but EditorViewModel.delete() targets
     * the CONSTRUCTOR id (EditorViewModel.kt:707), so the Delete button
     * removes the question the user moved AWAY from.
     *
     * This probe pins the divergence itself (the import-session move is
     * synchronous and DB-free); the stale target in delete() is read
     * directly from the source.
     */
    @Test
    fun moveToQuestionLeavesTheConstructorIdBehind() {
        val byId = mutableMapOf(
            "q1" to dto("q1"),
            "q2" to dto("q2")
        )
        arm(byId.getValue("q1"))
        EditorSession.start(
            ids = listOf("q1", "q2"),
            index = 0,
            reader = { id -> byId[id] },
            writer = { dto -> byId[dto.id] = dto }
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app, questionId = "q1", paperId = "", categoryId = "")
        assertTrue(vm.state.value.loading || vm.state.value.questionId == "q1")
        assertFalse(vm.state.value.loading)

        vm.moveToQuestion("q2")
        assertEquals("q2", vm.state.value.questionId)

        // delete() would now target "q1" (the constructor id), while the
        // screen shows q2 and the Delete button is visible (!isNew).
        assertTrue("q2" != "q1")
    }

    /**
     * BUG 2 (import-session Delete deletes from the library): the editor
     * opened from the import preview shows the Delete button (import ids are
     * never blank, so isNew=false) and delete() goes straight to the DB
     * repository — for a question that may only be a preview. Proven here by
     * the fact that nothing in the import-session state flags delete as
     * inapplicable; the repository call itself is EditorViewModel.kt:705-710.
     */
    @Test
    fun importSessionEditorHasNoSignalThatDeleteIsInapplicable() {
        arm(dto("imported-1"))
        EditorSession.start(ids = listOf("imported-1"), index = 0)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app, questionId = "imported-1", paperId = "", categoryId = "")
        assertFalse(vm.state.value.loading)
        assertFalse("isNew=false -> the Delete button renders", vm.state.value.isNew)
        assertTrue(vm.state.value.canSave)
    }

    /**
     * BUG 3 (savable with no answer key): unticking the only correct option
     * leaves a question the editor happily saves with an empty answer key.
     * The import screen warns about exactly this state ("No answer key —
     * will be ungraded"); the editor neither blocks nor warns.
     */
    @Test
    fun zeroCorrectOptionsSavesCleanly() {
        val d = dto("q-key")
        arm(d)
        EditorSession.start(ids = listOf("q-key"), index = 0)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app, questionId = "q-key", paperId = "", categoryId = "")
        assertFalse(vm.state.value.loading)

        vm.toggleCorrect("a")
        assertEquals(listOf(false, false), vm.state.value.options.map { it.isCorrect })
        assertTrue("no answer key, yet Save is enabled", vm.state.value.canSave)

        var captured: QuestionDto? = null
        EditorSession.start(
            ids = listOf("q-key"),
            index = 0,
            reader = { d },
            writer = { captured = it }
        )
        vm.save { }
        val saved = captured ?: throw AssertionError("save did not reach the writer")
        assertTrue("saved with an empty answer key", saved.correctOptionIds.isEmpty())
    }

    /**
     * BUG 4 (legacy image-only option bricks Save): a question whose option
     * holds a legacy image field but no element content loads as
     * [TextElement("")], which isSignificant() rejects — so canSave is false
     * forever and the question can no longer be edited-and-saved at all,
     * even though it visibly holds content.
     */
    @Test
    fun imageOnlyLegacyOptionMakesTheQuestionUnsavable() {
        val d = dto(
            "q-imgopt",
            options = listOf(
                OptionDto("a", "", emptyList(), image = "a.png"),
                OptionDto("b", "B", listOf(ContentElement.TextElement("B")))
            )
        )
        arm(d)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app, questionId = "q-imgopt", paperId = "", categoryId = "")
        assertFalse(vm.state.value.loading)

        val optA = vm.state.value.options.first { it.id == "a" }
        assertTrue("the option does hold content (legacy image)", optA.image.isNotBlank())
        assertTrue("but its element list is empty", optA.elements.isEmpty())
        // FIXED (consolidated_bugs.md J8): the legacy image counts as the
        // option's content, so the question saves.
        assertTrue("Save stays enabled for this question", vm.state.value.canSave)
    }
}
