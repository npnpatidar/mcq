package com.mcqapp

import com.mcqapp.data.local.chunks
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bind-variable budget, proved structurally rather than against a database.
 *
 * A 901-id device-emulated DB test would not prove it: the emulated SDK 34
 * SQLite allows 32766 bind variables, so an unchunked query succeeds there.
 * `ChunkedQueriesTest` is DB-backed and therefore absent from the local run, so
 * the shape proof lives here, in pure JVM, where `run-jvm-tests.sh` executes it
 * on every host. (This file must not name the emulator runtime anywhere:
 * `run-jvm-tests.sh` selects classes by grepping the source for that word, not
 * by parsing imports.)
 */
class ChunkShapeTest {

    @Test
    fun aThousandAndOneIdsSplitIntoFiveHundredFiveHundredAndOne() {
        val ids = (0 until 1001).map { "c$it" }
        val chunks = ids.chunks()
        assertEquals(listOf(500, 500, 1), chunks.map { it.size })
        // Order is preserved across the split, so concatenation keeps serving order.
        assertEquals(ids, chunks.flatten())
    }

    @Test
    fun fiveHundredIdsStayInOneChunk() {
        val ids = (0 until 500).map { "c$it" }
        assertEquals(listOf(500), ids.chunks().map { it.size })
    }

    @Test
    fun anEmptyIdListMakesNoChunks() {
        assertEquals(emptyList<List<String>>(), emptyList<String>().chunks())
    }
}
