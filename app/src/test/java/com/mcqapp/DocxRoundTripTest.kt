package com.mcqapp

import com.mcqapp.data.docx.decodeXmlEntities
import com.mcqapp.data.docx.parseDocx
import com.mcqapp.data.normalizeMathText
import com.mcqapp.data.export.DocxPaperWriter
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.domain.ContentElement
import org.junit.Assert.assertEquals
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
        DocxPaperWriter.paperToDocxBytes(paper, file.passages)

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
}
