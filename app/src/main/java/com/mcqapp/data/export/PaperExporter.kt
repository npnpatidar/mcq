package com.mcqapp.data.export

import com.mcqapp.data.io.Exporter
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.util.Logger
import kotlinx.serialization.json.Json

data class ExportResult(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
)

/** Produces the bytes for a single-paper export in the requested format. */
class PaperExporter(private val db: AppDatabase) {

    private val json = Json { prettyPrint = true }

    suspend fun exportPaper(paperId: String, format: ExportFormat): ExportResult {
        val dto = Exporter(db).getPaperDto(paperId)
            ?: throw IllegalStateException("Paper not found")
        Logger.i("EXPORT", "Exporting paper '${dto.title}' as ${format.name}")
        return render(dto, dto.title, format)
    }

    /** Any assembled DTO (e.g. bookmarks) through the same format writers. */
    fun exportDto(dto: PaperDto, title: String, format: ExportFormat): ExportResult {
        Logger.i("EXPORT", "Exporting '$title' as ${format.name}")
        return render(dto, title, format)
    }

    /** Single category (with descendants) through the same format writers. */
    suspend fun exportCategory(paperId: String, categoryId: String, format: ExportFormat): ExportResult {
        val dto = Exporter(db).getCategoriesDto(paperId, setOf(categoryId))
            ?.takeIf { it.categories.isNotEmpty() }
            ?: throw IllegalStateException("Category not found or empty")
        val title = dto.categories.firstOrNull()?.title ?: dto.title
        Logger.i("EXPORT", "Exporting category '$title' as ${format.name}")
        return render(dto, title, format)
    }

    private fun render(dto: PaperDto, title: String, format: ExportFormat): ExportResult {
        val base = baseName(title)
        return when (format) {
            ExportFormat.JSON_INLINE -> ExportResult(
                "$base.json",
                format.mimeType,
                json.encodeToString(
                    McqFileDto.serializer(),
                    McqFileDto(version = 1, papers = listOf(dto))
                ).toByteArray(Charsets.UTF_8)
            )
            ExportFormat.ZIP -> ExportResult(
                "$base.zip",
                format.mimeType,
                ZipPaperWriter.paperToZipBytes(dto)
            )
            ExportFormat.HTML -> ExportResult(
                "$base.html",
                format.mimeType,
                HtmlPaperWriter.paperToHtml(dto).toByteArray(Charsets.UTF_8)
            )
            ExportFormat.HTML_QUIZ -> ExportResult(
                "$base-quiz.html",
                format.mimeType,
                HtmlPaperWriter.paperToQuizHtml(dto).toByteArray(Charsets.UTF_8)
            )
            ExportFormat.PDF -> ExportResult(
                "$base.pdf",
                format.mimeType,
                PdfPaperWriter.paperToPdfBytes(dto)
            )
            ExportFormat.PDF_ANSWER_KEY -> ExportResult(
                "$base-answer-key.pdf",
                format.mimeType,
                PdfPaperWriter.paperToPdfBytes(dto, answersAtEnd = true)
            )
        }
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
