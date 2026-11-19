package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SE2 / H2 / H4 (reports 07 and 13): the Export Logs failure copy, the chooser
 * title, the single-paper export caveat and the log-sharing warning must be
 * localized resources, not English literals in the composable.
 */
class Round2BackupCopyTest {

    private fun strings(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun everyNewBackupStringHasAHindiTranslation() {
        val english = strings("src/main/res/values/strings_round2_backup.xml")
        val hindi = strings("src/main/res/values-hi/strings_round2_backup_hi.xml")
        assertTrue("English backup strings missing", english.isNotEmpty())
        assertEquals("locale key sets must match", english.keys.sorted(), hindi.keys.sorted())
        for ((name, value) in english) {
            assertTrue("$name must not be empty", value.isNotBlank())
            assertTrue("$name Hindi must not be empty", hindi.getValue(name).isNotBlank())
        }
    }

    @Test
    fun exportLogsCopyIsResourceBacked() {
        val section = File("src/main/java/com/mcqapp/ui/settings/BackupDataSection.kt").readText()
        assertFalse("hardcoded no-log copy", section.contains("\"No log file exists yet.\""))
        assertFalse("hardcoded share failure copy", section.contains("\"No app can handle this share.\""))
        assertFalse("hardcoded chooser title", section.contains("\"Export Logs\""))
        assertTrue("no-log string must be used", section.contains("r2_backup_no_log_file"))
        assertTrue("share failure string must be used", section.contains("r2_backup_no_share_app"))
        assertTrue("existing localized chooser title must be used", section.contains("exportLogsTitle"))
        assertTrue("H4 warning must be used", section.contains("r2_backup_export_logs_warning"))
        assertTrue("H2 caveat must be used", section.contains("r2_backup_paper_export_content_only"))
    }
}
