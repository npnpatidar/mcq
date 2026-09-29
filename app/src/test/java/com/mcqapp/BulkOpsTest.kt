package com.mcqapp

import com.mcqapp.domain.BulkOps
import org.junit.Assert.assertEquals
import org.junit.Test

class BulkOpsTest {

    @Test
    fun copyIdWithoutCollision() {
        assertEquals("q1-copy", BulkOps.copyId(setOf("q1", "q2"), "q1"))
    }

    @Test
    fun copyIdSkipsTakenSuffixes() {
        assertEquals(
            "q1-copy-2",
            BulkOps.copyId(setOf("q1", "q1-copy"), "q1")
        )
        assertEquals(
            "q1-copy-3",
            BulkOps.copyId(setOf("q1", "q1-copy", "q1-copy-2"), "q1")
        )
    }

    @Test
    fun copyIdOfACopyChainsCleanly() {
        assertEquals(
            "q1-copy-copy",
            BulkOps.copyId(setOf("q1", "q1-copy"), "q1-copy")
        )
    }
}
