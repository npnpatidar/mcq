package com.mcqapp

import com.mcqapp.data.anki.AnkiDtoMapper
import com.mcqapp.data.anki.AnkiPackageReader
import com.mcqapp.data.anki.AnkiPackageWriter
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app's own export must be readable by its own import, which is the whole
 * point of the schema-11 choice: Anki upgrades a legacy package on import, and
 * we read back both what we wrote and what Anki left after that upgrade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnkiRoundTripTest {

    @Test
    fun exportedDeckReadsBackAsTheSameQuestion() {
        val paper = PaperDto(
            id = "p1",
            title = "Biology",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Cells",
                    questions = listOf(
                        QuestionDto(
                            id = "q1",
                            text = "Which organelle makes ATP?",
                            image = "data:image/png;base64,iVBORw0KGgo=",
                            options = listOf(
                                OptionDto("a", "Mitochondrion"),
                                OptionDto("b", "Ribosome"),
                                OptionDto("c", "Nucleus")
                            ),
                            correctOptionIds = listOf("a"),
                            explanation = "ATP synthase sits on the inner membrane.",
                            difficulty = "medium",
                            marks = 2.0,
                            tags = listOf("biology")
                        )
                    )
                )
            )
        )

        val apkg = AnkiPackageWriter.write(paper, AnkiDtoMapper.flattenQuestions(paper))
        val read = AnkiPackageReader.read(apkg)

        assertEquals(0, read.recallCount)
        assertEquals(1, read.file.papers.size)
        val readPaper = read.file.papers.single()
        assertEquals("Biology", readPaper.title)
        val category = readPaper.categories.single()
        assertEquals("Cells", category.title)
        val q = category.questions.single()
        assertEquals(paper.categories.single().questions.single().text, q.text)
        assertEquals(listOf("Mitochondrion", "Ribosome", "Nucleus"), q.options.map { it.text })
        assertEquals(1, q.correctOptionIds.size)
        assertEquals("Mitochondrion", q.options.single { it.id == q.correctOptionIds.single() }.text)
        assertEquals("ATP synthase sits on the inner membrane.", q.explanation)
        assertEquals("data:image/png;base64,iVBORw0KGgo=", q.image)
    }
}
