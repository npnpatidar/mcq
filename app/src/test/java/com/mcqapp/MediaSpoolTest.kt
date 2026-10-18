package com.mcqapp

import com.mcqapp.data.export.MediaSpool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * An export's pictures live on disk, not in memory.
 *
 * The three rich formats name their media before the body that draws it, so the
 * bytes cannot simply be written as they are met. Holding them all instead is a
 * paper's worth of pictures in RAM; this pins that the spool is a file per name,
 * that a name already spooled keeps its bytes, and that closing removes them.
 */
class MediaSpoolTest {

    /** The spools in the JVM's temp dir — only ours use this prefix. */
    private fun spools(): Set<File> =
        File(System.getProperty("java.io.tmpdir"))
            .listFiles { f -> f.name.startsWith("mcq-media") }
            ?.toSet()
            ?: emptySet()

    @Test
    fun aSpooledPictureIsAFileAndClosingRemovesIt() {
        val before = spools()
        val spool = MediaSpool()
        spool.add("images/img001.png", byteArrayOf(1, 2, 3))

        val created = spools() - before
        assertEquals("the picture should be on disk, not in memory", 1, created.size)
        val spooled = created.single().listFiles()!!.single()
        assertArrayEquals(byteArrayOf(1, 2, 3), spooled.readBytes())

        val out = ByteArrayOutputStream()
        spool.copyTo("images/img001.png", out)
        assertArrayEquals(byteArrayOf(1, 2, 3), out.toByteArray())

        spool.close()
        assertFalse("close must remove the spool", spooled.exists())
    }

    @Test
    fun namesKeepTheirOrderAndADuplicateKeepsItsBytes() {
        val spool = MediaSpool()
        try {
            spool.add("images/img001.png", byteArrayOf(1))
            spool.add("images/img002.jpg", byteArrayOf(2))
            // The writers dedupe by src, but the spool must not be fooled by a
            // second registration of the same name either.
            spool.add("images/img001.png", byteArrayOf(9))
            assertEquals(listOf("images/img001.png", "images/img002.jpg"), spool.names)
            assertEquals(2, spool.size)
            assertEquals(false, spool.isEmpty)

            val out = ByteArrayOutputStream()
            spool.copyTo("images/img001.png", out)
            assertArrayEquals("a name already spooled keeps its bytes", byteArrayOf(1), out.toByteArray())
        } finally {
            spool.close()
        }
    }

    @Test
    fun aPoolWithNoPicturesLeavesNothingBehind() {
        val before = spools()
        val spool = MediaSpool()
        assertTrue(spool.isEmpty)
        assertEquals(0, spool.size)
        spool.close()
        assertEquals("nothing to create, nothing to clean up", before, spools())
    }
}
