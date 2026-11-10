package com.mcqapp

import com.mcqapp.domain.BlueprintSectionView
import com.mcqapp.ui.library.orphanedSections
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure editor logic: a section whose category left the paper is reported, not silently dropped. */
class BlueprintEditorLogicTest {

    private fun view(
        id: String,
        available: Boolean = true,
        children: List<BlueprintSectionView> = emptyList()
    ) = BlueprintSectionView(
        categoryId = id,
        title = id,
        parts = 1,
        pool = 10,
        available = available,
        children = children
    )

    @Test
    fun movedSectionsAreListedAndLiveOnesAreNot() {
        val tree = listOf(
            view("live"),
            view("parent", children = listOf(view("live-child"), view("moved-child", available = false)))
        )
        assertEquals(listOf("moved-child"), orphanedSections(tree).map { it.categoryId })
    }

    @Test
    fun movedSubtreeIsFullyReported() {
        val tree = listOf(
            view("moved", available = false, children = listOf(view("deep", available = false)))
        )
        assertEquals(listOf("moved", "deep"), orphanedSections(tree).map { it.categoryId })
    }

    @Test
    fun aFullyAvailableTreeHasNoOrphans() {
        assertEquals(emptyList<String>(), orphanedSections(listOf(view("a"), view("b"))).map { it.categoryId })
    }
}
