package com.mcqapp.data.export

import com.mcqapp.data.anki.AnkiPackageWriter
import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.toCardScheduleDto
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Where an export went, without carrying its bytes: the name the caller
 * suggested and the MIME type it created the document with. The bytes went
 * straight to the caller's stream.
 */
data class ExportFile(val fileName: String, val mimeType: String)

/**
 * Renders a single-paper export into a caller-provided stream.
 *
 * Every format writes as it goes: the paper arrives as a [PaperStream] walk,
 * the document goes into the caller's sink, and neither the paper's question
 * graph nor the finished document is ever held whole. The walk runs off the
 * main thread — a million-question export is minutes of I/O and encoding.
 */
class PaperExporter(private val db: AppDatabase) {

    /**
     * The passages with the given ids, for exports assembled outside a
     * paper walk — the selection export resolves its member questions'
     * passages here. Delegates to [Exporter], which owns that decoding.
     */
    suspend fun passagesOfIds(ids: List<String>): List<PassageDto> =
        Exporter(db).passagesOfIds(ids)

    suspend fun exportPaper(
        paperId: String,
        format: ExportFormat,
        sink: OutputStream,
        twoColumnPdf: Boolean = false
    ): ExportFile {
        val stream = Exporter(db).paperStream(paperId)
            ?: throw IllegalStateException("Paper not found")
        Logger.i("EXPORT", "Exporting paper '${stream.title}' as ${format.name}")
        return render(stream, stream.title, format, sink, ::loadScheduling, twoColumnPdf)
    }

    /** Any assembled DTO (e.g. bookmarks) through the same format writers. */
    suspend fun exportDto(
        dto: PaperDto,
        title: String,
        format: ExportFormat,
        sink: OutputStream,
        twoColumnPdf: Boolean = false
    ): ExportFile {
        Logger.i("EXPORT", "Exporting '$title' as ${format.name}")
        return render(PaperStream.of(dto), title, format, sink, twoColumnPdf = twoColumnPdf)
    }

    /**
     * Any database-backed walk (e.g. the bookmarks export) through the same
     * format writers, without an in-memory DTO: the stream reads its questions
     * a page at a time while the writer is on them.
     */
    suspend fun exportStream(
        stream: PaperStream,
        title: String,
        format: ExportFormat,
        sink: OutputStream,
        twoColumnPdf: Boolean = false
    ): ExportFile {
        Logger.i("EXPORT", "Exporting '$title' as ${format.name}")
        return render(stream, title, format, sink, twoColumnPdf = twoColumnPdf)
    }

    /**
     * The union of the given root categories (subtrees included), in every
     * format. The ticked branches on a card are exactly this shape, so the
     * Export button needs no new writer.
     *
     * A category with no questions resolves to a stream with no cards, so the
     * guard tests the walk's size too: an empty document would otherwise be
     * written while the error message promised otherwise.
     */
    suspend fun exportSelection(
        paperId: String,
        rootCategoryIds: List<String>,
        format: ExportFormat,
        sink: OutputStream,
        twoColumnPdf: Boolean = false
    ): ExportFile {
        val stream = Exporter(db).categoryStream(paperId, rootCategoryIds.toSet())
            ?.takeIf { it.categories.isNotEmpty() && it.totalQuestions > 0 }
            ?: throw IllegalStateException("The selected categories have no questions to export.")
        Logger.i("EXPORT", "Exporting selection of ${rootCategoryIds.size} branch(es) as ${format.name}")
        return render(stream, stream.title, format, sink, ::loadScheduling, twoColumnPdf)
    }

    /**
     * Review progress for a batch of questions, for Anki packages. JSON backups
     * carry their own copy of this (see [Exporter.exportAllTo]), so a backup no
     * longer loses schedules on restore.
     *
     * A batch at a time, because the alternative was every `card_state` row the
     * paper has in one list: ~100 MB of entities for a million studied cards,
     * for the handful of ids the writer is about to name.
     */
    private suspend fun loadScheduling(questionIds: List<String>): Map<String, CardScheduleDto> {
        if (questionIds.isEmpty()) return emptyMap()
        return db.cardStateDao().getForQuestionsChunked(questionIds)
            .filter { it.reps > 0 || it.dueAt > 0L }
            .associate { state -> state.questionId to state.toCardScheduleDto() }
    }

    /**
     * Writes [stream] to [sink] in [format] and reports what was written. Every
     * format writes into the caller's stream — the byte-heavy ones directly,
     * the text ones through a single encode — so no format hands back a second
     * full copy of the payload.
     */
    private suspend fun render(
        stream: PaperStream,
        title: String,
        format: ExportFormat,
        sink: OutputStream,
        schedulingFor: suspend (List<String>) -> Map<String, CardScheduleDto> = { emptyMap() },
        twoColumnPdf: Boolean = false
    ): ExportFile = withContext(Dispatchers.IO) {
        val base = baseName(title)
        val fileName = when (format) {
            ExportFormat.JSON_INLINE -> {
                text(sink) { PaperJsonWriter.writePaperJson(it, stream) }
                "$base.json"
            }
            ExportFormat.ZIP -> {
                ZipPaperWriter.paperToZip(stream, sink)
                "$base.zip"
            }
            ExportFormat.HTML -> {
                text(sink) { HtmlPaperWriter.writeHtml(stream, it) }
                "$base.html"
            }
            ExportFormat.HTML_QUIZ -> {
                text(sink) { HtmlPaperWriter.writeQuizHtml(stream, it) }
                "$base-quiz.html"
            }
            ExportFormat.PDF -> {
                PdfPaperWriter.writePdf(stream, sink, twoColumn = twoColumnPdf)
                "$base.pdf"
            }
            ExportFormat.PDF_ANSWER_KEY -> {
                PdfPaperWriter.writePdf(
                    stream, sink, answersAtEnd = true, twoColumn = twoColumnPdf
                )
                "$base-answer-key.pdf"
            }
            ExportFormat.APKG -> {
                AnkiPackageWriter.writeTo(stream, sink, schedulingFor)
                "$base.apkg"
            }
            ExportFormat.DOCX -> {
                DocxPaperWriter.writeDocx(stream, sink)
                "$base.docx"
            }
        }
        ExportFile(fileName, format.mimeType)
    }

    /**
     * Runs a text writer over [sink] through one buffered UTF-8 encode, so a
     * document is never built as a String and then encoded into a second copy
     * of itself. Flushed, never closed: the sink belongs to the caller.
     */
    private suspend fun text(sink: OutputStream, write: suspend (Appendable) -> Unit) {
        val out = BufferedWriter(OutputStreamWriter(sink, Charsets.UTF_8), 1 shl 14)
        write(out)
        out.flush()
    }

    companion object {
        fun baseName(title: String): String =
            title.replace(Regex("[\\\\/:*?\"<>|]"), "")
                .trim()
                .replace(Regex("\\s+"), "-")
                .take(80)
                .ifBlank { "paper" }

        fun fileNameFor(title: String, format: ExportFormat): String =
            "${baseName(title)}${format.fileSuffix}.${format.extension}"
    }
}
