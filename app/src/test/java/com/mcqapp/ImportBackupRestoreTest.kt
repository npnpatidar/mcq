package com.mcqapp

import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.ui.importscreen.ImportUiState
import com.mcqapp.ui.importscreen.buildImportFile
import com.mcqapp.ui.importscreen.isBackupFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1: backup restore through the Import screen silently dropped everything
 * after the first paper — and all SM-2 schedules. The preview can only show
 * one paper, so without special handling the rest never reached the Importer.
 *
 * These pin the file the Import button actually writes: backups keep every
 * paper plus review progress, while single-paper imports behave exactly as
 * before.
 */
class ImportBackupRestoreTest {

    private fun question(id: String, text: String = "Q $id") = QuestionDto(id = id, text = text)

    private fun paper(id: String, vararg qids: String) = PaperDto(
        id = id,
        title = "Paper $id",
        categories = listOf(
            CategoryDto(id = "$id-cat", title = "Cat", questions = qids.map { question(it) })
        )
    )

    private fun stateFor(firstPaper: PaperDto, edit: (List<QuestionDto>) -> List<QuestionDto> = { it }) =
        ImportUiState(
            paperTitle = firstPaper.title,
            questions = edit(firstPaper.categories.flatMap { it.questions }),
            originalFile = null
        )

    // --- backup detection ---

    @Test
    fun `several papers mean a backup`() {
        val file = McqFileDto(papers = listOf(paper("p1", "q1"), paper("p2", "q2")))
        assertTrue(isBackupFile(file))
    }

    @Test
    fun `review progress alone means a backup`() {
        val file = McqFileDto(
            papers = listOf(paper("p1", "q1")),
            scheduling = mapOf(
                "q1" to CardScheduleDto(
                    ease = 2.5, intervalDays = 3, dueAt = 1000L,
                    reps = 2, lapses = 0, leech = false, lastReviewedAt = 500L
                )
            )
        )
        assertTrue(isBackupFile(file))
    }

    @Test
    fun `a lone paper without progress is an ordinary import`() {
        assertFalse(isBackupFile(McqFileDto(papers = listOf(paper("p1", "q1")))))
    }

    // --- the built file ---

    @Test
    fun `a backup keeps every paper`() {
        val original = McqFileDto(
            papers = listOf(
                paper("p1", "q1", "q2"),
                paper("p2", "q3"),
                paper("p3", "q4", "q5", "q6")
            )
        )
        val file = buildImportFile(stateFor(original.papers[0]), original)
        assertEquals(listOf("p1", "p2", "p3"), file.papers.map { it.id })
        assertEquals(
            listOf("q1", "q2", "q3", "q4", "q5", "q6"),
            file.papers.flatMap { p -> p.categories.flatMap { it.questions } }.map { it.id }
        )
    }

    @Test
    fun `a backup keeps review progress, bookmarks and attempts`() {
        val scheduling = mapOf(
            "q1" to CardScheduleDto(
                ease = 2.5, intervalDays = 3, dueAt = 1000L,
                reps = 2, lapses = 0, leech = false, lastReviewedAt = 500L
            )
        )
        val original = McqFileDto(
            papers = listOf(paper("p1", "q1")),
            bookmarks = listOf("q1"),
            attempts = emptyList(),
            scheduling = scheduling
        )
        val file = buildImportFile(stateFor(original.papers[0]), original)
        assertEquals(scheduling, file.scheduling)
        assertEquals(listOf("q1"), file.bookmarks)
    }

    @Test
    fun `preview edits still apply to the backup first paper`() {
        val original = McqFileDto(
            papers = listOf(paper("p1", "q1", "q2"), paper("p2", "q3"))
        )
        // The preview deleted q2.
        val state = stateFor(original.papers[0]) { qs -> qs.filter { it.id != "q2" } }
        val file = buildImportFile(state, original)
        assertEquals(
            listOf("q1"),
            file.papers[0].categories.flatMap { it.questions }.map { it.id }
        )
        // Untouched papers ride along unedited.
        assertEquals(
            listOf("q3"),
            file.papers[1].categories.flatMap { it.questions }.map { it.id }
        )
    }

    @Test
    fun `a single paper imports exactly as before`() {
        val original = McqFileDto(papers = listOf(paper("p1", "q1", "q2")))
        val file = buildImportFile(stateFor(original.papers[0]), original)
        assertEquals(1, file.papers.size)
        assertEquals("p1", file.papers[0].id)
        assertTrue(file.scheduling.isEmpty())
    }

    @Test
    fun `no original mints an ephemeral paper with no history`() {
        val state = ImportUiState(
            paperTitle = "Fresh",
            questions = listOf(question("q1"))
        )
        val file = buildImportFile(state, null)
        assertEquals(1, file.papers.size)
        assertEquals("Fresh", file.papers[0].title)
        assertTrue(file.bookmarks.isEmpty())
        assertTrue(file.attempts.isEmpty())
        assertTrue(file.scheduling.isEmpty())
    }
}
