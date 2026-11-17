package com.mcqapp

import com.mcqapp.data.io.CardContent
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Difficulty
import com.mcqapp.ui.editor.EditorUiState
import com.mcqapp.ui.editor.OptionEditorState
import com.mcqapp.ui.editor.editorCardFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inline "saving this edit restarts the schedule" note must track exactly
 * the content [CardContent] fingerprints — the same function
 * `QuestionStore.saveQuestion` uses to delete `card_state`. All of these are
 * pure state tests (no Room), so they run on every host.
 */
class EditorScheduleDisclosureTest {

    private fun form(passageId: String? = "pass-1") = EditorUiState(
        loading = false,
        questionId = "q-1",
        paperId = "p-1",
        categoryId = "c-1",
        isNew = false,
        elements = listOf(ContentElement.TextElement("Stem?")),
        options = listOf(
            OptionEditorState("a", listOf(ContentElement.TextElement("Alpha")), isCorrect = true),
            OptionEditorState("b", listOf(ContentElement.TextElement("Beta")))
        ),
        explanationElements = listOf(ContentElement.TextElement("Because.")),
        passageId = passageId,
        marks = "2",
        tags = "one, two"
    )

    /** What load/save leaves behind: baseline equals the current card content. */
    private fun persisted(s: EditorUiState) =
        s.copy(baselineCardFingerprint = editorCardFingerprint(s))

    @Test
    fun cleanStoredFormShowsNoNote() {
        assertFalse(persisted(form()).willResetSchedule)
    }

    @Test
    fun noNoteWithoutAPersistedBaseline() {
        // A brand-new question and an import draft have no schedule to reset.
        val fresh = form().copy(questionId = "", isNew = true, baselineCardFingerprint = null)
        assertFalse(fresh.willResetSchedule)
        assertFalse(
            fresh.copy(elements = listOf(ContentElement.TextElement("Edited"))).willResetSchedule
        )
        val importDraft = persisted(form()).copy(baselineCardFingerprint = null)
        assertFalse(importDraft.willResetSchedule)
    }

    @Test
    fun bodyEditShowsResetNote() {
        val stored = persisted(form())
        assertTrue(stored.copy(elements = listOf(ContentElement.TextElement("Edited stem?"))).willResetSchedule)
    }

    @Test
    fun explanationAndMetadataOnlyEditDoesNotShowNote() {
        val stored = persisted(form())
        val edited = stored.copy(
            explanationElements = listOf(ContentElement.TextElement("A much longer explanation.")),
            explanationImage = "new.png",
            marks = "5",
            difficulty = Difficulty.HARD,
            tags = "three",
            categoryId = "c-2"
        )
        assertFalse(edited.willResetSchedule)
    }

    @Test
    fun answerKeyAndOptionContentEditsShowNote() {
        val stored = persisted(form())
        assertTrue(
            stored.copy(
                options = listOf(
                    stored.options[0].copy(isCorrect = false),
                    stored.options[1].copy(isCorrect = true)
                )
            ).willResetSchedule
        )
        assertTrue(
            stored.copy(
                options = listOf(
                    stored.options[0].copy(elements = listOf(ContentElement.TextElement("Changed"))),
                    stored.options[1]
                )
            ).willResetSchedule
        )
    }

    @Test
    fun passageRemovalShowsResetNote() {
        val stored = persisted(form(passageId = "pass-1"))
        assertTrue(stored.copy(passageId = null).willResetSchedule)
    }

    @Test
    fun noteClearsAfterSaveRebaselines() {
        val edited = persisted(form()).copy(
            elements = listOf(ContentElement.TextElement("Edited stem?"))
        )
        assertTrue(edited.willResetSchedule)
        // save() captures editorCardFingerprint(stateAtSave) and re-anchors to it.
        val afterSave = edited.copy(baselineCardFingerprint = editorCardFingerprint(edited))
        assertFalse(afterSave.willResetSchedule)
        // A fresh content edit after the save shows it again.
        assertTrue(
            afterSave.copy(elements = listOf(ContentElement.TextElement("Edited again"))).willResetSchedule
        )
    }

    @Test
    fun editorFingerprintMatchesCardContentOfSavedDto() {
        val s = persisted(form())
        val withImages = s.copy(
            image = "  q.png  ",
            options = listOf(
                s.options[0].copy(image = " opt.png "),
                s.options[1]
            )
        )
        // buildDto() trims scalar images to null when blank — mirror that here.
        val dto = QuestionDto(
            id = withImages.questionId,
            elements = withImages.elements,
            image = withImages.image.trim().ifBlank { null },
            options = withImages.options.map { o ->
                OptionDto(id = o.id, elements = o.elements, image = o.image.trim().ifBlank { null })
            },
            correctOptionIds = withImages.options.filter { it.isCorrect }.map { it.id },
            passageId = withImages.passageId?.takeIf { it.isNotBlank() }
        )
        assertEquals(CardContent.of(dto), editorCardFingerprint(withImages))
        assertFalse(
            withImages.copy(baselineCardFingerprint = editorCardFingerprint(withImages)).willResetSchedule
        )
    }
}
