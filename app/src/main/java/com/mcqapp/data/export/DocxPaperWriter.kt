package com.mcqapp.data.export

import com.mcqapp.data.docx.decodeXmlEntities
import com.mcqapp.data.docx.docxLineStartMarker
import com.mcqapp.data.docx.escapeXmlText
import com.mcqapp.data.docx.mathMlLinearText
import com.mcqapp.data.docx.mathMlToOmml
import com.mcqapp.data.docx.PAYLOAD_PROPERTY
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.Logger
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Renders a whole paper as a `.docx` (Office Open XML) the app's own DOCX
 * importer reads back: the paper title is a Heading 1, each category a
 * heading one level deeper than its parent (recursive, so subcategories of
 * subcategories survive), and questions follow the `N.) … (a) … Ans. …
 * Exp:` marker grammar with `Passage:` blocks.
 *
 * The visible document is the whole format: maths travels as native OMML
 * equations, pictures as `word/media` entries, tables as Word tables, and
 * the inline tags (`strong`, `em`, `u`, `del`, `sub`, `sup`, `mark`) as run
 * formatting. Marks, difficulty and tags have no marker on the import side,
 * so they are deliberately not written — this format is for reading and
 * light editing, not for lossless backup (the JSON and backup-zip exports
 * carry those).
 *
 * Hand-rolled OOXML, matching the hand-rolled reader: `java.util.zip` plus
 * XML strings, no library.
 */
object DocxPaperWriter {

    /**
     * Renders the document straight to [out]. The body is streamed into
     * `word/document.xml` as the paper is walked, the hidden payload is
     * streamed into `docProps/custom.xml` by a second walk, and the finished
     * package never exists as a second copy of either. The pictures the paper
     * embeds are the one thing held back — they are `word/media` entries, so
     * they have to be named and written before the body that draws them — and
     * they are spooled to temp files rather than kept in memory ([MediaPool]).
     */
    suspend fun writeDocx(stream: PaperStream, out: OutputStream) {
        val warnings = mutableListOf<String>()
        val media = MediaPool()
        try {
            val bySection = flattenCategories(stream.categories).associateBy { it.dto.id }

            ZipOutputStream(out).use { zip ->
                // [Content_Types].xml comes first: the OPC spec asks for it, and
                // strict readers look for it there.
                writeEntry(zip, "[Content_Types].xml", contentTypesXml())
                writeEntry(zip, "_rels/.rels", rootRelsXml())

                zip.putNextEntry(ZipEntry("word/document.xml"))
                val body = OutputStreamWriter(zip, Charsets.UTF_8)
                body.append(documentHead())
                writeBody(body, stream, bySection, media, warnings)
                body.append(documentTail())
                // Flush, never close: closing would take the zip with it.
                body.flush()
                zip.closeEntry()

                writeEntry(zip, "word/styles.xml", stylesXml())
                // After the body: it lists the media the body registered.
                writeEntry(zip, "word/_rels/document.xml.rels", documentRelsXml(media))

                // The hidden payload: the full paper DTO as a custom document
                // property. It is invisible in Word (File > Info > Advanced
                // Properties aside) and survives Word's own saves, so a file the
                // app wrote and nobody edited re-imports with ids, marks,
                // difficulty and tags intact instead of duplicating everything the
                // visible markers cannot carry. The parser falls back to the
                // markers when the payload no longer matches the visible text,
                // and grafts the image srcs back from the word/media entries the
                // payload deliberately does not duplicate. It is streamed with the
                // same writer the JSON export uses, with the images blanked.
                zip.putNextEntry(ZipEntry("docProps/custom.xml"))
                val props = OutputStreamWriter(zip, Charsets.UTF_8)
                props.append(customPropsHead())
                PaperJsonWriter.writePaperJson(
                    XmlEscapingAppendable(props),
                    stream,
                    json = payloadJsonFormat,
                    question = { stripPayloadImages(it) },
                    passage = { stripPayloadImages(it) }
                )
                props.append(customPropsTail())
                props.flush()
                zip.closeEntry()

                for (target in media.targets) {
                    zip.putNextEntry(ZipEntry("word/$target"))
                    media.copyTo(target, zip)
                    zip.closeEntry()
                }
            }
        } finally {
            media.close()
        }
        for (warning in warnings) Logger.w("DOCX", warning)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, xml: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(xml.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /**
     * The document flow, one walk: each section heading, then its questions,
     * with the passages printed once above their groups. Question numbering
     * runs across the whole document, so it stays a document of one paper.
     */
    private suspend fun writeBody(
        out: Appendable,
        stream: PaperStream,
        bySection: Map<String, CatNode>,
        media: MediaPool,
        warnings: MutableList<String>
    ) {
        val passagesById = stream.passages.associateBy { it.id }
        var number = 0
        var openPassageId: String? = null
        // Two blank paragraphs go between questions (the next question's
        // marker stays line-anchored either way, and the reader drops blank
        // paragraphs on re-import) but never after the last: the separator is
        // written before whatever follows, so a streamed body produces the
        // same document a look-ahead one did.
        var pendingSeparator = false

        suspend fun flushSeparator() {
            if (!pendingSeparator) return
            out.append("<w:p/><w:p/>")
            pendingSeparator = false
        }

        suspend fun flow(text: String) {
            flushSeparator()
            out.append(text)
        }

        suspend fun passageAbove(question: QuestionDto): String? {
            val wanted = question.passageId
            if (wanted == openPassageId ||
                (wanted.isNullOrBlank() && openPassageId.isNullOrBlank())
            ) {
                return openPassageId
            }
            val passage = passagesById[wanted]
            if (passage != null) {
                flow(passageBlocks(passage, media, warnings).joinToString(""))
            } else if (wanted.isNullOrBlank()) {
                // A standalone question after a passage's members: a bare
                // `Passage:` line is the marker grammar's end-of-passage
                // reset, so the question does not re-bind to the block
                // above it on re-import.
                flow("<w:p><w:r><w:t xml:space=\"preserve\">Passage:</w:t></w:r></w:p>")
            } else {
                // A question that names no passage in the stream — a
                // passage deleted after the questions that reference it, or
                // an incomplete stream — stays member-less: the visible
                // markers cannot bind it to anything. Unlike every other
                // uncarryable artifact (options, images), this used to be
                // silent, so the file just read wrong.
                warnings.add(
                    "Question ${question.id} references passage $wanted, which is not " +
                        "in the stream; the passage context was not written."
                )
            }
            return wanted
        }

        suspend fun question(question: QuestionDto) {
            number++
            flushSeparator()
            out.append(questionBlocks(question, number, media, warnings).joinToString(""))
            pendingSeparator = true
        }

        out.append(headingPara(1, stream.title.ifBlank { "Paper" }))
        if (stream.description.isNotBlank()) {
            out.append(para(listOf(RunSpec(stream.description, RunFlags()))))
        }
        val meta = buildList {
            if (stream.durationMinutes > 0) add("${stream.durationMinutes} min")
            if (stream.negativeMarking > 0) add("negative marking ${stream.negativeMarking}")
        }
        if (meta.isNotEmpty()) {
            out.append(para(listOf(RunSpec(meta.joinToString(" • "), RunFlags(italic = true)))))
        }

        stream.walk(
            onCategory = { category ->
                // Reset per section: the sectioned parser binds passages
                // within a section only, so a passage shared with a later
                // section is re-emitted above its members there instead of
                // silently lost.
                openPassageId = null
                val depth = bySection[category.id]?.depth ?: 0
                flow(headingPara((depth + 2).coerceAtMost(9), category.title))
            },
            onQuestion = { row ->
                openPassageId = passageAbove(row)
                question(row)
            }
        )

        // Top-level questions, a shape a foreign file can carry. With
        // categories present they get their own section, so they do not
        // silently join the last one.
        val loose = stream.looseQuestions
        if (loose.isNotEmpty()) {
            if (stream.categories.isNotEmpty()) flow(headingPara(2, "Uncategorized"))
            openPassageId = null
            for (row in loose) {
                openPassageId = passageAbove(row)
                question(row)
            }
        }
    }

    private val payloadJsonFormat = Json { encodeDefaults = false }

    // ------------------------------------------------------------------
    // Document structure
    // ------------------------------------------------------------------

    private fun headingPara(level: Int, title: String): String {
        val text = title.replace(Regex("<[^>]*>"), "").ifBlank { "Section" }
        return "<w:p><w:pPr><w:pStyle w:val=\"Heading$level\"/></w:pPr>" +
            "<w:r><w:t xml:space=\"preserve\">${escapeXmlText(text)}</w:t></w:r></w:p>"
    }

    private fun para(specs: List<RunSpec>): String =
        ParaBuilder().addAndBuild(specs)

    private fun passageBlocks(passage: PassageDto, media: MediaPool, warnings: MutableList<String>): List<String> {
        val out = mutableListOf<String>()
        // The marker line: "Passage: <title>". The "Passage: " prefix must
        // stay plain text — the parser only recognises a line that literally
        // starts with it, so the title (which may be bold) rides after.
        out.add(
            "<w:p>" +
                "<w:r><w:t xml:space=\"preserve\">Passage: </w:t></w:r>" +
                "<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">" +
                "${escapeXmlText(passage.title.ifBlank { "Passage" })}</w:t></w:r>" +
                "</w:p>"
        )
        out.addAll(fieldBlocks(passage.elements, passage.image, media, warnings))
        return out
    }

    /**
     * Image srcs stripped from the hidden payload: the same bytes ship as
     * `word/media` entries, and a payload full of base64 doubles the file
     * (a multi-MB `vt:lpwstr` property is also unverified against Word's
     * limits). Stripped srcs become empty strings — not nulls — so the
     * parser's freshness fingerprint still sees the image *positions* and
     * grafts the real srcs back from the body on re-import.
     */
    private fun stripPayloadImages(paper: PaperDto): PaperDto = paper.copy(
        categories = paper.categories.map { category ->
            category.copy(questions = category.questions.map { stripPayloadImages(it) })
        },
        questions = paper.questions.map { stripPayloadImages(it) }
    )

    private fun stripPayloadImages(passage: PassageDto): PassageDto = passage.copy(
        elements = stripElementImages(passage.elements),
        image = passage.image?.let { stripScalarSrc(it) }
    )

    private fun stripPayloadImages(question: QuestionDto): QuestionDto = question.copy(
        elements = stripElementImages(question.elements),
        image = question.image?.let { stripScalarSrc(it) },
        options = question.options.map { option ->
            // The payload's `options_elements` shape has no room for an
            // option's text beside its elements, so text-only options fold
            // their text into an element — the same fallback the visible
            // body uses. An option cannot carry a scalar image beside
            // element images either (the serializer drops it), so when
            // both exist the stripped scalar rides along as an extra
            // position marker instead.
            val base = if (option.elements.isNotEmpty()) {
                option.elements
            } else {
                listOfNotNull(
                    option.text.takeIf { it.isNotBlank() }
                        ?.let { ContentElement.TextElement(it) }
                )
            }
            val stripped = stripElementImages(base)
            val elements = if (
                option.image != null && option.image.startsWith("data:") &&
                base.any { it is ContentElement.ImageElement }
            ) {
                stripped + ContentElement.ImageElement("")
            } else {
                stripped
            }
            option.copy(elements = elements, image = option.image?.let { stripScalarSrc(it) })
        },
        explanationElements = stripElementImages(question.explanationElements),
        explanationImage = question.explanationImage?.let { stripScalarSrc(it) }
    )

    /**
     * Only embedded data URIs are blanked: a remote URL is a short string
     * the payload can keep as-is (the body cannot carry it, so the graft
     * leaves it alone), while base64 bytes are what bloats the property.
     */
    private fun stripScalarSrc(src: String): String =
        if (src.startsWith("data:")) "" else src

    private fun stripElementImages(elements: List<ContentElement>): List<ContentElement> =
        elements.map { element ->
            if (element is ContentElement.ImageElement) {
                element.copy(src = stripScalarSrc(element.src))
            } else {
                element
            }
        }

    private fun questionBlocks(
        question: QuestionDto,
        number: Int,
        media: MediaPool,
        warnings: MutableList<String>
    ): List<String> {
        val out = mutableListOf<String>()
        val stemElements = if (question.elements.isNotEmpty()) {
            question.elements
        } else {
            listOfNotNull(
                question.text.takeIf { it.isNotBlank() }
                    ?.let { ContentElement.TextElement(it) }
            )
        }
        // A question without options (or with nothing at all in the stem)
        // cannot round-trip: the marker grammar requires (a)… and Ans.
        // Emitting one would make the whole document fail loudly on import,
        // so it is left out with a warning instead.
        if (question.options.isEmpty() ||
            (stemElements.isEmpty() && question.image == null)
        ) {
            warnings.add(
                "Question ${question.id} has no options or no content; " +
                    "Word cannot carry it and it was left out."
            )
            return out
        }
        out.addAll(
            fieldBlocks(
                stemElements, question.image, media, warnings,
                markerPrefix = "$number.) "
            )
        )
        // Every stem element can be uncarryable (a remote picture the
        // body cannot hold); with no stem paragraph the `N.)` marker
        // never reaches the document and the import would choke.
        if (out.isEmpty()) {
            warnings.add(
                "Question ${question.id} has no content Word can carry; " +
                    "it was left out."
            )
            return out
        }

        val carried = question.options.take(OPTION_LETTERS.size)
        if (carried.size < question.options.size) {
            warnings.add(
                "Question ${question.id} has ${question.options.size} options; only " +
                    "${OPTION_LETTERS.size} fit in the marker format, the rest were left out."
            )
        }
        carried.forEachIndexed { index, option ->
            val optionElements = if (option.elements.isNotEmpty()) {
                option.elements
            } else {
                listOfNotNull(
                    option.text.takeIf { it.isNotBlank() }
                        ?.let { ContentElement.TextElement(it) }
                )
            }
            out.addAll(
                fieldBlocks(
                    optionElements, option.image, media, warnings,
                    markerPrefix = "(${OPTION_LETTERS[index]}) "
                )
            )
        }

        out.add(answerPara(question, carried, warnings))
        out.addAll(explanationBlocks(question, media, warnings))
        return out
    }

    /**
     * "Ans. b" or "Ans. b, d". Correct options are named by the letters the
     * marker grammar reads back, so the answer key survives. A question
     * whose correct ids resolve to nothing (corrupted data) still names its
     * first option — the format refuses answerless questions, and a wrong
     * but present answer beats an unimportable document. Warned about.
     */
    private fun answerPara(
        question: QuestionDto,
        options: List<OptionDto>,
        warnings: MutableList<String>
    ): String {
        val letters = question.correctOptionIds.mapNotNull { id ->
            options.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { OPTION_LETTERS[it] }
        }.distinct()
        val resolved = if (letters.isEmpty() && options.isNotEmpty()) {
            warnings.add(
                "Question ${question.id} has no correct option recorded; the answer was written as (a)."
            )
            listOf(OPTION_LETTERS[0])
        } else {
            letters
        }
        return "<w:p><w:r><w:t xml:space=\"preserve\">Ans. " +
            resolved.joinToString(", ") + "</w:t></w:r></w:p>"
    }

    private fun explanationBlocks(
        question: QuestionDto,
        media: MediaPool,
        warnings: MutableList<String>
    ): List<String> {
        val hasContent = question.explanationElements.isNotEmpty() ||
            question.explanation.isNotBlank() || question.explanationImage != null
        if (!hasContent) return emptyList()
        val elements = if (question.explanationElements.isNotEmpty()) {
            question.explanationElements
        } else {
            listOf(ContentElement.TextElement(question.explanation))
        }
        return fieldBlocks(
            elements, question.explanationImage, media, warnings, markerPrefix = "Exp: "
        )
    }

    // ------------------------------------------------------------------
    // Fields: content elements to paragraphs and tables
    // ------------------------------------------------------------------

    private sealed interface FieldItem
    private data class Runs(
        val specs: List<RunSpec>,
        val breaksParagraph: Boolean = false
    ) : FieldItem
    private data class Math(val omml: String) : FieldItem
    private data class Tbl(val rows: List<List<String>>) : FieldItem

    /**
     * Content elements to a list of blocks: paragraphs of runs (with maths
     * and pictures inline) and whole tables. A table cannot sit inside a
     * paragraph, so it closes the paragraph before it; the marker parser
     * joins everything until the next marker anyway.
     *
     * [markerPrefix] makes the first paragraph open with a literal marker
     * ("1.) ", "(a) ", "Exp: "). Later paragraphs, and every line after a
     * break, are guarded so their text cannot start with something the
     * parser would read as a marker of its own.
     */
    private fun fieldBlocks(
        elements: List<ContentElement>,
        legacyImage: String?,
        media: MediaPool,
        warnings: MutableList<String>,
        markerPrefix: String? = null
    ): List<String> {
        val items = mutableListOf<FieldItem>()
        // A field holding a formula renders in the app as one flowing block
        // (text and equation inline); without one, every element is its own
        // stacked row. The document mirrors whichever layout applies.
        val flowsInline = elements.any { it is ContentElement.MathElement }
        for (element in elements) {
            when (element) {
                is ContentElement.TextElement ->
                    items.add(Runs(inlineRuns(element.text), breaksParagraph = !flowsInline))
                is ContentElement.MathElement -> {
                    val omml = mathMlToOmml(element.mathml)
                    if (omml != null) {
                        items.add(Math(omml))
                    } else {
                        warnings.add(
                            "An equation uses constructs Word export does not support " +
                                "and was kept as plain text — check it after re-import."
                        )
                        items.add(Runs(listOf(RunSpec(mathMlLinearText(element.mathml), RunFlags()))))
                    }
                }
                is ContentElement.ImageElement ->
                    media.embed(element.src, warnings)?.let {
                        items.add(it.copy(breaksParagraph = !flowsInline))
                    }
                is ContentElement.TableElement -> items.add(Tbl(element.rows))
            }
        }
        media.embed(legacyImage, warnings)?.let {
            items.add(it.copy(breaksParagraph = !flowsInline))
        }

        val out = mutableListOf<String>()
        var pendingPrefix: String? = markerPrefix
        fun newBuilder(): ParaBuilder {
            val builder = ParaBuilder(pendingPrefix)
            pendingPrefix = null
            return builder
        }
        var current = newBuilder()
        fun flush() {
            current.build()?.let { out.add(it) }
            current = newBuilder()
        }
        for (item in items) {
            when (item) {
                is Runs -> {
                    // A stacked-layout element starts a new paragraph only
                    // when the current one already has content — the first
                    // one must join its marker ("(a) Alpha", never a lone
                    // "(a)" on its own line).
                    if (item.breaksParagraph && current.hasRealContent()) flush()
                    current.add(item.specs)
                }
                is Math -> current.addMath(item.omml)
                is Pic -> {
                    if (item.breaksParagraph && current.hasRealContent()) flush()
                    current.addPicture(item)
                }
                is Tbl -> {
                    // A field that opens with the table still gets its marker
                    // paragraph: flush() emits the prefix-only builder (its
                    // build() counts the prefix as content), so the marker
                    // paragraph exists before the table even when the field
                    // opens with the table. The dead `if (pendingPrefix !=
                    // null)` this replaces was unreachable — newBuilder()
                    // consumed the prefix before the loop and every flush
                    // re-consumes it — and had it ever fired it would have
                    // emitted the marker paragraph TWICE.
                    flush()
                    out.add(tableXml(item.rows))
                }
            }
        }
        flush()
        if (out.isEmpty() && markerPrefix != null) {
            out.add(ParaBuilder(markerPrefix).build()!!)
        }
        return out
    }

    private fun tableXml(rows: List<List<String>>): String {
        // Auto layout: Word and LibreOffice size columns to their content,
        // so a two-word table does not stretch across the page. The grid
        // widths are only hints; the borders cover the inner lines too —
        // an outline-only table could not be read as a grid.
        val hint = 9360
        val cols = maxOf(1, rows.maxOfOrNull { it.size } ?: 1)
        val colWidth = hint / cols
        val border = { tag: String ->
            "<w:$tag w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>"
        }
        val borders = listOf("top", "left", "bottom", "right", "insideH", "insideV")
            .joinToString("") { border(it) }
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>")
        sb.append("<w:tblLayout w:type=\"autofit\"/>")
        sb.append("<w:tblBorders>$borders</w:tblBorders></w:tblPr>")
        sb.append("<w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"$colWidth\"/>") }
        sb.append("</w:tblGrid>")
        for (row in rows) {
            sb.append("<w:tr>")
            for (c in 0 until cols) {
                val cell = row.getOrElse(c) { "" }
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr>")
                sb.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                sb.append(escapeXmlText(cell))
                sb.append("</w:t></w:r></w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Runs: inline HTML to Word run properties
    // ------------------------------------------------------------------

    private data class RunFlags(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val sub: Boolean = false,
        val sup: Boolean = false,
        val mark: Boolean = false
    ) {
        fun set(tag: String): RunFlags = when (tag) {
            "bold" -> copy(bold = true)
            "italic" -> copy(italic = true)
            "underline" -> copy(underline = true)
            "strike" -> copy(strike = true)
            "sub" -> copy(sub = true)
            "sup" -> copy(sup = true)
            else -> copy(mark = true)
        }

        fun clear(tag: String): RunFlags = when (tag) {
            "bold" -> copy(bold = false)
            "italic" -> copy(italic = false)
            "underline" -> copy(underline = false)
            "strike" -> copy(strike = false)
            "sub" -> copy(sub = false)
            "sup" -> copy(sup = false)
            else -> copy(mark = false)
        }
    }

    private data class RunSpec(val text: String, val flags: RunFlags, val isBreak: Boolean = false)

    private val inlineTagPattern = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*>")

    /** Allow-listed inline tag to the run flag it drives; null = literal text. */
    private val inlineFlagTags = mapOf(
        "b" to "bold", "strong" to "bold",
        "i" to "italic", "em" to "italic",
        "u" to "underline",
        "del" to "strike", "s" to "strike", "strike" to "strike",
        "sub" to "sub", "sup" to "sup", "mark" to "mark"
    )

    /** Tags the display layer renders as line breaks. */
    private val breakTags = setOf("br", "p", "div", "li", "tr")

    /**
     * Inline HTML (`<strong>`, `<sub>`, …) to run specs, mirroring what the
     * in-app renderer shows: known tags drive formatting, `br` (and the
     * block tags) become line breaks, unknown tags stay visible as literal
     * text, and character references decode to their characters.
     */
    private fun inlineRuns(html: String): List<RunSpec> {
        val out = mutableListOf<RunSpec>()
        var flags = RunFlags()
        var pos = 0
        for (match in inlineTagPattern.findAll(html)) {
            html.substring(pos, match.range.first).let {
                if (it.isNotEmpty()) out.add(RunSpec(decodeXmlEntities(it), flags))
            }
            val closing = match.groupValues[1] == "/"
            val tag = match.groupValues[2].lowercase()
            val flag = inlineFlagTags[tag]
            when {
                flag != null -> flags = if (closing) flags.clear(flag) else flags.set(flag)
                tag in breakTags -> out.add(RunSpec("", flags, isBreak = true))
                // Unknown tag shapes are literal text in legacy questions
                // and must stay visible rather than vanish.
                else -> out.add(RunSpec(decodeXmlEntities(match.value), flags))
            }
            pos = match.range.last + 1
        }
        html.substring(pos).let {
            if (it.isNotEmpty()) out.add(RunSpec(decodeXmlEntities(it), flags))
        }
        return out
    }

    private class ParaBuilder(prefix: String? = null) {
        private val runs = StringBuilder()
        private var atStart = true
        private var wroteAnything = prefix != null

        /**
         * Real content — a text run, maths or a picture — as opposed to the
         * marker prefix the field opens with. Only this may break a paragraph
         * before the next block: counting the prefix as content orphaned every
         * marker ("1.)" and "Ans. a" on lines of their own) because the first
         * block flushed the prefix-only paragraph it was meant to join.
         */
        private var wroteContent = false

        init {
            if (!prefix.isNullOrBlank()) {
                runs.append("<w:r><w:t xml:space=\"preserve\">")
                runs.append(escapeXmlText(prefix))
                runs.append("</w:t></w:r>")
                atStart = false
            }
        }

        fun add(specs: List<RunSpec>) {
            for (spec in specs) {
                if (spec.isBreak) {
                    if (wroteAnything) runs.append("<w:r><w:br/></w:r>")
                    atStart = true
                    continue
                }
                // Stored text carries literal newlines where the display
                // wraps; Word needs a real break, and each new line is
                // guarded like a fresh paragraph.
                val lines = spec.text.split("\n")
                for ((index, line) in lines.withIndex()) {
                    if (index > 0) {
                        // A break is a run-level child; bare it would be
                        // invalid OOXML and lost on re-import.
                        if (wroteAnything) runs.append("<w:r><w:br/></w:r>")
                        atStart = true
                    }
                    var text = line
                    // A line the parser would take as a marker stays text
                    // with one leading space; the grammar is the parser's own
                    // (docxLineStartMarker), so it cannot drift from it.
                    if (atStart && docxLineStartMarker.containsMatchIn(text)) {
                        text = " $text"
                    }
                    if (text.isEmpty()) continue
                    runs.append(runXml(text, spec.flags))
                    atStart = false
                    wroteAnything = true
                    wroteContent = true
                }
            }
        }

        fun addMath(omml: String) {
            runs.append("<m:oMath>$omml</m:oMath>")
            atStart = false
            wroteAnything = true
            wroteContent = true
        }

        fun addPicture(pic: Pic) {
            runs.append(drawingXml(pic))
            atStart = false
            wroteAnything = true
            wroteContent = true
        }

        /** The paragraph XML, or null when it would be empty. */
        fun build(): String? {
            if (!hasContent()) return null
            return "<w:p>$runs</w:p>"
        }

        fun hasContent(): Boolean = wroteAnything || runs.isNotEmpty()

        /**
         * Whether anything beyond the marker prefix was written. A paragraph
         * break can only happen between real blocks, never between a prefix
         * and the content that belongs with it.
         */
        fun hasRealContent(): Boolean = wroteContent

        fun addAndBuild(specs: List<RunSpec>): String {
            add(specs)
            return build() ?: "<w:p/>"
        }
    }

    private fun runXml(text: String, flags: RunFlags): String {
        val rPr = buildString {
            if (flags.bold) append("<w:b/>")
            if (flags.italic) append("<w:i/>")
            if (flags.underline) append("<w:u w:val=\"single\"/>")
            if (flags.strike) append("<w:strike/>")
            if (flags.sub) append("<w:vertAlign w:val=\"subscript\"/>")
            if (flags.sup) append("<w:vertAlign w:val=\"superscript\"/>")
            if (flags.mark) append("<w:highlight w:val=\"yellow\"/>")
        }
        val props = if (rPr.isEmpty()) "" else "<w:rPr>$rPr</w:rPr>"
        return "<w:r>$props<w:t xml:space=\"preserve\">${escapeXmlText(text)}</w:t></w:r>"
    }

    // ------------------------------------------------------------------
    // Media: data URIs to word/media entries
    // ------------------------------------------------------------------

    private class MediaPool : Closeable {
        private val spool = MediaSpool()
        val rels = LinkedHashMap<String, String>()

        /** The `media/…` targets, in the order the body met them. */
        val targets: List<String> get() = spool.names

        private val uriToRid = HashMap<String, String>()
        private var mediaCounter = 0
        private var docPrCounter = 0
        private var warnedWebp = false

        /** Inline picture item for a data URI, or null (with a warning). */
        fun embed(src: String?, warnings: MutableList<String>): Pic? {
            if (src == null) return null
            val image = parseDataUri(src)
            if (image == null) {
                warnings.add(
                    "An image that is not embedded (e.g. a remote URL) was left out — " +
                        "Word documents carry their pictures as files."
                )
                return null
            }
            if (image.mimeType.equals("image/webp", ignoreCase = true) && !warnedWebp) {
                warnedWebp = true
                warnings.add(
                    "A picture is in webp format; older Word versions may not display it — " +
                        "convert it to PNG for compatibility."
                )
            }
            val rid = uriToRid.getOrPut(mediaKey(src)) {
                mediaCounter++
                val target = "media/image$mediaCounter.${imageExtension(image.mimeType)}"
                // Spooled, not kept: the body registers every picture the paper
                // has, and the entries are written after it.
                spool.add(target, image.bytes)
                val rid = "rIdMedia$mediaCounter"
                rels[rid] = target
                rid
            }
            val (w, h) = imageDimensions(image.bytes) ?: (320 to 240)
            val (cx, cy) = emuExtent(w, h)
            return Pic(rid, cx, cy, ++docPrCounter)
        }

        fun copyTo(target: String, out: OutputStream) = spool.copyTo(target, out)

        override fun close() = spool.close()
    }

    private fun drawingXml(pic: Pic): String =
        "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"${pic.cx}\" cy=\"${pic.cy}\"/>" +
            "<wp:docPr id=\"${pic.docPrId}\" name=\"Picture ${pic.docPrId}\"/>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic>" +
            "<pic:nvPicPr><pic:cNvPr id=\"${pic.docPrId}\" name=\"image${pic.docPrId}\"/>" +
            "<pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"${pic.rid}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"${pic.cx}\" cy=\"${pic.cy}\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"

    private data class Pic(
        val rid: String,
        val cx: Int,
        val cy: Int,
        val docPrId: Int,
        val breaksParagraph: Boolean = false
    ) : FieldItem

    private const val EMU_PER_PX = 9525
    private const val MAX_CX = 5486400

    /**
     * Pixels → the drawing's EMU extent, computed in LONG: pixel headers the
     * imageDimensions bound still accepts (1..1,000,000 px) overflow the Int
     * multiply at 225,443 px (225,443 × 9525 = 2,148,463,375, Int.MAX is
     * 2,147,483,647), the wrapped negative then slipped past the `> MAX_CX`
     * rescale and was written straight into `<wp:extent cx="-…">`, which Word
     * refuses the whole file for ("unreadable content"). Long math, clamped
     * before the store back to Int, so every in-bounds header yields a
     * positive extent and the writer's clamp is the whole fix — the import
     * bound stays where it is: its job is rejecting corrupt headers, not an
     * extra sizing knob.
     */
    internal fun emuExtent(w: Int, h: Int): Pair<Int, Int> {
        var cx = w.toLong() * EMU_PER_PX
        var cy = h.toLong() * EMU_PER_PX
        if (cx > MAX_CX) {
            cy = cy * MAX_CX / cx
            cx = MAX_CX.toLong()
        }
        // A lone huge height (narrow but 900k px tall header) would overflow
        // cy on its own after the unperturbed cx path; clamp it the same way.
        if (cy > MAX_CX) cy = MAX_CX.toLong()
        return cx.toInt() to cy.toInt()
    }

    /** Option letters the marker grammar supports; (a)…(j). */
    private val OPTION_LETTERS = ('a'..'j').map { it.toString() }

    // ------------------------------------------------------------------
    // Categories: flat parentId list to depths
    // ------------------------------------------------------------------

    private class CatNode(val dto: CategoryDto, val depth: Int) {
        val title: String = dto.title.ifBlank { "Category" }
    }

    /**
     * Categories arrive in export order (a preorder sequence per paper)
     * with parentId links; returns them in that order with their tree
     * depths. A parentId that points nowhere or into a cycle (corrupt
     * data) makes the category top-level instead of recursing forever.
     */
    private fun flattenCategories(categories: List<CategoryDto>): List<CatNode> {
        val byId = HashMap<String, CategoryDto>()
        for (c in categories) byId[c.id] = c
        val depths = HashMap<String, Int>()

        fun depthOf(id: String, trail: Set<String>): Int {
            depths[id]?.let { return it }
            val dto = byId[id] ?: return 0
            val parentId = dto.parentId
            if (parentId == null || parentId !in byId || parentId in trail) {
                depths[id] = 0
                return 0
            }
            val depth = depthOf(parentId, trail + id) + 1
            depths[id] = depth
            return depth
        }
        return categories.map { CatNode(it, depthOf(it.id, emptySet())) }
    }

    // ------------------------------------------------------------------
    // Package assembly
    // ------------------------------------------------------------------

    private fun contentTypesXml(): String {
        val defaults = buildString {
            for (ext in listOf("rels", "xml", "png", "jpeg", "jpg", "gif", "bmp", "webp")) {
                val type = when (ext) {
                    "rels" -> "application/vnd.openxmlformats-package.relationships+xml"
                    "xml" -> "application/xml"
                    "jpg", "jpeg" -> "image/jpeg"
                    else -> "image/$ext"
                }
                append("<Default Extension=\"$ext\" ContentType=\"$type\"/>")
            }
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            defaults +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/docProps/custom.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.custom-properties+xml\"/>" +
            "</Types>"
    }

    private fun rootRelsXml(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/custom-properties\" Target=\"docProps/custom.xml\"/>" +
            "</Relationships>"

    /**
     * The custom-properties part, in two halves because the payload streams
     * between them. `pid` must be 2 or above (1 is reserved); the JSON rides
     * as a plain string, and everything XML-significant in it is escaped as it
     * is appended.
     */
    private fun customPropsHead(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/custom-properties\" " +
            "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">" +
            "<property fmtid=\"{D5CDD505-2E9C-101B-9397-08002B2CF9AE}\" pid=\"2\" " +
            "name=\"$PAYLOAD_PROPERTY\"><vt:lpwstr>"

    private fun customPropsTail(): String = "</vt:lpwstr></property></Properties>"

    private fun documentRelsXml(media: MediaPool): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        sb.append(
            "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
        )
        for ((rid, target) in media.rels) {
            sb.append(
                "<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"$target\"/>"
            )
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun documentHead(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document" +
            " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
            " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"" +
            " xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"" +
            " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"" +
            " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"" +
            " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"" +
            "><w:body>"

    private fun documentTail(): String =
        "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"" +
            " w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            "<w:docGrid w:linePitch=\"360\"/></w:sectPr>" +
            "</w:body></w:document>"

    private fun stylesXml(): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
        sb.append(
            "<w:style w:type=\"paragraph\" w:styleId=\"Normal\">" +
                "<w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
        )
        // Word sizes are half-points. Headings shrink as they descend so the
        // tree reads at a glance.
        val sizes = intArrayOf(48, 40, 36, 32, 28, 26, 24, 24, 24)
        for (level in 1..9) {
            val sz = sizes[level - 1]
            sb.append(
                "<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\">" +
                    "<w:name w:val=\"heading $level\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/>" +
                    "<w:outlineLvl w:val=\"${level - 1}\"/></w:pPr>" +
                    "<w:rPr><w:b/><w:sz w:val=\"$sz\"/></w:rPr></w:style>"
            )
        }
        sb.append("</w:styles>")
        return sb.toString()
    }
}

/**
 * XML-escapes text as it is appended, so a part written straight into a zip
 * entry never exists as an escaped copy of itself. Only `&`, `<` and `>` are
 * escaped, exactly as [escapeXmlText] does — an entity is never split across
 * two appends, so escaping chunk by chunk is the same as escaping the whole.
 *
 * Every append returns this appendable, as `Appendable` requires: a caller
 * that chained onto the wrapped writer would write the rest of its text
 * unescaped. Internal so the contract can be asserted directly.
 */
internal class XmlEscapingAppendable(private val out: Appendable) : Appendable {

    override fun append(csq: CharSequence?): Appendable = append(csq, 0, csq?.length ?: 0)

    override fun append(csq: CharSequence?, start: Int, end: Int): Appendable {
        if (csq == null) return this
        var runStart = start
        for (index in start until end) {
            val char = csq[index]
            if (char == '&' || char == '<' || char == '>') {
                if (runStart < index) out.append(csq, runStart, index)
                out.append(escaped(char))
                runStart = index + 1
            }
        }
        if (runStart < end) out.append(csq, runStart, end)
        return this
    }

    override fun append(c: Char): Appendable {
        out.append(escaped(c))
        return this
    }

    private fun escaped(c: Char): String = when (c) {
        '&' -> "\u0026amp;"
        '<' -> "\u0026lt;"
        '>' -> "\u0026gt;"
        else -> c.toString()
    }
}

/**
 * Pixel dimensions of an embedded image, read straight from the file
 * header (PNG IHDR, GIF screen descriptor, BMP header, JPEG SOFn scan).
 * Null when the format is not recognised; the caller then uses a default
 * size rather than a wrong one.
 */
internal fun imageDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 24) return null
    return when {
        bytes.isPng() -> pngSize(bytes)
        bytes.isGif() -> gifSize(bytes)
        bytes.isBmp() -> bmpSize(bytes)
        bytes.isJpeg() -> jpegSize(bytes)
        else -> null
    }
}

private fun ByteArray.isPng() =
    this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()

private fun ByteArray.isGif() = this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte()

private fun ByteArray.isBmp() = this[0] == 'B'.code.toByte() && this[1] == 'M'.code.toByte()

private fun ByteArray.isJpeg() = this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()

private fun ByteArray.u32be(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.u32le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.u16le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
    val w = bytes.u32be(16)
    val h = bytes.u32be(20)
    return if (w in 1..1_000_000 && h in 1..1_000_000) w to h else null
}

private fun gifSize(bytes: ByteArray): Pair<Int, Int>? {
    val w = bytes.u16le(6)
    val h = bytes.u16le(8)
    return if (w in 1..1_000_000 && h in 1..1_000_000) w to h else null
}

private fun bmpSize(bytes: ByteArray): Pair<Int, Int>? {
    // Height is signed (negative = top-down rows); take its magnitude.
    val w = bytes.u32le(18)
    val rawH = bytes.u32le(22)
    val h = if (rawH < 0) -rawH else rawH
    return if (w in 1..1_000_000 && h in 1..1_000_000) w to h else null
}

private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
    var i = 2
    while (i + 9 < bytes.size) {
        if (bytes[i] != 0xFF.toByte()) return null
        val marker = bytes[i + 1].toInt() and 0xFF
        when {
            // Standalone/no-length markers (TEM, RST0-7, EOI): skipping them
            // like SOI — treating them as length-bearing read the next two
            // payload bytes as a length and desynced the scan, so a packet
            // the writer met before the SOF degraded the drawing to the
            // 320×240 default. Malformed-only in practice (RSTs live after
            // SOS and the scan answers at the first SOF), but the walk is
            // now marker-shape-honest either way.
            marker == 0xFF || marker == 0x01 || marker in 0xD0..0xD9 -> i += 2
            // SOS (0xDA): everything from here to EOI is entropy-coded data
            // whose bytes both embed 0xFF-padded markers and carry no size.
            // There is no size segment after it either, so stop.
            marker == 0xDA -> return null
            // SOF0-SOF15 except DHT (C4), JPG (C8) and DAC (CC) carry the size.
            marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                val h = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val w = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return if (w in 1..1_000_000 && h in 1..1_000_000) w to h else null
            }
            else -> {
                val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                // A zero/negative length is a corrupt stream; without the
                // guard the scan advances nowhere and spins.
                if (len < 2) return null
                i += 2 + len
            }
        }
    }
    return null
}
