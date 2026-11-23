package com.mcqapp

import com.mcqapp.data.backup.writeBackupOrDrop
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A backup must be one the learner can restore. The old code deleted the picked
 * document only when the grading settings moved mid-write; a size cap or any
 * other failure after `openOutputStream` left a truncated zip at the URI, which
 * the learner could later pick as a backup.
 *
 * Pure JVM: the helper takes the open/delete actions, so the guarantee is
 * testable without Android, a picker or a real document provider.
 */
class BackupDocumentCleanupTest {

    /** Fails after the given number of bytes, like a provider closing mid-write. */
    private class FailingStream(private val failAfter: Int) : OutputStream() {
        private var written = 0
        override fun write(b: Int) {
            if (++written > failAfter) throw IllegalStateException("disk full")
            sink.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            repeat(len) { write(b[off + it].toInt()) }
        }

        val sink = ByteArrayOutputStream()
    }

    @Test
    fun aMidWriteFailureDeletesThePartialFile() = runBlocking {
        var deleted = 0
        try {
            writeBackupOrDrop(
                open = { FailingStream(failAfter = 8) },
                delete = { deleted++ },
                write = { out -> out.write(ByteArray(64)) }
            )
            fail("the write failure must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }
        assertEquals("a failed write must drop the document exactly once", 1, deleted)
    }

    @Test
    fun aFailedConsistencyCheckDeletesTheFile() = runBlocking {
        var deleted = 0
        try {
            writeBackupOrDrop(
                open = { ByteArrayOutputStream() },
                delete = { deleted++ },
                write = { out -> out.write(ByteArray(8)) },
                verify = { throw IllegalStateException("backup_rule_moved") }
            )
            fail("the consistency refusal must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("backup_rule_moved", e.message)
        }
        assertEquals("a refused backup must drop the document", 1, deleted)
    }

    @Test
    fun aSuccessfulWriteKeepsTheFile() = runBlocking {
        var deleted = 0
        val wrote = writeBackupOrDrop(
            open = { ByteArrayOutputStream() },
            delete = { deleted++ },
            write = { out -> out.write(ByteArray(8)) },
            verify = {}
        )
        assertTrue(wrote)
        assertEquals("a complete backup must stay where the learner picked it", 0, deleted)
    }

    @Test
    fun anOpenThatReturnsNothingDeletesNothing() = runBlocking {
        var deleted = 0
        val wrote = writeBackupOrDrop(
            open = { null },
            delete = { deleted++ },
            write = { fail("nothing may be written when the document did not open") }
        )
        assertFalse(wrote)
        assertEquals("there is no opened document to drop", 0, deleted)
    }
}
