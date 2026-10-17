package com.mcqapp.data.export

import com.mcqapp.data.anki.AnkiDtoMapper
import com.mcqapp.data.anki.AnkiPackageWriter
import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.util.Logger
import kotlinx.serialization.json.Json
import java.io.OutputStream

/**
 * Where an export went, without carrying its bytes: the name the caller
 * suggested and the MIME type it created the document with. The bytes went
 * straight to the caller's stream.
 */
data class ExportFile(val fileName: String, val mimeType: String)

/** Renders a single-paper export into a caller-provided stream. */
class PaperExporter(private val db: AppDatabase) {

    private val json = Json { prettyPrint = true }

    suspend fun exportPaper(
        paperId: String,
        format: ExportFormat,
        sink: OutputStream,
        twoColumnPdf: Boolean = false
    ): ExportFile {
        val dto = Exporter(db).getPaperDto(paperId)
            ?: throw IllegalStateException("Paper not found")
        Logger.i("EXPORT", "Exporting paper '${dto.title}' as ${format.name}")
        return render(
            withPassageDtos(dto), dto.title, format, sink,
            scheduling = loadScheduling(paperId),
            passages = loadPassages(dto),
            twoColumnPdf = twoColumnPdf
        )
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
        return render(withPassageDtos(dto), title, format, sink, twoColumnPdf = twoColumnPdf)
    }

    /** Single category (with descendants) through the same format writers. */
    suspend fun exportCategory(
        paperId: String,
        categoryId: String,
        format: ExportFormat,
        sink: OutputStream,
        twoColumnPdf: Boolean = false
    ): ExportFile {
        val dto = Exporter(db).getCategoriesDto(paperId, setOf(categoryId))
            ?.takeIf { it.categories.isNotEmpty() }
            ?: throw IllegalStateException("Category not found or empty")
        val title = dto.categories.firstOrNull()?.title ?: dto.title
        Logger.i("EXPORT", "Exporting category '$title' as ${format.name}")
        return render(
            withPassageDtos(dto), title, format, sink,
            scheduling = loadScheduling(paperId),
            passages = loadPassages(dto),
            twoColumnPdf = twoColumnPdf
        )
    }

    /**
     * Review progress for a paper, keyed by question id, for Anki packages.
     * JSON backups carry their own copy of this (see [Exporter.exportAll]), so
     * a backup no longer loses schedules on restore.
     */
    private suspend fun loadScheduling(paperId: String): Map<String, CardScheduleDto> =
        db.cardStateDao().getByPaper(paperId)
            .filter { it.reps > 0 || it.dueAt > 0L }
            .associate { state ->
                state.questionId to CardScheduleDto(
                    ease = state.ease,
                    intervalDays = state.intervalDays,
                    dueAt = state.dueAt,
                    reps = state.reps,
                    lapses = state.lapses,
                    leech = state.leech,
                    lastReviewedAt = state.lastReviewedAt,
                    difficulty = state.difficulty,
                    stability = state.stability,
                    cardState = state.cardState.name,
                    reviewCount = state.reviewCount
                )
            }

    /** Passages referenced by [dto]'s questions, keyed by id, for the apkg writer. */
    private suspend fun loadPassages(dto: PaperDto): Map<String, com.mcqapp.domain.Passage> {
        val ids = dto.categories
            .flatMap { it.questions }
            .mapNotNull { it.passageId }
            .distinct()
        if (ids.isEmpty()) return emptyMap()
        return Exporter(db).getPassagesByIds(ids).associateBy { it.id }
    }

    /**
     * Attaches the passages [dto]'s questions reference as wire DTOs, so every
     * format path (JSON, ZIP, HTML) writes the grouping without each caller
     * repeating the lookup. A dto that already carries them is untouched.
     */
    private suspend fun withPassageDtos(dto: PaperDto): PaperDto {
        if (dto.passages.isNotEmpty()) return dto
        val ids = dto.categories
            .flatMap { it.questions }
            .mapNotNull { it.passageId }
            .distinct()
        if (ids.isEmpty()) return dto
        return dto.copy(
            passages = Exporter(db).getPassagesByIds(ids).map { p ->
                com.mcqapp.data.io.PassageDto(
                    id = p.id,
                    title = p.title,
                    elements = p.elements,
                    image = p.image
                )
            }
        )
    }

    /**
     * Writes [dto] to [sink] in [format] and reports what was written. Every
     * format writes into the caller's stream — the byte-heavy ones directly,
     * the text ones through a single encode — so no format hands back a second
     * full copy of the payload.
     */
    private fun render(
        dto: PaperDto,
        title: String,
        format: ExportFormat,
        sink: OutputStream,
        scheduling: Map<String, CardScheduleDto> = emptyMap(),
        passages: Map<String, com.mcqapp.domain.Passage> = emptyMap(),
        twoColumnPdf: Boolean = false
    ): ExportFile {
        val base = baseName(title)
        val fileName = when (format) {
            ExportFormat.JSON_INLINE -> {
                writeUtf8(
                    sink,
                    json.encodeToString(
                        McqFileDto.serializer(),
                        McqFileDto(version = 1, papers = listOf(dto), passages = dto.passages)
                    )
                )
                "$base.json"
            }
            ExportFormat.ZIP -> {
                ZipPaperWriter.paperToZip(dto, sink)
                "$base.zip"
            }
            ExportFormat.HTML -> {
                writeUtf8(sink, HtmlPaperWriter.paperToHtml(dto, dto.passages))
                "$base.html"
            }
            ExportFormat.HTML_QUIZ -> {
                writeUtf8(sink, HtmlPaperWriter.paperToQuizHtml(dto, dto.passages))
                "$base-quiz.html"
            }
            ExportFormat.PDF -> {
                PdfPaperWriter.paperToPdf(dto, sink, twoColumn = twoColumnPdf)
                "$base.pdf"
            }
            ExportFormat.PDF_ANSWER_KEY -> {
                PdfPaperWriter.paperToPdf(dto, sink, answersAtEnd = true, twoColumn = twoColumnPdf)
                "$base-answer-key.pdf"
            }
            ExportFormat.APKG -> {
                AnkiPackageWriter.writeTo(
                    dto,
                    AnkiDtoMapper.flattenQuestions(dto),
                    sink,
                    scheduling,
                    passages
                )
                "$base.apkg"
            }
            ExportFormat.DOCX -> {
                DocxPaperWriter.paperToDocx(dto, sink, dto.passages)
                "$base.docx"
            }
        }
        return ExportFile(fileName, format.mimeType)
    }

    private fun writeUtf8(sink: OutputStream, text: String) {
        sink.write(text.toByteArray(Charsets.UTF_8))
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
