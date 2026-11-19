package com.mcqapp

import com.mcqapp.data.export.ExportFormat
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HIGH integration gap: [ExportFormat.descriptionWithCaveat] existed but had no
 * production caller, so a learner picking JSON or ZIP in any per-paper export
 * picker was never told that study progress is excluded. This guards the wiring
 * at all three pickers so one cannot silently drift back to the raw description.
 */
class Round2ExportCaveatWiringTest {

    private val pickers = mapOf(
        "src/main/java/com/mcqapp/ui/library/LibraryScreen.kt"
            to "val descriptionWithNote = format.descriptionWithCaveat",
        "src/main/java/com/mcqapp/ui/browse/BrowseScreen.kt"
            to "val descriptionWithNote = format.descriptionWithCaveat",
        "src/main/java/com/mcqapp/ui/history/BookmarksScreen.kt"
            to "val description = format.descriptionWithCaveat"
    )

    @Test
    fun everyPerPaperExportPickerRoutesDescriptionThroughTheHelper() {
        val rawDescriptionInText = Regex("""Text\(\s*stringResource\(format\.descriptionRes\)""")
        pickers.forEach { (path, helperCall) ->
            val src = File(path).readText()
            assertTrue(
                "$path must render the row description via descriptionWithCaveat",
                src.contains(helperCall)
            )
            assertTrue(
                "$path must resolve the content-only note resource at the picker",
                src.contains("stringResource(format.contentOnlyNoteRes)")
            )
            assertFalse(
                "$path must not pass the plain description straight into a Text row",
                rawDescriptionInText.containsMatchIn(src)
            )
        }
    }

    @Test
    fun helperShowsTheCaveatOnlyForTheContentOnlyFormats() {
        val note = "PROGRESS_NOT_INCLUDED"
        fun rendered(format: ExportFormat): String {
            val resolved = mapOf(
                format.descriptionRes to "desc-${format.name}",
                R.string.r2_backup_paper_export_content_only to note
            )
            return format.descriptionWithCaveat { resolved.getValue(it) }
        }

        for (format in listOf(ExportFormat.JSON_INLINE, ExportFormat.ZIP)) {
            assertTrue("$format must append the caveat", rendered(format).endsWith(note))
        }
        for (format in listOf(
            ExportFormat.HTML, ExportFormat.HTML_QUIZ, ExportFormat.PDF,
            ExportFormat.PDF_ANSWER_KEY, ExportFormat.APKG, ExportFormat.DOCX
        )) {
            assertFalse("$format must not append the caveat", rendered(format).contains(note))
        }
    }
}
