package com.mcqapp.data.export

import com.mcqapp.data.docx.decodeXmlEntities
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
import java.io.ByteArrayOutputStream
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

    fun paperToDocxBytes(paper: PaperDto, passages: List<PassageDto> = emptyList()): ByteArray {
        val warnings = mutableListOf<String>()
        val media = MediaPool()
        val body = StringBuilder()

        // The hidden payload: the full paper DTO as a custom document
        // property. It is invisible in Word (File > Info > Advanced
        // Properties aside) and survives Word's own saves, so a file the
        // app wrote and nobody edited re-imports with ids, marks,
        // difficulty and tags intact instead of duplicating everything the
        // visible markers cannot carry. The parser falls back to the
        // markers when the payload no longer matches the visible text,
        // and grafts the image srcs back from the word/media entries the
        // payload deliberately does not duplicate.
        val payload = payloadJsonFormat.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(
                version = 1,
                papers = listOf(stripPayloadImages(paper)),
                passages = passages.map { stripPayloadImages(it) }
            )
        )

        body.append(headingPara(1, paper.title.ifBlank { "Paper" }))
        if (paper.description.isNotBlank()) {
            body.append(para(listOf(RunSpec(paper.description, RunFlags()))))
        }
        val meta = buildList {
            if (paper.durationMinutes > 0) add("${paper.durationMinutes} min")
            if (paper.negativeMarking > 0) add("negative marking ${paper.negativeMarking}")
        }
        if (meta.isNotEmpty()) {
            body.append(para(listOf(RunSpec(meta.joinToString(" • "), RunFlags(italic = true)))))
        }

        var number = 0
        fun emitQuestion(q: QuestionDto, last: Boolean) {
            number++
            body.append(questionBlocks(q, number, media, warnings).joinToString(""))
            // Two blank lines between questions: the next question's marker
            // stays line-anchored either way, and the reader drops blank
            // paragraphs on re-import.
            if (!last) body.append("<w:p/><w:p/>")
        }

        fun emitPassageAbove(question: QuestionDto, openPassageId: String?): String? {
            val wanted = question.passageId
            if (wanted == openPassageId ||
                (wanted.isNullOrBlank() && openPassageId.isNullOrBlank())
            ) {
                return openPassageId
            }
            val passage = passages.firstOrNull { it.id == wanted }
            if (passage != null) {
                body.append(passageBlocks(passage, media, warnings).joinToString(""))
            } else if (wanted.isNullOrBlank()) {
                // A standalone question after a passage's members: a bare
                // `Passage:` line is the marker grammar's end-of-passage
                // reset, so the question does not re-bind to the block
                // above it on re-import.
                body.append(
                    "<w:p><w:r><w:t xml:space=\"preserve\">Passage:</w:t></w:r></w:p>"
                )
            }
            return wanted
        }

        var openPassageId: String? = null
        val allSections = flattenCategories(paper.categories)
        val lastSectionQuestionId = allSections.lastOrNull()?.dto?.questions?.lastOrNull()?.id
        val lastTopLevelId = paper.questions.lastOrNull()?.id
        for (node in allSections) {
            body.append(headingPara((node.depth + 2).coerceAtMost(9), node.title))
            // Reset per section: the sectioned parser binds passages within
            // a section only, so a passage shared with a later section is
            // re-emitted above its members there instead of silently lost.
            openPassageId = null
            for (question in node.dto.questions) {
                openPassageId = emitPassageAbove(question, openPassageId)
                emitQuestion(question, question.id == lastSectionQuestionId)
            }
        }
        // Top-level questions (the bookmarks/selection export shape). With
        // categories present they get their own section, so they do not
        // silently join the last one.
        if (paper.questions.isNotEmpty()) {
            if (paper.categories.isNotEmpty()) body.append(headingPara(2, "Uncategorized"))
            openPassageId = null
            for (question in paper.questions) {
                openPassageId = emitPassageAbove(question, openPassageId)
                emitQuestion(question, question.id == lastTopLevelId)
            }
        }

        for (warning in warnings) Logger.w("DOCX", warning)
        return assembleZip(body.toString(), media, payload)
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
                    if (item.breaksParagraph && current.hasContent()) flush()
                    current.add(item.specs)
                }
                is Math -> current.addMath(item.omml)
                is Pic -> {
                    if (item.breaksParagraph && current.hasContent()) flush()
                    current.addPicture(item)
                }
                is Tbl -> {
                    // The marker paragraph must exist before the table even
                    // when the field opens with the table.
                    if (pendingPrefix != null) {
                        out.add(ParaBuilder(pendingPrefix).build()!!)
                    }
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

    /**
     * Lines that would be read as markers if they started a paragraph or
     * followed a line break. A stem that continues "2.) …" on its own line
     * would otherwise split into a phantom question; one leading space
     * keeps it part of the field (the reader keeps authored leading space).
     */
    private val markerish = Regex(
        "^(\\d{1,7}\\.\\)|\\([a-j]\\)|Ans\\.|Exp:|Passage:)",
        RegexOption.IGNORE_CASE
    )

    private class ParaBuilder(prefix: String? = null) {
        private val runs = StringBuilder()
        private var atStart = true
        private var wroteAnything = prefix != null

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
                    if (atStart && markerish.containsMatchIn(text)) {
                        text = " $text"
                    }
                    if (text.isEmpty()) continue
                    runs.append(runXml(text, spec.flags))
                    atStart = false
                    wroteAnything = true
                }
            }
        }

        fun addMath(omml: String) {
            runs.append("<m:oMath>$omml</m:oMath>")
            atStart = false
            wroteAnything = true
        }

        fun addPicture(pic: Pic) {
            runs.append(drawingXml(pic))
            atStart = false
            wroteAnything = true
        }

        /** The paragraph XML, or null when it would be empty. */
        fun build(): String? {
            if (!hasContent()) return null
            return "<w:p>$runs</w:p>"
        }

        fun hasContent(): Boolean = wroteAnything || runs.isNotEmpty()

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

    private class MediaPool {
        val files = LinkedHashMap<String, ByteArray>()
        val rels = LinkedHashMap<String, String>()
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
            val rid = uriToRid.getOrPut(src) {
                mediaCounter++
                val target = "media/image$mediaCounter.${imageExtension(image.mimeType)}"
                files[target] = image.bytes
                val rid = "rIdMedia$mediaCounter"
                rels[rid] = target
                rid
            }
            val (w, h) = imageDimensions(image.bytes) ?: (320 to 240)
            var cx = w * EMU_PER_PX
            var cy = h * EMU_PER_PX
            if (cx > MAX_CX) {
                cy = (cy.toLong() * MAX_CX / cx).toInt()
                cx = MAX_CX
            }
            return Pic(rid, cx, cy, ++docPrCounter)
        }
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

    private fun assembleZip(bodyContent: String, media: MediaPool, payload: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(contentTypesXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(rootRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("docProps/custom.xml"))
            zip.write(customPropsXml(payload).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml(bodyContent).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zip.write(documentRelsXml(media).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/styles.xml"))
            zip.write(stylesXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((target, bytes) in media.files) {
                zip.putNextEntry(ZipEntry("word/$target"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

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
     * The custom-properties part. `pid` must be 2 or above (1 is reserved);
     * the JSON rides as a plain string. Everything XML-significant in the
     * JSON — quotes are fine in element text — is escaped.
     */
    private fun customPropsXml(payload: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/custom-properties\" " +
            "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">" +
            "<property fmtid=\"{D5CDD505-2E9C-101B-9397-08002B2CF9AE}\" pid=\"2\" " +
            "name=\"$PAYLOAD_PROPERTY\"><vt:lpwstr>" +
            escapeXmlText(payload) +
            "</vt:lpwstr></property></Properties>"

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

    private fun documentXml(bodyContent: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document" +
            " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
            " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"" +
            " xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"" +
            " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"" +
            " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"" +
            " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"" +
            "><w:body>$bodyContent" +
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
        if (marker == 0xD8 || marker in 0x01..0x09 || marker == 0x64) {
            i += 2
            continue
        }
        val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
        // SOF0-SOF15 except DHT (C4), JPG (C8) and DAC (CC) carry the size.
        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
            val h = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
            val w = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
            return if (w in 1..1_000_000 && h in 1..1_000_000) w to h else null
        }
        i += 2 + len
    }
    return null
}
