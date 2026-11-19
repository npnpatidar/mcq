package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.EditorUiState
import com.mcqapp.ui.editor.OptionEditorState
import com.mcqapp.ui.editor.editorCardFingerprint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-2 E9: the disclosure must still track exactly the content
 * [com.mcqapp.data.io.CardContent] fingerprints, now read from the fingerprint
 * cached on the state instance instead of re-hashed on every composition.
 * These are pure state tests (no Room) and run on every host.
 */
class Round2EditorFingerprintTest {

    private fun form() = EditorUiState(
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
        passageId = "pass-1"
    )

    private fun persisted(s: EditorUiState) =
        s.copy(baselineCardFingerprint = editorCardFingerprint(s))

    @Test
    fun aCleanStoredFormShowsNoNoteAndRepeatedReadsAgree() {
        val stored = persisted(form())
        assertFalse(stored.willResetSchedule)
        assertFalse("cached value is stable across reads", stored.willResetSchedule)
    }

    @Test
    fun aBodyEditShowsTheNoteUntilTheBaselineIsReanchored() {
        val edited = persisted(form()).copy(
            elements = listOf(ContentElement.TextElement("Edited stem?"))
        )
        assertTrue(edited.willResetSchedule)
        assertTrue(edited.willResetSchedule)

        val afterSave = edited.copy(baselineCardFingerprint = editorCardFingerprint(edited))
        assertFalse(afterSave.willResetSchedule)
        assertTrue(
            afterSave.copy(elements = listOf(ContentElement.TextElement("Edited again")))
                .willResetSchedule
        )
    }

    @Test
    fun metadataAndExplanationOnlyEditsDoNotShowTheNote() {
        val stored = persisted(form())
        assertFalse(
            stored.copy(
                explanationElements = listOf(ContentElement.TextElement("A longer explanation.")),
                marks = "5",
                tags = "changed",
                categoryId = "c-2"
            ).willResetSchedule
        )
    }

    @Test
    fun noPersistedBaselineMeansNoNoteEvenAfterAnEdit() {
        val fresh = form().copy(questionId = "", isNew = true, baselineCardFingerprint = null)
        assertFalse(fresh.willResetSchedule)
        assertFalse(
            fresh.copy(elements = listOf(ContentElement.TextElement("Edited"))).willResetSchedule
        )
    }

    @Test
    fun answerKeyChangesStillShowTheNote() {
        val stored = persisted(form())
        assertTrue(
            stored.copy(
                options = listOf(
                    stored.options[0].copy(isCorrect = false),
                    stored.options[1].copy(isCorrect = true)
                )
            ).willResetSchedule
        )
    }
}
