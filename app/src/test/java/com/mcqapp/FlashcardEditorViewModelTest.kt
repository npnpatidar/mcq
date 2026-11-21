package com.mcqapp

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.EditorCardType
import com.mcqapp.ui.editor.EditorSession
import com.mcqapp.ui.editor.EditorSource
import com.mcqapp.ui.editor.EditorViewModel
import com.mcqapp.ui.editor.saveBlockedHintRes
import com.mcqapp.ui.importscreen.ImportDataHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor's card-type behaviour through the import-session path, which
 * populates and moves state synchronously (no Room, no coroutines).
 *
 * Pins the three round-trip rules: a stored zero-option DTO opens as a
 * flashcard and saves as one, switching the type reshapes the option list, and
 * the type never leaks from one question to the next on Prev/Next.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlashcardEditorViewModelTest {

    private fun cardDto(id: String = "card-q", back: String = "Paris") = QuestionDto(
        id = id,
        elements = listOf(ContentElement.TextElement("Capital of France?")),
        options = emptyList(),
        correctOptionIds = emptyList(),
        explanation = back,
        explanationElements = listOf(ContentElement.TextElement(back))
    )

    private fun questionDto(id: String = "mcq-q") = QuestionDto(
        id = id,
        elements = listOf(ContentElement.TextElement("Which one?")),
        options = listOf(
            OptionDto("a", "Alpha", listOf(ContentElement.TextElement("Alpha"))),
            OptionDto("b", "Beta", listOf(ContentElement.TextElement("Beta")))
        ),
        correctOptionIds = listOf("a"),
        explanationElements = listOf(ContentElement.TextElement("Because."))
    )

    private fun vmFor(
        dto: QuestionDto,
        reader: ((String) -> QuestionDto?)? = null,
        writer: ((QuestionDto) -> Unit)? = null
    ): EditorViewModel {
        ImportDataHolder.pendingEditQuestion = dto
        if (reader != null || writer != null) {
            EditorSession.start(
                ids = listOf(dto.id, "mcq-q", "card-q").distinct(),
                index = 0,
                reader = reader,
                writer = writer
            )
        }
        return EditorViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            questionId = dto.id,
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

    @Test
    fun aZeroOptionDtoOpensAsAFlashcardWithItsBack() {
        val vm = vmFor(cardDto())
        val s = vm.state.value
        assertEquals(EditorCardType.FLASHCARD, s.cardType)
        assertTrue("the options section has nothing to show", s.options.isEmpty())
        assertEquals(listOf(ContentElement.TextElement("Paris")), s.explanationElements)
        assertTrue("a card with a back is savable", vm.canProceed())
    }

    @Test
    fun savingACardWritesZeroOptionsAndAnEmptyKey() {
        var captured: QuestionDto? = null
        val vm = vmFor(cardDto(), writer = { captured = it })
        assertTrue(vm.canProceed())
        var done = false
        vm.save { done = true }
        assertTrue(done)
        val saved = captured ?: throw AssertionError("importWriter was not called")
        assertTrue("a flashcard stores no options", saved.options.isEmpty())
        assertTrue("a flashcard has no answer key", saved.correctOptionIds.isEmpty())
        assertEquals(
            "the back is the DTO's explanation",
            listOf(ContentElement.TextElement("Paris")),
            saved.explanationElements
        )
    }

    @Test
    fun switchingACardToAQuestionSeedsTwoBlankOptions() {
        val vm = vmFor(cardDto())
        vm.setCardType(EditorCardType.MULTIPLE_CHOICE)
        val s = vm.state.value
        assertEquals(EditorCardType.MULTIPLE_CHOICE, s.cardType)
        assertEquals(2, s.options.size)
        assertTrue("the seeded options are blank", s.options.none { it.hasContent })
        assertFalse("a blank question is not savable", vm.canProceed())
        assertEquals(
            R.string.save_blocked_empty_option,
            saveBlockedHintRes(s)
        )
        assertTrue("switching the type is an edit", s.isDirty)
    }

    @Test
    fun switchingAQuestionToACardClearsItsOptions() {
        val vm = vmFor(questionDto())
        assertEquals(EditorCardType.MULTIPLE_CHOICE, vm.state.value.cardType)
        assertTrue(vm.canProceed())

        // What the confirmation dialog's Switch button calls.
        vm.setCardType(EditorCardType.FLASHCARD)
        val s = vm.state.value
        assertEquals(EditorCardType.FLASHCARD, s.cardType)
        assertTrue("a flashcard holds no options", s.options.isEmpty())
        assertTrue("the explanation becomes the back", vm.canProceed())
        assertNull(saveBlockedHintRes(s))
    }

    @Test
    fun aQuestionWithTypedOptionsIsFlaggedForTheConfirmationDialog() {
        val typed = vmFor(questionDto())
        assertTrue("typed options must be confirmed before clearing", typed.state.value.hasTypedOptions)

        val blank = vmFor(cardDto().copy(options = emptyList()))
        blank.setCardType(EditorCardType.MULTIPLE_CHOICE)
        assertFalse(
            "seeded blanks hold nothing to lose",
            blank.state.value.hasTypedOptions
        )
    }

    @Test
    fun theCardTypeDoesNotLeakAcrossPrevNext() {
        val vm = vmFor(
            cardDto(),
            reader = { id ->
                when (id) {
                    "card-q" -> cardDto()
                    "mcq-q" -> questionDto()
                    else -> null
                }
            }
        )
        assertEquals(EditorCardType.FLASHCARD, vm.state.value.cardType)

        vm.moveToQuestion("mcq-q")
        assertEquals("mcq-q", vm.state.value.questionId)
        assertEquals(EditorCardType.MULTIPLE_CHOICE, vm.state.value.cardType)
        assertEquals(2, vm.state.value.options.size)

        vm.moveToQuestion("card-q")
        assertEquals("card-q", vm.state.value.questionId)
        assertEquals("the card type came back with the card", EditorCardType.FLASHCARD, vm.state.value.cardType)
        assertTrue(vm.state.value.options.isEmpty())
    }
}
