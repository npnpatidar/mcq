package com.mcqapp

import com.mcqapp.data.export.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H2: the single-paper JSON and ZIP exports carry questions but no study
 * progress. The honest caveat is a localized resource on those two formats so
 * a learner cannot mistake them for a full backup and lose schedules and
 * review history. Pure JVM: resource ids are generated Int constants.
 */
class Round2ExportFormatContentOnlyTest {

    @Test
    fun reimportablePerPaperFormatsCarryTheContentOnlyCaveat() {
        for (format in listOf(ExportFormat.JSON_INLINE, ExportFormat.ZIP)) {
            assertEquals(
                "$format must warn that study progress is not included",
                R.string.r2_backup_paper_export_content_only,
                format.contentOnlyNoteRes
            )
        }
    }

    @Test
    fun nonReimportableFormatsDoNotClaimToBeFullBackups() {
        for (format in listOf(
            ExportFormat.HTML, ExportFormat.HTML_QUIZ, ExportFormat.PDF,
            ExportFormat.PDF_ANSWER_KEY, ExportFormat.APKG, ExportFormat.DOCX
        )) {
            assertEquals(
                "$format must not carry the content-only caveat",
                0,
                format.contentOnlyNoteRes
            )
        }
    }

    @Test
    fun caveatIsRenderedOnlyForTheFormatsThatNeedIt() {
        val strings = mutableMapOf<Int, String>()
        ExportFormat.entries.forEach { strings[it.descriptionRes] = "desc-${it.name}" }
        strings[R.string.r2_backup_paper_export_content_only] = "PROGRESS_CAVEAT"

        for (format in ExportFormat.entries) {
            val rendered = format.descriptionWithCaveat { strings.getValue(it) }
            assertTrue("$format must render its own description", rendered.startsWith("desc-${format.name}"))
            if (format.contentOnlyNoteRes == 0) {
                assertFalse("$format must not show the caveat", rendered.contains("PROGRESS_CAVEAT"))
            } else {
                assertTrue("$format must show the caveat", rendered.contains("PROGRESS_CAVEAT"))
            }
        }
    }
}
