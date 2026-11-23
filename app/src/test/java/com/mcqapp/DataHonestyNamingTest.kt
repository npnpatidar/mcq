package com.mcqapp

import com.mcqapp.ui.settings.formatBackupDate
import com.mcqapp.util.sanitizeFileName
import com.mcqapp.util.stampedFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Export names are no longer fixed, and a restore confirmation can name the file. */
class DataHonestyNamingTest {

    @Test
    fun wholeBankExportsGetADateTimeStamp() {
        // 2026-11-23 10:45 UTC — the formatter uses the device zone, which the
        // test pins so the assertion is independent of the host's timezone.
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val name = stampedFileName("mcqapp-backup", "zip", 1795430700000L)
            assertTrue("keeps the recognisable prefix", name.startsWith("mcqapp-backup-"))
            assertTrue("keeps the format extension", name.endsWith(".zip"))
            assertEquals("mcqapp-backup-2026-11-23-1045.zip", name)
            assertEquals(
                "mcq-export-2026-11-23-1045.json",
                stampedFileName("mcq-export", "json", 1795430700000L)
            )
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun stampedNamesSurviveTheShareFileSanitiser() {
        val stamped = stampedFileName("mcqapp-backup", "zip", 1795430700000L)
        assertEquals(stamped, sanitizeFileName(stamped))
    }

    @Test
    fun aManifestDateIsReadableInTheRestoreConfirmation() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("2026-11-23 10:45", formatBackupDate(1795430700000L))
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
