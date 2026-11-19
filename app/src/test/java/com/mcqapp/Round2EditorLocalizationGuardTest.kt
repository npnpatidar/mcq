package com.mcqapp

import com.mcqapp.domain.Difficulty
import com.mcqapp.ui.editor.EditorDirtyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-2 E8: difficulty display copy is localized through string resources,
 * while the stored token used by imports/FKs stays an English literal.
 * Round-2 E4: the editor's dirty flag is exposed app-wide for navigation to
 * consult, and is cleared when the editor leaves.
 */
class Round2EditorLocalizationGuardTest {

    @Before
    fun resetGuard() = EditorDirtyGuard.clear()

    @Test
    fun difficultyTokensAreUnchangedAndStillParse() {
        assertEquals("Easy", Difficulty.EASY.label)
        assertEquals("Medium", Difficulty.MEDIUM.label)
        assertEquals("Hard", Difficulty.HARD.label)
        assertEquals(Difficulty.EASY, Difficulty.fromLabel("Easy"))
        assertEquals(Difficulty.HARD, Difficulty.fromLabel("hard"))
        assertEquals(Difficulty.MEDIUM, Difficulty.fromLabel("not-a-difficulty"))
    }

    @Test
    fun eachDifficultyHasItsOwnDisplayNameResource() {
        val resources = Difficulty.entries.map { it.labelRes }
        assertEquals(3, resources.toSet().size)
        resources.forEach { assertTrue("a real resource id", it != 0) }
        assertEquals(com.mcqapp.R.string.r2_editor_difficulty_easy, Difficulty.EASY.labelRes)
        assertEquals(com.mcqapp.R.string.r2_editor_difficulty_medium, Difficulty.MEDIUM.labelRes)
        assertEquals(com.mcqapp.R.string.r2_editor_difficulty_hard, Difficulty.HARD.labelRes)
    }

    @Test
    fun dirtyGuardMirrorsTheEditorAndClearsOnDispose() {
        assertFalse(EditorDirtyGuard.isDirty.value)
        EditorDirtyGuard.setDirty(true)
        assertTrue(EditorDirtyGuard.isDirty.value)
        EditorDirtyGuard.setDirty(false)
        assertFalse(EditorDirtyGuard.isDirty.value)
        EditorDirtyGuard.setDirty(true)
        EditorDirtyGuard.clear()
        assertFalse(EditorDirtyGuard.isDirty.value)
    }
}
