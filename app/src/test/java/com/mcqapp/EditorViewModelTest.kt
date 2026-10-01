package com.mcqapp

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.EditorBlockType
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
 * Unit tests for [EditorViewModel] through the import-session init path,
 * which populates state synchronously (no coroutines needed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorViewModelTest {

    private val mathml = "<math><mi>x</mi></math>"

    private fun richDto(id: String = "q-rich"): QuestionDto {
        return QuestionDto(
            id = id,
            text = "What is shown?",
            elements = listOf(
                ContentElement.TextElement("What is shown?"),
                ContentElement.TableElement(listOf(listOf("a", "b"), listOf("1", "2"))),
                ContentElement.MathElement(mathml),
                ContentElement.ImageElement("q.png")
            ),
            options = listOf(
                OptionDto("a", "Four", listOf(ContentElement.TextElement("Four"))),
                OptionDto(
                    "b",
                    "Five",
                    listOf(
                        ContentElement.TextElement("Five"),
                        ContentElement.MathElement(mathml)
                    )
                )
            ),
            correctOptionIds = listOf("a"),
            explanation = "Because reasons.",
            explanationElements = listOf(
                ContentElement.TextElement("Because reasons."),
                ContentElement.MathElement(mathml)
            )
        )
    }

    private fun minimalDto(
        id: String,
        elements: List<ContentElement>,
        optionElements: List<List<ContentElement>>
    ): QuestionDto {
        return QuestionDto(
            id = id,
            elements = elements,
            options = optionElements.mapIndexed { i, els ->
                val oid = ('a' + i).toString()
                OptionDto(oid, "", els)
            },
            correctOptionIds = listOf("a")
        )
    }

    private fun vmFor(dto: QuestionDto, writer: ((QuestionDto) -> Unit)? = null): EditorViewModel {
        ImportDataHolder.editingFromImport = true
        ImportDataHolder.pendingEditQuestion = dto
        if (writer != null) {
            EditorSession.start(ids = listOf(dto.id), index = 0, writer = writer)
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        return EditorViewModel(app, questionId = dto.id, paperId = "", categoryId = "")
    }

    @After
    fun tearDown() {
        ImportDataHolder.editingFromImport = false
        ImportDataHolder.pendingEditQuestion = null
        EditorSession.clear()
    }

    @Test
    fun loadPreservesRichElements() {
        val dto = richDto()
        val vm = vmFor(dto)
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(dto.elements, s.elements)
        assertEquals(dto.options.map { it.id }, s.options.map { it.id })
        assertEquals(dto.options.map { it.elements }, s.options.map { it.elements })
        assertEquals(listOf(true, false), s.options.map { it.isCorrect })
        assertEquals(dto.explanationElements, s.explanationElements)
    }

    @Test
    fun addBlockAppendsEachDefaultType() {
        val vm = vmFor(richDto())
        val before = vm.state.value.elements.size
        vm.addBlock(EditorBlockType.TEXT)
        vm.addBlock(EditorBlockType.IMAGE)
        vm.addBlock(EditorBlockType.TABLE)
        vm.addBlock(EditorBlockType.MATH)
        val els = vm.state.value.elements
        assertEquals(before + 4, els.size)
        assertEquals(ContentElement.TextElement(""), els[before])
        assertEquals(ContentElement.ImageElement(""), els[before + 1])
        assertEquals(ContentElement.TableElement(listOf(listOf(""))), els[before + 2])
        assertEquals(ContentElement.MathElement("<math><mi></mi></math>"), els[before + 3])
    }

    @Test
    fun updateBlockReplacesAndOutOfRangeIsNoOp() {
        val vm = vmFor(richDto())
        vm.updateBlock(0, ContentElement.TextElement("edited"))
        assertEquals(ContentElement.TextElement("edited"), vm.state.value.elements[0])
        val snap = vm.state.value.elements
        vm.updateBlock(-1, ContentElement.TextElement("x"))
        vm.updateBlock(snap.size, ContentElement.TextElement("x"))
        vm.updateBlock(99, ContentElement.TextElement("x"))
        vm.removeBlock(-1)
        vm.removeBlock(snap.size)
        vm.moveBlockUp(-1)
        vm.moveBlockUp(snap.size)
        vm.moveBlockDown(-1)
        vm.moveBlockDown(snap.size)
        assertEquals(snap, vm.state.value.elements)
    }

    @Test
    fun removeAndMoveBlocksReorderCorrectly() {
        val dto = richDto("q-reorder").copy(
            elements = listOf(
                ContentElement.TextElement("A"),
                ContentElement.TextElement("B"),
                ContentElement.TextElement("C")
            )
        )
        val vm = vmFor(dto)
        vm.moveBlockDown(0)
        assertEquals(
            listOf(
                ContentElement.TextElement("B"),
                ContentElement.TextElement("A"),
                ContentElement.TextElement("C")
            ),
            vm.state.value.elements
        )
        vm.moveBlockUp(2)
        assertEquals(
            listOf(
                ContentElement.TextElement("B"),
                ContentElement.TextElement("C"),
                ContentElement.TextElement("A")
            ),
            vm.state.value.elements
        )
        val snap = vm.state.value.elements
        vm.moveBlockUp(0)
        vm.moveBlockDown(snap.lastIndex)
        assertEquals(snap, vm.state.value.elements)
        vm.removeBlock(1)
        assertEquals(
            listOf(
                ContentElement.TextElement("B"),
                ContentElement.TextElement("A")
            ),
            vm.state.value.elements
        )
    }

    @Test
    fun tableCellAndRowOps() {
        val dto = richDto("q-table").copy(
            elements = listOf(
                ContentElement.TableElement(listOf(listOf("a", "b"), listOf("c", "d"))),
                ContentElement.TextElement("note")
            )
        )
        val vm = vmFor(dto)
        vm.updateTableCell(0, 0, 1, "B!")
        assertEquals(
            listOf(listOf("a", "B!"), listOf("c", "d")),
            (vm.state.value.elements[0] as ContentElement.TableElement).rows
        )
        val snap = vm.state.value.elements
        vm.updateTableCell(0, 5, 0, "x")
        vm.updateTableCell(0, 0, 5, "x")
        assertEquals(snap, vm.state.value.elements)
        vm.addTableRow(0)
        assertEquals(
            listOf(listOf("a", "B!"), listOf("c", "d"), listOf("", "")),
            (vm.state.value.elements[0] as ContentElement.TableElement).rows
        )
        vm.removeTableRow(0, 2)
        assertEquals(
            listOf(listOf("a", "B!"), listOf("c", "d")),
            (vm.state.value.elements[0] as ContentElement.TableElement).rows
        )
    }

    @Test
    fun tableColumnOpsAndLastRowColumnRefusals() {
        val dto = richDto("q-cols").copy(
            elements = listOf(
                ContentElement.TableElement(listOf(listOf("a", "b"), listOf("c", "d"))),
                ContentElement.TextElement("note")
            )
        )
        val vm = vmFor(dto)
        vm.addTableColumn(0)
        assertEquals(
            listOf(listOf("a", "b", ""), listOf("c", "d", "")),
            (vm.state.value.elements[0] as ContentElement.TableElement).rows
        )
        vm.removeTableColumn(0, 2)
        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d")),
            (vm.state.value.elements[0] as ContentElement.TableElement).rows
        )
        val snap = vm.state.value.elements
        vm.updateTableCell(1, 0, 0, "x")
        vm.addTableRow(1)
        vm.removeTableRow(1, 0)
        vm.addTableColumn(1)
        vm.removeTableColumn(1, 0)
        assertEquals(snap, vm.state.value.elements)

        val singleRow = richDto("q-one-row").copy(
            elements = listOf(ContentElement.TableElement(listOf(listOf("only"))))
        )
        val vmSingleRow = vmFor(singleRow)
        vmSingleRow.removeTableRow(0, 0)
        assertEquals(
            listOf(listOf("only")),
            (vmSingleRow.state.value.elements[0] as ContentElement.TableElement).rows
        )

        val singleCol = richDto("q-one-col").copy(
            elements = listOf(ContentElement.TableElement(listOf(listOf("x"), listOf("y"))))
        )
        val vmSingleCol = vmFor(singleCol)
        vmSingleCol.removeTableColumn(0, 0)
        assertEquals(
            listOf(listOf("x"), listOf("y")),
            (vmSingleCol.state.value.elements[0] as ContentElement.TableElement).rows
        )
    }

    @Test
    fun optionBlockOps() {
        val vm = vmFor(richDto())
        vm.addOptionBlock("a", EditorBlockType.IMAGE)
        var optA = vm.state.value.options.first { it.id == "a" }
        assertEquals(2, optA.elements.size)
        assertEquals(ContentElement.ImageElement(""), optA.elements.last())

        vm.updateOptionBlock("a", 0, ContentElement.TextElement("updated"))
        optA = vm.state.value.options.first { it.id == "a" }
        assertEquals(ContentElement.TextElement("updated"), optA.elements[0])

        vm.moveOptionBlockDown("a", 0)
        optA = vm.state.value.options.first { it.id == "a" }
        assertEquals(
            listOf(ContentElement.ImageElement(""), ContentElement.TextElement("updated")),
            optA.elements
        )
        vm.moveOptionBlockUp("a", 1)
        optA = vm.state.value.options.first { it.id == "a" }
        assertEquals(
            listOf(ContentElement.TextElement("updated"), ContentElement.ImageElement("")),
            optA.elements
        )

        val snap = optA.elements
        vm.updateOptionBlock("a", 9, ContentElement.TextElement("x"))
        vm.removeOptionBlock("a", 9)
        vm.moveOptionBlockUp("a", 0)
        vm.moveOptionBlockDown("a", snap.lastIndex)
        vm.addOptionBlock("zzz", EditorBlockType.TEXT)
        assertEquals(snap, vm.state.value.options.first { it.id == "a" }.elements)

        vm.removeOptionBlock("a", 0)
        assertEquals(1, vm.state.value.options.first { it.id == "a" }.elements.size)
    }

    @Test
    fun removeOptionRefusesAtTwoToggleCorrectFlipsAndOptionMoves() {
        val vm = vmFor(richDto())
        assertEquals(2, vm.state.value.options.size)
        vm.removeOption("a")
        assertEquals(2, vm.state.value.options.size)

        vm.addOption()
        assertEquals(3, vm.state.value.options.size)
        assertEquals("c", vm.state.value.options.last().id)
        vm.removeOption("c")
        assertEquals(2, vm.state.value.options.size)

        val beforeA = vm.state.value.options.first { it.id == "a" }.isCorrect
        assertTrue(beforeA)
        vm.toggleCorrect("a")
        assertEquals(!beforeA, vm.state.value.options.first { it.id == "a" }.isCorrect)
        vm.toggleCorrect("a")
        assertEquals(beforeA, vm.state.value.options.first { it.id == "a" }.isCorrect)

        vm.updateOptionImage("a", "opt.png")
        assertEquals("opt.png", vm.state.value.options.first { it.id == "a" }.image)

        vm.moveOptionDown("a")
        assertEquals(listOf("b", "a"), vm.state.value.options.map { it.id })
        vm.moveOptionUp("a")
        assertEquals(listOf("a", "b"), vm.state.value.options.map { it.id })
        vm.moveOptionUp("a")
        vm.moveOptionDown("b")
        vm.moveOptionUp("zzz")
        vm.moveOptionDown("zzz")
        assertEquals(listOf("a", "b"), vm.state.value.options.map { it.id })
    }

    @Test
    fun explanationBlockOps() {
        val vm = vmFor(richDto())
        assertEquals(2, vm.state.value.explanationElements.size)
        vm.addExplanationBlock(EditorBlockType.TEXT)
        assertEquals(3, vm.state.value.explanationElements.size)
        assertEquals(
            ContentElement.TextElement(""),
            vm.state.value.explanationElements.last()
        )

        vm.updateExplanationBlock(0, ContentElement.TextElement("new expl"))
        assertEquals(
            ContentElement.TextElement("new expl"),
            vm.state.value.explanationElements[0]
        )

        vm.moveExplanationBlockDown(0)
        assertEquals(
            ContentElement.TextElement("new expl"),
            vm.state.value.explanationElements[1]
        )
        vm.moveExplanationBlockUp(1)
        assertEquals(
            ContentElement.TextElement("new expl"),
            vm.state.value.explanationElements[0]
        )

        val snap = vm.state.value.explanationElements
        vm.moveExplanationBlockUp(0)
        vm.moveExplanationBlockDown(snap.lastIndex)
        vm.updateExplanationBlock(-1, ContentElement.TextElement("x"))
        vm.updateExplanationBlock(snap.size, ContentElement.TextElement("x"))
        vm.removeExplanationBlock(-1)
        vm.removeExplanationBlock(snap.size)
        assertEquals(snap, vm.state.value.explanationElements)

        vm.removeExplanationBlock(2)
        assertEquals(2, vm.state.value.explanationElements.size)
    }

    @Test
    fun imageOnlyQuestionWithContentOptionsPasses() {
        val dto = minimalDto(
            "q-img",
            listOf(ContentElement.ImageElement("q.png")),
            listOf(
                listOf(ContentElement.TextElement("A")),
                listOf(ContentElement.TextElement("B"))
            )
        )
        assertTrue(vmFor(dto).canProceed())
    }

    @Test
    fun allEmptyContentFails() {
        val dto = minimalDto(
            "q-empty",
            listOf(ContentElement.TextElement("   ")),
            listOf(
                listOf(ContentElement.TextElement("")),
                listOf(ContentElement.TextElement("   "))
            )
        )
        assertFalse(vmFor(dto).canProceed())
    }

    @Test
    fun optionWithOnlyEmptyBlocksFails() {
        val dto = minimalDto(
            "q-opt-empty",
            listOf(ContentElement.TextElement("Q")),
            listOf(
                listOf(ContentElement.TextElement("A")),
                listOf(ContentElement.TextElement(""))
            )
        )
        assertFalse(vmFor(dto).canProceed())
    }

    @Test
    fun saveFromImportSessionWritesDtoViaImportWriter() {
        val dto = richDto("q-save")
        var captured: QuestionDto? = null
        val vm = vmFor(dto, writer = { captured = it })
        assertTrue(vm.canProceed())
        var done = false
        vm.save { done = true }
        assertTrue(done)
        val saved = captured ?: throw AssertionError("importWriter was not called")
        assertEquals(dto.elements, saved.elements)
        assertTrue("elements must not collapse to a single TextElement", saved.elements.size > 1)
        assertEquals(dto.options.map { it.elements }, saved.options.map { it.elements })
        assertEquals(dto.explanationElements, saved.explanationElements)
        assertEquals(dto.correctOptionIds, saved.correctOptionIds)
    }
}
