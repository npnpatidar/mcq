package com.mcqapp

import com.mcqapp.data.anki.AnkiPackageWriter
import com.mcqapp.data.export.DocxPaperWriter
import com.mcqapp.data.export.HtmlPaperWriter
import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.export.PdfPaperWriter
import com.mcqapp.data.export.ZipPaperWriter
import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.toQuestionDto
import com.mcqapp.domain.Passage
import com.mcqapp.domain.Question
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * The exporters take a [PaperStream] and write into a stream of the caller's,
 * so a test that wants finished bytes drives them here instead of repeating
 * the plumbing in every case. A [PaperDto] is a stream already (that is what
 * the bookmark and selection exports hand over), so a fixture needs no database.
 */
fun exportBytes(write: suspend (OutputStream) -> Unit): ByteArray {
    val out = ByteArrayOutputStream()
    runBlocking { write(out) }
    return out.toByteArray()
}

/** A paper whose questions reference [passages], as an export would build it. */
fun PaperDto.withPassages(passages: List<PassageDto>): PaperDto =
    if (passages.isEmpty()) this else copy(passages = passages)

fun htmlOf(paper: PaperDto, quiz: Boolean = false, passages: List<PassageDto> = emptyList()): String {
    val out = StringBuilder()
    val stream = PaperStream.of(paper.withPassages(passages))
    runBlocking {
        if (quiz) HtmlPaperWriter.writeQuizHtml(stream, out) else HtmlPaperWriter.writeHtml(stream, out)
    }
    return out.toString()
}

fun zipOf(paper: PaperDto): ByteArray =
    exportBytes { ZipPaperWriter.paperToZip(PaperStream.of(paper), it) }

fun docxOf(paper: PaperDto, passages: List<PassageDto> = emptyList()): ByteArray =
    exportBytes { DocxPaperWriter.writeDocx(PaperStream.of(paper.withPassages(passages)), it) }

fun pdfOf(
    paper: PaperDto,
    answersAtEnd: Boolean = false,
    twoColumn: Boolean = false,
    passages: List<PassageDto> = emptyList()
): ByteArray = exportBytes {
    PdfPaperWriter.writePdf(
        PaperStream.of(paper.withPassages(passages)),
        it,
        answersAtEnd = answersAtEnd,
        twoColumn = twoColumn
    )
}

/**
 * An .apkg for [paper], written the way the exporter writes one: the progress
 * in [scheduling] travels with the notes it names, and [passages] are the
 * domain objects the card front prepends.
 */
fun apkgOf(
    paper: PaperDto,
    scheduling: Map<String, CardScheduleDto> = emptyMap(),
    passages: Map<String, Passage> = emptyMap()
): ByteArray {
    val dto = if (passages.isEmpty()) paper else paper.copy(
        passages = passages.values.map {
            PassageDto(id = it.id, title = it.title, elements = it.elements, image = it.image)
        }
    )
    return exportBytes { out ->
        AnkiPackageWriter.writeTo(PaperStream.of(dto), out) { ids ->
            scheduling.filterKeys { it in ids }
        }
    }
}

/**
 * A package for a paper whose questions are [questions] rather than the ones
 * the fixture paper carries. They travel as the paper's own list, which is where
 * a hand-built domain question (its `categoryId` naming no category) used to
 * land too.
 */
fun apkgOfQuestions(
    paper: PaperDto,
    questions: List<Question>,
    scheduling: Map<String, CardScheduleDto> = emptyMap()
): ByteArray = apkgOf(
    paper.copy(categories = emptyList(), questions = questions.map { it.toQuestionDto() }),
    scheduling
)

/**
 * Every question id the Anki writer would note, in the order it notes them:
 * the walk's questions, then the paper's own top-level list. The two are
 * disjoint for anything the app writes.
 */
fun questionIdsOf(paper: PaperDto): List<String> {
    val stream = PaperStream.of(paper)
    val ids = mutableListOf<String>()
    runBlocking {
        stream.walk(onCategory = {}, onQuestion = { ids += it.id })
    }
    ids += stream.looseQuestions.map { it.id }
    return ids
}
