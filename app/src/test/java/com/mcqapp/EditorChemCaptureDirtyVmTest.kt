package com.mcqapp

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.EditorOperation
import com.mcqapp.ui.editor.EditorSession
import com.mcqapp.ui.editor.EditorSource
import com.mcqapp.ui.editor.EditorViewModel
import com.mcqapp.ui.importscreen.ImportDataHolder
import com.mcqapp.util.chemCellHtml
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * BEHAVIOUR: a structure raster that arrives after Save must still reach the
 * stored marker. Save snapshots the form at the moment it is pressed; a
 * capture that finishes afterwards is only written by a later save or
 * navigation if the draft is dirty. The old rule preserved a clean flag, so
 * the PNG stayed in memory and exports fell back to handles.
 *
 * Robolectric because this drives the real ViewModel's save/capture path; the
 * pure decision is also covered in EditorTableCellCaptureTest and runs on
 * every host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorChemCaptureDirtyVmTest {

    private val png = "data:image/png;base64,iVBORw0KGgo="

    private fun dto(): QuestionDto {
        val cell = chemCellHtml(
            ContentElement.ChemElement(
                smiles = "CCO",
                formula = "C2H5OH",
                caption = "ethanol",
                spoken = "ethanol"
            )
        )
        return QuestionDto(
            id = "q-capture",
            text = "Read the table.",
            elements = listOf(ContentElement.TableElement(listOf(listOf(cell)))),
            options = listOf(
                OptionDto("a", "Alpha", listOf(ContentElement.TextElement("Alpha"))),
                OptionDto("b", "Beta", listOf(ContentElement.TextElement("Beta")))
            ),
            correctOptionIds = listOf("a"),
            explanation = "Because",
            explanationElements = listOf(ContentElement.TextElement("Because"))
        )
    }

    private fun vmFor(question: QuestionDto = dto()): EditorViewModel {
        ImportDataHolder.pendingEditQuestion = question
        EditorSession.start(
            ids = listOf(question.id),
            index = 0,
            reader = { question },
            writer = { }
        )
        return EditorViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            questionId = question.id,
            paperId = "",
            categoryId = "",
            source = EditorSource.IMPORT
        )
    }

    @After
    fun tearDown() {
        ImportDataHolder.pendingEditQuestion = null
        EditorSession.clear()
    }

    private fun pumpUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            assertTrue("timed out waiting for the editor", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            Thread.sleep(20)
        }
    }

    @Test
    fun aCaptureAfterACleanSaveDirtiesTheDraftAndARepeatDoesNotChurnIt() {
        val vm = vmFor()
        pumpUntil { vm.state.value.blockIds.isNotEmpty() }
        val blockId = vm.state.value.blockIds.single()
        assertFalse("a freshly loaded form is clean", vm.state.value.isDirty)

        // Save writes the form as it is now: the capture below is NOT in it.
        vm.save { }
        pumpUntil { vm.state.value.operation == EditorOperation.IDLE }
        assertFalse("save leaves the saved form clean", vm.state.value.isDirty)

        vm.updateChemDiagram("$blockId#0:0@CCO", png)
        assertTrue(
            "a capture the save did not write must leave the draft dirty",
            vm.state.value.isDirty
        )
        val once = vm.state.value

        // The renderer repeats the same callback; an identical raster changes
        // nothing and must not replace or churn the state.
        vm.updateChemDiagram("$blockId#0:0@CCO", png)
        assertSame("a repeated identical capture is a no-op", once, vm.state.value)
        assertTrue("the unpersisted capture is still pending", vm.state.value.isDirty)
    }
}
