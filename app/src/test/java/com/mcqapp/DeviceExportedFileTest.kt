package com.mcqapp

import com.mcqapp.data.docx.parseDocx
import com.mcqapp.data.export.DocxPaperWriter
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.toContentJson
import com.mcqapp.domain.textContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The device path: the importer stores every blank field as one empty
 * TextElement (`.ifEmpty { listOf(TextElement(text)) }`), and the exporter
 * reads those element lists back out of the database. The payload built
 * from such DTOs must still fingerprint-match the visible body —
 * otherwise an untouched export re-imports as 32 new questions instead
 * of deduplicating (the 0.0.20 regression).
 *
 * The sample bank exercises every blank-field shape: q-e2 (no options,
 * left out of the body), q-e3 (no explanation), q-e5 (single option, no
 * explanation), and q-math-6 (a formula whose MathML carries MathML-level
 * entities like `&prime;` that the OMML round trip decodes).
 */
class DeviceExportedFileTest {

    private val sampleFile = LegacyParser.parse(sampleJson())
    private val samplePaper = sampleFile.papers.first()

    private fun sampleJson(): String =
        listOf("src/main/assets/sample_paper.json", "app/src/main/assets/sample_paper.json")
            .firstNotNullOfOrNull { File(it).takeIf { f -> f.isFile } }
            ?.readText()
            ?: error("sample_paper.json not found from ${File(".").absolutePath}")

    private fun allQuestions(paper: com.mcqapp.data.io.PaperDto) =
        paper.categories.flatMap { it.questions } + paper.questions

    /**
     * Mirrors what the importer stores and the exporter reads: every field
     * goes through `toContentJson` with the empty-list fallback filled by a
     * (possibly blank) TextElement, and every column parsed back.
     */
    private fun dbRoundTrip(file: com.mcqapp.data.io.McqFileDto): com.mcqapp.data.io.McqFileDto {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        fun stored(elements: List<ContentElement>, text: String): List<ContentElement> =
            elements.ifEmpty { listOf(ContentElement.TextElement(text)) }
                .toContentJson(json)
                .parseContentElements(json)
        fun rtQuestion(q: com.mcqapp.data.io.QuestionDto) = q.copy(
            text = stored(q.elements, q.text).textContent,
            elements = stored(q.elements, q.text),
            explanation = stored(q.explanationElements, q.explanation).textContent,
            explanationElements = stored(q.explanationElements, q.explanation),
            options = q.options.map { o ->
                o.copy(
                    text = stored(o.elements, o.text).textContent,
                    elements = stored(o.elements, o.text)
                )
            }
        )
        return file.copy(
            papers = file.papers.map { paper ->
                paper.copy(
                    categories = paper.categories.map { c ->
                        c.copy(questions = c.questions.map { rtQuestion(it) })
                    },
                    questions = paper.questions.map { rtQuestion(it) }
                )
            },
            passages = file.passages.map { p ->
                p.copy(
                    elements = stored(p.elements, "").let { els ->
                        if (p.elements.isEmpty()) els else p.elements
                    }
                )
            }
        )
    }

    @Test
    fun deviceShapedExportReimportKeepsEveryId() {
        val device = dbRoundTrip(sampleFile)
        val paper = device.papers.single()
        val bytes = DocxPaperWriter.paperToDocxBytes(paper, device.passages)

        val parsed = parseDocx(bytes)
        assertTrue(
            "payload must be accepted as fresh: ${parsed.warnings}",
            parsed.warnings.none { it.contains("hidden question data") }
        )
        val after = LegacyParser.parse(parsed.json).papers.single()
        assertEquals(
            "every id must survive so re-import deduplicates",
            allQuestions(paper).map { it.id },
            allQuestions(after).map { it.id }
        )
        assertEquals("paper id must come from the payload", paper.id, after.id)

        // The id-less question's option picture rides the structured
        // options_elements shape as a trailing element; the scalar field has
        // no room for it. Duplicate detection must read the picture
        // positionally (element srcs, then the scalar) or the content hash
        // changes and every export → re-import cycle reports the question
        // as updated.
        val original = allQuestions(paper).single { q -> q.options.any { o -> o.image != null } }
        val reimported = allQuestions(after).single { it.id == original.id }
        assertEquals(
            "content hash must be stable across the round trip",
            com.mcqapp.data.io.ContentHash.of(original),
            com.mcqapp.data.io.ContentHash.of(reimported)
        )
    }

    /**
     * The file actually exported on the failing device, when present.
     * Guarded, so CI (without the file) skips it.
     */
    @Test
    fun theDeviceExportedFileItselfParsesFresh() {
        val path = listOf(
            "Sample-Bank-—-English-+-हिन्दी (8).docx",
            "../Sample-Bank-—-English-+-हिन्दी (8).docx"
        ).firstNotNullOfOrNull { File(it).takeIf { f -> f.isFile } }
        assumeTrue("device-exported docx not present; skipping", path != null)
        val parsed = parseDocx(path!!.readBytes())
        assertTrue(
            "payload must be accepted as fresh: ${parsed.warnings}",
            parsed.warnings.none { it.contains("hidden question data") }
        )
        val after = LegacyParser.parse(parsed.json).papers.single()
        assertEquals(
            "ids must survive so re-import deduplicates",
            allQuestions(samplePaper).map { it.id },
            allQuestions(after).map { it.id }
        )
    }
}
