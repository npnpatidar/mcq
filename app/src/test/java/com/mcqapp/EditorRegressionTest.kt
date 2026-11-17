package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Marks
import com.mcqapp.ui.editor.EditorUiState
import com.mcqapp.ui.editor.dirtyAfterSave
import com.mcqapp.ui.editor.editorSnapshot
import com.mcqapp.ui.editor.canRemoveColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression tests for the editor review-fix batch: marks parsing
 * (decimal comma), dirty recomputation after a save, and the table column
 * removal rule shared by the model and its UI control.
 */
class EditorRegressionTest {

    // ---- marks: comma decimal, same rule as bulk edit ----

    @Test
    fun marksAcceptACommaDecimalSeparator() {
        assertTrue(Marks.isValid("1,5"))
        assertTrue(Marks.isValid(" 0,25 "))
        assertEquals(1.5, Marks.parse("1,5"), 0.0)
        assertEquals(0.25, Marks.parse("0,25"), 0.0)
        // A dot still works, so both keyboard locales save.
        assertTrue(Marks.isValid("1.5"))
        assertEquals(1.5, Marks.parse("1.5"), 0.0)
    }

    @Test
    fun marksStillRejectNegativeNonNumericAndNonFinite() {
        assertFalse(Marks.isValid("-1"))
        assertFalse(Marks.isValid("-1,5"))
        assertFalse(Marks.isValid("abc"))
        assertFalse(Marks.isValid("NaN"))
        assertFalse(Marks.isValid("Infinity"))
        assertFalse(Marks.isValid("1e999"))
        assertTrue("blank still means 1 mark", Marks.isValid(""))
        assertEquals(1.0, Marks.parse("-1"), 0.0)
        assertEquals(1.0, Marks.parse("abc"), 0.0)
    }

    // ---- dirty recomputation: edits during a write are not dropped ----

    @Test
    fun aFormEditedAfterTheSnapshotStaysDirty() {
        val clean = EditorUiState(marks = "1")
        val signature = editorSnapshot(clean)
        assertFalse("nothing changed since the snapshot", dirtyAfterSave(clean, signature))

        // The keystroke lands while the async write is in flight, after the
        // signature was captured but before markClean runs.
        val edited = clean.copy(marks = "2")
        assertTrue("the unpersisted keystroke must stay dirty", dirtyAfterSave(edited, signature))

        // Reverting to exactly the persisted content is clean again.
        assertFalse(dirtyAfterSave(clean.copy(), signature))
    }

    // ---- table column removal: UI button agrees with the data rule ----

    @Test
    fun columnRemovalRuleMatchesTheNoOpCases() {
        val single = ContentElement.TableElement(listOf(listOf("a")))
        assertFalse(single.canRemoveColumn(0))

        val normal = ContentElement.TableElement(listOf(listOf("a", "b"), listOf("c", "d")))
        assertTrue(normal.canRemoveColumn(0))
        assertTrue(normal.canRemoveColumn(1))
        assertFalse("removing the last column is a no-op", normal.canRemoveColumn(2))

        val ragged = ContentElement.TableElement(listOf(listOf("a", "b", "c"), listOf("d")))
        assertFalse("a short row makes removal a no-op", ragged.canRemoveColumn(2))
        assertTrue("a column present in every row can be removed", ragged.canRemoveColumn(0))
    }
}
