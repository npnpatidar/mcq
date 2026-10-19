package com.mcqapp

import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.ui.importscreen.ImportUiState
import com.mcqapp.ui.importscreen.importButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Import button's gate. The preview only knows the first paper, while a
 * backup is imported whole - so a small backup whose previewed paper is all
 * duplicates used to read "Nothing new to import" and stay disabled even
 * though the run would have restored the remaining papers and the schedules.
 */
class ImportButtonTest {

    private fun question(id: String) = QuestionDto(id = id, text = "Q $id")

    private fun paper(id: String, vararg qids: String) = PaperDto(
        id = id,
        title = "Paper $id",
        categories = listOf(
            CategoryDto(id = "$id-cat", title = "Cat", questions = qids.map { question(it) })
        )
    )

    private fun schedule() = CardScheduleDto(
        ease = 2.5, intervalDays = 3, dueAt = 1000L,
        reps = 2, lapses = 0, leech = false, lastReviewedAt = 500L
    )

    private fun stateFor(
        file: McqFileDto,
        previewTruncated: Boolean = false,
        totalQuestions: Int = 1,
        importing: Boolean = false,
        paperTitle: String = "Paper p1"
    ) = ImportUiState(
        paperTitle = paperTitle,
        originalFile = file,
        previewTruncated = previewTruncated,
        totalQuestionCount = totalQuestions,
        importing = importing
    )

    private fun ordinaryFile() = McqFileDto(papers = listOf(paper("p1", "q1")))

    @Test
    fun anOrdinaryFileWithNothingNewIsDisabled() {
        val button = importButton(stateFor(ordinaryFile()), newQuestions = 0, changedQuestions = 0)
        assertFalse(button.enabled)
        assertEquals("Nothing new to import", button.label)
    }

    @Test
    fun aSmallAllDuplicateBackupCanStillRestore() {
        // One paper plus review progress is a backup, but its previewed paper
        // has nothing new: import() would restore the schedules regardless.
        val file = McqFileDto(
            papers = listOf(paper("p1", "q1")),
            scheduling = mapOf("q1" to schedule())
        )
        val button = importButton(stateFor(file), newQuestions = 0, changedQuestions = 0)
        assertTrue(button.enabled)
        assertEquals("Restore backup", button.label)
    }

    @Test
    fun aBackupWithNewQuestionsStillNamesThem() {
        val file = McqFileDto(papers = listOf(paper("p1", "q1"), paper("p2", "q2")))
        val button = importButton(stateFor(file), newQuestions = 3, changedQuestions = 0)
        assertTrue(button.enabled)
        assertEquals("Import 3 new questions", button.label)
    }

    @Test
    fun anOrdinaryFileWithWorkIsEnabledAndLabelled() {
        val state = stateFor(ordinaryFile())
        assertEquals("Import 2 new questions", importButton(state, 2, 0).label)
        assertEquals("Update 2 questions", importButton(state, 0, 2).label)
        assertEquals("Import 2 new • update 1", importButton(state, 2, 1).label)
        assertTrue(importButton(state, 2, 0).enabled)
        assertTrue(importButton(state, 0, 2).enabled)
    }

    @Test
    fun aTruncatedFileStaysEnabledWhateverTheSampleShows() {
        // The held rows are a sample; the counts are not the file's.
        val state = stateFor(ordinaryFile(), previewTruncated = true, totalQuestions = 250)
        val button = importButton(state, newQuestions = 0, changedQuestions = 0)
        assertTrue(button.enabled)
        assertEquals("Import 250 questions", button.label)
        assertEquals("Importing…", importButton(state.copy(importing = true), 0, 0).label)
    }

    @Test
    fun aBlankTitleOrARunningImportDisablesTheButton() {
        val file = McqFileDto(papers = listOf(paper("p1", "q1"), paper("p2", "q2")))
        assertFalse(importButton(stateFor(file, paperTitle = ""), 1, 0).enabled)
        assertFalse(importButton(stateFor(file, importing = true), 1, 0).enabled)
    }
}
