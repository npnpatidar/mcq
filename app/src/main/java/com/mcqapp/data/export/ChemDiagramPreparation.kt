package com.mcqapp.data.export

import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CELL_CHEM_CLASS
import com.mcqapp.util.cellChemSpans
import com.mcqapp.util.chemCellHtml
import kotlinx.coroutines.CancellationException

/**
 * Fills a shallow **copy** of every structure that has no usable diagram with
 * a freshly rendered PNG, so the existing writers draw a picture instead of
 * falling back to the readable handles. Stored data is never touched: the
 * wrapper decorates the stream's walk (and maps its passages and loose
 * questions) and returns the original element unchanged whenever nothing could
 * be rendered.
 *
 * The pass is deliberately format-agnostic. Only [PaperExporter] decides
 * whether to use it; JSON_INLINE never does, because that format must stay a
 * byte-faithful copy of what is stored.
 */
internal object ChemDiagramPreparation {

    /**
     * Total time one export may spend drawing structures before the rest keep
     * their handles. The per-structure cap lives in the renderer; this bounds
     * a paper with hundreds of unique structures so an export cannot sit for
     * minutes behind the offscreen WebView.
     */
    internal const val MAX_RENDER_MILLIS_PER_EXPORT = 90_000L

    /**
     * How many rendered structures one export remembers. Repeats are common
     * (an option, the explanation and a later question may share a SMILES),
     * but a bank is not allowed to hold every PNG for the whole export.
     */
    internal const val MAX_CACHED_STRUCTURES = 128

    /**
     * Returns [stream] with drawings filled in. The walk is decorated lazily,
     * so questions are only examined as the writer consumes them; passages and
     * loose questions are small and are prepared up front.
     */
    suspend fun enrichPaper(
        stream: PaperStream,
        renderer: ChemDiagramRenderer,
        budgetMillis: Long = MAX_RENDER_MILLIS_PER_EXPORT
    ): PaperStream {
        val session = Session(renderer, budgetMillis)
        val passages = stream.passages.map { passage ->
            val elements = session.enrichElements(passage.elements)
            if (elements == passage.elements) passage else passage.copy(elements = elements)
        }
        val looseQuestions = stream.looseQuestions.map { session.enrichQuestion(it) }
        return EnrichedPaperStream(stream, session, passages, looseQuestions)
    }

    /** One export's renderer calls: per-structure cache, time budget, fail closed. */
    private class Session(
        private val renderer: ChemDiagramRenderer,
        private val budgetMillis: Long
    ) {
        private val startedAt = System.nanoTime()
        private val cache = object : LinkedHashMap<String, String?>(MAX_CACHED_STRUCTURES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String?>?
            ): Boolean = size > MAX_CACHED_STRUCTURES
        }

        suspend fun render(smiles: String, textAbove: String): String? {
            // Trim exactly as the renderer does, so the session cache and the
            // renderer cache agree on what one structure is.
            val source = smiles.trim()
            if (!chemSmilesIsRenderable(source)) return null
            val key = chemDrawingKey(source, textAbove)
            synchronized(cache) {
                if (cache.containsKey(key)) return cache[key]
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            val png = if (elapsedMs >= budgetMillis) {
                null
            } else {
                try {
                    renderer.render(source, textAbove)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A renderer failure is never an export failure.
                    null
                }
            }
            // Only a strict, writer-safe PNG may replace the handles fallback.
            val valid = png?.takeIf { isPngDataUri(it) }
            synchronized(cache) { cache[key] = valid }
            return valid
        }

        suspend fun enrichQuestion(question: QuestionDto): QuestionDto {
            val elements = enrichElements(question.elements)
            val options = enrichOptions(question.options)
            val explanation = enrichElements(question.explanationElements)
            if (elements === question.elements &&
                options === question.options &&
                explanation === question.explanationElements
            ) {
                return question
            }
            return question.copy(
                elements = elements,
                options = options,
                explanationElements = explanation
            )
        }

        private suspend fun enrichOptions(options: List<OptionDto>): List<OptionDto> {
            var changed = false
            val out = ArrayList<OptionDto>(options.size)
            for (option in options) {
                val elements = enrichElements(option.elements)
                if (elements == option.elements) {
                    out += option
                } else {
                    changed = true
                    out += option.copy(elements = elements)
                }
            }
            return if (changed) out else options
        }

        suspend fun enrichElements(elements: List<ContentElement>): List<ContentElement> {
            var changed = false
            val out = ArrayList<ContentElement>(elements.size)
            for (element in elements) {
                val enriched = enrichElement(element)
                if (enriched == element) {
                    out += element
                } else {
                    changed = true
                    out += enriched
                }
            }
            return if (changed) out else elements
        }

        private suspend fun enrichElement(element: ContentElement): ContentElement =
            when (element) {
                is ContentElement.ChemElement -> enrichChem(element)
                is ContentElement.TableElement -> {
                    val rows = element.rows.map { row -> row.map { enrichCell(it) } }
                    if (rows == element.rows) element else element.copy(rows = rows)
                }
                else -> element
            }

        private suspend fun enrichChem(element: ContentElement.ChemElement): ContentElement {
            if (isPngDataUri(element.diagram)) return element
            val png = render(element.smiles, element.caption) ?: return element
            return element.copy(diagram = png)
        }

        /**
         * Rebuilds only the structures that lack a raster. Everything outside a
         * rebuilt marker — prose, formulas, the other cells and the surrounding
         * formatting — is copied from the original cell byte for byte, and a
         * marker whose `data-png` is already a valid PNG is left exactly as
         * stored.
         */
        private suspend fun enrichCell(cell: String): String {
            if (!cell.contains(CELL_CHEM_CLASS)) return cell
            val spans = cellChemSpans(cell, withPng = true)
            if (spans.isEmpty()) return cell
            var changed = false
            val out = StringBuilder(cell.length + 128)
            var copied = 0
            for (span in spans) {
                if (copied < span.start) out.append(cell, copied, span.start)
                val element = span.element
                if (isPngDataUri(element.diagram)) {
                    out.append(cell, span.start, span.end)
                } else {
                    val png = render(element.smiles, element.caption)
                    if (png == null) {
                        out.append(cell, span.start, span.end)
                    } else {
                        out.append(chemCellHtml(element.copy(diagram = png), includePng = true))
                        changed = true
                    }
                }
                copied = span.end
            }
            if (copied < cell.length) out.append(cell, copied, cell.length)
            return if (changed) out.toString() else cell
        }
    }

    /**
     * A [PaperStream] whose questions are dressed as the writer walks them.
     * Every field the writer may read before the walk is delegated or mapped
     * eagerly, so the wrapper is indistinguishable from [delegate] apart from
     * the added diagrams.
     *
     * PDF and answers-at-end walk twice. The second walk reuses [Session]'s
     * cache, so a structure still in the session's bounded cache is not
     * rendered again; one evicted between the walks is, within the same export
     * budget. That is deliberate: re-rendering costs time, never correctness.
     */
    private class EnrichedPaperStream(
        private val delegate: PaperStream,
        private val session: Session,
        override val passages: List<PassageDto>,
        override val looseQuestions: List<QuestionDto>
    ) : PaperStream {

        override val id: String get() = delegate.id
        override val title: String get() = delegate.title
        override val description: String get() = delegate.description
        override val durationMinutes: Int get() = delegate.durationMinutes
        override val negativeMarking: Double get() = delegate.negativeMarking
        override val totalQuestions: Int get() = delegate.totalQuestions
        override val categories: List<CategoryDto> get() = delegate.categories

        override suspend fun walk(
            onCategory: suspend (CategoryDto) -> Unit,
            onQuestion: suspend (QuestionDto) -> Unit
        ) {
            delegate.walk(onCategory) { question -> onQuestion(session.enrichQuestion(question)) }
        }
    }
}

/**
 * The stream a writer receives: untouched for JSON (a backup must stay a
 * byte-faithful copy of storage) and for any export when no renderer is
 * installed.
 */
internal suspend fun preparePaperForExport(
    stream: PaperStream,
    format: ExportFormat,
    renderer: ChemDiagramRenderer? = ChemDiagramRendererProvider.current()
): PaperStream {
    if (format == ExportFormat.JSON_INLINE || renderer == null) return stream
    return ChemDiagramPreparation.enrichPaper(stream, renderer)
}
