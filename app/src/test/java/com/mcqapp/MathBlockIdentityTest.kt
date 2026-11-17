package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.EditorUiState
import com.mcqapp.ui.editor.ImageTarget
import com.mcqapp.ui.editor.MathTarget
import com.mcqapp.ui.editor.OptionEditorState
import com.mcqapp.ui.editor.applyMathTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-JVM regression for review-reports/08 H5.
 *
 * A MathLive WebView is keyed by its stable block id and its JS bridge is
 * installed once by AndroidView.factory. The old bridge captured the block's
 * list position, so after two formulas were reordered, typing in the moved one
 * overwrote the block that had been at the old index. The fix carries the
 * stable id through the callback and resolves the current index in
 * [applyMathTarget], exactly as picked images are resolved by [ImageTarget].
 */
class MathBlockIdentityTest {

    private fun math(ml: String) = ContentElement.MathElement(ml)

    private val x = "<math><mi>x</mi></math>"
    private val y = "<math><mi>y</mi></math>"
    private val z = "<math><mi>z</mi></math>"

    @Test
    fun mathEditResolvesTheCurrentIndexFromTheStableBlockIdAfterReorder() {
        val original = EditorUiState(
            loading = false,
            elements = listOf(math(x), math(y)),
            blockIds = listOf("b0", "b1")
        )
        // The learner moves b0 below b1. The WebView keyed by b0 moves with it,
        // but its already-installed bridge still emits b0's formula edits.
        val moved = original.copy(
            elements = listOf(original.elements[1], original.elements[0]),
            blockIds = listOf("b1", "b0")
        )

        val edited = applyMathTarget(moved, MathTarget.Body("b0"), z)

        assertEquals(math(y), edited.elements[0])
        assertEquals(math(z), edited.elements[1])
    }

    @Test
    fun positionalCallbackWouldHaveCorruptedTheNeighbourAfterReorder() {
        val moved = EditorUiState(
            loading = false,
            elements = listOf(math(y), math(x)),
            blockIds = listOf("b1", "b0")
        )
        // What the old code did: the callback captured index 0 and wrote z into
        // whatever now sat there — b1's formula, not the b0 block being typed.
        val oldBehaviour = moved.elements.toMutableList().also { it[0] = math(z) }
        val fixed = applyMathTarget(moved, MathTarget.Body("b0"), z)

        assertEquals(math(z), oldBehaviour[0])
        assertNotEquals(oldBehaviour, fixed.elements)
        assertEquals(math(y), fixed.elements[0])
        assertEquals(math(z), fixed.elements[1])
    }

    @Test
    fun staleMathBlockIdWritesNothing() {
        val before = EditorUiState(
            loading = false,
            elements = listOf(math(x)),
            blockIds = listOf("b0")
        )
        // The block was deleted while the WebView was still mounted.
        assertSame(before, applyMathTarget(before, MathTarget.Body("gone"), z))
        assertSame(before, applyMathTarget(before, MathTarget.Option("missing", "b0"), z))
        assertSame(before, applyMathTarget(before, MathTarget.Explanation("gone"), z))
    }

    @Test
    fun optionMathEditResolvesWithinTheAddressedOptionAfterReorder() {
        val option = OptionEditorState(
            id = "a",
            elements = listOf(math(x), math(y)),
            blockIds = listOf("a-b0", "a-b1")
        )
        val other = OptionEditorState(
            id = "b",
            elements = listOf(math("<math><mi>q</mi></math>")),
            blockIds = listOf("b-b0")
        )
        val moved = EditorUiState(
            loading = false,
            options = listOf(
                option.copy(
                    elements = listOf(option.elements[1], option.elements[0]),
                    blockIds = listOf("a-b1", "a-b0")
                ),
                other
            )
        )

        val edited = applyMathTarget(moved, MathTarget.Option("a", "a-b0"), z)

        assertEquals(math(y), edited.options[0].elements[0])
        assertEquals(math(z), edited.options[0].elements[1])
        // Another option's block with the same local id is untouched.
        assertEquals(other.elements, edited.options[1].elements)
    }

    @Test
    fun explanationMathEditResolvesFromExplanationBlockIds() {
        val before = EditorUiState(
            loading = false,
            explanationElements = listOf(math(x), math(y)),
            explanationBlockIds = listOf("e0", "e1")
        )
        val moved = before.copy(
            explanationElements = listOf(before.explanationElements[1], before.explanationElements[0]),
            explanationBlockIds = listOf("e1", "e0")
        )

        val edited = applyMathTarget(moved, MathTarget.Explanation("e0"), z)

        assertEquals(math(y), edited.explanationElements[0])
        assertEquals(math(z), edited.explanationElements[1])
        assertEquals(before.elements, edited.elements)
    }
}
