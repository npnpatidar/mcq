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
 * The editor's Prev/Next walks a scope. Large scopes are a cursor (size + an
 * id-at-index read) so Browse's filtered list is never materialised; small
 * ones stay an in-memory list. Both expose the same synchronous neighbours.
 */
class EditorSessionCursorTest {

    @After
    fun clear() = EditorSession.clear()

    @Test
    fun cursorResolvesTheNeighbours() = runBlocking {
        EditorSession.startCursor(EditorSession.Cursor(size = 5, index = 2, idAt = { "q$it" }))
        assertEquals(5, EditorSession.size)
        assertEquals(2, EditorSession.index)
        assertEquals("q1", EditorSession.prevId)
        assertEquals("q3", EditorSession.nextId)
        assertTrue(EditorSession.hasQueue)
    }

    @Test
    fun movingByDeltaRefreshesTheCursorNeighbours() = runBlocking {
        EditorSession.startCursor(EditorSession.Cursor(size = 3, index = 0, idAt = { "q$it" }))
        assertEquals(null, EditorSession.prevId)
        assertEquals("q1", EditorSession.nextId)

        EditorSession.go(EditorSession.index + 1)
        EditorSession.refreshNeighbors()
        assertEquals(1, EditorSession.index)
        assertEquals("q0", EditorSession.prevId)
        assertEquals("q2", EditorSession.nextId)
    }

    @Test
    fun theEndsHaveNoNeighbour() = runBlocking {
        EditorSession.startCursor(EditorSession.Cursor(size = 2, index = 1, idAt = { "q$it" }))
        assertEquals("q0", EditorSession.prevId)
        assertEquals(null, EditorSession.nextId)
    }

    @Test
    fun anInMemoryScopeStaysSynchronous() {
        EditorSession.start(listOf("a", "b", "c"), index = 1)
        assertEquals(3, EditorSession.size)
        assertEquals("a", EditorSession.prevId)
        assertEquals("c", EditorSession.nextId)
    }

    @Test
    fun clearResetsEverything() {
        EditorSession.start(listOf("a"), index = 0)
        EditorSession.clear()
        assertEquals(0, EditorSession.size)
        assertEquals(-1, EditorSession.index)
        assertEquals(null, EditorSession.prevId)
        assertEquals(null, EditorSession.nextId)
        assertFalse(EditorSession.hasQueue)
    }

    // --- atomic targets (question_editor_audit.md H-03) ---

    @Test
    fun aTargetCarriesThePositionItMovesTo() = runBlocking {
        EditorSession.startCursor(EditorSession.Cursor(size = 3, index = 0, idAt = { "q$it" }))
        val next = EditorSession.captureTarget("q1")
        assertEquals(EditorSession.MoveTarget("q1", 1), next)
        assertNull("only a neighbour is a target", EditorSession.captureTarget("q2"))
        assertNull("not a queue neighbour", EditorSession.captureTarget("elsewhere"))
    }

    @Test
    fun twoTapsCannotBothCaptureTheSameStep() = runBlocking {
        EditorSession.startCursor(EditorSession.Cursor(size = 3, index = 0, idAt = { "q$it" }))
        val first = EditorSession.captureTarget("q1")
        EditorSession.go(first!!.index)
        EditorSession.refreshNeighbors()
        // A second tap after the first move captured its own index, so the
        // session cannot advance twice onto one question.
        val second = EditorSession.captureTarget("q2")
        assertEquals(EditorSession.MoveTarget("q2", 2), second)
        assertNull("the old neighbour is no longer adjacent", EditorSession.captureTarget("q1"))
    }

    // --- cursor re-anchoring (M-04) ---

    @Test
    fun reanchoringFollowsTheLiveResultSet() = runBlocking {
        var ids = listOf("q0", "q1", "q2")
        EditorSession.startCursor(
            EditorSession.Cursor(
                size = ids.size,
                index = 1,
                idAt = { offset -> ids.getOrNull(offset) },
                count = { ids.size },
                indexOf = { id -> ids.indexOf(id) }
            )
        )
        assertEquals(3, EditorSession.size)

        // An edit removed an earlier question: the same anchor is now first,
        // and the counter/neighbours must say so.
        ids = listOf("q1", "q2")
        EditorSession.reanchor("q1")
        assertEquals(2, EditorSession.size)
        assertEquals(0, EditorSession.index)
        assertNull(EditorSession.prevId)
        assertEquals("q2", EditorSession.nextId)
    }

    @Test
    fun anAnchorThatLeftTheScopeStopsClaimingAPosition() = runBlocking {
        var ids = listOf("q0", "q1")
        EditorSession.startCursor(
            EditorSession.Cursor(
                size = ids.size,
                index = 0,
                idAt = { offset -> ids.getOrNull(offset) },
                count = { ids.size },
                indexOf = { id -> ids.indexOf(id) }
            )
        )
        ids = listOf("q1")
        EditorSession.reanchor("q0")
        assertEquals(1, EditorSession.size)
        assertEquals(-1, EditorSession.index)
        assertNull(EditorSession.prevId)
        assertNull(EditorSession.nextId)
        assertFalse(EditorSession.hasQueue)
    }

    @Test
    fun inMemoryScopesNeedNoReanchor() {
        EditorSession.start(listOf("a", "b"), index = 1)
        runBlocking { EditorSession.reanchor("b") }
        assertEquals(2, EditorSession.size)
        assertEquals(1, EditorSession.index)
        assertEquals("a", EditorSession.prevId)
        assertNull(EditorSession.nextId)
    }
}
