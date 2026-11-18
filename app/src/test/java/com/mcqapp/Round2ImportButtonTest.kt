package com.mcqapp

import com.mcqapp.ui.importscreen.ImportUiState
import com.mcqapp.ui.importscreen.importButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I2 — a streamed backup carries no `originalFile`, so the Import button must
 * gate on the preview's `isBackup` flag instead. Without it a >4 MB multi-paper
 * backup whose first paper is all duplicates gets "Nothing new to import" and a
 * disabled button even though papers 2+ and the schedules would be restored.
 */
class Round2ImportButtonTest {

    /** Streaming seam: originalFile is null, the flag carries the backup truth. */
    private fun streamedState(
        isBackup: Boolean,
        paperCount: Int,
        previewTruncated: Boolean = false,
        totalQuestions: Int = 1
    ) = ImportUiState(
        paperTitle = "Paper",
        originalFile = null,
        isBackup = isBackup,
        paperCount = paperCount,
        previewTruncated = previewTruncated,
        totalQuestionCount = totalQuestions
    )

    @Test
    fun streamedBackupWithAllDuplicateFirstPaperCanStillRestore() {
        val button = importButton(
            streamedState(isBackup = true, paperCount = 3),
            newQuestions = 0,
            changedQuestions = 0
        )
        assertTrue(button.enabled)
        assertEquals("Restore backup", button.label)
    }

    @Test
    fun streamedBackupWithNewQuestionsNamesThem() {
        val button = importButton(
            streamedState(isBackup = true, paperCount = 2),
            newQuestions = 4,
            changedQuestions = 0
        )
        assertTrue(button.enabled)
        assertEquals("Import 4 new questions", button.label)
    }

    @Test
    fun streamedOrdinaryFileWithNothingNewIsStillDisabled() {
        // Negative: a null originalFile alone must not make every file a backup.
        val button = importButton(
            streamedState(isBackup = false, paperCount = 1),
            newQuestions = 0,
            changedQuestions = 0
        )
        assertFalse(button.enabled)
        assertEquals("Nothing new to import", button.label)
    }

    @Test
    fun flagOrOriginalFileBothIdentifyABackup() {
        // The whole-file path still sets originalFile and leaves the flag false.
        val viaFlag = importButton(
            streamedState(isBackup = true, paperCount = 2), 0, 0
        )
        val viaFile = importButton(
            ImportUiState(
                paperTitle = "Paper",
                originalFile = com.mcqapp.data.io.McqFileDto(
                    papers = listOf(
                        com.mcqapp.data.io.PaperDto(
                            "p1", "One",
                            categories = listOf(com.mcqapp.data.io.CategoryDto("c1", "C"))
                        ),
                        com.mcqapp.data.io.PaperDto(
                            "p2", "Two",
                            categories = listOf(com.mcqapp.data.io.CategoryDto("c2", "C"))
                        )
                    )
                )
            ),
            0, 0
        )
        assertTrue(viaFlag.enabled)
        assertTrue(viaFile.enabled)
        assertEquals(viaFile.label, viaFlag.label)
    }
}
