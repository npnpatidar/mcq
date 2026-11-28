package com.mcqapp

import com.mcqapp.data.docx.canonCell
import com.mcqapp.data.docx.canonTable
import com.mcqapp.data.docx.decodeXmlEntities
import com.mcqapp.data.docx.graftElements
import com.mcqapp.data.docx.graftTable
import com.mcqapp.data.docx.parseDocx
import com.mcqapp.data.docx.parseXml
import com.mcqapp.data.export.docxCellImageSrc
import com.mcqapp.data.export.imageSrcDescriptor
import com.mcqapp.data.export.isImageSrcDescriptor
import com.mcqapp.data.export.withDocxCellImageSrc
import com.mcqapp.data.io.CardContent
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.cellImageTag
import com.mcqapp.util.chemCellHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Rich table cells in the Word export/import pair: a picture becomes a real
 * `word/media` drawing, a formula inline OMML, and a chemical structure its
 * captured PNG (with the readable handles in the drawing's description) or its
 * handles text when it has no PNG. None of them may fall back to the
 * literal `[image]`/`[formula]` markers on a supported cell, and the hidden
 * payload must stay fresh on an untouched round trip so marks, difficulty and
 * tags survive.
 */
class DocxTableCellRichTest {

    private fun pngBytes(w: Int, h: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(byteArrayOf(0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()))
        out.write(
            byteArrayOf(
                (w shr 24).toByte(), (w shr 16).toByte(), (w shr 8).toByte(), w.toByte(),
                (h shr 24).toByte(), (h shr 16).toByte(), (h shr 8).toByte(), h.toByte()
            )
        )
        out.write(byteArrayOf(8, 6, 0, 0, 0))
        out.write(byteArrayOf(0, 0, 0, 0, 'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte()))
        return out.toByteArray()
    }

    private fun pngDataUri(w: Int = 8, h: Int = 8): String =
        "data:image/png;base64," +
            java.util.Base64.getEncoder().encodeToString(pngBytes(w, h))

    private fun chem(diagram: String = pngDataUri()): ContentElement.ChemElement =
        ContentElement.ChemElement(
            smiles = "CCO",
            formula = "C2H5OH",
            caption = "ethanol",
            spoken = "ethanol C two H five O H",
            diagram = diagram
        )

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    /** [bytes] with one zip entry's body passed through [transform]. */
    private fun rewriteEntry(
        bytes: ByteArray,
        name: String,
        transform: (ByteArray) -> ByteArray
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            java.util.zip.ZipOutputStream(out).use { output ->
                var entry = input.nextEntry
                while (entry != null) {
                    output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    val body = input.readBytes()
                    output.write(if (entry.name == name) transform(body) else body)
                    output.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
        return out.toByteArray()
    }

    private fun documentXml(bytes: ByteArray): String =
        entries(bytes).getValue("word/document.xml").toString(Charsets.UTF_8)

    /**
     * The hidden payload's JSON with its XML entities decoded. Quotes inside
     * the JSON strings remain backslash-escaped (the property escapes only
     * XML markup), which is what the assertions below match.
     */
    private fun payloadJson(files: Map<String, ByteArray>): String {
        val props = files.getValue("docProps/custom.xml").toString(Charsets.UTF_8)
        val body = Regex("<vt:lpwstr>(.*?)</vt:lpwstr>", RegexOption.DOT_MATCHES_ALL)
            .find(props)?.groupValues?.get(1).orEmpty()
        return decodeXmlEntities(body)
    }

    private fun gridColumn(xml: String): Int {
        val table = Regex("<w:tbl>.*?</w:tbl>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.value ?: error("no table in: $xml")
        return Regex("<w:gridCol w:w=\"(\\d+)\"/>").find(table)!!.groupValues[1].toInt()
    }

    private fun drawingWidths(xml: String): List<Long> =
        Regex("<wp:extent cx=\"(\\d+)\"").findAll(xml)
            .map { it.groupValues[1].toLong() }.toList()

    private fun tableRows(xml: String): List<String> {
        val table = Regex("<w:tbl>.*?</w:tbl>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.value ?: error("no table in: $xml")
        return Regex("<w:tr>.*?</w:tr>", RegexOption.DOT_MATCHES_ALL)
            .findAll(table).map { it.value }.toList()
    }

    private fun cellSrcs(cell: String): List<String> =
        Regex("<img src=\"([^\"]+)\"").findAll(cell).map { it.groupValues[1] }.toList()

    /**
     * Four columns, each cell exercising one rich kind: prose with inline
     * markup, a chemical structure with its PNG, a wide picture beside text,
     * and a formula beside text. A second row holds a structure without a
     * diagram, where only the handles may be written.
     */
    private fun richRows(): List<List<String>> {
        val chemCell = chemCellHtml(chem())
        val noDiagramCell = chemCellHtml(chem(diagram = ""))
        return listOf(
            listOf(
                "Text <b>bold</b>",
                chemCell,
                "before " + cellImageTag(pngDataUri(2000, 10)) + " after",
                "y <math><mfrac><mi>a</mi><mi>b</mi></mfrac></math> z"
            ),
            listOf("plain", noDiagramCell, "right", "tail")
        )
    }

    private fun paper(
        rows: List<List<String>>,
        options: List<OptionDto> = listOf(OptionDto(id = "a", text = "Alpha"), OptionDto(id = "b", text = "Beta"))
    ) = PaperDto(
        id = "p1",
        title = "Rich cells",
        categories = listOf(
            CategoryDto(
                id = "c1",
                title = "Tables",
                questions = listOf(
                    QuestionDto(
                        id = "q1",
                        text = "Read the table.",
                        options = options,
                        correctOptionIds = if (options.isEmpty()) emptyList() else listOf("b"),
                        explanationElements = listOf(ContentElement.TableElement(rows)),
                        difficulty = "hard",
                        marks = 2.5,
                        tags = listOf("table", "rich")
                    )
                )
            )
        )
    )

    private fun singleQuestion(parsed: com.mcqapp.data.docx.DocxParseResult): QuestionDto =
        LegacyParser.parse(parsed.json).papers.single().categories.single().questions.single()

    /** The writer with its warning sink exposed; the public entry only logs. */
    private fun docxWithWarnings(paper: PaperDto): Pair<ByteArray, List<String>> {
        val warnings = mutableListOf<String>()
        val bytes = exportBytes { out ->
            com.mcqapp.data.export.DocxPaperWriter.writeDocx(
                com.mcqapp.data.export.PaperStream.of(paper),
                out,
                warnings
            )
        }
        return bytes to warnings
    }

    /**
     * Word export cannot express every MathML construct. The cell must still
     * show readable linear text (never raw markup or a marker), and the one
     * unrepresentable formula must produce exactly one warning — not one per
     * pass and not silence.
     */
    @Test
    fun anUnsupportedCellMathEmitsLinearTextAndOneWarning() {
        val unsupported = "<math><munder><mi>x</mi><mo>^</mo></munder></math>"
        val cell = "before $unsupported after"
        val (bytes, warnings) = docxWithWarnings(paper(listOf(listOf(cell))))

        assertEquals("one formula, one warning: $warnings", 1, warnings.size)
        assertTrue(
            "the warning names the fallback: $warnings",
            warnings.single().contains("does not support") &&
                warnings.single().contains("plain text")
        )

        val xml = documentXml(bytes)
        parseXml(xml.toByteArray(Charsets.UTF_8), "document")
        assertFalse("no OMML for an unsupported construct: $xml", xml.contains("<m:oMath>"))
        assertFalse("never raw MathML: $xml", xml.contains("<m:under") || xml.contains("&lt;munder"))
        assertFalse("never the fallback marker: $xml", xml.contains("[formula"))
        assertTrue("prose before survives: $xml", xml.contains("before "))
        assertTrue("prose after survives: $xml", xml.contains(" after"))
        assertTrue("the formula's leaf text is printed: $xml", xml.contains(">x^<"))
    }

    /**
     * An unsupported cell formula prints as linear text. The freshness canon
     * must use the writer's own decision (math part only when OMML converts),
     * so the untouched export stays fresh and metadata (marks, difficulty,
     * tags) rides the payload.
     */
    @Test
    fun anUnsupportedCellFormulaRoundTripKeepsMetadata() {
        val unsupported = "<math><munder><mi>x</mi><mo>^</mo></munder></math>"
        val paper = paper(listOf(listOf("before $unsupported after")))
        val parsed = parseDocx(docxOf(paper))
        assertTrue(
            "the untouched export must read as fresh: ${parsed.warnings}",
            parsed.warnings.isEmpty()
        )
        val after = singleQuestion(parsed)
        assertEquals("marks ride the payload", 2.5, after.marks, 0.0)
        assertEquals("difficulty rides the payload", "hard", after.difficulty)
        assertEquals("tags ride the payload", listOf("table", "rich"), after.tags)
    }

    /**
     * The owner-reported bug: MathLive committed a bare `<mrow>…</mrow>` (or a
     * sibling run) into a cell, so the DOCX writer treated it as prose. The
     * shared cell grammar repairs it on read, and the writer must then emit a
     * real inline OMML equation — never the literal markup, never the marker.
     */
    @Test
    fun aBareMathLiveFragmentInACellIsWrittenAsOMML() {
        val bare = "<mrow><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></mrow>"
        val files = entries(docxOf(paper(listOf(listOf("Find ", "x = $bare", " tail")))))
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        parseXml(xml.toByteArray(Charsets.UTF_8), "document")
        assertTrue("the fragment becomes inline OMML: $xml", xml.contains("<m:oMath>"))
        assertFalse("never literal markup: $xml", xml.contains("<mrow"))
        assertFalse("never escaped markup: $xml", xml.contains("&lt;mrow"))
        assertFalse("never the fallback marker: $xml", xml.contains("[formula]"))
        assertTrue("prose before survives: $xml", xml.contains("Find "))
        assertTrue("prose after survives: $xml", xml.contains(" tail"))
        // The hidden payload is the original cell and is allowed to carry it;
        // only the visible document must be free of the raw fragment.
        assertFalse("no raw fragment in the document body", xml.contains("&lt;msup"))
    }

    @Test
    fun aBareMathLiveFragmentRoundTripsThroughWordUnchanged() {
        val bare = "<mrow><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></mrow>"
        val rows = listOf(listOf("Find x = $bare tail"))
        val parsed = parseDocx(docxOf(paper(rows)))
        // The canon types the formula as OMML and the prose as text: a cell
        // whose formula was read as prose would come back with a different row
        // (and a stale/duplicate warning). An untouched export must be fresh.
        assertTrue("an untouched export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val after = singleQuestion(parsed)
        assertEquals(
            "the cell round-trips unchanged",
            rows,
            after.explanationElements.filterIsInstance<ContentElement.TableElement>().single().rows
        )
    }

    /**
     * `mathMlToOmml` refuses `msubsup` (and `mtable`, `mfenced`, …), so the
     * writer prints its linear text. The canon must type that formula as text
     * too; typing it `m:` made payload and visible disagree and dropped the
     * payload's ids, marks, difficulty and tags on an untouched export.
     */
    @Test
    fun anUnsupportedCellFormulaIsCanonicalisedAsItsVisibleText() {
        val math = "<math><msubsup><mi>x</mi><mi>i</mi><mn>2</mn></msubsup></math>"
        val cell = "x $math"
        val rows = listOf(listOf(cell))
        val paper = paper(rows)
        val xml = entries(docxOf(paper)).getValue("word/document.xml").toString(Charsets.UTF_8)
        parseXml(xml.toByteArray(Charsets.UTF_8), "document")
        assertTrue("the linear text must be what Word holds: $xml", xml.contains(">xi2<"))
        assertTrue("the prose before it survives: $xml", xml.contains(">x <"))
        assertFalse("an unsupported formula is never OMML: $xml", xml.contains("<m:oMath>"))

        val parsed = parseDocx(docxOf(paper))
        assertTrue(
            "an untouched export must stay fresh: ${parsed.warnings}",
            parsed.warnings.none { it.contains("hidden question data") }
        )
        val after = singleQuestion(parsed)
        assertEquals(
            "the payload cell must come back unchanged",
            rows,
            after.explanationElements.filterIsInstance<ContentElement.TableElement>().single().rows
        )
        assertEquals(
            "an untouched export must not reset the card's schedule",
            CardContent.of(paper.categories.single().questions.single()),
            CardContent.of(after)
        )
    }

    @Test
    fun anUnsupportedBodyFormulaIsCanonicalisedAsItsVisibleText() {
        val math = "<math><msubsup><mi>x</mi><mi>i</mi><mn>2</mn></msubsup></math>"
        val q = QuestionDto(
            id = "q1",
            text = "Find xi2",
            elements = listOf(
                ContentElement.TextElement("Find "),
                ContentElement.MathElement(math)
            ),
            options = listOf(OptionDto(id = "a", text = "Alpha"), OptionDto(id = "b", text = "Beta")),
            correctOptionIds = listOf("a"),
            difficulty = "hard",
            marks = 1.5,
            tags = listOf("formula")
        )
        val paper = PaperDto(
            id = "p1",
            title = "Body math",
            categories = listOf(CategoryDto(id = "c1", title = "C", questions = listOf(q)))
        )
        val parsed = parseDocx(docxOf(paper))
        assertTrue(
            "an untouched export must stay fresh: ${parsed.warnings}",
            parsed.warnings.none { it.contains("hidden question data") }
        )
        val after = singleQuestion(parsed)
        assertEquals(
            "the payload keeps the formula it could not write as OMML",
            listOf(math),
            after.elements.filterIsInstance<ContentElement.MathElement>().map { it.mathml }
        )
        assertEquals("marks, difficulty and tags ride the payload", 1.5, after.marks, 0.0)
        assertEquals("hard", after.difficulty)
        assertEquals(listOf("formula"), after.tags)
        assertEquals(CardContent.of(q), CardContent.of(after))
    }

    /** Supported formulas must keep their `m:` canon (mfrac/msup behaviour). */
    @Test
    fun onlyFormulasTheWriterCannotConvertAreCanonicalisedAsText() {
        fun canon(inner: String) = canonCell("<math>$inner</math>")
        assertTrue(
            "a fraction stays math",
            canon("<mfrac><mi>a</mi><mi>b</mi></mfrac>").startsWith("m:")
        )
        assertTrue(
            "a superscript stays math",
            canon("<msup><mi>x</mi><mn>2</mn></msup>").startsWith("m:")
        )
        assertTrue(
            "msubsup is linearised by the writer, so it is text",
            canon("<msubsup><mi>x</mi><mi>i</mi><mn>2</mn></msubsup>").startsWith("t:")
        )
    }

    @Test
    fun aMathSpaceInACellDoesNotSwallowTheNextAtom() {
        // MathLive writes `\,` as `<mspace width="0.5em"/>`. Re-emitting it as
        // `<mspace>` made it a container, and every following atom vanished.
        val cell = "<mi>a</mi><mspace width=\"0.5em\"/><mi>b</mi>"
        val files = entries(docxOf(paper(listOf(listOf(cell)))))
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        parseXml(xml.toByteArray(Charsets.UTF_8), "document")
        assertTrue("the atom before the space: $xml", xml.contains(">a<"))
        assertTrue("the atom after the space survives: $xml", xml.contains(">b<"))
        assertFalse("no literal markup: $xml", xml.contains("<mspace"))
    }

    @Test
    fun supportedCellContentIsWrittenAsRealContent() {
        val rows = richRows()
        val files = entries(docxOf(paper(rows)))
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        parseXml(xml.toByteArray(Charsets.UTF_8), "document")

        val cells = tableRows(xml).flatMap { row ->
            Regex("<w:tc>.*?</w:tc>", RegexOption.DOT_MATCHES_ALL)
                .findAll(row).map { it.value }.toList()
        }
        val formulaCell = cells.single { it.contains("<m:oMath>") }
        assertTrue(
            "a cell formula must be inline OMML: $formulaCell",
            formulaCell.contains("<m:oMath>") && formulaCell.contains("<m:f>")
        )
        assertFalse("a supported formula never becomes a marker", formulaCell.contains("[formula]"))
        assertFalse("a supported picture never becomes a marker", xml.contains("[image]"))

        // Two distinct pictures: the cell image and the captured chem PNG.
        val media = files.filterKeys { it.startsWith("word/media/") }
        assertEquals("one media entry per distinct picture", 2, media.size)
        assertTrue(media.values.any { it.contentEquals(pngBytes(2000, 10)) })
        assertTrue(media.values.any { it.contentEquals(pngBytes(8, 8)) })
        val rels = files.getValue("word/_rels/document.xml.rels").toString(Charsets.UTF_8)
        assertEquals(2, Regex("relationships/image").findAll(rels).count())

        // The structure's handles ride the drawing's description; a cell
        // without a diagram prints them as visible text.
        assertTrue(xml.contains("ethanol"))
        assertTrue(xml.contains("C2H5OH"))
        assertTrue(xml.contains("descr=\"Image in table\""))
        assertTrue(
            "the structure's accessible description rides the drawing",
            xml.contains("descr=\"ethanol C two H five O H C2H5OH\"")
        )

        // A cell picture is clamped to its column so Word cannot clip it.
        // The expectation is derived from the document's own grid, not from
        // the writer's constants.
        val cellMaxCx = (gridColumn(xml) - 240) * 635
        val extents = drawingWidths(xml)
        assertTrue("the wide picture must be drawn", extents.isNotEmpty())
        assertTrue(
            "every extent must fit the column: $extents > $cellMaxCx",
            extents.all { it <= cellMaxCx }
        )
        assertEquals(cellMaxCx.toLong(), extents.max())

        // The payload carries the cell picture and the structure's raster as
        // compact descriptors only; the bytes ride in word/media.
        val payload = payloadJson(files)
        assertTrue(
            "the image cell must keep a compact descriptor, not base64: $payload",
            payload.contains("mcqapp:img|data|image|png|base64|")
        )
        assertTrue(
            "a structure's data-png must be a descriptor too: $payload",
            payload.contains("data-png=\\\"mcqapp:img|data|image|png|base64|")
        )
        assertFalse(
            "the full picture bytes must not be in the hidden payload",
            payload.contains(pngDataUri(2000, 10).substringAfter(','))
        )
        assertFalse(
            "the structure's raster must not be in the hidden payload",
            payload.contains(pngDataUri(8, 8).substringAfter(','))
        )
    }

    @Test
    fun untouchedQuizRoundTripKeepsPayloadAndRichCells() {
        val rows = richRows()
        val paper = paper(rows)
        val parsed = parseDocx(docxOf(paper))
        assertTrue("an untouched export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val after = singleQuestion(parsed)

        assertEquals("the quiz must keep its options", 2, after.options.size)
        assertEquals(listOf("b"), after.correctOptionIds)
        assertEquals("marks ride the payload", 2.5, after.marks, 0.0)
        assertEquals("difficulty rides the payload", "hard", after.difficulty)
        assertEquals("tags ride the payload", listOf("table", "rich"), after.tags)
        assertEquals(
            "an untouched round trip returns the same cells, marker and picture included",
            rows,
            after.explanationElements.filterIsInstance<ContentElement.TableElement>().single().rows
        )
        assertEquals(
            "an untouched round trip must not reset the card's schedule",
            CardContent.of(paper.categories.single().questions.single()),
            CardContent.of(after)
        )
        assertTrue(
            "the chem spec survives through the payload",
            rows[0][1].contains("data-smiles=\"CCO\"")
        )
    }

    @Test
    fun plainCardWithRichCellsRoundTrips() {
        val rows = richRows()
        val paper = paper(rows, options = emptyList())
        val parsed = parseDocx(docxOf(paper))
        assertTrue("an untouched card export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val after = singleQuestion(parsed)
        assertTrue("the card must stay a card", after.options.isEmpty())
        assertEquals(rows, after.explanationElements.filterIsInstance<ContentElement.TableElement>().single().rows)
        assertEquals(2.5, after.marks, 0.0)
        assertEquals(
            "an untouched card round trip must not reset the schedule",
            CardContent.of(paper.categories.single().questions.single()),
            CardContent.of(after)
        )
    }

    @Test
    fun sampleShapedStemTableRoundTripsAndKeepsTheSchedule() {
        val rows = richRows()
        val q = QuestionDto(
            id = "q1",
            elements = listOf(
                ContentElement.TextElement("Read the table."),
                ContentElement.TableElement(rows)
            ),
            options = listOf(OptionDto(id = "a", text = "Alpha"), OptionDto(id = "b", text = "Beta")),
            correctOptionIds = listOf("b"),
            difficulty = "hard",
            marks = 2.5,
            tags = listOf("table", "rich")
        )
        val paper = PaperDto(
            id = "p1",
            title = "Rich cells",
            categories = listOf(CategoryDto(id = "c1", title = "Tables", questions = listOf(q)))
        )
        val parsed = parseDocx(docxOf(paper))
        assertTrue(
            "a stem table with formula, picture and structure must stay fresh: ${parsed.warnings}",
            parsed.warnings.isEmpty()
        )
        val after = singleQuestion(parsed)
        assertEquals(
            "the stem table must come back unchanged",
            rows,
            after.elements.filterIsInstance<ContentElement.TableElement>().single().rows
        )
        assertEquals(2.5, after.marks, 0.0)
        assertEquals("hard", after.difficulty)
        assertEquals(listOf("table", "rich"), after.tags)
        assertEquals(
            "an untouched round trip must not reset the card's schedule",
            CardContent.of(q),
            CardContent.of(after)
        )
    }

    @Test
    fun aCellPictureWithItsOwnSizingRoundTripsExactly() {
        val tag = "<img src=\"${pngDataUri()}\" style=\"width:120px\" alt=\"custom picture\">"
        val rows = listOf(listOf("own $tag sizing"))
        val files = entries(docxOf(paper(rows)))
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue("a tag with its own sizing must still embed", xml.contains("<w:drawing>"))
        assertEquals(1, files.keys.count { it.startsWith("word/media/") })
        val parsed = parseDocx(docxOf(paper(rows)))
        assertTrue("an untouched export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val after = singleQuestion(parsed)
        assertEquals(
            "the cell's own tag form must come back byte-identically",
            rows,
            after.explanationElements.filterIsInstance<ContentElement.TableElement>().single().rows
        )
    }

    @Test
    fun picturesInLaterRowsAndASecondTableGraftCorrectly() {
        val laterSrc = pngDataUri(8, 8)
        val backSrc = pngDataUri(9, 9)
        val stemRows = listOf(
            listOf("first", "no picture"),
            listOf("second", "later " + cellImageTag(laterSrc))
        )
        val backRows = listOf(
            listOf("back first", "no picture"),
            listOf("back second", "later " + cellImageTag(backSrc))
        )
        val q = QuestionDto(
            id = "q1",
            elements = listOf(
                ContentElement.TextElement("Read the tables."),
                ContentElement.TableElement(stemRows)
            ),
            options = listOf(OptionDto(id = "a", text = "Alpha"), OptionDto(id = "b", text = "Beta")),
            correctOptionIds = listOf("b"),
            explanationElements = listOf(ContentElement.TableElement(backRows))
        )
        val paper = PaperDto(
            id = "p1",
            title = "Two tables",
            categories = listOf(CategoryDto(id = "c1", title = "Tables", questions = listOf(q)))
        )
        val files = entries(docxOf(paper))
        assertEquals(2, files.keys.count { it.startsWith("word/media/") })
        val parsed = parseDocx(docxOf(paper))
        assertTrue("an untouched export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val after = singleQuestion(parsed)

        val afterStem = after.elements.filterIsInstance<ContentElement.TableElement>().single().rows
        val afterBack = after.explanationElements.filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals("a later-row stem picture must graft into its own cell", stemRows, afterStem)
        assertEquals("the second table's picture must graft into its own table", backRows, afterBack)
        assertEquals(listOf(laterSrc), cellSrcs(afterStem[1][1]))
        assertEquals(listOf(backSrc), cellSrcs(afterBack[1][1]))
    }

    @Test
    fun twoPicturesInOneCellKeepDocumentOrder() {
        val first = pngDataUri(8, 8)
        val second = pngDataUri(12, 12)
        val cell = "a " + cellImageTag(first) + " b " + cellImageTag(second)
        val rows = listOf(listOf(cell, "tail"))
        val files = entries(docxOf(paper(rows)))
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertEquals("both pictures must be embedded", 2, files.keys.count { it.startsWith("word/media/") })
        assertEquals("both pictures must be drawn", 2, Regex("<w:drawing>").findAll(xml).count())

        val parsed = parseDocx(docxOf(paper(rows)))
        assertTrue("an untouched export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val afterCell = singleQuestion(parsed).explanationElements
            .filterIsInstance<ContentElement.TableElement>().single().rows[0][0]
        assertEquals("the whole cell must round-trip", cell, afterCell)
        assertEquals("both srcs must return in document order", listOf(first, second), cellSrcs(afterCell))
    }

    @Test
    fun raggedRowsAndTrailingEmptyCellsStayFresh() {
        val rows = listOf(
            listOf("a", "b"),
            listOf("c", "d", "e", "f"),
            listOf("g", "h", "i", "")
        )
        val files = entries(docxOf(paper(rows)))
        val documentRows = tableRows(files.getValue("word/document.xml").toString(Charsets.UTF_8))
        assertEquals("the writer must keep every row", 3, documentRows.size)
        documentRows.forEachIndexed { index, row ->
            assertEquals(
                "row $index must be padded to the table's four columns",
                4,
                Regex("<w:tc>").findAll(row).count()
            )
        }

        val parsed = parseDocx(docxOf(paper(rows)))
        assertTrue(
            "ragged rows and a genuinely empty trailing cell must stay fresh: ${parsed.warnings}",
            parsed.warnings.isEmpty()
        )
        val after = singleQuestion(parsed).explanationElements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals("the payload's ragged shape must come back exactly", rows, after)
    }

    @Test
    fun oneAndTwoColumnTablesClampImageOnlyCells() {
        fun assertClamp(rows: List<List<String>>, cols: Int) {
            val paper = paper(rows)
            val files = entries(docxOf(paper))
            val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
            assertEquals("the table's declared columns", 9360 / cols, gridColumn(xml))
            val expected = ((9360 / cols) - 240) * 635L
            val extents = drawingWidths(xml)
            assertEquals("one image-only cell must draw once", 1, extents.size)
            assertEquals("the column is the only clamp here", expected, extents.single())
            assertEquals(1, files.keys.count { it.startsWith("word/media/") })

            val parsed = parseDocx(docxOf(paper))
            assertTrue("an untouched export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
            assertEquals(
                "the image-only cell must round-trip",
                rows,
                singleQuestion(parsed).explanationElements
                    .filterIsInstance<ContentElement.TableElement>().single().rows
            )
        }
        // A one-column image-only cell: `addCellProse` starts at offset 0.
        assertClamp(listOf(listOf(cellImageTag(pngDataUri(2000, 10)))), cols = 1)
        assertClamp(
            listOf(listOf(cellImageTag(pngDataUri(2000, 10)), "text")),
            cols = 2
        )
    }

    @Test
    fun optionTableWithPictureAndFormulaRoundTrips() {
        val src = pngDataUri(10, 10)
        val rows = listOf(
            listOf("opt " + cellImageTag(src), "y <math><mfrac><mi>a</mi><mi>b</mi></mfrac></math> z")
        )
        val q = QuestionDto(
            id = "q1",
            text = "Pick the table.",
            options = listOf(
                OptionDto(id = "a", elements = listOf(ContentElement.TableElement(rows))),
                OptionDto(id = "b", text = "plain")
            ),
            correctOptionIds = listOf("a")
        )
        val paper = PaperDto(
            id = "p1",
            title = "Option table",
            categories = listOf(CategoryDto(id = "c1", title = "Tables", questions = listOf(q)))
        )
        val files = entries(docxOf(paper))
        assertEquals(1, files.keys.count { it.startsWith("word/media/") })
        val parsed = parseDocx(docxOf(paper))
        assertTrue("an untouched option table must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val after = singleQuestion(parsed)
        val afterRows = after.options.first().elements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals("the option table must round-trip", rows, afterRows)
        assertEquals(listOf(src), cellSrcs(afterRows[0][0]))
    }

    @Test
    fun passageTableWithPictureAndFormulaRoundTrips() {
        val src = pngDataUri(11, 11)
        val rows = listOf(
            listOf("ps " + cellImageTag(src), "p <math><mfrac><mi>x</mi><mi>y</mi></mfrac></math> q")
        )
        val passage = PassageDto(
            id = "ps1",
            title = "Shared table",
            elements = listOf(ContentElement.TableElement(rows))
        )
        val q = QuestionDto(
            id = "q1",
            text = "Read the passage table.",
            options = listOf(OptionDto(id = "a", text = "Alpha")),
            correctOptionIds = listOf("a"),
            passageId = "ps1"
        )
        val paper = PaperDto(
            id = "p1",
            title = "Passage table",
            categories = listOf(CategoryDto(id = "c1", title = "Tables", questions = listOf(q)))
        )
        val files = entries(docxOf(paper, listOf(passage)))
        assertEquals(1, files.keys.count { it.startsWith("word/media/") })
        val parsed = parseDocx(docxOf(paper, listOf(passage)))
        assertTrue("an untouched passage table must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val afterPassage = LegacyParser.parse(parsed.json).passages.single()
        val afterRows = afterPassage.elements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals("the passage table must round-trip", rows, afterRows)
        assertEquals(listOf(src), cellSrcs(afterRows[0][0]))
    }

    @Test
    fun reorderedTableIsStaleWithExactlyOneWarning() {
        val rows = richRows()
        val bytes = docxOf(paper(rows))
        val xml = documentXml(bytes)
        val reordered = rezip(bytes, "word/document.xml") { swapFirstTwoTableRows(xml) }
        val parsed = parseDocx(reordered)
        assertEquals(
            "a structural table edit must produce exactly one warning: ${parsed.warnings}",
            1,
            parsed.warnings.size
        )
        assertTrue(
            "the warning must name the stale payload: ${parsed.warnings}",
            parsed.warnings.single().contains("hidden question data")
        )

        val afterRows = singleQuestion(parsed).explanationElements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertTrue("the reordered row must lead: ${afterRows[0][0]}", afterRows[0][0].contains("plain"))
        assertTrue("the original first row must follow: ${afterRows[1][0]}", afterRows[1][0].contains("Text"))
        val formulaCell = afterRows[1][3]
        assertTrue(
            "the formula must degrade to visible MathML: $formulaCell",
            formulaCell.contains("<math") && formulaCell.contains("<mfrac>") && formulaCell.contains("</math>")
        )
        assertFalse("a visible formula never becomes a marker", formulaCell.contains("[formula]"))
    }

    @Test
    fun swappingTwoCellsIsStale() {
        val rows = listOf(listOf("left", "right"), listOf("c", "d"))
        val paper = paper(rows)
        val bytes = docxOf(paper)
        val xml = documentXml(bytes)
        val tampered = rezip(bytes, "word/document.xml") { swapFirstTwoCellsInFirstRow(xml) }
        val parsed = parseDocx(tampered)
        assertEquals(
            "a cell-order edit must produce exactly one warning: ${parsed.warnings}",
            1,
            parsed.warnings.size
        )
        assertTrue(
            "the warning must name the stale payload: ${parsed.warnings}",
            parsed.warnings.single().contains("hidden question data")
        )
        val after = singleQuestion(parsed)
        val afterRows = after.explanationElements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals("the cells must come back in their edited order", listOf("right", "left"), afterRows[0].take(2))
        assertEquals("metadata the markers cannot carry is gone", 1.0, after.marks, 0.0)
    }

    @Test
    fun aWordEditedCellLosesTheStructureAndItsMetadata() {
        val rows = richRows()
        val bytes = docxOf(paper(rows))
        val xml = documentXml(bytes)
        // A plain word edit inside the table is still detected as an edit.
        val tampered = rezip(bytes, "word/document.xml") {
            xml.replace("Text ", "Edited ")
        }
        val parsed = parseDocx(tampered)
        assertEquals(
            "a text edit must produce exactly one warning: ${parsed.warnings}",
            1,
            parsed.warnings.size
        )
        assertTrue(
            "the stale-payload fallback must say why: ${parsed.warnings}",
            parsed.warnings.single().contains("hidden question data")
        )
        val after = singleQuestion(parsed)
        val afterRows = after.explanationElements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertTrue(
            "the edit must survive",
            afterRows.flatten().any { it.contains("Edited") }
        )
        assertFalse(
            "the structure degrades to what the reader sees, never a marker or raw tag",
            afterRows.flatten().any { it.contains("mcq-chem-cell") }
        )
        assertEquals(
            "the visible drawing that replaced the handles is the exact chem source",
            listOf(chem().diagram),
            cellSrcs(afterRows[0][1])
        )
        assertTrue(
            "the edited cell's picture still round-trips from word/media",
            afterRows[0][2].contains("data:image/png;base64,")
        )
        assertEquals("metadata the markers cannot carry is gone", 1.0, after.marks, 0.0)
    }

    @Test
    fun nonPortableAndHostilePicturesNeverReachWord() {
        val svg = "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="
        val bad = "data:image/png;base64,AA AA"
        val attr = "data:image/png;base64,AAAA"
        val hostileRows = listOf(
            listOf(
                "remote <img src=\"https://example.com/x.png\">",
                "file <img src=\"file:///tmp/x.png\">",
                "svg <img src=\"$svg\">",
                "bad <img src=\"$bad\">"
            ),
            listOf(
                "attr <img src=\"$attr\" onerror=\"alert(1)\">",
                "torn <img src=\"data:image/png;base64,AAAA",
                "no source <img>",
                "plain"
            ),
            listOf(
                "srcset <img srcset=\"a.png 1x\">",
                "data-src <img data-src=\"$attr\">",
                "empty <img src=\"\">",
                "plain2"
            )
        )
        val files = entries(docxOf(paper(hostileRows)))
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)

        assertTrue(
            "no hostile or non-portable picture may become media: ${files.keys}",
            files.keys.none { it.startsWith("word/media/") }
        )
        assertFalse("no data URI may leak into the visible document", xml.contains("base64"))
        assertFalse("no remote URL may leak into the document", xml.contains("example.com"))
        assertFalse("no hostile attribute may leak into the document", xml.contains("onerror"))
        assertTrue(
            "a non-embeddable data picture is visible as the marker",
            xml.contains("[image]")
        )

        // A non-embeddable data tag is kept verbatim in the payload (its stored
        // cell must survive untouched); a torn fragment is the only loss.
        val payload = payloadJson(files)
        assertTrue(
            "the SVG cell must stay exact in the payload",
            payload.contains(svg)
        )
        val parsed = parseDocx(docxOf(paper(hostileRows)))
        assertTrue(
            "a hostile cell must not make the payload stale: ${parsed.warnings}",
            parsed.warnings.isEmpty()
        )
        val rows = singleQuestion(parsed).explanationElements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals("the SVG cell must round-trip byte-identically", "svg <img src=\"$svg\">", rows[0][2])
        assertEquals("the bad-base64 cell must round-trip byte-identically", "bad <img src=\"$bad\">", rows[0][3])
        assertEquals(
            "the hostile-attribute cell must round-trip byte-identically",
            "attr <img src=\"$attr\" onerror=\"alert(1)\">",
            rows[1][0]
        )
        assertTrue("remote metadata stays payload-only", rows[0][0].contains("example.com"))
        assertTrue("file metadata stays payload-only", rows[0][1].contains("file:///"))
        assertEquals("a torn tag is dropped", "torn ", rows[1][1])
        assertEquals("a harmless source-less tag is kept", "no source <img>", rows[1][2])
        assertEquals("a srcset-only tag is kept, not mistaken for src", "srcset <img srcset=\"a.png 1x\">", rows[2][0])
        assertEquals("a data-src tag is kept, not mistaken for src", "data-src <img data-src=\"$attr\">", rows[2][1])
        assertEquals("an empty src is kept without a warning", "empty <img src=\"\">", rows[2][2])
    }

    @Test
    fun adversarialCellPictureTagsAreRejected() {
        val src = pngDataUri()
        assertEquals(src, docxCellImageSrc("<img src=\"$src\">"))
        assertEquals("<img src=\"\">", withDocxCellImageSrc("<img src=\"$src\">", ""))
        // Single-quoted values are accepted and preserved on a benign tag.
        assertEquals(src, docxCellImageSrc("<img src='$src'>"))
        assertEquals("<img src=''>", withDocxCellImageSrc("<img src='$src'>", ""))

        // Rejected: two sources, an unquoted value, a hostile single-quoted
        // attribute, and duplicate style/alt attributes.
        assertNull(docxCellImageSrc("<img src=\"$src\" src=\"$src\">"))
        assertNull(docxCellImageSrc("<img src=$src>"))
        assertNull(docxCellImageSrc("<img src=\"$src\" onerror='alert(1)'>"))
        assertNull(docxCellImageSrc("<img src=\"$src\" style=\"a\" style=\"b\">"))
        assertNull(docxCellImageSrc("<img src=\"$src\" alt=\"a\" alt=\"b\">"))

        assertNull(withDocxCellImageSrc("<img src=\"$src\" src=\"$src\">", ""))
        assertNull(withDocxCellImageSrc("<img src=$src>", ""))
        assertNull(withDocxCellImageSrc("<img src=\"$src\" onerror='alert(1)'>", ""))
        assertNull(withDocxCellImageSrc("<img src=\"$src\" style=\"a\" style=\"b\">", ""))
        assertNull(withDocxCellImageSrc("<img src=\"$src\" alt=\"a\" alt=\"b\">", ""))
    }

    @Test
    fun structureBeforeOrAfterAPictureKeepsThePicturesBytes() {
        val chemSrc = pngDataUri(8, 8)
        val userSrc = pngDataUri(16, 16)
        val chemTag = chemCellHtml(chem(diagram = chemSrc))
        val pictureTag = cellImageTag(userSrc)
        val chemFirst = chemTag + " " + pictureTag
        val pictureFirst = pictureTag + " " + chemTag
        val rows = listOf(listOf(chemFirst, pictureFirst))
        val files = entries(docxOf(paper(rows)))
        assertEquals(2, files.keys.count { it.startsWith("word/media/") })

        val parsed = parseDocx(docxOf(paper(rows)))
        assertTrue("an untouched export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val afterRows = singleQuestion(parsed).explanationElements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals("both mixed cells must round-trip", rows, afterRows)
        assertEquals(
            "chem before picture: the user picture keeps its own src",
            listOf(userSrc),
            cellSrcs(afterRows[0][0])
        )
        assertTrue(
            "the structure keeps its own data-png",
            afterRows[0][0].contains("data-png=\"${chemSrc.substringAfter(',')}\"")
        )
        assertEquals(
            "picture before structure: the picture keeps its own src",
            listOf(userSrc),
            cellSrcs(afterRows[0][1])
        )
    }

    /**
     * The hidden property carried each structure's whole captured raster,
     * which inflated it and hit Word's 255-character custom-property
     * truncation. The payload must hold only a compact descriptor and the
     * graft must restore the exact bytes from the visible structure picture.
     */
    @Test
    fun aStructureRasterTravelsAsADescriptorAndGraftsBackExactly() {
        val chemSrc = pngDataUri(8, 8)
        val rows = listOf(listOf(chemCellHtml(chem(diagram = chemSrc))))
        val paper = paper(rows)
        val payload = payloadJson(entries(docxOf(paper)))
        assertTrue(
            "the structure raster must be a compact descriptor: $payload",
            payload.contains("data-png=\\\"mcqapp:img|data|image|png|base64|")
        )
        assertFalse(
            "no raster bytes may stay in the hidden payload: $payload",
            payload.contains(chemSrc.substringAfter(','))
        )

        val parsed = parseDocx(docxOf(paper))
        assertTrue("an untouched export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val after = singleQuestion(parsed)
        val afterRows = after.explanationElements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals("the marker must come back byte-identically", rows, afterRows)
        assertEquals(CardContent.of(paper.categories.single().questions.single()), CardContent.of(after))
    }

    /**
     * A picture replaced after export must be detected: the descriptor's hash
     * no longer matches the visible bytes, so the graft refuses the payload and
     * the visible structure (its handles and the new drawing) wins.
     */
    @Test
    fun aModifiedStructurePictureIsDetectedAndTheVisibleStructureWins() {
        val rows = listOf(listOf(chemCellHtml(chem(diagram = pngDataUri(8, 8)))))
        val paper = paper(rows)
        val bytes = docxOf(paper)
        val media = entries(bytes).keys.single { it.startsWith("word/media/") }
        val replacement = pngBytes(9, 9)
        val tampered = rewriteEntry(bytes, media) { replacement }

        val parsed = parseDocx(tampered)
        assertTrue(
            "a replaced structure picture must make the payload stale: ${parsed.warnings}",
            parsed.warnings.any { it.contains("hidden question data") }
        )
        val after = singleQuestion(parsed)
        val cell = after.explanationElements
            .filterIsInstance<ContentElement.TableElement>().single().rows[0][0]
        assertFalse("the stale marker must not be stored: $cell", cell.contains("mcq-chem-cell"))
        assertEquals(
            "the visible drawing is the truth",
            listOf("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(replacement)),
            cellSrcs(cell)
        )
    }

    @Test
    fun aStructureBeforeABodyPictureKeepsThePicturesBytes() {
        val chemA = chem(diagram = pngDataUri(8, 8))
        val chemB = chem(diagram = pngDataUri(9, 9))
        val paddedA = java.util.Base64.getEncoder().encodeToString(pngBytes(14, 14))
        val paddedB = java.util.Base64.getEncoder().encodeToString(pngBytes(15, 15))
        // Body-level exactness too: an unpadded and an uppercase form.
        val imageA = "data:image/png;base64," + paddedA.trimEnd('=')
        val imageB = "DATA:IMAGE/PNG;BASE64,$paddedB"
        val q = QuestionDto(
            id = "q1",
            text = "Read the list.",
            elements = listOf(
                ContentElement.ChemElement(
                    smiles = chemA.smiles,
                    formula = chemA.formula,
                    caption = chemA.caption,
                    spoken = chemA.spoken,
                    diagram = chemA.diagram
                ),
                ContentElement.ImageElement(imageA),
                ContentElement.ImageElement(imageB),
                ContentElement.ChemElement(
                    smiles = chemB.smiles,
                    formula = chemB.formula,
                    caption = chemB.caption,
                    spoken = chemB.spoken,
                    diagram = chemB.diagram
                )
            ),
            options = listOf(OptionDto(id = "a", text = "Alpha"), OptionDto(id = "b", text = "Beta")),
            correctOptionIds = listOf("a")
        )
        val paper = PaperDto(
            id = "p1",
            title = "Body order",
            categories = listOf(CategoryDto(id = "c1", title = "C", questions = listOf(q)))
        )
        val parsed = parseDocx(docxOf(paper))
        assertTrue("an untouched export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val after = singleQuestion(parsed)
        assertEquals(
            "each structure keeps its own diagram",
            listOf(chemA.diagram, chemB.diagram),
            after.elements.filterIsInstance<ContentElement.ChemElement>().map { it.diagram }
        )
        assertEquals(
            "a body picture after a structure keeps its own src",
            listOf(imageA, imageB),
            after.elements.filterIsInstance<ContentElement.ImageElement>().map { it.src }
        )
        assertEquals(CardContent.of(q), CardContent.of(after))
    }

    @Test
    fun everyStoredPictureSrcFormRoundTripsExactly() {
        val padded = java.util.Base64.getEncoder().encodeToString(pngBytes(8, 8))
        val unpadded = padded.trimEnd('=')
        val forms = listOf(
            "data:image/png;base64,$padded",
            "data:image/jpg;base64,$padded",
            "DATA:IMAGE/PNG;BASE64,$padded",
            "data:image/png;base64,$unpadded",
            "data:image/gif;base64,$padded",
            "data:image/webp;base64,$padded",
            // Non-canonical tail bits: the same bytes as `...RA==` above.
            "data:image/png;base64,${padded.dropLast(3)}B=="
        )
        val cells = forms.map { "x <img src=\"$it\"> y" } +
            "x <img src=\"data:image/png;base64,$padded\" style=\"width:120px\" alt=\"mine\"> y"
        val rows = listOf(cells)
        val paper = paper(rows)
        val files = entries(docxOf(paper))
        assertTrue(
            "every form must embed a picture",
            files.keys.any { it.startsWith("word/media/") }
        )
        val payload = payloadJson(files)
        assertTrue(
            "the jpg alias is stored exactly: $payload",
            payload.contains("mcqapp:img|data|image|jpg|base64|")
        )
        assertTrue(
            "uppercase scheme/MIME/token are stored exactly: $payload",
            payload.contains("mcqapp:img|DATA|IMAGE|PNG|BASE64|")
        )
        assertFalse(
            "no image bytes or data URI may stay in the payload",
            payload.contains("data:image")
        )
        val parsed = parseDocx(docxOf(paper))
        assertTrue("an untouched export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val after = singleQuestion(parsed)
        val afterRows = after.explanationElements
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals(
            "png/jpg/uppercase/unpadded/gif/webp/style forms must round-trip byte-identically",
            rows,
            afterRows
        )
        assertEquals(CardContent.of(paper.categories.single().questions.single()), CardContent.of(after))
    }

    @Test
    fun nonEmbeddableDataPicturesSurviveUntouched() {
        val svg = "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="
        val bmp = "data:image/bmp;base64,Qk0AAAA="
        val rows = listOf(listOf("s <img src=\"$svg\">", "b <img src=\"$bmp\">"))
        val paper = paper(rows)
        val files = entries(docxOf(paper))
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue(
            "an SVG/BMP cell picture must not become media",
            files.keys.none { it.startsWith("word/media/") }
        )
        assertFalse("the SVG bytes must not be visible", xml.contains("PHN2Zz"))
        assertFalse("the BMP bytes must not be visible", xml.contains("Qk0AAAA"))
        assertTrue("the visible cell says a picture was there", xml.contains("[image]"))

        val parsed = parseDocx(docxOf(paper))
        assertTrue(
            "a non-embeddable data picture must not make the payload stale: ${parsed.warnings}",
            parsed.warnings.isEmpty()
        )
        val after = singleQuestion(parsed)
        assertEquals(
            "SVG/BMP cells must round-trip byte-identically",
            rows,
            after.explanationElements.filterIsInstance<ContentElement.TableElement>().single().rows
        )
        assertEquals(CardContent.of(paper.categories.single().questions.single()), CardContent.of(after))
    }

    @Test
    fun canonIsTypedAndCannotForgeBoundaries() {
        val png = pngDataUri()
        assertNotEquals(
            "prose `i` must not equal a picture marker",
            canonCell("i"),
            canonCell("<img src=\"$png\">")
        )
        assertNotEquals(
            "a canonical picture tag still differs from prose `i`",
            canonCell("i"),
            canonCell(cellImageTag(png))
        )
        assertNotEquals(
            "a cell-local separator must not forge a cell boundary",
            canonTable(listOf(listOf("a\u001Fb"))),
            canonTable(listOf(listOf("a", "b")))
        )
        assertNotEquals(
            "a row-local separator must not forge a row boundary",
            canonTable(listOf(listOf("a\u001Eb"))),
            canonTable(listOf(listOf("a"), listOf("b")))
        )
    }

    @Test
    fun uppercaseScalarImagesRoundTripExactly() {
        val paddedA = java.util.Base64.getEncoder().encodeToString(pngBytes(8, 8))
        val paddedB = java.util.Base64.getEncoder().encodeToString(pngBytes(9, 9))
        val paddedC = java.util.Base64.getEncoder().encodeToString(pngBytes(10, 10))
        val srcA = "DATA:IMAGE/PNG;BASE64,$paddedA"
        val srcB = "DATA:IMAGE/PNG;BASE64,$paddedB"
        val srcC = "DATA:IMAGE/PNG;BASE64,$paddedC"
        val q1 = QuestionDto(
            id = "q1",
            text = "Stem picture.",
            image = srcA,
            options = listOf(OptionDto(id = "a", text = "Alpha")),
            correctOptionIds = listOf("a")
        )
        val q2 = QuestionDto(
            id = "q2",
            text = "Card front",
            options = emptyList(),
            explanation = "Card back",
            explanationImage = srcB
        )
        val passage = PassageDto(
            id = "ps1",
            title = "Passage",
            elements = listOf(ContentElement.TextElement("Body")),
            image = srcC
        )
        val q3 = QuestionDto(
            id = "q3",
            text = "Passage member.",
            options = listOf(OptionDto(id = "a", text = "Alpha")),
            correctOptionIds = listOf("a"),
            passageId = "ps1"
        )
        val paper = PaperDto(
            id = "p1",
            title = "Scalars",
            categories = listOf(CategoryDto(id = "c1", title = "C", questions = listOf(q1, q2, q3)))
        )
        val parsed = parseDocx(docxOf(paper, listOf(passage)))
        assertTrue(
            "an uppercase portable scalar must embed and stay fresh: ${parsed.warnings}",
            parsed.warnings.isEmpty()
        )
        val file = LegacyParser.parse(parsed.json)
        val after = file.papers.single().categories.single().questions.associateBy { it.id }
        assertEquals("the stem scalar image must be byte-identical", srcA, after.getValue("q1").image)
        assertEquals(CardContent.of(q1), CardContent.of(after.getValue("q1")))
        assertEquals("the explanation scalar image must be byte-identical", srcB, after.getValue("q2").explanationImage)
        assertEquals(CardContent.of(q2), CardContent.of(after.getValue("q2")))
        assertEquals("the passage scalar image must be byte-identical", srcC, file.passages.single().image)
    }

    @Test
    fun aStructureAndPictureSwapInACellIsStale() {
        val chemSrc = pngDataUri(8, 8)
        val userSrc = pngDataUri(16, 16)
        val rows = listOf(listOf(chemCellHtml(chem(diagram = chemSrc)) + " " + cellImageTag(userSrc), "tail"))
        val paper = paper(rows)
        val bytes = docxOf(paper)
        val tampered = swapFirstTwoMediaTargets(bytes)
        val parsed = parseDocx(tampered)
        assertEquals(
            "swapping the structure drawing with the picture must be exactly one stale warning: ${parsed.warnings}",
            1,
            parsed.warnings.size
        )
        assertTrue(parsed.warnings.single().contains("hidden question data"))
    }

    @Test
    fun aStructureAndPictureSwapInABodyFieldIsRefused() {
        val chemSrc = pngDataUri(8, 8)
        val userSrc = pngDataUri(16, 16)
        val q = QuestionDto(
            id = "q1",
            text = "Body order",
            elements = listOf(
                ContentElement.ChemElement(
                    smiles = "CCO",
                    formula = "C2H5OH",
                    caption = "ethanol",
                    diagram = chemSrc
                ),
                ContentElement.ImageElement(userSrc)
            ),
            options = listOf(OptionDto(id = "a", text = "Alpha")),
            correctOptionIds = listOf("a")
        )
        val paper = PaperDto(
            id = "p1",
            title = "Body swap",
            categories = listOf(CategoryDto(id = "c1", title = "C", questions = listOf(q)))
        )
        val tampered = swapFirstTwoMediaTargets(docxOf(paper))
        val after = singleQuestion(parseDocx(tampered))
        assertTrue(
            "a refused graft must not fabricate a structure from the wrong bytes",
            after.elements.none { it is ContentElement.ChemElement }
        )
        assertEquals(
            "both visible pictures must survive the refusal",
            setOf(chemSrc, userSrc),
            after.elements.filterIsInstance<ContentElement.ImageElement>().map { it.src }.toSet()
        )
    }

    @Test
    fun aProtectedChemMarkerBesideAPictureRoundTripsExactly() {
        val picture = pngDataUri(10, 10)
        val marker = "<span class=\"mcq-chem-cell\" data-smiles=\"CCO\">ethanol CCO</span>"
        val cell = "<math><mtext>$marker</mtext></math> " + cellImageTag(picture)
        val rows = listOf(listOf(cell, "tail"))
        val paper = paper(rows, options = emptyList())
        val parsed = parseDocx(docxOf(paper))
        assertTrue("an untouched export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val after = singleQuestion(parsed)
        val afterRows = after.explanationElements.filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals("the protected-marker cell must round-trip byte-identically", rows, afterRows)
        assertFalse("no descriptor may survive into storage", afterRows[0][0].contains("mcqapp:img|"))
        assertEquals(listOf(picture), cellSrcs(afterRows[0][0]))
        assertEquals(CardContent.of(paper.categories.single().questions.single()), CardContent.of(after))
    }

    @Test
    fun aProtectedChemMarkerInAnAttributeValueRoundTripsExactly() {
        val picture = pngDataUri(12, 12)
        val marker = "<span class='mcq-chem-cell' data-smiles='CCO'>ethanol CCO</span>"
        val cell = "<b title=\"$marker\">bold</b> " + cellImageTag(picture)
        val rows = listOf(listOf(cell, "tail"))
        val paper = paper(rows, options = emptyList())
        val parsed = parseDocx(docxOf(paper))
        assertTrue("an untouched export must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val after = singleQuestion(parsed)
        val afterRows = after.explanationElements.filterIsInstance<ContentElement.TableElement>().single().rows
        assertEquals("the attribute-nested marker cell must round-trip byte-identically", rows, afterRows)
        assertFalse("no descriptor may survive into storage", afterRows[0][0].contains("mcqapp:img|"))
        assertEquals(listOf(picture), cellSrcs(afterRows[0][0]))
        assertEquals(CardContent.of(paper.categories.single().questions.single()), CardContent.of(after))
    }

    @Test
    fun anEleventhOptionPictureDescriptorNeverSurvives() {
        val png = pngDataUri(8, 8)
        val tablePng = pngDataUri(9, 9)
        val options = ('a'..'j').map { OptionDto(id = it.toString(), text = "Option $it") } +
            listOf(
                OptionDto(id = "k", elements = listOf(ContentElement.ImageElement(png))),
                OptionDto(
                    id = "l",
                    elements = listOf(
                        ContentElement.TableElement(listOf(listOf("cell " + cellImageTag(tablePng))))
                    )
                )
            )
        val q = QuestionDto(
            id = "q1",
            text = "Pick",
            options = options,
            correctOptionIds = listOf("a")
        )
        val paper = PaperDto(
            id = "p1",
            title = "Twelve options",
            categories = listOf(CategoryDto(id = "c1", title = "C", questions = listOf(q)))
        )
        val parsed = parseDocx(docxOf(paper))
        assertTrue("the payload must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val afterFile = LegacyParser.parse(parsed.json)
        assertNoStoredDescriptors(afterFile)
        val after = afterFile.papers.single().categories.single().questions.single()
        assertEquals("the payload keeps all twelve options", 12, after.options.size)
        assertTrue(
            "option 11's picture is dropped, never a descriptor",
            after.options[10].elements.none { it is ContentElement.ImageElement }
        )
        assertFalse(
            "option 12's table cell drops the descriptor",
            after.options[11].elements.filterIsInstance<ContentElement.TableElement>().single()
                .rows.flatten().any { it.contains("mcqapp:img|") }
        )

        // The next export/re-import is stable and also free of descriptors.
        val second = parseDocx(docxOf(afterFile.papers.single(), afterFile.passages))
        assertNoStoredDescriptors(LegacyParser.parse(second.json))
        assertTrue("the second pass must stay fresh: ${second.warnings}", second.warnings.isEmpty())
        assertEquals(
            12,
            LegacyParser.parse(second.json).papers.single().categories.single()
                .questions.single().options.size
        )
    }

    @Test
    fun aPayloadOnlyQuestionCannotKeepAPictureDescriptor() {
        val visible = QuestionDto(
            id = "q1",
            text = "Visible",
            options = listOf(OptionDto(id = "a", text = "Alpha"), OptionDto(id = "b", text = "Beta")),
            correctOptionIds = listOf("b")
        )
        val payloadOnly = QuestionDto(
            id = "q2",
            text = "",
            elements = emptyList(),
            options = listOf(
                OptionDto(
                    id = "a",
                    elements = listOf(ContentElement.ImageElement(pngDataUri(8, 8)))
                ),
                OptionDto(id = "b", text = "Beta")
            ),
            correctOptionIds = listOf("a")
        )
        val paper = PaperDto(
            id = "p1",
            title = "Payload only",
            categories = listOf(CategoryDto(id = "c1", title = "C", questions = listOf(visible, payloadOnly)))
        )
        val parsed = parseDocx(docxOf(paper))
        assertTrue("the payload must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        val afterFile = LegacyParser.parse(parsed.json)
        assertNoStoredDescriptors(afterFile)
        val after = afterFile.papers.single().categories.single().questions.associateBy { it.id }
        assertEquals(setOf("q1", "q2"), after.keys)
        assertTrue(
            "the payload-only question's picture is dropped, never a descriptor",
            after.getValue("q2").options.first().elements.none { it is ContentElement.ImageElement }
        )

        val second = parseDocx(docxOf(afterFile.papers.single(), afterFile.passages))
        assertNoStoredDescriptors(LegacyParser.parse(second.json))
        assertTrue("the second pass must stay fresh: ${second.warnings}", second.warnings.isEmpty())
    }

    @Test
    fun uppercaseNonPortableScalarImagesStayFresh() {
        val svgBytes = "<svg></svg>".toByteArray()
        val svgBody = java.util.Base64.getEncoder().encodeToString(svgBytes)
        val srcA = "DATA:image/svg+xml;base64,$svgBody"
        val srcB = "DATA:image/svg+xml;base64,$svgBody"
        val srcC = "DATA:image/svg+xml;base64,$svgBody"
        val q1 = QuestionDto(
            id = "q1",
            text = "Stem",
            image = srcA,
            options = listOf(OptionDto(id = "a", text = "Alpha")),
            correctOptionIds = listOf("a")
        )
        val q2 = QuestionDto(
            id = "q2",
            text = "Card",
            options = emptyList(),
            explanation = "Back",
            explanationImage = srcB
        )
        val q3 = QuestionDto(
            id = "q3",
            text = "Option",
            options = listOf(OptionDto(id = "a", text = "Alpha", image = srcC)),
            correctOptionIds = listOf("a")
        )
        val passage = PassageDto(
            id = "ps1",
            title = "Passage",
            elements = listOf(ContentElement.TextElement("Body")),
            image = srcC
        )
        val q4 = QuestionDto(
            id = "q4",
            text = "Member",
            options = listOf(OptionDto(id = "a", text = "Alpha")),
            correctOptionIds = listOf("a"),
            passageId = "ps1"
        )
        val paper = PaperDto(
            id = "p1",
            title = "Non-portable scalars",
            categories = listOf(
                CategoryDto(id = "c1", title = "C", questions = listOf(q1, q2, q3, q4))
            )
        )
        val parsed = parseDocx(docxOf(paper, listOf(passage)))
        assertTrue(
            "an uppercase non-portable scalar must not go stale: ${parsed.warnings}",
            parsed.warnings.isEmpty()
        )
        val file = LegacyParser.parse(parsed.json)
        val after = file.papers.single().categories.single().questions.associateBy { it.id }
        // Inherited body behaviour: the SVG bytes ride a `.jpg` media entry and
        // the visible reader returns image/jpeg; the src (and therefore the
        // schedule) changes, but no picture or metadata is lost and there is no
        // stale path. Cell SVG/BMP is exact and stays verbatim in the payload.
        val stem = after.getValue("q1").image!!
        assertTrue("the body picture is the embedded JPEG form: $stem", stem.startsWith("data:image/jpeg;base64,"))
        assertTrue(stem.substringAfter(',').let { java.util.Base64.getDecoder().decode(it) }.contentEquals(svgBytes))
        assertNotEquals(CardContent.of(q1), CardContent.of(after.getValue("q1")))
        val explanation = after.getValue("q2").explanationImage!!
        assertTrue(explanation.startsWith("data:image/jpeg;base64,"))
        assertTrue(explanation.substringAfter(',').let { java.util.Base64.getDecoder().decode(it) }.contentEquals(svgBytes))
        val optionImage = after.getValue("q3").options.first().elements
            .filterIsInstance<ContentElement.ImageElement>().single().src
        assertTrue(optionImage.startsWith("data:image/jpeg;base64,"))
        assertTrue(optionImage.substringAfter(',').let { java.util.Base64.getDecoder().decode(it) }.contentEquals(svgBytes))
        val passageImage = file.passages.single().image!!
        assertTrue(passageImage.startsWith("data:image/jpeg;base64,"))
        assertTrue(passageImage.substringAfter(',').let { java.util.Base64.getDecoder().decode(it) }.contentEquals(svgBytes))
    }

    @Test
    fun descriptorIsNotCountedAsALocalFileReference() {
        val rows = listOf(
            listOf(
                "picture " + cellImageTag(pngDataUri(8, 8)),
                "file <img src=\"file:///tmp/x.png\">"
            )
        )
        val parsed = parseDocx(docxOf(paper(rows)))
        val dto = LegacyParser.parse(parsed.json)
        val local = dto.warnings.filter { it.contains("point at files on the author's computer") }
        assertEquals("exactly the real file reference is warned about: ${dto.warnings}", 1, local.size)
        assertTrue("the count must be the real file reference: ${local.single()}", local.single().contains("1 image(s)"))
        assertTrue("the example must be the file path: ${local.single()}", local.single().contains("file:///tmp/x.png"))
        assertFalse("the internal descriptor must not be quoted: ${local.single()}", local.single().contains("mcqapp:img|"))
        assertNoStoredDescriptors(dto)
    }

    @Test
    fun descriptorOnlyPaperHasNoLocalImageWarning() {
        val rows = listOf(listOf("picture " + cellImageTag(pngDataUri(8, 8))))
        val parsed = parseDocx(docxOf(paper(rows)))
        val dto = LegacyParser.parse(parsed.json)
        assertFalse(
            "a descriptor is embedded, not a local file: ${dto.warnings}",
            dto.warnings.any { it.contains("point at files on the author's computer") }
        )
        assertTrue("the payload must stay fresh: ${parsed.warnings}", parsed.warnings.isEmpty())
        assertNoStoredDescriptors(dto)
    }

    @Test
    fun aDescriptorWithoutAVisibleCounterpartDegradesInsteadOfThrowing() {
        val descriptor = imageSrcDescriptor(pngDataUri())!!
        val cell = "cell <img src=\"$descriptor\">"

        // Missing visible row: the helper drops the descriptor, never throws.
        val rows = graftTable(listOf(listOf(cell)), emptyList())
        assertFalse(rows.flatten().any { it.contains("mcqapp:img|") })

        // Missing visible table: same degrade for a TableElement.
        val table = ContentElement.TableElement(listOf(listOf(cell)))
        val graftedTable = graftElements(listOf(table), emptyList())
            .filterIsInstance<ContentElement.TableElement>().single().rows
        assertFalse(graftedTable.flatten().any { it.contains("mcqapp:img|") })

        // No visible field at all: the visible (empty) element list wins.
        assertTrue(graftElements(listOf(ContentElement.ImageElement(descriptor)), emptyList()).isEmpty())
    }

    /** Assert no descriptor is storable anywhere in the DTO's content fields. */
    private fun assertNoStoredDescriptors(file: McqFileDto) {
        fun checkElements(elements: List<ContentElement>) {
            for (element in elements) {
                when (element) {
                    is ContentElement.ImageElement -> assertFalse(
                        "descriptor in an element: ${element.src}",
                        isImageSrcDescriptor(element.src)
                    )
                    is ContentElement.TableElement -> element.rows.flatten().forEach {
                        assertFalse("descriptor in a cell: $it", it.contains("mcqapp:img|"))
                    }
                    else -> Unit
                }
            }
        }
        fun checkScalar(src: String?) {
            if (src != null) assertFalse("descriptor scalar: $src", isImageSrcDescriptor(src))
        }
        for (paper in file.papers) {
            val questions = paper.categories.flatMap { it.questions } + paper.questions
            for (question in questions) {
                checkElements(question.elements)
                checkScalar(question.image)
                question.options.forEach { option ->
                    checkElements(option.elements)
                    checkScalar(option.image)
                }
                checkElements(question.explanationElements)
                checkScalar(question.explanationImage)
            }
        }
        file.passages.forEach { passage ->
            checkElements(passage.elements)
            checkScalar(passage.image)
        }
    }

    /** Swap the first two media relationship targets, as a picture reorder. */
    private fun swapFirstTwoMediaTargets(bytes: ByteArray): ByteArray =
        rezip(bytes, "word/_rels/document.xml.rels") { rels ->
            rels.replace("Target=\"media/image1.png\"", "Target=\"__TMP__\"")
                .replace("Target=\"media/image2.png\"", "Target=\"media/image1.png\"")
                .replace("Target=\"__TMP__\"", "Target=\"media/image2.png\"")
        }

    /** Swap the first two cells of the first row of the first table. */
    private fun swapFirstTwoCellsInFirstRow(xml: String): String {
        val tableStart = xml.indexOf("<w:tbl>")
        val tableEnd = xml.indexOf("</w:tbl>", tableStart)
        if (tableStart < 0 || tableEnd < 0) return xml
        val table = xml.substring(tableStart, tableEnd)
        val row = Regex("<w:tr>.*?</w:tr>", RegexOption.DOT_MATCHES_ALL)
            .find(table)?.value ?: return xml
        val cells = Regex("<w:tc>.*?</w:tc>", RegexOption.DOT_MATCHES_ALL)
            .findAll(row).map { it.value }.toList()
        if (cells.size < 2) return xml
        val swappedRow = row.replaceFirst(cells[0] + cells[1], cells[1] + cells[0])
        val swappedTable = table.replaceFirst(row, swappedRow)
        return xml.substring(0, tableStart) + swappedTable + xml.substring(tableEnd)
    }

    /** Swap the first two rows of the first table, as a structural Word edit. */
    private fun swapFirstTwoTableRows(xml: String): String {
        val tableStart = xml.indexOf("<w:tbl>")
        val tableEnd = xml.indexOf("</w:tbl>", tableStart)
        if (tableStart < 0 || tableEnd < 0) return xml
        val table = xml.substring(tableStart, tableEnd)
        val rows = Regex("<w:tr>.*?</w:tr>", RegexOption.DOT_MATCHES_ALL)
            .findAll(table).map { it.value }.toList()
        if (rows.size < 2) return xml
        val swapped = table.replaceFirst(rows[0] + rows[1], rows[1] + rows[0])
        return xml.substring(0, tableStart) + swapped + xml.substring(tableEnd)
    }

    /** Rewrite one zip entry, as a Word edit would. */
    private fun rezip(bytes: ByteArray, entry: String, transform: (String) -> String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipInputStream(bytes.inputStream()).use { input ->
            java.util.zip.ZipOutputStream(out).use { output ->
                var e = input.nextEntry
                while (e != null) {
                    output.putNextEntry(java.util.zip.ZipEntry(e.name))
                    if (e.name == entry) {
                        output.write(transform(input.readBytes().toString(Charsets.UTF_8)).toByteArray())
                    } else {
                        output.write(input.readBytes())
                    }
                    output.closeEntry()
                    e = input.nextEntry
                }
            }
        }
        return out.toByteArray()
    }
}
