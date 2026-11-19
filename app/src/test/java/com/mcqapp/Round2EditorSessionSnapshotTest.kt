package com.mcqapp

import com.mcqapp.ui.editor.EditorSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-2 E5: index/size/prev/next are one immutable snapshot behind a single
 * reference, and every change (especially a re-anchor) emits, so the screen's
 * counter and Prev/Next update without waiting for an unrelated state change.
 */
class Round2EditorSessionSnapshotTest {

    @After
    fun clear() = EditorSession.clear()

    @Test
    fun startPublishesOneCoherentSnapshot() {
        EditorSession.start(listOf("a", "b", "c"), 1)
        val s = EditorSession.snapshotFlow.value
        assertEquals(1, s.index)
        assertEquals(3, s.size)
        assertEquals("a", s.prevId)
        assertEquals("c", s.nextId)
        assertTrue(EditorSession.hasQueue)
        // The synchronous getters and the emitted snapshot agree.
        assertEquals(s.index, EditorSession.index)
        assertEquals(s.size, EditorSession.size)
        assertEquals(s.prevId, EditorSession.prevId)
        assertEquals(s.nextId, EditorSession.nextId)
    }

    @Test
    fun reanchorEmitsANewCoherentSnapshot() = runBlocking {
        var ids = listOf("q0", "q1", "q2")
        EditorSession.startCursor(
            EditorSession.Cursor(
                size = ids.size,
                index = 1,
                idAt = { ids.getOrNull(it) },
                count = { ids.size },
                indexOf = { ids.indexOf(it) }
            )
        )
        assertEquals(1, EditorSession.snapshotFlow.value.index)

        ids = listOf("q1", "q2") // an edit removed an earlier question
        EditorSession.reanchor("q1")
        val s = EditorSession.snapshotFlow.value
        assertEquals(0, s.index)
        assertEquals(2, s.size)
        assertNull("new first row has no previous", s.prevId)
        assertEquals("q2", s.nextId)
        assertEquals(s, EditorSession.snapshotFlow.value)
    }

    @Test
    fun anAnchorThatLeftTheScopeEmitsAClearedSnapshot() = runBlocking {
        var ids = listOf("q0", "q1")
        EditorSession.startCursor(
            EditorSession.Cursor(
                size = ids.size,
                index = 0,
                idAt = { ids.getOrNull(it) },
                count = { ids.size },
                indexOf = { ids.indexOf(it) }
            )
        )
        ids = listOf("q1")
        EditorSession.reanchor("q0")
        val s = EditorSession.snapshotFlow.value
        assertEquals(-1, s.index)
        assertEquals(1, s.size)
        assertNull(s.prevId)
        assertNull(s.nextId)
        assertFalse(EditorSession.hasQueue)
    }

    @Test
    fun captureTargetReadsOneSnapshotAndRejectsStaleNeighbours() = runBlocking {
        EditorSession.startCursor(EditorSession.Cursor(size = 3, index = 0, idAt = { "q$it" }))
        assertEquals(EditorSession.MoveTarget("q1", 1), EditorSession.captureTarget("q1"))
        assertNull("not adjacent", EditorSession.captureTarget("q2"))

        EditorSession.go(1)
        EditorSession.refreshNeighbors()
        assertNull("the old neighbour is no longer adjacent", EditorSession.captureTarget("q1"))
        assertEquals(EditorSession.MoveTarget("q2", 2), EditorSession.captureTarget("q2"))
    }

    @Test
    fun clearEmitsAnEmptySnapshot() {
        EditorSession.start(listOf("a"), 0)
        EditorSession.clear()
        assertEquals(EditorSession.Snapshot(), EditorSession.snapshotFlow.value)
    }
}
