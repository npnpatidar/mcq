package com.mcqapp

import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.backup.McqBackupFormat.BackupTooLargeException
import com.mcqapp.data.backup.McqBackupFormat.Manifest
import com.mcqapp.data.backup.McqBackupFormat.SettingsFile
import com.mcqapp.data.backup.McqBackupFormat.StudyFile
import com.mcqapp.data.io.McqFileDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * A backup we write must be a backup we can restore. The writer now enforces
 * SafeZip's per-entry and whole-archive caps at write time, with caps injected
 * here so the exact boundaries are testable without allocating 64 MB.
 */
class DataHonestyBackupTest {

    private val manifest = Manifest()
    private val content = McqFileDto()
    private val study = StudyFile()
    private val settings = SettingsFile()

    private val partSizes: List<Long> by lazy {
        listOf(
            McqBackupFormat.json.encodeToString(Manifest.serializer(), manifest),
            McqBackupFormat.json.encodeToString(McqFileDto.serializer(), content),
            McqBackupFormat.json.encodeToString(StudyFile.serializer(), study),
            McqBackupFormat.json.encodeToString(SettingsFile.serializer(), settings)
        ).map { it.toByteArray(Charsets.UTF_8).size.toLong() }
    }

    @Test
    fun writeSucceedsAtExactlyBothCaps() {
        val bytes = McqBackupFormat.write(
            manifest, content, study, settings,
            maxEntryBytes = partSizes.maxOrNull()!!,
            maxTotalBytes = partSizes.sum()
        )
        assertTrue(bytes.isNotEmpty())
        assertTrue(McqBackupFormat.isBackupArchive(bytes))
    }

    @Test
    fun writeFailsOneByteBelowTheTotalCap() {
        try {
            McqBackupFormat.write(
                manifest, content, study, settings,
                maxEntryBytes = partSizes.maxOrNull()!!,
                maxTotalBytes = partSizes.sum() - 1
            )
            fail("a backup over the whole-archive cap must be refused")
        } catch (e: BackupTooLargeException) {
            assertFalse("this is the archive cap, not a single entry", e.entry)
        }
    }

    @Test
    fun writeFailsOneByteBelowTheEntryCap() {
        try {
            McqBackupFormat.write(
                manifest, content, study, settings,
                maxEntryBytes = partSizes.maxOrNull()!! - 1,
                maxTotalBytes = partSizes.sum()
            )
            fail("an entry over the per-entry cap must be refused")
        } catch (e: BackupTooLargeException) {
            assertTrue("this is the per-entry cap", e.entry)
        }
    }

    /** The streaming test writes only "{}" for the two big parts. */
    private val streamingTotal: Long get() = partSizes[0] + 2 + 2 + partSizes[3]
    private val streamingMaxEntry: Long get() = maxOf(partSizes[0], partSizes[3], 2)

    @Test
    fun streamingWriteEnforcesTheSameCaps() {
        val out = ByteArrayOutputStream()
        try {
            McqBackupFormat.writeTo(
                output = out,
                manifest = manifest,
                content = { it.write("{}") },
                study = { it.write("{}") },
                settings = settings,
                maxEntryBytes = streamingMaxEntry,
                maxTotalBytes = streamingTotal - 1
            )
            fail("a streamed backup over the whole-archive cap must be refused")
        } catch (e: BackupTooLargeException) {
            assertFalse(e.entry)
        }
    }

    @Test
    fun streamingWriteSucceedsAtTheBoundary() {
        val out = ByteArrayOutputStream()
        McqBackupFormat.writeTo(
            output = out,
            manifest = manifest,
            content = { it.write("{}") },
            study = { it.write("{}") },
            settings = settings,
            maxEntryBytes = streamingMaxEntry,
            maxTotalBytes = streamingTotal
        )
        assertTrue(out.size() > 0)
    }
}
