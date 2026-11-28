package com.mcqapp

import com.mcqapp.data.export.mediaKey
import com.mcqapp.data.io.CardContent
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.toQuestionEntity
import com.mcqapp.data.io.withScaledImages
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.importscreen.previewHashOf
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.CELL_IMAGE_TOO_LARGE_MESSAGE
import com.mcqapp.util.Logger
import com.mcqapp.util.MAX_CELL_IMAGE_BYTES
import com.mcqapp.util.cellHasPortableImage
import com.mcqapp.util.cellImageTag
import com.mcqapp.util.mapCellImages
import com.mcqapp.util.normalizeCellImages
import com.mcqapp.util.portableCellImageSrc
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The import normaliser for pictures inside table cells. Pure JVM: the real
 * downscaler needs a bitmap and therefore a Robolectric/CI run, so the scaler
 * is injected exactly like [DownscaledImagesTraversalTest] does.
 *
 * The stored form is the contract these tests pin: an unwrapped
 * `data:image/…;base64,…` inside the one canonical tag, and nothing bigger
 * than [MAX_CELL_IMAGE_BYTES] may get there at all.
 */
class CellImageNormalizationTest {

    private fun dataSrc(payload: String): String = "data:image/png;base64,$payload"

    private fun imageTag(src: String): String = "<img src=\"$src\">"

    private fun srcIn(cell: String): String =
        cell.substringAfter("src=\"", "").substringBefore("\"")

    private val identity: (String?) -> String? = { it }

    @Test
    fun aCellImageReachesTheScalerAndComesBackCanonical() {
        val src = dataSrc("AAAA")
        val seen = mutableListOf<String?>()
        val out = normalizeCellImages("a ${imageTag(src)} b", { s -> seen += s; s }) {
            fail("nothing is over the cap: $it")
        }
        assertEquals(listOf<String?>(src), seen)
        // Everything the outer tag carried is rebuilt, never copied.
        assertEquals("a ${cellImageTag(src)} b", out)
    }

    @Test
    fun aWrappedPayloadIsUnwrappedAndTheMediaKeyMatches() {
        val payload = "A".repeat(80)
        val wrapped = dataSrc(payload.chunked(4).joinToString("\r\n"))
        // The renderer refuses a wrapped payload; the import must still store
        // the picture, in the same shape its unwrapped twin has.
        assertFalse("wrapped is not portable for rendering", portableCellImageSrc(wrapped))

        val fromWrapped = normalizeCellImages(imageTag(wrapped), identity) { fail(it) }
        val fromPlain = normalizeCellImages(imageTag(dataSrc(payload)), identity) { fail(it) }

        assertEquals(fromPlain, fromWrapped)
        assertFalse("no CRLF may reach the row", fromWrapped.contains("\r") ||
            fromWrapped.contains("\n"))
        assertEquals(payload, srcIn(fromWrapped).substringAfter("base64,"))
        assertEquals(mediaKey(srcIn(fromPlain)), mediaKey(srcIn(fromWrapped)))
    }

    @Test
    fun anOversizedImageBecomesTheMarkerWithOneLogLine() {
        val oversized = Base64.getEncoder()
            .encodeToString(ByteArray(MAX_CELL_IMAGE_BYTES + 1))
        val info = mutableListOf<String>()
        val out = normalizeCellImages(
            "before ${imageTag(dataSrc(oversized))} after",
            identity
        ) { info += it }

        assertEquals("before $CELL_IMAGE_MARKER after", out)
        assertFalse(out.contains("base64"))
        assertEquals(listOf(CELL_IMAGE_TOO_LARGE_MESSAGE), info)
    }

    @Test
    fun severalOversizedImagesReportTheSameStableMessage() {
        val oversized = Base64.getEncoder()
            .encodeToString(ByteArray(MAX_CELL_IMAGE_BYTES + 1))
        val info = mutableListOf<String>()
        val out = normalizeCellImages(
            (1..3).joinToString(" ") { "x${imageTag(dataSrc(oversized))}" },
            identity
        ) { info += it }

        assertEquals(3, Regex(Regex.escape(CELL_IMAGE_MARKER)).findAll(out).count())
        // The message is stable, so Logger.warnOnce reports it once per
        // session; the normaliser itself reports every replaced tag.
        assertEquals(List(3) { CELL_IMAGE_TOO_LARGE_MESSAGE }, info)
        assertFalse(out.contains("base64"))
    }

    @Test
    fun severalOversizedImagesProduceOneSessionWarning() {
        Logger.clearRecentErrors()
        Logger.resetWarnOnce()
        try {
            val oversized = Base64.getEncoder()
                .encodeToString(ByteArray(MAX_CELL_IMAGE_BYTES + 1))
            val cell = (1..3).joinToString(" ") { "x${imageTag(dataSrc(oversized))}" }
            normalizeCellImages(cell, identity) { Logger.warnOnce("IMPORT", it) }

            val warnings = Logger.recentErrors().filter { it.contains("W/IMPORT") }
            assertEquals("one warning for the stable message: $warnings", 1, warnings.size)
        } finally {
            Logger.clearRecentErrors()
            Logger.resetWarnOnce()
        }
    }

    @Test
    fun normalisationIsIdempotent() {
        val once = normalizeCellImages(
            """a <img src="data:image/png;base64,AAAA" onerror="x()"> b""",
            identity
        ) { fail(it) }
        val twice = normalizeCellImages(once, identity) { fail(it) }
        assertEquals(once, twice)
        assertFalse("the hostile attribute is gone", twice.contains("onerror"))
    }

    @Test
    fun aTwoMegabyteCellNormalisesStably() {
        val prose = "x".repeat(2_000_000)
        val src = dataSrc("AAAA")
        val cell = "$prose <img hidden src=\"$src\">"
        val once = normalizeCellImages(cell, identity) { fail(it) }
        val twice = normalizeCellImages(once, identity) { fail(it) }

        assertEquals("$prose ${cellImageTag(src)}", once)
        assertEquals(once, twice)
    }

    @Test
    fun reimportingTheSameFileStoresTheCanonicalTag() {
        val payload = "AAAA".repeat(20)
        val wrapped = dataSrc(payload.chunked(4).joinToString("\r\n"))
        val file = bank("before ${imageTag(wrapped)} after")

        val first = LegacyParser.parse(file).papers.single()
            .categories.single().questions.single()
            .withScaledImages(identity, identity)
        val second = LegacyParser.parse(file).papers.single()
            .categories.single().questions.single()
            .withScaledImages(identity, identity)

        val cell = (first.elements.single() as ContentElement.TableElement).rows[0][0]
        assertEquals("before ${cellImageTag(dataSrc(payload))} after", cell)
        assertEquals(first.elements, second.elements)
        // A second pass over the already-normalised DTO is a no-op too.
        assertEquals(first.elements, first.withScaledImages(identity, identity).elements)
    }

    @Test
    fun aTornImgFragmentKeepsTheProseAndLosesThePayload() {
        val src = dataSrc("AAAA")
        val quoted = normalizeCellImages(
            """before <img src="$src" and after""",
            identity
        ) { fail(it) }
        assertEquals("before  and after", quoted)
        val unquoted = normalizeCellImages("before <img src=$src after", identity) { fail(it) }
        assertEquals("before  after", unquoted)
        listOf(quoted, unquoted).forEach { out ->
            assertFalse("the payload must never become prose", out.contains("base64"))
            assertFalse(out.contains("AAAA"))
        }
    }

    @Test
    fun aCompleteCanonicalTagAfterAStrayQuoteIsStillAPicture() {
        // A quote before the second tag used to make the first, failed scan the
        // whole cell's quote state; the bulk torn pass then reported the real
        // picture as null and deleted it. Each start must be scanned with its
        // own quote state.
        val src = dataSrc("AAAA")
        val canonical = cellImageTag(src)
        val cell = "a <img b\" c $canonical d\""

        assertTrue("the canonical picture is complete", cellHasPortableImage(cell))
        val out = normalizeCellImages(cell, identity) { fail(it) }
        assertTrue("the picture survives normalisation: $out", out.contains(canonical))
        assertTrue("the trailing prose survives: $out", out.contains(" d\""))
    }

    @Test
    fun aTornPrefixDoesNotSwallowTheProseBeforeTheNextTag() {
        // Resuming over the intervening text used to delete " hello ". The torn
        // span ends at its own prefix; the text before the next start is prose.
        val mapped = mapCellImages("<img src=\"x\" hello <img src=y>") { "IMG" }
        assertEquals("IMG hello IMG", mapped)
    }

    @Test
    fun aPayloadAfterATornImagePrefixStillGoesWithoutAClosingBracket() {
        // No `>` anywhere: the old resume at `i + 4` re-read the tail for every
        // start. The one forward pass now reports each torn prefix, so an
        // unquoted payload after a torn `<img` is removed rather than stored as
        // visible prose.
        val out = normalizeCellImages("a <img <img src=data:image/png;base64,AAAA", identity) {
            fail(it)
        }
        assertFalse("never the payload: $out", out.contains("base64"))
        assertFalse("never the payload: $out", out.contains("AAAA"))
        assertTrue("the prose stays: $out", out.startsWith("a"))
    }

    @Test
    fun aTornTagWithAValueLessTokenLosesThePayloadAndKeepsTheProse() {
        val src = dataSrc("AAAA")
        val cases = listOf(
            "before <img hidden src=$src" to "before ",
            "before <img alt=\"x\" hidden src=$src" to "before ",
            "before <img hidden src=$src after" to "before  after",
            "before <img alt=\"x\" hidden src=$src after" to "before  after"
        )
        cases.forEach { (cell, expected) ->
            val out = normalizeCellImages(cell, identity) { fail(it) }
            assertEquals("prose for $cell", expected, out)
            assertFalse("never the payload for $cell: $out", out.contains("base64"))
            assertFalse("never the payload for $cell: $out", out.contains("AAAA"))
        }
    }

    @Test
    fun valueLessAttributesAndASlashBeforeTheNameStillYieldTheSource() {
        val src = dataSrc("AAAA")
        val tags = listOf(
            """<img hidden src="$src">""",
            """<img/src="$src">""",
            """<img/ src="$src">""",
            """<img hidden alt="x" src="$src">"""
        )
        tags.forEach { tag ->
            val out = normalizeCellImages("a $tag b", identity) { fail(it) }
            assertEquals("canonical for $tag", "a ${cellImageTag(src)} b", out)
        }
    }

    @Test
    fun aCompleteTagWithNoReadableSourceFailsClosed() {
        val tags = listOf(
            "<img hidden>",
            "<img src>",
            "<img hidden src>",
            """<img alt="x">"""
        )
        tags.forEach { tag ->
            val out = normalizeCellImages("before $tag after", identity) { fail(it) }
            assertEquals("fail closed for $tag", "before $CELL_IMAGE_MARKER after", out)
        }
    }

    @Test
    fun anUnquotedSourceIsStillNormalised() {
        val src = dataSrc("AAAA")
        val out = normalizeCellImages("a <img src=$src> b", identity) { fail(it) }
        assertEquals("a ${cellImageTag(src)} b", out)
    }

    @Test
    fun aReadableNonPortableCellSourceIsLeftAlone() {
        // A file reference is not drawable, but it is still a readable src:
        // the normaliser leaves the author's tag verbatim and never scales it.
        // The parser warns about `file:` only; remote and SVG refs are silent.
        val tags = listOf(
            """<img src="file:///tmp/x.png">""",
            """<img hidden src="https://example.com/x.png">""",
            """<img src="data:image/svg+xml;base64,AAAA">"""
        )
        tags.forEach { tag ->
            var scaled = false
            val out = normalizeCellImages("a $tag b", { scaled = true; it }) { fail(it) }
            assertEquals("left verbatim: $tag", "a $tag b", out)
            assertFalse("not scaled: $tag", scaled)
        }
    }

    @Test
    fun aStoredImageDescriptorIsNeverStored() {
        // `mcqapp:img|…` is the DOCX writer's hidden stand-in for a picture
        // whose bytes ride in the payload. It is not drawable, so storing one
        // makes a later DOCX export count a picture the body cannot draw.
        // A descriptor in a JSON file is blanked during import normalisation.
        val descriptor = "mcqapp:img|data|image|png|base64|0|A|deadbeef"
        val cell = "a <img src=\"$descriptor\"> b"
        val out = normalizeCellImages(cell, identity) { fail(it) }
        assertEquals("a $CELL_IMAGE_MARKER b", out)
        assertFalse("no descriptor survives: $out", out.contains("mcqapp:img|"))

        val scaled = LegacyParser.parse(bank(cell)).papers.single()
            .categories.single().questions.single()
            .withScaledImages(identity, identity)
        val stored = (scaled.elements.single() as ContentElement.TableElement).rows[0][0]
        assertEquals("a $CELL_IMAGE_MARKER b", stored)
        assertFalse("no descriptor in the row: $stored", stored.contains("mcqapp:img|"))
        assertFalse("no descriptor in CardContent", CardContent.of(scaled).contains("mcqapp:img|"))
    }

    @Test
    fun aFileCellSourceRaisesTheLocalImageWarning() {
        val warnings = LegacyParser.parse(
            bank("""<img src="file:///tmp/x.png">""")
        ).warnings
        assertTrue(
            "warnings were: $warnings",
            warnings.any { it.contains("point at files on the author's computer") }
        )
        assertTrue(
            "the example names the source: $warnings",
            warnings.any { it.contains("file:///tmp/x.png") }
        )
    }

    @Test
    fun aMultiAttributeFileCellSourceStillWarns() {
        val warnings = LegacyParser.parse(
            bank(
                """<img hidden alt="x" src="file:///tmp/x.png"> """ +
                    """<img/ src="file:///tmp/y.png">"""
            )
        ).warnings
        assertTrue(
            "warnings were: $warnings",
            warnings.any { it.contains("point at files on the author's computer") }
        )
        assertTrue(
            "the warning names the first source: $warnings",
            warnings.any { it.contains("file:///tmp/x.png") }
        )
    }

    @Test
    fun aCanonicalDataCellSourceDoesNotRaiseTheLocalImageWarning() {
        val warnings = LegacyParser.parse(bank(cellImageTag(dataSrc("AAAA")))).warnings
        assertFalse(
            "a data URI travels with the file: $warnings",
            warnings.any { it.contains("point at files on the author's computer") }
        )
    }

    @Test
    fun aTornTagLeavesNoPayloadInSearchTextOrCardContent() {
        val file = bank("""before <img src="data:image/png;base64,AAAA" and after""")
        val scaled = LegacyParser.parse(file).papers.single()
            .categories.single().questions.single()
            .withScaledImages(identity, identity)
        val row = scaled.toQuestionEntity(
            categoryId = "c1",
            sortOrder = 0,
            contentHash = "hash",
            json = Json { ignoreUnknownKeys = true }
        )

        assertFalse("search text keeps no payload: ${row.searchText}", row.searchText.contains("base64"))
        assertFalse("search text keeps no payload: ${row.searchText}", row.searchText.contains("AAAA"))
        assertTrue("the prose stays searchable: ${row.searchText}", row.searchText.contains("before"))
        assertTrue("the prose stays searchable: ${row.searchText}", row.searchText.contains("and after"))

        val card = CardContent.of(scaled)
        assertFalse("CardContent keeps no payload", card.contains("base64"))
        assertFalse("CardContent keeps no payload", card.contains("AAAA"))
    }

    @Test
    fun thePreviewHashPathSkipsTableCellsEntirely() {
        Logger.clearRecentErrors()
        Logger.resetWarnOnce()
        try {
            val oversized = Base64.getEncoder()
                .encodeToString(ByteArray(MAX_CELL_IMAGE_BYTES + 1))
            val cell = imageTag(dataSrc(oversized))
            val question = QuestionDto(
                id = "q1",
                text = "Q",
                elements = listOf(
                    ContentElement.TextElement("Q"),
                    ContentElement.TableElement(listOf(listOf(cell)))
                )
            )

            // The flag: no cell scaler call, no normalisation — even a cell the
            // importer would rewrite survives byte for byte.
            var cellScaleCalls = 0
            val skipped = question.withScaledImages(
                identity,
                { src -> cellScaleCalls++; src },
                includeCells = false
            )
            assertEquals(0, cellScaleCalls)
            assertEquals(question.elements, skipped.elements)

            // The preview hash discards tables, so it must not spend the
            // once-per-session oversized-cell warning.
            previewHashOf(question) { it }
            assertTrue(
                "the preview burned the cell warning: ${Logger.recentErrors()}",
                Logger.recentErrors().none { it.contains(CELL_IMAGE_TOO_LARGE_MESSAGE) }
            )

            // The import/comparison path still normalises the cell and warns.
            val imported = question.withScaledImages(identity, identity)
                .elements[1] as ContentElement.TableElement
            assertEquals(CELL_IMAGE_MARKER, imported.rows[0][0])
            assertEquals(
                "one session warning for the import path: ${Logger.recentErrors()}",
                1,
                Logger.recentErrors().count { it.contains(CELL_IMAGE_TOO_LARGE_MESSAGE) }
            )
        } finally {
            Logger.clearRecentErrors()
            Logger.resetWarnOnce()
        }
    }

    /** One question whose only element is a table with one cell. */
    private fun bank(cellHtml: String): String {
        val escaped = cellHtml.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"papers":[{"id":"p1","title":"P","categories":[{"id":"c1","title":"C",
            "questions":[{"id":"q1","text":"Q",
            "question_elements":[{"type":"table","content":[["$escaped"]]}],
            "options":[{"id":"a","text":"A"}],"correct":"a"}]}]}]}"""
    }
}
