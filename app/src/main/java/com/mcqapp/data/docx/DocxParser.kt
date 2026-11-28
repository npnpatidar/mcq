package com.mcqapp.data.docx

import com.mcqapp.data.escapeHtmlText
import com.mcqapp.data.export.chemElementText
import com.mcqapp.data.export.docxCellImageSrc
import com.mcqapp.data.export.forEachDocxCellImage
import com.mcqapp.data.export.imageSrcHash
import com.mcqapp.data.export.isImageSrcDescriptor
import com.mcqapp.data.export.isPngDataUri
import com.mcqapp.data.export.resolveImageSrcDescriptor
import com.mcqapp.data.export.withDocxCellImageSrc
import com.mcqapp.data.io.FALLBACK_CATEGORY_LABEL
import com.mcqapp.data.io.FALLBACK_CATEGORY_TITLE
import com.mcqapp.data.io.FALLBACK_PASSAGE_TITLE
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.CellPart
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.tableCellParts
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.CellSegment
import com.mcqapp.util.Logger
import com.mcqapp.util.cellChemSpans
import com.mcqapp.util.cellSegments
import com.mcqapp.util.forEachCellImage
import com.mcqapp.util.forEachCellImageSpan
import com.mcqapp.util.imgTagSrc
import com.mcqapp.util.portableCellImageSrc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * DOCX bytes to the labeled question JSON our importer reads
 * (`question_num`, `question_elements`, `options_elements`,
 * `answer`, `explanation_elements`) — the same shape `ratta_ai`
 * `docx2json` produces.
 *
 * Malformed markers fail loudly with the offending question number
 * (mirroring the Python pipeline) so authors fix the document
 * instead of silently losing questions. The returned JSON feeds
 * straight into `LegacyParser`.
 */
data class DocxParseResult(
    val json: String,
    val warnings: List<String>,
    /** The document's own paper title (a Title/Heading 1 line), when present. */
    val paperTitle: String? = null
)

private const val ZIP_MAGIC_0 = 0x50
private const val ZIP_MAGIC_1 = 0x4B

/**
 * True when [bytes] look like a .docx: a zip containing
 * `word/document.xml` near the front. Used to route picked files:
 * both .apkg and .docx are zips, so magic bytes alone cannot tell
 * them apart — and picker names/MIME types are unreliable.
 */
fun isDocxArchive(bytes: ByteArray): Boolean {
    if (bytes.size < 4 || bytes[0].toInt() != ZIP_MAGIC_0 || bytes[1].toInt() != ZIP_MAGIC_1) return false
    return try {
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            // Files counted, not entries — the same rule the streaming
            // classifier in PickedArchive applies: a file manager's re-zip
            // pads the archive with directory entries, and counting them
            // pushed word/document.xml past the cap and mis-filed a real
            // Word bank. The byte overload is the rule's second copy, so it
            // must not disagree with the primary one.
            var checked = 0
            var entry = zip.nextEntry
            while (entry != null && checked < 12) {
                if (!entry.isDirectory) {
                    if (entry.name == "word/document.xml") return true
                    checked++
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            false
        }
    } catch (e: Exception) {
        // A malformed or truncated ZIP means "not a .docx"; the caller tries
        // the other readers. Worth one diagnostic, not one per picked file.
        Logger.warnOnce("DOCX", "Could not inspect a picked archive as a .docx", e)
        false
    }
}

fun parseDocx(bytes: ByteArray): DocxParseResult {
    val read = readDocx(bytes)
    val lines = read.blocks.map { block ->
        when (block) {
            is DocxBlock.Para -> DocxLine(block.html, headingLevelOf(block.style))
            is DocxBlock.Table -> DocxLine(tableHtml(block), null)
        }
    }
    val visible = if (lines.none { it.heading != null }) {
        parseFlatDocx(lines.map { it.html }, read.warnings)
    } else {
        parseSectionedDocx(lines, read.warnings)
    }
    return preferPayload(read.payloadJson, visible, read.warnings)
}

/**
 * The app's own exports carry a hidden full-fidelity payload (ids, marks,
 * difficulty, tags — everything the visible markers cannot express). When
 * it is present **and still describes the visible text**, it wins: an
 * untouched export re-imports without duplicating a single question. A
 * payload that disagrees with the body — the file was edited in Word,
 * Google Docs stripped the part, or it is from an older app version — is
 * ignored in favour of what the reader can actually see.
 *
 * Freshness is a full-content fingerprint per question, not just the stem:
 * option texts, the answer key, explanations, math (as leaf text, so the
 * OMML round trip cannot cause false staleness), image positions (not
 * srcs — those are grafted from the body) and passage membership must all
 * line up, in order, with the paper title and the passage texts as well.
 * Any visible question the payload does not fingerprint-match means the
 * body was edited and the payload is stale.
 *
 * Matching is a subsequence walk: payload questions the writer left out of
 * the visible body (one with no stem cannot be carried by the markers) may
 * sit between matches — never the other way round.
 *
 * When the payload is fresh it also carries the images: the writer strips
 * data-URI srcs from the payload (they would double the file), so srcs are
 * grafted back from the visible body positionally before the payload JSON
 * is handed on.
 */
private fun preferPayload(
    payloadJson: String?,
    visible: DocxParseResult,
    warnings: List<String>
): DocxParseResult {
    if (payloadJson == null) return visible
    val payload = try {
        LegacyParser.parse(payloadJson)
    } catch (e: Exception) {
        // A payload that no longer parses is stale by definition. We import
        // the visible text, but the reason must be observable — otherwise a
        // writer regression looks exactly like a user-edited document.
        Logger.warnOnce("DOCX", "The hidden question data is unreadable; using the visible markers", e)
        return visible
    }
    val visibleFile = try {
        LegacyParser.parse(visible.json)
    } catch (e: Exception) {
        // The visible markers themselves failed to parse: a quiet return here
        // hid a real parser bug behind a successful-looking import.
        Logger.warnOnce("DOCX", "The visible markers could not be parsed for payload comparison", e)
        return visible
    }
    val payloadTitle = payload.papers.firstOrNull()?.title
    val payloadQuestions = payload.papers
        .flatMap { it.categories.flatMap { c -> c.questions } + it.questions }
    val visibleQuestions = visibleFile.papers
        .flatMap { it.categories.flatMap { c -> c.questions } + it.questions }
    fun stale(reason: String): DocxParseResult {
        Logger.warnOnce(
            "DOCX",
            "payload stale ($reason): payloadQs=${payloadQuestions.size} visibleQs=${visibleQuestions.size} " +
                "payloadTitle='$payloadTitle' visibleTitle='${visible.paperTitle}'"
        )
        return DocxParseResult(
            visible.json,
            warnings + listOf(
                "The document's hidden question data is out of date (the file was " +
                    "edited outside the app), so marks, difficulty and tags were " +
                    "restored from the visible text only."
            ),
            visible.paperTitle
        )
    }

    // A renamed title is an edit like any other.
    if (visible.paperTitle != null && !payloadTitle.isNullOrBlank() &&
        normalizeStem(payloadTitle) != normalizeStem(visible.paperTitle)
    ) {
        return stale("title")
    }

    // Passage texts, order-independent: the visible side may repeat a
    // passage when it spans category sections (the writer re-emits the
    // block per section so members bind within their section), and the
    // payload's list order is the exporter's, not the document's first-
    // use order. Membership is matched by content canon, not position.
    if (payload.passages.map { canonPassage(it) }.distinct().sorted() !=
        visibleFile.passages.map { canonPassage(it) }.distinct().sorted()
    ) {
        return stale("passages")
    }
    val payloadPassageCanons = payload.passages.associate { it.id to canonPassage(it) }
    val visiblePassageCanons = visibleFile.passages.associate { it.id to canonPassage(it) }

    if (payloadQuestions.isEmpty() || visibleQuestions.isEmpty()) return visible
    val payloadCanon = payloadQuestions.map { canonQuestion(it, payloadPassageCanons) }
    val visibleCanon = visibleQuestions.map { canonQuestion(it, visiblePassageCanons) }

    // Subsequence walk: every visible question must fingerprint-match a
    // payload question, in order; payload-only questions may sit between.
    var pi = 0
    val matchedVisible = mutableMapOf<Int, QuestionDto>()
    var matched = 0
    for (visibleQuestion in visibleQuestions) {
        val canon = visibleCanon[matched]
        while (pi < payloadCanon.size && payloadCanon[pi] != canon) pi++
        if (pi == payloadCanon.size) return stale("question ${matched + 1} canon mismatch")
        matchedVisible[pi] = visibleQuestion
        pi++
        matched++
    }

    // Graft image srcs from the body onto the (stripped) payload, in the
    // same traversal order the fingerprint walk used.
    var flatIndex = 0
    fun graftList(questions: List<QuestionDto>): List<QuestionDto> = questions.map { q ->
        val visibleQuestion = matchedVisible[flatIndex]
        flatIndex++
        if (visibleQuestion != null) graftQuestion(q, visibleQuestion) else q
    }
    val graftedPapers = payload.papers.map { paper ->
        paper.copy(
            categories = paper.categories.map { c -> c.copy(questions = graftList(c.questions)) },
            questions = graftList(paper.questions)
        )
    }
    val visibleByCanon = visibleFile.passages.associateBy { canonPassage(it) }
    val graftedPassages = payload.passages.map { passage ->
        visibleByCanon[canonPassage(passage)]?.let { graftPassage(passage, it) } ?: passage
    }
    val graftedFile = payload.copy(papers = graftedPapers, passages = graftedPassages)
        .sweepResidualDescriptors()
    val graftedJson = kotlinx.serialization.json.Json.encodeToString(
        McqFileDto.serializer(),
        graftedFile
    )
    return DocxParseResult(graftedJson, warnings, payloadTitle ?: visible.paperTitle)
}

/**
 * Freshness key for one field (stem, option, explanation, passage body):
 * every element reduced to a content-only canon — text normalized like the
 * stems, math to its leaf text (the OMML round trip restructures the markup,
 * so tag shape cannot be compared), images to a bare position marker (the
 * src is grafted, never compared), tables to their cells' visible text with a
 * position marker for every picture and a formula canon that ignores tag
 * shape. A non-null legacy scalar image counts as a trailing position marker:
 * the writer embeds it after the element images, so the visible side has one
 * more image element than the payload's element list.
 *
 * Formula *structure* is intentionally not part of freshness: math is reduced
 * to its leaf text, so a fraction and a row with the same leaves compare
 * equal. A truncated or corrupt payload never reaches this comparison —
 * `preferPayload` catches the parse failure and uses the visible side.
 *
 * A formula [mathMlToOmml] refuses is typed as text (`t:`), not math: the
 * writer prints its linear text in the same run, so the visible parse sees
 * prose. Typing it `m:` made payload and visible disagree for a formula Word
 * cannot carry (`msubsup`, `mtable`, `mfenced`, …) and dropped the payload —
 * with the ids, marks, difficulty and tags it holds — on an untouched export.
 */
private fun canonField(elements: List<ContentElement>, scalarImage: String?): String {
    if (elements.isEmpty() && scalarImage == null) return ""
    // Consecutive text elements merge: the visible parse splits runs on
    // markup boundaries the payload's stored elements do not share, so
    // only the text itself — not its element packing — is compared.
    val parts = mutableListOf<String>()
    val text = StringBuilder()
    fun flushText() {
        if (text.isNotEmpty()) {
            // A whitespace-only run is no content: the visible parse leaves the
            // newline between a table and the paragraph after it as its own
            // text element, and the canon must not count that as a part the
            // payload does not have — an untouched card whose back mixes a
            // table with a formula read as edited because of it.
            val normalized = normalizeStem(text.toString())
            if (normalized.isNotEmpty()) parts.add("t:$normalized")
            text.clear()
        }
    }
    for (element in elements) {
        when (element) {
            is ContentElement.TextElement -> text.append(element.text)
            is ContentElement.MathElement -> {
                if (mathMlToOmml(element.mathml) == null) {
                    // The writer cannot express this formula as OMML and
                    // prints its linear text into the same run; the visible
                    // side is prose, so canon it as prose too.
                    text.append(mathMlLinearText(element.mathml))
                } else {
                    flushText()
                    parts.add("m:" + canonMathLeaves(element.mathml))
                }
            }
            is ContentElement.ImageElement -> {
                flushText()
                // A remote URL rides only in the payload (the document body
                // cannot carry it), so it is metadata like marks — not part
                // of the freshness comparison.
                if (isGraftableImage(element.src)) parts.add("i")
            }
            is ContentElement.TableElement -> {
                flushText()
                parts.add("tbl:" + canonTable(element.rows))
            }
            is ContentElement.ChemElement -> {
                // The writer prints the handles as a text paragraph. Adjacent
                // paragraphs merge on the visible side, so the payload's
                // handles join the same text buffer rather than starting a
                // second `t:` part — a stem of "Identify:" + a structure must
                // canon to one run, not two. The PNG, when present, is a real
                // picture and breaks the run after the text is flushed.
                // Spoken is an accessibility label and is never printed.
                val handles = chemElementText(element)
                if (handles.isNotBlank()) text.append(handles)
                if (isPngDataUri(element.diagram)) {
                    flushText()
                    parts.add("i")
                }
            }
        }
    }
    flushText()
    if (scalarImage != null && isGraftableImage(scalarImage)) parts.add("i")
    return parts.joinToString(",")
}

/**
 * One table's freshness canon: row and cell boundaries are kept (a merge or a
 * moved cell is an edit), each cell is reduced to its visible content, and the
 * writer's row padding — it always emits `cols` cells per row — is ignored by
 * dropping the trailing empty cells the payload never had. Without the
 * per-cell walk, a picture in a cell changed the canon between a descriptor
 * (payload) and the picture the body draws, and a formula's OMML round trip
 * restructured its tags: every such export read as edited and lost its marks,
 * difficulty and tags.
 *
 * The encoding is length-prefixed (a netstring): the row count, each row's
 * cell count and each cell canon's length are part of the string, so prose
 * containing any separator byte (`\u001E`, `\u001F`, `,`) can never forge a
 * row, cell or part boundary. Text parts are typed (`t:`), so a prose `i` can
 * never equal a picture marker. Every picture part carries its SHA-256
 * (`i:<len>:<hash>`), so swapping a structure drawing with an adjacent picture
 * cannot pass the canon even though both still count as one picture.
 *
 * A Word edit makes the payload stale by design (same as every other hidden
 * DOCX field): the visible table then wins, so a structure degrades to its
 * readable handles and a cell formula to its MathML, and the payload-only
 * spec/schedule metadata is dropped. Pure markup differences are not edits —
 * tags are stripped and whitespace is normalized, so `<b>` versus the reader's
 * `<strong>` or a re-tokenised OMML tree still compares equal. Formula
 * *structure* is intentionally not compared either: math is reduced to its
 * leaf text, so a fraction and a row with the same leaves read as unedited.
 * A truncated or corrupt payload never reaches this comparison — the payload
 * parser catches the failure and the visible side is used.
 *
 * Text normalization is [normalizeStem]: lowercased and stripped of all
 * whitespace. A spacing- or case-only edit inside a cell ("1 2" versus "12",
 * "NaCl" versus "nacl") therefore reads as unedited and the payload is kept.
 * This is pre-existing and intentional for stems; do not change it without the
 * owner.
 */
internal fun canonTable(rows: List<List<String>>): String = buildString {
    append(rows.size).append(':')
    for (row in rows) {
        val cells = row.map(::canonCell).dropLastWhile { it.isEmpty() }
        append(cells.size).append(':')
        for (cell in cells) {
            append(cell.length).append(':').append(cell)
        }
    }
}

/**
 * One cell's freshness canon. A chemical structure contributes its readable
 * handles plus one picture position when it has a valid PNG (or the DOCX
 * payload's descriptor for it); prose and math follow the body's rules (tags
 * stripped, whitespace removed, math by leaf text; a formula the writer must
 * linearise is text), and every picture the writer embeds contributes one `i`.
 * A non-embeddable data picture contributes the same visible `[image]` text the
 * writer prints; a remote/file/empty source or a tag with no readable source
 * contributes nothing on either side.
 *
 * Every text and math part is length-prefixed with its type (`t:<len>:…`,
 * `m:<len>:…`) so no text can mimic a picture marker or a part boundary.
 */
internal fun canonCell(cell: String): String {
    val parts = mutableListOf<String>()
    val text = StringBuilder()
    fun flush() {
        if (text.isEmpty()) return
        val normalized = normalizeStem(text.toString().replace(Regex("<[^>]*>"), ""))
        if (normalized.isNotEmpty()) parts.add(canonPart("t", normalized))
        text.clear()
    }
    for (segment in cellSegments(cell)) {
        when (segment) {
            is CellSegment.Chem -> {
                text.append(chemElementText(segment.element))
                // A payload descriptor is the same picture the visible cell
                // draws; its hash must count at the same position.
                val hash = when {
                    isPngDataUri(segment.element.diagram) ->
                        imageSrcHash(segment.element.diagram)
                    segment.pngDescriptor != null -> imageSrcHash(segment.pngDescriptor)
                    else -> null
                }
                if (hash != null) {
                    flush()
                    parts.add(canonPart("i", hash))
                }
            }
            is CellSegment.Text -> for (part in tableCellParts(segment.html)) {
                when (part) {
                    is CellPart.Math -> {
                        if (mathMlToOmml(part.mathml) == null) {
                            // The cell writer falls back to the same linear
                            // text in the same paragraph, so this formula is
                            // prose on the visible side.
                            text.append(mathMlLinearText(part.mathml))
                        } else {
                            flush()
                            parts.add(canonPart("m", canonMathLeaves(part.mathml)))
                        }
                    }
                    is CellPart.Text -> {
                        var pos = 0
                        forEachDocxCellImage(part.html) { tag, src, start, end ->
                            if (start > pos) text.append(part.html, pos, start)
                            when {
                                src != null &&
                                    (isImageSrcDescriptor(src) || portableCellImageSrc(src)) -> {
                                    flush()
                                    parts.add(canonPart("i", imageSrcHash(src).orEmpty()))
                                }
                                // A non-embeddable data picture is printed as
                                // the marker by the writer, so the payload must
                                // contribute the same visible text.
                                tag != null &&
                                    imgTagSrc(tag)?.startsWith("data:", ignoreCase = true) == true ->
                                    text.append(CELL_IMAGE_MARKER)
                                // Remote/file/empty/no-source: no visible picture.
                            }
                            pos = end
                        }
                        if (pos < part.html.length) {
                            text.append(part.html, pos, part.html.length)
                        }
                    }
                }
            }
        }
    }
    flush()
    return parts.joinToString(",")
}

/** A typed, length-prefixed canon part: the length makes the encoding injective. */
private fun canonPart(type: String, value: String): String = "$type:${value.length}:$value"

/**
 * A formula's leaf text: character data only, entities decoded and layout
 * whitespace removed. The OMML round trip restructures tags, so only the
 * leaves are comparable.
 */
private fun canonMathLeaves(mathml: String): String =
    decodeXmlEntities(mathml)
        .replace(Regex("<[^>]*>"), "")
        .replace(Regex("\\s+"), "")

/**
 * Image srcs the document body can carry: a descriptor for an embedded data
 * URI, the empty string a non-portable top-level data URI was blanked to, or a
 * legacy full data URI. Remote URLs and `file:` references stay payload-only
 * metadata and are neither compared nor overwritten.
 */
private fun isGraftableImage(src: String): Boolean =
    isImageSrcDescriptor(src) || src.isEmpty() || src.startsWith("data:", ignoreCase = true)

private fun canonPassage(passage: PassageDto): String =
    normalizeStem(passage.title) + "|" + canonField(passage.elements, passage.image)

/**
 * One question's freshness key. Options are compared by content in order
 * (ids are derived letters on the visible side and may be arbitrary in the
 * payload); the answer key by the *indices* of the correct options, capped
 * at what the marker format can carry, so a payload holding more options
 * than the ten `(a)`…`(j)` markers does not read as edited. Passage
 * membership is an index into the file's passage list, so the two sides'
 * id schemes (original vs. section-qualified) never collide.
 */
private fun canonQuestion(q: QuestionDto, passageCanons: Map<String, String>): String {
    val stem = canonText(q.elements, q.image, q.text)
    val options = q.options.take(10).joinToString(",") { option ->
        canonText(option.elements, option.image, option.text)
    }
    val correctIndices = q.options.withIndex()
        .filter { it.value.id in q.correctOptionIds }
        .map { it.index }
        .filter { it < 10 }
        .distinct()
        .sorted()
    // The writer resolves an answerless question to its first option (with
    // a warning), so the visible side always names one; mirror that here
    // or every untouched export of such a question reads as edited.
    val correct = if (correctIndices.isEmpty() && q.options.isNotEmpty()) {
        "0"
    } else {
        correctIndices.joinToString(",")
    }
    val explanation = canonText(q.explanationElements, q.explanationImage, q.explanation)
    // Membership as the passage's content canon, not its id: the two
    // sides' id schemes differ (original vs. section-qualified), and a
    // passage spanning sections appears as a per-section copy.
    val membership = if (q.passageId.isNullOrEmpty()) "" else passageCanons[q.passageId] ?: "?"
    return listOf(stem, options, correct, explanation, "P:$membership").joinToString("|")
}

/**
 * A blank field canon: the importer stores blank text as one empty
 * TextElement, so the payload's element list is non-empty and [canonField]
 * reduces it to ""; the visible parse of a missing marker yields no
 * elements at all. Fold a plain-text fallback in the same way so both
 * sides of a blank field canon to "" (or just the image marker) —
 * otherwise an untouched re-export reads as edited.
 */
private fun canonText(
    elements: List<ContentElement>,
    scalarImage: String?,
    text: String
): String = canonField(
    elements.ifEmpty {
        listOfNotNull(text.takeIf { it.isNotBlank() }?.let { ContentElement.TextElement(it) })
    },
    scalarImage
)

/** Restore the image srcs the writer replaced with descriptors, in body order. */
internal fun graftElements(
    payloadElements: List<ContentElement>,
    visibleElements: List<ContentElement>
): List<ContentElement> {
    val srcs = visibleElements.filterIsInstance<ContentElement.ImageElement>()
        .map { it.src }
        .filter { isGraftableImage(it) }
    val visibleTables = visibleElements.filterIsInstance<ContentElement.TableElement>()
    var next = 0
    var nextTable = 0
    val out = mutableListOf<ContentElement>()
    for (element in payloadElements) {
        when {
            // The writer draws a structure's PNG inline; it occupies one body
            // picture even though the payload marker has no <img>. Verify the
            // visible bytes at that slot: if the structure drawing and an
            // adjacent picture were reordered, refuse the whole field rather
            // than assign the wrong bytes.
            element is ContentElement.ChemElement && isPngDataUri(element.diagram) -> {
                val expected = imageSrcHash(element.diagram)
                val visible = srcs.getOrNull(next)
                if (expected == null || visible == null || imageSrcHash(visible) != expected) {
                    return visibleElements
                }
                next++
                out += element
            }
            element is ContentElement.ImageElement && isGraftableImage(element.src) -> {
                val visible = srcs.getOrNull(next)
                next++
                if (visible == null) {
                    out += element
                } else {
                    val resolved = if (isImageSrcDescriptor(element.src)) {
                        resolveImageSrcDescriptor(element.src, visible)
                    } else {
                        visible
                    }
                    out += if (resolved != null) element.copy(src = resolved) else element
                }
            }
            element is ContentElement.TableElement -> {
                val visibleRows = visibleTables.getOrNull(nextTable)?.rows
                nextTable++
                val rows = if (visibleRows != null) {
                    graftTable(element.rows, visibleRows)
                } else {
                    element.rows.map { row -> row.map(::dropResidualDescriptors) }
                }
                // Defensive degrade, never a throw: if a descriptor somehow
                // survived, use the visible table (or drop the tag cell by
                // cell) rather than storing it.
                val safeRows = if (rows.any { row -> row.any(::containsImageDescriptor) }) {
                    (visibleRows ?: rows).map { row ->
                        row.map { cell ->
                            if (containsImageDescriptor(cell)) "" else cell
                        }
                    }
                } else {
                    rows
                }
                out += element.copy(rows = safeRows)
            }
            else -> out += element
        }
    }
    // Fail closed: a descriptor that could not be resolved must never reach
    // storage; the visible field is the truth in that case.
    return if (out.any { it is ContentElement.ImageElement && isImageSrcDescriptor(it.src) }) {
        visibleElements
    } else {
        out
    }
}

/**
 * Restore each cell's stripped picture srcs from the visible table's same
 * row/column, in order. Positional and defensive on purpose: a table the user
 * restructured in Word makes the freshness canon stale and the visible body
 * wins, so a mismatch here must never throw or move a picture into another
 * cell — an unresolvable descriptor degrades to the visible cell.
 */
internal fun graftTable(
    payloadRows: List<List<String>>,
    visibleRows: List<List<String>>
): List<List<String>> = payloadRows.mapIndexed { rowIndex, row ->
    val visibleRow = visibleRows.getOrNull(rowIndex)
    if (visibleRow == null) {
        // Defensive: a fresh payload always has the visible row. If it does
        // not, no descriptor may survive this path.
        return@mapIndexed row.map { cell ->
            val dropped = dropResidualDescriptors(cell)
            if (containsImageDescriptor(dropped)) "" else dropped
        }
    }
    row.mapIndexed { cellIndex, cell ->
        val visibleCell = visibleRow.getOrNull(cellIndex).orEmpty()
        val grafted = graftCell(cell, visibleCell)
        if (containsImageDescriptor(grafted)) visibleCell else grafted
    }
}

/**
 * One cell's picture tags restored from the visible cell's pictures, in
 * document order, by picture slot rather than by searching for segment text:
 * `cellSegments` rebuilds a marker that starts inside a protected range (a
 * complete tag or `<math>`), so its text is not a substring of the payload.
 * Instead every chem marker with a valid PNG (or the payload descriptor that
 * replaced it) is counted from [cellChemSpans] and verified against the
 * visible picture at that slot; the next `<img>` consumes the following one.
 * A descriptor's hash must match the visible bytes before its exact
 * `data-png` payload is put back. A hash mismatch refuses the graft for the
 * cell (the visible cell wins), and a descriptor that survives the walk always
 * falls back to the visible cell — a stored cell must never keep a
 * `mcqapp:img|` src or a `data-png` descriptor.
 */
private fun graftCell(payload: String, visible: String): String {
    val chemSpans = cellChemSpans(payload)
    val hasChemDescriptor = chemSpans.any { it.pngDescriptor != null }
    if (!payload.contains("<img", ignoreCase = true) && !hasChemDescriptor) return payload
    val srcs = mutableListOf<String>()
    forEachCellImage(visible) { src, _, _ ->
        if (src != null && src.startsWith("data:", ignoreCase = true)) srcs.add(src)
    }
    val spans = mutableListOf<CellImageSpan>()
    forEachCellImageSpan(payload) { tag, start, end ->
        if (tag != null) spans.add(CellImageSpan(tag, start, end))
    }
    if (spans.isEmpty() && !hasChemDescriptor) return payload

    var next = 0
    var chemIndex = 0
    val replacements = mutableListOf<Triple<Int, Int, String>>()

    /** Consume and verify every chem picture that precedes [upTo]. */
    fun consumeChemPictures(upTo: Int): Boolean {
        while (chemIndex < chemSpans.size && chemSpans[chemIndex].start < upTo) {
            val chem = chemSpans[chemIndex++]
            val descriptor = chem.pngDescriptor
            if (descriptor != null) {
                val expected = imageSrcHash(descriptor) ?: return false
                val visibleSrc = srcs.getOrNull(next) ?: return false
                if (imageSrcHash(visibleSrc) != expected) return false
                next++
                // The descriptor held the exact header, padding and tail, so
                // this is the byte-identical payload the writer stripped.
                val resolved = resolveImageSrcDescriptor(descriptor, visibleSrc) ?: return false
                val payloadBody = resolved.substringAfter(',', "")
                if (payloadBody.isEmpty()) return false
                if (chem.pngValueStart < 0 || chem.pngValueEnd <= chem.pngValueStart) return false
                replacements += Triple(chem.pngValueStart, chem.pngValueEnd, payloadBody)
                continue
            }
            if (!isPngDataUri(chem.element.diagram)) continue
            val expected = imageSrcHash(chem.element.diagram) ?: return false
            val visibleSrc = srcs.getOrNull(next) ?: return false
            if (imageSrcHash(visibleSrc) != expected) return false
            next++
        }
        return true
    }

    for (span in spans) {
        if (!consumeChemPictures(span.start)) return visible
        val src = docxCellImageSrc(span.tag) ?: continue
        if (!isGraftableCellImage(src)) continue
        val visibleSrc = srcs.getOrNull(next) ?: continue
        next++
        val rewritten = if (isImageSrcDescriptor(src)) {
            resolveImageSrcDescriptor(src, visibleSrc)?.let {
                withDocxCellImageSrc(span.tag, it)
            }
        } else {
            withDocxCellImageSrc(span.tag, visibleSrc)
        } ?: continue
        replacements += Triple(span.start, span.end, rewritten)
    }
    if (!consumeChemPictures(payload.length)) return visible
    if (replacements.isEmpty()) {
        return if (containsImageDescriptor(payload)) visible else payload
    }
    val sb = StringBuilder(payload)
    for ((start, end, text) in replacements.asReversed()) sb.replace(start, end, text)
    val result = sb.toString()
    return if (containsImageDescriptor(result)) visible else result
}

/**
 * True when a cell still carries a picture the body was supposed to resolve:
 * an `<img>` descriptor or a chem marker's `data-png` descriptor. Such a cell
 * must never reach storage (see [graftCell]).
 */
private fun containsImageDescriptor(cell: String): Boolean {
    var found = false
    forEachCellImageSpan(cell) { tag, _, _ ->
        val src = tag?.let(::docxCellImageSrc)
        if (src != null && isImageSrcDescriptor(src)) found = true
    }
    if (found) return true
    return cellChemSpans(cell).any { it.pngDescriptor != null }
}

/**
 * [cell] with every descriptor removed or degraded to its visible text. The
 * last-resort path when no visible cell can resolve it: a descriptor must
 * never reach storage, so a picture position is dropped rather than stored as
 * `mcqapp:img|…`, and a structure whose raster cannot be restored becomes its
 * readable handles (exactly what a text-only surface shows).
 */
private fun dropResidualDescriptors(cell: String): String {
    if (!containsImageDescriptor(cell)) return cell
    val replacements = mutableListOf<Triple<Int, Int, String>>()
    for (span in cellChemSpans(cell)) {
        if (span.pngDescriptor != null) {
            replacements += Triple(
                span.start,
                span.end,
                escapeHtmlText(chemElementText(span.element))
            )
        }
    }
    forEachCellImageSpan(cell) { tag, start, end ->
        val src = tag?.let(::docxCellImageSrc)
        if (tag != null && src != null && isImageSrcDescriptor(src)) {
            replacements += Triple(start, end, "")
        }
    }
    if (replacements.isEmpty()) return cell
    replacements.sortBy { it.first }
    val sb = StringBuilder(cell.length)
    var pos = 0
    for ((start, end, text) in replacements) {
        if (start < pos) continue
        sb.append(cell, pos, start).append(text)
        pos = end
    }
    sb.append(cell, pos, cell.length)
    return sb.toString()
}

private data class CellImageSpan(val tag: String, val start: Int, val end: Int)

/**
 * A cell picture position the writer actually embeds: a stored descriptor, or
 * a legacy full/empty data URI. A non-embeddable data URI, a remote URL or a
 * tag with no readable source is payload-only metadata: the body cannot draw
 * it, so it must not consume a body picture during grafting.
 */
private fun isGraftableCellImage(src: String): Boolean =
    isImageSrcDescriptor(src) || portableCellImageSrc(src)

/**
 * The writer embeds a legacy scalar image after the element images, so the
 * visible side's last image element is the scalar one when the payload had
 * both (or just the scalar).
 */
private fun graftQuestion(payload: QuestionDto, visible: QuestionDto): QuestionDto = payload.copy(
    elements = graftElements(payload.elements, visible.elements),
    image = graftScalarImage(payload.image, visible.elements),
    options = payload.options.mapIndexed { index, option ->
        val visibleOption = visible.options.getOrNull(index)
        option.copy(
            elements = if (visibleOption != null) {
                graftElements(option.elements, visibleOption.elements)
            } else {
                option.elements
            },
            image = graftScalarImage(option.image, visibleOption?.elements ?: emptyList())
        )
    },
    explanationElements = graftElements(payload.explanationElements, visible.explanationElements),
    explanationImage = graftScalarImage(payload.explanationImage, visible.explanationElements)
)

private fun graftPassage(payload: PassageDto, visible: PassageDto): PassageDto = payload.copy(
    elements = graftElements(payload.elements, visible.elements),
    image = graftScalarImage(payload.image, visible.elements)
)

/** A scalar descriptor/legacy src takes the body's last image; metadata stays. */
private fun graftScalarImage(scalar: String?, visibleElements: List<ContentElement>): String? {
    if (scalar == null || !isGraftableImage(scalar)) return scalar
    val visible = visibleElements.filterIsInstance<ContentElement.ImageElement>()
        .lastOrNull { isGraftableImage(it.src) }?.src
        ?: return if (isImageSrcDescriptor(scalar)) null else scalar
    return if (isImageSrcDescriptor(scalar)) {
        resolveImageSrcDescriptor(scalar, visible) ?: visible
    } else {
        visible
    }
}

/**
 * Last-resort guarantee: no `mcqapp:img|…` descriptor may survive a graft,
 * whichever path skipped it (an option beyond the writer's ten, a payload-only
 * question, a missing visible row/table/passage, or a scalar with no visible
 * picture). Runs once on the whole DTO before it is serialized.
 */
private fun McqFileDto.sweepResidualDescriptors(): McqFileDto = copy(
    papers = papers.map { paper ->
        paper.copy(
            categories = paper.categories.map { category ->
                category.copy(questions = category.questions.map(::sweepQuestion))
            },
            questions = paper.questions.map(::sweepQuestion),
            passages = paper.passages.map(::sweepPassage)
        )
    },
    passages = passages.map(::sweepPassage)
)

private fun sweepPassage(passage: PassageDto): PassageDto = passage.copy(
    elements = sweepElements(passage.elements),
    image = sweepScalar(passage.image)
)

private fun sweepQuestion(question: QuestionDto): QuestionDto = question.copy(
    elements = sweepElements(question.elements),
    image = sweepScalar(question.image),
    options = question.options.map { option ->
        option.copy(
            elements = sweepElements(option.elements),
            image = sweepScalar(option.image)
        )
    },
    explanationElements = sweepElements(question.explanationElements),
    explanationImage = sweepScalar(question.explanationImage)
)

private fun sweepElements(elements: List<ContentElement>): List<ContentElement> =
    elements.mapNotNull { element ->
        when (element) {
            // Drop rather than blank: an empty image src is still a picture
            // position to `isGraftableImage`, and a future canon change must
            // not make a swept file stale.
            is ContentElement.ImageElement ->
                element.takeUnless { isImageSrcDescriptor(it.src) }
            is ContentElement.TableElement ->
                element.copy(rows = element.rows.map { row -> row.map(::dropResidualDescriptors) })
            else -> element
        }
    }

private fun sweepScalar(src: String?): String? =
    if (src != null && isImageSrcDescriptor(src)) null else src

/**
 * Stem text for the payload-freshness comparison: entities decoded (the
 * visible text always decodes them; the payload stores what was authored),
 * every whitespace removed — the visible side joins stacked text elements
 * with a newline the payload does not have — and case folded. A freshness
 * key, not an identity: two stems that differ only in spacing are the same
 * question for this purpose.
 *
 * Markup breaks normalise before the entities decode, so `<br>`, `<br/>` and
 * `<br />` are a break the same way the visible document's `\n` is one. Decoding
 * after the replace is what keeps an *authored* `&lt;br&gt;` literal: it must
 * not be read as a break merely because it looks like the markup.
 */
private fun normalizeStem(text: String): String =
    text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        .let(::decodeXmlEntities)
        .replace(Regex("\\s+"), "")
        .lowercase()

internal data class DocxLine(val html: String, val heading: Int?)

/**
 * The paragraph style to a heading level: `Heading1`…`Heading9` (Word's
 * style ids, with LibreOffice's `Heading_20_1` spelling tolerated) and
 * `Title` count as level 1. Anything else is ordinary text.
 */
internal fun headingLevelOf(style: String?): Int? {
    if (style == null) return null
    val trimmed = style.trim()
    if (trimmed.equals("Title", ignoreCase = true)) return 1
    val match = Regex("(?i)^heading[ _]?(?:20[ _]?)?(\\d+)$").find(trimmed) ?: return null
    val level = match.groupValues[1].toIntOrNull() ?: return null
    return if (level in 1..9) level else null
}

/** A table block to the HTML line the marker pass understands. */
internal fun tableHtml(block: DocxBlock.Table): String {
    val sb = StringBuilder("<table border=\"1\">")
    for (row in block.rows) {
        sb.append("<tr>")
        for (cell in row) sb.append("<td>").append(cell).append("</td>")
        sb.append("</tr>")
    }
    sb.append("</table>")
    return sb.toString()
}

/** The original path: no headings, one implicit section, wrapper JSON. */
private fun parseFlatDocx(texts: List<String>, warnings: List<String>): DocxParseResult {
    val text = StringBuilder()
    for (html in texts) text.append(html).append('\n')
    // Collapse blank lines like the pipeline's cleanup: markers must
    // start lines, stray empties are noise.
    val cleaned = text.toString().replace(Regex("\n{2,}"), "\n")
    val (passages, cleanedQuestions, anchors) = extractPassages(cleaned)
    val questions = extractQuestions(cleanedQuestions, anchors)
    val array = buildJsonArray {
        for (q in questions) add(questionJson(q))
    }
    val passageArray = passageJsonArray(passages, categoryId = null)
    // Passages ride top-level; the questions JSON stays the array the
    // importer already parses.
    val wrapper = if (passages.isEmpty()) {
        array.toString()
    } else {
        buildJsonObject {
            put("questions", array)
            put("passages", passageArray)
        }.toString()
    }
    return DocxParseResult(wrapper, warnings)
}

private data class Section(val level: Int?, val titleHtml: String, val lines: MutableList<String>)

/**
 * Headings to categories: each heading opens a section; its level sets the
 * category's depth (Heading 2 = top level, Heading 3 = its subcategory, …),
 * so a recursive tree maps onto nothing but ordinary Word styles. The first
 * Title/Heading 1 names the paper. Questions and passages join the section
 * they sit under; anything before the first heading goes to the
 * [FALLBACK_CATEGORY_TITLE] fallback.
 */
private fun parseSectionedDocx(lines: List<DocxLine>, warnings: List<String>): DocxParseResult {
    val sections = mutableListOf<Section>()
    for (line in lines) {
        if (line.heading != null) {
            sections.add(Section(line.heading, line.html, mutableListOf()))
        } else {
            if (sections.isEmpty()) sections.add(Section(null, "", mutableListOf()))
            sections.last().lines.add(line.html)
        }
    }

    // Categories as plain records: the questions are RawQuestions (they
    // become JSON per question via questionJson), so the DTO type does not
    // fit until LegacyParser sees the file.
    data class CatOut(val id: String, val title: String, val parentId: String?)
    val categories = mutableListOf<CatOut>()
    val stack = ArrayDeque<Pair<String, Int>>()
    var paperTitle: String? = null
    var catCounter = 0
    data class SectionResult(
        val categoryId: String?,
        val questions: List<RawQuestion>,
        val passages: List<RawPassage>
    )

    val results = sections.mapIndexed { index, section ->
        var categoryId: String? = null
        if (section.level != null) {
            val title = stripTags(section.titleHtml).trim()
            if (section.level == 1 && paperTitle == null && categories.isEmpty()) {
                paperTitle = title
            } else {
                val depth = if (section.level == 1) 0 else section.level - 2
                while (stack.isNotEmpty() && stack.last().second >= depth) stack.removeLast()
                catCounter++
                val id = "doc-cat-$catCounter"
                categories.add(
                    CatOut(
                        id = id,
                        title = title.ifBlank { FALLBACK_CATEGORY_LABEL },
                        parentId = stack.lastOrNull()?.first
                    )
                )
                stack.addLast(id to depth)
                categoryId = id
            }
        }
        val cleaned = section.lines.joinToString("\n")
            .replace(Regex("\n{2,}"), "\n")
        var sectionQuestions: List<RawQuestion> = emptyList()
        var sectionPassages: List<RawPassage> = emptyList()
        if (cleaned.isNotBlank()) {
            val (passages, rest, anchors) = extractPassages(cleaned)
            // Passage ids are minted per section ("passage-1"); with several
            // sections they would collide, so they are qualified with the
            // section number. One section keeps the bare names, so the flat
            // path's output shape is unchanged.
            val idMap = if (sections.size > 1) {
                passages.mapIndexed { pIndex, p ->
                    p.id to "passage-${index + 1}-${pIndex + 1}"
                }.toMap()
            } else {
                emptyMap()
            }
            sectionPassages = passages.map { it.copy(id = idMap[it.id] ?: it.id) }
            if (questionSplit.containsMatchIn(rest)) {
                sectionQuestions = extractQuestions(rest, anchors).map { q ->
                    if (q.passageId.isEmpty()) q
                    else q.copy(passageId = idMap[q.passageId] ?: q.passageId)
                }
            }
        }
        SectionResult(categoryId, sectionQuestions, sectionPassages)
    }

    val allQuestions = results.flatMap { it.questions }
    if (allQuestions.isEmpty()) {
        val anyText = sections.any { s -> s.lines.any { it.isNotBlank() } }
        throw IllegalArgumentException(
            if (!anyText) {
                "No questions found: the document is empty."
            } else {
                "No questions found: expected 'N.)' numbered questions with " +
                    "'(a)..(d)', 'Ans.' and 'Exp:' markers, or a card's 'Answer:' back."
            }
        )
    }

    // Questions before any heading (or under the bare title) get their own
    // section, matching what the flat path builds.
    val ungrouped = results.filter { it.categoryId == null }.flatMap { it.questions }
    if (ungrouped.isNotEmpty()) {
        categories.add(CatOut("doc-cat-ungrouped", FALLBACK_CATEGORY_TITLE, null))
    }
    val paperJson = buildJsonObject {
        if (paperTitle != null) put("title", JsonPrimitive(paperTitle))
        put("categories", buildJsonArray {
            for (category in categories) {
                val own = if (category.id == "doc-cat-ungrouped") {
                    ungrouped
                } else {
                    results.filter { it.categoryId == category.id }.flatMap { it.questions }
                }
                add(buildJsonObject {
                    put("id", JsonPrimitive(category.id))
                    put("title", JsonPrimitive(category.title))
                    category.parentId?.let { put("parentId", JsonPrimitive(it)) }
                    put("questions", buildJsonArray {
                        for (q in own) add(questionJson(q))
                    })
                })
            }
        })
    }
    val passageArray = buildJsonArray {
        for ((index, result) in results.withIndex()) {
            val categoryId = result.categoryId
            for (p in result.passages) {
                add(passageJson(p, categoryId))
            }
        }
    }
    val wrapper = buildJsonObject {
        put("version", JsonPrimitive(1))
        put("papers", buildJsonArray { add(paperJson) })
        put("passages", passageArray)
    }
    return DocxParseResult(wrapper.toString(), warnings, paperTitle)
}

private fun passageJsonArray(passages: List<RawPassage>, categoryId: String?): kotlinx.serialization.json.JsonArray =
    buildJsonArray {
        for (p in passages) add(passageJson(p, categoryId))
    }

private fun passageJson(p: RawPassage, categoryId: String?): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(p.id))
    put("title", JsonPrimitive(passageTitle(p)))
    categoryId?.let { put("categoryId", JsonPrimitive(it)) }
    put("elements", buildJsonArray {
        for (el in fieldElements(p.bodyHtml)) add(elementJson(el))
    })
}

/** The one-line title after `Passage:`; blank falls back to a numbered default. */
private fun passageTitle(p: RawPassage): String {
    val plain = stripTags(p.titleHtml).trim()
    return plain.ifBlank { FALLBACK_PASSAGE_TITLE }
}

private fun stripTags(html: String): String =
    Regex("<[^>]*>").replace(html, "")

internal data class RawQuestion(
    val num: String,
    val stemHtml: String,
    val options: Map<String, String>,
    val answer: String,
    val explanationHtml: String,
    /**
     * Option ids when the answer named letters directly, which is how several
     * correct answers are expressed. Empty for the older forms that name the
     * option's text or a zero-based index, and for the invalid forms that are
     * refused outright.
     */
    val correctIds: List<String> = emptyList(),
    /**
     * Id of the `Passage:` block this question follows, when one does. Empty
     * for standalone questions, which keeps every pre-marker question exactly
     * as it was.
     */
    val passageId: String = ""
)

/**
 * One `Passage:` block from the document: the context text shared by the
 * questions that follow it, until the next marker or the end.
 */
internal data class RawPassage(
    val id: String,
    val titleHtml: String,
    val bodyHtml: String
)

/**
 * The line-start marker grammar, as fragments: the parser splits on these and
 * the writer escapes a line that matches them, so both must come from here.
 * Keeping one definition is what stops the writer's guard and the parser's
 * grammar from drifting apart again.
 */
private const val QUESTION_MARKER = "\\d{1,7}\\.\\)"
private const val OPTION_MARKER = "\\(([a-z])\\)"
private const val ANSWER_MARKER = "Ans."
private const val EXPLANATION_MARKER = "Exp:"

/**
 * The label a flashcard's back carries: a card has no options, so what
 * follows is the answer itself, not a note about an option. The app stores a
 * card's back in the explanation field, so reading this marker fills
 * `explanation_elements` — the one place a card's back lives.
 */
private const val CARD_BACK_MARKER = "Answer:"

/**
 * The marker that ends a card's stem: strictly the writer's own `Answer:`
 * label. A no-option block carrying `Ans.` is **not** a card — `Ans.` means
 * "the answer key of these options", so a hand-written multiple-choice
 * question whose options were not on their own lines must keep failing as a
 * malformed MCQ instead of importing as a card with its options swallowed
 * into the stem. `Exp:` is excluded for the same reason.
 */
private val cardBackSplit = Regex("(?m)^${Regex.escape(CARD_BACK_MARKER)}")

/** `Passage:` is the one case-insensitive marker: authors need not match its casing. */
private const val PASSAGE_MARKER = "(?i:Passage:)"

private val questionSplit = Regex("(?m)^($QUESTION_MARKER)")

/**
 * Line-start `Passage:` marker (D4). Fits the existing `(?m)^` marker style;
 * question stems always start with `N.)`, so the two cannot collide. A case-
 * insensitive match spares authors remembering the exact casing, but a line
 * that merely *contains* "Passage:" mid-sentence is not a marker.
 */
internal val passageSplit = Regex("(?m)^($PASSAGE_MARKER)[ \\t]*(.*)$")

/** Option labels, in order. Ten is plenty for a written paper and bounded on purpose. */
private val OPTION_LABELS = ('a'..'j').map { it.toString() }

/** `(a)` … at the start of a line. Any letter is matched so an out-of-range one is caught. */
private val optionMarker = Regex("(?m)^$OPTION_MARKER")

/**
 * A line the parser would read as a marker: `N.)`, `(x)` (any letter — an
 * out-of-range one is refused, so it must not survive at a line start),
 * `Ans.`, `Exp:`, `Answer:` or `Passage:`. The DOCX writer prefixes such a
 * line with one space so a marker-shaped line in a stem, option or back
 * stays text.
 */
internal val docxLineStartMarker = Regex(
    "^(?:$QUESTION_MARKER|$OPTION_MARKER|$ANSWER_MARKER|$EXPLANATION_MARKER|" +
        "$CARD_BACK_MARKER|$PASSAGE_MARKER)"
)

/** Separators between several answers: a comma, an "and", an ampersand. */
private val answerSeparator = Regex("\\s*(?:,|;|&|\\band\\b)\\s*", RegexOption.IGNORE_CASE)

private fun isSingleLetter(token: String) = token.length == 1 && token[0] in 'a'..'z'

/**
 * One `Passage:` marker's place in the cleaned text: where it starts, and
 * which passage (index into the extracted list) questions after it belong
 * to. A bare `Passage:` — no title, no body — is the writer's end-of-
 * passage reset: it anchors position but names no passage, so the
 * questions after it are standalone until the next marker.
 */
internal data class PassageAnchor(val position: Int, val passageIndex: Int?)

/**
 * Splits the cleaned document on `Passage:` markers and returns the passages
 * found plus the text still to question-split. Each question keeps its
 * membership through the passage id, minted `passage-N` in document order.
 */
internal fun extractPassages(cleaned: String): Triple<List<RawPassage>, String, List<PassageAnchor>> {
    val matches = passageSplit.findAll(cleaned).toList()
    if (matches.isEmpty()) return Triple(emptyList<RawPassage>(), cleaned, emptyList())
    val passages = mutableListOf<RawPassage>()
    val anchors = mutableListOf<PassageAnchor>()
    val rebuilt = StringBuilder()
    var cursor = 0
    matches.forEachIndexed { index, match ->
        val titleHtml = match.groupValues[2].trim()
        // The match ends at the line's last character, so `range.last + 1` is
        // the newline itself. Skip it: leaving it out would merge the marker
        // line with the first question stem, whose `N.)` then stops being a
        // line-start marker and the first member is lost.
        var bodyStart = match.range.last + 1
        if (bodyStart < cleaned.length && cleaned[bodyStart] == '\n') bodyStart++
        // The body ends where the questions begin: the first `N.)` marker
        // after the `Passage:` line, or the next marker — whichever comes
        // first. Stopping at the next marker alone would swallow the member
        // questions into the body and drop them from the question stream.
        val firstQuestion = questionSplit.find(cleaned.substring(bodyStart))
        val questionBodyEnd = firstQuestion?.let { bodyStart + it.range.first }
        val bodyEnd = if (index + 1 < matches.size) {
            val nextPassage = matches[index + 1].range.first
            if (questionBodyEnd != null && questionBodyEnd < nextPassage) questionBodyEnd else nextPassage
        } else {
            questionBodyEnd ?: cleaned.length
        }
        val body = cleaned.substring(bodyStart, bodyEnd).trim()
        // Rebuild the question stream by blanking the whole passage block —
        // marker line and body — in place. Newlines are preserved so the
        // question markers after it stay line-anchored, and every character
        // position is kept: the anchors extractQuestions tags members by
        // must stay valid in the rebuilt text, or members of the second and
        // later passages bind to the wrong passage. The blanked text is
        // whitespace-only, which the field parser drops, so the title never
        // leaks into a preceding question's explanation either.
        rebuilt.append(cleaned.substring(cursor, match.range.first))
        for (c in cleaned.substring(match.range.first, bodyEnd)) {
            rebuilt.append(if (c == '\n') '\n' else ' ')
        }
        cursor = bodyEnd
        if (titleHtml.isBlank() && body.isBlank()) {
            // A bare `Passage:` line: the writer's end-of-passage reset.
            // No passage is minted; questions after it are standalone.
            anchors.add(PassageAnchor(position = match.range.first, passageIndex = null))
        } else {
            val id = "passage-${passages.size + 1}"
            passages.add(RawPassage(id = id, titleHtml = titleHtml, bodyHtml = body))
            anchors.add(PassageAnchor(position = match.range.first, passageIndex = passages.size - 1))
        }
    }
    rebuilt.append(cleaned.substring(cursor))
    return Triple(passages, rebuilt.toString(), anchors)
}

private fun extractQuestions(
    cleaned: String,
    /** Offsets of the `Passage:` markers, valid in this text (see extractPassages). */
    passageAnchors: List<PassageAnchor> = emptyList()
): List<RawQuestion> {
    val matches = questionSplit.findAll(cleaned).toList()
    if (matches.isEmpty()) {
        if (cleaned.isBlank()) throw IllegalArgumentException("No questions found: the document is empty.")
        throw IllegalArgumentException(
            "No questions found: expected 'N.)' numbered questions with " +
                "'(a)..(d)', 'Ans.' and 'Exp:' markers, or a card's 'Answer:' back."
        )
    }
    // Which passage each question belongs to: the last `Passage:` marker at or
    // before the question's own position. Questions before any marker are
    // standalone (passageId "").
    fun passageIdAt(position: Int): String {
        var active = ""
        for (anchor in passageAnchors) {
            if (anchor.position > position) break
            // A bare `Passage:` reset clears the binding: the questions
            // after it are standalone until the next marker.
            active = anchor.passageIndex?.let { "passage-${it + 1}" } ?: ""
        }
        return active
    }
    val out = mutableListOf<RawQuestion>()
    for (i in matches.indices) {
        val num = matches[i].groupValues[1].trim()
        val start = matches[i].range.last + 1
        val end = if (i + 1 < matches.size) matches[i + 1].range.first else cleaned.length
        var body = cleaned.substring(start, end).trim()
        // The number may hug the stem on one line ("1.) What…").
        if (body.startsWith(num)) body = body.substring(num.length).trim()
        out.add(extractQuestionData(num, body, passageIdAt(start - num.length - 1)))
    }
    return out
}

private fun extractQuestionData(num: String, block: String, passageId: String = ""): RawQuestion {
    val label = "$num"
    fun fail(reason: String): Nothing =
        throw IllegalArgumentException("Malformed question $label: $reason")

    val expParts = block.split("\n$EXPLANATION_MARKER", limit = 2)
    val explanation = if (expParts.size > 1) expParts[1].trim() else ""
    val preExp = expParts[0]
    val ansParts = preExp.split("\n$ANSWER_MARKER", limit = 2)
    val answer = if (ansParts.size > 1) ansParts[1].trim() else ""
    val optionText = ansParts[0]

    // Options are found by scanning for their markers rather than by splitting
    // off four hard-coded ones, so a paper with more than four options keeps
    // every option's text to itself instead of folding the tail into (d).
    //
    // Options always begin at (a). A single-letter parenthetical before the
    // first (a) is stem content, not an option: statements numbered (i)-(iv)
    // are common inside Hindi question stems, and (i) in particular matches
    // the marker shape while `i` is even a valid option letter. Without this,
    // such a question is refused as "option (e) is missing … but found (iabcd)".
    //
    // The option run is the one starting at the LAST (a): assertion questions
    // letter their statements (a)-(d) and then their code options (a)-(d)
    // too ("सही कूट चुनिए"), and the answer keys the options, not the
    // statements. Taking the first (a) here would read the statements as
    // options and die on the duplicate. Documents parse identically either
    // way — any question with two (a) markers was refused before — and the
    // statements stay in the stem, where they read naturally.
    val markers = optionMarker.findAll(optionText).toList()
    if (markers.isEmpty()) {
        // Only the writer's own `Answer:` label makes a card: an `Ans.`-only
        // block is a multiple-choice question whose options were not on their
        // own lines, and it must keep being refused rather than guessed into
        // a card with its options swallowed into the stem.
        val cardBack = cardBackSplit.find(block)
        if (cardBack != null) return extractCard(num, block, cardBack, passageId)
        fail(
            "missing option (a). Expected format 'N.) question (a) .. (b) .. (c) .. (d) " +
                ".. Ans. .. Exp: ..'."
        )
    }
    val firstOption = markers.indexOfLast { it.groupValues[1] == "a" }
    if (firstOption < 0) {
        fail(
            "missing option (a). The first marker found was " +
                "(${markers.first().groupValues[1]}), but options must start at (a). " +
                "Expected format 'N.) question (a) .. (b) .. (c) .. (d) .. Ans. .. Exp: ..'."
        )
    }
    val optionMarkers = markers.drop(firstOption)
    var stem = optionText.substring(0, optionMarkers.first().range.first).trim()
    // A leading space before the stem was kept by the reader; markers had to
    // start the line, so the stem never carries one.
    stem = stem.trim()
    val options = LinkedHashMap<String, String>()
    for ((index, marker) in optionMarkers.withIndex()) {
        val id = marker.groupValues[1]
        val from = marker.range.last + 1
        val to = if (index + 1 < optionMarkers.size) optionMarkers[index + 1].range.first else optionText.length
        val content = optionText.substring(from, to).trim()
        if (id in options) fail("option ($id) appears more than once.")
        if (id !in OPTION_LABELS) {
            fail(
                "option ($id) is not supported; use (a) to (${OPTION_LABELS.last()})."
            )
        }
        options[id] = content
    }
    // Letters must run a, b, c … with no gap, so a missing (d) is caught
    // instead of silently renumbering everything after it.
    val actual = options.keys.joinToString("")
    val expected = OPTION_LABELS.take(options.size).joinToString("")
    if (actual != expected) {
        val absent = OPTION_LABELS.take(options.size).first { !options.containsKey(it) }
        fail(
            "option ($absent) is missing; labels must run (a), (b), (c), (d) … without " +
                "gaps, but found ($actual)."
        )
    }

    val missing = mutableListOf<String>()
    if (stem.isBlank()) missing.add("question stem")
    for (id in OPTION_LABELS.take(options.size)) {
        if (options[id].orEmpty().isBlank()) missing.add("option ($id)")
    }
    if (answer.isBlank()) missing.add("answer")
    // The explanation is the one field allowed to be absent. A paper written
    // without `Exp:` lines is still a usable paper, and refusing the whole
    // document over it would lose every question in it. An empty explanation
    // is already handled downstream: the study and browse screens skip the
    // block, and a test session falls back to "No explanation provided."
    if (missing.isNotEmpty()) {
        throw IllegalArgumentException(
            "Malformed question $label: missing ${missing.joinToString(", ")}. " +
                "Expected format 'N.) question (a) .. (b) .. (c) .. (d) .. Ans. .. Exp: ..'."
        )
    }

    // An option's own text is a valid answer, and that text can legitimately
    // contain a separator: "(c) Yes, definitely" is answered by "Ans. Yes,
    // definitely", and "(b) Salt and pepper" by "Ans. Salt and pepper". Splitting
    // those first would cut them into tokens and refuse the whole document, so
    // the whole trimmed answer is offered to the options before the split is
    // refused. The forms are tried in this order:
    //   a run of letters -> the letters path below (unchanged),
    //   the whole answer -> the option's own text or label,
    //   several tokens   -> refused, as before,
    //   one token        -> the older text/index forms, as before.
    // The letters path stays first: a bare "b" names the option labelled (b),
    // and only a pure letter run is ever read as letters.
    val strippedAnswer = answer.trim()
    val wholeAnswerNamesAnOption =
        options.keys.any { it.equals(strippedAnswer, ignoreCase = true) } ||
            options.values.any { it.equals(strippedAnswer, ignoreCase = true) }

    // "Ans. b" is one option; "Ans. b, d" or "Ans. b and d" is several. A letter
    // that names no option is an authoring mistake, and is refused here rather
    // than importing as a silently ungraded question.
    val tokens = answer.split(answerSeparator).map { it.trim() }.filter { it.isNotEmpty() }
    val letters = tokens.filter { isSingleLetter(it.lowercase()) }
    val correctIds: List<String>
    if (tokens.isNotEmpty() && letters.size == tokens.size) {
        val ids = tokens.map { it.lowercase() }
        val unknown = ids.firstOrNull { it !in options }
        if (unknown != null) {
            fail(
                "answer names option ($unknown), which this question does not have. " +
                    "It has (${options.keys.joinToString(") (")})."
            )
        }
        correctIds = ids
    } else if (wholeAnswerNamesAnOption) {
        // The option's own text (or the label): left for the importer to resolve
        // by text/id exactly as the single-token form always was.
        correctIds = emptyList()
    } else if (tokens.size > 1) {
        fail(
            "answer '$answer' mixes letters with other text; write several answers as " +
                "'Ans. b, d' so each one can be matched to an option."
        )
    } else {
        // The older forms: the option's own text, or a zero-based index. Left for
        // the importer to resolve exactly as before.
        correctIds = emptyList()
    }

    return RawQuestion(
        num = num,
        stemHtml = stem,
        options = options,
        answer = answer,
        explanationHtml = explanation,
        correctIds = correctIds,
        passageId = passageId
    )
}

/**
 * A `N.)` block with no option markers is a flashcard: the stem is the front,
 * the back is everything after the `Answer:` marker. The back rides in
 * `explanation_elements` (through [RawQuestion.explanationHtml]) because that
 * is where the app keeps a card's back — the same field the hidden payload
 * fingerprints, so an untouched export stays fresh and an edited one still
 * comes back as a card with its answer intact.
 *
 * [backMarker] is always the `Answer:` line: the caller only reaches here
 * when that explicit card label is present, so `Ans.` and `Exp:` blocks keep
 * their previous "malformed multiple-choice question" refusal.
 */
private fun extractCard(
    num: String,
    block: String,
    backMarker: MatchResult,
    passageId: String
): RawQuestion {
    val stem = block.substring(0, backMarker.range.first).trim()
    val back = block.substring(backMarker.range.last + 1).trim()
    if (stem.isBlank()) {
        throw IllegalArgumentException(
            "Malformed question $num: missing question stem. Expected " +
                "'N.) front .. Answer: back'."
        )
    }
    return RawQuestion(
        num = num,
        stemHtml = stem,
        options = emptyMap(),
        answer = "",
        explanationHtml = back,
        correctIds = emptyList(),
        passageId = passageId
    )
}

private val imgPattern = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
private val tablePattern = Regex("<table\\b.*?</table\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val rowPattern = Regex("<tr\\b[^>]*>(.*?)</tr\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val cellPattern = Regex("<t[dh]\\b[^>]*>(.*?)</t[dh]\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val tableOrImg = Regex(
    "<table\\b.*?</table\\s*>|<img\\b[^>]*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** Split field HTML into text/image/table elements (math stays in text). */
internal fun fieldElements(html: String): List<Map<String, Any>> {
    val out = mutableListOf<Map<String, Any>>()
    var pos = 0
    fun flushText(end: Int) {
        val chunk = html.substring(pos, end)
        if (chunk.isBlank()) return
        if (out.isNotEmpty() && out.last()["type"] == "text") {
            // removeLast() on java.util.List needs API 35; minSdk is 26.
            val merged = out.removeAt(out.lastIndex).toMutableMap()
            merged["content"] = (merged["content"] as String) + chunk
            out.add(merged)
        } else {
            out.add(mapOf("type" to "text", "content" to chunk))
        }
    }
    for (match in tableOrImg.findAll(html)) {
        flushText(match.range.first)
        val tag = match.value
        if (tag.startsWith("<img", ignoreCase = true)) {
            out.add(mapOf("type" to "image", "content" to tag))
        } else {
            out.add(mapOf("type" to "table", "content" to parseTableBlock(tag)))
        }
        pos = match.range.last + 1
    }
    flushText(html.length)
    // Drop whitespace-only text runs (stray breaks around blocks).
    return out.filterNot { it["type"] == "text" && (it["content"] as String).isBlank() }
}

private fun parseTableBlock(tableHtml: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    for (rowMatch in rowPattern.findAll(tableHtml)) {
        val cells = cellPattern.findAll(rowMatch.groupValues[1]).map { cellMatch ->
            cellMatch.groupValues[1]
        }.toList()
        rows.add(cells)
    }
    return rows
}

private fun elementJson(el: Map<String, Any>): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(el["type"] as String))
    when (el["type"]) {
        "table" -> {
            @Suppress("UNCHECKED_CAST")
            val rows = el["content"] as List<List<String>>
            put("content", buildJsonArray {
                for (row in rows) {
                    add(buildJsonArray {
                        for (cell in row) add(JsonPrimitive(cell))
                    })
                }
            })
        }
        else -> put("content", JsonPrimitive(el["content"] as String))
    }
}

private fun questionJson(q: RawQuestion): JsonObject = buildJsonObject {
    put("question_num", JsonPrimitive(q.num))
    put("question_elements", buildJsonArray {
        for (el in fieldElements(q.stemHtml)) add(elementJson(el))
    })
    put("options_elements", buildJsonObject {
        for ((key, value) in q.options) {
            put(key, buildJsonArray {
                for (el in fieldElements(value)) add(elementJson(el))
            })
        }
    })
    if (q.correctIds.isNotEmpty()) {
        // Named letters, so the choice is made here and the importer is told
        // exactly which options are right — that is what carries several
        // answers. The single-answer case resolves to the same ids.
        put("correctOptionIds", buildJsonArray { for (id in q.correctIds) add(JsonPrimitive(id)) })
    } else {
        // Option text or a zero-based index: left to the importer as before.
        put("answer", JsonPrimitive(q.answer))
    }
    put("explanation_elements", buildJsonArray {
        for (el in fieldElements(q.explanationHtml)) add(elementJson(el))
    })
    if (q.passageId.isNotEmpty()) {
        put("passageId", JsonPrimitive(q.passageId))
    }
}

/** Visible for tests: the marker splitter without zip/DOM. */
internal fun splitDocxMarkersForTest(coded: String): List<RawQuestion> = extractQuestions(coded)

/** Visible for tests: the cleaned text the marker pass runs on. */
internal fun docxTextForTest(bytes: ByteArray): String {
    val read = readDocx(bytes)
    val text = StringBuilder()
    for (block in read.blocks) {
        when (block) {
            is DocxBlock.Para -> text.append(block.html).append('\n')
            is DocxBlock.Table -> text.append(tableHtml(block)).append('\n')
        }
    }
    return text.toString().replace(Regex("\n{2,}"), "\n")
}

/** Visible for tests: the raw JSON object for one parsed question. */
internal fun questionJsonForTest(q: RawQuestion): JsonObject = questionJson(q)

/** Visible for tests: re-exposes the private shape. */
internal fun rawQuestionForTest(
    num: String,
    stemHtml: String,
    options: Map<String, String>,
    answer: String,
    explanationHtml: String,
    correctIds: List<String> = emptyList()
) = RawQuestion(num, stemHtml, options, answer, explanationHtml, correctIds)
