package com.mcqapp.data.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import android.text.TextPaint
import com.mcqapp.data.io.PaperDto
import com.mcqapp.domain.ContentElement
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

    fun paperToPdfBytes(paper: PaperDto): ByteArray = paperToPdfBytes(paper, answersAtEnd = false)

    /**
     * @param answersAtEnd when true, questions print without correct marks and
     * all answers + explanations move to an "Answer Key" section at the end,
     * so the paper can be attempted without seeing answers.
     */
    fun paperToPdfBytes(paper: PaperDto, answersAtEnd: Boolean): ByteArray {
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

            data class KeyEntry(
                val number: Int,
                val answer: CharSequence,
                val explanationElements: List<ContentElement>,
                val explanationImage: String?
            )
            val key = mutableListOf<KeyEntry>()

            var number = 0
            for (category in paper.categories) {
                w.paragraph(category.title, 14f, Typeface.BOLD, Color.BLACK, spaceBefore = 10f)
                for (question in category.questions) {
                    number++
                    // The number prefixes the body flow itself ("Q1. text…"
                    // in one paragraph) instead of a title line, so the
                    // question never prints twice nor starts below its
                    // number. Not bold, or <b> inside would not show.
                    val marker = buildString {
                        append("Q$number. ")
                        if (question.marks != 1.0) {
                            val display = if (question.marks == kotlin.math.floor(question.marks)) {
                                question.marks.toLong().toString()
                            } else {
                                question.marks.toString()
                            }
                            append("[$display marks] ")
                        }
                    }
                    val body = question.elements.ifEmpty {
                        if (question.text.isNotBlank()) listOf(ContentElement.TextElement(question.text))
                        else emptyList()
                    }
                    w.elements(
                        listOf(ContentElement.TextElement(marker)) + body,
                        question.image,
                        indent = 0f
                    )
                    for (option in question.options) {
                        val isCorrect = !answersAtEnd && option.id in question.correctOptionIds
                        val marker = ContentElement.TextElement(
                            (if (isCorrect) "✓ " else "○ ") + "${option.id}) "
                        )
                        val body = option.elements.ifEmpty {
                            if (option.text.isNotBlank()) listOf(ContentElement.TextElement(option.text))
                            else emptyList()
                        }
                        w.elements(listOf(marker) + body, option.image, indent = 12f)
                    }
                    val correct = question.options.filter { it.id in question.correctOptionIds }
                    val answerText = SpannableStringBuilder()
                    correct.forEachIndexed { index, option ->
                        if (index > 0) answerText.append(", ")
                        answerText.append(spannedFromInlineHtml(option.text))
                    }
                    if (answersAtEnd) {
                        if (answerText.isNotEmpty() || question.explanationElements.isNotEmpty()) {
                            key.add(
                                KeyEntry(
                                    number = number,
                                    answer = answerText,
                                    explanationElements = question.explanationElements,
                                    explanationImage = question.explanationImage
                                )
                            )
                        }
                    } else {
                        if (correct.isNotEmpty()) {
                            w.paragraph(
                                SpannableStringBuilder("Answer: ").append(answerText),
                                11f, Typeface.NORMAL, Color.rgb(46, 125, 50)
                            )
                        }
                        w.elements(question.explanationElements, question.explanationImage, question.explanation)
                    }
                }
            }
            if (answersAtEnd && key.isNotEmpty()) {
                w.paragraph("Answer Key", 16f, Typeface.BOLD, Color.BLACK, spaceBefore = 16f)
                for (entry in key) {
                    val line = SpannableStringBuilder("Q${entry.number}. ")
                    if (entry.answer.isBlank()) line.append("—") else line.append(entry.answer)
                    w.paragraph(line, 11f, Typeface.NORMAL, Color.rgb(46, 125, 50), spaceBefore = 6f)
                    w.elements(entry.explanationElements, entry.explanationImage)
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
            text: CharSequence,
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

        /**
         * Renders content elements: text as styled paragraphs (inline
         * tags become spans — bold, subscript, highlight…), images
         * inline, real bordered tables, and formulas drawn on canvas
         * flowing inside their sentence. Empty element lists fall back
         * to [legacyText] so pre-rich-content rows still print.
         */
        fun elements(
            elements: List<ContentElement>,
            legacyImage: String? = null,
            legacyText: String = "",
            indent: Float = 0f
        ) {
            val list = elements.ifEmpty {
                if (legacyText.isNotBlank()) listOf(ContentElement.TextElement(legacyText))
                else emptyList()
            }
            // Consecutive text and formulas accumulate into one paragraph
            // so equations flow inside their sentence; images and tables
            // break the run.
            val run = SpannableStringBuilder()
            fun flushRun() {
                if (run.isNotEmpty()) {
                    paragraph(run, 11f, Typeface.NORMAL, Color.BLACK, indent = indent)
                    run.clear()
                    run.clearSpans()
                }
            }
            for (element in list) {
                when (element) {
                    is ContentElement.TextElement -> {
                        val rich = spannedFromInlineHtml(element.text)
                        if (rich.isNotBlank()) run.append(rich)
                    }
                    is ContentElement.MathElement -> {
                        val nodes = mathMlToNodes(element.mathml)
                        if (nodes != null) {
                            val start = run.length
                            run.append("\uFFFC")
                            run.setSpan(
                                MathSpan(nodes, 11f),
                                start, run.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        } else {
                            val linear = mathToLinear(element.mathml)
                            run.append(if (linear.isNotBlank()) "[formula: $linear]" else "[formula]")
                        }
                    }
                    else -> {
                        flushRun()
                        when (element) {
                            is ContentElement.ImageElement -> image(element.src, indent = indent)
                            is ContentElement.TableElement -> table(element.rows, indent = indent)
                            is ContentElement.MathElement -> error("unreachable")
                            is ContentElement.TextElement -> error("unreachable")
                        }
                    }
                }
            }
            flushRun()
            image(legacyImage, indent = indent)
        }

        /**
         * A real bordered table grid sized to its content: columns take
         * their natural width (capped to the page), rows paginate one by
         * one. Cells render inline formatting like body text.
         */
        fun table(rows: List<List<String>>, indent: Float = 0f) {
            val cols = rows.maxOfOrNull { it.size } ?: 0
            if (rows.isEmpty() || cols == 0) return
            val pad = 4f
            val textPaint = paint(10f, Typeface.NORMAL, Color.BLACK)
            val natural = FloatArray(cols) { 2 * pad }
            for (row in rows) {
                for (c in 0 until cols) {
                    val w = textPaint.measureText(stripInlineHtml(row.getOrElse(c) { "" })) + 2 * pad
                    if (w > natural[c]) natural[c] = w
                }
            }
            val total = natural.sum()
            val avail = CONTENT_W - indent
            val scale = if (total > avail && total > 0) avail / total else 1f
            val edges = FloatArray(cols + 1)
            edges[0] = MARGIN + indent
            for (c in 0 until cols) edges[c + 1] = edges[c] + natural[c] * scale
            val linePaint = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE
                color = Color.BLACK
                strokeWidth = 1f
                isAntiAlias = true
            }
            fun cellLayout(text: Spanned, width: Float) =
                StaticLayout.Builder.obtain(
                    text, 0, text.length, textPaint,
                    width.toInt().coerceAtLeast(1)
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1f)
                    .setIncludePad(true)
                    .build()
            fun lineHeight(): Int {
                val fm = textPaint.fontMetricsInt
                return fm.descent - fm.ascent + fm.leading
            }
            for (row in rows) {
                val spans = List(cols) { c -> spannedFromInlineHtml(row.getOrElse(c) { "" }) }
                val layouts = spans.mapIndexed { c, span ->
                    if (span.isBlank()) null
                    else cellLayout(span, edges[c + 1] - edges[c] - 2 * pad)
                }
                val h = ((layouts.maxOfOrNull { it?.height ?: 0 } ?: 0)
                    .coerceAtLeast(lineHeight())) + (2 * pad).toInt()
                if (y + h > BOTTOM && y > MARGIN) nextPage()
                val top = y
                for (c in 0 until cols) {
                    page.canvas.drawRect(edges[c], top, edges[c + 1], top + h, linePaint)
                    val layout = layouts[c] ?: continue
                    page.canvas.save()
                    page.canvas.translate(edges[c] + pad, top + pad)
                    layout.draw(page.canvas)
                    page.canvas.restore()
                }
                y = top + h
            }
            y += 6f
        }
    }
}

private val tagLikePattern = Regex("<(/?)\\s*([a-zA-Z][a-zA-Z0-9]*)[^>]*>")

/**
 * Strips real tag shapes (`<sub>`, `</b>`, …) for plain-text output. A
 * bare `<` as in `5 < 6` is not a tag shape and survives.
 */
internal fun stripInlineHtml(text: String): String {
    if (!text.contains('<')) return text
    val sb = StringBuilder()
    var pos = 0
    for (match in tagLikePattern.findAll(text)) {
        sb.append(text.substring(pos, match.range.first))
        pos = match.range.last + 1
    }
    return sb.append(text.substring(pos)).toString()
}

private fun styleSpans(tag: String): List<Any> = when (tag) {
    "b", "strong" -> listOf(StyleSpan(Typeface.BOLD))
    "i", "em" -> listOf(StyleSpan(Typeface.ITALIC))
    "u" -> listOf(UnderlineSpan())
    "del", "s", "strike" -> listOf(StrikethroughSpan())
    "mark" -> listOf(BackgroundColorSpan(Color.YELLOW))
    "sub" -> listOf(RelativeSizeSpan(0.75f), SubscriptSpan())
    "sup" -> listOf(RelativeSizeSpan(0.75f), SuperscriptSpan())
    else -> emptyList()
}

private fun isBlockBreak(tag: String): Boolean =
    tag == "br" || tag == "p" || tag == "div" || tag == "li" || tag == "tr"

/**
 * Inline tags as Android spans for PDF paragraphs: bold/italic/underline/
 * strike/highlight plus true sub/superscripts (chemistry like H₂O keeps
 * working), `<br/>` line breaks, entities decoded. Unknown tag shapes
 * and bare `&`/`<` stay visible as text.
 */
internal fun spannedFromInlineHtml(text: String): Spanned {
    val out = SpannableStringBuilder()
    val open = ArrayDeque<Pair<String, Int>>()
    var pos = 0
    for (match in tagLikePattern.findAll(text)) {
        val chunk = text.substring(pos, match.range.first)
        if (chunk.isNotEmpty()) out.append(decodeEntities(chunk))
        val closing = match.groupValues[1] == "/"
        val tag = match.groupValues[2].lowercase()
        if (isBlockBreak(tag)) {
            if (out.isNotEmpty() && out[out.length - 1] != '\n') out.append("\n")
        } else if (!closing && styleSpans(tag).isNotEmpty()) {
            open.addLast(tag to out.length)
        } else if (closing && open.lastOrNull()?.first == tag) {
            val (_, start) = open.removeLast()
            for (span in styleSpans(tag)) {
                out.setSpan(span, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // Unknown opens are stripped; stray closes pop nothing.
        pos = match.range.last + 1
    }
    val tail = text.substring(pos)
    if (tail.isNotEmpty()) out.append(decodeEntities(tail))
    return out
}

/** The TeX source MathLive embeds in its output, when present. */
internal fun annotationTex(mathml: String): String? {
    val open = Regex("<annotation\\s+[^>]*>", RegexOption.IGNORE_CASE).find(mathml)
        ?: return null
    if (!open.value.contains("tex", ignoreCase = true)) return null
    val rest = mathml.substring(open.range.last + 1)
    val close = Regex("</annotation\\s*>", RegexOption.IGNORE_CASE).find(rest)
        ?: return null
    return rest.substring(0, close.range.first).trim().takeIf { it.isNotEmpty() }
}

private val entityPattern = Regex("&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

/**
 * Best-effort MathML to single-line text for PDF, which has no math
 * renderer: prefers the TeX annotation MathLive embeds
 * (`x^{2} + 2x + 1 = 0`), else strips tags and decodes entities.
 */
internal fun mathToLinear(mathml: String): String {
    annotationTex(mathml)?.let { return it }
    if (!mathml.contains('<') && !mathml.contains('&')) return mathml.trim()
    val stripped = stripInlineHtml(mathml)
    val decoded = entityPattern.replace(stripped) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#") -> {
                val code = if (body[1] == 'x' || body[1] == 'X') body.substring(2).toIntOrNull(16)
                else body.substring(1).toIntOrNull()
                if (code != null && code in 0..0x10FFFF) String(Character.toChars(code)) else match.value
            }
            body == "amp" -> "&"
            body == "lt" -> "<"
            body == "gt" -> ">"
            body == "quot" -> "\""
            body == "apos" -> "'"
            body == "nbsp" -> "\u00A0"
            else -> match.value
        }
    }
    return decoded.replace(Regex("\\s+"), " ").trim()
}
