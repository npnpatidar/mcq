package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against the dead code the audit found, and against the escapers
 * drifting apart again. Deleting a public function is invisible to the
 * compiler; leaving it behind is how four copies of the same escaper appeared.
 */
class NoDeadCodeTest {

    private fun sources(): List<File> =
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    private fun sourceOf(name: String): String {
        val file = sources().firstOrNull { it.name == name }
        assertTrue("missing source $name", file != null)
        return file!!.readText()
    }

    @Test
    fun theUnreferencedHelpersAreGone() {
        listOf(
            "ImportViewModel.kt" to "fun loadJson(",
            "McqRepository.kt" to "fun searchQuestions(",
            "McqRepository.kt" to "fun observeQuestion(",
            "AnkiSchema11.kt" to "fun stripHtml(",
            "Scoring.kt" to "fun isGraded("
        ).forEach { (file, needle) ->
            assertFalse("$file still declares $needle", sourceOf(file).contains(needle))
        }
    }

    @Test
    fun theDeadCountDaosAreGone() {
        val daos = sourceOf("Daos.kt")
        for (needle in listOf("fun countDue(", "fun countLeeches(", "fun countNew(", "fun countByCategory(")) {
            assertFalse("Daos.kt still declares $needle", daos.contains(needle))
        }
    }

    @Test
    fun imageScalingHasOneImplementation() {
        // The file-level scaler is the single copy of the mapping and the
        // import path calls it; the paper-level twin of it is the dead code
        // the audit found.
        val importer = sourceOf("Importer.kt")
        assertTrue(
            "the import path scales the whole file",
            importer.contains("file.withDownscaledImages().papers")
        )
        assertFalse(
            "Importer.kt still declares the paper-level duplicate",
            importer.contains("fun PaperDto.withDownscaledImages(")
        )
    }

    @Test
    fun allRenderersShareOneHtmlEscaper() {
        // escapeHtmlText is the single implementation; the Anki variant keeps
        // its own because it also maps ' and newlines for Anki's renderer.
        val richText = sourceOf("RichTextHtml.kt")
        assertTrue(richText.contains("internal fun escapeHtmlText("))
        assertTrue(
            "ContentElements should delegate",
            sourceOf("ContentElements.kt").contains("escapeHtmlText(s)")
        )
        assertTrue(
            "HtmlPaperWriter should delegate",
            sourceOf("HtmlPaperWriter.kt").contains("escapeHtmlText(s)")
        )
    }
}
