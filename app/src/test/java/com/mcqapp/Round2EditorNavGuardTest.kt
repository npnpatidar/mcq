package com.mcqapp

import com.mcqapp.ui.editor.EditorDirtyGuard
import com.mcqapp.ui.navigation.EditorExitDecision
import com.mcqapp.ui.navigation.EditorExitGuard
import com.mcqapp.ui.navigation.decodePendingExit
import com.mcqapp.ui.navigation.encodePendingExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-2 E4: an external navigation (the SEND/import flow) must not pop a
 * dirty editor silently. These tests drive the pure decision seam the NavHost
 * uses; no Compose, Room or NavController is needed.
 */
class Round2EditorNavGuardTest {

    private val editorRoute = "editor?questionId=q1&paperId=p1&categoryId=&source=database"

    @Before
    fun resetGuard() {
        EditorDirtyGuard.clear()
    }

    @Test
    fun `navigating away from a dirty editor is confirmed`() {
        // A shared file (SEND) arriving mid-edit targets import/direct.
        assertEquals(
            EditorExitDecision.CONFIRM,
            EditorExitGuard.decide(isDirty = true, destination = "import/direct", bypass = false)
        )
        // A pop with no explicit destination also leaves the editor.
        assertEquals(
            EditorExitDecision.CONFIRM,
            EditorExitGuard.decide(isDirty = true, destination = null, bypass = false)
        )
    }

    @Test
    fun `clean editor navigates freely`() {
        assertEquals(
            EditorExitDecision.PROCEED,
            EditorExitGuard.decide(isDirty = false, destination = "import/direct", bypass = false)
        )
        assertEquals(
            EditorExitDecision.PROCEED,
            EditorExitGuard.decide(isDirty = false, destination = null, bypass = false)
        )
    }

    @Test
    fun `confirm action bypasses the guard it just satisfied`() {
        // The discard button re-issues the held navigation with bypass=true;
        // if that were blocked the user could never leave (deadlock).
        assertEquals(
            EditorExitDecision.PROCEED,
            EditorExitGuard.decide(isDirty = true, destination = "import/direct", bypass = true)
        )
        assertEquals(
            EditorExitDecision.PROCEED,
            EditorExitGuard.decide(isDirty = true, destination = null, bypass = true)
        )
    }

    @Test
    fun `navigation within the editor is not blocked`() {
        assertEquals(
            EditorExitDecision.PROCEED,
            EditorExitGuard.decide(isDirty = true, destination = editorRoute, bypass = false)
        )
        assertTrue(EditorExitGuard.isEditorRoute(editorRoute))
        assertTrue(EditorExitGuard.isEditorRoute("editor?questionId=q2"))
        assertTrue(EditorExitGuard.isEditorRoute("editor"))
        assertFalse(EditorExitGuard.isEditorRoute("import/direct"))
        assertFalse(EditorExitGuard.isEditorRoute("library"))
        assertFalse(EditorExitGuard.isEditorRoute(null))
    }

    @Test
    fun `guard is a pure decision and leaves the dirty flag untouched`() {
        EditorDirtyGuard.setDirty(true)
        val decision = EditorExitGuard.decide(
            isDirty = EditorDirtyGuard.isDirty.value,
            destination = "import/direct",
            bypass = false
        )
        assertEquals(EditorExitDecision.CONFIRM, decision)
        assertTrue(EditorDirtyGuard.isDirty.value)
        EditorDirtyGuard.clear()
        assertFalse(EditorDirtyGuard.isDirty.value)
    }

    @Test
    fun `held exit destination survives a rotation round trip`() {
        // The dialog's pending navigation is saveable so rotating while it is
        // up keeps the staged SEND/import payload deliverable.
        assertEquals(null, decodePendingExit(encodePendingExit(null)))
        assertEquals("", encodePendingExit(null))
        listOf("import/direct", "editor?questionId=q1", "library").forEach { route ->
            assertEquals(route, decodePendingExit(encodePendingExit(route)))
        }
        // An empty saved slot decodes back to "nothing pending".
        assertEquals(null, decodePendingExit(""))
    }
}
