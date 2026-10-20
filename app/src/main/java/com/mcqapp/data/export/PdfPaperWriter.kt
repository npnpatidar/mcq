package com.mcqapp.data.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import java.io.OutputStream

/**
 * Renders a paper as a PDF (A4) with questions, options, correct answers and
 * images, using only the Android framework (no extra dependencies).
 */
object PdfPaperWriter {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    internal const val MARGIN = 40
    internal val CONTENT_W = (PAGE_W - MARGIN * 2).toFloat()
    internal val BOTTOM = (PAGE_H - MARGIN).toFloat()

    /**
     * Renders the document straight to [out]: `PdfDocument.writeTo` already
     * writes progressively, so the finished file never exists as a second copy.
     *
     * The questions themselves are never held either — they come off the
     * [PaperStream] walk, and [PdfDocument] keeps the finished pages the way it
     * already did. With [answersAtEnd] the paper is walked a second time for
     * the key, which costs a re-read and saves holding every answer and
     * explanation until the end.
     *
     * @param answersAtEnd when true, questions print without correct marks and
     * all answers + explanations move to an "Answer Key" section at the end,
     * so the paper can be attempted without seeing answers.
     * @param twoColumn flow content down the left column, then the right,
     * then the next page (single-column pagination is otherwise untouched).
     */
    suspend fun writePdf(
        stream: PaperStream,
        out: OutputStream,
        answersAtEnd: Boolean = false,
        twoColumn: Boolean = false
    ) {
        val doc = PdfDocument()
        try {
            val w = Writer(PdfPages(doc, PAGE_W, PAGE_H), twoColumn)
            w.paragraph(stream.title, 20f, Typeface.BOLD, Color.BLACK, spaceAfter = 4f)
            val meta = buildList {
                if (stream.description.isNotBlank()) add(stream.description)
                val bits = mutableListOf<String>()
                if (stream.durationMinutes > 0) bits.add("${stream.durationMinutes} min")
                if (stream.negativeMarking > 0) bits.add("negative marking ${stream.negativeMarking}")
                bits.add("${stream.totalQuestions} questions")
                add(bits.joinToString(" • "))
            }.joinToString("\n")
            w.paragraph(meta, 10f, Typeface.NORMAL, Color.GRAY, spaceAfter = 12f)

            questions(w, stream, answersAtEnd)
            if (answersAtEnd) answerKey(w, stream)
            w.finish()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** The question flow: every category heading, then its questions. */
    private suspend fun questions(w: Writer, stream: PaperStream, answersAtEnd: Boolean) {
        val passagesById = stream.passages.associateBy { it.id }
        var number = 0
        var openPassageId: String? = null
        stream.walk(
            onCategory = { category ->
                w.paragraph(category.title, 14f, Typeface.BOLD, Color.BLACK, spaceBefore = 10f)
                // The passage prints once above its group, not above every
                // member: contiguity is the invariant, and a reader reads a
                // document linearly. A new run (or a resumed group in another
                // category) prints its header again.
                openPassageId = null
            },
            onQuestion = { question ->
                number++
                w.ensureSpace(120f)
                if (question.passageId != openPassageId) {
                    openPassageId = question.passageId
                    passagesById[question.passageId]?.let { passage ->
                        w.paragraph(
                            stripInlineHtml(passage.title.ifBlank { "Passage" }),
                            11f, Typeface.BOLD, Color.rgb(70, 70, 70), spaceBefore = 6f
                        )
                        w.elements(passage.elements, passage.image, indent = 0f)
                    }
                }
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
                question.options.forEachIndexed { index, option ->
                    val isCorrect = !answersAtEnd && option.id in question.correctOptionIds
                    val marker = ContentElement.TextElement(
                        // By position, so the label always matches the
                        // letter the answer names.
                        (if (isCorrect) "✓ " else "○ ") + "${optionLetter(index)}) "
                    )
                    val body = option.elements.ifEmpty {
                        if (option.text.isNotBlank()) listOf(ContentElement.TextElement(option.text))
                        else emptyList()
                    }
                    w.elements(listOf(marker) + body, option.image, indent = 12f)
                }
                val answer = answerLabels(question.options, question.correctOptionIds)
                // With the answers at the end, nothing is revealed here: the
                // marks above are already suppressed and the key prints later.
                if (!answersAtEnd) {
                    if (answer.isNotEmpty()) {
                        w.paragraph(
                            SpannableStringBuilder("Answer: ")
                                .append(SpannableStringBuilder(answer.joinToString(", "))),
                            11f, Typeface.NORMAL, Color.rgb(46, 125, 50)
                        )
                    }
                    w.elements(
                        question.explanationElements,
                        question.explanationImage,
                        question.explanation
                    )
                }
            }
        )
    }

    /**
     * The key, printed from a second walk: only the questions that actually
     * have an answer or an explanation are listed, and the heading waits for
     * the first of them — exactly what collecting them all and checking
     * `isNotEmpty()` produced, without holding a million answers in the heap.
     */
    private suspend fun answerKey(w: Writer, stream: PaperStream) {
        var number = 0
        var headingWritten = false
        stream.walk(
            onCategory = {},
            onQuestion = { question ->
                number++
                val entry = answerKeyEntry(question, number)
                if (entry != null) {
                    if (!headingWritten) {
                        w.paragraph("Answer Key", 16f, Typeface.BOLD, Color.BLACK, spaceBefore = 16f)
                        headingWritten = true
                    }
                    w.paragraph(entry, 11f, Typeface.NORMAL, Color.rgb(46, 125, 50), spaceBefore = 6f)
                    w.elements(
                        question.explanationElements,
                        question.explanationImage,
                        question.explanation
                    )
                }
            }
        )
    }

    /**
     * The page surface the writer draws on. PdfDocument's page is adapted by
     * [PdfPages]; the oversized-block test injects a recorder instead,
     * because Robolectric cannot construct a PdfDocument page ("document is
     * closed!") — but it CAN run StaticLayout and Canvas, so this seam is
     * what makes the page/draw output observable without a real file.
     */
    internal interface PdfPageHost {
        val canvas: Canvas
        fun finish()
    }

    internal fun interface PdfPageSink {
        /** Starts page [pageNum] (1-based); page [pageNum-1] is already finished. */
        fun startPage(pageNum: Int): PdfPageHost
    }

    internal class PdfPages(
        private val doc: PdfDocument,
        private val width: Int = PAGE_W,
        private val height: Int = PAGE_H
    ) : PdfPageSink {
        override fun startPage(pageNum: Int): PdfPageHost {
            val p = doc.startPage(PdfDocument.PageInfo.Builder(width, height, pageNum).create())
            return object : PdfPageHost {
                override val canvas = p.canvas
                override fun finish() = doc.finishPage(p)
            }
        }
    }

    internal class Writer(
        private val pages: PdfPageSink,
        private val twoColumn: Boolean
    ) {
        private val geometry = PdfColumns(PAGE_W.toFloat(), MARGIN.toFloat(), twoColumn)
        private var col = 0
        private var pageNum = 0
        private var page: PdfPageHost = newPage()
        private var y = MARGIN.toFloat()

        private fun paint(size: Float, style: Int, color: Int) = TextPaint().apply {
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, style)
            this.color = color
            isAntiAlias = true
        }

        private fun newPage(): PdfPageHost {
            pageNum++
            y = MARGIN.toFloat()
            return pages.startPage(pageNum)
        }

        fun finish() {
            footer()
            page.finish()
        }

        private fun footer() {
            val p = paint(9f, Typeface.NORMAL, Color.GRAY)
            val text = "Page $pageNum"
            page.canvas.drawText(text, (PAGE_W - p.measureText(text)) / 2f, (PAGE_H - 20).toFloat(), p)
        }

        private fun nextPage() {
            footer()
            page.finish()
            page = newPage()
        }

        /** Next column, or next page once past the last one. */
        private fun advance() {
            if (twoColumn && col == 0) {
                col = 1
                y = MARGIN.toFloat()
            } else {
                nextPage()
                col = 0
            }
        }

        /**
         * Jump past a nearly-empty column end before a question starts,
         * so questions do not open as widows (two-column only; the
         * single-column pagination stays exactly as it was).
         */
        fun ensureSpace(minRemaining: Float) {
            if (twoColumn && y > MARGIN && y + minRemaining > BOTTOM) advance()
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
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint(size, style, color), (geometry.contentWidth() - indent).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1f)
                .setIncludePad(true)
                .build()
            y += spaceBefore
            if (y + layout.height > BOTTOM && y > MARGIN + spaceBefore) {
                advance()
                y += spaceBefore
            }
            val canvas = page.canvas
            if (y + layout.height <= BOTTOM) {
                canvas.save()
                canvas.translate(geometry.originX(col) + indent, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height
            } else {
                // A block taller than one printable page: band it. advance()
                // protects a block's START only, so this used to draw the
                // whole layout once from the top of the fresh page and lose
                // everything below the page bottom.
                drawTallBands(layout.height.toFloat()) { canvas0, blockTop, bandHeight ->
                    canvas0.save()
                    canvas0.translate(geometry.originX(col) + indent, y - blockTop)
                    canvas0.clipRect(
                        0f, blockTop.toFloat(),
                        (layout.width).toFloat(), (blockTop + bandHeight).toFloat()
                    )
                    layout.draw(canvas0)
                    canvas0.restore()
                }
            }
            y += spaceAfter
        }

        /**
         * Slices one oversized block into page-sized bands, calling [band]
         * once per band with (canvas, block-space top, band height); the
         * callback draws the band clipped at the current [y]. Everything
         * after the first band starts on a fresh page/column ([advance]
         * sets y = MARGIN), so each band fills `BOTTOM - y` of the page it
         * lands on, and [y] ends at the last band's bottom edge.
         */
        private fun drawTallBands(blockHeight: Float, band: (Canvas, Int, Int) -> Unit) {
            var blockTop = 0
            var bandNo = 0
            while (true) {
                if (bandNo > 0) advance()
                val bandHeight = minOf(
                    (BOTTOM - y).toInt(),
                    (blockHeight - blockTop).toInt()
                ).coerceAtLeast(1) // a single line taller than a page: draw it, clipped
                band(page.canvas, blockTop, bandHeight)
                y += bandHeight
                blockTop += bandHeight
                if (blockTop >= blockHeight) break
                bandNo++
                check(bandNo < 100_000) { "oversized block banding did not advance" }
            }
        }

        /** The oversized-block test observes the draining of the flow here. */
        internal fun finalY(): Float = y

        fun image(src: String?, indent: Float = 0f) {
            if (src == null) return
            val embedded = parseDataUri(src)
            // Subsampled, and Throwable because an oversized embedded image
            // raises OutOfMemoryError rather than an Exception.
            val bitmap: Bitmap? = embedded?.let {
                com.mcqapp.util.decodeBounded(it.bytes, com.mcqapp.util.MAX_EXPORT_DIMENSION)
            }
            if (bitmap == null) {
                val label = if (embedded == null) "[image: $src]" else "[unreadable image]"
                paragraph(label, 10f, Typeface.NORMAL, Color.GRAY, indent = indent)
                return
            }
            try {
                val maxW = geometry.contentWidth() - indent
                val maxH = BOTTOM - MARGIN
                var scale = minOf(1f, maxW / bitmap.width)
                if (bitmap.height * scale > maxH) scale = maxH / bitmap.height
                val dw = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val dh = (bitmap.height * scale).toInt().coerceAtLeast(1)
                if (y + dh > BOTTOM && y > MARGIN) advance()
                val scaled = if (dw != bitmap.width || dh != bitmap.height) {
                    Bitmap.createScaledBitmap(bitmap, dw, dh, true)
                } else {
                    bitmap
                }
                try {
                    page.canvas.drawBitmap(scaled, geometry.originX(col) + indent, y, null)
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
            val avail = geometry.contentWidth() - indent
            val scale = if (total > avail && total > 0) avail / total else 1f
            // X edges depend on the current column: recompute after
            // every advance, or rows after a column/page break draw at
            // the old column's x — on top of earlier rows.
            val scaled = FloatArray(cols) { natural[it] * scale }
            fun computeEdges() = columnEdges(geometry.originX(col) + indent, scaled)
            var edges = computeEdges()
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
                if (y + h > BOTTOM && y > MARGIN) {
                    advance()
                    edges = computeEdges()
                }
                val top = y
                if (top + h <= BOTTOM) {
                    for (c in 0 until cols) {
                        page.canvas.drawRect(edges[c], top, edges[c + 1], top + h, linePaint)
                        val layout = layouts[c] ?: continue
                        page.canvas.save()
                        page.canvas.translate(edges[c] + pad, top + pad)
                        layout.draw(page.canvas)
                        page.canvas.restore()
                    }
                    y = top + h
                } else {
                    // A row taller than one printable page: band it over ROW
                    // space so the ruling stays aligned across columns —
                    // every page draws one band of all cells. The old code
                    // drew the whole row once below the page bottom and lost
                    // the tail of the giant cell.
                    val contentH = h - (2 * pad).toInt()
                    var rowTop = 0
                    var bandNo = 0
                    while (true) {
                        if (bandNo > 0) {
                            advance()
                            edges = computeEdges()
                        }
                        val bandH = minOf(
                            (BOTTOM - y - 2 * pad).toInt(),
                            contentH - rowTop
                        ).coerceAtLeast(1)
                        for (c in 0 until cols) {
                            page.canvas.drawRect(
                                edges[c], y, edges[c + 1],
                                y + pad + bandH + pad, linePaint
                            )
                            val layout = layouts[c] ?: continue
                            page.canvas.save()
                            page.canvas.translate(edges[c] + pad, y + pad - rowTop)
                            page.canvas.clipRect(
                                0f, rowTop.toFloat(),
                                (layout.width).toFloat(), (rowTop + bandH).toFloat()
                            )
                            layout.draw(page.canvas)
                            page.canvas.restore()
                        }
                        y += pad + bandH + pad
                        rowTop += bandH
                        if (rowTop >= contentH) break
                        bandNo++
                        check(bandNo < 100_000) { "oversized row banding did not advance" }
                    }
                }
            }
            y += 6f
        }
    }
}

/** X edges for table columns: chained from the origin, so a recompute after a column/page break moves the whole grid. */
internal fun columnEdges(originX: Float, widths: FloatArray): FloatArray {
    val edges = FloatArray(widths.size + 1)
    edges[0] = originX
    for (c in widths.indices) edges[c + 1] = edges[c] + widths[c]
    return edges
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
        val spans = styleSpans(tag)
        when {
            isBlockBreak(tag) -> {
                if (out.isNotEmpty() && out[out.length - 1] != '\n') out.append("\n")
            }
            spans.isNotEmpty() -> {
                if (closing) {
                    // A close with no matching open pops nothing and is
                    // dropped, the way a real tag would be when rendered.
                    if (open.lastOrNull()?.first == tag) {
                        val (_, start) = open.removeLast()
                        for (span in spans) {
                            out.setSpan(span, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                } else {
                    open.addLast(tag to out.length)
                }
            }
            // Unknown tag shapes are literal text in legacy questions and must
            // stay visible rather than vanish — the rule the DOCX writer's
            // inlineRuns and the HTML writer already follow. `<one>` in
            // `Pick <one> & "all"?` reads the same in all three exports.
            else -> out.append(decodeEntities(match.value))
        }
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

/**
 * One answer-key line — `Q7. B`, or `Q7. —` when nothing is correct — or null
 * when the question has neither an answer nor an explanation, in which case
 * the key does not list it at all.
 *
 * Explanation counts whether it is rich content, the legacy text field or an
 * image: they are the same thing to a reader, and the inline path already
 * prints all three. Pure so the entry rules unit-test without Android.
 */
internal fun answerKeyEntry(question: QuestionDto, number: Int): String? {
    val answer = answerLabels(question.options, question.correctOptionIds).joinToString(", ")
    val hasExplanation = question.explanationElements.isNotEmpty() ||
        question.explanation.isNotBlank() ||
        question.explanationImage != null
    if (answer.isEmpty() && !hasExplanation) return null
    return "Q$number. " + if (answer.isEmpty()) "—" else answer
}

/**
 * Column geometry for PDF pages: one full-width column, or two with a
 * gutter. Pure so the layout math unit-tests without Android.
 */
internal data class PdfColumns(
    val pageWidth: Float,
    val margin: Float,
    val twoColumn: Boolean
) {
    val gutter: Float = 24f
    val count: Int get() = if (twoColumn) 2 else 1
    fun contentWidth(): Float =
        if (!twoColumn) pageWidth - 2 * margin
        else (pageWidth - 2 * margin - gutter) / 2
    fun originX(col: Int): Float = margin + col * (contentWidth() + gutter)
}
