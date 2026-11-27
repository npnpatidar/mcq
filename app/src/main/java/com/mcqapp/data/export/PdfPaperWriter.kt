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
import androidx.core.graphics.scale
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import com.mcqapp.data.escapeHtmlText
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.CellPart
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.tableCellParts
import com.mcqapp.util.CELL_FORMULA_MARKER
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.CellSegment
import com.mcqapp.util.cellSegments
import com.mcqapp.util.forEachCellImage
import com.mcqapp.util.mapCellImages
import com.mcqapp.util.portableCellImageSrc
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
                    if (question.options.isEmpty()) {
                        // A flashcard has no option label to print: its back
                        // *is* the answer, so it is labelled "Answer:" here
                        // rather than left unlabelled or called an
                        // explanation, and printed once.
                        val hasBack = question.explanationElements.isNotEmpty() ||
                            question.explanation.isNotBlank() ||
                            question.explanationImage != null
                        if (hasBack) {
                            w.paragraph(
                                "Answer:",
                                11f, Typeface.NORMAL, Color.rgb(46, 125, 50)
                            )
                            w.elements(
                                question.explanationElements,
                                question.explanationImage,
                                question.explanation
                            )
                        }
                    } else {
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
                    if (question.options.isEmpty()) {
                        // The card's back text is already on the entry line;
                        // only a picture cannot ride it, so print the back's
                        // pictures here rather than the whole back twice.
                        w.elements(
                            question.explanationElements
                                .filterIsInstance<ContentElement.ImageElement>(),
                            question.explanationImage
                        )
                    } else {
                        w.elements(
                            question.explanationElements,
                            question.explanationImage,
                            question.explanation
                        )
                    }
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
                val label = if (embedded == null) unreadableImageLabel(src) else "[unreadable image]"
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
                            is ContentElement.ChemElement -> {
                                // Caption, formula and SMILES are plain text on
                                // the page; a valid PNG diagram rides the same
                                // image() path every other picture uses. No new
                                // renderer — an SVG or broken URI leaves only
                                // the text handles.
                                paragraph(
                                    chemElementText(element), 11f,
                                    Typeface.NORMAL, Color.BLACK, indent = indent
                                )
                                if (isPngDataUri(element.diagram)) {
                                    image(element.diagram, indent = indent)
                                }
                            }
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
         * one. Cells render inline formatting like body text, plus the rich
         * content a cell can carry: portable pictures are drawn in the grid,
         * MathML is typeset through [MathSpan], and a captured chemistry PNG
         * is drawn while a structure without one falls back to its readable
         * handles. A non-portable or hostile source keeps the text marker, so
         * a broken picture can never dump base64 onto the page.
         */
        fun table(rows: List<List<String>>, indent: Float = 0f) {
            val cols = rows.maxOfOrNull { it.size } ?: 0
            if (rows.isEmpty() || cols == 0) return
            val pad = TABLE_CELL_PAD
            val textPaint = paint(10f, Typeface.NORMAL, Color.BLACK)
            val natural = FloatArray(cols) { 2 * pad }
            for (row in rows) {
                for (c in 0 until cols) {
                    // Segment-aware measurement: a formula measures with its
                    // ReplacementSpan (a wide fraction must not collapse to
                    // the `[formula]` marker's width) and a picture reserves a
                    // nominal column so it is never squeezed to `[image]`.
                    val w = cellNaturalWidth(row.getOrElse(c) { "" }, textPaint) + 2 * pad
                    if (w > natural[c]) natural[c] = w
                }
            }
            val avail = geometry.contentWidth() - indent
            // X edges depend on the current column: recompute after
            // every advance, or rows after a column/page break draw at
            // the old column's x — on top of earlier rows.
            val scaled = fitColumns(natural, avail)
            val contentWidths = FloatArray(cols) { (scaled[it] - 2 * pad).coerceAtLeast(1f) }
            fun computeEdges() = columnEdges(geometry.originX(col) + indent, scaled)
            var edges = computeEdges()
            val linePaint = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE
                color = Color.BLACK
                strokeWidth = 1f
                isAntiAlias = true
            }
            fun lineHeight(): Int {
                val fm = textPaint.fontMetricsInt
                return fm.descent - fm.ascent + fm.leading
            }
            for (row in rows) {
                // Each cell is ordered blocks — prose and formulas flow in one
                // StaticLayout, pictures and structures sit between the runs —
                // so all of a rich cell is drawn in document order. Blocks are
                // built before the page check because their total height is
                // what decides the break.
                val contents = List(cols) { c ->
                    buildCellBlocks(
                        row.getOrElse(c) { "" },
                        contentWidths[c],
                        textPaint
                    )
                }
                try {
                    val contentH = contents.maxOfOrNull { cellBlocksHeight(it) } ?: 0f
                    val h = contentH.coerceAtLeast(lineHeight().toFloat()) + 2 * pad
                    if (y + h > BOTTOM && y > MARGIN) {
                        advance()
                        edges = computeEdges()
                    }
                    val top = y
                    if (top + h <= BOTTOM) {
                        for (c in 0 until cols) {
                            page.canvas.drawRect(edges[c], top, edges[c + 1], top + h, linePaint)
                            drawCellBlocks(
                                page.canvas, contents[c], edges[c] + pad,
                                top + pad, top + pad, top + pad + contentH,
                                edges[c + 1] - 1f
                            )
                        }
                        y = top + h
                    } else {
                        // A row taller than one printable page: band it over ROW
                        // space so the ruling stays aligned across columns —
                        // every page draws one band of all cells. The old code
                        // drew the whole row once below the page bottom and lost
                        // the tail of the giant cell.
                        val bands = rowBands(
                            contentH, y, PdfPaperWriter.MARGIN.toFloat(), BOTTOM, pad
                        )
                        for ((bandNo, band) in bands.withIndex()) {
                            if (bandNo > 0) {
                                advance()
                                edges = computeEdges()
                            }
                            val rowTop = band.start
                            val bandH = band.endInclusive - rowTop
                            for (c in 0 until cols) {
                                page.canvas.drawRect(
                                    edges[c], y, edges[c + 1],
                                    y + pad + bandH + pad, linePaint
                                )
                                drawCellBlocks(
                                    page.canvas, contents[c], edges[c] + pad,
                                    y + pad - rowTop, y + pad, y + pad + bandH,
                                    edges[c + 1] - 1f
                                )
                            }
                            y += pad + bandH + pad
                        }
                    }
                } finally {
                    // A picture that straddles a band boundary is drawn in both
                    // bands, so it may only be recycled once the whole row is
                    // finished.
                    contents.forEach { releaseCellBlocks(it) }
                }
            }
            y += 6f
        }

        /**
         * Draws one cell's blocks from content-space top [top], clipped to the
         * current band and to one pixel inside the cell's outer border. The
         * right clip is the backstop for anything that cannot wrap — a
         * `ReplacementSpan` (formula) is fitted first, but an over-wide
         * unbreakable word is clipped here rather than painted over the
         * border/neighbour or off the page. Clipping inside the border, not at
         * the content edge, leaves the cell padding available to a glyph's
         * overhang and antialias fringe.
         *
         * A block entirely outside the band is skipped rather than drawn into
         * a clip that would discard it (the banding path calls this for every
         * band); a partially visible block is drawn whole and sliced by the
         * canvas clip so no content is lost at a boundary.
         */
        private fun drawCellBlocks(
            canvas: Canvas,
            blocks: List<CellBlock>,
            x: Float,
            top: Float,
            clipTop: Float,
            clipBottom: Float,
            right: Float
        ) {
            val contentTop = clipTop - top
            val contentBottom = clipBottom - top
            canvas.withSave {
                // The left edge stays at the page so a glyph's left overhang is
                // not cut; the right edge is one pixel inside the cell border,
                // the only place that could overlap a neighbour or the page.
                clipRect(0f, clipTop, right, clipBottom)
                var cy = top
                for (block in blocks) {
                    val offset = cy - top
                    when (block) {
                        is CellBlock.Text -> if (
                            visibleLineRange(block.layout, offset, contentTop, contentBottom) != null
                        ) {
                            withTranslation(x, cy) { block.layout.draw(this) }
                        }
                        is CellBlock.Picture -> if (
                            intersectsBand(offset, block.height, contentTop, contentBottom)
                        ) {
                            drawBitmap(block.bitmap, x, cy, null)
                        }
                    }
                    cy += block.height + CELL_BLOCK_GAP
                }
            }
        }
    }
}

/** Vertical gap between two blocks of one cell (text vs picture). */
private const val CELL_BLOCK_GAP = 2f

/**
 * Horizontal padding inside every table cell. Shared with the picture height
 * budget so a maximum-height picture plus the row's padding fits one band
 * instead of being sliced by one padding's worth.
 */
internal const val TABLE_CELL_PAD = 4f

/**
 * Width reserved in the column measurement for one drawable picture. The
 * picture itself is scaled to the final column anyway; the nominal width only
 * stops an image cell from being squeezed to the `[image]` marker's two
 * characters.
 */
internal const val TABLE_CELL_IMAGE_NOMINAL_PX = 120f

/**
 * The shortest a cell picture is shrunk to when the cell's own prose/formulas
 * leave no room. If even this cannot fit, the row bands (the documented slice)
 * rather than losing the rest of the cell to an invisible sliver.
 */
internal const val MIN_CELL_PICTURE_HEIGHT = 48f

/**
 * One drawable piece of a rich table cell. A cell's prose and formulas flow in
 * one [Text] layout (so an equation stays inside its sentence), while pictures
 * — a portable cell image or a captured chemistry PNG — are decoded, scaled to
 * the column, and placed in document order.
 */
internal sealed interface CellBlock {
    val height: Float

    class Text(internal val layout: StaticLayout) : CellBlock {
        override val height: Float get() = layout.height.toFloat()
    }

    class Picture(internal val bitmap: Bitmap) : CellBlock {
        override val height: Float get() = bitmap.height.toFloat()
        internal val width: Float get() = bitmap.width.toFloat()
    }
}

/** Height of one cell's blocks, including the gaps between them. */
internal fun cellBlocksHeight(blocks: List<CellBlock>): Float {
    if (blocks.isEmpty()) return 0f
    var height = 0f
    for (block in blocks) height += block.height
    return height + CELL_BLOCK_GAP * (blocks.size - 1)
}

/** Releases the decoded pictures of a finished row (banding re-draws them). */
internal fun releaseCellBlocks(blocks: List<CellBlock>) {
    for (block in blocks) {
        if (block is CellBlock.Picture && !block.bitmap.isRecycled) block.bitmap.recycle()
    }
}

/** One ordered piece of a cell, before layout: prose, formula, picture or structure. */
private sealed interface CellPiece {
    /**
     * A run of prose. [openAtStart] and [openAtEnd] are the inline style tags
     * that cross the run's boundaries, so a `<b>` opened before an image or a
     * formula still covers the text on both sides of it.
     */
    class Html(
        val html: String,
        val openAtStart: List<String> = emptyList(),
        val openAtEnd: List<String> = emptyList()
    ) : CellPiece
    class Math(val mathml: String) : CellPiece
    class Image(val src: String?) : CellPiece
    class Chem(val element: ContentElement.ChemElement) : CellPiece
}

/**
 * Splits a cell into its ordered pieces with the three grammars the rest of the
 * app already shares: chemistry markers first ([cellSegments], so a marker
 * inside MathML or a tag stays text), then formulas ([tableCellParts]), then
 * pictures ([forEachCellImage]). A truncated `<img` is an [CellPiece.Image]
 * with a null source, never an unterminated tag swallowing the rest.
 *
 * Inline style tags are tracked across every boundary. The old splitter handed
 * each chunk to `spannedFromInlineHtml` alone, which only applies a span when
 * it sees the closing tag, so `<b>see <img> here</b>` printed both sides
 * unbolded. Each [CellPiece.Html] now records the tags open at its start and
 * end, and [appendCellHtml] re-opens/closes them around the chunk.
 *
 * [withPng] false skips validating a stored chemistry PNG: the text-only
 * surfaces only need the handles, and decoding base64 to print them would be
 * waste on every answer key.
 */
private fun cellPieces(cell: String, withPng: Boolean): List<CellPiece> {
    val out = mutableListOf<CellPiece>()
    val open = mutableListOf<String>()

    fun addHtml(html: String) {
        if (html.isEmpty()) return
        val start = open.toList()
        updateOpenTags(html, open)
        out += CellPiece.Html(html, start, open.toList())
    }

    for (segment in cellSegments(cell, withPng)) {
        when (segment) {
            is CellSegment.Chem -> {
                val element = segment.element
                if (element.diagram.isEmpty()) {
                    // Handles are prose for layout purposes: emit them as an
                    // Html chunk so an enclosing <b>/<i> wrapped around a
                    // preceding picture still styles them.
                    addHtml(escapeHtmlText(chemElementText(element)))
                } else {
                    out += CellPiece.Chem(element)
                }
            }
            is CellSegment.Text -> for (part in tableCellParts(segment.html)) {
                when (part) {
                    is CellPart.Math -> out += CellPiece.Math(part.mathml)
                    is CellPart.Text -> {
                        var pos = 0
                        forEachCellImage(part.html) { src, start, end ->
                            if (start > pos) addHtml(part.html.substring(pos, start))
                            out += CellPiece.Image(src)
                            pos = end
                        }
                        if (pos < part.html.length) addHtml(part.html.substring(pos))
                    }
                }
            }
        }
    }
    return out
}

/**
 * Advances [open] over the inline style tags in [html] with the same
 * pop-until-found rule [spannedFromInlineHtml] uses, so the two agree on a
 * mis-nested close. Block breaks and unknown tags carry no style and are
 * ignored here; they are layout or literal text, not span state.
 */
private fun updateOpenTags(html: String, open: MutableList<String>) {
    for (match in tagLikePattern.findAll(html)) {
        val closing = match.groupValues[1] == "/"
        val tag = match.groupValues[2].lowercase()
        if (styleSpans(tag).isEmpty()) continue
        if (closing) {
            val idx = open.lastIndexOf(tag)
            if (idx >= 0) {
                while (open.size > idx) open.removeAt(open.lastIndex)
            }
        } else {
            open += tag
        }
    }
}

/**
 * Appends one prose chunk with the style tags that cross its boundaries
 * re-opened before it and closed after it. The synthetic tags add no text (the
 * text pipeline ignores an unclosed open tag until a close arrives), so the
 * chunk keeps its exact text while its spans reach both ends of the picture or
 * formula that split it.
 */
private fun appendCellHtml(out: SpannableStringBuilder, piece: CellPiece.Html) {
    if (piece.openAtStart.isEmpty() && piece.openAtEnd.isEmpty()) {
        out.append(spannedFromInlineHtml(piece.html))
        return
    }
    val wrapped = StringBuilder(piece.html.length + 16)
    for (tag in piece.openAtStart) wrapped.append('<').append(tag).append('>')
    wrapped.append(piece.html)
    for (tag in piece.openAtEnd.asReversed()) wrapped.append("</").append(tag).append('>')
    out.append(spannedFromInlineHtml(wrapped.toString()))
}

/**
 * Builds the drawable blocks of one cell at [width] px. Prose and formulas
 * accumulate into one spanned run; a portable picture, or a chemistry PNG,
 * breaks the run and is decoded and scaled to fit the cell. Anything that
 * cannot be drawn keeps readable text instead of a payload:
 *
 * - a non-portable or hostile source (`http:`, `file:`, SVG, an extra
 *   attribute, a malformed tag) becomes [CELL_IMAGE_MARKER];
 * - a portable source that still fails to decode becomes
 *   [unreadableImageLabel] — never its literal data URI;
 * - a structure without a valid PNG becomes its handles text.
 *
 * The decoded bitmaps are owned by the returned blocks: call
 * [releaseCellBlocks] once the row is fully drawn.
 *
 * Accepted limitations, recorded so they are not mistaken for bugs:
 * - A picture-only cell prints no caption or alt text: PDF has no alt text,
 *   and unlike the body path (handles then picture) a cell draws only the
 *   image, so a cell picture carries no spoken copy.
 * - The same data URI in N cells is decoded and embedded N times; there is no
 *   cross-cell cache, so a repeated picture grows the file by N copies.
 * - A chemistry PNG scales down with its column, so atom labels can end up
 *   smaller than the body text.
 * - A picture shares the printable page with the cell's prose/formulas: it is
 *   capped to what they leave (down to [MIN_CELL_PICTURE_HEIGHT]). If even that
 *   cannot fit, the row bands and the picture is sliced at the band edge; no
 *   other content is dropped.
 */
internal fun buildCellBlocks(cell: String, width: Float, paint: TextPaint): List<CellBlock> {
    val pending = mutableListOf<PendingBlock>()
    val run = SpannableStringBuilder()
    fun flush() {
        if (run.isEmpty()) return
        val text = SpannableStringBuilder(run)
        run.clear()
        run.clearSpans()
        pending += PendingBlock.Text(CellBlock.Text(cellLayout(text, width, paint)))
    }
    // cellSegments(withPng = true) already validated the diagram, so the
    // element's diagram is non-empty only when it is a drawable PNG.
    for (piece in cellPieces(cell, withPng = true)) {
        when (piece) {
            is CellPiece.Html -> appendCellHtml(run, piece)
            is CellPiece.Math -> appendCellMath(run, piece.mathml, paint, width)
            is CellPiece.Image -> {
                val src = piece.src
                if (src == null || !portableCellImageSrc(src)) {
                    run.append(CELL_IMAGE_MARKER)
                } else {
                    flush()
                    pending += PendingBlock.Picture(src)
                }
            }
            is CellPiece.Chem -> {
                val diagram = piece.element.diagram
                if (diagram.isEmpty()) {
                    run.append(chemElementText(piece.element))
                } else {
                    flush()
                    pending += PendingBlock.Picture(diagram)
                }
            }
        }
    }
    flush()
    return layoutPendingBlocks(pending, width, paint)
}

/** A laid-out text block, or a picture still waiting for its page-height share. */
private sealed interface PendingBlock {
    class Text(val block: CellBlock.Text) : PendingBlock
    class Picture(val src: String) : PendingBlock
}

/**
 * Decodes the cell's pictures into the height the text/formula/handles blocks
 * leave inside one printable band, and returns the blocks in document order.
 *
 * Before this pass each picture was capped only to the page, so a cell holding
 * prose and a maximum-height picture still made the row taller than a band and
 * its bottom sliver was orphaned into the next one. The other blocks are
 * measured first; pictures then share `printable - otherHeight - gaps`, split
 * evenly between them, never below [MIN_CELL_PICTURE_HEIGHT]. When even that
 * floor cannot fit, the cap is the floor and the row bands (documented slice).
 */
private fun layoutPendingBlocks(
    pending: List<PendingBlock>,
    width: Float,
    paint: TextPaint
): List<CellBlock> {
    if (pending.none { it is PendingBlock.Picture }) {
        return pending.map { (it as PendingBlock.Text).block }
    }
    val printable = PdfPaperWriter.BOTTOM - PdfPaperWriter.MARGIN - 2 * TABLE_CELL_PAD
    var textHeight = 0f
    for (block in pending) {
        if (block is PendingBlock.Text) textHeight += block.block.height
    }
    val gaps = CELL_BLOCK_GAP * (pending.size - 1).coerceAtLeast(0)
    var remaining = printable - textHeight - gaps
    var picturesLeft = pending.count { it is PendingBlock.Picture }
    return pending.map { block ->
        when (block) {
            is PendingBlock.Text -> block.block
            is PendingBlock.Picture -> {
                val share = if (picturesLeft > 0) remaining / picturesLeft else remaining
                val budget = maxOf(share, MIN_CELL_PICTURE_HEIGHT)
                val picture = decodedCellPicture(block.src, width, minOf(printable, budget))
                picturesLeft--
                if (picture == null) {
                    // Passed the portable-shape check but cannot be decoded: a
                    // readable label, never the data URI.
                    CellBlock.Text(
                        cellLayout(
                            SpannableStringBuilder(unreadableImageLabel(block.src)),
                            width, paint
                        )
                    )
                } else {
                    remaining -= picture.height
                    CellBlock.Picture(picture)
                }
            }
        }
    }
}

/**
 * The natural width of one cell: the widest spanned line (formulas measured
 * through their [MathSpan]) or the nominal picture width, whichever is wider.
 * This replaces `measureText(sanitizeTableCell(cell))`, which flattened a
 * formula to `[formula]` and a picture to `[image]` and so collapsed their
 * columns.
 *
 * The grammar is walked twice per cell (measure, then build): MathML parses
 * twice and a chemistry PNG is signature-checked twice. That is deliberately
 * cheap next to decoding a bitmap; caching the pieces across the two passes
 * would have to outlive the per-row release/recycle contract.
 */
internal fun cellNaturalWidth(cell: String, paint: TextPaint): Float {
    var widest = 0f
    val run = SpannableStringBuilder()
    fun flush() {
        if (run.isEmpty()) return
        widest = maxOf(widest, Layout.getDesiredWidth(run, paint))
        run.clear()
        run.clearSpans()
    }
    for (piece in cellPieces(cell, withPng = true)) {
        when (piece) {
            is CellPiece.Html -> appendCellHtml(run, piece)
            is CellPiece.Math -> appendCellMath(run, piece.mathml, paint)
            is CellPiece.Image -> {
                val src = piece.src
                if (src != null && portableCellImageSrc(src)) {
                    flush()
                    widest = maxOf(widest, TABLE_CELL_IMAGE_NOMINAL_PX)
                } else {
                    run.append(CELL_IMAGE_MARKER)
                }
            }
            is CellPiece.Chem -> {
                if (piece.element.diagram.isNotEmpty()) {
                    flush()
                    widest = maxOf(widest, TABLE_CELL_IMAGE_NOMINAL_PX)
                } else {
                    run.append(chemElementText(piece.element))
                }
            }
        }
    }
    flush()
    return widest
}

/**
 * Appends one cell formula to [out]: a real [MathSpan] when MathML parses, the
 * same linear/`[formula]` fallback the body uses otherwise. A picture inside
 * the formula becomes `<mtext>[image]</mtext>` BEFORE parsing, because the
 * MathML parser would otherwise see a foreign tag and fall back to text that
 * prints the whole base64 URI.
 *
 * [maxWidth] is the cell's usable width. A `ReplacementSpan` cannot wrap, and
 * column scaling can make a formula measured at the base size wider than the
 * column it lands in, so the span is built at a size that fits [maxWidth].
 * [Float.MAX_VALUE] keeps the natural size for the measurement pass.
 */
private fun appendCellMath(
    out: SpannableStringBuilder,
    mathml: String,
    paint: TextPaint,
    maxWidth: Float = Float.MAX_VALUE
) {
    val marked = mapCellImages(mathml) { "<mtext>$CELL_IMAGE_MARKER</mtext>" }
    val nodes = mathMlToNodes(marked)
    if (nodes != null) {
        val size = fittedMathSize(nodes, paint, maxWidth)
        val start = out.length
        out.append('\uFFFC')
        out.setSpan(
            MathSpan(nodes, size),
            start, out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    } else {
        val linear = mathToLinear(mathml)
        out.append(if (linear.isNotBlank()) "[formula: $linear]" else CELL_FORMULA_MARKER)
    }
}

/**
 * The formula's type size, shrunk until its measured ink fits [maxWidth].
 * Glyph metrics are not perfectly linear in the type size (hinting and
 * rounding), so the proportional guess is re-measured and corrected; the
 * baseline needs no other adjustment because [MathSpan] derives its
 * ascent/descent from the same measurement at the final size.
 *
 * A minimum size keeps a pathologically wide formula legible instead of
 * collapsing to nothing; if it still cannot fit, the cell's right clip is the
 * backstop that stops it painting over the neighbour.
 */
private fun fittedMathSize(nodes: List<TexNode>, paint: TextPaint, maxWidth: Float): Float {
    val base = paint.textSize
    if (maxWidth == Float.MAX_VALUE || maxWidth <= 0f) return base
    val probe = TextPaint(paint)
    var size = base
    repeat(3) {
        probe.textSize = size
        val measured = measureNodes(nodes, probe).width
        if (measured <= maxWidth || measured <= 0f) return size
        size = maxOf(MIN_FORMULA_SIZE, size * (maxWidth / measured))
    }
    return size
}

/** The smallest type size a fitted cell formula is allowed to reach. */
private const val MIN_FORMULA_SIZE = 4f

/** One cell flattened to readable single-line text (answer key), never base64. */
internal fun cellFlatText(cell: String): String {
    val sb = StringBuilder(cell.length)
    for (piece in cellPieces(cell, withPng = false)) {
        when (piece) {
            is CellPiece.Html -> sb.append(piece.html)
            // Mark a picture inside the formula first, like the cell surface:
            // the lineariser must not carry the data URI or skip the marker.
            is CellPiece.Math -> sb.append(
                mathToLinear(mapCellImages(piece.mathml) { CELL_IMAGE_MARKER })
            )
            is CellPiece.Image -> sb.append(CELL_IMAGE_MARKER)
            is CellPiece.Chem -> sb.append(chemElementText(piece.element))
        }
    }
    return sb.toString()
}

/** A cell layout for [spanned] at [width] px, with the writer's line spacing. */
private fun cellLayout(spanned: Spanned, width: Float, paint: TextPaint): StaticLayout =
    StaticLayout.Builder.obtain(
        spanned, 0, spanned.length, paint,
        width.toInt().coerceAtLeast(1)
    )
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(2f, 1f)
        .setIncludePad(true)
        .build()

/**
 * Decodes [src] subsampled, then scales it down to fit [maxWidth] ×
 * [maxHeight], keeping its aspect ratio. Never upscales: a small picture keeps
 * its own size rather than turning into a blurry column-wide block. The
 * original bitmap is recycled when a scaled copy replaces it.
 */
private fun decodedCellPicture(src: String, maxWidth: Float, maxHeight: Float): Bitmap? {
    val embedded = parseDataUri(src) ?: return null
    val decoded = com.mcqapp.util.decodeBounded(
        embedded.bytes,
        com.mcqapp.util.MAX_EXPORT_DIMENSION
    ) ?: return null
    val widthLimit = maxWidth.coerceAtLeast(1f)
    val heightLimit = maxHeight.coerceAtLeast(1f)
    val scale = minOf(1f, minOf(widthLimit / decoded.width, heightLimit / decoded.height))
    if (scale >= 1f) return decoded
    val scaled = try {
        decoded.scale(
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )
    } catch (e: Throwable) {
        // A hostile payload raises OutOfMemoryError, an Error, not an Exception.
        null
    }
    if (scaled == null) {
        decoded.recycle()
        return null
    }
    if (scaled !== decoded) decoded.recycle()
    return scaled
}

/**
 * The content-space vertical bands of one table row. The first band gets the
 * space left on the current page/column ([firstStartY] to [bottom]); every
 * later band starts at [pageStartY] on a fresh page/column. Extracted so the
 * pagination arithmetic is the writer's own when a test asserts that each band
 * shows its own lines and none is lost.
 */
internal fun rowBands(
    contentH: Float,
    firstStartY: Float,
    pageStartY: Float,
    bottom: Float,
    pad: Float
): List<ClosedFloatingPointRange<Float>> {
    val bands = mutableListOf<ClosedFloatingPointRange<Float>>()
    var y = firstStartY
    var rowTop = 0f
    while (true) {
        val bandH = minOf(bottom - y - 2 * pad, contentH - rowTop).coerceAtLeast(1f)
        bands += rowTop..(rowTop + bandH)
        y += 2 * pad + bandH
        rowTop += bandH
        if (rowTop >= contentH) break
        check(bands.size < 100_000) { "oversized row banding did not advance" }
        y = pageStartY
    }
    return bands
}

/**
 * The layout lines visible in a content-space band [clipTop]..[clipBottom] for
 * a block starting at [blockTop], or null when the block is entirely outside
 * the band. A text block is measured rather than assumed: a line whose glyphs
 * have no vertical overlap with the band is not "drawn" as content.
 */
internal fun visibleLineRange(
    layout: StaticLayout,
    blockTop: Float,
    clipTop: Float,
    clipBottom: Float
): IntRange? {
    val top = (clipTop - blockTop).coerceAtLeast(0f)
    val bottom = (clipBottom - blockTop).coerceAtMost(layout.height.toFloat())
    if (bottom <= top) return null
    val first = layout.getLineForVertical(top.toInt())
    val last = layout.getLineForVertical((bottom.toInt() - 1).coerceAtLeast(top.toInt()))
    return first..last
}

/** True when a block at [blockTop] with [height] overlaps the band. */
private fun intersectsBand(
    blockTop: Float,
    height: Float,
    clipTop: Float,
    clipBottom: Float
): Boolean = blockTop < clipBottom && blockTop + height > clipTop

/**
 * Column widths after fitting [natural] widths into [avail]: scale every column
 * by the same factor, or keep natural widths when they already fit. Shared with
 * the tests so the formula-fit assertion uses the writer's own arithmetic.
 */
internal fun fitColumns(natural: FloatArray, avail: Float): FloatArray {
    val total = natural.sum()
    val scale = if (total > avail && total > 0) avail / total else 1f
    return FloatArray(natural.size) { natural[it] * scale }
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
                    // Pop-until-found, mirroring InlineHtml.parse: a
                    // mis-nested close (`<u>a <b>b</u> c</b>`) used to be
                    // dropped when it did not match the stack top, so the
                    // underline vanished and the bold leaked over the rest
                    // (consolidated_bugs.md G5).
                    val idx = open.indexOfLast { it.first == tag }
                    if (idx >= 0) {
                        while (open.size > idx) {
                            val (openTag, start) = open.removeLast()
                            for (span in styleSpans(openTag)) {
                                out.setSpan(span, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
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
 * One answer-key line — `Q7. B` for a question, `Q7. Paris` for a flashcard —
 * or null when the question has nothing to list: no answer of its own, or a
 * card with no back at all. A question whose answer is empty but which has an
 * explanation prints the old `Q7. —` marker.
 *
 * Explanation counts whether it is rich content, the legacy text field or an
 * image: they are the same thing to a reader, and the inline path already
 * prints all three. For a card the back *is* the answer, so the line carries
 * its text instead of the `—` placeholder that used to stand where a card's
 * answer should be. A back that only carries a picture or a table flattens to
 * its readable part; a picture-only back keeps the explicit `(card)` marker
 * and the picture itself prints under the line.
 *
 * Pure so the entry rules unit-test without Android.
 */
internal fun answerKeyEntry(question: QuestionDto, number: Int): String? {
    // A zero-option question is a flashcard: its back is the answer.
    if (question.options.isEmpty()) {
        val back = cardBackText(question)
        val hasBack = question.explanationElements.isNotEmpty() ||
            question.explanation.isNotBlank() ||
            question.explanationImage != null
        if (!hasBack) return null
        return "Q$number. " + back.ifEmpty { "(card)" }
    }
    val answer = answerLabels(question.options, question.correctOptionIds).joinToString(", ")
    val hasExplanation = question.explanationElements.isNotEmpty() ||
        question.explanation.isNotBlank() ||
        question.explanationImage != null
    if (answer.isEmpty() && !hasExplanation) return null
    return "Q$number. " + if (answer.isEmpty()) "—" else answer
}

/**
 * A flashcard's back as one line of plain text, for [answerKeyEntry].
 *
 * Pictures cannot ride a line, so they are skipped here (the key prints them
 * under the entry); formulas become their linear text and table cells join
 * with a space, the same flattening the PDF reader already sees on the page.
 * Inline markup is stripped and entities decoded, because the line is drawn
 * as plain text.
 */
private fun cardBackText(question: QuestionDto): String {
    if (question.explanationElements.isNotEmpty()) {
        val text = question.explanationElements.joinToString(" ") { element ->
            when (element) {
                is ContentElement.TextElement -> element.text
                is ContentElement.MathElement -> mathToLinear(element.mathml)
                is ContentElement.TableElement ->
                    element.rows.joinToString(" ") { row ->
                        row.joinToString(" ") { cellFlatText(it) }
                    }
                is ContentElement.ImageElement -> ""
                is ContentElement.ChemElement -> chemElementText(element)
            }
        }
        val plain = decodeEntities(stripInlineHtml(text))
            .replace(Regex("\\s+"), " ")
            .trim()
        if (plain.isNotEmpty()) return plain
    }
    return decodeEntities(stripInlineHtml(question.explanation))
        .replace(Regex("\\s+"), " ")
        .trim()
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
