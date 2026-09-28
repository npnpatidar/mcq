package com.mcqapp.data.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.mcqapp.data.io.PaperDto
import java.io.ByteArrayOutputStream

/**
 * Renders a paper as a PDF (A4) with questions, options, correct answers and
 * images, using only the Android framework (no extra dependencies).
 */
object PdfPaperWriter {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40
    private val CONTENT_W = (PAGE_W - MARGIN * 2).toFloat()
    private val BOTTOM = (PAGE_H - MARGIN).toFloat()

    fun paperToPdfBytes(paper: PaperDto): ByteArray {
        val doc = PdfDocument()
        try {
            val w = Writer(doc)
            w.paragraph(paper.title, 20f, Typeface.BOLD, Color.BLACK, spaceAfter = 4f)
            val meta = buildList {
                if (paper.description.isNotBlank()) add(paper.description)
                val bits = mutableListOf<String>()
                if (paper.durationMinutes > 0) bits.add("${paper.durationMinutes} min")
                if (paper.negativeMarking > 0) bits.add("negative marking ${paper.negativeMarking}")
                bits.add("${paper.categories.sumOf { it.questions.size }} questions")
                add(bits.joinToString(" • "))
            }.joinToString("\n")
            w.paragraph(meta, 10f, Typeface.NORMAL, Color.GRAY, spaceAfter = 12f)

            var number = 0
            for (category in paper.categories) {
                w.paragraph(category.title, 14f, Typeface.BOLD, Color.BLACK, spaceBefore = 10f)
                for (question in category.questions) {
                    number++
                    w.paragraph(
                        "Q$number. ${question.text}",
                        12f, Typeface.BOLD, Color.BLACK, spaceBefore = 8f
                    )
                    w.image(question.image)
                    for (option in question.options) {
                        val isCorrect = option.id in question.correctOptionIds
                        w.paragraph(
                            (if (isCorrect) "✓ " else "○ ") + "${option.id}) ${option.text}",
                            11f,
                            if (isCorrect) Typeface.BOLD else Typeface.NORMAL,
                            if (isCorrect) Color.rgb(46, 125, 50) else Color.BLACK,
                            indent = 12f,
                            spaceAfter = 1f
                        )
                        w.image(option.image, indent = 12f)
                    }
                    val correct = question.options.filter { it.id in question.correctOptionIds }
                    if (correct.isNotEmpty()) {
                        w.paragraph(
                            "Answer: " + correct.joinToString(", ") { it.text },
                            11f, Typeface.NORMAL, Color.rgb(46, 125, 50)
                        )
                    }
                    if (question.explanation.isNotBlank()) {
                        w.paragraph(
                            "Explanation: ${question.explanation}",
                            10f, Typeface.NORMAL, Color.GRAY
                        )
                    }
                    w.image(question.explanationImage)
                }
            }
            w.finish()
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    private class Writer(private val doc: PdfDocument) {
        private var pageNum = 0
        private var page: PdfDocument.Page = newPage()
        private var y = MARGIN.toFloat()

        private fun paint(size: Float, style: Int, color: Int) = TextPaint().apply {
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, style)
            this.color = color
            isAntiAlias = true
        }

        private fun newPage(): PdfDocument.Page {
            pageNum++
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create()
            val p = doc.startPage(info)
            y = MARGIN.toFloat()
            return p
        }

        fun finish() {
            footer()
            doc.finishPage(page)
        }

        private fun footer() {
            val p = paint(9f, Typeface.NORMAL, Color.GRAY)
            val text = "Page $pageNum"
            page.canvas.drawText(text, (PAGE_W - p.measureText(text)) / 2f, (PAGE_H - 20).toFloat(), p)
        }

        private fun nextPage() {
            footer()
            doc.finishPage(page)
            page = newPage()
        }

        fun paragraph(
            text: String,
            size: Float,
            style: Int,
            color: Int,
            indent: Float = 0f,
            spaceBefore: Float = 4f,
            spaceAfter: Float = 4f
        ) {
            if (text.isBlank()) return
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint(size, style, color), (CONTENT_W - indent).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1f)
                .setIncludePad(true)
                .build()
            y += spaceBefore
            if (y + layout.height > BOTTOM && y > MARGIN + spaceBefore) {
                nextPage()
                y += spaceBefore
            }
            val canvas = page.canvas
            canvas.save()
            canvas.translate(MARGIN + indent, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + spaceAfter
        }

        fun image(src: String?, indent: Float = 0f) {
            if (src == null) return
            val embedded = parseDataUri(src)
            val bitmap: Bitmap? = embedded?.let {
                try {
                    BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size)
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap == null) {
                val label = if (embedded == null) "[image: $src]" else "[unreadable image]"
                paragraph(label, 10f, Typeface.NORMAL, Color.GRAY, indent = indent)
                return
            }
            try {
                val maxW = CONTENT_W - indent
                val maxH = BOTTOM - MARGIN
                var scale = minOf(1f, maxW / bitmap.width)
                if (bitmap.height * scale > maxH) scale = maxH / bitmap.height
                val dw = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val dh = (bitmap.height * scale).toInt().coerceAtLeast(1)
                if (y + dh > BOTTOM && y > MARGIN) nextPage()
                val scaled = if (dw != bitmap.width || dh != bitmap.height) {
                    Bitmap.createScaledBitmap(bitmap, dw, dh, true)
                } else {
                    bitmap
                }
                try {
                    page.canvas.drawBitmap(scaled, MARGIN + indent, y, null)
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
                y += dh + 6f
            } finally {
                bitmap.recycle()
            }
        }
    }
}
