package com.mcqapp

import com.mcqapp.data.anki.AnkiTempFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnkiTempFilesTest {

    @Test
    fun cleanupRemovesOnlyKnownTempPrefixes() {
        val dir = createTempDir()
        try {
            val staleExport = File(dir, "anki-export-1234.anki2").apply { writeText("x") }
            val staleImport = File(dir, "anki-import-5678.anki2").apply { writeText("x") }
            val unrelated = File(dir, "anki-export-9999.txt").apply { writeText("x") }
            val other = File(dir, "notes.anki2").apply { writeText("x") }

            val removed = AnkiTempFiles.cleanup(dir)

            assertEquals(2, removed)
            assertTrue("stale export removed", !staleExport.exists())
            assertTrue("stale import removed", !staleImport.exists())
            assertTrue("wrong suffix kept", unrelated.exists())
            assertTrue("unrelated file kept", other.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cleanupOnEmptyDirectoryRemovesNothing() {
        val dir = createTempDir()
        try {
            assertEquals(0, AnkiTempFiles.cleanup(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The export media spool is a directory of pictures, so the sweep has to
     * take it whole — and only it.
     */
    @Test
    fun cleanupRemovesTheExportMediaSpool() {
        val dir = createTempDir()
        try {
            val spool = File(dir, "mcq-media123").apply { mkdirs() }
            File(spool, "img001.png").writeText("x")
            val unrelated = File(dir, "mcq-notours").apply { mkdirs() }

            assertEquals(1, AnkiTempFiles.cleanup(dir))

            assertTrue("the spool removed", !spool.exists())
            assertTrue("an unrelated directory kept", unrelated.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
