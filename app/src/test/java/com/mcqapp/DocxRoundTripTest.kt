package com.mcqapp

import com.mcqapp.data.docx.decodeXmlEntities
import com.mcqapp.data.docx.parseDocx
import com.mcqapp.data.normalizeMathText
import com.mcqapp.data.export.DocxPaperWriter
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.toContentJson
import com.mcqapp.domain.textContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Export the sample paper to Word, import it, export it again: the document
 * the writer produces must be readable by the app's own DOCX importer with
 * every nuance the visible format can carry — the category tree (including
 * subcategories), questions with their options and correct answers,
 * explanations, passages and their members, tables, pictures and formulas.
 *
 * The visible document cannot carry marks, difficulty or tags (no marker
 * reads them back), so those are deliberately absent from the shapes here —
 * this format is for reading and light editing, not backup.
 *
 * Like the Anki round trip, a single direction can look right while a
 * second pass quietly rearranges something, so the strong claim is the
 * second pass: once the first pass has normalised (entities decoded, option
 * ids renamed to letters, pictures re-embedded), nothing may move again.
 */
class DocxRoundTripTest {

    private val sampleFile: McqFileDto by lazy { LegacyParser.parse(sampleJson()) }
    private val samplePaper: PaperDto by lazy { sampleFile.papers.first() }

    private fun sampleJson(): String =
        listOf("src/main/assets/sample_paper.json", "app/src/main/assets/sample_paper.json")
            .firstNotNullOfOrNull { File(it).takeIf { file -> file.isFile } }
            ?.readText()
            ?: error("sample_paper.json not found from ${File(".").absolutePath}")

    // ------------------------------------------------------------------
    // Shapes: what the visible format can carry, compared semantically
    // ------------------------------------------------------------------

    private sealed interface El
    private data class T(val text: String) : El
    private data class M(val mathml: String) : El
    private data class Tb(val rows: List<List<String>>) : El

    /** Pictures compare by content: a data class would use array identity. */
    private data class Img(val base64: String) : El

    private fun shape(elements: List<ContentElement>): List<El> = elements.map { element ->
        when (element) {
            // A paragraph before a table carries its newline; trailing
            // whitespace around blocks is the reader's business, not
            // content. Compare the trimmed text.
            is ContentElement.TextElement -> T(decodeXmlEntities(element.text).trim())
            // Formulas re-tokenise (and the reader wraps script parts in
            // mrow) on the way back; canonicalised they agree.
            is ContentElement.MathElement ->
                M(
                    normalizeMathText(decodeXmlEntities(element.mathml))
                        .replace("<mrow>", "")
                        .replace("</mrow>", "")
                )
            is ContentElement.TableElement -> Tb(element.rows)
            is ContentElement.ImageElement ->
                Img(element.src.substringAfter(','))
        }
    }

    private data class OptionShape(val elements: List<El>, val correct: Boolean)

    private data class QuestionShape(
        val elements: List<El>,
        val options: List<OptionShape>,
        val explanation: List<El>
    )

    /**
     * Consecutive text elements render as separate paragraphs in the app;
     * the reader merges paragraphs into one text element with a newline.
     * Same visible content, so the canonical form merges them — a merge
     * that loses the newline would still be caught (it must contain one).
     */
    private fun canonical(elements: List<El>): List<El> {
        val out = mutableListOf<El>()
        for (element in elements) {
            val last = out.lastOrNull()
            if (element is T && last is T) {
                out[out.lastIndex] = T(last.text + "\n" + element.text)
            } else {
                out.add(element)
            }
        }
        return out
    }

    /**
     * The scalar `image` slots have no marker of their own, so the document
     * carries the picture as an element — a copy, not a move, exactly like
     * the Anki round trip's derived fields. The sample side folds its scalar
     * images in, so both sides describe the same visible content.
     */
    private fun com.mcqapp.data.io.QuestionDto.shape(): QuestionShape {
        fun fold(elements: List<ContentElement>, scalar: String?): List<El> {
            val merged = if (scalar != null &&
                elements.none { it is ContentElement.ImageElement && it.src == scalar }
            ) {
                elements + ContentElement.ImageElement(scalar)
            } else {
                elements
            }
            return canonical(shape(merged))
        }
        return QuestionShape(
            elements = fold(elements, image),
            options = options.map { option ->
                OptionShape(fold(option.elements, option.image), option.id in correctOptionIds)
            },
            explanation = fold(explanationElements, explanationImage)
        )
    }

    private fun allQuestions(paper: PaperDto): List<com.mcqapp.data.io.QuestionDto> =
        paper.categories.flatMap { it.questions }

    /**
     * Questions the visible format can carry: the marker grammar demands
     * options and a stem, so the sample's deliberate option-less edge case
     * is written out by the exporter (with a warning) rather than emitted
     * as a question the importer would refuse the whole document over.
     */
    private fun carryable(paper: PaperDto): List<com.mcqapp.data.io.QuestionDto> =
        allQuestions(paper).filter { q ->
            q.options.isNotEmpty() &&
                (q.elements.isNotEmpty() || q.text.isNotBlank() || q.image != null)
        }

    private fun shapes(paper: PaperDto): List<QuestionShape> =
        allQuestions(paper).map { it.shape() }

    /** Category tree as (title -> parent title) so ids never leak in. */
    private fun tree(paper: PaperDto): Map<String, String?> {
        val titles = paper.categories.associate { it.id to it.title }
        return paper.categories.associate { it.title to it.parentId?.let { id -> titles[id] } }
    }

    private fun questionPassageTitles(paper: PaperDto): List<String?> {
        val titles = paper.passages.associate { it.id to it.title }
        return allQuestions(paper).map { it.passageId?.let { id -> titles[id] } }
    }

    // ------------------------------------------------------------------
    // The round trip
    // ------------------------------------------------------------------

    private fun export(paper: PaperDto, file: McqFileDto): ByteArray =
        docxOf(paper, file.passages)

    @Test
    fun firstPassPreservesEverythingTheFormatCarries() {
        val bytes = export(samplePaper, sampleFile)
        val parsed = parseDocx(bytes)
        assertTrue(
            "importing our own export must not warn: ${parsed.warnings}",
            parsed.warnings.isEmpty()
        )
        val imported = LegacyParser.parse(parsed.json)
        val after = imported.papers.single()

        assertEquals(
            "every question must survive — including the ones the visible "
                + "markers cannot carry (the payload does)",
            allQuestions(samplePaper).size,
            allQuestions(after).size
        )
        val before = allQuestions(samplePaper).map { it.shape() }
        val afterShapes = shapes(after)
        val changed = before.indices.filter { before[it] != afterShapes[it] }
        assertEquals(
            "importing our own export changed the content of question #" +
                (changed.firstOrNull()?.plus(1)) + ": " +
                changed.take(3).joinToString { i -> "\nBEFORE ${before[i]}\nAFTER  ${afterShapes[i]}" },
            emptyList<Int>(),
            changed
        )
        // The tree: Science above Chemistry and Physics, the rest top level.
        assertEquals(tree(samplePaper), tree(after))
        // Passage groups: same titles above the same members.
        assertEquals(questionPassageTitles(samplePaper), questionPassageTitles(after))
        // The paper title comes from Heading 1.
        assertEquals(samplePaper.title, after.title)
    }

    /**
     * The payload's whole point: everything the visible markers drop —
     * ids, marks, difficulty, tags, the answerless edge cases — comes back
     * untouched, so a re-import recognises every question instead of
     * adding duplicates.
     */
    @Test
    fun payloadPreservesWhatMarkersCannotCarry() {
        val bytes = export(samplePaper, sampleFile)
        val after = LegacyParser.parse(parseDocx(bytes).json).papers.single()

        assertEquals(
            "ids must survive so re-import deduplicates",
            allQuestions(samplePaper).map { it.id },
            allQuestions(after).map { it.id }
        )
        allQuestions(samplePaper).forEachIndexed { index, q ->
            val got = allQuestions(after)[index]
            assertEquals("marks of ${q.id}", q.marks, got.marks, 0.0)
            assertEquals("difficulty of ${q.id}", q.difficulty, got.difficulty)
            assertEquals("tags of ${q.id}", q.tags, got.tags)
            assertEquals("correct of ${q.id}", q.correctOptionIds, got.correctOptionIds)
        }
    }

    /**
     * A stored line that happens to start with a marker must stay text on the
     * way back. The writer escapes such a line with one leading space; the
     * guard has to cover the parser's whole option grammar, not just the ten
     * labels the app accepts, because the parser matches any letter and
     * refuses an out-of-range one. An option that continued "(k) ..." on its
     * own line was written bare, and the app's own export then could not be
     * imported: "option (k) is not supported; use (a) to (j)."
     *
     * The in-range "(b)" and "(c)" lines are the control: they were guarded
     * before and must still be, and must still come back with their text
     * intact. The stem and explanation carry the same shapes to show the
     * guard is applied wherever a field breaks into lines.
     */
    @Test
    fun markerShapedLinesInEveryFieldSurviveTheRoundTrip() {
        val question = com.mcqapp.data.io.QuestionDto(
            id = "q-marker",
            // The marker-shaped lines use the newline form the app's own DOCX
            // import stores (the writer breaks a run on `\n` exactly as on a
            // `<br>` tag, and the reader echoes `\n` back), so the hidden
            // payload must come back fresh and the texts can be compared
            // verbatim.
            text = "Pick one:\n(k) the step after the last option\n(b) an in-range letter",
            options = listOf(
                com.mcqapp.data.io.OptionDto(
                    "a",
                    "first\n(k) a step inside an option\n(b) an in-range letter inside an option"
                ),
                com.mcqapp.data.io.OptionDto("b", "second")
            ),
            correctOptionIds = listOf("a"),
            explanation = "because\n(z) the last step\n(c) an in-range letter"
        )
        val paper = PaperDto(
            id = "p-markers",
            title = "Markers",
            categories = listOf(
                com.mcqapp.data.io.CategoryDto(
                    id = "c1",
                    title = "Category",
                    questions = listOf(question)
                )
            )
        )

        val parsed = parseDocx(docxOf(paper))
        assertTrue(
            "our own export must import without warnings: ${parsed.warnings}",
            parsed.warnings.isEmpty()
        )
        val after = allQuestions(LegacyParser.parse(parsed.json).papers.single()).single()

        assertEquals("the stem must come back verbatim", question.text, after.text)
        assertEquals(
            "every option must come back verbatim",
            question.options.map { it.text },
            after.options.map { it.text }
        )
        assertEquals("the explanation must come back verbatim", question.explanation, after.explanation)
    }

    /**
     * XML 1.0 cannot carry the C0 controls, so the writer drops them: the
     * import must still succeed (it used to die with an XML parse error —
     * one U+0007 made the whole package unreadable), and every legal
     * character around them must survive untouched.
     */
    @Test
    fun controlCharactersAreDroppedOnExportAndTheImportStillWorks() {
        val question = com.mcqapp.data.io.QuestionDto(
            id = "q-controls",
            text = "Before\u0007after\u000Bform:\u000Cfeed\u001Fend",
            options = listOf(
                com.mcqapp.data.io.OptionDto("a", "first"),
                com.mcqapp.data.io.OptionDto("b", "second")
            ),
            correctOptionIds = listOf("a"),
            explanation = "because\u001Bescape"
        )
        val paper = PaperDto(
            id = "p-controls",
            title = "Controls",
            categories = listOf(
                com.mcqapp.data.io.CategoryDto(id = "c1", title = "Category", questions = listOf(question))
            )
        )

        val parsed = parseDocx(docxOf(paper))
        val after = allQuestions(LegacyParser.parse(parsed.json).papers.single()).single()

        assertEquals("the stem must come back with the controls dropped", "Beforeafterform:feedend", after.text)
        assertEquals(
            "the options must come back untouched",
            question.options.map { it.text },
            after.options.map { it.text }
        )
        assertEquals("the explanation must come back with the controls dropped", "becauseescape", after.explanation)
    }

    @Test
    fun aSecondPassThroughTheFormatChangesNothing() {
        val bytes = export(samplePaper, sampleFile)
        val passOneFile = LegacyParser.parse(parseDocx(bytes).json)
        val passOne = passOneFile.papers.single()
        val bytesTwo = export(passOne, passOneFile)
        val passTwo = LegacyParser.parse(parseDocx(bytesTwo).json).papers.single()

        val one = shapes(passOne)
        val two = shapes(passTwo)
        assertEquals(one.size, two.size)
        val moved = one.indices.filter { one[it] != two[it] }
        assertEquals(
            "the second export must describe the same questions, but these moved: " +
                moved.joinToString { i -> "\nPASS1 ${one[i]}\nPASS2 ${two[i]}" },
            emptyList<Int>(),
            moved
        )
        assertEquals(tree(passOne), tree(passTwo))
        assertEquals(questionPassageTitles(passOne), questionPassageTitles(passTwo))
    }

    /**
     * Ids are content hashes on import, so re-importing the same document
     * must not duplicate: the second parse yields the same ids the first
     * one did.
     */
    @Test
    fun idsAreStableAcrossParses() {
        val bytes = export(samplePaper, sampleFile)
        val passOneFile = LegacyParser.parse(parseDocx(bytes).json)
        val passOne = passOneFile.papers.single()
        val passTwo = LegacyParser.parse(parseDocx(export(passOne, passOneFile)).json).papers.single()
        assertEquals(
            allQuestions(passOne).map { it.id },
            allQuestions(passTwo).map { it.id }
        )
    }

    /**
     * Passage membership after more than one passage: the question stream
     * the parser walks has passage bodies blanked in place, so each
     * question must bind to the passage whose block precedes it — a member
     * of the second passage must not drift onto the first.
     */
    @Test
    fun twoPassagesBindTheirMembers() {
        fun member(id: String, passageId: String) = com.mcqapp.data.io.QuestionDto(
            id = id,
            text = "Question $id",
            options = listOf(
                com.mcqapp.data.io.OptionDto(id = "a", text = "x"),
                com.mcqapp.data.io.OptionDto(id = "b", text = "y")
            ),
            correctOptionIds = listOf("a"),
            passageId = passageId
        )
        val paper = PaperDto(
            id = "p",
            title = "Passage Paper",
            categories = listOf(
                // Members of two passages, then a standalone question after
                // the last passage, then a second category (section) that
                // reuses passage-1.
                com.mcqapp.data.io.CategoryDto(
                    id = "c1",
                    title = "First",
                    questions = listOf(
                        member("q-a1", "passage-1"),
                        member("q-a2", "passage-1"),
                        member("q-b1", "passage-2"),
                        member("q-solo", "")
                    )
                ),
                com.mcqapp.data.io.CategoryDto(
                    id = "c2",
                    title = "Second",
                    questions = listOf(member("q-a3", "passage-1"))
                )
            )
        )
        val passages = listOf(
            com.mcqapp.data.io.PassageDto(
                id = "passage-1",
                title = "Alpha",
                elements = listOf(com.mcqapp.domain.ContentElement.TextElement("Body alpha."))
            ),
            com.mcqapp.data.io.PassageDto(
                id = "passage-2",
                title = "Beta",
                elements = listOf(com.mcqapp.domain.ContentElement.TextElement("Body beta."))
            )
        )
        val bytes = docxOf(paper, passages)
        val parsed = parseDocx(bytes)
        // An untouched export carries the payload, so the original ids and
        // passage ids come straight back.
        val afterFile = LegacyParser.parse(parsed.json)
        val after = afterFile.papers.single()
        val byStem = allQuestions(after).associateBy { it.text.trim() }
        val titles = afterFile.passages.associate { it.id to it.title }
        assertEquals("Alpha", titles[byStem.getValue("Question q-a1").passageId])
        assertEquals("Alpha", titles[byStem.getValue("Question q-a2").passageId])
        assertEquals("Beta", titles[byStem.getValue("Question q-b1").passageId])
        assertEquals("Alpha", titles[byStem.getValue("Question q-a3").passageId])
        assertNull(byStem.getValue("Question q-solo").passageId)
    }

    /**
     * Editing the visible text in Word must not resurrect stale metadata:
     * a payload that no longer matches the body is ignored, and the
     * markers (which cannot carry marks, difficulty or tags) take over.
     */
    @Test
    fun editedDocumentFallsBackToVisibleMarkers() {
        val bytes = export(samplePaper, sampleFile)
        val tampered = rezip(bytes, "word/document.xml") { xml ->
            xml.replace("A car starts from rest", "A truck starts from rest")
        }
        val parsed = parseDocx(tampered)
        val after = LegacyParser.parse(parsed.json).papers.single()
        assertTrue(
            "the fallback must say why: ${parsed.warnings}",
            parsed.warnings.any { it.contains("hidden question data") }
        )
        // Marker path: the metadata the markers cannot carry is gone, and
        // the option-less edge case is written out of the visible body.
        assertEquals(allQuestions(samplePaper).size - 1, allQuestions(after).size)
        assertEquals(1.0, allQuestions(after)[0].marks, 0.0)
    }

    /**
     * 2.1: an option edited in Word is an edit like any other — the stems
     * still match, but the full-content fingerprint must notice the option
     * text changed and refuse the stale payload.
     */
    @Test
    fun optionEditFallsBackToVisibleMarkers() {
        val bytes = export(samplePaper, sampleFile)
        val tampered = rezip(bytes, "word/document.xml") { xml ->
            xml.replace(
                "A gas has a fixed volume but no fixed shape.",
                "A liquid has a fixed volume but no fixed shape."
            )
        }
        val parsed = parseDocx(tampered)
        assertTrue(
            "the fallback must say why: ${parsed.warnings}",
            parsed.warnings.any { it.contains("hidden question data") }
        )
        val after = LegacyParser.parse(parsed.json).papers.single()
        val first = allQuestions(after).first()
        assertTrue(
            "the edited option text must survive",
            first.options.any { option ->
                option.elements.any { element ->
                    element is ContentElement.TextElement &&
                        element.text.contains("A liquid has a fixed volume")
                }
            }
        )
        // Metadata the markers cannot carry is gone with the payload.
        assertEquals(1.0, first.marks, 0.0)
    }

    /**
     * 2.1: the answer key is visible text (`Ans. b, c`); editing it must
     * disable the payload, or the edit is silently reverted on import.
     */
    @Test
    fun answerKeyEditFallsBackToVisibleMarkers() {
        val bytes = export(samplePaper, sampleFile)
        val tampered = rezip(bytes, "word/document.xml") { xml ->
            xml.replace("Ans. b, c", "Ans. a")
        }
        val parsed = parseDocx(tampered)
        assertTrue(
            "the fallback must say why: ${parsed.warnings}",
            parsed.warnings.any { it.contains("hidden question data") }
        )
        val after = LegacyParser.parse(parsed.json).papers.single()
        assertEquals(listOf("a"), allQuestions(after).first().correctOptionIds)
    }

    /**
     * 2.1: renaming the paper in Word is an edit too — the payload's title
     * must not silently win over the visible one.
     */
    @Test
    fun titleEditFallsBackToVisibleMarkers() {
        val bytes = export(samplePaper, sampleFile)
        val tampered = rezip(bytes, "word/document.xml") { xml ->
            xml.replace("Sample Bank", "Renamed Bank")
        }
        val parsed = parseDocx(tampered)
        assertTrue(
            "the fallback must say why: ${parsed.warnings}",
            parsed.warnings.any { it.contains("hidden question data") }
        )
        val after = LegacyParser.parse(parsed.json)
        assertEquals("Renamed Bank — English + हिन्दी", after.papers.single().title)
        assertEquals("Renamed Bank — English + हिन्दी", parsed.paperTitle)
    }

    /**
     * 3.1: the hidden payload must not duplicate the images as base64 (the
     * same bytes ship as word/media entries, and a multi-MB property risks
     * Word reporting unreadable content). An untouched re-import still
     * grafts the real srcs back from the body.
     */
    @Test
    fun untouchedPayloadOmitsImageBytesButReimportGraftsThem() {
        val bytes = export(samplePaper, sampleFile)
        val custom = readEntry(bytes, "docProps/custom.xml")
        assertTrue(
            "the payload must carry no base64 image bytes",
            !custom.contains("data:image")
        )
        val parsed = parseDocx(bytes)
        assertTrue(
            "an untouched export must stay fresh: ${parsed.warnings}",
            parsed.warnings.none { it.contains("hidden question data") }
        )
        val after = LegacyParser.parse(parsed.json)
        val images = after.papers.flatMap { allQuestions(it) }
            .flatMap { q -> q.elements + q.explanationElements + q.options.flatMap { it.elements } }
            .filterIsInstance<ContentElement.ImageElement>()
        assertTrue("the sample carries pictures", images.isNotEmpty())
        assertTrue(
            "grafted srcs must be real bytes",
            images.all { it.src.startsWith("data:image") }
        )
    }

    /**
     * A remote image URL rides only in the payload (the document body
     * cannot carry it); the strip must keep it and the fingerprint must
     * not read its absence from the body as an edit.
     */
    @Test
    fun remoteImageUrlSurvivesTheRoundTrip() {
        val remote = "https://example.com/pic.png"
        val question = com.mcqapp.data.io.QuestionDto(
            id = "q-remote",
            text = "Question with a remote picture",
            elements = listOf(
                ContentElement.TextElement("Question with a remote picture"),
                ContentElement.ImageElement(remote)
            ),
            options = listOf(
                com.mcqapp.data.io.OptionDto(id = "a", text = "x"),
                com.mcqapp.data.io.OptionDto(id = "b", text = "y")
            ),
            correctOptionIds = listOf("a"),
            marks = 3.0
        )
        val paper = PaperDto(id = "p", title = "Remote Paper", questions = listOf(question))
        val bytes = docxOf(paper)
        val parsed = parseDocx(bytes)
        assertTrue(
            "a remote-only image is not an edit: ${parsed.warnings}",
            parsed.warnings.none { it.contains("hidden question data") }
        )
        val after = LegacyParser.parse(parsed.json).papers.single()
        val restored = allQuestions(after).single()
        assertEquals(remote, restored.elements.filterIsInstance<ContentElement.ImageElement>().single().src)
        assertEquals(3.0, restored.marks, 0.0)
    }

    private fun readEntry(bytes: ByteArray, entry: String): String {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { input ->
            var e = input.nextEntry
            while (e != null) {
                if (e.name == entry) return input.readBytes().toString(Charsets.UTF_8)
                e = input.nextEntry
            }
        }
        error("entry $entry not found")
    }

    private fun rezip(bytes: ByteArray, entry: String, transform: (String) -> String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { input ->
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

    /**
     * The payload must stay fresh on an untouched export — if this fails,
     * every re-import duplicates the whole paper.
     */
    @Test
    fun untouchedExportKeepsPayloadFresh() {
        val bytes = export(samplePaper, sampleFile)
        val parsed = parseDocx(bytes)
        assertTrue(
            "untouched export must not warn about stale payload: ${parsed.warnings}",
            parsed.warnings.none { it.contains("hidden question data") }
        )
        val after = LegacyParser.parse(parsed.json).papers.single()
        assertEquals(
            "ids must survive so re-import deduplicates",
            allQuestions(samplePaper).map { it.id },
            allQuestions(after).map { it.id }
        )
    }

    /**
     * The device export path rebuilds every DTO from the database: each
     * element list was stored as JSON and parsed back. That round trip must
     * not make the payload go stale — otherwise re-import duplicates the
     * whole paper.
     */
    @Test
    fun payloadSurvivesTheDatabaseRoundTrip() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        fun rt(elements: List<ContentElement>): List<ContentElement> =
            elements.toContentJson(json).parseContentElements(json)
        fun rtQuestion(q: com.mcqapp.data.io.QuestionDto) = q.copy(
            text = rt(q.elements).textContent,
            elements = rt(q.elements),
            explanation = rt(q.explanationElements).textContent,
            explanationElements = rt(q.explanationElements),
            options = q.options.map { o ->
                o.copy(
                    text = rt(o.elements).textContent,
                    elements = rt(o.elements)
                )
            }
        )
        val rtPaper = samplePaper.copy(
            categories = samplePaper.categories.map { c ->
                c.copy(questions = c.questions.map { rtQuestion(it) })
            },
            questions = samplePaper.questions.map { rtQuestion(it) }
        )
        val rtPassages = sampleFile.passages.map { p ->
            p.copy(elements = rt(p.elements))
        }
        val bytes = docxOf(rtPaper, rtPassages)
        val parsed = parseDocx(bytes)
        assertTrue(
            "db round-tripped export must not warn about stale payload: ${parsed.warnings}",
            parsed.warnings.none { it.contains("hidden question data") }
        )
        val after = LegacyParser.parse(parsed.json).papers.single()
        assertEquals(
            "ids must survive so re-import deduplicates",
            allQuestions(rtPaper).map { it.id },
            allQuestions(after).map { it.id }
        )
    }
}
