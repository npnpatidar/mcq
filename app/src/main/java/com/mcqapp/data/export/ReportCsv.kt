package com.mcqapp.data.export

import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Slice 5 (stats-phase3.md): a shareable CSV report with the same numbers the
 * result screen shows. UTF-8, RFC 4180 quoting, stable column order, and a
 * header comment block with attempt/type/family/export time.
 */
data class ReportCsvHeader(
    val attemptId: Long,
    val paperTitle: String,
    val testType: String,
    val family: String,
    val exportedAtEpochMs: Long,
    /** The report's guessing-corrected accuracy (0..1), when it has one. */
    val guessingCorrected: Double? = null
)

data class ReportCsvRow(
    val number: Int,
    val questionId: String,
    val category: String,
    val result: String,
    val dwellSeconds: Long,
    val tags: String
)

object ReportCsv {

    val COLUMNS = listOf("number", "questionId", "category", "result", "dwellSeconds", "tags")

    fun write(out: Writer, header: ReportCsvHeader, rows: List<ReportCsvRow>) {
        out.append("# mcq report export\n")
        out.append("# attemptId=").append(header.attemptId.toString()).append('\n')
        out.append("# paper=").append(sanitizeComment(header.paperTitle)).append('\n')
        out.append("# type=").append(sanitizeComment(header.testType)).append('\n')
        out.append("# family=").append(sanitizeComment(header.family)).append('\n')
        out.append("# exportedAt=").append(formatIso(header.exportedAtEpochMs)).append('\n')
        header.guessingCorrected?.let {
            out.append("# guessingCorrected=")
                .append(((it * 100).roundToInt()).toString())
                .append("%\n")
        }
        out.append(COLUMNS.joinToString(",")).append('\n')
        rows.forEach { row ->
            out.append(
                listOf(
                    row.number.toString(),
                    row.questionId,
                    row.category,
                    row.result,
                    row.dwellSeconds.toString(),
                    row.tags
                ).joinToString(",") { quote(it) }
            ).append('\n')
        }
    }

    internal fun quote(value: String): String {
        val needs = value.indexOfAny(charArrayOf(',', '"', '\n', '\r')) >= 0
        if (!needs) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun sanitizeComment(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ')

    private fun formatIso(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }
}
