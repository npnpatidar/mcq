package com.mcqapp

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.EditorBlockType
import com.mcqapp.ui.editor.EditorOperation
import com.mcqapp.ui.editor.EditorSession
import com.mcqapp.ui.editor.EditorSource
import com.mcqapp.ui.editor.EditorViewModel
import com.mcqapp.ui.editor.ImageTarget
import com.mcqapp.ui.importscreen.ImportDataHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Editor behaviour on the in-memory (import-session) path, which is the one
 * that runs without Room: dirty tracking, block identity, table editing in
 * options and explanations, the marks gate, and image-target resolution.
 *
 * These are the rules the screen shows for both an existing and a brand-new
 * question; the screen itself is covered by the androidTest UI suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorWorkspaceRulesTest {

    private val writerCalls = mutableListOf<QuestionDto>()

    private fun dto(id: String = "q-ws") = QuestionDto(
        id = id,
        text = "Question body",
        elements = listOf(
            ContentElement.TextElement("Question body"),
            ContentElement.TableElement(listOf(listOf("a", "b"), listOf("c", "d")))
        ),
        options = listOf(
            OptionDto("a", "Alpha", listOf(ContentElement.TextElement("Alpha"))),
            OptionDto("b", "Beta", listOf(ContentElement.TextElement("Beta")))
        ),
        correctOptionIds = listOf("a"),
        explanation = "Because",
        explanationElements = listOf(ContentElement.TextElement("Because")),
        marks = 2.0
    )

    private fun vmFor(
        question: QuestionDto = dto(),
        queue: List<String> = listOf(question.id),
        index: Int = 0,
        writer: ((QuestionDto) -> Unit)? = { writerCalls += it }
    ): EditorViewModel {
        ImportDataHolder.pendingEditQuestion = question
        EditorSession.start(
            ids = queue,
            index = index,
            reader = { id -> if (id == question.id) question else dto(id) },
            writer = writer
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
        writerCalls.clear()
    }

    /** Pumps Robolectric's main looper until [cond] holds (or times out). */
    private fun pumpUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            assertTrue("timed out waiting for the editor", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            Thread.sleep(20)
        }
    }

    // ---- dirty tracking ----

    @Test
    fun dirtyTracksExactPersistedContent() {
        val vm = vmFor()
        assertFalse("a freshly loaded form is clean", vm.state.value.isDirty)

        vm.updateBlock(0, ContentElement.TextElement("Changed"))
        assertTrue("a content edit is dirty", vm.state.value.isDirty)

        vm.updateBlock(0, ContentElement.TextElement("Question body"))
        assertFalse("reverting to the persisted content is clean again", vm.state.value.isDirty)
    }

    @Test
    fun whitespaceOnlyEditsAreDirty() {
        val vm = vmFor()
        vm.updateBlock(0, ContentElement.TextElement("Question body "))
        assertTrue("a trailing space is a real, persistable edit", vm.state.value.isDirty)
    }

    @Test
    fun reorderingAndRestoringBlocksIsCleanAgain() {
        val vm = vmFor()
        vm.moveBlockDown(0)
        assertTrue(vm.state.value.isDirty)
        vm.moveBlockUp(1)
        assertFalse("order back to the persisted one is clean", vm.state.value.isDirty)
    }

    @Test
    fun addingThenRemovingTheSameBlockIsCleanAgain() {
        val vm = vmFor()
        val before = vm.state.value.elements.size
        vm.addBlock(EditorBlockType.TEXT)
        assertTrue(vm.state.value.isDirty)
        vm.removeBlock(before)
        assertFalse("the draft matches what was persisted", vm.state.value.isDirty)
    }

    @Test
    fun tagsAndMarksCountAsEdits() {
        val vm = vmFor()
        vm.updateTags("new, tags")
        assertTrue(vm.state.value.isDirty)
        vm.updateTags("")
        vm.updateMarks("2")
        assertFalse("marks 2 matches the stored 2", vm.state.value.isDirty)
        vm.updateMarks("2.0")
        assertFalse("2.0 formats to the same number", vm.state.value.isDirty)
        vm.updateMarks("3")
        assertTrue(vm.state.value.isDirty)
    }

    // ---- block identity ----

    @Test
    fun blockIdsStayWithTheirBlocks() {
        val vm = vmFor()
        val ids = vm.state.value.blockIds
        assertEquals("one id per body block", vm.state.value.elements.size, ids.size)
        assertEquals("ids are unique", ids.size, ids.toSet().size)

        vm.moveBlockDown(0)
        assertEquals(listOf(ids[1], ids[0]), vm.state.value.blockIds)

        vm.removeBlock(0)
        assertEquals(listOf(ids[0]), vm.state.value.blockIds)

        vm.addBlock(EditorBlockType.TEXT)
        val fresh = vm.state.value.blockIds.last()
        assertFalse("a new block gets a fresh id", fresh in ids)
    }

    @Test
    fun optionAndExplanationBlockIdsAreParallel() {
        val vm = vmFor()
        vm.state.value.options.forEach { o ->
            assertEquals(o.elements.size, o.blockIds.size)
            assertEquals(o.blockIds.size, o.blockIds.toSet().size)
        }
        assertEquals(vm.state.value.explanationElements.size, vm.state.value.explanationBlockIds.size)

        vm.moveOptionBlockDown("a", 0)
        assertEquals("option block ids follow the moved element", 1, vm.state.value.options[0].blockIds.size)
        vm.addExplanationBlock(EditorBlockType.TABLE)
        assertEquals(
            vm.state.value.explanationElements.size,
            vm.state.value.explanationBlockIds.size
        )
    }

    // ---- marks gate ----

    @Test
    fun marksValidationGatesSave() {
        val vm = vmFor()
        assertTrue(vm.state.value.marksValid)
        assertTrue(vm.canProceed())

        vm.updateMarks("two")
        assertFalse("a non-number is invalid", vm.state.value.marksValid)
        assertFalse("and blocks Save", vm.canProceed())

        vm.updateMarks("-1")
        assertFalse("a negative mark is invalid", vm.state.value.marksValid)

        vm.updateMarks("   ")
        assertTrue("blank still means the 1-mark default", vm.state.value.marksValid)
        assertTrue(vm.canProceed())

        vm.updateMarks("2.5")
        assertTrue(vm.state.value.marksValid)
        assertTrue("2.5 is a real edit", vm.state.value.isDirty)
    }

    @Test
    fun commaMarksValidateAndSaveLikeTheBulkEditField() {
        val vm = vmFor()
        vm.updateMarks("1,5")
        assertTrue("a comma decimal is valid", vm.state.value.marksValid)
        assertTrue("and it is a real edit", vm.state.value.isDirty)
        vm.save { }
        assertEquals(1, writerCalls.size)
        assertEquals(1.5, writerCalls.single().marks, 0.0)
    }

    @Test
    fun aNegativeStoredMarkDoesNotMakeAnUnrelatedEditUnsavable() {
        // Imported/stored data can carry a negative value the user never typed.
        // It is normalised to 1 on load, so editing some other field still saves.
        val negative = dto().copy(marks = -2.0)
        val vm = vmFor(question = negative)
        assertEquals("1", vm.state.value.marks)
        assertTrue(vm.state.value.marksValid)
        assertTrue(vm.canProceed())
        vm.updateBlock(0, ContentElement.TextElement("A valid edit"))
        vm.save { }
        assertEquals(1, writerCalls.size)
    }

    @Test
    fun saveIsRefusedWithoutCallingTheWriter() {
        val vm = vmFor()
        vm.updateMarks("NaN")
        var navigated = false
        vm.save { navigated = true }
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertFalse("nothing was written", navigated)
        assertTrue("the writer never saw a draft", writerCalls.isEmpty())
        assertEquals(EditorOperation.IDLE, vm.state.value.operation)
    }

    @Test
    fun aValidSaveWritesParsedMarksAndTags() {
        val vm = vmFor()
        vm.updateMarks(" 3 ")
        vm.updateTags("  one , , two ")
        vm.save { }
        assertEquals(1, writerCalls.size)
        assertEquals(3.0, writerCalls.single().marks, 0.0)
        assertEquals(listOf("one", "two"), writerCalls.single().tags)
    }

    // ---- option and explanation tables ----

    @Test
    fun optionTablesAreEditablePerOption() {
        val vm = vmFor()
        vm.addOptionBlock("a", EditorBlockType.TABLE)
        val tableIndex = vm.state.value.options[0].elements.lastIndex

        vm.updateOptionTableCell("a", tableIndex, 0, 0, "X")
        assertEquals(
            listOf(listOf("X")),
            (vm.state.value.options[0].elements[tableIndex] as ContentElement.TableElement).rows
        )
        // The other option is untouched.
        assertEquals(listOf(ContentElement.TextElement("Beta")), vm.state.value.options[1].elements)

        vm.addOptionTableRow("a", tableIndex)
        vm.addOptionTableColumn("a", tableIndex)
        assertEquals(
            listOf(listOf("X", ""), listOf("", "")),
            (vm.state.value.options[0].elements[tableIndex] as ContentElement.TableElement).rows
        )

        vm.removeOptionTableRow("a", tableIndex, 1)
        vm.removeOptionTableColumn("a", tableIndex, 1)
        assertEquals(
            listOf(listOf("X")),
            (vm.state.value.options[0].elements[tableIndex] as ContentElement.TableElement).rows
        )

        // Unknown option and out-of-range indices are no-ops.
        val snap = vm.state.value.options
        vm.updateOptionTableCell("nope", tableIndex, 0, 0, "Y")
        vm.updateOptionTableCell("a", 99, 0, 0, "Y")
        vm.addOptionTableRow("a", 99)
        vm.removeOptionTableColumn("a", tableIndex, 7)
        assertEquals(snap, vm.state.value.options)
    }

    @Test
    fun explanationTablesAreEditable() {
        val vm = vmFor()
        vm.addExplanationBlock(EditorBlockType.TABLE)
        val index = vm.state.value.explanationElements.lastIndex

        vm.updateExplanationTableCell(index, 0, 0, "why")
        assertEquals(
            listOf(listOf("why")),
            (vm.state.value.explanationElements[index] as ContentElement.TableElement).rows
        )
        vm.addExplanationTableColumn(index)
        vm.addExplanationTableRow(index)
        assertEquals(
            listOf(listOf("why", ""), listOf("", "")),
            (vm.state.value.explanationElements[index] as ContentElement.TableElement).rows
        )
        vm.removeExplanationTableRow(index, 0)
        vm.removeExplanationTableColumn(index, 1)
        assertEquals(
            listOf(listOf("")),
            (vm.state.value.explanationElements[index] as ContentElement.TableElement).rows
        )
        // The last row and column are kept.
        vm.removeExplanationTableRow(index, 0)
        vm.removeExplanationTableColumn(index, 0)
        assertEquals(
            listOf(listOf("")),
            (vm.state.value.explanationElements[index] as ContentElement.TableElement).rows
        )
    }

    // ---- image targeting ----

    @Test
    fun aPendingTargetIsKeptUntilThePickerReturns() {
        val vm = vmFor()
        val blockId = vm.state.value.blockIds[0]
        vm.beginImagePick(ImageTarget.Body(blockId))
        assertEquals(ImageTarget.Body(blockId), vm.state.value.pendingImageTarget)

        vm.onImagePickCancelled()
        assertNull("cancelling clears the target", vm.state.value.pendingImageTarget)
        assertEquals(
            "and writes nothing",
            listOf(ContentElement.TextElement("Question body")),
            vm.state.value.elements.take(1)
        )
    }

    @Test
    fun anUnreadablePickReportsAnErrorAndWritesNothing() {
        val vm = vmFor()
        val before = vm.state.value.elements
        val blockId = vm.state.value.blockIds[0]
        vm.beginImagePick(ImageTarget.Body(blockId))
        vm.onImagePicked(Uri.parse("content://com.mcqapp.test/missing-image"))

        pumpUntil { vm.state.value.imageError != null || vm.state.value.pendingImageTarget == null }
        pumpUntil { vm.state.value.imageError != null }
        assertNotNull("the failure is visible", vm.state.value.imageError)
        assertEquals("no block was replaced", before, vm.state.value.elements)
        assertNull("the target is consumed", vm.state.value.pendingImageTarget)
    }

    @Test
    fun aPickWithoutATargetIsIgnored() {
        val vm = vmFor()
        val before = vm.state.value
        vm.onImagePicked(Uri.parse("content://com.mcqapp.test/missing-image"))
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertEquals(before.elements, vm.state.value.elements)
        assertNull(vm.state.value.imageError)
    }

    // ---- invalid edit gating on moves ----

    @Test
    fun dirtyInvalidEditsBlockAMoveAndSayWhy() {
        val question = dto()
        val vm = vmFor(question = question, queue = listOf(question.id, "q-other"))
        vm.updateOptionBlock("a", 0, ContentElement.TextElement(""))
        assertFalse(vm.canProceed())

        vm.moveToQuestion("q-other")
        pumpUntil { vm.state.value.moveError != null }
        assertEquals("still on the same question", question.id, vm.state.value.questionId)
        assertTrue("the reason is shown", vm.state.value.moveError!!.isNotBlank())
        assertTrue("nothing was written", writerCalls.isEmpty())
        assertEquals(EditorOperation.IDLE, vm.state.value.operation)
    }
}
