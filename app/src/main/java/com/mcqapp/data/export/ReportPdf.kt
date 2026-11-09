package com.mcqapp.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.mcqapp.domain.TestReport
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Slice 5: a simple v1 report PDF (tables/text, no charts). Honest and small;
 * the CSV carries the per-question detail.
 *
 * The text is laid out by the pure [lines] function and the PDF writer only
 * draws it, so the report's content is testable on a JVM even where
 * `PdfDocument` is unavailable (see `PdfPaperWriterTest`).
 */
object ReportPdf {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val LINE = 14f

    /** How a laid-out line is drawn. */
    enum class Style { TITLE, HEADING, BODY }

    /** One line of the report, before wrapping. */
    data class ReportLine(val text: String, val style: Style = Style.BODY)

    /**
     * The report's text in draw order. [generatedAt] is injectable so tests
     * can pin the timestamp line.
     */
    fun lines(
        report: TestReport,
        generatedAt: Long = System.currentTimeMillis()
    ): List<ReportLine> {
        val out = mutableListOf<ReportLine>()
        fun heading(text: String) = out.add(ReportLine(text, Style.HEADING))
        fun body(text: String) = out.add(ReportLine(text, Style.BODY))

        out.add(ReportLine("MCQ Report", Style.TITLE))
        heading("Paper: ${report.attempt.title}")
        body("Attempt: ${report.attempt.id} · ${report.family} · ${report.type ?: "legacy"}")
        body(
            "Finished: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(Date(report.attempt.finishedAt))
        )

        heading("Score")
        val pct = (report.score.accuracy * 100).roundToInt()
        body("${report.score.correct}/${report.score.shown} correct · $pct% accuracy")
        body(
            "Correct ${report.score.correct}, wrong ${report.score.wrong}, " +
                "skipped ${report.score.skipped}, ungraded ${report.score.ungraded}"
        )
        report.marginPoints?.let {
            body(
                "Approx. margin: ±${String.format(Locale.US, "%.1f", it)} points " +
                    "(comparable sample)"
            )
        }
        report.guessing?.let { guessing ->
            body(
                "Guessing-corrected: ${(guessing.correctedAccuracy * 100).roundToInt()}% " +
                    "(raw ${(guessing.rawAccuracy * 100).roundToInt()}%, " +
                    "chance ${(guessing.chance * 100).roundToInt()}%, estimate)"
            )
        }

        heading("Error mix")
        body(
            "Knowledge gap ${report.errorMix.knowledgeGap}, rushed ${report.errorMix.rushed}, " +
                "skipped ${report.errorMix.skipped}, possible guess " +
                "${report.errorMix.possibleGuess}, ungraded ${report.errorMix.ungraded}"
        )
        if (report.errorMix.smallSample) body("(Timing split based on a small sample.)")

        heading("Timing")
        if (report.timing.hasDwell) {
            body(
                "Median dwell ${report.timing.medianSeconds}s · slow threshold " +
                    "${report.timing.slowThresholdSeconds}s · avg correct " +
                    "${report.timing.correctAverageSeconds}s · avg wrong " +
                    "${report.timing.wrongAverageSeconds}s"
            )
        } else {
            body("No timing data recorded.")
        }

        if (report.topics.isNotEmpty()) {
            heading("Topics")
            report.topics.take(20).forEach { topic ->
                body(
                    "${topic.title}: ${topic.correct}/${topic.asked} " +
                        "(${(topic.accuracy * 100).roundToInt()}%)" +
                        if (topic.smallSample) " · small sample" else ""
                )
            }
        }

        if (report.actions.isNotEmpty()) {
            heading("Recommended actions")
            report.actions.take(5).forEach { action ->
                val label = action.rule::class.simpleName ?: "Action"
                body("• $label (${action.kind})")
            }
        }

        body("Test schedule unchanged. Study scheduling is separate from mock/practice tests.")
        body(
            "Generated " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date(generatedAt))
        )
        return out
    }

    fun write(report: TestReport, out: OutputStream) {
        val doc = PdfDocument()
        try {
            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create()
            var page = doc.startPage(pageInfo)
            var canvas = page.canvas
            var y = MARGIN

            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val bodyPaint = Paint().apply {
                color = Color.DKGRAY
                textSize = 10f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            val boldPaint = Paint().apply {
                color = Color.BLACK
                textSize = 10f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }

            fun newPage() {
                doc.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create()
                page = doc.startPage(pageInfo)
                canvas = page.canvas
                y = MARGIN
            }

            fun ensure(space: Float = LINE) {
                if (y + space > PAGE_H - MARGIN) newPage()
            }

            fun line(text: String, paint: Paint = bodyPaint, gap: Float = LINE) {
                // Simple wrapping by characters to avoid overflow.
                val maxChars = 95
                var remaining = text
                while (remaining.isNotEmpty()) {
                    ensure(gap)
                    val chunk = if (remaining.length <= maxChars) remaining else remaining.substring(0, maxChars)
                    canvas.drawText(chunk, MARGIN, y, paint)
                    y += gap
                    remaining = if (remaining.length <= maxChars) "" else remaining.substring(maxChars)
                }
            }

            fun blank(gap: Float = 8f) { y += gap }

            var previous: Style? = null
            lines(report).forEach { entry ->
                // A blank line before every section, as the hand-written
                // layout had.
                if (entry.style == Style.HEADING && previous != null) blank()
                when (entry.style) {
                    Style.TITLE -> {
                        canvas.drawText(entry.text, MARGIN, y, titlePaint)
                        y += 22f
                    }
                    Style.HEADING -> line(entry.text, boldPaint)
                    Style.BODY -> line(entry.text, bodyPaint)
                }
                previous = entry.style
            }

            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
