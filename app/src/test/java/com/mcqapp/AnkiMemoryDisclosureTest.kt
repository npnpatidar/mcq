package com.mcqapp

import com.mcqapp.ui.importscreen.ImportSource
import com.mcqapp.ui.importscreen.ankiMemoryDisclosureApplies
import com.mcqapp.ui.library.PickedArchive
import com.mcqapp.ui.library.pickedArchiveIsAnki
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The import-time disclosure that an Anki deck's own scheduling memory is not
 * carried over. The decision is a pure helper (so it runs on the ARM64 host),
 * and two structural checks pin the note to the preview and to the Anki-only
 * Library report dialog.
 */
class AnkiMemoryDisclosureTest {

    @Test
    fun anAnkiSourceShowsTheDisclosure() {
        assertTrue("Anki deck must show the memory note",
            ankiMemoryDisclosureApplies(ImportSource.ANKI))
    }

    @Test
    fun jsonDocxAndUnknownSourcesNeverShowTheDisclosure() {
        assertFalse("a JSON bank has no Anki memory",
            ankiMemoryDisclosureApplies(ImportSource.JSON))
        assertFalse("a Word bank has no Anki memory",
            ankiMemoryDisclosureApplies(ImportSource.DOCX))
        assertFalse("an unidentified file must not be guessed as Anki",
            ankiMemoryDisclosureApplies(null))
    }

    @Test
    fun theClassifierSignalsAnkiOnlyForTheOtherBucket() {
        assertTrue("OTHER is an Anki package", pickedArchiveIsAnki(PickedArchive.OTHER))
        assertFalse("a Word bank is not Anki", pickedArchiveIsAnki(PickedArchive.DOCX))
        assertFalse("a backup is not Anki", pickedArchiveIsAnki(PickedArchive.BACKUP))
    }

    @Test
    fun theNoteIsPlainAndSaysMemoryIsApproximatedFromTheInterval() {
        val xml = File("src/main/res/values/strings_disclosure_anki.xml").readText()
        assertTrue("the title lives in its own resource file",
            xml.contains("name=\"anki_memory_notice_title\""))
        val body = Regex("""<string name="anki_memory_notice_body">(.*?)</string>""")
            .find(xml)?.groupValues?.get(1)
            ?: error("anki_memory_notice_body is missing")
        assertTrue("names the interval", "interval" in body)
        assertTrue("names the fixed retention", "90 percent" in body)
        assertTrue("says memory is approximated", "approximated" in body)
        assertFalse("no internal SM-2 term", "SM-2" in body)
        assertFalse("no internal stability term", "stability" in body)
        assertFalse("no internal bridge term", "bridge" in body)
    }

    @Test
    fun thePreviewShowsTheNoteBeforeTheImportButton() {
        val src = File("src/main/java/com/mcqapp/ui/importscreen/ImportScreen.kt").readText()
        val gate = src.indexOf("ankiMemoryDisclosureApplies(state.importSource)")
        val title = src.indexOf("R.string.anki_memory_notice_title")
        val body = src.indexOf("R.string.anki_memory_notice_body")
        val button = src.indexOf("viewModel.import()")
        assertTrue("the preview gates the note on the Anki source", gate >= 0)
        assertTrue("the preview renders the note title and body", title >= 0 && body >= 0)
        assertTrue("the import action is present", button >= 0)
        assertTrue("the note is shown before the import action",
            gate < button && title < button && body < button)
    }

    @Test
    fun theNoteNeverDisablesTheImportButton() {
        val src = File("src/main/java/com/mcqapp/ui/importscreen/ImportViewModel.kt").readText()
        val start = src.indexOf("internal fun importButton(")
        val end = src.indexOf("internal fun backupRestoreNotice(", start)
        assertTrue("the pure import gate was found", start >= 0 && end > start)
        val gate = src.substring(start, end)
        assertFalse("the note is informational, not a button gate",
            "importSource" in gate || "ankiMemoryDisclosureApplies" in gate)
    }

    @Test
    fun theAnkiOnlyLibraryReportShowsTheNote() {
        val screen = File("src/main/java/com/mcqapp/ui/library/LibraryScreen.kt").readText()
        val start = screen.indexOf("importReport?.let")
        val end = screen.indexOf("if (showExportFormatDialog)", start)
        assertTrue("the Anki report dialog was found", start >= 0 && end > start)
        val dialog = screen.substring(start, end)
        assertTrue("the report shows the note title",
            "R.string.anki_memory_notice_title" in dialog)
        assertTrue("the report shows the note body",
            "R.string.anki_memory_notice_body" in dialog)

        val vm = File("src/main/java/com/mcqapp/ui/library/LibraryViewModel.kt").readText()
        assertTrue("the router uses the Anki classifier signal",
            "pickedArchiveIsAnki(kind)" in vm)
        assertTrue("the report is produced by exactly one import path",
            Regex("""_importReport\.value = report""").findAll(vm).count() == 1)
    }
}
