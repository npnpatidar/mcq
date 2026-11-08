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
 */
object ReportPdf {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val LINE = 14f

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

            canvas.drawText("MCQ Report", MARGIN, y, titlePaint)
            y += 22f
            line("Paper: ${report.attempt.title}", boldPaint)
            line("Attempt: ${report.attempt.id} · ${report.family} · ${report.type ?: "legacy"}")
            line("Finished: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(report.attempt.finishedAt))}")
            blank()

            line("Score", boldPaint)
            val pct = (report.score.accuracy * 100).roundToInt()
            line("${report.score.correct}/${report.score.shown} correct · $pct% accuracy")
            line("Correct ${report.score.correct}, wrong ${report.score.wrong}, skipped ${report.score.skipped}, ungraded ${report.score.ungraded}")
            report.marginPoints?.let { line("Approx. margin: ±${String.format(Locale.US, "%.1f", it)} points (comparable sample)") }
            blank()

            line("Error mix", boldPaint)
            line("Knowledge gap ${report.errorMix.knowledgeGap}, rushed ${report.errorMix.rushed}, skipped ${report.errorMix.skipped}, possible guess ${report.errorMix.possibleGuess}, ungraded ${report.errorMix.ungraded}")
            if (report.errorMix.smallSample) line("(Timing split based on a small sample.)")
            blank()

            line("Timing", boldPaint)
            if (report.timing.hasDwell) {
                line("Median dwell ${report.timing.medianSeconds}s · slow threshold ${report.timing.slowThresholdSeconds}s · avg correct ${report.timing.correctAverageSeconds}s · avg wrong ${report.timing.wrongAverageSeconds}s")
            } else {
                line("No timing data recorded.")
            }
            blank()

            if (report.topics.isNotEmpty()) {
                line("Topics", boldPaint)
                report.topics.take(20).forEach { topic ->
                    line("${topic.title}: ${topic.correct}/${topic.asked} (${(topic.accuracy * 100).roundToInt()}%)${if (topic.smallSample) " · small sample" else ""}")
                }
                blank()
            }

            if (report.actions.isNotEmpty()) {
                line("Recommended actions", boldPaint)
                report.actions.take(5).forEach { action ->
                    val label = action.rule::class.simpleName ?: "Action"
                    line("• $label (${action.kind})")
                }
                blank()
            }

            line("Test schedule unchanged. Study scheduling is separate from mock/practice tests.", bodyPaint)
            line("Generated ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}", bodyPaint)

            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
