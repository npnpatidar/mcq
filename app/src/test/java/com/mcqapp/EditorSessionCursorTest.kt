package com.mcqapp

import com.mcqapp.ui.editor.EditorSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        EditorSession.startCursor(EditorSession.Cursor(size = 5, index = 2) { "q$it" })
        assertEquals(5, EditorSession.size)
        assertEquals(2, EditorSession.index)
        assertEquals("q1", EditorSession.prevId)
        assertEquals("q3", EditorSession.nextId)
        assertTrue(EditorSession.hasQueue)
    }

    @Test
    fun movingByDeltaRefreshesTheCursorNeighbours() = runBlocking {
        EditorSession.startCursor(EditorSession.Cursor(size = 3, index = 0) { "q$it" })
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
        EditorSession.startCursor(EditorSession.Cursor(size = 2, index = 1) { "q$it" })
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
}
