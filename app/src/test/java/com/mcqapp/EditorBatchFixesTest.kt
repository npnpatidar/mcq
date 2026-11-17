package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Marks
import com.mcqapp.ui.editor.EditorUiState
import com.mcqapp.ui.editor.ImageTarget
import com.mcqapp.ui.editor.OptionEditorState
import com.mcqapp.ui.editor.applyImageTarget
import com.mcqapp.ui.editor.editorSnapshot
import com.mcqapp.ui.editor.optionOrdinalLabel
import com.mcqapp.ui.editor.updateTableAt
import com.mcqapp.ui.editor.withCell
import com.mcqapp.ui.editor.withColumnAdded
import com.mcqapp.ui.editor.withColumnRemoved
import com.mcqapp.ui.editor.withRowAdded
import com.mcqapp.ui.editor.withRowRemoved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-logic coverage for the question editor audit fixes: dirty snapshot
 * identity, marks validation, identity-based image write-back, table bounds
 * and option labels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorBatchFixesTest {

    private fun option(id: String, vararg elements: String, blockIds: List<String> = emptyList()) =
        OptionEditorState(
            id = id,
            elements = elements.map { ContentElement.TextElement(it) },
            blockIds = blockIds.ifEmpty { List(elements.size) { "${id}-b$it" } },
            isCorrect = id == "a"
        )

    private fun state(
        elements: List<ContentElement> = listOf(ContentElement.TextElement("Question")),
        blockIds: List<String> = List(elements.size) { "b$it" },
        options: List<OptionEditorState> = listOf(option("a", "One"), option("b", "Two")),
        explanationElements: List<ContentElement> = emptyList(),
        explanationBlockIds: List<String> = emptyList(),
        marks: String = "1"
    ) = EditorUiState(
        loading = false,
        questionId = "q",
        elements = elements,
        blockIds = blockIds,
        options = options,
        explanationElements = explanationElements,
        explanationBlockIds = explanationBlockIds,
        marks = marks
    )

    // ---- snapshot ----

    @Test
    fun snapshotDoesNotCollideWhenContentContainsTheSeparator() {
        val left = state(
            elements = listOf(
                ContentElement.TextElement("a\u0000b"),
                ContentElement.TextElement("c")
            )
        )
        val right = state(
            elements = listOf(
                ContentElement.TextElement("a"),
                ContentElement.TextElement("\u0000bc")
            )
        )
        assertFalse(editorSnapshot(left) == editorSnapshot(right))
    }

    @Test
    fun snapshotDistinguishesEmptyBlockListsFromOneEmptyBlock() {
        assertFalse(editorSnapshot(state(elements = emptyList())) ==
            editorSnapshot(state(elements = listOf(ContentElement.TextElement("")))))
    }

    @Test
    fun snapshotPreservesSignificantWhitespace() {
        val withSpace = state(elements = listOf(ContentElement.TextElement("a ")))
        val without = state(elements = listOf(ContentElement.TextElement("a")))
        assertFalse(editorSnapshot(withSpace) == editorSnapshot(without))
    }

    @Test
    fun snapshotIgnoresPendingImageTargetAndErrors() {
        val base = state()
        val decorated = base.copy(
            pendingImageTarget = ImageTarget.Body("b0"),
            imageError = "boom",
            moveError = "nope"
        )
        assertEquals(editorSnapshot(base), editorSnapshot(decorated))
    }

    // ---- marks ----

    @Test
    fun marksAcceptsBlankAndNonNegativeNumbers() {
        assertTrue(Marks.isValid(""))
        assertTrue(Marks.isValid("   "))
        assertTrue(Marks.isValid("0"))
        assertTrue(Marks.isValid("1.5"))
        assertTrue(Marks.isValid(" 2 "))
        // Owner decision: a comma-locale decimal is valid, as in bulk edit.
        assertTrue(Marks.isValid("1,5"))
        assertEquals(1.5, Marks.parse("1,5"), 0.0)
    }

    @Test
    fun marksRejectsNegativeAndNonNumeric() {
        assertFalse(Marks.isValid("-1"))
        assertFalse(Marks.isValid("abc"))
        assertFalse(Marks.isValid("NaN"))
        assertFalse(Marks.isValid("Infinity"))
        assertFalse(Marks.isValid("1e999"))
    }

    @Test
    fun newQuestionDefaultsCannotSaveUntilFilled() {
        // The new-question form starts blank: one empty body block and two
        // empty options, so Save must stay gated (it used to be gated only by
        // an opaque flag, with no reason shown).
        val blank = EditorUiState()
        assertTrue(blank.isNew)
        assertFalse(blank.canSave)

        val filled = state()
        assertTrue(filled.canSave)
        assertFalse(filled.copy(options = filled.options.take(1)).canSave)
        assertFalse(filled.copy(elements = listOf(ContentElement.TextElement("  "))).canSave)
    }

    @Test
    fun invalidMarksBlocksSave() {
        val invalid = state(marks = "abc")
        assertFalse(invalid.marksValid)
        assertFalse(invalid.canSave)
        assertTrue(state(marks = "").canSave)
        assertTrue(state(marks = "2").canSave)
    }

    // ---- image targets ----

    @Test
    fun bodyImageTargetFollowsTheBlockIdAfterReorder() {
        val before = state(
            elements = listOf(ContentElement.TextElement("Q"), ContentElement.TextElement("extra")),
            blockIds = listOf("b0", "b1")
        )
        // The learner moved "extra" above the question while the picker was open.
        val moved = before.copy(
            elements = listOf(ContentElement.TextElement("extra"), ContentElement.TextElement("Q")),
            blockIds = listOf("b1", "b0")
        )
        val after = applyImageTarget(moved, ImageTarget.Body("b0"), "img.png")
        assertEquals(ContentElement.ImageElement("img.png"), after.elements[1])
        assertEquals(ContentElement.TextElement("extra"), after.elements[0])
    }

    @Test
    fun staleBodyImageTargetWritesNothing() {
        val before = state()
        val after = applyImageTarget(before, ImageTarget.Body("gone"), "img.png")
        assertSame(before, after)
    }

    @Test
    fun optionImageTargetWritesOnlyThatOptionsBlock() {
        val first = option("a", "One", blockIds = listOf("a-b0"))
        val second = option("b", "Two", blockIds = listOf("b-b0"))
        val before = state(options = listOf(first, second))
        val after = applyImageTarget(before, ImageTarget.Option("b", "b-b0"), "img.png")
        assertEquals(listOf(ContentElement.ImageElement("img.png")), after.options[1].elements)
        assertEquals(listOf(ContentElement.TextElement("One")), after.options[0].elements)
    }

    @Test
    fun optionImageTargetWithUnknownOptionWritesNothing() {
        val before = state()
        assertSame(before, applyImageTarget(before, ImageTarget.Option("zz", "b0"), "img.png"))
    }

    @Test
    fun explanationImageTargetFollowsTheBlockId() {
        val before = state(
            explanationElements = listOf(
                ContentElement.TextElement("why"),
                ContentElement.TextElement("more")
            ),
            explanationBlockIds = listOf("e0", "e1")
        )
        val after = applyImageTarget(before, ImageTarget.Explanation("e1"), "img.png")
        assertEquals(ContentElement.ImageElement("img.png"), after.explanationElements[1])
        assertEquals(ContentElement.TextElement("why"), after.explanationElements[0])
    }

    // ---- table bounds ----

    @Test
    fun tableCellWriteOutsideTheGridIsIgnored() {
        val table = ContentElement.TableElement(listOf(listOf("a", "b")))
        assertSame(table, table.withCell(0, 2, "x"))
        assertSame(table, table.withCell(1, 0, "x"))
        assertEquals("x", (table.withCell(0, 1, "x") as ContentElement.TableElement).rows[0][1])
    }

    @Test
    fun lastRowAndColumnAreNeverRemoved() {
        val table = ContentElement.TableElement(listOf(listOf("a")))
        assertSame(table, table.withRowRemoved(0))
        assertSame(table, table.withColumnRemoved(0))

        val grown = table.withRowAdded().withColumnAdded()
        val twoByTwo = grown as ContentElement.TableElement
        assertEquals(2, twoByTwo.rows.size)
        assertEquals(2, twoByTwo.rows[0].size)
        assertEquals(1, (twoByTwo.withRowRemoved(1) as ContentElement.TableElement).rows.size)
        assertEquals(1, (twoByTwo.withColumnRemoved(0) as ContentElement.TableElement).rows[0].size)
        // Out-of-range indices are no-ops too, never a crash.
        assertSame(twoByTwo, twoByTwo.withRowRemoved(5))
        assertSame(twoByTwo, twoByTwo.withColumnRemoved(5))
    }

    @Test
    fun tableUpdateLeavesNonTableBlocksAlone() {
        val elements = listOf(ContentElement.TextElement("x"), ContentElement.TableElement(listOf(listOf(""))))
        assertEquals(elements, updateTableAt(elements, 0) { it.withRowAdded() })
        val updated = updateTableAt(elements, 1) { it.withRowAdded() }
        assertEquals(2, (updated[1] as ContentElement.TableElement).rows.size)
    }

    // ---- labels ----

    @Test
    fun optionLabelsAreOrdinalNotInternalIds() {
        assertEquals("A", optionOrdinalLabel(0))
        assertEquals("B", optionOrdinalLabel(1))
        assertEquals("Z", optionOrdinalLabel(25))
        assertEquals("AA", optionOrdinalLabel(26))
        assertEquals("AB", optionOrdinalLabel(27))
        assertEquals("A", optionOrdinalLabel(-3))
    }
}
