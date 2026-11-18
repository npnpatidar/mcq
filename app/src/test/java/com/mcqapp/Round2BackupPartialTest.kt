package com.mcqapp

import com.mcqapp.R
import com.mcqapp.data.backup.RestorePartial
import com.mcqapp.data.backup.RestorePartialCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SE3 (report 07): a partial settings restore used to append the raw
 * [Throwable.message] to the human sentence shown in the completion dialog.
 * The detail must reach Logger only; the learner sees a generic, localizable
 * phrase. Pure JVM so it runs on the ARM64 host as well as CI.
 */
class Round2BackupPartialTest {

    @Test
    fun failureDetailIsLoggedButNeverRendered() {
        val logged = mutableListOf<String?>()
        val flag = RestorePartialCopy.flagged(
            RestorePartial.SETTINGS,
            IllegalStateException("SUPER_SECRET_EXCEPTION_DETAIL")
        ) { logged.add(it.message) }

        assertEquals(RestorePartial.SETTINGS, flag)
        assertEquals(listOf("SUPER_SECRET_EXCEPTION_DETAIL"), logged)

        val note = RestorePartialCopy.fallback(listOf(flag))
        assertTrue(note.contains("settings were not restored"))
        assertFalse("raw exception detail leaked into the report: $note", note.contains("SUPER_SECRET_EXCEPTION_DETAIL"))
    }

    @Test
    fun inProgressFailureIsAlsoSuppressed() {
        val logged = mutableListOf<String?>()
        val flag = RestorePartialCopy.flagged(
            RestorePartial.IN_PROGRESS,
            RuntimeException("ANOTHER_SECRET")
        ) { logged.add(it.message) }

        assertEquals(listOf("ANOTHER_SECRET"), logged)
        val note = RestorePartialCopy.fallback(listOf(flag))
        assertTrue(note.contains("the in-progress test was not restored"))
        assertFalse(note.contains("ANOTHER_SECRET"))
    }

    @Test
    fun bothFailuresProduceOneGenericSentence() {
        val note = RestorePartialCopy.fallback(
            listOf(RestorePartial.SETTINGS, RestorePartial.IN_PROGRESS)
        )
        assertTrue(note.contains("settings were not restored"))
        assertTrue(note.contains("the in-progress test was not restored"))
        assertTrue(note.contains(" and "))
        assertFalse("old parenthesised detail must be gone: $note", note.contains("("))
    }

    @Test
    fun localizedRendererResolvesEveryPieceThroughResources() {
        // Fake resources mirror the real ones: no trailing whitespace in any
        // translatable string. The renderer adds the separating space itself,
        // so the rendered sentence has exactly one space after the semicolon.
        val english = mapOf(
            R.string.r2_backup_partial_prefix to "The library itself came back;",
            R.string.r2_backup_partial_settings to "settings were not restored",
            R.string.r2_backup_partial_in_progress to "the in-progress test was not restored"
        )
        val hindi = mapOf(
            R.string.r2_backup_partial_prefix to "पुस्तकालय वापस आ गया;",
            R.string.r2_backup_partial_settings to "सेटिंग्स बहाल नहीं हो सकीं",
            R.string.r2_backup_partial_in_progress to "चल रहा परीक्षण बहाल नहीं हो सका"
        )
        val items = listOf(RestorePartial.SETTINGS, RestorePartial.IN_PROGRESS)
        // The guard-clean resource contract is part of what this test pins.
        assertFalse(english[R.string.r2_backup_partial_prefix]!!.endsWith(" "))
        assertFalse(hindi[R.string.r2_backup_partial_prefix]!!.endsWith(" "))
        assertEquals(
            "The library itself came back; settings were not restored and the in-progress test was not restored.",
            RestorePartialCopy.localized(items) { english.getValue(it) }
        )
        assertEquals(
            "पुस्तकालय वापस आ गया; सेटिंग्स बहाल नहीं हो सकीं and चल रहा परीक्षण बहाल नहीं हो सका.",
            RestorePartialCopy.localized(items) { hindi.getValue(it) }
        )
    }

    @Test
    fun restoreSourceNeverEmbedsAThrowableMessageInPartialCopy() {
        val source = File("src/main/java/com/mcqapp/data/backup/Backup.kt").readText()
        assertFalse(
            "raw exception message still appended to the restore report",
            Regex("""partial\.add\([^)]*e\.message""").containsMatchIn(source)
        )
        assertTrue("detail-free helper must be used", source.contains("RestorePartialCopy.flagged"))
    }

    @Test
    fun productionRestoreWiresTheLocalizedRenderer() {
        // SE2/SE3: the localized renderer must be handed to the production
        // BackupRestorer call, not left unused next to the English fallback.
        val vm = File("src/main/java/com/mcqapp/ui/settings/SettingsViewModel.kt").readText()
        assertTrue(
            "the streaming restore must pass a partial-note renderer",
            vm.contains("renderPartialNote =")
        )
        assertTrue(
            "production must render the note through the localized resolver",
            vm.contains("RestorePartialCopy.localized")
        )
        assertFalse(
            "the English-only fallback must not be the production renderer",
            vm.contains("RestorePartialCopy.fallback")
        )
    }
}
