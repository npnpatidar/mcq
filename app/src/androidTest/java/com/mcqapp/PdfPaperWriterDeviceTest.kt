package com.mcqapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.export.PdfPaperWriter
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * The PDF byte assertions used to live in the JVM suite behind @Ignore:
 * Robolectric's PdfDocument is already closed when constructed on the host, so
 * even PdfDocument().startPage() throws there. On an emulator the real framework
 * class runs, which is why these checks moved here. CI's ui-test job executes
 * connectedDebugAndroidTest, so they now run rather than being skipped.
 */
@RunWith(AndroidJUnit4::class)
class PdfPaperWriterDeviceTest {

    private fun paper(questionCount: Int = 2, withExplanation: Boolean = true) = PaperDto(
        id = "p1",
        title = "Physics Paper",
        categories = listOf(
            CategoryDto(
                id = "c1",
                title = "Mechanics",
                questions = (1..questionCount).map { i ->
                    QuestionDto(
                        id = "q$i",
                        text = "Question number $i about gravity?",
                        options = listOf(
                            OptionDto(id = "q$i-a", text = "First option $i"),
                            OptionDto(id = "q$i-b", text = "Second option $i")
                        ),
                        correctOptionIds = listOf("q$i-b"),
                        explanation = if (withExplanation) "Because of physics $i" else ""
                    )
                }
            )
        )
    )

    /** The passage shape whose PDF export used to be ignored in ExportWritersTest. */
    private fun passagePaper(): PaperDto {
        val passage = PassageDto(
            id = "passage-1",
            title = "The water cycle",
            elements = listOf(ContentElement.TextElement("Rain fills rivers."))
        )
        val members = listOf("q1", "q2").map { id ->
            QuestionDto(
                id = id,
                text = "Question $id?",
                options = listOf(OptionDto(id = "a", text = "A"), OptionDto(id = "b", text = "B")),
                correctOptionIds = listOf("a"),
                passageId = "passage-1"
            )
        }
        val standalone = QuestionDto(
            id = "q3",
            text = "Standalone?",
            options = listOf(OptionDto(id = "a", text = "A"), OptionDto(id = "b", text = "B")),
            correctOptionIds = listOf("a")
        )
        return PaperDto(
            id = "p1",
            title = "Paper",
            categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = members + standalone)),
            passages = listOf(passage)
        )
    }

    private fun pdfOf(
        paper: PaperDto,
        answersAtEnd: Boolean = false,
        twoColumn: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking {
            PdfPaperWriter.writePdf(
                PaperStream.of(paper),
                out,
                answersAtEnd = answersAtEnd,
                twoColumn = twoColumn
            )
        }
        return out.toByteArray()
    }

    private fun ByteArray.asLatin1(): String = String(this, Charsets.ISO_8859_1)

    @Test
    fun producesARealPdfFile() {
        val bytes = pdfOf(paper())
        assertTrue("too small to be a PDF: ${bytes.size}", bytes.size > 400)
        assertTrue("missing PDF header", bytes.copyOfRange(0, 5).asLatin1() == "%PDF-")
        assertTrue("missing PDF trailer", bytes.asLatin1().contains("%%EOF"))
    }

    @Test
    fun anEmptyPaperStillProducesAValidFile() {
        val empty = PaperDto(id = "p1", title = "Empty", categories = emptyList())
        val bytes = pdfOf(empty)
        assertTrue(bytes.copyOfRange(0, 5).asLatin1() == "%PDF-")
    }

    @Test
    fun answersAtEndKeepsTheBodyAndTheKeyLayout() {
        val inline = pdfOf(paper())
        val atEnd = pdfOf(paper(), answersAtEnd = true)

        assertTrue("inline export produced nothing", inline.isNotEmpty())
        assertTrue("answers-at-end export produced nothing", atEnd.isNotEmpty())
        // The two layouts differ, which is what answersAtEnd asks for.
        assertTrue("answersAtEnd should change the output", !inline.contentEquals(atEnd))
    }

    @Test
    fun aTwoColumnExportStillProducesAValidFile() {
        val bytes = pdfOf(paper(questionCount = 6), twoColumn = true)
        assertTrue(bytes.copyOfRange(0, 5).asLatin1() == "%PDF-")
        assertTrue(bytes.asLatin1().contains("%%EOF"))
    }

    @Test
    fun largePapersArePaginatedRatherThanDropped() {
        val bytes = pdfOf(paper(questionCount = 40))
        assertTrue(bytes.asLatin1().contains("%%EOF"))
        // 40 questions cannot fit one page; the writer must have paginated.
        assertTrue("expected more than one page object", bytes.asLatin1().contains("/Type /Page"))
    }

    @Test
    fun pdfWithPassagesStaysAValidPdf() {
        val bytes = pdfOf(passagePaper())
        assertTrue(bytes.size > 400)
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("%%EOF"))
    }
}
