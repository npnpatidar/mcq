package com.mcqapp

import com.mcqapp.data.anki.AnkiHtml
import com.mcqapp.data.anki.AnkiMediaPool
import com.mcqapp.data.anki.AnkiPackageWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field-shaping tests for `.apkg` import, without touching SQLite so they run
 * on any host.
 *
 * The round trip is: text is HTML-escaped on export and flattened back to plain
 * text on import, so the pairing of [AnkiMediaPool.escapeHtml] and
 * [AnkiHtml.toPlainText] has to be lossless for anything the app can store.
 */
class AnkiHtmlTest {

    private val pool = AnkiMediaPool()

    private fun roundTrip(text: String): String = AnkiHtml.toPlainText(pool.html(text))

    @Test
    fun plainTextSurvivesTheRoundTrip() {
        assertEquals("Which organelle makes ATP?", roundTrip("Which organelle makes ATP?"))
    }

    @Test
    fun markupCharactersSurviveTheRoundTrip() {
        listOf(
            "5 < 6 & 7 > 2",
            "He said \"stop\"",
            "it's fine",
            "a & b <c> \"d\" 'e'",
            "100% of 1/2"
        ).forEach { text ->
            assertEquals("round trip: $text", text, roundTrip(text))
        }
    }

    @Test
    fun lineBreaksSurviveTheRoundTrip() {
        assertEquals("first\nsecond", roundTrip("first\nsecond"))
        assertEquals("first\n\nsecond", roundTrip("first\n\nsecond"))
        // A list of options, as the exporter writes them.
        assertEquals(
            "Which?\n\nA. one\nB. two",
            roundTrip("Which?\n\nA. one\nB. two")
        )
    }

    @Test
    fun htmlIsFlattenedToText() {
        assertEquals(
            "Capital of France?",
            AnkiHtml.toPlainText("<b>Capital</b> of France?")
        )
        assertEquals("one\ntwo", AnkiHtml.toPlainText("one<br>two"))
        assertEquals("one\ntwo", AnkiHtml.toPlainText("<div>one</div><div>two</div>"))
        assertEquals("a & b", AnkiHtml.toPlainText("a &amp; b"))
        assertEquals("A.\nB.\nC.", AnkiHtml.toPlainText("<ol><li>A.</li><li>B.</li><li>C.</li></ol>"))
    }

    @Test
    fun ourFrontKeepsOnlyTheQuestionWhenTheOptionsAreStripped() {
        val front = AnkiHtml.toPlainText(
            pool.html("Which organelle makes ATP?") + AnkiPackageWriter.BR +
                AnkiPackageWriter.BR +
                "<b>A.</b> Mitochondrion" + AnkiPackageWriter.BR +
                "<b>B.</b> Ribosome" + AnkiPackageWriter.BR
        )
        assertEquals("Which organelle makes ATP?", AnkiHtml.questionTextFromFront(front))
    }

    @Test
    fun theMultiSelectPromptIsNotPartOfTheQuestion() {
        val front = AnkiHtml.toPlainText(
            pool.html("Pick two") + AnkiPackageWriter.BR +
                AnkiPackageWriter.BR +
                "<b>Select all that apply.</b>" + AnkiPackageWriter.BR +
                "<b>A.</b> one" + AnkiPackageWriter.BR
        )
        assertEquals("Pick two", AnkiHtml.questionTextFromFront(front))
    }

    @Test
    fun optionLettersBeyondZAreStillRecognised() {
        val options = (0 until 28).joinToString(AnkiPackageWriter.BR) { i ->
            "<b>${letterFor(i)}.</b> option $i"
        }
        val front = AnkiHtml.toPlainText(
            pool.html("Question") + AnkiPackageWriter.BR + AnkiPackageWriter.BR + options
        )
        assertEquals("Question", AnkiHtml.questionTextFromFront(front))
    }

    @Test
    fun aQuestionThatMerelyStartsWithALetterIsKept() {
        // "B. Welch" is a sentence, not an option line, and must not be cut.
        val front = AnkiHtml.toPlainText(pool.html("B. Welch wrote a paper") + AnkiPackageWriter.BR)
        assertEquals("B. Welch wrote a paper", AnkiHtml.questionTextFromFront(front))
    }

    @Test
    fun aMultiLineOptionStaysWithItsOption() {
        // The option text is escaped, so its newline becomes <br> and comes back
        // as a line break inside the same option.
        val front = AnkiHtml.toPlainText(
            pool.html("Pick one") + AnkiPackageWriter.BR + AnkiPackageWriter.BR +
                "<b>A.</b> " + pool.html("Ribosome\non the rough ER") + AnkiPackageWriter.BR +
                "<b>B.</b> Mitochondrion" + AnkiPackageWriter.BR
        )
        assertEquals("Pick one", AnkiHtml.questionTextFromFront(front))
        val options = AnkiHtml.parseBackField(
            AnkiHtml.toPlainText("✗ Ribosome\non the rough ER\n✓ Mitochondrion")
        )
        assertEquals(listOf("Ribosome\non the rough ER", "Mitochondrion"), options.options.map { it.text })
        assertEquals(listOf(false, true), options.options.map { it.correct })
    }

    @Test
    fun markedBackFieldIsParsedIntoOptionsAndExplanation() {
        val back = AnkiHtml.parseBackField(
            AnkiHtml.toPlainText(
                "Select all that apply.\n✓ one\n✗ two\n✓ three\nExplanation: because\nreasons"
            )
        )
        assertTrue(back.hasMarkers)
        assertEquals(listOf("one", "two", "three"), back.options.map { it.text })
        assertEquals(listOf(true, false, true), back.options.map { it.correct })
        assertEquals("because\nreasons", back.explanation)
    }

    @Test
    fun aBackFieldWithoutMarkersIsReportedAsSuch() {
        val back = AnkiHtml.parseBackField(AnkiHtml.toPlainText("Paris"))
        assertFalse(back.hasMarkers)
        assertTrue(back.options.isEmpty())
    }

    @Test
    fun numericEntityMarkersFromOlderExportsAreDecoded() {
        val back = AnkiHtml.parseBackField("&#10003; yes&#10;&#10007; no")
        assertTrue(back.hasMarkers)
        assertEquals(listOf("yes", "no"), back.options.map { it.text })
        assertEquals(listOf(true, false), back.options.map { it.correct })
    }

    @Test
    fun mediaFilenamesAreSafeForAnki() {
        // Anki rejects separators and control characters, and treats names
        // starting with `_` or `latex-` as static files it will not rewrite.
        mapOf("png" to ".png", "jpg" to ".jpg", "jpeg" to ".jpeg", "gif" to ".gif", "webp" to ".webp")
            .forEach { (ext, expected) ->
                val name = AnkiMediaPool.safeName(3, ext)
                assertFalse("no separator in $name", name.contains('/') || name.contains('\\'))
                assertFalse("does not start with a dot: $name", name.startsWith('.'))
                assertFalse("not a static name: $name", name.startsWith('_') || name.startsWith("latex-"))
                assertEquals("mcqapp-3$expected", name)
            }
    }

    @Test
    fun anUnusableExtensionFallsBackToJpeg() {
        assertEquals("mcqapp-0.jpg", AnkiMediaPool.safeName(0, ""))
        assertEquals("mcqapp-0.png", AnkiMediaPool.safeName(0, "PNG"))
        assertEquals("mcqapp-0.jpg", AnkiMediaPool.safeName(0, "???"))
        assertEquals("unique per image", "mcqapp-1.png", AnkiMediaPool.safeName(1, "png"))
    }

    @Test
    fun anImageIsReferencedByItsFilenameNotItsZipEntry() {
        val bytes = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
        val uri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
        val html = pool.htmlField("See diagram", uri)

        // Anki keys its media map by filename, so this is the reference that
        // actually resolves on import.
        assertTrue(html, "<img src=\"mcqapp-0.png\">" in html)
        assertFalse(html, "<img src=\"0\">" in html)
        assertEquals("mcqapp-0.png", pool.manifest["0"])
    }

    @Test
    fun theSameImageInTwoFieldsIsStoredOnce() {
        val bytes = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
        val uri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
        val first = pool.htmlField("a", uri)
        val second = pool.htmlField("b", uri)
        assertTrue(first, "<img src=\"mcqapp-0.png\">" in first)
        assertTrue(second, "<img src=\"mcqapp-0.png\">" in second)
        assertEquals(1, pool.entries.size)
    }

    @Test
    fun aRemoteUrlIsPassedThroughUntouched() {
        val html = pool.htmlField("Look", "https://example.com/a.png")
        assertEquals("""Look <img src="https://example.com/a.png">""", html)
        assertTrue("nothing downloaded", pool.entries.isEmpty())
    }

    private fun letterFor(index: Int): String =
        if (index < 26) ('A' + index).toString() else "(${index / 26}${'A' + index % 26})"
}
